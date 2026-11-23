package zw.co.unipay.payments.ui

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
import zw.co.unipay.payments.model.TenderCurrency
import zw.co.unipay.payments.model.Money

internal sealed class CashResult {
    data class Completed(val change: CashChange) : CashResult()
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

    // Everything on screen comes from CashChange.compute, the same code that
    // produces the recorded outcome. Null while the tender is empty, short, or
    // cannot be valued.
    var cash by remember { mutableStateOf<CashChange?>(null) }
    // The tender in the sale's currency, for the shortfall line when cash is null.
    var tenderedInBase by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(tendered, tenderedCurrency, changeCurrency) {
        if (tendered <= 0) {
            cash = null
            tenderedInBase = null
            return@LaunchedEffect
        }
        val money = Money(tendered, tenderedCurrency)
        cash = CashChange.compute(total, money, changeCurrency, convertCurrency)
        tenderedInBase = cash?.tenderedInSale?.amount
            ?: if (tenderedCurrency == baseCurrency) tendered
            else convertCurrency(money, baseCurrency)?.amount
    }

    PaymentScreenSurface(
        onDismissRequest = { onResult(CashResult.Cancelled); onDismiss() },
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
                val converted = tenderedInBase
                if (tenderedCurrency != baseCurrency && tendered > 0) {
                    if (converted != null) {
                        Text("= $baseCurrency ${"%.2f".format(converted)}", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("No rate for $tenderedCurrency", fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
                    }
                }

                // Change or shortfall
                val current = cash
                if (tendered > 0) {
                    if (current != null) {
                        Text(
                            "Change: ${current.changeGiven.format()}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (current.changeCurrencyFellBack) {
                            Text(
                                "No rate for $changeCurrency: change in $baseCurrency",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (current.changeInSale.amount > 0) {
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
                            }
                        }
                    } else if (converted != null) {
                        Text(
                            "Short: $baseCurrency ${"%.2f".format((total.amount - converted).coerceAtLeast(0.0))}",
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
                        onClick = { cash?.let { onResult(CashResult.Completed(it)); onDismiss() } },
                        enabled = cash != null,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Complete", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
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
