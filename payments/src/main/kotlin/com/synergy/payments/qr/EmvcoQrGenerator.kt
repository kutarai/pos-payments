package com.synergy.payments.qr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Builds the national scheme's EMVCo merchant-presented payload, and draws it.
 *
 * The merchant identity is the scheme's, not this switch's. Tag 26 carries `qr_merchant_id`
 * and `qr_outlet_number` as they arrive in managed configuration — the numbers the scheme
 * routes on. An earlier version of this file named the merchant by the acquiring switch's own
 * id under the GUID "com.synergy.pos", which the scheme had never been told; nothing caught it
 * because the switch routed on the payment reference and never read the payload.
 */
object EmvcoQrGenerator {

    /** Sub-tag 00 of every scheme template, naming who owns it. Four characters, deliberately. */
    const val SCHEME_GUID = "ZWQR"

    /** Domestic template: the scheme's merchant account information. */
    private const val MERCHANT_TEMPLATE = "26"

    /**
     * The MAC template. EMVCo reserves 80–99 for unreserved templates, which is what this is;
     * 26–51 is Merchant Account Information and a seal is not that.
     */
    private const val MAC_TEMPLATE = "80"

    private const val CRC_TAG = "63"
    private const val ADDITIONAL_DATA = "62"

    private const val STATIC_INITIATION = "11"
    private const val DYNAMIC_INITIATION = "12"

    private const val MERCHANT_NAME_LIMIT = 25
    private const val MERCHANT_CITY_LIMIT = 15
    private const val BILL_NUMBER_LIMIT = 25

    /** ISO 4217 alpha to numeric, for tag 53. */
    private fun currencyToNumeric(alpha: String): String = when (alpha.uppercase()) {
        "ZWG" -> "924"
        "USD" -> "840"
        "ZAR" -> "710"
        "EUR" -> "978"
        "GBP" -> "826"
        "BWP" -> "072"
        "CNY", "RMB" -> "156"
        else -> "924"
    }

    /**
     * The payload for one sale, sealed by [sealer] if the terminal can seal it.
     *
     * @param paymentReference the till's own reference, which rides in tag 62 sub-tag 05 and
     *   is what the switch matches a payment back to. Minted by the caller, not here, so a
     *   retry can open a new sale without this function knowing what a retry is.
     * @return the payload, or null when [sealer] cannot seal it — a code the switch will
     *   refuse is not worth putting on a screen.
     */
    fun generatePayload(
        qrMerchantId: String,
        qrOutletNumber: Int,
        merchantName: String,
        merchantCity: String,
        merchantCategoryCode: String,
        currency: String,
        amount: Double?,
        paymentReference: String,
        billNumber: String,
        countryCode: String = "ZW",
        sealer: QrMacSealer?,
    ): String? {
        val tags = StringBuilder()

        tags.append(tlv("00", "01"))
        // §3: an amount fixed by the till makes the code dynamic.
        tags.append(tlv("01", if (amount == null) STATIC_INITIATION else DYNAMIC_INITIATION))

        tags.append(tlv(MERCHANT_TEMPLATE, buildString {
            append(tlv("00", SCHEME_GUID))
            append(tlv("01", qrMerchantId))
            append(tlv("02", qrOutletNumber.toString()))
        }))

        tags.append(tlv("52", merchantCategoryCode))
        tags.append(tlv("53", currencyToNumeric(currency)))
        if (amount != null) tags.append(tlv("54", String.format("%.2f", amount)))
        tags.append(tlv("58", countryCode))
        tags.append(tlv("59", merchantName.take(MERCHANT_NAME_LIMIT)))
        tags.append(tlv("60", merchantCity.take(MERCHANT_CITY_LIMIT)))

        // Sub-tags ascend, as EMVCo orders them: bill number 01 before reference label 05.
        val additional = buildString {
            if (billNumber.isNotBlank()) append(tlv("01", billNumber.take(BILL_NUMBER_LIMIT)))
            append(tlv("05", paymentReference))
        }
        tags.append(tlv(ADDITIONAL_DATA, additional))

        // The MAC covers everything above: merchant identity, amount, reference. It cannot
        // cover the tag-80 template that carries it, nor the CRC, which changes when tag 80 is
        // appended. The switch reverses this by removing tag 80 and the CRC by offset —
        // substring removal on both sides, so neither has to re-serialise and agree.
        val sealed = sealer?.seal(tags.toString()) ?: return null

        tags.append(tlv(MAC_TEMPLATE, buildString {
            append(tlv("00", SCHEME_GUID))
            append(tlv("01", sealed))
        }))

        val body = tags.toString() + CRC_TAG + "04"
        return body + crc16(body)
    }

    /** Render a payload as a QR bitmap. */
    fun generateBitmap(payload: String, size: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bitmap
    }

    /**
     * EMVCo TLV. The length is two decimal characters, not hexadecimal — the single most
     * common misreading of the format, and why a 12-character value is prefixed "12", not "0C".
     */
    private fun tlv(tag: String, value: String): String {
        require(value.length <= 99) { "Tag '$tag' is ${value.length} characters; EMVCo caps at 99." }
        return "%s%02d%s".format(tag, value.length, value)
    }

    /** CRC-16/CCITT-FALSE: polynomial 0x1021, init 0xFFFF, no reflection, no final XOR. */
    private fun crc16(data: String): String {
        var crc = 0xFFFF
        for (byte in data.toByteArray(Charsets.UTF_8)) {
            crc = crc xor (byte.toInt() and 0xFF shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return "%04X".format(crc)
    }
}
