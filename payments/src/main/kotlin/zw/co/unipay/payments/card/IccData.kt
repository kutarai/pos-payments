package zw.co.unipay.payments.card

/**
 * The BER-TLV the issuer recomputes a chip cryptogram from.
 *
 * An online chip authorisation is the issuer regenerating the ARQC the card produced and
 * comparing the two. It does that from the tags the terminal sends it, so a field 55 that is
 * malformed — or merely padded with tags that have no value — is not a cosmetic problem: it is
 * a payment the issuer refuses, for a fault in neither the card nor the customer.
 *
 * The kernel answers `getTlvList` with one entry per tag ASKED FOR, not per tag it holds: a tag
 * the card never supplied comes back with length zero. Twenty-two tags are requested, so any
 * the card lacks are shipped as empty TLVs — an empty Issuer Application Data, an empty ATC, an
 * empty Unpredictable Number — and no ARQC can be validated against those.
 *
 * So the kernel's answer is parsed rather than forwarded: empty entries are dropped, the
 * structure is checked, and a blob that cannot be read is not sent at all. Sending nothing
 * makes the failure visible; sending half a cryptogram has the issuer decline while everyone
 * looks at the card.
 */
object IccData {

    /** One tag and its value, both as uppercase hex. */
    data class Tlv(val tag: String, val value: String)

    /**
     * What was sent and what was taken out, so the caller can say so in the log. Kept apart
     * from the parsing itself: this object has no Android in it, which is what lets the rules
     * above be checked on a desk rather than on a terminal.
     */
    data class Sanitised(
        val hex: String,
        val droppedEmpty: List<String>,
        val problem: String?,
    )

    /**
     * The blob to send, or "" when the kernel's answer cannot be trusted.
     *
     * Empty entries are dropped. A missing or empty cryptogram drops everything: without the
     * ARQC there is nothing for the issuer to verify, and the rest only invites a decline whose
     * reason nobody can see.
     */
    fun forIssuer(kernelHex: String): String = sanitise(kernelHex).hex

    /** [forIssuer] with the reasons, for the log. */
    fun sanitise(kernelHex: String): Sanitised {
        val parsed = parse(kernelHex)
        if (parsed.isEmpty()) {
            return Sanitised("", emptyList(),
                if (kernelHex.isBlank()) "the kernel returned no data"
                else "the kernel data is not well-formed BER-TLV")
        }

        val populated = parsed.filter { it.value.isNotEmpty() }
        val dropped = parsed.filter { it.value.isEmpty() }.map { it.tag }

        if (populated.none { it.tag == APPLICATION_CRYPTOGRAM }) {
            return Sanitised("", dropped, "no application cryptogram (9F26) in the kernel data")
        }
        return Sanitised(
            hex = withUsableAid(populated).joinToString("") { it.tag + lengthHex(it.value) + it.value },
            droppedEmpty = dropped,
            problem = null,
        )
    }

    /**
     * The kernel's answer as tags and values, or an empty list if it is not well-formed.
     *
     * Deliberately all-or-nothing: a blob that runs out mid-value has been truncated somewhere,
     * and the tags read before that point are no more trustworthy than the one that failed.
     */
    fun parse(kernelHex: String): List<Tlv> {
        val hex = kernelHex.trim().uppercase()
        if (hex.isEmpty() || hex.length % 2 != 0) return emptyList()
        if (!hex.all { it in "0123456789ABCDEF" }) return emptyList()

        val out = mutableListOf<Tlv>()
        var i = 0
        while (i < hex.length) {
            // Tag: one byte, unless the low five bits are all set, which says the tag runs on
            // for as long as each further byte has its top bit set. 9F26 is one tag, not two.
            val tagStart = i
            val first = hex.substring(i, i + 2).toInt(16)
            i += 2
            if (first and 0x1F == 0x1F) {
                do {
                    if (i + 2 > hex.length) return emptyList()
                    val next = hex.substring(i, i + 2).toInt(16)
                    i += 2
                } while (next and 0x80 != 0)
            }
            val tag = hex.substring(tagStart, i)

            // Length: one byte up to 127, otherwise 0x8n says how many bytes carry it.
            if (i + 2 > hex.length) return emptyList()
            var length = hex.substring(i, i + 2).toInt(16)
            i += 2
            if (length and 0x80 != 0) {
                val lengthBytes = length and 0x7F
                if (lengthBytes == 0 || lengthBytes > 3) return emptyList()  // indefinite/absurd
                if (i + lengthBytes * 2 > hex.length) return emptyList()
                length = hex.substring(i, i + lengthBytes * 2).toInt(16)
                i += lengthBytes * 2
            }

            if (i + length * 2 > hex.length) return emptyList()   // truncated value
            out += Tlv(tag, hex.substring(i, i + length * 2))
            i += length * 2
        }
        return out
    }

    /**
     * Makes sure the application identifier the acquirer reads is the card's, not a prefix of it.
     *
     * Three tags can carry it and hosts differ on which they read: [CARD_AID] is the card's,
     * [TERMINAL_AID] the terminal's, [DF_NAME] the DF Name from the SELECT response. For a
     * payment application the DF Name IS the AID, so 84 and 4F are the same value.
     *
     * 9F06 is not. Terminal AIDs are registered as partials on purpose — A000000003 matches any
     * Visa product — so on a Visa card the terminal's 9F06 is the five-byte A000000003 where
     * the card's AID is A0000000031010. Sent to a host that reads 9F06, that is not the Visa
     * AID but a prefix of it, naming an application that does not exist. A 9F06 that is a
     * strict prefix of the DF Name is therefore dropped, and 4F is filled in from the DF Name
     * when the kernel did not return it, so whichever tag the host reads it finds the real one.
     */
    private fun withUsableAid(tlvs: List<Tlv>): List<Tlv> {
        val dfName = tlvs.firstOrNull { it.tag == DF_NAME }?.value ?: return tlvs

        var out = tlvs.filterNot { it.tag == TERMINAL_AID && dfName.startsWith(it.value) && it.value != dfName }
        if (out.none { it.tag == CARD_AID }) {
            // Placed next to the DF Name it came from rather than at the end, so the order
            // still reads as the kernel returned it.
            val at = out.indexOfFirst { it.tag == DF_NAME }
            out = out.toMutableList().also { it.add(at, Tlv(CARD_AID, dfName)) }
        }
        return out
    }

    private fun lengthHex(value: String): String {
        val bytes = value.length / 2
        return if (bytes <= 0x7F) "%02X".format(bytes) else "81" + "%02X".format(bytes)
    }

    const val APPLICATION_CRYPTOGRAM = "9F26"

    /** The card's application identifier. */
    const val CARD_AID = "4F"

    /** The terminal's — registered as a partial for Visa and Mastercard, so not always the AID. */
    const val TERMINAL_AID = "9F06"

    /** The DF Name from the SELECT response, which for a payment application is the AID. */
    const val DF_NAME = "84"
}
