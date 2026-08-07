package com.synergy.payments.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
sealed class Payment {
    abstract val id: String
    abstract val transactionId: String
    abstract val amount: Money
    abstract val timestamp: Instant
    abstract val status: PaymentStatus
    abstract val method: PaymentMethod
    abstract val reference: String?
    abstract val notes: String?
    
    fun isSuccessful(): Boolean = status == PaymentStatus.APPROVED
    fun isPending(): Boolean = status == PaymentStatus.PENDING
    fun isFailed(): Boolean = status == PaymentStatus.DECLINED || status == PaymentStatus.ERROR
}

@Serializable
data class CardPayment(
    override val id: String,
    override val transactionId: String,
    override val amount: Money,
    override val timestamp: Instant = Clock.System.now(),
    override val status: PaymentStatus = PaymentStatus.PENDING,
    override val method: PaymentMethod = PaymentMethod.CARD,
    override val reference: String? = null,
    override val notes: String? = null,
    val cardType: CardType,
    val cardLastFour: String,
    val cardHolderName: String? = null,
    val authorizationCode: String? = null,
    val terminalId: String? = null,
    val emvData: EmvTransactionData? = null,
    val pinVerified: Boolean = false,
    val contactless: Boolean = false,
    val cardEntryMethod: CardEntryMethod = CardEntryMethod.CHIP
) : Payment()

@Serializable
data class CashPayment(
    override val id: String,
    override val transactionId: String,
    override val amount: Money,
    override val timestamp: Instant = Clock.System.now(),
    override val status: PaymentStatus = PaymentStatus.APPROVED,
    override val method: PaymentMethod = PaymentMethod.CASH,
    override val reference: String? = null,
    override val notes: String? = null,
    val tenderedAmount: Money,
    val changeAmount: Money
) : Payment()

@Serializable
data class MobilePayment(
    override val id: String,
    override val transactionId: String,
    override val amount: Money,
    override val timestamp: Instant = Clock.System.now(),
    override val status: PaymentStatus = PaymentStatus.PENDING,
    override val method: PaymentMethod = PaymentMethod.MOBILE,
    override val reference: String? = null,
    override val notes: String? = null,
    val provider: MobilePaymentProvider,
    val qrCodeData: String? = null,
    val mobileNumber: String? = null
) : Payment()

@Serializable
enum class PaymentMethod {
    CASH, CARD, MOBILE, CHECK, GIFT_CARD, LOYALTY_POINTS, OTHER
}

@Serializable
enum class PaymentStatus {
    PENDING, PROCESSING, APPROVED, DECLINED, CANCELLED, REFUNDED, ERROR
}

@Serializable
enum class CardType {
    VISA, MASTERCARD, AMEX, DISCOVER, UNIONPAY, JCB, DINERS, OTHER
}

@Serializable
enum class CardEntryMethod {
    CHIP, CONTACTLESS, SWIPE, MANUAL
}

@Serializable
enum class MobilePaymentProvider {
    APPLE_PAY, GOOGLE_PAY, SAMSUNG_PAY, ALIPAY, WECHAT_PAY, EMVCO_QR, MOBILE_MONEY, OTHER
}

@Serializable
data class EmvTransactionData(
    val applicationIdentifier: String? = null,
    val applicationLabel: String? = null,
    val applicationPreferredName: String? = null,
    val terminalVerificationResults: String? = null,
    val transactionStatusInformation: String? = null,
    val authorizationResponseCode: String? = null,
    val issuerApplicationData: String? = null,
    val cryptogram: String? = null,
    val cryptogramInformationData: String? = null,
    val cardholderVerificationMethodResults: String? = null,
    val terminalCountryCode: String? = null,
    val transactionCurrencyCode: String? = null,
    val transactionDate: String? = null,
    val transactionType: String? = null,
    val amountAuthorized: String? = null,
    val amountOther: String? = null,
    val terminalCapabilities: String? = null,
    val additionalTerminalCapabilities: String? = null,
    val issuerScriptResults: String? = null,
    val rawData: Map<String, String> = emptyMap()
) {
    fun isContactless(): Boolean {
        return cardholderVerificationMethodResults?.contains("NO_CVM") == true
    }
    
    fun isPinVerified(): Boolean {
        return cardholderVerificationMethodResults?.contains("PIN") == true ||
               cardholderVerificationMethodResults?.contains("SIGNATURE") == true
    }
}