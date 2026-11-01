package zw.co.unipay.payments.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data the card signs.
 *
 * An online chip authorisation is the issuer recomputing the cryptogram the card generated. It
 * can only do that if the values it is given are the values the card used — so every one of
 * these tags is an input to an ARQC that has to validate on the other side of the switch.
 *
 * The currency is here because it was wrong. The kernel was configured with a hard-coded
 * `0840` — a deliberate workaround, since some Ciontek builds reject ZWG and silently stop
 * detecting cards — while the switch was told the real currency of the sale. So on every
 * non-USD transaction the card signed "USD" and the issuer recomputed with "ZWG", the two
 * cryptograms disagreed, and the issuer rejected a payment that was otherwise perfectly good.
 * One function now answers "what currency is this?" for both the card and the switch, so they
 * cannot drift apart again.
 */
class EmvTransactionDataTest {

    @Test
    fun `the currency the card signs is the currency of the sale, not a fixed USD`() {
        assertEquals("0924", alphaToNumericCurrency("ZWG"))
        assertEquals("0840", alphaToNumericCurrency("USD"))
        assertEquals("0932", alphaToNumericCurrency("ZWL"))
        assertEquals("0710", alphaToNumericCurrency("ZAR"))
    }

    @Test
    fun `an unknown currency falls back to USD rather than sending nothing`() {
        // A blank 5F2A would fail the kernel outright; the fallback at least transacts, and
        // the currencies this estate actually uses are all named above.
        assertEquals("0840", alphaToNumericCurrency("XYZ"))
        assertEquals("0840", alphaToNumericCurrency(""))
    }

    @Test
    fun `case does not decide what a card signs`() {
        assertEquals("0924", alphaToNumericCurrency("zwg"))
    }

    @Test
    fun `the transaction tags carry the sale's own currency into the cryptogram`() {
        val (tags, values) = emvTransactionTlv(amount = 5867L, currencyNumeric = "0924", sequenceCounter = 1L)
        val byTag = tags.zip(values).toMap()
        assertEquals("the card must sign the currency it is actually being paid in", "0924", byTag["5F2A"])
    }

    @Test
    fun `the amount is twelve BCD digits, which is what tag 9F02 is`() {
        val (tags, values) = emvTransactionTlv(amount = 5867L, currencyNumeric = "0840", sequenceCounter = 1L)
        val byTag = tags.zip(values).toMap()
        // 9F02 is n12 — six bytes BCD. 5867 minor units is 58.67.
        assertEquals("000000005867", byTag["9F02"])
        assertEquals(12, byTag["9F02"]!!.length)
    }

    @Test
    fun `the binary amount tag agrees with the BCD one`() {
        val (tags, values) = emvTransactionTlv(amount = 5867L, currencyNumeric = "0840", sequenceCounter = 1L)
        val byTag = tags.zip(values).toMap()
        // Tag 81 is the same amount as a four-byte binary, and a card that reads both must
        // not be shown two different figures.
        assertEquals(5867L, byTag["81"]!!.toLong(16))
        assertEquals(8, byTag["81"]!!.length)
    }

    @Test
    fun `the transaction is a purchase in Zimbabwe`() {
        val (tags, values) = emvTransactionTlv(amount = 100L, currencyNumeric = "0840", sequenceCounter = 1L)
        val byTag = tags.zip(values).toMap()
        assertEquals("00", byTag["9C"])     // Purchase
        assertEquals("0716", byTag["9F1A"]) // Terminal country
    }

    // ----- tag 9F41, which crashed a gateway ----------------------------------------------
    //
    // EMV gives the Transaction Sequence Counter a length of 2 to 4 bytes. This terminal sent
    // six — `String.format("%012d", 1L)` — and it went out in field 55 on every chip
    // transaction. A parser reading 9F41 into a four-byte field has nowhere to put the other
    // two, and the acquirer's gateway stopped responding rather than declining: from the till
    // that looked like "Gateway unavailable", so the fault was invisible at both ends.
    //
    // It was also a constant. The counter exists so an issuer can tell one transaction from a
    // replay of it, and a terminal that sends 1 forever is telling it nothing.

    @Test
    fun `the transaction sequence counter is four bytes, which is inside what EMV allows`() {
        val (tags, values) = emvTransactionTlv(amount = 100L, currencyNumeric = "0840", sequenceCounter = 1L)
        val byTag = tags.zip(values).toMap()
        val bytes = byTag["9F41"]!!.length / 2
        assertTrue("9F41 was $bytes bytes; EMV allows 2 to 4", bytes in 2..4)
        assertEquals("00000001", byTag["9F41"])
    }

    @Test
    fun `the counter carries the number it was given, not a fixed one`() {
        val (tags, values) = emvTransactionTlv(amount = 100L, currencyNumeric = "0840", sequenceCounter = 4213L)
        assertEquals("00004213", tags.zip(values).toMap()["9F41"])
    }

    @Test
    fun `a counter past eight digits wraps rather than growing the tag`() {
        // Four bytes BCD hold 99999999. Growing the field instead is exactly the fault above,
        // so it wraps — which is what the counter is expected to do anyway.
        val (tags, values) = emvTransactionTlv(amount = 100L, currencyNumeric = "0840", sequenceCounter = 100000001L)
        val byTag = tags.zip(values).toMap()
        assertEquals("00000001", byTag["9F41"])
        assertEquals(4, byTag["9F41"]!!.length / 2)
    }

    @Test
    fun `every tag sent is inside the length EMV gives it`() {
        // The whole point: nothing this terminal puts in field 55 may be out of spec again.
        val (tags, values) = emvTransactionTlv(amount = 2934L, currencyNumeric = "0840", sequenceCounter = 26L)
        val allowed = mapOf(
            "9C" to (1..1), "9F41" to (2..4), "9F02" to (6..6),
            "81" to (4..4), "5F2A" to (2..2), "9F1A" to (2..2),
        )
        tags.zip(values).forEach { (tag, value) ->
            val range = allowed[tag] ?: error("no length rule recorded for $tag")
            assertTrue("$tag was ${value.length / 2} bytes; EMV allows $range",
                value.length / 2 in range)
        }
    }

    // ----- what the issuer is given to verify the cryptogram with --------------------------

    @Test
    fun `an application identifier the acquirer can find is sent`() {
        // Hosts differ on which one they read. Only 84 was sent; this acquirer reads 4F or
        // 9F06, so a chip transaction arrived with no identifier it recognised. All three go
        // now — the kernel returns what it holds and IccData drops the rest rather than
        // sending empty tags.
        assertTrue("the card's own AID (4F)", ICC_TAGS_FOR_ISSUER.contains("4F"))
        assertTrue("the terminal's AID (9F06)", ICC_TAGS_FOR_ISSUER.contains("9F06"))
        assertTrue("the DF Name (84)", ICC_TAGS_FOR_ISSUER.contains("84"))
    }

    @Test
    fun `everything an issuer recomputes a cryptogram from is asked for`() {
        // An ARQC cannot be checked without these. Losing one is a decline with no visible
        // cause, so the list is asserted rather than trusted.
        for (tag in listOf("9F26", "9F27", "9F10", "9F37", "9F36", "82", "95", "9A", "9C",
                           "9F02", "5F2A", "9F1A", "9F03", "9F33", "9F34", "9F35", "5F34")) {
            assertTrue("$tag missing from the issuer tag list", ICC_TAGS_FOR_ISSUER.contains(tag))
        }
    }

    @Test
    fun `no tag is asked for twice`() {
        // A duplicate would come back twice in field 55, which is malformed to a strict parser.
        assertEquals(ICC_TAGS_FOR_ISSUER.size, ICC_TAGS_FOR_ISSUER.toSet().size)
    }
}

