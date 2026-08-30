package com.synergy.payments

import com.synergy.payments.qr.EmvcoQrGenerator
import com.synergy.payments.qr.QrMacSealer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payload a till puts on screen. What it names the merchant, and that it cannot be
 * presented unsealed, are the two things that decide whether the switch will honour it.
 */
class EmvcoQrGeneratorTest {

    private object AlwaysSeals : QrMacSealer {
        override fun seal(payload: String) = "0123456789ABCDEF"
    }

    private object CannotSeal : QrMacSealer {
        override fun seal(payload: String): String? = null
    }

    private fun payload(sealer: QrMacSealer? = AlwaysSeals, amount: Double? = 12.50) =
        EmvcoQrGenerator.generatePayload(
            qrMerchantId = "600123456789",
            qrOutletNumber = 4,
            merchantName = "KUDZI HARDWARE",
            merchantCity = "HARARE",
            merchantCategoryCode = "5200",
            currency = "ZWG",
            amount = amount,
            paymentReference = "QR123",
            billNumber = "RCT-00417",
            sealer = sealer,
        )

    /** Two decimal characters of length, not hexadecimal — the format's usual trap. */
    private fun subTag(template: String, id: String): String? {
        var i = 0
        while (i + 4 <= template.length) {
            val tag = template.substring(i, i + 2)
            val len = template.substring(i + 2, i + 4).toInt()
            if (tag == id) return template.substring(i + 4, i + 4 + len)
            i += 4 + len
        }
        return null
    }

    private fun tag(payload: String, id: String): String? = subTag(payload, id)

    @Test
    fun `names the merchant by the scheme's number, not the acquirer's`() {
        val template = tag(payload()!!, "26")!!

        assertEquals("ZWQR", subTag(template, "00"))
        assertEquals("600123456789", subTag(template, "01"))
        assertEquals("4", subTag(template, "02"))
    }

    @Test
    fun `seals the payload in tag 80`() {
        val template = tag(payload()!!, "80")!!

        assertEquals("ZWQR", subTag(template, "00"))
        assertEquals("0123456789ABCDEF", subTag(template, "01"))
    }

    /**
     * A code the switch will refuse is not worth a customer's time. Without a MAC the switch
     * cannot tell this till from a printer, so nothing goes on screen at all.
     */
    @Test
    fun `refuses to build a payload it cannot seal`() {
        assertNull(payload(sealer = CannotSeal))
        assertNull(payload(sealer = null))
    }

    /**
     * The MAC covers the identity, the amount and the reference — everything the customer is
     * agreeing to — and cannot cover the template carrying it or the CRC that follows.
     */
    @Test
    fun `the sealed bytes are everything before tag 80`() {
        var sealed: String? = null
        val capturing = object : QrMacSealer {
            override fun seal(payload: String): String? {
                sealed = payload
                return "0123456789ABCDEF"
            }
        }

        val full = EmvcoQrGenerator.generatePayload(
            qrMerchantId = "600123456789", qrOutletNumber = 4,
            merchantName = "KUDZI HARDWARE", merchantCity = "HARARE",
            merchantCategoryCode = "5200", currency = "ZWG", amount = 12.50,
            paymentReference = "QR123", billNumber = "RCT-00417", sealer = capturing,
        )!!

        assertTrue("the sealed bytes are the payload's own prefix", full.startsWith(sealed!!))
        assertTrue("covers the merchant", sealed!!.contains("600123456789"))
        assertTrue("covers the amount", sealed!!.contains("12.50"))
        assertTrue("covers the reference", sealed!!.contains("QR123"))
        assertTrue("cannot cover its own MAC", !sealed!!.contains("0123456789ABCDEF"))
    }

    @Test
    fun `the crc closes the payload and covers its own tag and length`() {
        val full = payload()!!

        assertTrue(full.contains("6304"))
        assertEquals(full.length - 8, full.indexOf("6304", full.length - 8))
    }

    @Test
    fun `an amount makes the code dynamic and its absence makes it static`() {
        assertEquals("12", tag(payload()!!, "01"))
        assertEquals("11", tag(payload(amount = null)!!, "01"))
    }

    /** EMVCo caps the bill number at 25; a till's numbering convention is not a reason to fail. */
    @Test
    fun `the receipt number rides in tag 62 as the bill number`() {
        val additional = tag(payload()!!, "62")!!

        assertEquals("RCT-00417", subTag(additional, "01"))
        assertEquals("QR123", subTag(additional, "05"))
    }
}
