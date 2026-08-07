package com.synergy.payments.card

/**
 * TLV (Tag-Length-Value) processor for EMV data
 * Based on existing Ciontek TLV utilities
 */
object TlvProcessor {
    
    /**
     * Parse TLV data string
     */
    fun parseTlv(data: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        
        while (index < data.length) {
            // Parse tag (1-2 bytes)
            val tag = when {
                data[index].digitToInt(16) and 0x1F == 0x1F -> {
                    // Two-byte tag
                    data.substring(index, index + 4).also { index += 4 }
                }
                else -> {
                    // One-byte tag
                    data.substring(index, index + 2).also { index += 2 }
                }
            }
            
            // Parse length
            val lengthBytes = when {
                data[index].digitToInt(16) and 0x80 != 0 -> {
                    // Multi-byte length
                    val numBytes = data[index].digitToInt(16) and 0x7F
                    data.substring(index, index + (numBytes * 2) + 2).also {
                        index += (numBytes * 2) + 2
                    }
                }
                else -> {
                    // Single-byte length
                    data.substring(index, index + 2).also { index += 2 }
                }
            }
            
            val length = hexToInt(lengthBytes)
            
            // Parse value
            val value = data.substring(index, index + (length * 2)).also {
                index += length * 2
            }
            
            result[tag] = value
        }
        
        return result
    }
    
    /**
     * Build TLV data string
     */
    fun buildTlv(data: Map<String, String>): String {
        val builder = StringBuilder()
        
        data.forEach { (tag, value) ->
            builder.append(tag)
            
            val length = value.length / 2
            when {
                length <= 127 -> {
                    builder.append(intToHex(length, 2))
                }
                length <= 255 -> {
                    builder.append("81")
                    builder.append(intToHex(length, 2))
                }
                length <= 65535 -> {
                    builder.append("82")
                    builder.append(intToHex(length, 4))
                }
                else -> {
                    throw IllegalArgumentException("Value too long")
                }
            }
            
            builder.append(value)
        }
        
        return builder.toString()
    }
    
    /**
     * Extract specific tags from TLV data
     */
    fun extractTags(data: String, tags: Set<String>): Map<String, String> {
        val parsed = parseTlv(data)
        return parsed.filterKeys { it in tags }
    }
    
    /**
     * Check if TLV data contains required tags
     */
    fun validateRequiredTags(data: String, requiredTags: Set<String>): Boolean {
        val parsed = parseTlv(data)
        return requiredTags.all { it in parsed }
    }
    
    /**
     * Convert EMV TLV to human-readable format
     */
    fun formatTlvForDisplay(data: String): Map<String, String> {
        val parsed = parseTlv(data)
        return parsed.mapValues { (tag, value) ->
            when (tag) {
                "9F02" -> "Amount, Authorized: ${formatAmount(value)}"
                "9F03" -> "Amount, Other: ${formatAmount(value)}"
                "9F1A" -> "Terminal Country Code: ${hexToInt(value)}"
                "9F26" -> "Application Cryptogram: $value"
                "9F27" -> "Cryptogram Information Data: $value"
                "9F36" -> "Application Transaction Counter: ${hexToInt(value)}"
                "5F2A" -> "Transaction Currency Code: ${hexToInt(value)}"
                "5F34" -> "Application Primary Account Number (PAN) Sequence Number: ${hexToInt(value)}"
                "82" -> "Application Interchange Profile: $value"
                "84" -> "Dedicated File (DF) Name: $value"
                "95" -> "Terminal Verification Results (TVR): $value"
                "9B" -> "Transaction Status Information (TSI): $value"
                "9C" -> "Transaction Type: ${formatTransactionType(value)}"
                "9F37" -> "Unpredictable Number: $value"
                "9F10" -> "Issuer Application Data: $value"
                "9F4E" -> "Merchant Name: ${hexToString(value)}"
                else -> "$tag: $value"
            }
        }
    }
    
    private fun formatAmount(hex: String): String {
        return try {
            val amount = hexToInt(hex) / 100.0
            String.format("$%.2f", amount)
        } catch (e: Exception) {
            "Invalid amount: $hex"
        }
    }
    
    private fun formatTransactionType(hex: String): String {
        return when (hex) {
            "00" -> "Purchase"
            "01" -> "Cash Advance"
            "03" -> "Refund"
            "09" -> "Pre-authorization"
            "0A" -> "Pre-authorization Completion"
            else -> "Unknown ($hex)"
        }
    }
    
    private fun hexToString(hex: String): String {
        return try {
            hex.chunked(2)
                .map { it.toInt(16).toChar() }
                .joinToString("")
                .trim()
        } catch (e: Exception) {
            hex
        }
    }
    
    private fun hexToInt(hex: String): Int {
        return hex.toInt(16)
    }
    
    private fun intToHex(value: Int, digits: Int): String {
        return value.toString(16).padStart(digits, '0').uppercase()
    }
}

/**
 * EMV-specific TLV tags
 */
object EmvTags {
    // Amounts
    const val AMOUNT_AUTHORIZED = "9F02"
    const val AMOUNT_OTHER = "9F03"
    const val AMOUNT_REFERENCE_CURRENCY = "9F3A"
    
    // Application data
    const val APPLICATION_IDENTIFIER = "4F"
    const val APPLICATION_LABEL = "50"
    const val APPLICATION_PREFERRED_NAME = "9F12"
    const val APPLICATION_PRIORITY_INDICATOR = "87"
    const val APPLICATION_USAGE_CONTROL = "9F07"
    const val APPLICATION_VERSION_NUMBER = "9F08"
    const val ISSUER_CODE_TABLE_INDEX = "9F11"
    
    // Card data
    const val PAN = "5A"
    const val PAN_SEQUENCE_NUMBER = "5F34"
    const val CARDHOLDER_NAME = "5F20"
    const val CARD_EXPIRY_DATE = "5F24"
    
    // Cryptogram data
    const val APPLICATION_CRYPTOGRAM = "9F26"
    const val CRYPTOGRAM_INFORMATION_DATA = "9F27"
    const val ISSUER_APPLICATION_DATA = "9F10"
    
    // Terminal data
    const val TERMINAL_VERIFICATION_RESULTS = "95"
    const val TRANSACTION_STATUS_INFORMATION = "9B"
    const val TRANSACTION_TYPE = "9C"
    const val TERMINAL_COUNTRY_CODE = "9F1A"
    const val TERMINAL_CAPABILITIES = "9F33"
    const val ADDITIONAL_TERMINAL_CAPABILITIES = "9F40"
    const val TRANSACTION_CURRENCY_CODE = "5F2A"
    const val TRANSACTION_DATE = "9A"
    const val TRANSACTION_TIME = "9F21"
    const val UNPREDICTABLE_NUMBER = "9F37"
    
    // Cardholder verification
    const val CARDHOLDER_VERIFICATION_METHOD_RESULTS = "9F34"
    const val CVM_RESULTS = "9F10"
    
    // Terminal risk management
    const val TERMINAL_FLOOR_LIMIT = "9F1B"
    const val TARGET_PERCENTAGE = "9F1C"
    const val MAX_TARGET_PERCENTAGE = "9F1D"
    const val TRANSACTION_CATEGORY_CODE = "9F53"
    
    // Required tags for basic transaction
    val REQUIRED_FOR_TRANSACTION = setOf(
        AMOUNT_AUTHORIZED,
        APPLICATION_CRYPTOGRAM,
        TERMINAL_VERIFICATION_RESULTS,
        TRANSACTION_STATUS_INFORMATION,
        TRANSACTION_TYPE,
        TERMINAL_COUNTRY_CODE,
        TRANSACTION_CURRENCY_CODE
    )
}