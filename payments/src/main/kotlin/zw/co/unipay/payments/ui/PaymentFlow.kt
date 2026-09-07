package zw.co.unipay.payments.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import zw.co.unipay.payments.card.CardPaymentDriver
import zw.co.unipay.payments.model.TenderCurrency
import zw.co.unipay.payments.model.Money
import zw.co.unipay.payments.switching.SwitchClient
import zw.co.unipay.payments.terminal.TerminalSnapshot

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
 * @param electronicPaymentsEnabled false when this terminal has no identity its switch would
 *        recognise. Card, QR and mobile money are then not offered at all and only cash is;
 *        a payment sent under no identity, or a borrowed one, settles to someone.
 */
@Composable
fun PaymentFlow(
    amount: Money,
    config: PaymentConfig,
    cardDriver: CardPaymentDriver,
    currencies: List<TenderCurrency> = emptyList(),
    convertCurrency: suspend (Money, String) -> Money? = { _, _ -> null },
    cardPaymentEnabled: Boolean = true,
    electronicPaymentsEnabled: Boolean = config.identity.isProvisioned,
    onResult: (PaymentOutcome) -> Unit,
    onDismiss: () -> Unit,
) {
    // Where the switch is, is the Device Owner's to say, and it may change under a running
    // terminal. Reading it per call rather than capturing it here is what lets a re-provisioned
    // till reach the new address without a restart.
    val switchClient = remember { SwitchClient { config.identity.endpoint } }

    var screen by remember { mutableStateOf(PaymentStep.MethodSelection) }
    var network by remember { mutableStateOf(cryptoNetworks.first()) }

    DisposableEffect(switchClient) {
        // shutdownNow, not shutdown: onDispose runs on the main thread, and shutdown()'s
        // graceful wait can block it for up to 5 seconds — the ANR threshold — when the
        // channel's transport is already dead (a QR payment that timed out because the
        // network dropped, for instance).
        onDispose { switchClient.shutdownNow() }
    }

    fun finish(result: PaymentOutcome) {
        onResult(result)
        onDismiss()
    }

    // The card step used to be its own Activity, where the platform's back press ended the
    // payment. In-composition it would otherwise finish the whole application mid-transaction.
    BackHandler(enabled = true) {
        when (screen) {
            PaymentStep.MethodSelection -> finish(PaymentOutcome.Cancelled)
            PaymentStep.Crypto -> screen = PaymentStep.CryptoNetwork
            // Back from a payment step returns to the method list rather than abandoning the
            // sale: a customer who changes their mind about how to pay has not changed their
            // mind about paying.
            else -> screen = PaymentStep.MethodSelection
        }
    }

    when (screen) {
        PaymentStep.MethodSelection -> PaymentMethodDialog(
            paymentAmount = amount,
            // Never enter the card flow without a reader: the screen would sit
            // waiting for a card that can never be presented.
            onCard = { if (cardPaymentEnabled && electronicPaymentsEnabled) screen = PaymentStep.Card },
            onQr = { if (electronicPaymentsEnabled) screen = PaymentStep.Qr },
            onMobileMoney = { if (electronicPaymentsEnabled) screen = PaymentStep.MobileMoney },
            onCrypto = { if (electronicPaymentsEnabled) screen = PaymentStep.CryptoNetwork },
            onCash = { screen = PaymentStep.Cash },
            onDismiss = { finish(PaymentOutcome.Cancelled) },
            cardPaymentEnabled = cardPaymentEnabled && electronicPaymentsEnabled,
            electronicPaymentsEnabled = electronicPaymentsEnabled,
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
            identity = config.identity,
            // The till composes the payload again, so it needs both: the trading name for
            // tag 59 and the receipt number for the bill number in tag 62.
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
                    // A cashier who has just told the customer "the QR didn't go through, do you
                    // have cash" should not have to start the payment again.
                    is QrPaymentResult.SwitchToCash -> { screen = PaymentStep.Cash }
                    // Neither ending is the end of the sale, and neither is the other. A wait
                    // that lapsed and a wait an operator cut short both leave a customer still
                    // standing at the counter wanting to pay, so both come back to the method
                    // list — where Cash is one press away, the same answer back already gives.
                    // Folding the timeout into Cancelled here ended the sale outright, and the
                    // one method that could still have taken the money was the one the till
                    // then stopped offering.
                    QrPaymentResult.Timeout,
                    QrPaymentResult.Cancelled -> screen = PaymentStep.MethodSelection
                }
            },
            onDismiss = {},
        )

        PaymentStep.MobileMoney -> MobileMoneyPaymentDialog(
            amount = amount,
            identity = config.identity,
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
                    // A cashier who has just told the customer "the mobile money payment didn't
                    // go through, do you have cash" should not have to start the payment again.
                    is MobileMoneyPaymentResult.SwitchToCash -> { screen = PaymentStep.Cash }
                    // As on the QR path: a wait that lapsed and a wait an operator cut short
                    // both leave a customer still wanting to pay, so both come back to the
                    // method list where Cash is one press away, rather than ending the sale and
                    // taking that last way of taking the money off the screen with it.
                    MobileMoneyPaymentResult.Timeout,
                    MobileMoneyPaymentResult.Declined,
                    MobileMoneyPaymentResult.Cancelled -> screen = PaymentStep.MethodSelection
                }
            },
            onDismiss = {},
        )

        PaymentStep.CryptoNetwork -> CryptoNetworkDialog(
            paymentAmount = amount,
            onNetwork = { chosen ->
                network = chosen
                screen = PaymentStep.Crypto
            },
            onBack = { screen = PaymentStep.MethodSelection },
        )

        PaymentStep.Crypto -> CryptoPaymentDialog(
            amount = amount,
            identity = config.identity,
            receiptNumber = config.receiptNumber,
            network = network,
            switchClient = switchClient,
            onResult = { result ->
                when (result) {
                    is CryptoPaymentResult.Success -> finish(
                        PaymentOutcome.CryptoApproved(
                            paymentReference = result.paymentReference,
                            authorizationCode = result.authorizationCode,
                            asset = result.asset,
                            chain = result.chain,
                            assetAmount = result.assetAmount,
                        )
                    )
                    // As on every other rail: a cashier who has just said "the crypto
                    // didn't go through, do you have cash" should not have to start
                    // the sale again.
                    is CryptoPaymentResult.SwitchToCash -> { screen = PaymentStep.Cash }
                    // None of these end the sale. A customer whose wallet would not
                    // scan, or who took too long, is still standing at the counter
                    // wanting to pay — so all three come back to the method list where
                    // cash is one press away.
                    // Back to the network question rather than the method list: a
                    // customer whose Tron payment lapsed may simply have meant to pay
                    // on the other one, and making them choose the method again to
                    // find out is a step for nothing.
                    CryptoPaymentResult.Timeout,
                    CryptoPaymentResult.Declined,
                    CryptoPaymentResult.Cancelled -> screen = PaymentStep.CryptoNetwork
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

/**
 * What the flow needs beyond the amount: who this terminal is, and how the shop presents itself.
 *
 * The identity is the Device Owner's and arrives whole — it is not restated field by field here,
 * which is what let a caller pass a merchant id that disagreed with the one the card path used.
 * What remains is presentation: the name printed on a QR code, the receipt it belongs to, and
 * where the terminal is standing.
 */
data class PaymentConfig(
    val identity: TerminalSnapshot,
    val merchantName: String,
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

    /**
     * The bill was settled in crypto. What the customer owed is still the bill's own
     * currency; the asset, chain and amount are recorded beside it because they are
     * the only things anyone can look up if the payment is ever questioned.
     */
    data class CryptoApproved(
        val paymentReference: String,
        val authorizationCode: String?,
        val asset: String,
        val chain: String,
        val assetAmount: String,
    ) : PaymentOutcome()

    data class CashCompleted(
        val tenderedAmount: Money,
        val changeAmount: Money,
    ) : PaymentOutcome()

    data class Failed(val message: String) : PaymentOutcome()
    data object Cancelled : PaymentOutcome()
}

private enum class PaymentStep { MethodSelection, Card, Qr, MobileMoney, CryptoNetwork, Crypto, Cash }

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
    // Every other step in this flow is a dialog. CardPaymentScreen was written to
    // be hosted by CardPaymentActivity - a whole activity - so dropped in here it
    // drew inline, underneath whatever screen the payment was started from.
    // usePlatformDefaultWidth = false keeps it full-bleed the way it was designed.
    //
    // Back is off: once a card flow is counting down, the customer may already
    // have presented a card, and a stray back press must not abandon it. The
    // screen has its own Cancel.
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
    CardPaymentScreen(
        amount = amountCents,
        currency = currency,
        driver = driver,
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
}
