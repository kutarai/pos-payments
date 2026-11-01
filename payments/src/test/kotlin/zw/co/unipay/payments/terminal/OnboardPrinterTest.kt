package zw.co.unipay.payments.terminal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The defaults a printer written before QR codes gets, so it stays truthful. */
class OnboardPrinterTest {

    private class TextOnly : OnboardPrinter {
        val printed = mutableListOf<List<String>>()
        override suspend fun printLines(lines: List<String>): Boolean { printed += lines; return true }
        override fun isAvailable() = true
    }

    @Test fun `a text-only printer says it cannot print QR codes`() {
        assertFalse(TextOnly().printsQrCodes)
    }

    @Test fun `lines alone go through printLines unchanged`() = runBlocking {
        val p = TextOnly()
        assertTrue(p.print(listOf(PrintItem.Line("A"), PrintItem.Line("B"))))
        assertEquals(listOf(listOf("A", "B")), p.printed)
    }

    @Test fun `a job with a QR code is refused whole, not printed without it`() = runBlocking {
        val p = TextOnly()
        assertFalse(p.print(listOf(PrintItem.Line("A"), PrintItem.QrCode("https://x"))))
        assertTrue(p.printed.isEmpty())
    }
}
