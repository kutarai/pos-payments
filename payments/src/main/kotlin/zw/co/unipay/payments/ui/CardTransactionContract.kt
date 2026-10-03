package zw.co.unipay.payments.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import zw.co.unipay.payments.card.CardTransactionType

/**
 * One card transaction for [CardTransactionContract] to run.
 *
 * @param amountMinor minor units; ignored for a balance enquiry, required for the rest.
 * @param currency    ISO 4217 alpha-3 the till is working in. For a balance enquiry the bank
 *                    answers in the account's own currency, which may differ.
 */
data class CardTransactionRequest(
    val type: CardTransactionType,
    val amountMinor: Long = 0L,
    val currency: String = "USD",
) {
    init {
        require(!type.movesMoney || amountMinor > 0) {
            "${type.title} needs an amount greater than zero; got $amountMinor"
        }
    }

    companion object {
        fun withdrawal(amountMinor: Long, currency: String) =
            CardTransactionRequest(CardTransactionType.CASH_WITHDRAWAL, amountMinor, currency)

        fun deposit(amountMinor: Long, currency: String) =
            CardTransactionRequest(CardTransactionType.CASH_DEPOSIT, amountMinor, currency)

        fun balanceEnquiry(currency: String) =
            CardTransactionRequest(CardTransactionType.BALANCE_ENQUIRY, 0L, currency)
    }
}

/**
 * Runs a card transaction on [CardPaymentActivity] and hands back what happened.
 *
 * ```
 * val bank = registerForActivityResult(CardTransactionContract()) { result ->
 *     when (result) {
 *         is CardPaymentResult.Success -> // cash out, cash in, or read result.balance
 *         is CardPaymentResult.Error -> // result.errorMessage says why
 *         else -> // the operator backed out
 *     }
 * }
 * bank.launch(CardTransactionRequest.withdrawal(2_000, "USD"))
 * ```
 *
 * Only a [CardPaymentResult.Success] means the bank moved money or answered; anything else
 * means it did not, and for a withdrawal no cash should leave the drawer.
 *
 * The application declares [CardPaymentActivity] in its own manifest, as it already must to take
 * card payments, and registers a card driver at start-up.
 */
class CardTransactionContract : ActivityResultContract<CardTransactionRequest, CardPaymentResult>() {

    override fun createIntent(context: Context, input: CardTransactionRequest): Intent =
        Intent(context, CardPaymentActivity::class.java)
            .putExtra(CardPaymentActivity.EXTRA_TRANSACTION_TYPE, input.type.name)
            .putExtra(CardPaymentActivity.EXTRA_AMOUNT, input.amountMinor)
            .putExtra(CardPaymentActivity.EXTRA_CURRENCY, input.currency)

    override fun parseResult(resultCode: Int, intent: Intent?): CardPaymentResult {
        if (resultCode != Activity.RESULT_OK || intent == null) return CardPaymentResult.Cancelled
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(CardPaymentActivity.EXTRA_RESULT, CardPaymentResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(CardPaymentActivity.EXTRA_RESULT) as? CardPaymentResult
        }
        return result ?: CardPaymentResult.Cancelled
    }
}
