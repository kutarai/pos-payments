package com.synergy.payments.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.synergy.payments.model.TenderCurrency
import com.synergy.payments.model.Money

internal sealed class CashResult {
    data class Completed(val tenderedAmount: Money) : CashResult()
    object Cancelled : CashResult()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CashPaymentDialog(
    total: Money,
    currencies: List<TenderCurrency>,
    convertCurrency: suspend (Money, String) -> Money?,
    onResult: (CashResult) -> Unit,
    onDismiss: () -> Unit
) {
    val baseCurrency = total.currency
    var tenderedText by remember { mutableStateOf("") }
    var tenderedCurrency by remember { mutableStateOf(baseCurrency) }
    var changeCurrency by remember { mutableStateOf(baseCurrency) }
    var tenderedExpanded by remember { mutableStateOf(false) }
    var changeExpanded by remember { mutableStateOf(false) }

    val tendered = tenderedText.toDoubleOrNull() ?: 0.0

    var tenderedInBase by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(tendered, tenderedCurrency) {
        tenderedInBase = when {
            tendered <= 0 -> 0.0
            tenderedCurrency == baseCurrency -> tendered
            else -> convertCurrency(Money(tendered, tenderedCurrency), baseCurrency)?.amount ?: 0.0
        }
    }

    val changeInBase = tenderedInBase - total.amount

    var changeDisplay by remember { mutableStateOf<Money?>(null) }
    LaunchedEffect(changeInBase, changeCurrency) {
        changeDisplay = when {
            changeInBase <= 0 -> null
            changeCurrency == baseCurrency -> Money(changeInBase, baseCurrency)
            else -> convertCurrency(Money(changeInBase, baseCurrency), changeCurrency)
        }
    }

    Dialog(
        onDismissRequest = { onResult(CashResult.Cancelled); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Cash Payment", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

                // Amount
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Total Due", fontSize = 16.sp)
                        Text(total.format(), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Amount tendered + currency dropdown
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = tenderedText,
                        onValueChange = { tenderedText = it },
                        label = { Text("Amount Paid", fontSize = 16.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(fontSize = 20.sp)
                    )
                    ExposedDropdownMenuBox(
                        expanded = tenderedExpanded,
                        onExpandedChange = { tenderedExpanded = it },
                        modifier = Modifier.width(110.dp)
                    ) {
                        OutlinedTextField(
                            value = tenderedCurrency,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(tenderedExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable),
                            textStyle = LocalTextStyle.current.copy(fontSize = 18.sp)
                        )
                        ExposedDropdownMenu(expanded = tenderedExpanded, onDismissRequest = { tenderedExpanded = false }) {
                            currencies.forEach { currency ->
                                DropdownMenuItem(
                                    text = { Text("${currency.code} (${currency.symbol})", fontSize = 16.sp) },
                                    onClick = { tenderedCurrency = currency.code; tenderedExpanded = false }
                                )
                            }
                        }
                    }
                }

                // Show equivalent in base currency
                if (tenderedCurrency != baseCurrency && tendered > 0 && tenderedInBase > 0) {
                    Text("= $baseCurrency ${"%.2f".format(tenderedInBase)}", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Change or shortfall
                if (tendered > 0) {
                    if (changeInBase >= 0) {
                        Text(
                            "Change: $baseCurrency ${"%.2f".format(changeInBase)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (changeInBase > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Change in:", fontSize = 16.sp)
                                ExposedDropdownMenuBox(
                                    expanded = changeExpanded,
                                    onExpandedChange = { changeExpanded = it },
                                    modifier = Modifier.width(110.dp)
                                ) {
                                    OutlinedTextField(
                                        value = changeCurrency,
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(changeExpanded) },
                                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable),
                                        textStyle = LocalTextStyle.current.copy(fontSize = 18.sp)
                                    )
                                    ExposedDropdownMenu(expanded = changeExpanded, onDismissRequest = { changeExpanded = false }) {
                                        currencies.forEach { currency ->
                                            DropdownMenuItem(
                                                text = { Text(currency.code, fontSize = 16.sp) },
                                                onClick = { changeCurrency = currency.code; changeExpanded = false }
                                            )
                                        }
                                    }
                                }
                                if (changeCurrency != baseCurrency && changeDisplay != null) {
                                    Text("= ${changeDisplay!!.format()}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
                                }
                            }
                        }
                    } else {
                        Text(
                            "Short: $baseCurrency ${"%.2f".format(-changeInBase)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { onResult(CashResult.Cancelled); onDismiss() },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Cancel", fontSize = 18.sp) }
                    Button(
                        onClick = { onResult(CashResult.Completed(Money(tendered, tenderedCurrency))); onDismiss() },
                        enabled = changeInBase >= 0 && tendered > 0,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Complete", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
