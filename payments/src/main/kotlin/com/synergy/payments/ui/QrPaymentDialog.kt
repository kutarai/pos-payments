package com.synergy.payments.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.synergy.payments.model.Money
import com.synergy.payments.qr.EmvcoQrGenerator
import com.synergy.payments.qr.QrMacSealers
import com.synergy.payments.card.TerminalConfig
import com.synergy.payments.grpc.payment.QrPaymentStatus
import com.synergy.payments.switching.SwitchClient
import com.synergy.payments.switching.SwitchRequests
import com.synergy.payments.terminal.TerminalSnapshot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Serializable
import java.util.UUID

sealed class QrPaymentResult : Serializable {
    data class Success(
        val paymentReference: String,
        val authorizationCode: String?,
        val qrCodeData: String
    ) : QrPaymentResult()

    object Timeout : QrPaymentResult()
    object Cancelled : QrPaymentResult()
    object SwitchToCash : QrPaymentResult()
}

private enum class QrFlowState {
    /**
     * Opened the session, waiting for the switch to send the code back.
     *
     * A state of its own rather than a blank QR frame: the till has nothing to show yet, and a
     * customer holding a phone at an empty square is a customer who has been told to scan
     * something that is not there.
     */
    AWAITING_QR,
    DISPLAYING_QR,
    WAITING_CONFIRMATION,
    APPROVED,
    TIMEOUT
}

private const val TAG = "QrPaymentDialog"

@Composable
fun QrPaymentDialog(
    amount: Money,
    identity: TerminalSnapshot,
    merchantName: String,
    receiptNumber: String,
    /**
     * Tag 60. Not in managed configuration, because the switch does not hold one — it is the
     * town on a printed receipt, and a wrong one costs a wallet a line of display rather than
     * a misrouted payment. The scheme's own record is what a payer's bank shows.
     */
    merchantCity: String = "HARARE",
    latitude: Double,
    longitude: Double,
    switchClient: SwitchClient,
    onResult: (QrPaymentResult) -> Unit,
    onDismiss: () -> Unit
) {
    var flowState by remember { mutableStateOf(QrFlowState.AWAITING_QR) }
    var countdown by remember { mutableIntStateOf(PaymentWaits.SWITCH_SECONDS) }
    // Why it ended. A declined payment, a customer who never scanned, and a switch that could
    // not be reached were all announced as "Payment not received" - true of all three and
    // useful for none, and the one an operator most needs to tell apart is the one where the
    // problem is the bank rather than the customer.
    var failureMessage by remember { mutableStateOf("Payment not received") }
    val coroutineScope = rememberCoroutineScope()
    var streamJob by remember { mutableStateOf<Job?>(null) }

    // The reference is ours and the payload is the switch's. Both are replaced on a retry:
    // a fresh reference opens a new session, and the code that belongs to the old one must not
    // stay on screen where somebody could still scan it.
    var qrPayload by remember { mutableStateOf("") }
    var paymentReference by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    /**
     * Opens a new sale.
     *
     * Only the reference is minted here — it is this terminal's idempotency key for the
     * session. The payload arrives from the switch with QR_PENDING, because the scheme's
     * merchant number, the signature over it and the reference inside it all belong to the
     * switch that issues them.
     */
    fun newSale() {
        qrPayload = ""
        qrBitmap = null
        val reference = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

        val schemeMerchantId = identity.qrMerchantId
        if (schemeMerchantId == null) {
            // Not a failure of this sale — the merchant is not on the scheme at all, and no
            // retry will change that. Saying so is better than a countdown that ends in
            // "Payment not received", which reads as the customer's fault.
            Log.w(TAG, "No qr_merchant_id in managed configuration; this merchant is not on the scheme")
            failureMessage = "This merchant is not set up for QR payments"
            flowState = QrFlowState.TIMEOUT
            return
        }

        val payload = EmvcoQrGenerator.generatePayload(
            qrMerchantId = schemeMerchantId,
            qrOutletNumber = identity.qrOutlet,
            merchantName = merchantName,
            merchantCity = merchantCity,
            merchantCategoryCode = TerminalConfig.DEFAULT_MERCHANT_CATEGORY_CODE,
            currency = amount.currency,
            amount = amount.amount,
            paymentReference = reference,
            billNumber = receiptNumber,
            sealer = QrMacSealers.get(),
        )

        if (payload == null) {
            // The PED could not seal it: no MAC key injected, or the hardware refused. The
            // switch would refuse the code, so it is not put on screen for a customer to scan.
            Log.e(TAG, "Could not seal the QR payload; refusing to present an unsealed code")
            failureMessage = "This terminal cannot secure a QR code — call support"
            flowState = QrFlowState.TIMEOUT
            return
        }

        paymentReference = reference
        qrPayload = payload
        qrBitmap = EmvcoQrGenerator.generateBitmap(payload, size = 512)
        flowState = QrFlowState.DISPLAYING_QR
        Log.d(TAG, "Opening sale: ref=$reference, payload=${payload.length} chars")
    }

    LaunchedEffect(Unit) {
        newSale()
    }

    // Function to start/restart the gRPC stream
    fun startStream() {
        val currentRef = paymentReference
        if (currentRef.isEmpty()) return

        streamJob?.cancel()
        streamJob = coroutineScope.launch {
            try {
                val request = SwitchRequests.qr(
                    identity = identity,
                    paymentReference = currentRef,
                    currency = amount.currency,
                    amountMinor = (amount.amount * 100).toLong(),
                    // What is on the screen, sealed. The switch verifies the tag-80 MAC
                    // against this terminal's key before it opens a hold.
                    qrPayload = qrPayload,
                    billNumber = receiptNumber,
                    latitude = latitude,
                    longitude = longitude,
                )

                Log.d(TAG, "Opening gRPC stream: ref=$currentRef")
                val flow = switchClient.waitForQrPayment(request)

                flow.collect { update ->
                    Log.d(TAG, "QR update: status=${update.status}, ref=$currentRef")

                    when (update.status) {
                        // The hold is open and the code the customer is already looking at
                        // was accepted. The switch sends no payload back — this till minted it.
                        QrPaymentStatus.QR_PENDING -> {
                            flowState = QrFlowState.WAITING_CONFIRMATION
                        }
                        QrPaymentStatus.QR_CLAIMED -> {
                            flowState = QrFlowState.APPROVED
                            delay(1500)
                            onResult(
                                QrPaymentResult.Success(
                                    paymentReference = currentRef,
                                    authorizationCode = update.authorizationCode.ifEmpty { null },
                                    qrCodeData = qrPayload
                                )
                            )
                        }
                        // The switch's own words when it has any: "This merchant is not
                        // enrolled for QR" and "QR is temporarily unavailable" are different
                        // problems with different people to call, and both were being shown as
                        // "Payment not received" — which blames a customer who never saw a code.
                        QrPaymentStatus.QR_DECLINED -> {
                            Log.w(TAG, "Declined: ref=$currentRef, message=${update.message}")
                            failureMessage = update.message.ifBlank { "Payment declined" }
                            flowState = QrFlowState.TIMEOUT
                        }
                        QrPaymentStatus.QR_TIMED_OUT -> {
                            Log.w(TAG, "Timed out: ref=$currentRef, message=${update.message}")
                            failureMessage = update.message.ifBlank { "Payment not received" }
                            flowState = QrFlowState.TIMEOUT
                        }
                        else -> {}
                    }
                }
            } catch (_: CancellationException) {
                Log.d(TAG, "Stream cancelled: ref=$currentRef")
            } catch (e: Exception) {
                // gRPC error — log but do NOT change flowState.
                // The countdown timer will handle the timeout transition: the customer may
                // still be paying, and the stream dropping is not proof that they did not.
                // The reason is kept, though, so that if the countdown does run out the screen
                // can say the bank could not be reached rather than blaming the customer.
                Log.e(TAG, "Stream error (countdown still running): ref=$currentRef", e)
                failureMessage = "Bank unreachable — could not confirm the payment"
            }
        }
    }

    // Start stream once QR is generated
    LaunchedEffect(paymentReference) {
        if (paymentReference.isNotEmpty()) {
            startStream()
        }
    }

    // 30-second countdown timer — this is the ONLY thing that triggers TIMEOUT
    LaunchedEffect(flowState, countdown) {
        if ((flowState == QrFlowState.DISPLAYING_QR || flowState == QrFlowState.WAITING_CONFIRMATION)
            && countdown > 0
        ) {
            delay(1000)
            countdown--
            if (countdown <= 0) {
                // Cancel the gRPC stream — signals switch to mark TIMED_OUT
                streamJob?.cancel()
                flowState = QrFlowState.TIMEOUT
            }
        }
    }

    // Clean up stream on dismiss
    DisposableEffect(Unit) {
        onDispose {
            streamJob?.cancel()
        }
    }

    // Once a QR code is on screen the customer may already have scanned it and
    // the switch may already be holding the payment. A stray back press must not
    // abandon that - only Cancel, which the operator presses deliberately.
    val countingDown = flowState == QrFlowState.AWAITING_QR ||
        flowState == QrFlowState.DISPLAYING_QR ||
        flowState == QrFlowState.WAITING_CONFIRMATION

    Dialog(
        onDismissRequest = {
            if (!countingDown) {
                streamJob?.cancel()
                onResult(QrPaymentResult.Cancelled)
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !countingDown,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // A QR, a caption, a clock and a button, on a terminal screen: it came to
                    // more than the height available and the Cancel button was cut off at the
                    // bottom, which on a payment screen is the one control that must be
                    // reachable. The sizes below fit it; the scroll is there so no future
                    // addition can quietly take it away again.
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Zim QR Payment",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                // Amount
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
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
                    QrFlowState.AWAITING_QR -> {
                        Spacer(modifier = Modifier.height(24.dp))
                        CircularProgressIndicator()
                        Text(
                            "Preparing the code",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedButton(
                            onClick = {
                                streamJob?.cancel()
                                onResult(QrPaymentResult.Cancelled)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel")
                        }
                    }

                    QrFlowState.DISPLAYING_QR, QrFlowState.WAITING_CONFIRMATION -> {
                        qrBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Zim QR Code",
                                modifier = Modifier.size(200.dp)
                            )
                        }

                        Text(
                            "Scan with your banking app",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Smaller than the other waits: the QR itself takes 200dp of this screen.
                        PaymentCountdown(seconds = countdown, diameter = 56.dp, warnAt = 10)

                        OutlinedButton(
                            onClick = {
                                streamJob?.cancel()
                                onResult(QrPaymentResult.Cancelled)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel")
                        }
                    }

                    QrFlowState.APPROVED -> {
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            "APPROVED",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = PaymentColors.success
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                    }

                    QrFlowState.TIMEOUT -> {
                        PaymentErrorMessage(failureMessage)
                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                // A new reference, and the screen back to waiting for the
                                // switch's code. newSale() sets the state itself; the stream
                                // auto-starts via LaunchedEffect(paymentReference).
                                newSale()
                                countdown = PaymentWaits.SWITCH_SECONDS
                                failureMessage = "Payment not received"
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PaymentColors.secondaryAction
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "Retry QR Payment",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                streamJob?.cancel()
                                onResult(QrPaymentResult.SwitchToCash)
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
                            Text(
                                "Pay with Cash",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                streamJob?.cancel()
                                onResult(QrPaymentResult.Cancelled)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}
