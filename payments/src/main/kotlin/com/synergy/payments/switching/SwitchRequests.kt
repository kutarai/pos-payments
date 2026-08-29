package com.synergy.payments.switching

import com.synergy.payments.grpc.payment.MobileMoneyPaymentRequest
import com.synergy.payments.grpc.payment.QrPaymentRequest
import com.synergy.payments.terminal.TerminalSnapshot

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
