package zw.co.unipay.payments.card

import zw.co.unipay.payments.ui.CardNetwork
import zw.co.unipay.payments.ui.CardPaymentResult

/**
 * A [CardPaymentDriver] that takes no card payments and says why.
 *
 * What a device library's `TerminalSupport.install` registers when the application has not given
 * it a switch and a merchant configuration. The alternative is a driver that completes a purchase
 * with no bank behind it, which is right on a bench and wrong at a counter: an application that
 * forgot to configure the bank would be taking card payments that reach nobody, with nothing to
 * tell the cashier or anyone else. A refusal is a missing line that shows up the first time a card
 * is offered; a quiet approval is one that shows up in the reconciliation.
 *
 * It never reads a card, so a customer is not asked to present one only to be turned away, and
 * [reason] is what the cashier's screen shows. Keep it free of the words the card screen routes
 * on ("timeout", "unavailable", "bank offline"), which would put a configuration fault under
 * the wrong heading.
 */
class RefusingCardPaymentDriver(
    /** What is missing, in words a person setting the terminal up can act on. */
    val reason: String,
) : CardPaymentDriver {

    override suspend fun processPayment(
        amount: Long,
        cardNetwork: CardNetwork,
        currency: String,
        transactionType: CardTransactionType,
        onUpdate: ((CardFlowUpdate) -> Unit)?,
    ): CardPaymentResult = CardPaymentResult.Error(
        "Card payments are not set up on this terminal: $reason. Use another way to pay.",
    )

    /** There is never anything in progress. */
    override suspend fun cancel() = Unit
}
