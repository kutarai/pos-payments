package zw.co.unipay.payments.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * How a payment button looks, in one place.
 *
 * Taken from the card screen's network buttons — the ones a cashier presses most,
 * and the size the hardware was chosen for. A till is worked at speed, often by
 * someone not looking straight at it, and a control sized for a phone is a mis-tap
 * waiting to happen. The other payment screens were on the framework defaults, so
 * the same action was a different target depending on which method the customer had
 * picked.
 */
internal object PaymentButtonStyle {
    val Height = 56.dp
    val Shape = RoundedCornerShape(12.dp)
    val TextSize = 18.sp
}

/**
 * A payment action, sized like the card screen's.
 *
 * Shaped like Material's own [Button] so the screens that already had buttons could
 * adopt it without being rewritten around it. The text size comes through the
 * ambient style, so a caller that sets its own still wins.
 */
@Composable
internal fun PaymentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // Accepted so the screens that already set their own keep working; they were
    // using the same twelve as the card screen anyway.
    shape: Shape = PaymentButtonStyle.Shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(PaymentButtonStyle.Height),
        enabled = enabled,
        shape = shape,
        colors = colors,
    ) {
        val row = this

        // Through the ambient style rather than on each Text, so a caller that sets
        // its own size still wins.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontSize = PaymentButtonStyle.TextSize),
        ) {
            row.content()
        }
    }
}

/**
 * The quieter one — cancelling, going back, choosing another way to pay. The same
 * size as the rest, because a cashier reaching for Cancel under pressure should not
 * have to aim at a smaller target than the one that takes the money.
 */
@Composable
internal fun PaymentOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = PaymentButtonStyle.Shape,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(PaymentButtonStyle.Height),
        enabled = enabled,
        shape = shape,
    ) {
        val row = this

        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontSize = PaymentButtonStyle.TextSize),
        ) {
            row.content()
        }
    }
}

/** The stacked, full-width form the method and network screens are lists of. */
@Composable
internal fun FullWidthButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    PaymentButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}
