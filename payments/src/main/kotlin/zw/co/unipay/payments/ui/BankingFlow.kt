package zw.co.unipay.payments.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import zw.co.unipay.payments.card.AccountBalance
import zw.co.unipay.payments.card.CardPaymentDriver
import zw.co.unipay.payments.card.CardTransactionType
import zw.co.unipay.payments.switching.SwitchClient
import java.math.BigDecimal

/**
 * Banking at the counter: a cash withdrawal, a cash deposit or a balance enquiry, from the first
 * screen to a result.
 *
 * Withdrawal and deposit ask for the amount first; a balance enquiry has none and starts at the
 * choice of how. Then Card or Mobile, and for a card the customer inserts or swipes it and enters
 * their PIN, and the switch takes it on to their bank.
 *
 * Mobile sends the customer's wallet number to the switch's mobile money banking call — never its
 * payment call, which would debit the wallet of someone depositing cash — and the customer
 * confirms on their phone.
 *
 * @param transactionType one of the banking types; a purchase belongs to [PaymentFlow].
 * @param currency the till's working currency, ISO 4217 alpha-3.
 * @param cardPaymentEnabled false when there is no card reader.
 * @param electronicPaymentsEnabled false when this terminal has no identity the switch would
 *        recognise, in which case nothing here can be done and the screen says so.
 */
@Composable
fun BankingFlow(
    transactionType: CardTransactionType,
    currency: String,
    config: PaymentConfig,
    cardDriver: CardPaymentDriver,
    cardPaymentEnabled: Boolean = true,
    electronicPaymentsEnabled: Boolean = config.identity.isProvisioned,
    onResult: (BankingOutcome) -> Unit,
    onDismiss: () -> Unit,
) {
    require(transactionType.isBanking) {
        "BankingFlow is for withdrawals, deposits and balance enquiries; take a purchase with PaymentFlow"
    }

    var step by remember {
        mutableStateOf(if (transactionType.movesMoney) BankingStep.Amount else BankingStep.Method)
    }
    var amountMinor by remember { mutableLongStateOf(0L) }

    // As in PaymentFlow: where the switch is may change under a running terminal, so it is read
    // per call; shutdownNow because onDispose runs on the main thread.
    val switchClient = remember { SwitchClient { config.identity.endpoint } }
    DisposableEffect(switchClient) {
        onDispose { switchClient.shutdownNow() }
    }

    fun finish(outcome: BankingOutcome) {
        onResult(outcome)
        onDismiss()
    }

    BackHandler(enabled = true) {
        when (step) {
            BankingStep.Amount -> finish(BankingOutcome.Cancelled)
            BankingStep.Method ->
                if (transactionType.movesMoney) step = BankingStep.Amount
                else finish(BankingOutcome.Cancelled)
            // The card and mobile screens have their own ways out and must not be abandoned
            // mid-transaction.
            BankingStep.Card, BankingStep.Mobile -> Unit
        }
    }

    when (step) {
        BankingStep.Amount -> BankingAmountDialog(
            transactionType = transactionType,
            currency = currency,
            initialMinor = amountMinor,
            onContinue = { minor ->
                amountMinor = minor
                step = BankingStep.Method
            },
            onDismiss = { finish(BankingOutcome.Cancelled) },
        )

        BankingStep.Method -> BankingMethodDialog(
            transactionType = transactionType,
            amount = if (transactionType.movesMoney) formatMinor(currency, amountMinor) else null,
            cardEnabled = cardPaymentEnabled && electronicPaymentsEnabled,
            cardReaderMissing = !cardPaymentEnabled,
            electronicPaymentsEnabled = electronicPaymentsEnabled,
            onCard = { if (cardPaymentEnabled && electronicPaymentsEnabled) step = BankingStep.Card },
            onMobile = { if (electronicPaymentsEnabled) step = BankingStep.Mobile },
            // Back to the amount for a withdrawal or deposit, so a wrong figure can be put right
            // without starting again; a balance enquiry has nowhere further back to go.
            onBack = {
                if (transactionType.movesMoney) step = BankingStep.Amount
                else finish(BankingOutcome.Cancelled)
            },
        )

        BankingStep.Card -> Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            CardPaymentScreen(
                amount = amountMinor,
                currency = currency,
                driver = cardDriver,
                transactionType = transactionType,
                // Cancel on the card screen goes back to Card or Mobile, not out of the flow: the
                // customer may have the wrong card, or want the other way.
                onBack = { step = BankingStep.Method },
                onPaymentComplete = { result ->
                    when (result) {
                        is CardPaymentResult.Success -> finish(
                            BankingOutcome.Approved(
                                transactionType = transactionType,
                                amountMinor = result.amount,
                                currency = currency,
                                authorizationCode = result.authorizationCode.ifEmpty { null },
                                cardLastFour = result.cardLastFour,
                                balance = result.balance,
                                method = BankingMethod.CARD,
                            )
                        )
                        is CardPaymentResult.Error -> finish(BankingOutcome.Failed(result.errorMessage))
                        // Handled by onBack, which the card screen calls straight after.
                        CardPaymentResult.Cancelled -> Unit
                        // Not offered for banking; here only so the when is complete.
                        CardPaymentResult.SwitchToCash -> step = BankingStep.Method
                    }
                },
            )
        }

        BankingStep.Mobile -> MobileBankingDialog(
            transactionType = transactionType,
            amountMinor = amountMinor,
            currency = currency,
            identity = config.identity,
            switchClient = switchClient,
            latitude = config.latitude,
            longitude = config.longitude,
            onResult = { result ->
                when (result) {
                    is MobileBankingResult.Success -> finish(
                        BankingOutcome.Approved(
                            transactionType = transactionType,
                            amountMinor = if (transactionType.movesMoney) amountMinor else 0L,
                            currency = currency,
                            authorizationCode = result.authorizationCode,
                            cardLastFour = null,
                            balance = result.balance,
                            method = BankingMethod.MOBILE,
                            mobileNumber = result.mobileNumber,
                            reference = result.paymentReference,
                        )
                    )
                    is MobileBankingResult.Unresolved -> finish(
                        BankingOutcome.Unresolved(
                            transactionType = transactionType,
                            amountMinor = amountMinor,
                            currency = currency,
                            method = BankingMethod.MOBILE,
                            reference = result.paymentReference,
                            message = result.message,
                        )
                    )
                    // Back to Card or Mobile: a customer whose wallet declined may have a card.
                    MobileBankingResult.Cancelled -> step = BankingStep.Method
                }
            },
        )
    }
}

enum class BankingMethod { CARD, MOBILE }

/** How a withdrawal, deposit or balance enquiry ended. */
sealed class BankingOutcome {
    /**
     * The bank said yes. For a withdrawal, hand over [amountMinor]; for a deposit, keep it; for a
     * balance enquiry, [balance] is the answer.
     */
    data class Approved(
        val transactionType: CardTransactionType,
        /** Minor units; zero for a balance enquiry. */
        val amountMinor: Long,
        val currency: String,
        /** Null when the bank gave none, which it does not for a balance enquiry. */
        val authorizationCode: String?,
        val cardLastFour: String?,
        val balance: AccountBalance?,
        val method: BankingMethod = BankingMethod.CARD,
        /** The wallet, when [method] is MOBILE. */
        val mobileNumber: String? = null,
        /** The till's reference for a mobile transaction, which the provider and the switch both hold. */
        val reference: String? = null,
    ) : BankingOutcome()

    /**
     * Nobody said whether it happened: the provider had the request and did not answer. For a
     * withdrawal the wallet may have been debited; for a deposit it may have been credited. No
     * cash should change hands and nothing should be retried until the provider confirms —
     * [reference] is what to ask about.
     */
    data class Unresolved(
        val transactionType: CardTransactionType,
        val amountMinor: Long,
        val currency: String,
        val method: BankingMethod,
        val reference: String,
        val message: String,
    ) : BankingOutcome()

    /** The bank said no, or could not be asked. No money moved — no cash changes hands. */
    data class Failed(val message: String) : BankingOutcome()

    data object Cancelled : BankingOutcome()
}

private enum class BankingStep { Amount, Method, Card, Mobile }

/**
 * "1250" from "12.50", "12.5" or "12". Null for anything that is not a positive amount with at
 * most two decimal places — a third decimal is a typing slip, not a fraction of a cent to round.
 */
internal fun parseAmountMinor(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val value = trimmed.toBigDecimalOrNull() ?: return null
    if (value.signum() <= 0 || value.scale() > 2) return null
    return try {
        value.movePointRight(2).longValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}

@Composable
private fun BankingAmountDialog(
    transactionType: CardTransactionType,
    currency: String,
    initialMinor: Long,
    onContinue: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember {
        mutableStateOf(
            if (initialMinor > 0) BigDecimal.valueOf(initialMinor, 2).toPlainString() else ""
        )
    }
    val minor = parseAmountMinor(text)

    PaymentScreenSurface(onDismissRequest = onDismiss) {
        Text(
            text = transactionType.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = when (transactionType) {
                CardTransactionType.CASH_WITHDRAWAL -> "How much does the customer want to withdraw?"
                else -> "How much is the customer depositing?"
            },
            style = MaterialTheme.typography.titleMedium,
        )

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Amount ($currency)", fontSize = 16.sp) },
            singleLine = true,
            isError = text.isNotBlank() && minor == null,
            supportingText = {
                if (text.isNotBlank() && minor == null) {
                    Text("Enter an amount greater than zero, with at most two decimals")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
        )

        PaymentButton(
            onClick = { minor?.let(onContinue) },
            enabled = minor != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue", fontWeight = FontWeight.Bold)
        }
        PaymentOutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@Composable
private fun BankingMethodDialog(
    transactionType: CardTransactionType,
    amount: String?,
    cardEnabled: Boolean,
    cardReaderMissing: Boolean,
    electronicPaymentsEnabled: Boolean,
    onCard: () -> Unit,
    onMobile: () -> Unit,
    onBack: () -> Unit,
) {
    PaymentScreenSurface(onDismissRequest = onBack) {
        Text(
            text = transactionType.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        amount?.let {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Amount")
                    Text(
                        it,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        if (!electronicPaymentsEnabled) {
            Text(
                text = "This terminal is not set up with the switch, so it cannot do banking",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(text = "Select Method", style = MaterialTheme.typography.titleMedium)

        FullWidthButton("Card", onClick = onCard, enabled = cardEnabled)
        if (cardReaderMissing) {
            Text(
                text = "Card reader not connected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        FullWidthButton("Mobile", onClick = onMobile, enabled = electronicPaymentsEnabled)

        PaymentOutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(if (transactionType.movesMoney) "Back" else "Cancel")
        }
    }
}
