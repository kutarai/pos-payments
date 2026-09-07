package zw.co.unipay.payments.switching

import zw.co.unipay.payments.grpc.payment.CryptoPaymentRequest
import zw.co.unipay.payments.grpc.payment.MobileMoneyPaymentRequest
import zw.co.unipay.payments.grpc.payment.QrPaymentRequest
import zw.co.unipay.payments.terminal.TerminalSnapshot

/**
 * Every switch message that carries this terminal's identity is built here.
 *
 * Not for tidiness: identity used to be stamped at each call site, and the call sites disagreed —
 * one screen said "MERCHANT_001", the card path said "MERCH001", and the terminal id was the
 * serial number in all of them. One builder is what makes a fourth payment kind inherit the
 * right answer instead of inventing another one.
 */
object SwitchRequests {

    fun qr(
        identity: TerminalSnapshot,
        paymentReference: String,
        currency: String,
        amountMinor: Long,
        qrPayload: String,
        billNumber: String,
        latitude: Double,
        longitude: Double,
    ): QrPaymentRequest = QrPaymentRequest.newBuilder()
        .setDeviceId(identity.deviceId.orEmpty())
        .setTerminalId(identity.terminalId.orEmpty())
        .setSerialNumber(identity.serialNumber)
        .setMerchantId(identity.merchantId.orEmpty())
        .setPaymentReference(paymentReference)
        .setCurrency(currency)
        .setAmount(amountMinor)
        .setQrPayload(qrPayload)
        // The till's receipt number. The switch puts it in the payload's bill number, so the
        // number a cashier reads off the slip is the one in the code the customer scanned.
        .setBillNumber(billNumber)
        .setLatitude(latitude)
        .setLongitude(longitude)
        .build()

    /**
     * A crypto payment for this sale.
     *
     * The bill's own currency and amount go up, never a crypto figure: converting is
     * the switch's job at the switch's rate, and a till that quoted its own would be
     * offering a price nobody had agreed to honour.
     */
    fun crypto(
        identity: TerminalSnapshot,
        paymentReference: String,
        currency: String,
        amountMinor: Long,
        billNumber: String,
        asset: String = "",
        chain: String = "",
    ): CryptoPaymentRequest = CryptoPaymentRequest.newBuilder()
        .setDeviceId(identity.deviceId.orEmpty())
        .setTerminalId(identity.terminalId.orEmpty())
        .setSerialNumber(identity.serialNumber)
        .setMerchantId(identity.merchantId.orEmpty())
        .setPaymentReference(paymentReference)
        .setCurrency(currency)
        .setAmount(amountMinor)
        .setBillNumber(billNumber)
        // Empty asks the switch to choose. A cashier should not be asked which chain
        // settles fastest today.
        .setAsset(asset)
        .setChain(chain)
        .build()

    fun mobileMoney(
        identity: TerminalSnapshot,
        paymentReference: String,
        currency: String,
        amountMinor: Long,
        mobileNumber: String,
        latitude: Double,
        longitude: Double,
    ): MobileMoneyPaymentRequest = MobileMoneyPaymentRequest.newBuilder()
        .setDeviceId(identity.deviceId.orEmpty())
        .setTerminalId(identity.terminalId.orEmpty())
        .setSerialNumber(identity.serialNumber)
        .setMerchantId(identity.merchantId.orEmpty())
        .setPaymentReference(paymentReference)
        .setCurrency(currency)
        .setAmount(amountMinor)
        .setMobileNumber(mobileNumber)
        .setLatitude(latitude)
        .setLongitude(longitude)
        .build()
}
