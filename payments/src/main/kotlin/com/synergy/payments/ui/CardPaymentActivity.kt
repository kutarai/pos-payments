package com.synergy.payments.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synergy.payments.R
import com.synergy.payments.switching.SwitchClient
import com.synergy.payments.card.CardFlowUpdate
import com.synergy.payments.card.CardPaymentDriver
import com.synergy.payments.card.CardPaymentDrivers
import com.synergy.payments.switching.SwitchIntegration
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
    onBack: () -> Unit,
    onPaymentComplete: (CardPaymentResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // SynergySwitch gRPC integration.
    // Set to null to use simulated offline approval.
    val switchHost: String? = "switch.unipay.co.zw"
    val switchPort = 3333
    val switchIntegration = remember(switchHost) {
        switchHost?.let { host ->
            SwitchIntegration(SwitchClient(host, switchPort))
        }
    }

    var flowState by remember { mutableStateOf(FlowState.SELECT_NETWORK) }
    var selectedNetwork by remember { mutableStateOf<CardNetwork?>(null) }
    var statusMessage by remember { mutableStateOf("Select Card Type") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableIntStateOf(30) }
    var switchCountdown by remember { mutableIntStateOf(20) }
    var paymentResult by remember { mutableStateOf<CardPaymentResult?>(null) }

    val displayAmount = "$currency ${amount / 100}.${String.format("%02d", amount % 100)}"

    // Countdown timer while waiting for card
    LaunchedEffect(flowState, countdown) {
        if (flowState == FlowState.WAITING_FOR_CARD && countdown > 0) {
            kotlinx.coroutines.delay(1000)
            countdown--
            if (countdown == 0) {
                flowState = FlowState.TIMEOUT
                statusMessage = "No card detected — timed out"
            }
        }
    }

    // Countdown timer while waiting for switch response
    LaunchedEffect(flowState) {
        if (flowState == FlowState.ONLINE_AUTH) {
            switchCountdown = 20
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
            try { CardPaymentDrivers.create(context.applicationContext).cancel() } catch (_: Exception) {}
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
                    FlowState.WAITING_FOR_CARD -> "Insert, Tap, or Swipe Card"
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                countdown = 30
                                statusMessage = "Insert, Tap, or Swipe Card"

                                scope.launch {
                                    val driver: CardPaymentDriver =
                                        CardPaymentDrivers.create(context.applicationContext)
                                    val result = driver.processPayment(
                                        amount, CardNetwork.ZIMSWITCH, currency
                                    ) { update ->
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
                                countdown = 30
                                statusMessage = "Insert, Tap, or Swipe Card"

                                scope.launch {
                                    val driver: CardPaymentDriver =
                                        CardPaymentDrivers.create(context.applicationContext)
                                    val result = driver.processPayment(
                                        amount, CardNetwork.VISA_MASTERCARD, currency
                                    ) { update ->
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
                        Text(
                            "$countdown",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (countdown <= 10) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                        )

                        // Show card images: chip + tap
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.chip_card),
                                    contentDescription = "Tap or insert card",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds
                                )
                                Text(
                                    "TAP or INSERT",
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 16.dp),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.magnetic_stripe_card),
                                    contentDescription = "Swipe card",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds
                                )
                                Text(
                                    "SWIPE",
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 16.dp),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
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
                        Text(
                            "$switchCountdown",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (switchCountdown <= 5) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                        Text(
                            "Contacting bank...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    FlowState.SWITCH_OFFLINE, FlowState.SWITCH_TIMEOUT -> {
                        Text(
                            if (flowState == FlowState.SWITCH_OFFLINE)
                                "Bank offline" else "Transaction timed out",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                flowState = FlowState.SELECT_NETWORK
                                countdown = 30
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
            title = { Text("Payment Error") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    errorMessage = null
                    flowState = FlowState.SELECT_NETWORK
                    countdown = 30
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
