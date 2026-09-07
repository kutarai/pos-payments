package zw.co.unipay.payments.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * What a payment screen looks like, in one place.
 *
 * Every step of a payment takes the whole screen. A cashier working a counter is
 * not choosing between the payment and whatever is behind it, and a card that
 * floats over a shrunken till screen invites a mis-tap onto the sale underneath at
 * exactly the moment that costs the most.
 *
 * It was six separate answers before this — some full width, some not, none full
 * height — so a customer watching over the counter saw the screen change shape as
 * the payment moved between steps. The same reasoning as [PaymentWaits]: a thing
 * that is the same from the counter's side should not be three different things in
 * three files.
 *
 * @param onDismissRequest what a back press means here. Each step decides for
 *        itself, because backing out of choosing a method ends the sale while
 *        backing out of a QR does not.
 * @param dismissOnBackPress false while a payment is in flight and there is nothing
 *        useful for back to do.
 */
@Composable
internal fun PaymentScreenSurface(
    onDismissRequest: () -> Unit,
    dismissOnBackPress: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissOnBackPress,
            // Never on an outside tap: on a full screen there is no outside, and a
            // stray touch must not end a payment a customer is part way through.
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Scrollable because a till screen is short and some of these
                    // steps are tall. Content that cannot be reached is a button a
                    // cashier cannot press.
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}
