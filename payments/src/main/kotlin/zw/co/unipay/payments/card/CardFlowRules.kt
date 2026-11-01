package zw.co.unipay.payments.card

/**
 * The rules a card transaction follows that belong to the acquirer, not to a make of terminal.
 *
 * Everything here was `internal` in the CS20's `CiontekEmvPaymentProcessor.kt` and was moved
 * whole, with its tests, when a second make of terminal arrived. These are the decisions an
 * acquirer and an issuer see — what the card signs, which tags field 55 carries, whether a sale
 * happened when the kernel and the bank disagree, what a cashier is told when it did not, and
 * whether the cardholder proved themselves. **A second copy of any of them would be two
 * terminals behaving differently at one counter**, and the difference would show up as a decline
 * on one make of hardware that nobody could explain from the till.
 *
 * So they are deliberately `public` and deliberately pure: no Android, no vendor SDK, no state.
 * Each make of terminal adapts its own kernel's API to these; none of them re-decides what they
 * decide. [CardInterface] is the one concession to two makes — the vendors name the card
 * interfaces differently, so the rule is stated in neutral terms and each adapter translates.
 */

/**
 * A way a card can be presented, as the acquirer thinks of it rather than as any one SDK
 * enumerates it.
 *
 * The Ciontek reader takes a bitmask of its own `AidlConstantsV2.CardType` values; the ZCS
 * reader takes a `CardReaderTypeEnum` naming a combination. Neither representation can live
 * here, and the rule that chooses between them ([cardTypesFor]) is the same on both, so the rule
 * speaks in these and each adapter maps them to what its reader wants.
 */
enum class CardInterface {
    /** The magnetic stripe. */
    MAGNETIC,

    /** The contact chip. */
    CHIP,

    /** Contactless — a tap, whether card or phone. */
    CONTACTLESS,
}

/**
 * Whether a sale happened, when the kernel and the bank disagree.
 *
 * The kernel can refuse to complete a transaction the issuer has already authorised — it
 * rejects the issuer data, because the switch protocol carries no field for the issuer's
 * response and a lone tag 8A is not what the kernel wants — and the transaction then ends
 * "Online Decline".
 *
 * Following the kernel there was the worst outcome available: the issuer holds an
 * authorisation, the customer is charged, the council has neither receipt nor money, and the
 * till asks for another attempt that takes a second hold. So a bank approval decides it.
 *
 * What that gives up, plainly: the card never performed its second GENERATE AC, so there is no
 * Transaction Certificate and the authorisation stands alone. A deliberate decision, taken
 * because the alternative charges customers for sales that never complete.
 *
 * Hardware failures are not rescued this way. A negative code is the terminal giving up — card
 * removed, reader fault — and no answer from a bank makes a transaction out of a card that was
 * never read to the end.
 *
 * @param kernelCode the EMV kernel's own result code. Both kernels agree on the shape that
 *   matters here: 0, 1 and 5 are approvals, and anything negative is the terminal giving up.
 */
fun transactionApproved(
    kernelCode: Int,
    switchApproved: Boolean,
    requiresBank: Boolean = false,
): Boolean = when {
    // Banking stands on the bank's answer alone. The kernel approving offline — code 1, or 0
    // when the switch could not be reached — is no withdrawal at all: nobody debited anything.
    requiresBank && !switchApproved -> false
    kernelCode == 0 || kernelCode == 1 || kernelCode == 5 -> true
    kernelCode < 0 -> false
    else -> switchApproved
}

/**
 * What to put on the screen when the kernel ends a transaction without approving it.
 *
 * The switch's words first. It is the only party that knows *why* — "Insufficient funds",
 * "Invalid PIN" — while the kernel's [desc] names the step that stopped, in vendor terms
 * ("Online Decline"), and [code] is a number from a table the cashier does not have. The
 * fallback keeps both, because with nothing from the switch they are all anyone has.
 *
 * Every non-approval comes through here. It used to be decided twice: the decline codes (2 and
 * 6) printed the kernel's label and its number with `switchDisplayMessage` sitting unread beside
 * them, and only the remaining codes consulted it — so the commonest decline of all, the one the
 * bank actually explained, was the one that arrived as "Transaction declined: Online Decline
 * (code=6)".
 *
 * @param fallbackLabel how to describe the outcome when the switch said nothing — a decline and
 *   a kernel failure are not the same thing to the person holding the card.
 */
fun declineMessage(
    code: Int,
    desc: String?,
    fallbackLabel: String,
    switchUnavailable: Boolean,
    switchDisplayMessage: String?,
    switchApproved: Boolean = false,
    transactionType: CardTransactionType = CardTransactionType.PURCHASE,
    localRefusal: String? = null,
): String {
    // The terminal refused before the bank was asked, and said why. Its words go out unaltered —
    // in particular not through the "Incorrect PIN" rewrite below, which would tell a customer
    // who was never asked for a PIN that they had typed it wrongly.
    if (localRefusal != null) return localRefusal

    // The switch was never reached, so whatever it last said is about the line, not the card.
    if (switchUnavailable) return switchDisplayMessage ?: "Bank offline"

    // The bank said yes and the card did not finish — the kernel refused the issuer data and
    // ended the transaction "Online Decline". Showing the switch's own words made the till read
    // "Payment Error: Approved". And an authorisation is standing at the issuer: a retry takes
    // a second hold on the customer for a sale they are being told did not happen, with no
    // reversal anywhere in this codebase to release the first.
    if (switchApproved) {
        return when (transactionType) {
            // The account has been debited. Handing the cash over is the bank's call, not the
            // till's — and neither is trying again, which would debit it twice.
            CardTransactionType.CASH_WITHDRAWAL ->
                "The bank approved this withdrawal but the card did not complete it. " +
                    "The customer's account may have been debited — do not retry, and check " +
                    "with the bank before giving out cash."
            CardTransactionType.CASH_DEPOSIT ->
                "The bank approved this deposit but the card did not complete it. " +
                    "The account may already be credited — do not retry; check with the bank " +
                    "before returning the cash."
            CardTransactionType.BALANCE_ENQUIRY ->
                "The bank answered but the card did not complete the enquiry. Try again."
            CardTransactionType.PURCHASE ->
                "The bank approved this payment but the card did not complete it. " +
                    "The approval stands — do not take payment again; check with the bank first."
        }
    }

    switchDisplayMessage?.let { msg ->
        // "Invalid PIN" is accurate and unhelpful: what the customer needs to know is that the
        // card is fine and the next attempt can succeed.
        return if (msg.contains("PIN", ignoreCase = true))
            "Incorrect PIN. Try again with the correct PIN."
        else msg
    }

    return "$fallbackLabel: ${desc ?: "Unknown"} (code=$code)"
}

/**
 * The ISO 4217 numeric code, four digits, for the currency a sale is priced in.
 *
 * One answer for two consumers: the EMV kernel, which hands it to the card as tag 5F2A and so
 * decides what the cryptogram is computed over, and the switch, which tells the issuer what to
 * recompute it with. They were separate before — a literal in the kernel setup and this
 * function for the switch — and a non-USD sale had the card sign one currency while the issuer
 * checked another.
 *
 * Unknown currencies fall back to USD: a blank 5F2A is refused by the kernel outright, and
 * every currency this estate actually takes is named here.
 */
fun alphaToNumericCurrency(alpha: String): String = when (alpha.uppercase()) {
    "USD" -> "0840"
    "ZWG" -> "0924"  // Zimbabwe Gold, ISO 4217 numeric 924
    "ZWL" -> "0932"  // Retired Zimbabwe Dollar
    "ZAR" -> "0710"
    "EUR" -> "0978"
    "CNY", "RMB" -> "0156"
    "GBP" -> "0826"
    else -> "0840"
}

/**
 * The tags asked of the kernel and forwarded to the issuer as field 55.
 *
 * Named and tested rather than written inline, because what is in this list is what an issuer
 * has to verify a cryptogram with, and what is missing from it is a decline nobody can explain
 * from the till.
 *
 * Three tags carry an application identifier and hosts differ on which they read: 4F is the
 * card's own, 9F06 the one this terminal matched, and 84 the DF Name from the SELECT response.
 * Only 84 was sent, and the acquirer reads 4F or 9F06 — so all three go, and any the kernel
 * does not hold come back empty and are dropped by [IccData] rather than sent as empty tags.
 */
val ICC_TAGS_FOR_ISSUER = arrayOf(
    "9F26", // Application Cryptogram — the ARQC itself
    "9F27", // Cryptogram Information Data
    "9F10", // Issuer Application Data — carries the key index and CVR
    "9F37", // Unpredictable Number
    "9F36", // Application Transaction Counter
    "95",   // Terminal Verification Results
    "9A",   // Transaction Date
    "9C",   // Transaction Type
    "9F02", // Amount, Authorised
    "5F2A", // Transaction Currency Code
    "82",   // Application Interchange Profile
    "9F1A", // Terminal Country Code
    "9F03", // Amount, Other
    "9F33", // Terminal Capabilities
    "9F34", // CVM Results
    "9F35", // Terminal Type
    "9F1E", // IFD Serial Number
    "4F",   // Application Identifier — the card's
    "9F06", // Application Identifier — the terminal's
    "84",   // DF Name
    "9F09", // Application Version Number
    "9F41", // Transaction Sequence Counter
    "5F34", // PAN Sequence Number
    "50",   // Application Label
)

/**
 * The transaction data given to the kernel before the card is read — and therefore the data the
 * card computes its cryptogram over. Every value here is an input the issuer recomputes with,
 * so a wrong one is not a display bug: it is a payment the issuer rejects.
 *
 * Returned as tags and values rather than written straight to the kernel so that what the card
 * signs can be asserted without a terminal in hand.
 */
fun emvTransactionTlv(
    amount: Long,
    currencyNumeric: String,
    sequenceCounter: Long,
    transactionTypeCode: String = CardTransactionType.PURCHASE.emvCode,
): Pair<Array<String>, Array<String>> =
    arrayOf("9C", "9F41", "9F02", "81", "5F2A", "9F1A") to arrayOf(
        transactionTypeCode,               // 9C   - Transaction Type: 00 purchase, 01 cash, 21 deposit, 31 balance
        // 9F41 - Transaction Sequence Counter. FOUR bytes: EMV allows two to four, and this
        // sent six ("%012d") on every chip transaction until 2026-09-23. It travels in field
        // 55, so an acquirer parser reading it into a four-byte field had two bytes too many
        // — their gateway stopped answering rather than declining, which reached the till as
        // "Gateway unavailable" and looked like an outage at both ends.
        // Wraps at 99999999 rather than growing the field, which is the whole point.
        String.format("%08d", sequenceCounter % 100_000_000L),
        String.format("%012d", amount),    // 9F02 - Amount, Authorised (BCD, n12)
        String.format("%08x", amount),     // 81   - Amount, Authorised (binary, 4 bytes)
        currencyNumeric,                   // 5F2A - Transaction Currency Code
        "0716",                            // 9F1A - Terminal Country Code (Zimbabwe)
    )

/**
 * The card interfaces the reader listens on. Everything for a purchase; chip and stripe for
 * banking, where a PIN is required and a tap cannot be relied on to ask for one.
 *
 * Returned as [CardInterface]s rather than one vendor's bitmask: the Ciontek reader wants a
 * bit-or of its own constants and the ZCS reader wants an enum naming the combination, and the
 * rule — which interfaces a withdrawal may use — must not be decided twice.
 */
fun cardTypesFor(transactionType: CardTransactionType): Set<CardInterface> =
    if (transactionType.isBanking) setOf(CardInterface.MAGNETIC, CardInterface.CHIP)
    else setOf(CardInterface.MAGNETIC, CardInterface.CHIP, CardInterface.CONTACTLESS)

/**
 * Whether the cardholder proved themselves with a PIN: an online PIN block captured on the
 * keypad, or the card reporting in 9F34 that it checked one itself and it was right.
 *
 * 9F34 is three bytes — the method, the condition, the result. Methods 01, 03, 04 and 05 are the
 * card verifying the PIN offline (plaintext or enciphered, with or without a signature as
 * well); 02 is online PIN, which only counts with a block to send. A result of 02 is "successful".
 */
fun pinVerified(onlinePinBlock: ByteArray?, cvmResults: String?): Boolean {
    if (onlinePinBlock != null && onlinePinBlock.isNotEmpty()) return true
    if (cvmResults == null || cvmResults.length < 6) return false
    val method = cvmResults.substring(0, 2).toIntOrNull(16)?.and(0x3F) ?: return false
    val result = cvmResults.substring(4, 6).toIntOrNull(16) ?: return false
    return method in setOf(0x01, 0x03, 0x04, 0x05) && result == 0x02
}
