package zw.co.unipay.payments

import zw.co.unipay.payments.card.EmvConfiguration
import zw.co.unipay.payments.card.IccData
import zw.co.unipay.payments.card.EmvConfiguration.tlvOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The terminal's AID and CAPK tables — what the EMV kernel is told it may accept.
 *
 * A malformed entry here does not fail loudly: the kernel refuses the AID and every card of that
 * scheme is simply declined at the counter, with nothing on screen to say why. The length byte in
 * front of an AID is the usual culprit, because it is written by hand and nothing else checks it.
 *
 * Both terminal libraries configure their kernels from these tables, so this is the one place
 * they are checked.
 */
class EmvConfigurationTest {

    /** The AID as the kernel reads it: tag 9F06, a length, then that many bytes. */
    private fun aidOf(entry: String): String {
        assertTrue("entry does not start with tag 9F06: ${entry.take(16)}", entry.startsWith("9F06"))
        return tlvOf(entry).getValue("9F06")
    }

    @Test
    fun `every entry is hex, and its declared length matches the AID it carries`() {
        for (entry in EmvConfiguration.AID_TLV) {
            assertTrue("odd number of hex digits: ${entry.take(24)}", entry.length % 2 == 0)
            assertTrue("not hex: ${entry.take(24)}", entry.all { it in "0123456789ABCDEFabcdef" })

            val aid = aidOf(entry)
            val declared = entry.substring(4, 6).toInt(16)
            assertEquals("length byte disagrees with the AID in ${entry.take(24)}",
                declared * 2, aid.length)

            // 5 to 16 bytes: a 5-byte RID with up to 11 bytes of PIX, per ISO/IEC 7816-5.
            assertTrue("AID $aid is not between 5 and 16 bytes", declared in 5..16)
        }
    }

    @Test
    fun `every AID entry carries 9F06 and its declared length matches`() {
        assertTrue("the AID table is empty", EmvConfiguration.AID_TLV.isNotEmpty())
        for (entry in EmvConfiguration.AID_TLV) {
            assertTrue("entry does not start with 9F06: ${entry.take(24)}", entry.startsWith("9F06"))
            EmvConfiguration.requireWellFormed(entry)
            assertNoRepeatedTag(entry)

            val declared = entry.substring(4, 6).toInt(16)
            assertEquals("declared length of 9F06 disagrees with the value in ${entry.take(24)}",
                declared, tlvOf(entry).getValue("9F06").length / 2)
        }
    }

    @Test
    fun `every CAPK entry carries a RID, an index and a modulus`() {
        assertTrue("the CAPK table is empty", EmvConfiguration.CAPK_TLV.isNotEmpty())
        for (entry in EmvConfiguration.CAPK_TLV) {
            EmvConfiguration.requireWellFormed(entry)
            assertNoRepeatedTag(entry)
            val tlv = tlvOf(entry)

            val rid = tlv["9F06"]
            assertTrue("no RID (9F06) in ${entry.take(24)}", rid != null)
            assertEquals("a CAPK's RID is 5 bytes: ${entry.take(24)}", 10, rid!!.length)

            val index = tlv["9F22"]
            assertTrue("no key index (9F22) in ${entry.take(24)}", index != null)
            assertEquals("a CAPK's index is 1 byte: ${entry.take(24)}", 2, index!!.length)

            val modulus = tlv["DF02"]
            assertTrue("no modulus (DF02) in ${entry.take(24)}", !modulus.isNullOrEmpty())
            assertTrue("no public exponent (DF04) in ${entry.take(24)}", !tlv["DF04"].isNullOrEmpty())
        }
    }

    @Test
    fun `the proprietary AID for BIN 669034 is accepted over the contactless interface`() {
        val entries = EmvConfiguration.AID_TLV.filter { aidOf(it).startsWith(PROPRIETARY_669034) }
        assertTrue("no AID table entry for BIN 669034", entries.isNotEmpty())

        val contactless = entries.filter { tlvOf(it)[PARAM_TYPE] == CONTACTLESS }
        assertTrue("BIN 669034 has no contactless entry — a tap would not select it",
            contactless.isNotEmpty())
    }

    @Test
    fun `the BIN 669034 AID is proprietary and matches on the RID alone`() {
        val entry = EmvConfiguration.AID_TLV.first { aidOf(it).startsWith(PROPRIETARY_669034) }
        val aid = aidOf(entry)

        // Category F: proprietary, unregistered with ISO — see ISO/IEC 7816-5.
        assertTrue("$aid is not an F-category AID", aid.startsWith("F"))

        // The BIN, in the same BCD form the card's issuer identifier uses.
        assertTrue("$aid does not carry BIN 669034", aid.contains("669034"))

        // Partial match, so whatever PIX the issuer personalises onto the card still selects.
        assertEquals("the entry must match on the RID alone (DF01 00 = partial)",
            "00", tlvOf(entry)["DF01"])
    }

    @Test
    fun `requireWellFormed rejects an odd-length entry`() {
        assertRejected("9F0605A00000000")           // one digit short of a byte
        assertRejected("9F0605A0000000039")         // one digit too many
    }

    @Test
    fun `requireWellFormed rejects a length byte that overruns the entry`() {
        // 9F06 says six bytes follow; five do.
        assertRejected("9F0606A000000003")
        // The last tag is cut off mid-value, after an otherwise good AID.
        assertRejected("9F0605A000000003" + "DF0802AB")
    }

    @Test
    fun `requireWellFormed rejects an entry that ends mid-tag`() {
        // 9F06 is a good entry; the trailing DF starts a tag whose low five bits are all set, so a
        // second tag byte must follow, and none does.
        assertRejected("9F0605A000000003" + "DF")
        // A tag with no length byte at all.
        assertRejected("9F0605A000000003" + "DF01")
    }

    @Test
    fun `requireWellFormed rejects an indefinite length`() {
        // 0x80 says the value runs to an end-of-contents marker, which these tables never use.
        assertRejected("9F0680A0000000030000")
    }

    @Test
    fun `requireWellFormed rejects a length field that is too wide`() {
        // 0x84 says four bytes of length follow; this parser accepts at most three.
        assertRejected("9F06840000000500A000000003")
    }

    @Test
    fun `requireWellFormed accepts a long-form length and tlvOf reads through it`() {
        // 0x81 05: one byte of length follows, saying five.
        assertEquals(mapOf("9F06" to "A000000003", "DF01" to "00"),
            tlvOf("9F068105A000000003" + "DF010100"))
        // 0x82 0005: two bytes of length follow.
        assertEquals(mapOf("9F06" to "A000000003"), tlvOf("9F06820005A000000003"))
        // And a long-form length that overruns is still caught.
        assertRejected("9F068106A000000003")
    }

    @Test
    fun `requireWellFormed accepts what the tables actually hold`() {
        EmvConfiguration.AID_TLV.forEach { EmvConfiguration.requireWellFormed(it) }
        EmvConfiguration.CAPK_TLV.forEach { EmvConfiguration.requireWellFormed(it) }
        EmvConfiguration.requireWellFormed("9F0605A000000003")
    }

    @Test
    fun `requireWellFormed rejects what is not hex, and an empty entry`() {
        assertRejected("9F0605A00000000G")
        assertRejected("")
    }

    @Test
    fun `tlvOf reads every tag in a known VISA entry`() {
        // The Visa contactless entry: RID A000000003, contactless parameter type.
        val visaContactless = EmvConfiguration.AID_TLV.single {
            val tlv = tlvOf(it)
            tlv["9F06"] == "A000000003" && tlv[PARAM_TYPE] == CONTACTLESS
        }

        assertEquals(
            mapOf(
                "9F06" to "A000000003",
                "DF01" to "00",
                "9F09" to "008C",
                "DF11" to "DC4000A800",
                "DF12" to "DC4004F800",
                "DF13" to "0010000000",
                "DF15" to "00000000",
                "DF16" to "00",
                "DF17" to "00",
                "DF14" to "9F3704",
                "9F1B" to "00004000",
                "9F7B" to "000000000000",
                "DF19" to "000000020000",
                "DF20" to "000000200001",
                "DF21" to "000000010000",
                "9F01" to "1234567891",
                "9F4E" to "53484F502032",
                "9F15" to "1342",
                "9F16" to "303030303030303030303030303030",
                "9F3C" to "0056",
                "9F3D" to "02",
                "DFC108" to "01",
                "DFC109" to "01",
                "DF8101" to "0840",
                "DF8102" to "9F3704",
                "DFC10A" to "03",
                "DFC10B" to "02",
                "DFC10C" to "03",
                "DFC10D" to "00",
            ),
            tlvOf(visaContactless),
        )
    }

    @Test
    fun `tlvOf keeps the first value when a tag repeats`() {
        // Each terminal library's adapter has always read the first occurrence; the shared parser must agree.
        assertEquals("01", tlvOf("DF010101" + "DF010102")["DF01"])
    }

    /** A tag repeated inside one table entry is a hand-editing slip that [tlvOf] would swallow. */
    private fun assertNoRepeatedTag(entry: String) {
        val tags = IccData.parse(entry).map { it.tag }
        val repeated = tags.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("tag(s) $repeated appear more than once in ${entry.take(24)}", repeated.isEmpty())
    }

    private fun assertRejected(entry: String) {
        try {
            EmvConfiguration.requireWellFormed(entry)
            fail("accepted a malformed entry: '$entry'")
        } catch (expected: IllegalArgumentException) {
            assertFalse("the reason should say something", expected.message.isNullOrBlank())
        }
    }

    private companion object {
        /**
         * DFC10B is the parameter type: which interface an AID entry applies to. The values are
         * the ones the table's own section headings use ("Contactless AIDs (paramType=02)",
         * "Contact AIDs (paramType=01)", "Default AIDs (paramType=00)"); see the tag dictionary
         * on EmvConfiguration.
         */
        const val PARAM_TYPE = "DFC10B"
        const val CONTACTLESS = "02"

        /** The scheme's own RID: F-category, carrying BIN 669034. Given, not derived. */
        const val PROPRIETARY_669034 = "F716669034"
    }
}
