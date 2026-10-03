package zw.co.unipay.payments.card

import java.io.Serializable

/**
 * What a card transaction asks the customer's bank to do.
 *
 * A purchase pays this merchant. The other three are banking at the counter — an agent hands
 * over cash, takes cash in, or tells the customer what they have — and the switch routes all
 * four along the same card and PIN path, so they share one screen and one driver call here.
 *
 * @param switchCode the ISO 20022 transaction type the switch reads (`Transaction.transaction_type`).
 * @param emvCode    EMV tag 9C, which is the first two digits of the ISO 8583 processing code
 *                   the switch sends on: 00 goods and services, 01 cash, 21 deposit, 31 balance.
 *                   The card signs it, so it has to agree with what the bank is told.
 */
enum class CardTransactionType(
    val switchCode: String,
    val emvCode: String,
    val title: String,
) {
    PURCHASE("CRDP", "00", "Card Payment"),
    CASH_WITHDRAWAL("CSHW", "01", "Cash Withdrawal"),
    CASH_DEPOSIT("CSHD", "21", "Cash Deposit"),
    BALANCE_ENQUIRY("BALC", "31", "Balance Enquiry");

    /** Whether there is an amount. A balance enquiry has none and is sent with zero. */
    val movesMoney: Boolean get() = this != BALANCE_ENQUIRY

    /**
     * Banking at the counter rather than paying for something. These are card-and-PIN only:
     * no contactless, no cash fallback, no approval the card gives without asking the bank —
     * an offline "approved" withdrawal would be cash handed over that no account was debited for.
     */
    val isBanking: Boolean get() = this != PURCHASE
}

/**
 * The account balance the bank sent back, in minor units.
 *
 * Present on an approved balance enquiry, and on a withdrawal or deposit when the bank chose to
 * say what the account now holds. [ledger] is the book balance and is null when the bank sent
 * only the available one; [currency] is ISO 4217 alpha-3, the account's and not the till's.
 */
data class AccountBalance(
    val available: Long,
    val ledger: Long?,
    val currency: String,
) : Serializable
