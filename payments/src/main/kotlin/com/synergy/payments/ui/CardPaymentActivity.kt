package com.synergy.payments.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.synergy.payments.R
import com.synergy.payments.card.CardFlowUpdate
import com.synergy.payments.card.CardPaymentDriver
import com.synergy.payments.card.CardPaymentDrivers
import kotlinx.coroutines.launch
import java.io.Serializable

/** Card network types for different processing flows. */
enum class CardNetwork(val displayName: String) {
    ZIMSWITCH("Zimswitch"),
    VISA_MASTERCARD("Visa / Mastercard")
}

class CardPaymentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val amount = intent.getLongExtra("amount", 0L)
        val currency = intent.getStringExtra("currency") ?: "ZWG"

        setContent {
            MaterialTheme {
                CardPaymentScreen(
                    amount = amount,
                    currency = currency,
                    onBack = { finish() },
                    onPaymentComplete = { result ->
                        val resultIntent = Intent().apply {
                            putExtra("payment_result", result)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    }
                )
            }
        }
    }
}

/**
 * The states that mean the payment did not happen — every one of them drawn in the theme's
 * error colour, wherever the screen says something about them.
 */
private val FAILED_STATES = setOf(
    FlowState.DECLINED,
    FlowState.ERROR,
    FlowState.TIMEOUT,
    FlowState.SWITCH_TIMEOUT,
    FlowState.SWITCH_OFFLINE,
)

/** Payment flow states driven by hardware events. */
private enum class FlowState {
    SELECT_NETWORK,
    WAITING_FOR_CARD,
    READING_CARD,
    ENTER_PIN_ON_KEYPAD,
    PROCESSING,
    ONLINE_AUTH,
    APPROVED,
    DECLINED,
    ERROR,
    TIMEOUT,
    /** Connected to the switch but no response in time. */
    SWITCH_TIMEOUT,
    /** Could not connect to the switch at all (offline / DNS / refused). */
    SWITCH_OFFLINE
}

@Composable
fun CardPaymentScreen(
    amount: Long,
    currency: String = "ZWG",
    driver: CardPaymentDriver? = null,
    onBack: () -> Unit,
    onPaymentComplete: (CardPaymentResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Where this screen gets the terminal from. A caller that already holds a driver hands it
    // over; that is the payment flow, which builds one per payment out of the merchant and
    // serial the sale is being taken under. An Activity started by an Intent holds nothing and
    // can be handed nothing, so it falls back to whatever the application registered at
    // start-up. Resolved at the moment a card is asked for, not when the screen opens, so an
    // application with neither fails at the button rather than on arrival.
    val resolveDriver = { driver ?: CardPaymentDrivers.create(context.applicationContext) }

    var flowState by remember { mutableStateOf(FlowState.SELECT_NETWORK) }
    var selectedNetwork by remember { mutableStateOf<CardNetwork?>(null) }
    var statusMessage by remember { mutableStateOf("Select Card Type") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Longer than the driver's own card-detect window, deliberately.
    //
    // At thirty it was exactly equal to the CS20's, so the two expired together and the screen
    // always won: the operator got this screen's generic "no card presented" while the driver's
    // own verdict - a reader error, a code, a reason - was thrown away unread. This is meant to
    // be the backstop for a driver that says nothing at all, not the thing that normally fires.
    var countdown by remember { mutableIntStateOf(45) }
    var switchCountdown by remember { mutableIntStateOf(PaymentWaits.SWITCH_SECONDS) }
    var paymentResult by remember { mutableStateOf<CardPaymentResult?>(null) }

    // Which run of the card flow the screen is showing. Cancelling a driver does not make its
    // processPayment call disappear - it returns, late, with Cancelled, and without this that
    // stale answer would paint "Cancelled" over the timeout screen the cashier is reading. Each
    // press of a card-type button owns a number; results arriving under an older one are dropped.
    var attempt by remember { mutableIntStateOf(0) }

    val displayAmount = "$currency ${amount / 100}.${String.format("%02d", amount % 100)}"

    // Countdown timer while waiting for card
    LaunchedEffect(flowState, countdown) {
        if (flowState == FlowState.WAITING_FOR_CARD && countdown > 0) {
            kotlinx.coroutines.delay(1000)
            countdown--
            if (countdown == 0) {
                flowState = FlowState.TIMEOUT
                statusMessage = "No card detected — timed out"
                attempt++
                // The reader is still hunting for a card, and the screen has stopped waiting
                // for one. Left running, a card presented late would authorise against a
                // payment the cashier had already moved on from, and Retry would start a
                // second flow alongside the first.
                try { resolveDriver().cancel() } catch (_: Exception) {}
            }
        }
    }

    // Countdown timer while waiting for switch response
    LaunchedEffect(flowState) {
        if (flowState == FlowState.ONLINE_AUTH) {
            switchCountdown = PaymentWaits.SWITCH_SECONDS
            while (switchCountdown > 0 && flowState == FlowState.ONLINE_AUTH) {
                kotlinx.coroutines.delay(1000)
                switchCountdown--
            }
        }
    }

    // Cancel hardware helper
    fun cancelHardwareAndClose() {
        scope.launch {
            // Whatever the terminal needs doing to stop looking for a card, it knows; this
            // screen only knows that the customer has gone.
            try { resolveDriver().cancel() } catch (_: Exception) {}
            kotlinx.coroutines.delay(300)
            onPaymentComplete(CardPaymentResult.Cancelled)
            onBack()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title
            Text(
                "Card Payment",
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
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Amount", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        displayAmount,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Status message
            Text(
                text = when (flowState) {
                    FlowState.SELECT_NETWORK -> "Select Card Type"
                    FlowState.WAITING_FOR_CARD -> "Tap or Insert Card"
                    FlowState.READING_CARD -> "Reading Card..."
                    FlowState.ENTER_PIN_ON_KEYPAD -> "Enter PIN on Keypad"
                    FlowState.PROCESSING -> "Processing Payment..."
                    FlowState.ONLINE_AUTH -> "Waiting for bank response..."
                    FlowState.APPROVED -> "Approved"
                    FlowState.DECLINED -> "Declined"
                    FlowState.ERROR -> "Error"
                    FlowState.TIMEOUT -> "Timed Out"
                    FlowState.SWITCH_TIMEOUT -> "Bank did not respond in time"
                    FlowState.SWITCH_OFFLINE -> "Bank offline — could not connect"
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                // Twice the size while waiting for a card. That line is the instruction the
                // customer is meant to act on, read at arm's length across a counter, and it
                // is now the only text on the screen besides the amount.
                fontSize = if (flowState == FlowState.WAITING_FOR_CARD) 32.sp
                           else TextUnit.Unspecified,
                fontWeight = if (flowState == FlowState.WAITING_FOR_CARD) FontWeight.Bold
                             else FontWeight.Normal,
                color = when {
                    // A failure is said in the colour that means failure, here as well as in
                    // the headline below - otherwise the line a cashier reads first is the one
                    // still in the same black it used while the payment was going fine.
                    flowState in FAILED_STATES -> MaterialTheme.colorScheme.error
                    flowState == FlowState.WAITING_FOR_CARD -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            // Main content area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (flowState) {
                    FlowState.SELECT_NETWORK -> {
                        Spacer(modifier = Modifier.height(16.dp))

                        // Zimswitch button
                        Button(
                            onClick = {
                                selectedNetwork = CardNetwork.ZIMSWITCH
                                flowState = FlowState.WAITING_FOR_CARD
                                countdown = 45
                                statusMessage = "Tap or Insert Card"

                                scope.launch {
                                    val thisAttempt = attempt
                                    val terminal: CardPaymentDriver = resolveDriver()
                                    val result = terminal.processPayment(
                                        amount, CardNetwork.ZIMSWITCH, currency
                                    ) { update ->
                                        if (attempt != thisAttempt) return@processPayment
                                        when (update) {
                                            CardFlowUpdate.CARD_DETECTED ->
                                                flowState = FlowState.READING_CARD
                                            CardFlowUpdate.ENTER_PIN ->
                                                flowState = FlowState.ENTER_PIN_ON_KEYPAD
                                            CardFlowUpdate.PROCESSING ->
                                                flowState = FlowState.PROCESSING
                                            CardFlowUpdate.ONLINE_AUTH ->
                                                flowState = FlowState.ONLINE_AUTH
                                        }
                                    }
                                    if (attempt != thisAttempt) return@launch
                                    handlePaymentResult(
                                        result,
                                        onFlowState = { flowState = it },
                                        onStatusMessage = { statusMessage = it },
                                        onErrorMessage = { errorMessage = it },
                                        onPaymentResult = { paymentResult = it },
                                        onPaymentComplete = onPaymentComplete
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PaymentColors.zimswitch
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "Zimswitch",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Visa / Mastercard button
                        Button(
                            onClick = {
                                selectedNetwork = CardNetwork.VISA_MASTERCARD
                                flowState = FlowState.WAITING_FOR_CARD
                                countdown = 45
                                statusMessage = "Tap or Insert Card"

                                scope.launch {
                                    val thisAttempt = attempt
                                    val terminal: CardPaymentDriver = resolveDriver()
                                    val result = terminal.processPayment(
                                        amount, CardNetwork.VISA_MASTERCARD, currency
                                    ) { update ->
                                        if (attempt != thisAttempt) return@processPayment
                                        when (update) {
                                            CardFlowUpdate.CARD_DETECTED ->
                                                flowState = FlowState.READING_CARD
                                            CardFlowUpdate.ENTER_PIN ->
                                                flowState = FlowState.ENTER_PIN_ON_KEYPAD
                                            CardFlowUpdate.PROCESSING ->
                                                flowState = FlowState.PROCESSING
                                            CardFlowUpdate.ONLINE_AUTH ->
                                                flowState = FlowState.ONLINE_AUTH
                                        }
                                    }
                                    if (attempt != thisAttempt) return@launch
                                    handlePaymentResult(
                                        result,
                                        onFlowState = { flowState = it },
                                        onStatusMessage = { statusMessage = it },
                                        onErrorMessage = { errorMessage = it },
                                        onPaymentResult = { paymentResult = it },
                                        onPaymentComplete = onPaymentComplete
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PaymentColors.visaMastercard
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "Visa / Mastercard",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    FlowState.WAITING_FOR_CARD -> {
                        // Nothing else on this screen while it waits. The chip and magstripe
                        // illustrations that used to sit here took two thirds of the height to
                        // say what the line above already says, and pushed the countdown - the
                        // one thing that is changing - down into a corner.
                        PaymentCountdown(seconds = countdown, diameter = 220.dp, warnAt = 10)
                    }

                    FlowState.ENTER_PIN_ON_KEYPAD -> {
                        Spacer(modifier = Modifier.height(32.dp))
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Please enter your PIN\non the device keypad",
                            textAlign = TextAlign.Center,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    FlowState.APPROVED -> {
                        Spacer(modifier = Modifier.height(48.dp))
                        Text(
                            "APPROVED",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = PaymentColors.success
                        )
                    }

                    FlowState.ONLINE_AUTH -> {
                        Spacer(modifier = Modifier.height(16.dp))
                        PaymentCountdown(seconds = switchCountdown, warnAt = 5)
                        Text(
                            "Contacting bank...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Three ways a card payment can stall, one thing for the cashier to do
                    // about any of them: go round again, or take the money in cash. TIMEOUT -
                    // nobody presented a card - used to fall through to the empty branch below,
                    // leaving the word "Timed Out" and a Cancel button, so the only way on from
                    // a customer who was slow finding their card was to abandon the sale and
                    // start it again.
                    FlowState.SWITCH_OFFLINE, FlowState.SWITCH_TIMEOUT,
                    FlowState.TIMEOUT, FlowState.DECLINED -> {
                        PaymentErrorMessage(
                            when (flowState) {
                                FlowState.SWITCH_OFFLINE -> "Bank offline — could not connect"
                                FlowState.TIMEOUT -> "No card presented"
                                FlowState.DECLINED -> "Payment cancelled"
                                else -> "Bank did not respond in time"
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                // Back to the card type, not straight to waiting: after a
                                // timeout the cashier may want the other network, and the
                                // driver call that was in flight has been cancelled.
                                flowState = FlowState.SELECT_NETWORK
                                countdown = 45
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
                                "Retry Card Payment",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = {
                                onPaymentComplete(CardPaymentResult.SwitchToCash)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PaymentColors.success
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "Pay with Cash",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    else -> {
                        if (flowState == FlowState.READING_CARD || flowState == FlowState.PROCESSING) {
                            Spacer(modifier = Modifier.height(32.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 3.dp
                            )
                        }
                    }
                }
            }

            // Cancel button
            OutlinedButton(
                onClick = { cancelHardwareAndClose() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancel", fontSize = 18.sp)
            }
        }
    }

    // Error dialog
    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = {
                Text("Payment Error", color = MaterialTheme.colorScheme.error)
            },
            // The reason the terminal gave — a declined code, a wrong PIN, whatever the kernel
            // said. Red, because it is the only place the operator learns why.
            text = { Text(message, color = MaterialTheme.colorScheme.error) },
            confirmButton = {
                TextButton(onClick = {
                    errorMessage = null
                    flowState = FlowState.SELECT_NETWORK
                    countdown = 45
                }) {
                    Text("Try Again")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    errorMessage = null
                    onPaymentComplete(CardPaymentResult.Cancelled)
                    onBack()
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private suspend fun handlePaymentResult(
    result: CardPaymentResult,
    onFlowState: (FlowState) -> Unit,
    onStatusMessage: (String) -> Unit,
    onErrorMessage: (String?) -> Unit,
    onPaymentResult: (CardPaymentResult?) -> Unit,
    onPaymentComplete: (CardPaymentResult) -> Unit
) {
    when (result) {
        is CardPaymentResult.Success -> {
            onFlowState(FlowState.APPROVED)
            onStatusMessage("Approved")
            onPaymentResult(result)
            kotlinx.coroutines.delay(2000)
            onPaymentComplete(result)
        }
        is CardPaymentResult.Error -> {
            val msg = result.errorMessage
            when {
                // Could not reach the switch at all (connection refused / DNS / offline)
                msg.contains("Bank offline", ignoreCase = true) ||
                    msg.contains("UNAVAILABLE", ignoreCase = true) -> {
                    onFlowState(FlowState.SWITCH_OFFLINE)
                    onStatusMessage("Bank offline — could not connect")
                }
                // Connected to switch but it did not respond in time
                msg.contains("did not respond", ignoreCase = true) ||
                    msg.contains("DEADLINE_EXCEEDED", ignoreCase = true) ||
                    msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("Unable to go online", ignoreCase = true) -> {
                    onFlowState(FlowState.SWITCH_TIMEOUT)
                    onStatusMessage("Bank did not respond in time")
                }
                msg.contains("No card detected", ignoreCase = true) -> {
                    onFlowState(FlowState.TIMEOUT)
                    onStatusMessage("No card detected — timed out")
                }
                else -> {
                    onFlowState(FlowState.ERROR)
                    onStatusMessage("Declined")
                    onErrorMessage(msg)
                }
            }
        }
        is CardPaymentResult.Cancelled -> {
            onFlowState(FlowState.DECLINED)
            onStatusMessage("Cancelled")
        }
        is CardPaymentResult.SwitchToCash -> {
            onPaymentComplete(result)
        }
    }
}

sealed class CardPaymentResult : Serializable {
    data class Success(
        val authorizationCode: String,
        val cardLastFour: String,
        val cardType: String,
        val emvData: Map<String, String> = emptyMap(),
        // ── Fields for switch integration ──
        val pan: String? = null,                    // Full PAN from card (tag 5A)
        val track2EquivalentData: String? = null,   // Track 2 data (tag 57)
        val encryptedPinBlock: String? = null,       // Encrypted PIN block (hex)
        val panSequenceNumber: String? = null,       // PAN Sequence Number (tag 5F34)
        val cardEntryMode: String? = null            // "ICC", "NFC", or "MCR"
    ) : CardPaymentResult()

    data class Error(val errorMessage: String) : CardPaymentResult()
    object Cancelled : CardPaymentResult()
    object SwitchToCash : CardPaymentResult()
}

enum class PaymentStatus {
    SUCCESS, ERROR, CANCELLED, PROCESSING
}
