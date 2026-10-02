package zw.co.unipay.payments.qr

import android.util.Log
import com.google.protobuf.ByteString
import zw.co.unipay.payments.grpc.terminal.RegisterQrSigningKeyRequest
import zw.co.unipay.payments.switching.SwitchClient

/**
 * Tells the switch which key this till signs QR codes with, before the first code it signs.
 *
 * Once per key per process: the call is cheap, but it sits in front of a sale, and the key only
 * changes when the app is reinstalled or its keystore cleared — both of which start a new
 * process. Re-sent whenever the key differs from the one last accepted, which covers a terminal
 * re-enrolled under a different device id as well.
 */
object QrSigningKeyRegistration {

    private const val TAG = "QrSigningKey"

    sealed interface Outcome {
        /** The switch holds this till's key. */
        data object Registered : Outcome

        /** The switch answered and said no; [reason] is its own words, fit for the log. */
        data class Refused(val reason: String) : Outcome

        /** The switch could not be asked. The sale goes ahead and its own call reports why. */
        data class Unreachable(val cause: Exception) : Outcome
    }

    @Volatile
    private var registered: Pair<String, ByteString>? = null

    /** Blocking: call it off the main thread. */
    fun ensure(client: SwitchClient, deviceId: String, sealer: QrSealer): Outcome {
        val der = sealer.publicKeyDer()
            ?: return Outcome.Refused("this terminal has no QR signing key and cannot make one")

        val key = deviceId to ByteString.copyFrom(der)
        if (registered == key) return Outcome.Registered

        return try {
            val response = client.registerQrSigningKey(
                RegisterQrSigningKeyRequest.newBuilder()
                    .setDeviceId(deviceId)
                    .setPublicKey(key.second)
                    .setSecurityLevel(sealer.securityLevel())
                    .build())

            if (response.success) {
                registered = key
                Outcome.Registered
            } else {
                Log.e(TAG, "The switch refused this till's QR signing key: ${response.message}")
                Outcome.Refused(response.message)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not register the QR signing key; the sale will report the cause", e)
            Outcome.Unreachable(e)
        }
    }
}
