package com.synergy.payments.ui

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.synergy.payments.model.Money
import com.synergy.payments.grpc.payment.MobileMoneyPaymentStatus
import com.synergy.payments.switching.SwitchClient
import com.synergy.payments.switching.SwitchRequests
import com.synergy.payments.terminal.TerminalSnapshot

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
    var countdown by remember { mutableIntStateOf(20) }
    var failureMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var streamJob by remember { mutableStateOf<Job?>(null) }
    var paymentReference by remember { mutableStateOf("") }
    var confirmedMobileNumber by remember { mutableStateOf("") }

    fun startPayment(mobile: String) {
        val ref = "MOB_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        paymentReference = ref
        confirmedMobileNumber = mobile
        flowState = MobileMoneyFlowState.WAITING_CONFIRMATION
        countdown = 20

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
                        MobileMoneyPaymentStatus.MOBILE_CONFIRMED -> {
                            flowState = MobileMoneyFlowState.APPROVED
                            delay(1500)
                            onResult(
                                MobileMoneyPaymentResult.Success(
                                    paymentReference = ref,
                                    authorizationCode = update.authorizationCode.ifEmpty { null },
                                    mobileNumber = mobile
                                )
                            )
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

    // 20-second countdown — only runs while waiting for confirmation
    LaunchedEffect(flowState, countdown) {
        if (flowState == MobileMoneyFlowState.WAITING_CONFIRMATION && countdown > 0) {
            delay(1000)
            countdown--
            if (countdown <= 0) {
                streamJob?.cancel()
                failureMessage = "Payment timed out"
                flowState = MobileMoneyFlowState.FAILED
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { streamJob?.cancel() }
    }

    // Once the prompt has gone to the customer's phone the switch is holding the
    // payment, and a stray back press must not abandon it. Entering the number is
    // still safe to back out of, because nothing has been sent yet.
    val awaitingCustomer = flowState == MobileMoneyFlowState.WAITING_CONFIRMATION

    Dialog(
        onDismissRequest = {
            if (!awaitingCustomer) {
                streamJob?.cancel()
                onResult(MobileMoneyPaymentResult.Cancelled)
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !awaitingCustomer,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
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
                            OutlinedButton(
                                onClick = {
                                    streamJob?.cancel()
                                    onResult(MobileMoneyPaymentResult.Cancelled)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Cancel") }
                            Button(
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
                        OutlinedButton(
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

                        Button(
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

                        Button(
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

                        OutlinedButton(
                            onClick = {
                                streamJob?.cancel()
                                onResult(MobileMoneyPaymentResult.Cancelled)
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
