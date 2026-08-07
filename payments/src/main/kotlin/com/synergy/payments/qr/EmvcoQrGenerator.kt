package com.synergy.payments.qr

import com.synergy.payments.card.TerminalConfig
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.UUID

/**
 * Generates EMVco Merchant-Presented QR code payloads and bitmaps.
 */
object EmvcoQrGenerator {

    /**
     * Build the EMVco TLV payload string and a unique payment reference.
     *
     * @return Pair of (payload string, paymentReference)
     */
    /** Map ISO 4217 alpha code → numeric code for QR Tag 53 */
    private fun currencyToNumeric(alpha: String): String = when (alpha.uppercase()) {
        "ZWG" -> "924"
        "USD" -> "840"
        "ZAR" -> "710"
        "EUR" -> "978"
        "GBP" -> "826"
        "BWP" -> "072"
        "CNY", "RMB" -> "156"
        else  -> "924" // default ZWG
    }

    fun generatePayload(
        merchantId: String,
        terminalId: String,
        merchantName: String,
        currency: String = "ZWG",
        receiptNumber: String = "",
        merchantCity: String = "Harare",
        countryCode: String = "ZW",
        merchantCategoryCode: String = TerminalConfig.DEFAULT_MERCHANT_CATEGORY_CODE,
        amount: Double
    ): Pair<String, String> {
        val currencyNumeric = currencyToNumeric(currency)
        val paymentReference = "QR${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

        val sb = StringBuilder()

        // Tag 00 - Payload Format Indicator
        sb.append(tlv("00", "01"))

        // Tag 01 - Point of Initiation Method: "12" = dynamic QR
        sb.append(tlv("01", "12"))

        // Tag 26 - Merchant Account Information
        val merchantAcct = StringBuilder()
        merchantAcct.append(tlv("00", "com.synergy.pos"))
        merchantAcct.append(tlv("01", merchantId))
        merchantAcct.append(tlv("02", terminalId))
        sb.append(tlv("26", merchantAcct.toString()))

        // Tag 52 - Merchant Category Code
        sb.append(tlv("52", merchantCategoryCode))

        // Tag 53 - Transaction Currency (ISO 4217 numeric)
        sb.append(tlv("53", currencyNumeric))

        // Tag 54 - Transaction Amount
        sb.append(tlv("54", String.format("%.2f", amount)))

        // Tag 58 - Country Code
        sb.append(tlv("58", countryCode))

        // Tag 59 - Merchant Name
        sb.append(tlv("59", merchantName.take(25)))

        // Tag 60 - Merchant City
        sb.append(tlv("60", merchantCity.take(15)))

        // Tag 62 - Additional Data
        val additionalData = StringBuilder()
        if (receiptNumber.isNotEmpty()) {
            additionalData.append(tlv("01", receiptNumber.take(25)))  // Bill Number
        }
        additionalData.append(tlv("05", paymentReference))            // Reference Label
        additionalData.append(tlv("07", terminalId.take(25)))         // Terminal Label
        sb.append(tlv("62", additionalData.toString()))

        // Tag 63 - CRC: append tag + length placeholder, then calculate
        sb.append("6304")
        val crc = crc16(sb.toString().toByteArray(Charsets.UTF_8))
        sb.append(String.format("%04X", crc))

        return Pair(sb.toString(), paymentReference)
    }

    /**
     * Render a string payload as a QR code Bitmap using ZXing.
     */
    fun generateBitmap(payload: String, size: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = QRCodeWriter().encode(
            payload, BarcodeFormat.QR_CODE, size, size, hints
        )
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bitmap
    }

    private fun tlv(tag: String, value: String): String {
        val len = String.format("%02d", value.length)
        return "$tag$len$value"
    }

    /** CRC-16/CCITT-FALSE as specified by EMVco */
    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
