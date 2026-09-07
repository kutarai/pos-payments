package zw.co.unipay.payments.printing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

/**
 * A printer seen over Wi-Fi Direct.
 *
 * [isPrinter] is what the peer says it is in its WPS device type. It is a hint for the setup
 * screen rather than a filter: plenty of till printers report themselves as something else, and a
 * list that hid them would leave the operator with nothing to choose.
 */
data class WifiDirectPrinter(
    val name: String,
    val address: String,
    val isPrinter: Boolean = false
)

/**
 * Prints to a Wi-Fi Direct till printer, which takes the same ESC/POS bytes as the Bluetooth ones
 * on a raw TCP port.
 *
 * Two kinds of printer are sold as "Wi-Fi Direct" and both are handled here. The first is a true
 * Wi-Fi P2P peer: it is discovered, a group is formed with it, and it answers at the group owner's
 * address. The second is really a small access point with a fixed address, joined from Android's
 * own Wi-Fi settings; for those the operator types the address and no peer is involved.
 *
 * The group is deliberately formed with a group owner intent of zero. Forming it the other way
 * round works, but leaves the till as owner with no way to learn the address the printer was given,
 * which is a failure that only shows up when a customer is waiting.
 *
 * As with Bluetooth, nothing here is allowed to break a sale: every path returns a [PrintResult].
 */
class WifiDirectReceiptPrinter(
    context: Context,
    private val settings: PrinterSettings
) : ReceiptPrinter {

    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "WifiDirectPrinter"

        /** How long a scan runs. Peers answer a probe in their own time; a short scan misses them. */
        const val DISCOVERY_MS = 12_000L

        private const val POLL_MS = 1_500L

        /** How long the printer is given to finish forming the group before printing gives up. */
        private const val GROUP_TIMEOUT_MS = 25_000L

        private const val SOCKET_TIMEOUT_MS = 8_000

        /** Category 3 in a WPS device type is a printer. */
        private const val PRINTER_CATEGORY = "3-"
    }

    private val manager: WifiP2pManager? =
        this.context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null

    /** Whether this terminal has a Wi-Fi Direct radio at all. */
    fun isSupported(): Boolean = manager != null

    /** Whether the operator has granted what this Android version needs to find nearby printers. */
    fun hasPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            // Before Android 13 a peer scan counted as locating the operator, and was refused
            // without this even though nothing here wants their whereabouts.
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, needed) == PackageManager.PERMISSION_GRANTED
    }

    /** What to ask for, so the screen does not have to know which Android version needs what. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Wi-Fi Direct rides on the Wi-Fi radio: switched off, there is nothing to discover. */
    fun isWifiOn(): Boolean =
        (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true

    /**
     * Printers within range, as a single scan sees them.
     *
     * The whole window is used rather than returning on the first answer: peers reply at their own
     * pace, and a scan that stopped early would show the operator one printer of the three on the
     * counter and give them no reason to think anything was missing.
     */
    suspend fun discoverPrinters(timeoutMs: Long = DISCOVERY_MS): List<WifiDirectPrinter> {
        val manager = manager ?: return emptyList()
        val channel = channel() ?: return emptyList()
        if (!hasPermission() || !isWifiOn()) return emptyList()

        try {
            manager.discoverPeers(channel, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to discover peers", e)
            return emptyList()
        }

        val found = LinkedHashMap<String, WifiDirectPrinter>()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_MS)
            peers(manager, channel).forEach { found[it.address] = it }
        }

        try {
            manager.stopPeerDiscovery(channel, null)
        } catch (_: SecurityException) {
            // Discovery times out on its own; leaving it running only costs battery.
        }

        return found.values.sortedWith(
            compareByDescending<WifiDirectPrinter> { it.isPrinter }.thenBy { it.name.lowercase() }
        )
    }

    override suspend fun printLines(lines: List<String>): PrintResult = withContext(Dispatchers.IO) {
        when (val endpoint = endpoint()) {
            is Endpoint.Unreachable -> PrintResult.Failed(endpoint.reason)
            is Endpoint.At -> send(endpoint.host, settings.wifiDirectPort(), lines)
        }
    }

    /** Where this till's printer answers, once whatever has to happen first has happened. */
    private sealed class Endpoint {
        data class At(val host: String) : Endpoint()
        data class Unreachable(val reason: String) : Endpoint()
    }

    private suspend fun endpoint(): Endpoint {
        val fixedHost = settings.wifiDirectHost()
        val peer = settings.wifiDirectAddress()
            ?: return fixedHost?.let { Endpoint.At(it) }
                ?: Endpoint.Unreachable("No printer has been chosen yet.")

        if (!hasPermission()) return Endpoint.Unreachable(
            "This terminal has not been given permission to reach nearby printers."
        )
        if (!isWifiOn()) return Endpoint.Unreachable("Wi-Fi is switched off.")

        val manager = manager ?: return Endpoint.Unreachable("This terminal has no Wi-Fi Direct.")
        val channel = channel() ?: return Endpoint.Unreachable("This terminal has no Wi-Fi Direct.")

        // Already in a group with this printer — the usual case between two receipts.
        val standing = connectionInfo(manager, channel)
        if (standing == null || !standing.groupFormed || !joinedTo(peer, manager, channel)) {
            if (!connect(peer, manager, channel)) return Endpoint.Unreachable(
                "Could not reach the printer — check it is switched on and in range."
            )
        }

        val info = awaitGroup(manager, channel) ?: return Endpoint.Unreachable(
            "The printer did not answer — check it is switched on and in range."
        )

        // A fixed address wins once the group is up: some printers form the group but answer on an
        // address of their own rather than the group owner's.
        if (fixedHost != null) return Endpoint.At(fixedHost)

        if (info.isGroupOwner) return Endpoint.Unreachable(
            "This till ended up as the group owner, so the printer's address is not known. " +
                "Enter the printer's address on the printer screen."
        )

        val host = info.groupOwnerAddress?.hostAddress
            ?: return Endpoint.Unreachable("The printer did not give an address.")
        return Endpoint.At(host)
    }

    private fun send(host: String, port: Int, lines: List<String>): PrintResult {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            socket.getOutputStream().apply {
                write(EscPos.document(lines))
                flush()
            }

            // The printer buffers; closing immediately can cut the job off mid-receipt.
            Thread.sleep(300)

            PrintResult.Printed
        } catch (e: IOException) {
            Log.w(TAG, "Could not print to $host:$port", e)
            PrintResult.Failed("Could not reach the printer — check it is switched on and in range.")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected failure while printing", e)
            PrintResult.Failed(e.message ?: "The printer could not be reached.")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    @Synchronized
    private fun channel(): WifiP2pManager.Channel? {
        val manager = manager ?: return null
        // The main looper is passed explicitly, so this is safe to reach from the printing thread.
        return channel ?: manager.initialize(context, Looper.getMainLooper(), null)
            .also { channel = it }
    }

    private suspend fun peers(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): List<WifiDirectPrinter> = suspendCancellableCoroutine { continuation ->
        try {
            manager.requestPeers(channel) { peers ->
                if (continuation.isActive) {
                    continuation.resume(peers.deviceList.orEmpty().map { it.asPrinter() })
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to list peers", e)
            if (continuation.isActive) continuation.resume(emptyList())
        }
    }

    private fun WifiP2pDevice.asPrinter() = WifiDirectPrinter(
        name = (deviceName ?: "").ifBlank { deviceAddress },
        address = deviceAddress,
        isPrinter = primaryDeviceType?.startsWith(PRINTER_CATEGORY) == true
    )

    private suspend fun connect(
        peer: String,
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val config = WifiP2pConfig().apply {
            deviceAddress = peer
            wps.setup = WpsInfo.PBC
            // Nought: let the printer own the group, so its address is the one Android reports.
            groupOwnerIntent = 0
        }
        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Could not ask for a group with $peer, reason $reason")
                    if (continuation.isActive) continuation.resume(false)
                }
            })
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to connect to $peer", e)
            if (continuation.isActive) continuation.resume(false)
        }
    }

    /**
     * Waits for the group to actually form. [WifiP2pManager.connect] reporting success only means
     * the request was accepted; the printer has yet to be asked at that point.
     */
    private suspend fun awaitGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): WifiP2pInfo? {
        val deadline = SystemClock.elapsedRealtime() + GROUP_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val info = connectionInfo(manager, channel)
            if (info != null && info.groupFormed) return info
            delay(500)
        }
        return null
    }

    private suspend fun joinedTo(
        peer: String,
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            manager.requestGroupInfo(channel) { group ->
                val members = group?.let { it.clientList.orEmpty() + listOfNotNull(it.owner) }
                val joined = members?.any { it.deviceAddress.equals(peer, ignoreCase = true) } == true
                if (continuation.isActive) continuation.resume(joined)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to read the current group", e)
            if (continuation.isActive) continuation.resume(false)
        }
    }

    private suspend fun connectionInfo(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): WifiP2pInfo? = suspendCancellableCoroutine { continuation ->
        try {
            manager.requestConnectionInfo(channel) { info ->
                if (continuation.isActive) continuation.resume(info)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to read the connection", e)
            if (continuation.isActive) continuation.resume(null)
        }
    }
}
