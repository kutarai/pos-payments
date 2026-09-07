package zw.co.unipay.payments.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import zw.co.unipay.payments.model.Money
import zw.co.unipay.payments.qr.EmvcoQrGenerator
import zw.co.unipay.payments.switching.SwitchClient
import zw.co.unipay.payments.switching.SwitchRequests
import zw.co.unipay.payments.terminal.TerminalSnapshot
import zw.co.unipay.payments.grpc.payment.CryptoPaymentStatus
import java.io.Serializable

sealed class CryptoPaymentResult : Serializable {
    data class Success(
        val paymentReference: String,
        val authorizationCode: String?,
        val asset: String,
        val chain: String,
        val assetAmount: String,
    ) : CryptoPaymentResult()

    object Timeout : CryptoPaymentResult()
    object Declined : CryptoPaymentResult()
    object Cancelled : CryptoPaymentResult()
    object SwitchToCash : CryptoPaymentResult()
}

private const val TAG = "CryptoPayment"

/**
 * Taking payment in crypto at the counter.
 *
 * The till has no wallet and cannot read a chain, so it asks the switch for a
 * payment and renders what comes back. What is on screen is a standard payment
 * request rather than a bare address: a customer given only an address has to type
 * the amount, and a mistyped amount is a payment that arrives, buys nothing and has
 * to be sent back.
 *
 * The wait is longer than the other methods because it is a different wait — a
 * person unlocking a phone and approving a transfer, and then a chain burying it —
 * so the screen says what is happening at each stage rather than showing a spinner
 * for a minute and a half.
 */
@Composable
internal fun CryptoPaymentDialog(
    amount: Money,
    identity: TerminalSnapshot,
    receiptNumber: String,
    network: CryptoNetwork,
    switchClient: SwitchClient,
    onResult: (CryptoPaymentResult) -> Unit,
    onDismiss: () -> Unit,
) {
    var countdown by remember { mutableIntStateOf(PaymentWaits.CRYPTO_SECONDS) }
    var paymentUri by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var assetAmount by remember { mutableStateOf("") }
    var asset by remember { mutableStateOf("") }
    var chain by remember { mutableStateOf("") }
    var confirmations by remember { mutableIntStateOf(0) }
    var confirmationsRequired by remember { mutableIntStateOf(0) }
    var seen by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var settled by remember { mutableStateOf(false) }

    // Said on this screen rather than by disappearing. A cashier who presses Tron
    // and is bounced silently back to the network list has learned nothing about
    // why — the card screen names its failures and offers the way on, and this is
    // the same failure from the counter's side.
    var failure by remember { mutableStateOf<String?>(null) }

    val reference = remember { "CRY${System.currentTimeMillis()}" }

    LaunchedEffect(reference, network.id) {
        val request = SwitchRequests.crypto(
            identity = identity,
            paymentReference = reference,
            currency = amount.currency,
            amountMinor = (amount.amount * 100).toLong(),
            billNumber = receiptNumber,
            // Named, never left to the switch to choose. The customer picked the
            // network their wallet is on, and an address issued for another one is
            // money sent where nobody is watching.
            chain = network.id,
        )

        switchClient.initiateCryptoPayment(request)
            .catch { error ->
                Log.w(TAG, "Crypto payment stream failed: ref=$reference", error)

                // Named, and deliberately not as a declined payment: the switch was
                // not reached, so nothing is known about the customer's money. If
                // they had already sent, saying "declined" here would be a lie a
                // cashier would repeat out loud.
                if (!settled) {
                    settled = true
                    failure = "Switch offline — could not start the payment"
                }
            }
            .collect { update ->
                when (update.status) {
                    CryptoPaymentStatus.CRYPTO_PENDING -> {
                        paymentUri = update.paymentUri
                        address = update.address
                        asset = update.asset
                        chain = update.chain
                        assetAmount = update.assetAmount
                        confirmationsRequired = update.confirmationsRequired

                        if (update.paymentUri.isNotEmpty()) {
                            qr = EmvcoQrGenerator.generateBitmap(update.paymentUri, size = 512)
                        }

                        // The clock starts when the customer can actually act. The
                        // seconds spent reaching the switch were not theirs to spend.
                        countdown = PaymentWaits.CRYPTO_SECONDS
                    }

                    CryptoPaymentStatus.CRYPTO_SEEN -> {
                        // The money is on the chain and only the burying is left, so
                        // the countdown stops mattering — cutting a customer off here
                        // would abandon a payment that has already been made.
                        seen = true
                        confirmations = update.confirmations
                        confirmationsRequired = update.confirmationsRequired
                    }

                    CryptoPaymentStatus.CRYPTO_CONFIRMED -> {
                        settled = true
                        onResult(
                            CryptoPaymentResult.Success(
                                paymentReference = reference,
                                authorizationCode = update.authorizationCode.ifEmpty { null },
                                asset = update.asset.ifEmpty { asset },
                                chain = update.chain.ifEmpty { chain },
                                assetAmount = update.assetAmount.ifEmpty { assetAmount },
                            )
                        )
                    }

                    CryptoPaymentStatus.CRYPTO_UNDERPAID -> {
                        settled = true
                        failure = "Less arrived than was asked for"
                    }

                    CryptoPaymentStatus.CRYPTO_DECLINED -> {
                        settled = true
                        failure = update.message.ifBlank { "This payment was refused" }
                    }

                    CryptoPaymentStatus.CRYPTO_TIMED_OUT -> {
                        settled = true
                        failure = "No payment received"
                    }

                    else -> Unit
                }
            }
    }

    LaunchedEffect(seen, settled) {
        while (!seen && !settled && countdown > 0) {
            delay(1_000)
            countdown -= 1
        }

        if (!seen && !settled && countdown <= 0) {
            settled = true
            failure = "No payment received"
        }
    }

    PaymentScreenSurface(
        onDismissRequest = { onResult(CryptoPaymentResult.Cancelled) },
        // Once the money is on the chain there is nothing for back to undo: it has
        // been sent and cannot be unsent.
        dismissOnBackPress = !seen,
    ) {
                Text(
                    "Pay with Crypto",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Total Amount")
                        Text(
                            amount.format(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )

                        if (assetAmount.isNotEmpty()) {
                            Text(
                                "$assetAmount $asset on ${chain.uppercase()}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

        when {
            // Named, with the way on, as the card screen does. Try Again returns to
            // the network question rather than out of the sale: a customer whose
            // Tron payment failed may simply have meant to pay on the other one.
            failure != null -> {
                PaymentErrorMessage(failure!!)

                FullWidthButton(
                    "Try Again",
                    onClick = { onResult(CryptoPaymentResult.Cancelled) },
                )

                PaymentOutlinedButton(
                    onClick = { onResult(CryptoPaymentResult.SwitchToCash) },
                ) {
                    Text("Pay by Cash Instead")
                }
            }

            seen -> {
                Text(
                    "Payment received — confirming on the network",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )

                LinearProgressIndicator(
                    progress = {
                        if (confirmationsRequired <= 0) 0f
                        else (confirmations.toFloat() / confirmationsRequired).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "This takes about half a minute. The money has been sent and cannot be recalled.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            qr != null -> {
                Text(
                    "Ask the customer to scan with their crypto wallet",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Image(
                        bitmap = qr!!.asImageBitmap(),
                        contentDescription = "Crypto payment request",
                        modifier = Modifier.size(240.dp),
                    )
                }

                // Shown so a customer whose wallet will not scan can still pay, and
                // so one who wants to check where it is going can.
                if (address.isNotEmpty()) {
                    Text(
                        address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                    )
                }

                PaymentCountdown(seconds = countdown, warnAt = 15)

                PaymentOutlinedButton(
                    onClick = { onResult(CryptoPaymentResult.SwitchToCash) },
                ) {
                    Text("Pay by Cash Instead")
                }

                PaymentOutlinedButton(
                    onClick = { onResult(CryptoPaymentResult.Cancelled) },
                ) {
                    Text("Cancel")
                }
            }

            // Reaching the switch, which has not answered yet. No buttons at all:
            // there is nothing here to cancel that has begun, and a control offered
            // for the half-second before an address arrives is one a cashier can
            // press by accident and lose the sale to.
            else -> {
                CircularProgressIndicator()

                Text(
                    "Preparing payment…",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
