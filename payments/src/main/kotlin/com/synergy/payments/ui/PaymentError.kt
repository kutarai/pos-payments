package com.synergy.payments.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Something went wrong with a payment, said in the one colour that means it.
 *
 * A bank that cannot be reached, a PIN that was wrong, a customer who never confirmed — every
 * method has its own way of failing, and each screen had been announcing them in the same
 * black as "Insert your card". A cashier glancing at a terminal between customers has to be
 * able to tell a payment that failed from a payment that is still going, and colour is what
 * does that a good deal faster than reading does.
 *
 * The error colour comes from the theme rather than a literal red, so it stays legible against
 * whatever ground the screen is drawn on.
 */
@Composable
internal fun PaymentErrorMessage(
    message: String,
    modifier: Modifier = Modifier,
) {
    Text(
        message,
        modifier = modifier,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.error,
    )
}
