package zw.co.unipay.payments.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import zw.co.unipay.payments.terminal.CustomerDisplay
import zw.co.unipay.payments.terminal.Terminals

private const val TAG = "CustomerFacingQr"

/**
 * Mirrors the QR code on the cashier's screen onto the terminal's customer-facing screen, if it
 * has one. Pass the payload exactly while the cashier's screen shows the code and null otherwise:
 * the customer's screen then shows it for that time and no longer, and goes back to idle when the
 * calling screen goes away however it goes.
 *
 * Nothing here can stop a payment. A terminal with no customer screen, no terminal registered, or
 * a screen that refuses the image all leave the cashier's screen as the one the customer uses.
 */
@Composable
internal fun CustomerFacingQr(payload: String?, caption: List<String>) {
    val context = LocalContext.current.applicationContext
    val mirror = remember { CustomerQrMirror { customerDisplayOf(context) } }
    LaunchedEffect(payload, caption) { mirror.show(payload, caption) }
    DisposableEffect(Unit) {
        // The composition is going, and its own scope with it, so the screen is cleared on a
        // scope that outlives it. Clearing is quick and harmless if nothing is up.
        onDispose { CustomerQrMirror.cleanup.launch { mirror.show(null, emptyList()) } }
    }
}

/**
 * The decisions behind [CustomerFacingQr], kept free of Compose: which display, and whether a
 * clear is owed. Asks for the display on every change, since on some makes it exists only once
 * the hardware has connected.
 */
internal class CustomerQrMirror(private val display: () -> CustomerDisplay?) {

    /** The display a code is up on, so a clear goes there and is not sent when nothing is. */
    @Volatile private var showingOn: CustomerDisplay? = null

    suspend fun show(payload: String?, caption: List<String>) {
        try {
            if (payload != null) {
                val target = display() ?: return
                // Owed a clear from the moment the image is sent, not from when the screen says
                // it took it: a payment screen closed mid-send must still take its code down.
                showingOn = target
                target.showQr(payload, caption)
            } else {
                val target = showingOn ?: return
                showingOn = null
                target.clear()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "The customer screen could not be updated: ${t.message}")
        }
    }

    companion object {
        /** Outlives any one payment screen, for the clear owed when one closes. */
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

private fun customerDisplayOf(context: Context): CustomerDisplay? =
    if (!Terminals.isRegistered()) null
    else runCatching { Terminals.create(context).customerDisplay }.getOrNull()
