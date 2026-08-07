package com.synergy.payments

import com.synergy.payments.printing.EscPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bytes that go down the Bluetooth socket.
 *
 * ESC/POS printers do not answer back. A wrong byte produces a receipt that is subtly wrong —
 * missing the cut, stuck in double-width for the rest of the roll, or printing the escape
 * sequence itself as characters — and the till reports success either way. So the sequences are
 * pinned here rather than trusted.
 */
class EscPosTest {

    @Test
    fun `a job starts by resetting whatever the last job left behind`() {
        val bytes = EscPos.document(listOf("Hello"))

        // ESC @ — a printer left in double-width or inverted by a previous job would otherwise
        // print this one that way too.
        assertEquals(0x1B.toByte(), bytes[0])
        assertEquals('@'.code.toByte(), bytes[1])
    }

    @Test
    fun `each line is sent with a line feed after it`() {
        val text = String(EscPos.document(listOf("one", "two")), Charsets.US_ASCII)

        assertTrue(text.contains("one\ntwo\n"))
    }

    @Test
    fun `the paper is fed clear of the head and cut`() {
        val bytes = EscPos.document(listOf("Hello"))
        val tail = bytes.toList().takeLast(5)

        // GS V 1 — a partial cut. Without the feed first, the cut lands in the middle of the
        // last line, which is the printed total.
        assertTrue("expected a cut at the end", tail.containsSubList(listOf(0x1D.toByte(), 'V'.code.toByte())))
        assertTrue("expected a feed before the cut",
            bytes.toList().containsSubList(listOf(0x1B.toByte(), 'd'.code.toByte())))
    }

    @Test
    fun `characters a thermal printer cannot render are replaced, not dropped`() {
        // An em dash or a curly quote arrives from item names typed at head office. Sent raw
        // they print as a stray glyph or nothing at all; the line still has to read.
        val text = String(EscPos.document(listOf("Synergy — Retail’s “best”")), Charsets.US_ASCII)

        assertTrue("em dash should become a hyphen", text.contains("Synergy - Retail's \"best\""))
    }

    @Test
    fun `a receipt with no lines still resets and cuts, so the roll is left tidy`() {
        val bytes = EscPos.document(emptyList())

        assertEquals(0x1B.toByte(), bytes[0])
        assertTrue(bytes.toList().containsSubList(listOf(0x1D.toByte(), 'V'.code.toByte())))
    }

    private fun <T> List<T>.containsSubList(sub: List<T>): Boolean =
        indices.any { i -> i + sub.size <= size && subList(i, i + sub.size) == sub }
}
