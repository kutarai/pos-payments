package com.synergy.payments.terminal

import kotlinx.coroutines.flow.Flow

/**
 * Interface for barcode scanning
 */
interface BarcodeScanner {
    /**
     * Scan a single barcode with optional format filtering
     * Returns null if scanning was cancelled or failed
     */
    suspend fun scanSingle(format: BarcodeFormat = BarcodeFormat.ALL): ScanResult?
    
    /**
     * Start continuous scanning mode
     * Results are emitted through the flow
     */
    fun startContinuousScan(): Flow<ScanResult>
    
    /**
     * Stop continuous scanning
     */
    suspend fun stopContinuousScan()
    
    /**
     * Check if scanner hardware is available
     */
    fun isScannerAvailable(): Boolean
    
    /**
     * Get supported barcode formats for this scanner
     */
    fun getSupportedFormats(): Set<BarcodeFormat>
}

/**
 * Barcode scanning result
 */
data class ScanResult(
    val rawValue: String,
    val format: BarcodeFormat,
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float = 1.0f,
    val rawBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanResult) return false
        
        if (rawValue != other.rawValue) return false
        if (format != other.format) return false
        if (timestamp != other.timestamp) return false
        if (confidence != other.confidence) return false
        if (rawBytes != null) {
            if (other.rawBytes == null) return false
            if (!rawBytes.contentEquals(other.rawBytes)) return false
        } else if (other.rawBytes != null) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = rawValue.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + (rawBytes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Supported barcode formats
 */
enum class BarcodeFormat {
    // 1D formats
    CODE_128,
    CODE_39,
    CODE_93,
    CODABAR,
    EAN_8,
    EAN_13,
    ITF,
    UPC_A,
    UPC_E,
    
    // 2D formats
    QR_CODE,
    DATA_MATRIX,
    AZTEC,
    PDF_417,
    
    // Special
    ALL
}

/**
 * Scanner configuration
 */
data class ScannerConfig(
    val enableBeep: Boolean = true,
    val enableVibration: Boolean = true,
    val enableFlash: Boolean = false,
    val autoFocus: Boolean = true,
    val scanTimeoutMs: Long = 30000,
    val continuousScanIntervalMs: Long = 1000,
    val formats: Set<BarcodeFormat> = setOf(BarcodeFormat.ALL)
)

/**
 * Scanner events for state management
 */
sealed class ScannerEvent {
    data object ScannerStarted : ScannerEvent()
    data object ScannerStopped : ScannerEvent()
    data class ScanError(val error: ScannerError) : ScannerEvent()
    data class FormatNotSupported(val format: BarcodeFormat) : ScannerEvent()
}

/**
 * Scanner errors
 */
sealed class ScannerError : Exception() {
    data object NoScannerAvailable : ScannerError()
    data object CameraPermissionDenied : ScannerError()
    data object CameraUnavailable : ScannerError()
    data object ScannerBusy : ScannerError()
    data class HardwareError(override val message: String) : ScannerError()
    data class UnknownError(override val cause: Throwable?) : ScannerError()
}