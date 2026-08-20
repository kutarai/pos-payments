package com.synergy.payments.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
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
import com.synergy.payments.grpc.payment.QrPaymentStatus
import com.synergy.payments.switching.SwitchClient
import com.synergy.payments.switching.SwitchRequests
import com.synergy.payments.terminal.TerminalSnapshot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Serializable

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
    latitude: Double,
    longitude: Double,
    switchClient: SwitchClient,
    onResult: (QrPaymentResult) -> Unit,
    onDismiss: () -> Unit
) {
    var flowState by remember { mutableStateOf(QrFlowState.DISPLAYING_QR) }
    var countdown by remember { mutableIntStateOf(30) }
    val coroutineScope = rememberCoroutineScope()
    var streamJob by remember { mutableStateOf<Job?>(null) }

    // Mutable QR data — regenerated on each retry so the switch gets a fresh reference
    var qrPayload by remember { mutableStateOf("") }
    var paymentReference by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Generate QR data (called on first composition and on retry)
    fun generateQr() {
        val (payload, reference) = EmvcoQrGenerator.generatePayload(
            merchantId = identity.merchantId.orEmpty(),
            terminalId = identity.terminalId ?: identity.deviceId.orEmpty(),
            merchantName = merchantName,
            currency = amount.currency,
            receiptNumber = receiptNumber,
            amount = amount.amount
        )
        qrPayload = payload
        paymentReference = reference
        qrBitmap = EmvcoQrGenerator.generateBitmap(payload, size = 512)
        Log.d(TAG, "Generated QR: ref=$reference")
    }

    // Generate on first composition
    LaunchedEffect(Unit) {
        generateQr()
    }

    // Function to start/restart the gRPC stream
    fun startStream() {
        val currentRef = paymentReference
        val currentPayload = qrPayload
        if (currentRef.isEmpty()) return

        streamJob?.cancel()
        streamJob = coroutineScope.launch {
            try {
                val request = SwitchRequests.qr(
                    identity = identity,
                    paymentReference = currentRef,
                    currency = amount.currency,
                    amountMinor = (amount.amount * 100).toLong(),
                    qrPayload = currentPayload,
                    latitude = latitude,
                    longitude = longitude,
                )

                Log.d(TAG, "Opening gRPC stream: ref=$currentRef")
                val flow = switchClient.waitForQrPayment(request)

                flow.collect { update ->
                    Log.d(TAG, "QR update: status=${update.status}, ref=$currentRef")

                    when (update.status) {
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
                                    qrCodeData = currentPayload
                                )
                            )
                        }
                        QrPaymentStatus.QR_DECLINED -> {
                            flowState = QrFlowState.TIMEOUT
                        }
                        QrPaymentStatus.QR_TIMED_OUT -> {
                            flowState = QrFlowState.TIMEOUT
                        }
                        else -> {}
                    }
                }
            } catch (_: CancellationException) {
                Log.d(TAG, "Stream cancelled: ref=$currentRef")
            } catch (e: Exception) {
                // gRPC error — log but do NOT change flowState.
                // The countdown timer will handle the timeout transition.
                Log.e(TAG, "Stream error (countdown still running): ref=$currentRef", e)
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

    Dialog(
        onDismissRequest = {
            streamJob?.cancel()
            onResult(QrPaymentResult.Cancelled)
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
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
                    QrFlowState.DISPLAYING_QR, QrFlowState.WAITING_CONFIRMATION -> {
                        qrBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Zim QR Code",
                                modifier = Modifier.size(256.dp)
                            )
                        }

                        Text(
                            "Scan with your banking app",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            "$countdown",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (countdown <= 10) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )

                        if (flowState == QrFlowState.WAITING_CONFIRMATION) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
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
                        Text(
                            "Payment not received",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                // Generate fresh QR with new payment reference
                                generateQr()
                                countdown = 30
                                flowState = QrFlowState.DISPLAYING_QR
                                // Stream auto-starts via LaunchedEffect(paymentReference)
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
