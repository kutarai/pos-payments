package zw.co.unipay.payments.printing

import android.content.Context

/** How this till reaches its printer. */
enum class PrinterTransport {
    /** A printer paired over Bluetooth, speaking the serial port profile. */
    BLUETOOTH,

    /** A printer reached over Wi-Fi Direct, taking ESC/POS on a raw TCP port. */
    WIFI_DIRECT;

    companion object {
        /**
         * Reads back a stored name. Anything unrecognised — an older install that stored nothing,
         * a value from a newer build that has been rolled back — means Bluetooth, which is what
         * every till in the estate had before there was a choice.
         */
        fun named(stored: String?): PrinterTransport =
            values().firstOrNull { it.name == stored } ?: BLUETOOTH
    }
}

/**
 * Which printer this terminal prints to, how it is reached, and how wide its paper is.
 *
 * Kept on the device rather than in the merchant configuration that comes down from the branch:
 * a printer stands at one counter with one till, and a branch that pushed a printer address to
 * every terminal would have them all trying to reach the same one.
 *
 * Each transport keeps its own keys. An operator who moves a counter from a Bluetooth printer to
 * a Wi-Fi Direct one and back again finds the first printer still chosen, rather than having to
 * pair it a second time.
 */
class PrinterSettings(context: Context) {

    // The old coordinate, kept deliberately. This names a file on the device, not a class: every
    // terminal already in a shop has its printer choice stored under it, and renaming it would
    // not fail to compile — it would silently open an empty file, and the first thing an
    // operator would know about it is a receipt that did not print. Moving it needs a migration
    // that reads the old name once and writes the new one, not a rename.
    private val prefs = context.getSharedPreferences("synergy.printer", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val KEY_WIDTH = "width"

        private const val KEY_WIFI_ADDRESS = "wifi_address"
        private const val KEY_WIFI_NAME = "wifi_name"
        private const val KEY_WIFI_HOST = "wifi_host"
        private const val KEY_WIFI_PORT = "wifi_port"

        /** 58mm paper, which is what a hand-held till printer takes. */
        const val NARROW_ROLL = 32

        /** 80mm paper, the counter-top size. */
        const val WIDE_ROLL = 48

        /** The raw port nearly every network-capable ESC/POS printer listens on. */
        const val DEFAULT_PRINT_PORT = 9100
    }

    fun transport(): PrinterTransport = PrinterTransport.named(prefs.getString(KEY_TRANSPORT, null))

    fun printerAddress(): String? = prefs.getString(KEY_ADDRESS, null)

    fun printerName(): String? = when (transport()) {
        PrinterTransport.BLUETOOTH -> prefs.getString(KEY_NAME, null)
        PrinterTransport.WIFI_DIRECT -> prefs.getString(KEY_WIFI_NAME, null)
    }

    fun paperWidth(): Int = prefs.getInt(KEY_WIDTH, NARROW_ROLL)

    /**
     * The Wi-Fi Direct peer this till joins to print, as its P2P hardware address.
     *
     * Null when the operator typed an address instead, which is how the soft-access-point printers
     * that call themselves Wi-Fi Direct — and are not Wi-Fi P2P peers at all — are reached.
     */
    fun wifiDirectAddress(): String? = prefs.getString(KEY_WIFI_ADDRESS, null)?.ifBlank { null }

    /** A fixed address for the printer, when one was given, in place of joining a peer. */
    fun wifiDirectHost(): String? = prefs.getString(KEY_WIFI_HOST, null)?.ifBlank { null }

    fun wifiDirectPort(): Int = prefs.getInt(KEY_WIFI_PORT, DEFAULT_PRINT_PORT)

    /** Whether this till has a printer to print to at all. */
    fun isConfigured(): Boolean = when (transport()) {
        PrinterTransport.BLUETOOTH -> !printerAddress().isNullOrBlank()
        PrinterTransport.WIFI_DIRECT -> wifiDirectAddress() != null || wifiDirectHost() != null
    }

    fun choosePrinter(printer: PairedPrinter, paperWidth: Int) {
        prefs.edit()
            .putString(KEY_TRANSPORT, PrinterTransport.BLUETOOTH.name)
            .putString(KEY_ADDRESS, printer.address)
            .putString(KEY_NAME, printer.name)
            .putInt(KEY_WIDTH, paperWidth)
            .apply()
    }

    /**
     * Chooses a Wi-Fi Direct printer, either as a peer to join or as an address to dial.
     *
     * Both are stored when both are known: a peer that is also reachable at a fixed address saves
     * the group having to be formed again on every receipt.
     */
    fun chooseWifiDirectPrinter(
        printer: WifiDirectPrinter?,
        host: String? = null,
        port: Int = DEFAULT_PRINT_PORT,
        paperWidth: Int = paperWidth()
    ) {
        prefs.edit()
            .putString(KEY_TRANSPORT, PrinterTransport.WIFI_DIRECT.name)
            .putString(KEY_WIFI_ADDRESS, printer?.address.orEmpty())
            .putString(KEY_WIFI_NAME, printer?.name ?: host.orEmpty())
            .putString(KEY_WIFI_HOST, host?.trim().orEmpty())
            .putInt(KEY_WIFI_PORT, port)
            .putInt(KEY_WIDTH, paperWidth)
            .apply()
    }

    /** Which transport the setup screen is currently pointing this till at. */
    fun chooseTransport(transport: PrinterTransport) {
        prefs.edit().putString(KEY_TRANSPORT, transport.name).apply()
    }

    /**
     * The paper is a property of the roll in the printer, not of the radio, so it is set on its
     * own — a till that changes from a 58mm hand-held to an 80mm counter-top keeps the printer it
     * had chosen.
     */
    fun setPaperWidth(paperWidth: Int) {
        prefs.edit().putInt(KEY_WIDTH, paperWidth).apply()
    }

    /**
     * Forgets one transport's printer, leaving the other alone.
     *
     * Defaults to the one in use, which is what a till being stood down wants; the setup screen
     * names the transport instead, so an operator can drop a printer from the tab they are looking
     * at without first having to point the till at it.
     */
    fun forgetPrinter(transport: PrinterTransport = transport()) {
        val edit = prefs.edit()
        when (transport) {
            PrinterTransport.BLUETOOTH -> edit.remove(KEY_ADDRESS).remove(KEY_NAME)
            PrinterTransport.WIFI_DIRECT ->
                edit.remove(KEY_WIFI_ADDRESS).remove(KEY_WIFI_NAME).remove(KEY_WIFI_HOST)
        }
        edit.apply()
    }
}
