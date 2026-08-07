package com.synergy.payments.terminal

/**
 * What a payment terminal has to be able to do, whoever makes it.
 *
 * Everything above this line — the card flow, the switch, the QR and mobile-money dialogs, the
 * slip that gets printed — is written against these and against nothing from a vendor's SDK. A
 * second make of terminal is then a matter of implementing this package again, not of copying the
 * payment logic and letting the two drift.
 *
 * The card port itself lives next door in [com.synergy.payments.card.EmvPaymentProcessor], since
 * it carries the EMV request and result types the switch also speaks.
 */

/** Who this terminal is, as the acquirer and the branch know it. */
interface TerminalIdentity {
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

/** Paper out of the unit's own printer, as opposed to a Bluetooth one. */
interface OnboardPrinter {
    suspend fun printLines(lines: List<String>): Boolean
    fun isAvailable(): Boolean
}

/**
 * The pieces of a terminal an application asks for by name.
 *
 * Every one is optional because they genuinely are: a countertop unit has a printer and no
 * scanner, a handheld has both, and a phone acting as a soft terminal has neither. Code that
 * needs one asks and copes with null rather than assuming the hardware it was written on.
 */
interface Terminal {
    val identity: TerminalIdentity
    val pinPad: SecurePinPad?
    val barcodeReader: BarcodeScanner?
    val onboardPrinter: OnboardPrinter?

    /** Brings the hardware up. Called once, and safe to call again. */
    suspend fun connect(): Boolean

    fun isConnected(): Boolean

    suspend fun disconnect()
}
