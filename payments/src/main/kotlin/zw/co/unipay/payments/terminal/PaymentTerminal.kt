package zw.co.unipay.payments.terminal

import kotlinx.coroutines.flow.StateFlow

/**
 * What a payment terminal has to be able to do, whoever makes it.
 *
 * Everything above this line — the card flow, the switch, the QR and mobile-money dialogs, the
 * slip that gets printed — is written against these and against nothing from a vendor's SDK. A
 * second make of terminal is then a matter of implementing this package again, not of copying the
 * payment logic and letting the two drift.
 *
 * The card port itself lives next door in [zw.co.unipay.payments.card.EmvPaymentProcessor], since
 * it carries the EMV request and result types the switch also speaks.
 */

/** Who this terminal is, as the acquirer and the branch know it. */
interface TerminalHardwareIdentity {
    /**
     * The serial printed on the unit.
     *
     * Read from the device rather than configured, because it is what a terminal is registered
     * under and what an activation is matched against — a serial somebody typed is a serial
     * somebody can mistype.
     */
    fun serialNumber(): String

    /** Model name, for a diagnostics screen and for deciding what the hardware can do. */
    fun model(): String
}

/** A PIN entered on hardware the application never sees the digits of. */
interface SecurePinPad {
    /**
     * Asks for a PIN and returns the encrypted block, or null if the customer cancelled.
     *
     * A terminal that returned the digits themselves would put every application that used it
     * inside the cardholder-data boundary, so implementations must not.
     */
    suspend fun requestPin(promptAmount: String, maxLength: Int = 12): ByteArray?
}

/** Where a [PrintItem.Line] sits across the paper. */
enum class PrintAlign { LEFT, CENTER, RIGHT }

/** One thing on a slip from the unit's own printer, in the order it comes out. */
sealed interface PrintItem {
    /**
     * A line of text. With the defaults, exactly what [OnboardPrinter.printLines] prints for the
     * same string.
     */
    data class Line(
        val text: String,
        val align: PrintAlign = PrintAlign.LEFT,
        val bold: Boolean = false,
    ) : PrintItem

    /**
     * One line with [left] at the left edge and [right] at the right — a label and its amount.
     * Laid out by the printer, which knows its own font and width; a caller padding with spaces
     * would only line up on a fixed-width font.
     */
    data class Row(val left: String, val right: String, val bold: Boolean = false) : PrintItem

    /**
     * A QR code encoding [payload], centred on the paper. Its size is the printer's to choose:
     * the point is that it scans, and every make here draws it about 200 dots square.
     */
    data class QrCode(val payload: String) : PrintItem
}

/** Paper out of the unit's own printer, as opposed to a Bluetooth one. */
interface OnboardPrinter {
    suspend fun printLines(lines: List<String>): Boolean
    fun isAvailable(): Boolean

    /**
     * Whether [print] can put a [PrintItem.QrCode] on paper. False unless the implementation
     * says otherwise, so a printer written before QR codes existed answers truthfully.
     */
    val printsQrCodes: Boolean get() = false

    /**
     * Prints [items] in order as one job, and returns whether the printer accepted it.
     *
     * Bold and alignment are presentation: a printer that cannot honour them prints the same
     * words plainly (this default prints a [PrintItem.Row] as its two halves separated by a
     * space). A QR code is content: a printer that cannot draw QR codes refuses a job containing
     * one — returns false and prints nothing — rather than leaving the code out, because a
     * receipt whose QR code is the proof of payment is worse without it than not printed, and
     * only the caller knows which kind it has. A caller content to go without asks
     * [printsQrCodes] first and leaves the codes out itself.
     */
    suspend fun print(items: List<PrintItem>): Boolean {
        val lines = items.map { item ->
            when (item) {
                is PrintItem.Line -> item.text
                is PrintItem.Row -> "${item.left} ${item.right}"
                is PrintItem.QrCode -> return false
            }
        }
        return printLines(lines)
    }
}

/**
 * The pieces of a terminal an application asks for by name.
 *
 * Every one is optional because they genuinely are: a countertop unit has a printer and no
 * scanner, a handheld has both, and a phone acting as a soft terminal has neither. Code that
 * needs one asks and copes with null rather than assuming the hardware it was written on.
 */
interface Terminal {
    val identity: TerminalHardwareIdentity
    val pinPad: SecurePinPad?
    val barcodeReader: BarcodeScanner?
    val onboardPrinter: OnboardPrinter?

    /**
     * Whether the hardware is usable right now, observable so a screen can show it without polling.
     *
     * Unlike the peripherals above this is never null: every terminal has a connection state, even
     * if it is permanently [TerminalConnection.CONNECTED].
     */
    val connection: StateFlow<TerminalConnection>

    /** Brings the hardware up. Called once, and safe to call again. */
    suspend fun connect(): Boolean

    fun isConnected(): Boolean

    suspend fun disconnect()
}
