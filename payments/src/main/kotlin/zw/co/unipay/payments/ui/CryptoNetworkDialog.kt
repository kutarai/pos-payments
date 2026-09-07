package zw.co.unipay.payments.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import zw.co.unipay.payments.model.Money

/**
 * A network the customer can pay on, as it is offered at the counter.
 *
 * [id] is what the switch knows it as; [label] is what a cashier and a customer
 * call it. They differ on purpose — nobody at a till says "BSC".
 */
internal data class CryptoNetwork(val id: String, val label: String)

internal val cryptoNetworks = listOf(
    CryptoNetwork("bsc", "Binance"),
    CryptoNetwork("tron", "Tron"),
)

/**
 * Which network the customer is paying on.
 *
 * Asked before anything is sent to the switch, because the answer decides which
 * address the customer is given. USDT on Binance and USDT on Tron are the same
 * token and not the same money: an address for one is meaningless on the other, and
 * a customer who sends to the wrong one has put their money somewhere nobody is
 * watching and nobody can reach.
 *
 * So it is the customer's wallet that decides this, not the cashier's preference —
 * ask which app they are paying from.
 */
@Composable
internal fun CryptoNetworkDialog(
    paymentAmount: Money,
    onNetwork: (CryptoNetwork) -> Unit,
    onBack: () -> Unit,
) {
    PaymentScreenSurface(onDismissRequest = onBack) {
                Text(
                    text = "Pay with Crypto",
                    style = MaterialTheme.typography.headlineMedium,
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
                            paymentAmount.format(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Text(
                    text = "Select Network",
                    style = MaterialTheme.typography.titleMedium,
                )

                Text(
                    text = "Ask the customer which network their wallet uses.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start,
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cryptoNetworks.forEach { network ->
                        FullWidthButton(network.label, onClick = { onNetwork(network) })
                    }

            FullWidthButton("Back", onClick = onBack)
        }
    }
}
