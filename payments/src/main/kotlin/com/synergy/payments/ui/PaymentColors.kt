package com.synergy.payments.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The few colours a payment screen needs that Material's scheme does not name.
 *
 * "Approved" has to be green and "declined" has to be red wherever this library is used, because
 * a cashier reads the colour before the words. Everything else defers to the host application's
 * theme, so payment screens look like the application they are embedded in rather than announcing
 * that they came from a library.
 */
object PaymentColors {

    /** An approval, a successful settlement, a payment received. */
    val success: Color
        @Composable @ReadOnlyComposable
        get() = if (isDark()) Color(0xFF6BD68A) else Color(0xFF1B7F3B)

    /** A decline or a failure. Distinct from Material's error, which is also used for validation. */
    val declined: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.error

    /** A second choice on a payment screen — "pay by cash instead", "cancel and go back". */
    val secondaryAction: Color
        @Composable @ReadOnlyComposable
        get() = if (isDark()) Color(0xFF9FB4D0) else Color(0xFF37536E)

    /** Waiting on the customer or on the switch. */
    val pending: Color
        @Composable @ReadOnlyComposable
        get() = if (isDark()) Color(0xFFE3C05C) else Color(0xFF8A6100)

    /** The domestic scheme's button. */
    val zimswitch: Color
        @Composable @ReadOnlyComposable
        get() = if (isDark()) Color(0xFF2E7D32) else Color(0xFF1B5E20)

    /** The international schemes' button. */
    val visaMastercard: Color
        @Composable @ReadOnlyComposable
        get() = if (isDark()) Color(0xFF1565C0) else Color(0xFF0D47A1)

    @Composable
    @ReadOnlyComposable
    private fun isDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

    private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
}
