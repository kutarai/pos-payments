package com.synergy.payments.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * This terminal's identity, as its Device Owner set it.
 *
 * Managed configuration is the standard channel for a DPC to configure an app: the app reads it
 * with no permission and no knowledge of the DPC, and the platform guarantees only the Device
 * Owner can write it. Nothing here can be set from inside this application, which is the point —
 * an identity a till could choose for itself is not an identity.
 *
 * The serial is not part of that policy. It is read from the hardware, and passed in rather than
 * fetched: this library must not depend on any particular make of terminal.
 */
class TerminalIdentity(
    context: Context,
    private val serialProvider: () -> String,
) {
    private val appContext = context.applicationContext

    private val restrictionsManager =
        appContext.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager

    private val _current = MutableStateFlow(read())

    /** The identity as it stands, updated when the Device Owner pushes a new policy. */
    val current: StateFlow<TerminalSnapshot> = _current.asStateFlow()

    /** The identity as it stands, for callers outside a composition. */
    val snapshot: TerminalSnapshot get() = _current.value

    private val restrictionsChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val updated = read()
            Log.d(TAG, "Managed configuration changed: provisioned=${updated.isProvisioned}")
            _current.value = updated
        }
    }

    init {
        // A fleet that needs a restart to accept its own configuration is a fleet that needs a
        // site visit. NOT_EXPORTED is required from target 34 and correct regardless: this is a
        // protected system broadcast and nothing else may send it.
        ContextCompat.registerReceiver(
            appContext,
            restrictionsChanged,
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun read(): TerminalSnapshot {
        val restrictions = restrictionsManager.applicationRestrictions
        val values = TerminalSnapshot.KEYS.associateWith { restrictions?.getString(it) }
        return TerminalSnapshot.parse(values, serialProvider())
    }

    companion object {
        private const val TAG = "TerminalIdentity"
    }
}
