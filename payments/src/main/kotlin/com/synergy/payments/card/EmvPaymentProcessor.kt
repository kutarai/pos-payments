package com.synergy.payments.card

import com.synergy.payments.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Interface for EMV payment processing
 */
interface EmvPaymentProcessor {
    /**
     * Initialize payment terminal
     */
    suspend fun initializeTerminal(config: TerminalConfig): TerminalStatus
    
    /**
     * Process EMV card payment
     */
    suspend fun processCardPayment(request: CardPaymentRequest): CardPaymentResult
    
    /**
     * Process contactless (NFC) payment
     */
    suspend fun processContactlessPayment(request: ContactlessPaymentRequest): CardPaymentResult
    
    /**
     * Cancel current payment transaction
     */
    suspend fun cancelTransaction(reference: String? = null): Boolean
    
    /**
     * Get terminal status
     */
    suspend fun getTerminalStatus(): TerminalStatus
    
    /**
     * Listen for card insertion/removal events
     */
    fun listenForCardEvents(): Flow<CardEvent>
    
    /**
     * Perform terminal self-test
     */
    suspend fun performSelfTest(): SelfTestResult
    
    /**
     * Update terminal firmware/parameters
     */
    suspend fun updateTerminal(update: TerminalUpdate): UpdateResult
}

/**
 * Card payment request
 */
data class CardPaymentRequest(
    val amount: Money,
    val currencyCode: String = "840", // USD
    val transactionType: TransactionType = TransactionType.SALE,
    val reference: String? = null,
    val invoiceNumber: String? = null,
    val customerReference: String? = null,
    val forceOnline: Boolean = false,
    val fallbackToSwipe: Boolean = true,
    val requirePin: Boolean = true,
    val tipAmount: Money? = null,
    val additionalData: Map<String, String> = emptyMap()
)

/**
 * Contactless payment request
 */
data class ContactlessPaymentRequest(
    val amount: Money,
    val currencyCode: String = "840",
    val transactionType: TransactionType = TransactionType.SALE,
    val reference: String? = null,
    val customerReference: String? = null,
    val tapTimeoutSeconds: Int = 30,
    val requireCvm: Boolean = true,
    val additionalData: Map<String, String> = emptyMap()
)

/**
 * Card payment result
 */
sealed class CardPaymentResult {
    data class Success(
        val payment: CardPayment,
        val emvData: EmvTransactionData,
        val receiptData: ReceiptData? = null
    ) : CardPaymentResult()
    
    data class Declined(
        val reason: DeclineReason,
        val responseCode: String? = null,
        val message: String? = null
    ) : CardPaymentResult()
    
    data class Error(
        val error: PaymentError,
        val retryable: Boolean = false
    ) : CardPaymentResult()
    
    data object Cancelled : CardPaymentResult()
    data object Timeout : CardPaymentResult()
}

/**
 * Decline reasons
 */
enum class DeclineReason {
    INSUFFICIENT_FUNDS,
    EXPIRED_CARD,
    STOLEN_CARD,
    LOST_CARD,
    INCORRECT_PIN,
    TRANSACTION_NOT_PERMITTED,
    EXCEEDS_WITHDRAWAL_LIMIT,
    SECURITY_VIOLATION,
    CARD_NOT_SUPPORTED,
    DO_NOT_HONOR,
    INVALID_TRANSACTION,
    CONTACT_ISSUER,
    SYSTEM_ERROR
}

/**
 * Payment errors
 */
sealed class PaymentError {
    data object TerminalNotInitialized : PaymentError()
    data object NoCardDetected : PaymentError()
    data object CardReadError : PaymentError()
    data object PinEntryFailed : PaymentError()
    data object NetworkError : PaymentError()
    data object TerminalBusy : PaymentError()
    data object InvalidAmount : PaymentError()
    data object InvalidCurrency : PaymentError()
    data object TerminalError : PaymentError()
    data class CommunicationError(val message: String) : PaymentError()
    data class ConfigurationError(val message: String) : PaymentError()
    data class UnknownError(val cause: Throwable?) : PaymentError()
}

/**
 * Terminal configuration
 */
data class TerminalConfig(
    val terminalId: String,
    val merchantId: String,
    val merchantName: String,
    /** Ours, permanent, from managed configuration. The switch resolves the bank's number from it. */
    val deviceId: String = "",
    /** The hardware's serial, read from the terminal rather than configured. */
    val serialNumber: String = "",
    val merchantCategoryCode: String = DEFAULT_MERCHANT_CATEGORY_CODE,
    val countryCode: String = "840", // USA
    val currencyCode: String = "840", // USD
    val terminalCapabilities: String = "E0F8C8", // Contact, contactless, MSR
    val additionalTerminalCapabilities: String = "6000000000",
    val terminalType: TerminalType = TerminalType.ATTENDED,
    val floorLimit: Money = Money(0.0, "USD"), // No floor limit
    val contactlessFloorLimit: Money = Money(50.0, "USD"),
    val requireOnlinePin: Boolean = true,
    val enableContactless: Boolean = true,
    val enableChip: Boolean = true,
    val enableMagstripe: Boolean = true,
    val connectionType: ConnectionType = ConnectionType.USB,
    val hostUrls: List<String> = emptyList(),
    val timeoutSeconds: Int = 60
) {
    companion object {
        /**
         * The category this till presents, for cards and QR codes alike.
         *
         * Defined once because it was defined twice: the card path said 5912 while
         * EmvcoQrGenerator defaulted to 5999, so a QR sale was declined for a merchant category
         * the same till did not use when the card was tapped.
         */
        const val DEFAULT_MERCHANT_CATEGORY_CODE = "5912"  // Drug stores and pharmacies
    }
}

/**
 * Terminal types
 */
enum class TerminalType {
    ATTENDED, UNATTENDED, MOBILE, SELF_SERVICE
}

/**
 * Connection types
 */
enum class ConnectionType {
    USB, BLUETOOTH, WIFI, ETHERNET, SERIAL
}

/**
 * Terminal status
 */
data class TerminalStatus(
    val isInitialized: Boolean,
    val isOnline: Boolean,
    val batteryLevel: Int? = null,
    val signalStrength: Int? = null,
    val lastTransactionTime: Instant? = null,
    val transactionCount: Long = 0,
    val errors: List<TerminalError> = emptyList(),
    val firmwareVersion: String? = null,
    val serialNumber: String? = null,
    val supportedCardTypes: Set<CardType> = emptySet()
)

/**
 * Terminal errors
 */
data class TerminalError(
    val code: String,
    val message: String,
    val severity: ErrorSeverity,
    val timestamp: Instant = Clock.System.now()
)

/**
 * Error severity levels
 */
enum class ErrorSeverity {
    INFO, WARNING, ERROR, CRITICAL
}

/**
 * Card events
 */
sealed class CardEvent {
    data object CardInserted : CardEvent()
    data object CardRemoved : CardEvent()
    data object CardTapped : CardEvent()
    data class CardError(val error: CardErrorType) : CardEvent()
    data object PinEntryStarted : CardEvent()
    data object PinEntryCompleted : CardEvent()
}

/**
 * Card error types
 */
enum class CardErrorType {
    READ_ERROR, CHIP_ERROR, CONTACTLESS_ERROR, UNSUPPORTED_CARD, EXPIRED_CARD
}

/**
 * Self-test result
 */
data class SelfTestResult(
    val passed: Boolean,
    val tests: List<TestResult>,
    val timestamp: Instant = Clock.System.now()
)

/**
 * Individual test result
 */
data class TestResult(
    val name: String,
    val passed: Boolean,
    val message: String? = null,
    val durationMs: Long = 0
)

/**
 * Terminal update
 */
sealed class TerminalUpdate {
    data class FirmwareUpdate(val url: String, val version: String) : TerminalUpdate()
    data class ParameterUpdate(val parameters: Map<String, String>) : TerminalUpdate()
    data class AidUpdate(val aids: List<ApplicationIdentifier>) : TerminalUpdate()
    data class CapkUpdate(val capks: List<CertificateAuthorityPublicKey>) : TerminalUpdate()
}

/**
 * Application Identifier (AID)
 */
data class ApplicationIdentifier(
    val aid: String,
    val label: String,
    val priority: Int = 0,
    val floorLimit: Money? = null,
    val targetPercentage: Int? = null,
    val maxTargetPercentage: Int? = null,
    val tacDefault: String? = null,
    val tacDenial: String? = null,
    val tacOnline: String? = null
)

/**
 * Certificate Authority Public Key (CAPK)
 */
data class CertificateAuthorityPublicKey(
    val rid: String,
    val index: Int,
    val hash: String,
    val exponent: String,
    val modulus: String,
    val expiryDate: String? = null
)

/**
 * Update result
 */
data class UpdateResult(
    val success: Boolean,
    val message: String? = null,
    val requiresRestart: Boolean = false
)

/**
 * Receipt data for printing
 */
data class ReceiptData(
    val merchantCopy: String,
    val customerCopy: String,
    val qrCodeData: String? = null,
    val signatureRequired: Boolean = false,
    val signatureData: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReceiptData) return false
        
        if (merchantCopy != other.merchantCopy) return false
        if (customerCopy != other.customerCopy) return false
        if (qrCodeData != other.qrCodeData) return false
        if (signatureRequired != other.signatureRequired) return false
        if (signatureData != null) {
            if (other.signatureData == null) return false
            if (!signatureData.contentEquals(other.signatureData)) return false
        } else if (other.signatureData != null) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = merchantCopy.hashCode()
        result = 31 * result + customerCopy.hashCode()
        result = 31 * result + (qrCodeData?.hashCode() ?: 0)
        result = 31 * result + signatureRequired.hashCode()
        result = 31 * result + (signatureData?.contentHashCode() ?: 0)
        return result
    }
}