package com.synergy.payments.terminal

/**
 * Where the switch is, as a host and a port the terminal can dial.
 *
 * Parsed rather than trusted: the value arrives as a string in a policy, and a terminal that
 * accepts "switch.unipay.co.zw" without a port spends its first payment discovering that.
 */
data class Endpoint(val host: String, val port: Int) {
    companion object {
        fun parse(value: String?): Endpoint? {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty()) return null

            // lastIndexOf, so a future "[::1]:3333" splits on the port rather than the address.
            val separator = raw.lastIndexOf(':')
            if (separator <= 0 || separator == raw.length - 1) return null

            val host = raw.substring(0, separator).trim()
            if (host.isEmpty()) return null

            val port = raw.substring(separator + 1).trim().toIntOrNull() ?: return null
            if (port !in 1..65535) return null

            return Endpoint(host, port)
        }
    }
}

/**
 * What this terminal believes about itself, as of one reading of managed configuration.
 *
 * [deviceId] is ours and permanent. [terminalId] is the bank's and may not exist yet — the
 * switch resolves it from the device id, so a null here is not a reason to refuse a payment.
 * [serialNumber] is read from the hardware, not from the policy.
 */
data class TerminalSnapshot(
    val deviceId: String?,
    val terminalId: String?,
    val merchantId: String?,
    /**
     * The merchant's trading name, as it should appear on a receipt.
     *
     * Nothing settles to it — [merchantId] decides that — so a terminal without one can still
     * take money. It is here because it belongs to the deployment rather than to the build: a
     * name compiled into an application is the wrong council's name on somebody's receipt.
     */
    val merchantName: String?,
    /**
     * The merchant's tax identification number, for the revenue authority's copy of the
     * receipt. The seller's, not the customer's.
     */
    val taxIdentificationNumber: String?,
    /**
     * The merchant's number on the national QR scheme — its Merchant Account Information, what
     * goes in a QR payload's tag 26, and what every switch routes the payment on.
     *
     * A different identifier from [merchantId], which is the acquiring switch's own and never
     * leaves it. Null means the merchant is not enrolled on the scheme, in which case the
     * switch sends no number at all rather than a blank one, and this till cannot present a
     * code — see [canPresentQr].
     */
    val qrMerchantId: String?,
    /**
     * Which outlet of that merchant this till stands in. Null reads as 1, which is what the
     * scheme means by a blank outlet.
     */
    val qrOutletNumber: Int?,
    val endpoint: Endpoint?,
    val serialNumber: String,
) {
    /**
     * Whether this terminal may take an electronic payment.
     *
     * A merchant to attribute the money to and a switch to send it through are as necessary as
     * the identity itself; without any one of the three there is no payment to make, only a
     * wrong one.
     */
    val isProvisioned: Boolean
        get() = deviceId != null && merchantId != null && endpoint != null

    /**
     * Whether this till can present a QR code.
     *
     * Stricter than [isProvisioned]: a terminal can take a card without its merchant being on
     * the QR scheme. Without a scheme merchant number there is no tag 26 to build, so the
     * honest answer at the till is "this merchant does not accept QR" rather than a code that
     * routes nowhere.
     */
    val canPresentQr: Boolean
        get() = isProvisioned && qrMerchantId != null

    /** What the scheme means by a blank outlet (source spec §15). */
    val qrOutlet: Int
        get() = qrOutletNumber ?: DEFAULT_QR_OUTLET
    // Deliberately not merchantName or the TIN: both belong on the paperwork, and a receipt
    // missing a line is a worse receipt, not a wrong payment. Refusing to take money over
    // either would turn a typo in a policy into a closed counter.

    companion object {
        /** What the scheme reads a blank outlet as, and what the switch sends when none is set. */
        const val DEFAULT_QR_OUTLET = 1

        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TERMINAL_ID = "terminal_id"
        const val KEY_MERCHANT_ID = "merchant_id"
        const val KEY_MERCHANT_NAME = "merchant_name"
        const val KEY_MERCHANT_TIN = "merchant_tin"
        const val KEY_QR_MERCHANT_ID = "qr_merchant_id"
        const val KEY_QR_OUTLET_NUMBER = "qr_outlet_number"
        const val KEY_SWITCH_ENDPOINT = "switch_endpoint"

        val KEYS = listOf(
            KEY_DEVICE_ID,
            KEY_TERMINAL_ID,
            KEY_MERCHANT_ID,
            KEY_MERCHANT_NAME,
            KEY_MERCHANT_TIN,
            KEY_QR_MERCHANT_ID,
            KEY_QR_OUTLET_NUMBER,
            KEY_SWITCH_ENDPOINT,
        )

        fun parse(values: Map<String, String?>, serialNumber: String) = TerminalSnapshot(
            deviceId = values[KEY_DEVICE_ID].orNull(),
            terminalId = values[KEY_TERMINAL_ID].orNull(),
            merchantId = values[KEY_MERCHANT_ID].orNull(),
            merchantName = values[KEY_MERCHANT_NAME].orNull(),
            taxIdentificationNumber = values[KEY_MERCHANT_TIN].orNull(),
            qrMerchantId = values[KEY_QR_MERCHANT_ID].orNull(),
            // Delivered as a JSON number, so a reader that hands us everything as strings gives
            // us digits; one that does not gives us nothing, and the default of 1 applies.
            qrOutletNumber = values[KEY_QR_OUTLET_NUMBER].orNull()?.toIntOrNull(),
            endpoint = Endpoint.parse(values[KEY_SWITCH_ENDPOINT]),
            serialNumber = serialNumber,
        )

        /** A cleared key and one set to spaces mean the same thing to whoever wrote the policy. */
        private fun String?.orNull(): String? = this?.trim()?.ifEmpty { null }
    }
}
