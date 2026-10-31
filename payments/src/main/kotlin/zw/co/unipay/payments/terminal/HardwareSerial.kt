package zw.co.unipay.payments.terminal

/**
 * Chooses a terminal's serial number from the several places it might be readable.
 *
 * The serial is the terminal's identity: it is what the branch server matches an activation
 * against, and what the operator compares with the serial printed on the unit. So a value that
 * merely looks like a serial is worse than admitting there isn't one — "unknown" would activate
 * nothing, and the model name would have every terminal of that model claiming one identity.
 *
 * Deliberately free of Android imports so the choosing can be tested without a device; the
 * reading of each source stays with each terminal library.
 */
object HardwareSerial {

    /** Values that stand in for a serial rather than being one. */
    private val PLACEHOLDERS = setOf("unknown", "unknown_sn", "null", "none", "n/a", "na")

    /** No vendor serial is this short; anything shorter is junk from a failed read. */
    private const val MIN_LENGTH = 4

    /** SpReadSN fills a fixed-size buffer, so a value can arrive NUL-padded. */
    private val TRIM = charArrayOf(' ', '\t', '\n', '\r', '\u0000')

    fun isPlausible(candidate: String?, model: String): Boolean {
        val value = candidate?.trim(*TRIM).orEmpty()
        if (value.length < MIN_LENGTH) return false
        if (value.lowercase() in PLACEHOLDERS) return false
        if (model.isNotBlank() && value.equals(model.trim(), ignoreCase = true)) return false
        // Some units answer a run of zeroes when the serial was never programmed.
        if (value.all { it == '0' }) return false
        return true
    }

    /** The first candidate that is a serial, in the caller's order of preference. */
    fun firstPlausible(candidates: List<String?>, model: String): String? =
        candidates.firstOrNull { isPlausible(it, model) }?.trim(*TRIM)
}
