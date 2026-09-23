package zw.co.unipay.payments

import zw.co.unipay.payments.card.IccData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BER-TLV the issuer recomputes the cryptogram from.
 *
 * The kernel is asked for a fixed list of tags and answers with one entry per tag — including
 * the ones it does not have, which come back with length zero. Shipping that answer verbatim
 * sends the issuer a field 55 containing empty Issuer Application Data, an empty ATC or an
 * empty Unpredictable Number, and an ARQC cannot be validated against any of those. The issuer
 * calls the data invalid, which it is.
 *
 * So the blob is parsed before it is sent: empty entries dropped, structure checked, and
 * nothing sent at all rather than something malformed.
 */
class IccDataTest {

    // 9F26 (ARQC) · 9F10 (IAD) · 9F37 (UN) · 9F36 (ATC) · 82 (AIP) · 95 (TVR)
    private val good =
        "9F2608A1B2C3D4E5F60718" +
        "9F100706010A03A00000" +
        "9F370411223344" +
        "9F3602005B" +
        "82025C00" +
        "95050000000000"

    @Test
    fun `a well-formed blob is passed through unchanged`() {
        assertEquals(good, IccData.forIssuer(good))
    }

    @Test
    fun `tags the kernel did not have are dropped, not sent empty`() {
        // 9F1E and 50 asked for, absent from the card: the kernel answers with length 0.
        val withEmpties = "9F2608A1B2C3D4E5F60718" + "9F1E00" + "5000" + "9F3602005B"
        val cleaned = IccData.forIssuer(withEmpties)
        assertEquals("9F2608A1B2C3D4E5F60718" + "9F3602005B", cleaned)
        assertTrue("an empty IFD serial must not reach the issuer", !cleaned.contains("9F1E00"))
    }

    @Test
    fun `an empty cryptogram is not something to send at all`() {
        // If the ARQC itself came back empty the transaction cannot be authorised online, and
        // sending the rest invites the issuer to reject data that was never going to validate.
        val noArqc = "9F2600" + "9F3602005B" + "82025C00"
        assertEquals("", IccData.forIssuer(noArqc))
    }

    @Test
    fun `multi-byte tags are read as one tag, not as two`() {
        // 9F26 is a two-byte tag: the 0x9F says another byte follows. Reading 9F as a tag on
        // its own would take 0x26 for a length and shift every following byte.
        val tags = IccData.parse(good).map { it.tag }
        assertEquals(listOf("9F26", "9F10", "9F37", "9F36", "82", "95"), tags)
    }

    @Test
    fun `the cryptogram and its inputs survive the cleaning`() {
        val kept = IccData.parse(IccData.forIssuer(good)).associate { it.tag to it.value }
        assertEquals("A1B2C3D4E5F60718", kept["9F26"])
        assertEquals("06010A03A00000", kept["9F10"])
        assertEquals("11223344", kept["9F37"])
        assertEquals("005B", kept["9F36"])
    }

    @Test
    fun `a long-form length is understood`() {
        // 0x81 says "the next byte is the length" — used once a value passes 127 bytes.
        val long = "9F2681" + "80" + "AB".repeat(128)
        assertEquals(1, IccData.parse(long).size)
        assertEquals(128, IccData.parse(long).first().value.length / 2)
    }

    @Test
    fun `a truncated blob sends nothing rather than a guess`() {
        // A value shorter than its length claims: the kernel read was cut short. Half a
        // cryptogram is worse than none — the issuer would decline and nobody would know why.
        assertEquals("", IccData.forIssuer("9F2608A1B2C3"))
        assertEquals("", IccData.forIssuer("9F26"))
    }

    @Test
    fun `odd or non-hex input is refused`() {
        assertEquals("", IccData.forIssuer("9F260"))
        assertEquals("", IccData.forIssuer("not hex at all"))
        assertEquals("", IccData.forIssuer(""))
    }

    // ----- which application identifier the issuer is given -------------------------------
    //
    // Three tags can carry it and hosts differ on which they read: 4F is the card's, 9F06 the
    // terminal's, 84 the DF Name from the SELECT response. For a payment application the DF
    // Name IS the AID, so 84 and 4F carry the same value.
    //
    // 9F06 does not. Terminal AIDs are registered as PARTIALS on purpose — A000000003 matches
    // any Visa product — so on a Visa card the terminal's 9F06 is five bytes where the card's
    // AID is seven. Sent to an acquirer that reads 9F06, that is not the Visa AID: it is a
    // prefix of it, and a host keying off it sees an application that does not exist.

    private val DF_NAME = "84"
    private val CARD_AID = "4F"
    private val TERMINAL_AID = "9F06"

    @Test
    fun `a partial terminal AID is dropped in favour of the card's own`() {
        // 9F06 = A000000003 (the registered Visa partial), 84 = A0000000031010 (the card's).
        val blob = "9F2608A1B2C3D4E5F60718" + "9F0605A000000003" + "8407A0000000031010"
        val kept = IccData.parse(IccData.forIssuer(blob)).associate { it.tag to it.value }
        assertEquals("A0000000031010", kept[DF_NAME])
        assertEquals("the card's AID stands in for the partial", "A0000000031010", kept[CARD_AID])
        assertEquals("a prefix of the real AID must not be sent as the AID", null, kept[TERMINAL_AID])
    }

    @Test
    fun `a full terminal AID is left alone`() {
        // Zimswitch is registered in full, so its 9F06 is the AID and not a prefix of it.
        val blob = "9F2608A1B2C3D4E5F60718" + "9F0607A0000007790000" + "8407A0000007790000"
        val kept = IccData.parse(IccData.forIssuer(blob)).associate { it.tag to it.value }
        assertEquals("A0000007790000", kept[TERMINAL_AID])
        assertEquals("A0000007790000", kept[DF_NAME])
    }

    @Test
    fun `the card's AID is supplied from the DF Name when the kernel does not give it`() {
        // For a payment application these are the same value, and the acquirer reads 4F.
        // Leaving it out because the kernel did not return it hands them nothing they read.
        val blob = "9F2608A1B2C3D4E5F60718" + "8407A0000000031010"
        val kept = IccData.parse(IccData.forIssuer(blob)).associate { it.tag to it.value }
        assertEquals("A0000000031010", kept[CARD_AID])
    }

    @Test
    fun `a card that gave its own AID keeps it`() {
        val blob = "9F2608A1B2C3D4E5F60718" + "4F07A0000000041010" + "8407A0000000041010"
        val kept = IccData.parse(IccData.forIssuer(blob)).associate { it.tag to it.value }
        assertEquals("A0000000041010", kept[CARD_AID])
    }

    @Test
    fun `no DF Name means nothing is invented`() {
        val blob = "9F2608A1B2C3D4E5F60718" + "9F3602005B"
        val kept = IccData.parse(IccData.forIssuer(blob)).associate { it.tag to it.value }
        assertEquals(null, kept[CARD_AID])
        assertEquals(null, kept[DF_NAME])
    }
}

