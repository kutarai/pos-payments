package com.synergy.payments.qr

/**
 * Seals a QR payload with a MAC computed inside the terminal's PED, so the switch can tell
 * that this till produced the code and not somebody with a printer.
 *
 * A MAC and not a signature, because the hardware cannot sign. The CS series PCI-SDK
 * documents nineteen calls; the PED generates an RSA pair inside its boundary
 * (`PedGenKeyPairTr34`) and exports the public half, but the private key's only documented
 * use is TR-34 key transport. There is no hash-and-sign. `PedRsaDecrypt` exists in the AAR
 * and in no document — the same class of undocumented call as `PedWritePinKey` mode 0x31,
 * which returned success and installed a different key from the one it was given.
 *
 * An RSA-2048 signature would not fit regardless: 256 bytes is 344 base64url characters
 * against EMVCo's 99-character cap on a template value.
 *
 * What is given up by using a MAC is non-repudiation. The switch holds the same key, so this
 * proves origin *to the switch* and is not evidence in a dispute between the switch and the
 * merchant. Nothing in the threat model asks for that: the question is whether a code was
 * produced by an enrolled till, and the switch is the only party that needs convincing.
 */
interface QrMacSealer {

    /**
     * The MAC over [payload], as uppercase hex.
     *
     * [payload] is the EMVCo string as it will be displayed, minus the tag-80 template that
     * carries the result and minus the CRC — see [EmvcoQrGenerator] for how those are
     * excluded, and why exclusion is defined as substring removal rather than re-serialising.
     *
     * Returns null when the terminal cannot seal the code: no MAC key injected yet, the PED
     * unreachable, the vendor call refusing. Null is not an error to swallow — a code that
     * cannot be sealed is one the switch will refuse, so the caller says so at the till
     * rather than presenting something that fails after the customer has scanned it.
     */
    fun seal(payload: String): String?
}

/**
 * Where the application says which terminal it is running on, for QR sealing.
 *
 * The same arrangement as [com.synergy.payments.card.CardPaymentDrivers] and for the same
 * reason: the QR screen is composed by the platform and cannot be handed dependencies. An
 * application that registers nothing gets a till that cannot present QR codes, said plainly,
 * rather than a crash inside a vendor SDK.
 */
object QrMacSealers {

    @Volatile
    private var sealer: QrMacSealer? = null

    fun register(sealer: QrMacSealer) {
        this.sealer = sealer
    }

    fun isRegistered(): Boolean = sealer != null

    /** Null when the application registered none — the caller decides what to tell the operator. */
    fun get(): QrMacSealer? = sealer
}
