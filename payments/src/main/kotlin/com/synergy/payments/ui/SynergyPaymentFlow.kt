package com.synergy.payments.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.synergy.payments.card.CardPaymentDriver
import com.synergy.payments.model.TenderCurrency
import com.synergy.payments.model.Money
import com.synergy.payments.switching.SwitchClient

/**
 * The whole payment, from "how would you like to pay" to a result.
 *
 * Card, QR, mobile money and cash, with the switch conversation and the screens
 * that go with each. A caller hands it an amount and a card driver and gets back
 * one [PaymentOutcome]; everything between is this function's business.
 *
 * It lives here rather than in an application because the counter that sells a
 * burial plot and the counter that sells a licence take money the same way, and
 * two copies of a payment flow drift apart exactly where it costs money. It was
 * an application's copy until it was moved here.
 *
 * @param amount what is owed, in the bill's currency.
 * @param config merchant and terminal identity, and where the switch is.
 * @param cardDriver the terminal's card flow — CS20 supplies one.
 * @param currencies what the counter will accept as cash; empty means the bill's
 *        currency only.
 * @param convertCurrency how to value a tender given in another currency.
 * @param cardPaymentEnabled false when there is no card reader, so the card tile
 *        is offered but refuses rather than opening a flow that cannot finish.
 */
@Composable
fun SynergyPaymentFlow(
    amount: Money,
    config: PaymentConfig,
    cardDriver: CardPaymentDriver,
    currencies: List<TenderCurrency> = emptyList(),
    convertCurrency: suspend (Money, String) -> Money? = { _, _ -> null },
    cardPaymentEnabled: Boolean = true,
    onResult: (PaymentOutcome) -> Unit,
    onDismiss: () -> Unit,
) {
    val switchClient = remember(config.switchHost, config.switchPort) {
        SwitchClient(config.switchHost, config.switchPort)
    }

    var screen by remember { mutableStateOf(PaymentStep.MethodSelection) }

    DisposableEffect(switchClient) {
        onDispose { switchClient.shutdown() }
    }

    fun finish(result: PaymentOutcome) {
        onResult(result)
        onDismiss()
    }

    when (screen) {
        PaymentStep.MethodSelection -> PaymentMethodDialog(
            paymentAmount = amount,
            // Never enter the card flow without a reader: the screen would sit
            // waiting for a card that can never be presented.
            onCard = { if (cardPaymentEnabled) screen = PaymentStep.Card },
            onQr = { screen = PaymentStep.Qr },
            onMobileMoney = { screen = PaymentStep.MobileMoney },
            onCash = { screen = PaymentStep.Cash },
            onDismiss = { finish(PaymentOutcome.Cancelled) },
            cardPaymentEnabled = cardPaymentEnabled,
        )

        PaymentStep.Card -> CardPaymentStep(
            amountCents = amount.cents,
            currency = amount.currency,
            driver = cardDriver,
            onSwitchToCash = { screen = PaymentStep.Cash },
            onResult = ::finish,
        )

        PaymentStep.Qr -> QrPaymentDialog(
            amount = amount,
            merchantId = config.merchantId,
            terminalId = config.terminalId,
            merchantName = config.merchantName,
            receiptNumber = config.receiptNumber,
            latitude = config.latitude,
            longitude = config.longitude,
            switchClient = switchClient,
            onResult = { result ->
                when (result) {
                    is QrPaymentResult.Success -> finish(
                        PaymentOutcome.QrApproved(
                            paymentReference = result.paymentReference,
                            authorizationCode = result.authorizationCode,
                            qrCodeData = result.qrCodeData,
                        )
                    )
                    else -> finish(PaymentOutcome.Cancelled)
                }
            },
            onDismiss = {},
        )

        PaymentStep.MobileMoney -> MobileMoneyPaymentDialog(
            amount = amount,
            terminalId = config.terminalId,
            merchantId = config.merchantId,
            switchClient = switchClient,
            onResult = { result ->
                when (result) {
                    is MobileMoneyPaymentResult.Success -> finish(
                        PaymentOutcome.MobileMoneyApproved(
                            paymentReference = result.paymentReference,
                            authorizationCode = result.authorizationCode,
                            mobileNumber = result.mobileNumber,
                        )
                    )
                    else -> finish(PaymentOutcome.Cancelled)
                }
            },
            onDismiss = {},
        )

        PaymentStep.Cash -> CashPaymentDialog(
            total = amount,
            currencies = currencies,
            convertCurrency = convertCurrency,
            onResult = { result ->
                when (result) {
                    is CashResult.Completed -> {
                        val tendered = result.tenderedAmount
                        finish(
                            PaymentOutcome.CashCompleted(
                                tenderedAmount = tendered,
                                // Change is worked out against what is owed, never
                                // taken from the dialog: the tender may have been
                                // given in another currency.
                                changeAmount = Money(
                                    amount = maxOf(tendered.amount - amount.amount, 0.0),
                                    currency = amount.currency,
                                ),
                            )
                        )
                    }
                    is CashResult.Cancelled -> finish(PaymentOutcome.Cancelled)
                }
            },
            onDismiss = {},
        )
    }
}

/** Merchant and terminal identity, and where the switch is. */
data class PaymentConfig(
    val merchantId: String,
    val terminalId: String,
    val merchantName: String,
    val switchHost: String,
    val switchPort: Int,
    val receiptNumber: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
)

/** How a payment ended, whichever way it was taken. */
sealed class PaymentOutcome {
    data class CardApproved(
        val authorizationCode: String?,
        val cardLastFour: String?,
        val cardType: String?,
        val cardEntryMode: String? = null,
        val emvData: Map<String, String> = emptyMap(),
    ) : PaymentOutcome()

    data class QrApproved(
        val paymentReference: String,
        val authorizationCode: String?,
        val qrCodeData: String,
    ) : PaymentOutcome()

    data class MobileMoneyApproved(
        val paymentReference: String,
        val authorizationCode: String?,
        val mobileNumber: String,
    ) : PaymentOutcome()

    data class CashCompleted(
        val tenderedAmount: Money,
        val changeAmount: Money,
    ) : PaymentOutcome()

    data class Failed(val message: String) : PaymentOutcome()
    data object Cancelled : PaymentOutcome()
}

private enum class PaymentStep { MethodSelection, Card, Qr, MobileMoney, Cash }

/**
 * The card step: runs the driver and turns its result into an outcome. Separate
 * from the flow so the screen it shows can change without the flow changing.
 */
@Composable
private fun CardPaymentStep(
    amountCents: Long,
    currency: String,
    driver: CardPaymentDriver,
    onSwitchToCash: () -> Unit,
    onResult: (PaymentOutcome) -> Unit,
) {
    CardPaymentScreen(
        amount = amountCents,
        currency = currency,
        onBack = { onResult(PaymentOutcome.Cancelled) },
        onPaymentComplete = { result ->
            when (result) {
                is CardPaymentResult.Success -> onResult(
                    PaymentOutcome.CardApproved(
                        authorizationCode = result.authorizationCode,
                        cardLastFour = result.cardLastFour,
                        cardType = result.cardType,
                        cardEntryMode = result.cardEntryMode,
                        emvData = result.emvData,
                    )
                )
                // The card screen offers "pay cash instead" once a card has
                // failed; the flow honours it rather than ending the payment.
                is CardPaymentResult.SwitchToCash -> onSwitchToCash()
                is CardPaymentResult.Error -> onResult(
                    PaymentOutcome.Failed(result.errorMessage)
                )
                CardPaymentResult.Cancelled -> onResult(PaymentOutcome.Cancelled)
            }
        },
    )
}
