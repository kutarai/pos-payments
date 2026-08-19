package com.synergy.payments.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.synergy.payments.model.Money

@Composable
internal fun PaymentMethodDialog(
    paymentAmount: Money,
    onCard: () -> Unit,
    onQr: () -> Unit,
    onMobileMoney: () -> Unit,
    onCash: () -> Unit,
    onDismiss: () -> Unit,
    cardPaymentEnabled: Boolean = true,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Payment",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Total Amount")
                        Text(
                            paymentAmount.format(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "Select Payment Method",
                    style = MaterialTheme.typography.titleMedium
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    FullWidthButton("Cash Payment", onClick = onCash)
                    FullWidthButton("Cancel", onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
internal fun FullWidthButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled
    ) {
        Text(text)
    }
}
