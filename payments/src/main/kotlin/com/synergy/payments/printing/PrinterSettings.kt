package com.synergy.payments.printing

import android.content.Context

/**
 * Which printer this terminal prints to, and how wide its paper is.
 *
 * Kept on the device rather than in the merchant configuration that comes down from the branch:
 * a printer is paired with one till at one counter, and a branch that pushed a printer address to
 * every terminal would have them all trying to reach the same one.
 */
class PrinterSettings(context: Context) {

    private val prefs = context.getSharedPreferences("synergy.printer", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val KEY_WIDTH = "width"

        /** 58mm paper, which is what a hand-held till printer takes. */
        const val NARROW_ROLL = 32

        /** 80mm paper, the counter-top size. */
        const val WIDE_ROLL = 48

    }

    fun printerAddress(): String? = prefs.getString(KEY_ADDRESS, null)

    fun printerName(): String? = prefs.getString(KEY_NAME, null)

    fun paperWidth(): Int = prefs.getInt(KEY_WIDTH, NARROW_ROLL)

    fun isConfigured(): Boolean = !printerAddress().isNullOrBlank()

    fun choosePrinter(printer: PairedPrinter, paperWidth: Int) {
        prefs.edit()
            .putString(KEY_ADDRESS, printer.address)
            .putString(KEY_NAME, printer.name)
            .putInt(KEY_WIDTH, paperWidth)
            .apply()
    }

    fun forgetPrinter() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
    }
}
