package zw.co.unipay.payments.terminal

import android.view.KeyCharacterMap
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Barcodes from a scanner that types them, as a keyboard would ("keyboard wedge").
 *
 * Some terminals' imagers (the ZCS family's, for one) do not hand a decoded barcode to the
 * application at all: the scanner types it, character by character, into whatever window has
 * focus. The keystrokes reach an Activity and nothing else, so the application's Activity hands
 * each one to [onKeyEvent] before its own handling (from `dispatchKeyEvent`), and a device
 * library's [BarcodeScanner] reads [scans].
 *
 * Keystrokes are taken only while somebody is listening to [scans], and only from a hardware
 * device (never the on-screen keyboard, whose text does not arrive as key events anyway). The
 * rest of the time they pass through untouched, so a focused search field still receives a scan
 * typed into it.
 */
object KeyboardWedge {

    /**
     * A barcode is complete at Enter or Tab, or when the typing pauses this long. A scanner types
     * a whole code in a few milliseconds; a pause this long is the end of it.
     */
    internal const val PAUSE_ENDS_SCAN_MS = 120L

    private val decoder = WedgeDecoder()
    private val _scans = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pauseTimer: Job? = null

    internal fun publish(barcode: String) {
        _scans.tryEmit(barcode)
    }

    /** Each barcode the scanner types, once, as it is completed. */
    val scans: SharedFlow<String> = _scans.asSharedFlow()

    /** Whether keystrokes are being taken: someone is collecting [scans]. */
    val isListening: Boolean get() = _scans.subscriptionCount.value > 0

    /**
     * Hands a key event over. Returns true if it was part of a scan and has been taken, in which
     * case the caller must not deliver it any further (both the down and the up are taken).
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isListening) return false
        if (event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return false
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> WedgeKey.End
            else -> event.unicodeChar.takeIf { it > ' '.code }?.let { WedgeKey.Char(it.toChar()) }
                ?: return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) synchronized(this) {
            decoder.key(key)?.let { emit(it) }
            pauseTimer?.cancel()
            pauseTimer = if (decoder.hasPartial()) scope.launch {
                delay(PAUSE_ENDS_SCAN_MS)
                synchronized(this@KeyboardWedge) { decoder.flush() }?.let { emit(it) }
            } else null
        }
        return true
    }
}

private fun emit(barcode: String) = KeyboardWedge.publish(barcode)

internal sealed class WedgeKey {
    data class Char(val c: kotlin.Char) : WedgeKey()
    object End : WedgeKey()
}

/** Characters into barcodes. Free of Android, so it is tested on the JVM. */
internal class WedgeDecoder {
    private val buffer = StringBuilder()

    /** Feeds one key; returns the barcode it completed, if any. */
    fun key(key: WedgeKey): String? = when (key) {
        is WedgeKey.Char -> {
            buffer.append(key.c)
            null
        }
        WedgeKey.End -> flush()
    }

    fun hasPartial(): Boolean = buffer.isNotEmpty()

    /** Whatever has been typed so far, as a barcode, or null if nothing has. */
    fun flush(): String? {
        val text = buffer.toString().trim()
        buffer.clear()
        return text.ifEmpty { null }
    }
}
