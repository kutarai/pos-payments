package com.synergy.payments.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The wait, wherever a payment has one: a turning ring with the seconds left inside it.
 *
 * Every method in this library ends up waiting on somebody — the customer to present a card,
 * the customer to confirm on their phone, the bank to answer — and each screen had grown its
 * own arrangement of a spinner and a number. Mobile money put the number under the ring, the
 * card screen put it over the ring while waiting for the bank, and while waiting for a card it
 * showed a number with no ring at all. A cashier moving between them was reading three
 * different things.
 *
 * One thing now, and the number sits where a cashier looks for it — in the middle of what is
 * turning.
 */
@Composable
internal fun PaymentCountdown(
    seconds: Int,
    modifier: Modifier = Modifier,
    diameter: Dp = 88.dp,
    /** Below this many seconds the whole thing turns red — ring and number together. */
    warnAt: Int = 5,
) {
    val tint =
        if (seconds <= warnAt) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.fillMaxSize(),
            strokeWidth = diameter / 20,
            color = tint,
        )
        Text(
            "$seconds",
            // Proportional to the ring so the digits stay clear of it at any size.
            fontSize = (diameter.value * 0.36f).sp,
            fontWeight = FontWeight.Bold,
            color = tint,
        )
    }
}
