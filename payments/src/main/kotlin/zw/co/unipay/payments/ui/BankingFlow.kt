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
import java.math.BigDecimal

/**
 * Banking at the counter: a cash withdrawal, a cash deposit or a balance enquiry, from the first
 * screen to a result.
 *
 * Withdrawal and deposit ask for the amount first; a balance enquiry has none and starts at the
 * choice of how. Then Card or Mobile, and for a card the customer inserts or swipes it and enters
 * their PIN, and the switch takes it on to their bank.
 *
 * Mobile is shown and not yet offered. The switch's mobile money call is a payment — it debits the
 * customer's wallet and pays the merchant — and has no way to say "withdrawal", "deposit" or
 * "balance". A deposit sent that way would take money out of the account of the person handing
 * cash over. It is enabled when the switch has a call that means the right thing.
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
            // The card screen has its own Cancel and must not be abandoned mid-card.
            BankingStep.Card -> Unit
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
    }
}

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
    ) : BankingOutcome()

    /** The bank said no, or could not be asked. No money moved — no cash changes hands. */
    data class Failed(val message: String) : BankingOutcome()

    data object Cancelled : BankingOutcome()
}

private enum class BankingStep { Amount, Method, Card }

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

        FullWidthButton("Mobile", onClick = {}, enabled = false)
        Text(
            text = "Mobile ${transactionType.title.lowercase()} is not available yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PaymentOutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(if (transactionType.movesMoney) "Back" else "Cancel")
        }
    }
}
