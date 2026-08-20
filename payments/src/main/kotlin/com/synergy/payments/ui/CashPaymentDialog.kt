package com.synergy.payments.ui

import androidx.compose.foundation.clickable
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
                    CurrencyPicker(
                        selected = tenderedCurrency,
                        currencies = currencies,
                        expanded = tenderedExpanded,
                        onExpandedChange = { tenderedExpanded = it },
                        label = { "${it.code} (${it.symbol})" },
                        onSelect = { tenderedCurrency = it },
                    )
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
                                CurrencyPicker(
                                    selected = changeCurrency,
                                    currencies = currencies,
                                    expanded = changeExpanded,
                                    onExpandedChange = { changeExpanded = it },
                                    label = { it.code },
                                    onSelect = { changeCurrency = it },
                                )
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

/**
 * Picks a currency, without ExposedDropdownMenuBox.
 *
 * That component's signature has changed across Material3 releases, and this
 * library is compiled against one version while the applications that use it
 * ship another — which crashed a live terminal mid-payment with
 * NoSuchMethodError. A read-only field and a DropdownMenu do the same job using
 * only API that has been stable, so the host's Material3 version stops mattering.
 */
@Composable
private fun CurrencyPicker(
    selected: String,
    currencies: List<TenderCurrency>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    label: (TenderCurrency) -> String,
    onSelect: (String) -> Unit,
) {
    Box(modifier = Modifier.width(110.dp)) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            textStyle = LocalTextStyle.current.copy(fontSize = 18.sp),
            colors = OutlinedTextFieldDefaults.colors(
                // A disabled field is the only way to make the whole control
                // tappable rather than the caret landing in it; it must not look
                // disabled, so the enabled colours are restated.
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) },
        )

        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            currencies.forEach { currency ->
                DropdownMenuItem(
                    text = { Text(label(currency), fontSize = 16.sp) },
                    onClick = { onSelect(currency.code); onExpandedChange(false) },
                )
            }
        }
    }
}
