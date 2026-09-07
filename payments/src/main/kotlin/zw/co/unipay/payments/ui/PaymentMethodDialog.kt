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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import zw.co.unipay.payments.model.Money

@Composable
internal fun PaymentMethodDialog(
    paymentAmount: Money,
    onCard: () -> Unit,
    onQr: () -> Unit,
    onMobileMoney: () -> Unit,
    onCrypto: () -> Unit,
    onCash: () -> Unit,
    onDismiss: () -> Unit,
    cardPaymentEnabled: Boolean = true,
    electronicPaymentsEnabled: Boolean = true,
) {
    PaymentScreenSurface(onDismissRequest = onDismiss) {
        Text(
            text = "Payment",
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
            text = "Select Payment Method",
            style = MaterialTheme.typography.titleMedium,
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (electronicPaymentsEnabled) {
                FullWidthButton("Card Payment", onClick = onCard, enabled = cardPaymentEnabled)

                if (!cardPaymentEnabled) {
                    Text(
                        text = "Card reader not connected — use another method",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                FullWidthButton("Zim QR Payment", onClick = onQr)
                FullWidthButton("Mobile Money", onClick = onMobileMoney)
                FullWidthButton("Crypto", onClick = onCrypto)
            }

            FullWidthButton("Cash Payment", onClick = onCash)
            FullWidthButton("Cancel", onClick = onDismiss)
        }
    }
}
