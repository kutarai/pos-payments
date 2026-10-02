package zw.co.unipay.payments.qr

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Seals with an ECDSA P-256 key the till generates in its own Android Keystore.
 *
 * The key is this application's alone: Keystore keys belong to the app that made them, so the
 * MDM agent's key — which unwraps injected PIN keys — is a different key in a different app,
 * and the switch keeps the two apart. That is also why the till reports this one itself.
 */
object AndroidKeystoreQrSealer : QrSealer {

    private const val TAG = "QrSealer"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** Versioned, so a key generated under different parameters can be retired by a new alias. */
    private const val ALIAS = "unipay-qr-seal-p256"

    /** P-256's order is 256 bits, so r and s are 32 bytes each in the fixed-width form. */
    private const val COMPONENT_BYTES = 32

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    override fun seal(payload: String): String? = try {
        val key = privateKey() ?: return null

        // SHA256withECDSA and not "...inP1363Format": the Keystore provider is only guaranteed to
        // offer the DER form, so this signs in DER and re-shapes the result. The re-shaping is
        // not optional — DER is 70–72 bytes, which as base64url overruns the seal.
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(payload.toByteArray(Charsets.UTF_8))
            sign()
        }

        Base64.encodeToString(
            derToP1363(der), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.e(TAG, "Could not sign the QR payload", e)
        null
    }

    override fun publicKeyDer(): ByteArray? = runCatching {
        ensureKey()
        keyStore.getCertificate(ALIAS)?.publicKey?.encoded
    }.onFailure { Log.e(TAG, "Could not read the QR signing public key", it) }.getOrNull()

    override fun securityLevel(): String = try {
        val key = privateKey() ?: return "none"
        val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                else -> "unknown"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "tee" else "software"
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not read the QR signing key's security level", e)
        "unknown"
    }

    private fun privateKey(): PrivateKey? {
        ensureKey()
        return keyStore.getKey(ALIAS, null) as? PrivateKey
    }

    @Synchronized
    private fun ensureKey() {
        if (keyStore.containsAlias(ALIAS)) return

        // StrongBox where the terminal has it, falling back rather than failing. securityLevel()
        // reports which was actually granted; the switch can decide whether that is enough.
        if (!generate(strongBox = true)) generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): Boolean = try {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // No user authentication: the till seals unattended, mid-sale. The protection is
            // that the key cannot be exported, not that someone unlocked the screen.
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
        true
    } catch (t: Throwable) {
        // Throwable: a vendor build missing a framework method throws an Error, and a till that
        // cannot make a key should say so at the QR screen, not die.
        if (strongBox) Log.i(TAG, "StrongBox unavailable for the QR key; using the default keystore")
        else Log.e(TAG, "Could not generate a QR signing key", t)
        false
    }

    /**
     * DER `SEQUENCE { INTEGER r, INTEGER s }` to the fixed-width `r || s` the switch verifies.
     *
     * Each INTEGER is minimal two's-complement, so it may carry a leading zero (when the top bit
     * is set) or be shorter than 32 bytes (when it has leading zeros of its own). Both are undone
     * here: strip to the magnitude, then left-pad to 32.
     */
    internal fun derToP1363(der: ByteArray): ByteArray {
        var i = 0
        require(der[i++] == 0x30.toByte()) { "not a DER sequence" }
        i += lengthOfLength(der, i)

        val out = ByteArray(COMPONENT_BYTES * 2)
        for (component in 0..1) {
            require(der[i++] == 0x02.toByte()) { "not a DER integer" }
            val length = der[i++].toInt() and 0xFF
            var start = i
            var size = length
            while (size > COMPONENT_BYTES && der[start] == 0.toByte()) {
                start++
                size--
            }
            require(size <= COMPONENT_BYTES) { "component is wider than P-256" }
            System.arraycopy(der, start, out, component * COMPONENT_BYTES + (COMPONENT_BYTES - size), size)
            i += length
        }
        return out
    }

    /** How many bytes the sequence's length field occupies, so the walk can step over it. */
    private fun lengthOfLength(der: ByteArray, at: Int): Int {
        val first = der[at].toInt() and 0xFF
        return if (first < 0x80) 1 else 1 + (first and 0x7F)
    }
}
