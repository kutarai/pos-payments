package zw.co.unipay.payments.printing

/** What came of trying to print. Never thrown: a sale is already done and paid for. */
sealed class PrintResult {
    object Printed : PrintResult()
    data class Failed(val reason: String) : PrintResult()
}

/**
 * A till printer, whatever radio it is reached over.
 *
 * The selling side of an application only ever has finished lines and needs to know whether they
 * came out. Which transport carries them is a property of the counter the till stands on — settled
 * once on the setup screen, kept in [PrinterSettings] — so it is deliberately not visible here.
 *
 * Every implementation reports failure as a [PrintResult] rather than throwing: by the time a
 * receipt is printed the customer has paid and the sale is recorded.
 */
interface ReceiptPrinter {
    suspend fun printLines(lines: List<String>): PrintResult
}

/**
 * Prints to whichever printer this till has been set up with.
 *
 * The transport is read per receipt rather than fixed at construction, because an operator can
 * change it on the setup screen mid-shift and the next receipt has to go to the printer they just
 * chose, not the one that was chosen when the application started.
 */
class TillReceiptPrinter(
    private val settings: PrinterSettings,
    private val bluetooth: BluetoothReceiptPrinter,
    private val wifiDirect: WifiDirectReceiptPrinter
) : ReceiptPrinter {

    override suspend fun printLines(lines: List<String>): PrintResult =
        when (settings.transport()) {
            PrinterTransport.BLUETOOTH -> bluetooth.printLines(lines)
            PrinterTransport.WIFI_DIRECT -> wifiDirect.printLines(lines)
        }
}
