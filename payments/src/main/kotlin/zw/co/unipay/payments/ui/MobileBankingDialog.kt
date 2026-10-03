package zw.co.unipay.payments.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import zw.co.unipay.payments.card.AccountBalance
import zw.co.unipay.payments.card.CardTransactionType
import zw.co.unipay.payments.grpc.payment.MobileMoneyPaymentStatus
import zw.co.unipay.payments.grpc.payment.MobileMoneyPaymentUpdate
import zw.co.unipay.payments.switching.SwitchClient
import zw.co.unipay.payments.switching.SwitchRequests
import zw.co.unipay.payments.terminal.TerminalSnapshot
import java.util.UUID

internal sealed class MobileBankingResult {
    data class Success(
        val paymentReference: String,
        val authorizationCode: String?,
        val mobileNumber: String,
        val balance: AccountBalance?,
    ) : MobileBankingResult()

    /** The provider may have moved the money and not said so. Nothing to retry; something to check. */
    data class Unresolved(val paymentReference: String, val mobileNumber: String, val message: String) :
        MobileBankingResult()

    data object Cancelled : MobileBankingResult()
}

private enum class MobileBankingState {
    ENTERING_NUMBER,
    WAITING_CONFIRMATION,
    APPROVED,

    /** Definitely did not happen: declined, refused, or never reached the provider. Retry is safe. */
    FAILED,

    /** May have happened. No Retry — a second attempt under a new reference could move the money twice. */
    UNRESOLVED,
}

private const val MOBILE_BANKING_TAG = "MobileBankingDialog"

/**
 * A cash out, cash in or balance enquiry on the customer's mobile wallet.
 *
 * The agent enters the wallet number; the switch sends the request to the provider, the customer
 * confirms on their phone, and the outcome comes back on the same stream. A confirmed withdrawal
 * or deposit waits on Done with the instruction for the cash, as the card screen does.
 *
 * What differs from a mobile money payment is the ending where nobody answered. A payment that
 * lapsed can be tried again; a cash out that lapsed after the provider had it may already have
 * debited the wallet, and a retry under a new reference could debit it again. So once the switch
 * has confirmed it holds the request, a timeout for anything that moves money is "unresolved" —
 * check with the provider before cash changes hands — and Retry is not offered.
 */
@Composable
internal fun MobileBankingDialog(
    transactionType: CardTransactionType,
    amountMinor: Long,
    currency: String,
    identity: TerminalSnapshot,
    switchClient: SwitchClient,
    latitude: Double,
    longitude: Double,
    onResult: (MobileBankingResult) -> Unit,
) {
    var state by remember { mutableStateOf(MobileBankingState.ENTERING_NUMBER) }
    var mobileNumber by remember { mutableStateOf("") }
    var sentTo by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(PaymentWaits.SWITCH_SECONDS) }
    var message by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    var authorizationCode by remember { mutableStateOf<String?>(null) }
    var balance by remember { mutableStateOf<AccountBalance?>(null) }
    // Whether the switch said it holds the request. Before that, a failure means nothing reached
    // the provider; after it, the provider's answer is the only one that counts.
    var switchHasIt by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var streamJob by remember { mutableStateOf<Job?>(null) }

    val amountText = formatMinor(currency, amountMinor)

    /** The ending for a request whose outcome nobody reported. */
    fun lapsed(reason: String) {
        message = reason
        state = if (transactionType.movesMoney && switchHasIt) MobileBankingState.UNRESOLVED
        else MobileBankingState.FAILED
    }

    fun send(mobile: String) {
        val ref = "MOB_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        reference = ref
        sentTo = mobile
        state = MobileBankingState.WAITING_CONFIRMATION
        countdown = PaymentWaits.SWITCH_SECONDS
        switchHasIt = false
        authorizationCode = null
        balance = null

        streamJob?.cancel()
        streamJob = scope.launch {
            try {
                val request = SwitchRequests.mobileMoneyBanking(
                    identity = identity,
                    transactionType = transactionType,
                    paymentReference = ref,
                    currency = currency,
                    amountMinor = amountMinor,
                    mobileNumber = mobile,
                    latitude = latitude,
                    longitude = longitude,
                )
                switchClient.initiateMobileMoneyBanking(request).collect { update ->
                    Log.d(MOBILE_BANKING_TAG, "update: status=${update.status}, ref=$ref")
                    when (update.status) {
                        MobileMoneyPaymentStatus.MOBILE_PENDING -> switchHasIt = true
                        MobileMoneyPaymentStatus.MOBILE_CONFIRMED -> {
                            authorizationCode = update.authorizationCode.ifEmpty { null }
                            balance = mobileBalanceOf(update)
                            state = MobileBankingState.APPROVED
                        }
                        MobileMoneyPaymentStatus.MOBILE_DECLINED -> {
                            message = update.message.ifBlank { "Declined by the provider" }
                            state = MobileBankingState.FAILED
                        }
                        // The switch's own words: for a cash movement they already say not to
                        // pay out or retry until the provider confirms.
                        MobileMoneyPaymentStatus.MOBILE_TIMED_OUT ->
                            lapsed(update.message.ifBlank { "The provider did not answer" })
                        else -> Unit
                    }
                }
                if (state == MobileBankingState.WAITING_CONFIRMATION) {
                    lapsed("The switch closed the request without an answer")
                }
            } catch (_: CancellationException) {
                Log.d(MOBILE_BANKING_TAG, "stream cancelled: ref=$ref")
            } catch (e: StatusRuntimeException) {
                Log.e(MOBILE_BANKING_TAG, "stream failed: ref=$ref, status=${e.status}", e)
                if (state != MobileBankingState.WAITING_CONFIRMATION) return@launch
                when (e.status.code) {
                    // An older switch: it does not have the call, so it did nothing.
                    Status.Code.UNIMPLEMENTED -> {
                        message = "This switch does not support mobile ${transactionType.title.lowercase()} yet"
                        state = MobileBankingState.FAILED
                    }
                    else -> lapsed(
                        if (switchHasIt) unresolvedMessage(transactionType)
                        else "Could not reach the switch — nothing was sent"
                    )
                }
            }
        }
    }

    // Bounded wait on the customer's phone, as for a payment. The switch gives the provider
    // less than this, so normally its answer arrives first.
    LaunchedEffect(state, countdown) {
        if (state == MobileBankingState.WAITING_CONFIRMATION && countdown > 0) {
            delay(1000)
            if (state != MobileBankingState.WAITING_CONFIRMATION) return@LaunchedEffect
            countdown--
            if (countdown <= 0) {
                streamJob?.cancel()
                lapsed(
                    if (switchHasIt) unresolvedMessage(transactionType)
                    else "Timed out — no response after ${PaymentWaits.SWITCH_SECONDS} seconds"
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { streamJob?.cancel() }
    }

    // Back is safe only where nothing is in flight and nothing is owed: entering the number, and
    // a definite failure. Waiting, approved and unresolved each have their own way out.
    val dismissable = state == MobileBankingState.ENTERING_NUMBER || state == MobileBankingState.FAILED

    PaymentScreenSurface(
        onDismissRequest = { if (dismissable) onResult(MobileBankingResult.Cancelled) },
        dismissOnBackPress = dismissable,
    ) {
        Text(
            text = "Mobile ${transactionType.title}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        if (transactionType.movesMoney) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Amount", style = MaterialTheme.typography.bodyMedium)
                    Text(amountText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
        }

        when (state) {
            MobileBankingState.ENTERING_NUMBER -> {
                Text(
                    "Enter the customer's mobile wallet number",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                OutlinedTextField(
                    value = mobileNumber,
                    onValueChange = { mobileNumber = it },
                    label = { Text("Mobile number") },
                    placeholder = { Text("+263...") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PaymentOutlinedButton(
                        onClick = { onResult(MobileBankingResult.Cancelled) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Back") }
                    PaymentButton(
                        onClick = { send(mobileNumber.trim()) },
                        modifier = Modifier.weight(1f),
                        enabled = mobileNumber.isNotBlank(),
                    ) { Text("Send Request") }
                }
            }

            MobileBankingState.WAITING_CONFIRMATION -> {
                Text("Request sent to $sentTo", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Text(
                    "Waiting for the customer to confirm on their phone...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                PaymentCountdown(seconds = countdown, warnAt = 5)
                // No Cancel. Closing the stream does not withdraw a request the provider already
                // has; it only stops the till hearing the answer — and for a cash out, that turns
                // a clean outcome into one somebody has to chase.
            }

            MobileBankingState.APPROVED -> {
                Text("APPROVED", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = PaymentColors.success)
                BankingApprovalDetails(
                    transactionType = transactionType,
                    amount = amountText,
                    balance = balance,
                )
                PaymentButton(
                    onClick = {
                        onResult(
                            MobileBankingResult.Success(
                                paymentReference = reference,
                                authorizationCode = authorizationCode,
                                mobileNumber = sentTo,
                                balance = balance,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Done", fontWeight = FontWeight.Bold) }
            }

            MobileBankingState.FAILED -> {
                PaymentErrorMessage(message)
                PaymentButton(
                    onClick = {
                        mobileNumber = sentTo
                        state = MobileBankingState.ENTERING_NUMBER
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Try Again", fontWeight = FontWeight.Bold) }
                PaymentOutlinedButton(
                    onClick = { onResult(MobileBankingResult.Cancelled) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back") }
            }

            MobileBankingState.UNRESOLVED -> {
                PaymentErrorMessage(message)
                Text(
                    "Reference $reference",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PaymentButton(
                    onClick = { onResult(MobileBankingResult.Unresolved(reference, sentTo, message)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Done", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** The balance on a mobile update, or null when the provider sent none — the currency says which. */
internal fun mobileBalanceOf(update: MobileMoneyPaymentUpdate): AccountBalance? =
    if (update.balanceCurrency.isBlank()) null
    else AccountBalance(
        available = update.availableBalance,
        ledger = update.ledgerBalance.takeIf { it != 0L },
        currency = update.balanceCurrency,
    )

private fun unresolvedMessage(transactionType: CardTransactionType): String = when (transactionType) {
    CardTransactionType.CASH_WITHDRAWAL ->
        "No answer from the provider. The wallet may have been debited — do not hand out cash " +
            "or retry until the provider confirms."
    CardTransactionType.CASH_DEPOSIT ->
        "No answer from the provider. The wallet may already be credited — do not retry or " +
            "return the cash until the provider confirms."
    else -> "No answer from the provider. Try again."
}
