package zw.co.unipay.payments.qr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Keystore signs in DER; the seal carries the fixed-width r || s. The switch verifies the
 * latter, so a conversion that is off by one byte refuses every code the till makes — and does
 * it only for the signatures whose r or s happens to need a leading zero, about one in 128.
 */
class DerToP1363Test {

    private fun der(r: ByteArray, s: ByteArray): ByteArray {
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun bytes(n: Int, fill: Int) = ByteArray(n) { fill.toByte() }

    @Test
    fun `full-width components are copied as they are`() {
        val r = bytes(32, 0x11)
        val s = bytes(32, 0x22)

        assertArrayEquals(r + s, AndroidKeystoreQrSealer.derToP1363(der(r, s)))
    }

    /** DER adds a zero byte when the top bit is set, so the integer does not read as negative. */
    @Test
    fun `a sign byte is dropped`() {
        val r = bytes(32, 0x81)
        val s = bytes(32, 0xFF)

        val out = AndroidKeystoreQrSealer.derToP1363(der(byteArrayOf(0) + r, byteArrayOf(0) + s))

        assertEquals(64, out.size)
        assertArrayEquals(r + s, out)
    }

    /** DER drops leading zeros of the value itself; P1363 needs them back. */
    @Test
    fun `a short component is padded on the left`() {
        val r = bytes(31, 0x33)
        val s = bytes(30, 0x44)

        val out = AndroidKeystoreQrSealer.derToP1363(der(r, s))

        assertArrayEquals(byteArrayOf(0) + r + byteArrayOf(0, 0) + s, out)
    }
}
