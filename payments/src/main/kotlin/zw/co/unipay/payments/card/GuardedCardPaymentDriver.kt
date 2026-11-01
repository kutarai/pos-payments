package zw.co.unipay.payments.card

import zw.co.unipay.payments.ui.CardNetwork
import zw.co.unipay.payments.ui.CardPaymentResult

/**
 * A [CardPaymentDriver] that checks a requirement at the start of every payment and refuses, as
 * [RefusingCardPaymentDriver] does, when it is not met.
 *
 * It exists so that the object the registry hands out can be **one driver for the life of the
 * process** without freezing what the requirement depends on. A device library's
 * `TerminalSupport.install` needs both: a single instance, because the card screen asks
 * `CardPaymentDrivers.create` again when the cashier presses Cancel and the cancel has to reach
 * the payment in flight; and a merchant configuration read afresh for each payment, because the
 * Device Owner can change it between sales.
 *
 * [unmetRequirement] is asked at the start of each [processPayment] and returns what is missing,
 * or null when nothing is. If it throws, the payment is refused: an application's configuration
 * bug is a refusal on the card screen, not a crash. **[cancel] never consults it.** A cancel must
 * reach the in-flight payment whatever the configuration says at that moment; routing it through
 * the check would turn a configuration that has just gone away into a Cancel button that does
 * nothing.
 */
class GuardedCardPaymentDriver(
    private val driver: CardPaymentDriver,
    private val unmetRequirement: () -> String?,
) : CardPaymentDriver {

    override suspend fun processPayment(
        amount: Long,
        cardNetwork: CardNetwork,
        currency: String,
        transactionType: CardTransactionType,
        onUpdate: ((CardFlowUpdate) -> Unit)?,
    ): CardPaymentResult {
        val unmet = try {
            unmetRequirement()
        } catch (t: Throwable) {
            "the check for it failed (${t.javaClass.simpleName})"
        }
        if (unmet != null) {
            return RefusingCardPaymentDriver(unmet)
                .processPayment(amount, cardNetwork, currency, transactionType, onUpdate)
        }
        return driver.processPayment(amount, cardNetwork, currency, transactionType, onUpdate)
    }

    override suspend fun cancel() = driver.cancel()
}
