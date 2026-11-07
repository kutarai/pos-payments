package zw.co.unipay.payments.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WedgeDecoderTest {

    private fun WedgeDecoder.type(text: String): List<String?> = text.map { key(WedgeKey.Char(it)) }

    @Test fun `a code ending in Enter is one barcode`() {
        val d = WedgeDecoder()
        assertEquals(List(13) { null }, d.type("9800000000014"))
        assertEquals("9800000000014", d.key(WedgeKey.End))
    }

    @Test fun `a code with no terminator is completed by the pause`() {
        val d = WedgeDecoder()
        d.type("ABC-123")
        assertEquals("ABC-123", d.flush())
        assertNull(d.flush())
    }

    @Test fun `a stray Enter with nothing typed is no barcode`() {
        val d = WedgeDecoder()
        assertNull(d.key(WedgeKey.End))
    }

    @Test fun `codes back to back stay apart`() {
        val d = WedgeDecoder()
        d.type("111")
        assertEquals("111", d.key(WedgeKey.End))
        d.type("222")
        assertEquals("222", d.key(WedgeKey.End))
    }
}
