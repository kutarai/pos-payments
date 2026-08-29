package com.synergy.payments.qr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders an EMVCo merchant-presented QR payload as a bitmap.
 *
 * It no longer builds the payload. The till used to compose one here, and it named the merchant
 * by this switch's own merchant id under the GUID "com.synergy.pos" — neither of which the
 * national scheme has ever been told. The payload now comes down the payment stream, minted and
 * signed by the switch that owns the scheme's numbering, and this only draws it.
 */
object EmvcoQrGenerator {

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
}
