package com.synergy.payments.printing

/**
 * Turns finished lines into the bytes an ESC/POS printer expects.
 *
 * These printers never answer back: whatever is written to the socket is accepted, and a wrong
 * byte produces a receipt that is subtly wrong — no cut, or the rest of the roll in double width,
 * or the escape sequence printed as characters — while the till reports success. Kept apart from
 * the Bluetooth socket so the bytes can be asserted without a printer on the desk.
 */
object EscPos {

    private const val ESC = 0x1B.toByte()
    private const val GS = 0x1D.toByte()

    /** How far the paper is fed before cutting, so the cut misses the last printed line. */
    private const val FEED_LINES = 3

    fun document(lines: List<String>): ByteArray {
        val out = ArrayList<Byte>(256)

        // ESC @ — reset. A printer left inverted or in double width by the previous job would
        // otherwise print this one the same way.
        out += ESC
        out += '@'.code.toByte()

        for (line in lines) {
            out += ascii(line)
            out += '\n'.code.toByte()
        }

        // ESC d n — feed clear of the head, then GS V 1 — partial cut. Without the feed the cut
        // lands across the last line printed, which is usually the total.
        out += ESC
        out += 'd'.code.toByte()
        out += FEED_LINES.toByte()

        out += GS
        out += 'V'.code.toByte()
        out += 1.toByte()

        return out.toByteArray()
    }

    /**
     * What a thermal printer's built-in code page can actually render.
     *
     * Item names are typed at head office on a full keyboard, so em dashes and curly quotes
     * arrive routinely. Sent raw they print as a stray glyph or as nothing; the line still has
     * to read at the counter.
     */
    private fun ascii(text: String): List<Byte> = text
        .replace('—', '-')
        .replace('–', '-')
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace('“', '"')
        .replace('”', '"')
        .replace('…', '.')
        .map { char -> if (char.code in 32..126) char.code.toByte() else '?'.code.toByte() }
}
