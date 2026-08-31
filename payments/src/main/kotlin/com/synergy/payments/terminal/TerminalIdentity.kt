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

    /**
     * The identity as it stands, updated when the Device Owner pushes a new policy.
     *
     * The serial in this StateFlow is only as fresh as the last policy push or the vendor SDK
     * binding — it is not re-read per collection. A composable re-reading the hardware on every
     * recomposition would be worse than the staleness this trades for; callers who need the
     * freshly-read serial should use [snapshot] instead.
     */
    val current: StateFlow<TerminalSnapshot> = _current.asStateFlow()

    /**
     * The identity as it stands, for callers outside a composition.
     *
     * The serial is re-read here rather than trusted from the last policy push: the hardware
     * manager deliberately retries rather than caching a failed read, so a snapshot taken before
     * the vendor SDK had bound would otherwise pin "UNKNOWN_SN" for the life of the process — and
     * the serial's whole purpose is to be cross-checked against the one the device enrolled with.
     */
    val snapshot: TerminalSnapshot
        get() = _current.value.let { current ->
            val serial = serialProvider()
            if (serial == current.serialNumber) current else current.copy(serialNumber = serial)
        }

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
        // Read whatever type the DPC set, then render it as text for parse().
        //
        // getString() was used for every key, and a Bundle returns null — with a warning nobody
        // reads — when the value is not the type asked for. qr_outlet_number arrives from the
        // MDM as an Integer, so the number the policy actually set was discarded and every till
        // fell back to outlet 1 while reporting the key as absent. Which outlet a payment came
        // from is not a detail worth losing to a getter.
        @Suppress("DEPRECATION")
        val values = TerminalSnapshot.KEYS.associateWith { key ->
            restrictions?.get(key)?.toString()?.takeIf { it.isNotBlank() }
        }
        val snapshot = TerminalSnapshot.parse(values, serialProvider())

        // Say out loud what the policy contained.
        //
        // An unprovisioned terminal offers cash and nothing else, which on the counter looks
        // exactly like a payment library that has lost its card support - and the policy is the
        // one input nobody can inspect from outside the app: adb cannot read another package's
        // restrictions without root, and a DPC that is simply not pushing to this package
        // writes nothing anywhere. Without this line the difference between "no policy" and
        // "policy with a typo in one key" costs a site visit to tell apart.
        //
        // None of it is a secret. It is a merchant number, a hostname and a tax number - the
        // same things that are printed on the receipt handed to the customer.
        Log.i(
            TAG,
            "Managed configuration read: " +
                TerminalSnapshot.KEYS.joinToString(", ") { key ->
                    "$key=" + (values[key]?.takeIf { it.isNotBlank() } ?: "<absent>")
                } +
                ", provisioned=${snapshot.isProvisioned}",
        )
        return snapshot
    }

    companion object {
        private const val TAG = "TerminalIdentity"
    }
}
