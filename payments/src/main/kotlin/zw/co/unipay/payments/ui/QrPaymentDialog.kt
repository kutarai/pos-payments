package zw.co.unipay.payments.ui

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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import zw.co.unipay.payments.model.Money
import zw.co.unipay.payments.qr.EmvcoQrGenerator
import zw.co.unipay.payments.qr.QrMacSealers
import zw.co.unipay.payments.card.TerminalConfig
import zw.co.unipay.payments.grpc.payment.QrPaymentStatus
import zw.co.unipay.payments.switching.SwitchClient
import zw.co.unipay.payments.switching.SwitchRequests
import zw.co.unipay.payments.terminal.TerminalSnapshot

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
     * The code is built and sealed, the switch has been told about it, and its acknowledgement
     * has not arrived yet.
     *
     * Nothing is on screen for this: a code the switch has not registered is one a customer can
     * scan into a payment with nowhere to land, and the wallet's error then lands on somebody
     * who did exactly what the till asked them to. The QR goes up on QR_PENDING, not before.
     */
    AWAITING_QR,

    /**
     * The switch has the code and the customer can scan it.
     *
     * There is no separate state for "scanned but not yet confirmed": between QR_PENDING and
     * the payment landing the switch tells this till nothing, so it would be a state the till
     * could never be told to enter.
     */
    DISPLAYING_QR,

    /** The switch confirmed the payment. The screen says so, then the sale is handed back. */
    APPROVED,

    /**
     * The wait is over and no payment was taken: declined, never scanned, the bank unreachable,
     * or this merchant not on the scheme at all.
     *
     * Not named after the countdown, because the countdown is only one of five ways in. It was
     * called TIMEOUT while the other four also ended here, and a log line reading "timeout" for
     * a merchant who was never enrolled sends somebody to go and look at the network.
     */
    FAILED
}

private const val TAG = "QrPaymentDialog"

/** How long APPROVED stays up before the sale is handed back — long enough to be read. */
private const val APPROVED_DWELL_MS = 1500L

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
    // What a lapsed wait is called, in the terminal's own words and its own number of seconds.
    // "Payment not received" was true of a timeout and of four other endings, and told an
    // operator nothing about which of them they were looking at: whether to ask the customer to
    // try again, or to ring the bank. Naming the clock is what separates it from the rest.
    val timedOutMessage = "Timed out — no payment after ${PaymentWaits.SWITCH_SECONDS} seconds"

    // Why it ended. A declined payment, a customer who never scanned, and a switch that could
    // not be reached were all announced as "Payment not received" - true of all three and
    // useful for none, and the one an operator most needs to tell apart is the one where the
    // problem is the bank rather than the customer. This is the default because the countdown
    // is the one ending that arrives without having set a reason of its own.
    var failureMessage by remember { mutableStateOf(timedOutMessage) }
    // Whether the countdown is what ended it, as opposed to a decline, a dead bank or an
    // operator who pressed Cancel. The screen shows all of those the same way — a reason and a
    // way out — but the till's record should not call a decline a timeout.
    var timedOut by remember { mutableStateOf(false) }
    // The switch's authorisation code, held for the hand-off below rather than read straight
    // out of the update, because the hand-off no longer happens in the stream's coroutine.
    var authorizationCode by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var streamJob by remember { mutableStateOf<Job?>(null) }

    // The reference is ours and the payload is the switch's. Both are replaced on a retry:
    // a fresh reference opens a new session, and the code that belongs to the old one must not
    // stay on screen where somebody could still scan it.
    var qrPayload by remember { mutableStateOf("") }
    var paymentReference by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    /**
     * Opens a new sale: mints the reference, composes the payload and seals it.
     *
     * It displays nothing. The code reaches the screen only once the switch has acknowledged
     * it — see [QrFlowState.AWAITING_QR] — so everything here happens before the switch is
     * asked, and a payload this terminal cannot build or seal costs no round trip to find out.
     */
    fun newSale() {
        qrPayload = ""
        qrBitmap = null
        timedOut = false
        authorizationCode = null
        // Cleared here, and no longer by the retry button, which set it on the line after this
        // function had already run: a retry that failed again inside newSale — an unenrolled
        // merchant, a PED that would not seal — had its real reason overwritten by the default
        // a moment later, and the operator was told the payment had simply not arrived.
        failureMessage = timedOutMessage
        val reference = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

        val schemeMerchantId = identity.qrMerchantId
        if (schemeMerchantId == null) {
            // Not a failure of this sale — the merchant is not on the scheme at all, and no
            // retry will change that. Saying so is better than a countdown that ends in
            // "Payment not received", which reads as the customer's fault.
            Log.w(TAG, "No qr_merchant_id in managed configuration; this merchant is not on the scheme")
            failureMessage = "This merchant is not set up for QR payments"
            flowState = QrFlowState.FAILED
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
            flowState = QrFlowState.FAILED
            return
        }

        paymentReference = reference
        qrPayload = payload
        qrBitmap = EmvcoQrGenerator.generateBitmap(payload, size = 512)
        flowState = QrFlowState.AWAITING_QR
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
                        // The switch has the code and the hold is open, so it goes on screen
                        // now — and the clock starts again from a full thirty. The seconds
                        // spent reaching the switch were not the customer's to spend: they are
                        // being asked to scan as this arrives, and the wait before it was ours.
                        // Only the first one moves anything. If the switch ever repeats
                        // QR_PENDING — a keepalive, a re-send — a second reset would hand the
                        // customer another thirty seconds each time it arrived, and a wait that
                        // renews itself is one the cashier can never see the end of.
                        QrPaymentStatus.QR_PENDING -> if (flowState == QrFlowState.AWAITING_QR) {
                            flowState = QrFlowState.DISPLAYING_QR
                            countdown = PaymentWaits.SWITCH_SECONDS
                        }
                        // Recorded, not concluded. The hand-off waits a moment so the cashier
                        // can read APPROVED, and a moment spent inside this coroutine is a
                        // moment in which cancelling the stream would swallow the sale — so it
                        // is done below, where nothing cancels it. See the effect that follows.
                        QrPaymentStatus.QR_CLAIMED -> {
                            authorizationCode = update.authorizationCode.ifEmpty { null }
                            flowState = QrFlowState.APPROVED
                        }
                        // The switch's own words when it has any: "This merchant is not
                        // enrolled for QR" and "QR is temporarily unavailable" are different
                        // problems with different people to call, and both were being shown as
                        // "Payment not received" — which blames a customer who never saw a code.
                        QrPaymentStatus.QR_DECLINED -> {
                            Log.w(TAG, "Declined: ref=$currentRef, message=${update.message}")
                            failureMessage = update.message.ifBlank { "Payment declined" }
                            flowState = QrFlowState.FAILED
                        }
                        QrPaymentStatus.QR_TIMED_OUT -> {
                            Log.w(TAG, "Timed out: ref=$currentRef, message=${update.message}")
                            failureMessage = update.message.ifBlank { timedOutMessage }
                            flowState = QrFlowState.FAILED
                        }
                        else -> {}
                    }
                }
            } catch (_: CancellationException) {
                Log.d(TAG, "Stream cancelled: ref=$currentRef")
            } catch (e: Exception) {
                Log.e(TAG, "Stream error: ref=$currentRef", e)

                if (flowState == QrFlowState.AWAITING_QR) {
                    // No code ever reached the screen, so there is no customer part way through
                    // paying and nothing left to wait for. Ending it here hands the operator the
                    // retry twenty-odd seconds before a countdown that can only ever expire.
                    failureMessage = "Bank unreachable — no code was issued"
                    flowState = QrFlowState.FAILED
                } else {
                    // The code is on screen and the customer may be paying against it right now;
                    // the stream dropping is not proof that they did not. The countdown owns
                    // this ending. The reason is kept so that when it does run out the screen
                    // can say the bank could not be reached rather than blame the customer.
                    failureMessage = "Bank unreachable — could not confirm the payment"
                }
            }
        }
    }

    // Start stream once QR is generated
    LaunchedEffect(paymentReference) {
        if (paymentReference.isNotEmpty()) {
            startStream()
        }
    }

    // A payment the switch has confirmed is reported from here, and not from the stream that
    // carried the news. That coroutine is cancelled on timeout and again on dismissal; anything
    // still owed at the moment of a cancel is simply never delivered, and a sale the customer
    // has already paid for would vanish between the switch confirming it and the till hearing.
    // Nothing cancels the composition but the dialog going away.
    LaunchedEffect(flowState) {
        if (flowState == QrFlowState.APPROVED) {
            delay(APPROVED_DWELL_MS)
            onResult(
                QrPaymentResult.Success(
                    paymentReference = paymentReference,
                    authorizationCode = authorizationCode,
                    qrCodeData = qrPayload,
                )
            )
        }
    }

    // Thirty seconds, and it runs over the wait on the switch as well as the wait on the
    // customer: a switch that never acknowledges the code would otherwise leave the till on a
    // spinner with no ending but Cancel. QR_PENDING restarts it, so gating the display on the
    // switch never costs the customer scanning time.
    LaunchedEffect(flowState, countdown) {
        if ((flowState == QrFlowState.AWAITING_QR || flowState == QrFlowState.DISPLAYING_QR)
            && countdown > 0
        ) {
            delay(1000)

            // Read again after the second, not only before it. A switch response arriving
            // during that second ends the wait there and then, but this effect is only torn
            // down at the next recomposition — a frame away, and a frame is long enough for
            // the lines below to cancel the stream out from under an approval and turn a paid
            // sale into "Payment not received".
            if (flowState != QrFlowState.AWAITING_QR && flowState != QrFlowState.DISPLAYING_QR) {
                return@LaunchedEffect
            }

            countdown--
            if (countdown <= 0) {
                timedOut = true
                // Cancelling the stream is how the switch is told this till gave up; it marks
                // the payment TIMED_OUT and stops holding it.
                streamJob?.cancel()
                flowState = QrFlowState.FAILED
            }
        }
    }

    // Clean up stream on dismiss
    DisposableEffect(Unit) {
        onDispose {
            streamJob?.cancel()
        }
    }

    // Only the ending screen can be dismissed. While a code is up the customer may already
    // have scanned it and the switch may already be holding the payment, and a stray back press
    // must not abandon that — only Cancel, which an operator presses deliberately. APPROVED is
    // shut too: the sale is confirmed and part way through being handed back, and a back press
    // during that second would report a paid customer as a cancellation.
    val dismissable = flowState == QrFlowState.FAILED

    Dialog(
        onDismissRequest = {
            if (dismissable) {
                streamJob?.cancel()
                onResult(if (timedOut) QrPaymentResult.Timeout else QrPaymentResult.Cancelled)
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
                        // The ring the customer's own wait uses. A bare spinner said only that
                        // something was happening; this says how much of the wait is left, and
                        // an operator watching it run down is being told to look at the bank
                        // rather than at the terminal.
                        PaymentCountdown(seconds = countdown, warnAt = 10)
                        Text(
                            "Registering with the bank",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        PaymentOutlinedButton(
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

                    QrFlowState.DISPLAYING_QR -> {
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

                        PaymentOutlinedButton(
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

                    QrFlowState.FAILED -> {
                        PaymentErrorMessage(failureMessage)
                        Spacer(modifier = Modifier.height(8.dp))

                        PaymentButton(
                            onClick = {
                                // A new reference, and the screen back to waiting for the
                                // switch's code. newSale() sets the state itself; the stream
                                // auto-starts via LaunchedEffect(paymentReference).
                                newSale()
                                countdown = PaymentWaits.SWITCH_SECONDS
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

                        PaymentButton(
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

                        PaymentOutlinedButton(
                            onClick = {
                                streamJob?.cancel()
                                // Cancel here closes an ending, it does not cause one. Whoever
                                // reconciles this sale wants the reason it did not happen — a
                                // wait that ran out, not the button that acknowledged it. The
                                // two Cancels on the waiting screens do cause it, and say so.
                                onResult(
                                    if (timedOut) QrPaymentResult.Timeout
                                    else QrPaymentResult.Cancelled
                                )
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
