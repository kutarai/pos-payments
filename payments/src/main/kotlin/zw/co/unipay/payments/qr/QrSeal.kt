package zw.co.unipay.payments.qr

/**
 * Seals a QR payload with a signature only this till can make, so the switch can tell that
 * this till produced the code and not somebody with a printer.
 *
 * The till generates an ECDSA P-256 pair in its own Android Keystore the first time it needs
 * one, signs with the private half — which never leaves the secure hardware — and reports the
 * public half to the switch on a heartbeat. The switch verifies against that.
 *
 * Why P-256 and not RSA, which is what the device's other key is: the seal has 87 characters
 * to live in. EMVCo caps a template value at 99, and the scheme GUID and the signature's own
 * tag and length spend 12 of them. A P-256 signature in IEEE P1363 form is 64 bytes, which is
 * 86 characters of base64url. RSA-2048 is 256 bytes — 344 characters — and does not come close.
 *
 * Why not the PED: the CS series PCI-SDK has no signing call. It MACs, under a key the switch
 * also holds, which proves origin to the switch but nothing more; and it needs a MAC key
 * injected into every terminal before the first QR sale. A Keystore key needs nothing injected.
 */
interface QrSealer {

    /**
     * The signature over [payload], as unpadded base64url — case-sensitive, so nothing on the
     * way to the switch may change its case.
     *
     * [payload] is the EMVCo string as it will be displayed, minus the tag-80 template that
     * carries the result and minus the CRC — see [EmvcoQrGenerator] for how those are
     * excluded, and why exclusion is defined as substring removal rather than re-serialising.
     *
     * Returns null when the terminal cannot seal the code. Null is not an error to swallow — a
     * code that cannot be sealed is one the switch will refuse, so the caller says so at the
     * till rather than presenting something that fails after the customer has scanned it.
     */
    fun seal(payload: String): String?

    /**
     * The public half, as X.509 SubjectPublicKeyInfo DER — what the switch imports. Null when
     * there is no key and one cannot be made.
     */
    fun publicKeyDer(): ByteArray?

    /** "strongbox", "tee" or "software", read from the key rather than from what was asked for. */
    fun securityLevel(): String
}

/**
 * Where the application can say how this terminal seals QR codes.
 *
 * Most need say nothing: [get] falls back to a Keystore-backed sealer, which any Android
 * terminal can provide. [register] exists for a terminal that has something better, and for
 * tests.
 */
object QrSealers {

    @Volatile
    private var sealer: QrSealer? = null

    fun register(sealer: QrSealer) {
        this.sealer = sealer
    }

    fun get(): QrSealer = sealer ?: AndroidKeystoreQrSealer.also { sealer = it }
}
