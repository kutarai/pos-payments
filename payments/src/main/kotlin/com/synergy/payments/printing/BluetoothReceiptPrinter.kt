package com.synergy.payments.printing

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.synergy.payments.printing.EscPos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** What a paired printer looks like on the setup screen. */
data class PairedPrinter(val name: String, val address: String)

/** What came of trying to print. Never thrown: a sale is already done and paid for. */
sealed class PrintResult {
    object Printed : PrintResult()
    data class Failed(val reason: String) : PrintResult()
}

/**
 * Prints to a Bluetooth till printer over the serial port profile.
 *
 * Nothing here is allowed to break a sale. By the time a receipt is printed the customer has paid
 * and the sale is recorded; a printer that is switched off, out of paper or out of range is an
 * inconvenience to be reported, never a reason to fail the transaction — so every path returns a
 * result rather than throwing.
 *
 * The connection is opened per receipt rather than held. A till printer is switched off at the
 * end of a shift and moved between counters, and a socket held across that comes back dead in a
 * way that only shows up on the next customer's receipt.
 */
class BluetoothReceiptPrinter(
    private val context: Context,
    private val settings: PrinterSettings
) {

    companion object {
        private const val TAG = "BluetoothPrinter"

        /** The serial port profile every ESC/POS till printer speaks. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val SOCKET_TIMEOUT_MS = 8_000L
    }

    private val adapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    /** Whether the operator has granted what this Android version needs to reach a printer. */
    fun hasPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(context, needed) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    /**
     * Printers already paired with this terminal.
     *
     * Pairing is deliberately left to Android's own Bluetooth settings: it needs a PIN, it is
     * done once per printer, and reimplementing it here would be a worse version of a screen
     * every operator already knows.
     */
    fun pairedPrinters(): List<PairedPrinter> {
        if (!hasPermission()) return emptyList()
        return try {
            adapter?.bondedDevices.orEmpty().map { device ->
                PairedPrinter(name = device.name ?: device.address, address = device.address)
            }.sortedBy { it.name }
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to list paired devices", e)
            emptyList()
        }
    }

    suspend fun printLines(lines: List<String>): PrintResult = withContext(Dispatchers.IO) {
        val address = settings.printerAddress()
            ?: return@withContext PrintResult.Failed("No printer has been chosen yet.")

        if (!hasPermission()) return@withContext PrintResult.Failed(
            "This terminal has not been given permission to use Bluetooth."
        )
        if (!isBluetoothOn()) return@withContext PrintResult.Failed("Bluetooth is switched off.")

        val device: BluetoothDevice = try {
            adapter?.getRemoteDevice(address)
                ?: return@withContext PrintResult.Failed("This terminal has no Bluetooth.")
        } catch (e: IllegalArgumentException) {
            return@withContext PrintResult.Failed("The stored printer address is not valid.")
        }

        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            // Discovery keeps the radio busy and makes connecting fail for no visible reason.
            try { adapter?.cancelDiscovery() } catch (_: SecurityException) {}

            socket.connect()
            socket.outputStream.write(EscPos.document(lines))
            socket.outputStream.flush()

            // The printer buffers; closing immediately can cut the job off mid-receipt.
            Thread.sleep(300)

            PrintResult.Printed
        } catch (e: SecurityException) {
            Log.w(TAG, "Refused permission while printing", e)
            PrintResult.Failed("This terminal has not been given permission to use Bluetooth.")
        } catch (e: IOException) {
            Log.w(TAG, "Could not print", e)
            PrintResult.Failed("Could not reach the printer — check it is switched on and in range.")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected failure while printing", e)
            PrintResult.Failed(e.message ?: "The printer could not be reached.")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
