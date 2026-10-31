package zw.co.unipay.payments.terminal

import android.content.Context
import android.content.RestrictionsManager
import android.util.Log

/**
 * How this terminal encrypts a PIN block.
 *
 * The two schemes are not interchangeable and nothing about a transaction reveals which one an
 * acquirer expects: get it wrong and the PIN block is well-formed, encrypted, and undecryptable
 * at the other end, so the customer is declined with nothing on the terminal to say why. Which
 * is why it is the Device Owner's to state rather than the application's to assume.
 */
enum class PinEncryptionScheme {
    /**
     * Derived Unique Key Per Transaction. The PED derives a per-transaction key from an IPEK
     * and a counter, and returns the KSN saying which derivation the switch must replay.
     */
    DUKPT,

    /**
     * Master Key / Session Key. The PED encrypts under a session key already shared with the
     * acquirer, so there is no KSN — the switch knows the key from the terminal's identity.
     */
    MKSK;

    companion object {
        private const val TAG = "PinEncryptionScheme"

        /** Managed-configuration key. Values: "DUKPT" | "MKSK". */
        const val POLICY_KEY = "pin_encryption_scheme"

        /**
         * Reads the scheme from managed configuration.
         *
         * Read once, at initialisation, and used for the life of the session: a key ceremony is
         * not something that happens mid-shift, and re-reading it per transaction would mean a
         * policy pushed between the PIN entry and the authorisation could encrypt under one
         * scheme and be sent as the other.
         *
         * Falls back to [default], which is the caller's to choose because it depends on the make
         * of terminal: the Ciontek units in the field are all DUKPT, the ZCS ones MKSK. A fleet
         * that has not been told is a fleet that carries on working; the alternative is a policy
         * typo taking PIN entry away from every till at once.
         *
         * The log lines are deliberate and must stay. They are the only way to tell "no policy
         * was set" from "a policy was set and misspelt" on a terminal in the field, and both
         * end up using [default].
         */
        fun fromPolicy(context: Context, default: PinEncryptionScheme): PinEncryptionScheme {
            val raw = try {
                (context.applicationContext
                    .getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager)
                    .applicationRestrictions
                    ?.getString(POLICY_KEY)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not read managed configuration", t)
                null
            }

            val scheme = when (raw?.trim()?.uppercase()) {
                "MKSK", "MK_SK", "MS_TDESKSK" -> MKSK
                "DUKPT" -> DUKPT
                null, "" -> default
                else -> {
                    // Named, but not as anything recognised. Said out loud, because the
                    // alternative is silently encrypting under a scheme nobody chose.
                    Log.w(TAG, "$POLICY_KEY is \"$raw\", which is neither DUKPT nor MKSK — using $default")
                    default
                }
            }

            Log.i(TAG, "PIN encryption scheme: $scheme (policy said ${raw ?: "<absent>"})")
            return scheme
        }
    }
}
