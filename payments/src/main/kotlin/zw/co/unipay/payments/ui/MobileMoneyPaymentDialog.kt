package zw.co.unipay.payments.ui

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import zw.co.unipay.payments.model.Money
import zw.co.unipay.payments.grpc.payment.MobileMoneyPaymentStatus
import zw.co.unipay.payments.switching.SwitchClient
import zw.co.unipay.payments.switching.SwitchRequests
import zw.co.unipay.payments.terminal.TerminalSnapshot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Serializable
import java.util.UUID

sealed class MobileMoneyPaymentResult : Serializable {
    data class Success(
        val paymentReference: String,
        val authorizationCode: String?,
        val mobileNumber: String
    ) : MobileMoneyPaymentResult()

    object Timeout : MobileMoneyPaymentResult()
    object Declined : MobileMoneyPaymentResult()
    object Cancelled : MobileMoneyPaymentResult()
    object SwitchToCash : MobileMoneyPaymentResult()
}

private enum class MobileMoneyFlowState {
    ENTERING_NUMBER,
    WAITING_CONFIRMATION,
    APPROVED,
    FAILED
}

private const val MOBILE_MONEY_TAG = "MobileMoneyDialog"

/** How long APPROVED stays up before the sale is handed back — long enough to be read. */
private const val APPROVED_DWELL_MS = 1500L

@Composable
fun MobileMoneyPaymentDialog(
    amount: Money,
    identity: TerminalSnapshot,
    switchClient: SwitchClient,
    onResult: (MobileMoneyPaymentResult) -> Unit,
    onDismiss: () -> Unit
) {
    var flowState by remember { mutableStateOf(MobileMoneyFlowState.ENTERING_NUMBER) }
    var mobileNumber by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(PaymentWaits.SWITCH_SECONDS) }
    // What a lapsed wait is called, in this screen's own number of seconds.
    val timedOutMessage = "Timed out — no confirmation after ${PaymentWaits.SWITCH_SECONDS} seconds"
    var failureMessage by remember { mutableStateOf("") }
    // Whether the countdown is what ended it, as opposed to a decline or an operator who
    // pressed Cancel. The screen shows all of them the same way — a reason and a way out — but
    // the till's record should not call a lapsed wait a cancellation.
    var timedOut by remember { mutableStateOf(false) }
    // Held for the hand-off below, which no longer happens inside the stream's coroutine.
    var authorizationCode by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var streamJob by remember { mutableStateOf<Job?>(null) }
    var paymentReference by remember { mutableStateOf("") }
    var confirmedMobileNumber by remember { mutableStateOf("") }

    fun startPayment(mobile: String) {
        val ref = "MOB_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        paymentReference = ref
        confirmedMobileNumber = mobile
        flowState = MobileMoneyFlowState.WAITING_CONFIRMATION
        countdown = PaymentWaits.SWITCH_SECONDS
        timedOut = false
        authorizationCode = null

        streamJob?.cancel()
        streamJob = coroutineScope.launch {
            try {
                val request = SwitchRequests.mobileMoney(
                    identity = identity,
                    paymentReference = ref,
                    currency = amount.currency,
                    amountMinor = (amount.amount * 100).toLong(),
                    mobileNumber = mobile,
                    latitude = 0.0,
                    longitude = 0.0,
                )

                Log.d(MOBILE_MONEY_TAG, "Opening gRPC stream: ref=$ref, mobile=$mobile")
                val flow = switchClient.initiateMobileMoneyPayment(request)

                flow.collect { update ->
                    Log.d(MOBILE_MONEY_TAG, "Mobile money update: status=${update.status}, ref=$ref")
                    when (update.status) {
                        // Recorded, not concluded. The hand-off waits a moment so the cashier
                        // can read APPROVED, and a moment spent inside this coroutine is a
                        // moment in which cancelling the stream would swallow the sale — so it
                        // happens below, where nothing cancels it.
                        MobileMoneyPaymentStatus.MOBILE_CONFIRMED -> {
                            authorizationCode = update.authorizationCode.ifEmpty { null }
                            flowState = MobileMoneyFlowState.APPROVED
                        }
                        MobileMoneyPaymentStatus.MOBILE_DECLINED -> {
                            failureMessage = "Payment was declined"
                            flowState = MobileMoneyFlowState.FAILED
                        }
                        MobileMoneyPaymentStatus.MOBILE_TIMED_OUT -> {
                            failureMessage = "Bank unreachable — no response received"
                            flowState = MobileMoneyFlowState.FAILED
                        }
                        else -> {}
                    }
                }
            } catch (_: CancellationException) {
                Log.d(MOBILE_MONEY_TAG, "Stream cancelled: ref=$paymentReference")
            } catch (e: Exception) {
                // gRPC error — countdown handles the timeout transition
                Log.e(MOBILE_MONEY_TAG, "Stream error (countdown still running): ref=$paymentReference", e)
            }
        }
    }

    // A payment the switch has confirmed is reported from here, and not from the stream that
    // carried the news: that coroutine is cancelled on timeout and again on dismissal, and
    // anything it still owed at the moment of a cancel is simply never delivered. Nothing
    // cancels the composition but the dialog going away.
    LaunchedEffect(flowState) {
        if (flowState == MobileMoneyFlowState.APPROVED) {
            delay(APPROVED_DWELL_MS)
            onResult(
                MobileMoneyPaymentResult.Success(
                    paymentReference = paymentReference,
                    authorizationCode = authorizationCode,
                    mobileNumber = confirmedMobileNumber,
                )
            )
        }
    }

    // The wait on the customer's phone, bounded. Thirty seconds — the comment here said twenty
    // long after PaymentWaits became the one place that decides.
    LaunchedEffect(flowState, countdown) {
        if (flowState == MobileMoneyFlowState.WAITING_CONFIRMATION && countdown > 0) {
            delay(1000)

            // Read again after the second, not only before it. A confirmation arriving during
            // that second ends the wait there and then, but this effect is only torn down at
            // the next recomposition — a frame away, and a frame is long enough for the lines
            // below to cancel the stream out from under an approval.
            if (flowState != MobileMoneyFlowState.WAITING_CONFIRMATION) return@LaunchedEffect

            countdown--
            if (countdown <= 0) {
                timedOut = true
                streamJob?.cancel()
                failureMessage = timedOutMessage
                flowState = MobileMoneyFlowState.FAILED
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { streamJob?.cancel() }
    }

    // Once the prompt has gone to the customer's phone the switch is holding the payment, and
    // a stray back press must not abandon it. Entering the number is still safe to back out of,
    // because nothing has been sent yet, and so is the ending screen. APPROVED is shut: the
    // payment is confirmed and part way through being handed back, and a back press during that
    // second would report a paid customer as a cancellation.
    val dismissable = flowState == MobileMoneyFlowState.ENTERING_NUMBER ||
        flowState == MobileMoneyFlowState.FAILED

    Dialog(
        onDismissRequest = {
            if (dismissable) {
                streamJob?.cancel()
                onResult(
                    if (timedOut) MobileMoneyPaymentResult.Timeout
                    else MobileMoneyPaymentResult.Cancelled
                )
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissable,
            dismissOnClickOutside = false
        )
    ) {
        // Full bleed, like every other payment step. These two keep their own
        // layout rather than moving to PaymentScreenSurface: both were tuned to fit
        // a short till screen — the QR one after its Cancel button was found cut off
        // — and they carry their own scrolling to match. Wrapping tuned content in a
        // second scroller would undo the tuning to gain a shared wrapper.
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Mobile Money",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                // Amount card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Amount", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            amount.format(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                when (flowState) {
                    MobileMoneyFlowState.ENTERING_NUMBER -> {
                        Text(
                            "Enter the customer's mobile wallet number",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        OutlinedTextField(
                            value = mobileNumber,
                            onValueChange = { mobileNumber = it },
                            label = { Text("Mobile number") },
                            placeholder = { Text("+263...") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PaymentOutlinedButton(
                                onClick = {
                                    streamJob?.cancel()
                                    onResult(MobileMoneyPaymentResult.Cancelled)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Cancel") }
                            PaymentButton(
                                onClick = { startPayment(mobileNumber.trim()) },
                                modifier = Modifier.weight(1f),
                                enabled = mobileNumber.isNotBlank()
                            ) { Text("Send Request") }
                        }
                    }

                    MobileMoneyFlowState.WAITING_CONFIRMATION -> {
                        Text(
                            "Request sent to $confirmedMobileNumber",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "Waiting for customer to confirm payment...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        PaymentCountdown(seconds = countdown, warnAt = 5)
                        PaymentOutlinedButton(
                            onClick = {
                                streamJob?.cancel()
                                onResult(MobileMoneyPaymentResult.Cancelled)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Cancel") }
                    }

                    MobileMoneyFlowState.APPROVED -> {
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            "APPROVED",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = PaymentColors.success
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                    }

                    MobileMoneyFlowState.FAILED -> {
                        PaymentErrorMessage(failureMessage)
                        Spacer(modifier = Modifier.height(8.dp))

                        PaymentButton(
                            onClick = {
                                // Pre-fill number from previous attempt and go back to input
                                mobileNumber = confirmedMobileNumber
                                flowState = MobileMoneyFlowState.ENTERING_NUMBER
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PaymentColors.secondaryAction
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Retry", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }

                        PaymentButton(
                            onClick = {
                                streamJob?.cancel()
                                onResult(MobileMoneyPaymentResult.SwitchToCash)
                                onDismiss()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Pay with Cash", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }

                        PaymentOutlinedButton(
                            onClick = {
                                streamJob?.cancel()
                                // Cancel here acknowledges an ending, it does not cause one.
                                // Whoever reconciles this sale wants the reason it did not
                                // happen — a wait that ran out, not the button that closed the
                                // message. The two Cancels on the earlier screens do cause it.
                                onResult(
                                    if (timedOut) MobileMoneyPaymentResult.Timeout
                                    else MobileMoneyPaymentResult.Cancelled
                                )
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Cancel") }
                    }
                }
            }
        }
    }
}
