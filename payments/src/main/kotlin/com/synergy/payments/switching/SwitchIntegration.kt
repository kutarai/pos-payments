package com.synergy.payments.switching

import android.util.Log
import com.google.protobuf.ByteString
import com.synergy.payments.card.TerminalConfig
import com.synergy.payments.grpc.payment.*
import io.grpc.StatusRuntimeException
import java.time.Instant
import java.util.UUID

/**
 * Orchestrates online authorisation via SynergySwitch.
 *
 * Called from [onOnlineProc] on the EMV kernel's binder thread.
 * All calls are synchronous/blocking.
 */
class SwitchIntegration(private val switchClient: SwitchClient) {

    companion object {
        private const val TAG = "SwitchIntegration"
    }

    /**
     * Result of an online authorisation attempt, ready for
     * [EMVOptV2.importOnlineProcStatus].
     */
    data class OnlineProcResult(
        val status: Int,                   // 0=approved, 1=declined, 2=unable to go online
        val responseTags: Array<String>,
        val responseValues: Array<String>,
        val authorisationCode: String?,
        val displayMessage: String?
    )

    /**
     * Build an ISO 20022 AcceptorAuthorisationRequest from EMV kernel data,
     * send it to SynergySwitch, and map the response for the EMV kernel.
     */
    fun performOnlineAuthorisation(
        terminalConfig: TerminalConfig,
        emvTlvData: Map<String, String>,
        pan: String,
        encryptedPinBlock: ByteArray?,
        dukptKsn: ByteArray?,
        cardEntryMode: String?,
        amount: Long
    ): OnlineProcResult {
        return try {
            val request = buildAuthorisationRequest(
                terminalConfig, emvTlvData, pan, encryptedPinBlock, dukptKsn, cardEntryMode, amount
            )

            Log.d(TAG, "Authorisation request: PAN=${maskPan(pan)}, " +
                "amount=$amount, entry=$cardEntryMode")

            val response = switchClient.authorise(request)

            // emvResponseCode and displayMessage carry the reason. Logging only the verdict left
            // "DECL, authCode=" as the whole story, with nothing to say whether it was the amount,
            // the merchant category or an absent session.
            Log.d(TAG, "Authorisation response: ${response.result.response}, " +
                "authCode=${response.result.authorisationCode}, " +
                "emvResponseCode=${response.result.emvResponseCode}, " +
                "message=${response.displayMessage}")

            mapResponse(response)

        } catch (e: StatusRuntimeException) {
            Log.e(TAG, "gRPC error: ${e.status}", e)
            // Distinguish "couldn't connect to switch" from "connected but slow".
            //   UNAVAILABLE / DNS / connection refused → switch offline.
            //   DEADLINE_EXCEEDED → connected but no response in time.
            //   anything else    → generic communication error.
            val msg = when (e.status.code) {
                io.grpc.Status.Code.UNAVAILABLE       -> "Bank offline"
                io.grpc.Status.Code.DEADLINE_EXCEEDED -> "Bank did not respond in time"
                // Include the gRPC status code + first 80 chars of the description
                // so the device-side error tells us why instead of just "Communication error".
                else -> "Communication error: ${e.status.code} ${e.status.description?.take(80) ?: ""}"
            }
            OnlineProcResult(
                status = 2,
                responseTags = arrayOf("8A"),
                responseValues = arrayOf("3936"), // "96" = system malfunction (ASCII)
                authorisationCode = null,
                displayMessage = msg
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during authorisation", e)
            OnlineProcResult(
                status = 2,
                responseTags = arrayOf("8A"),
                responseValues = arrayOf("3936"),
                authorisationCode = null,
                displayMessage = "System error"
            )
        }
    }

    // ── Build protobuf request from EMV data ──────────────────────────

    internal fun buildAuthorisationRequest(
        config: TerminalConfig,
        emvTlvData: Map<String, String>,
        pan: String,
        encryptedPinBlock: ByteArray?,
        dukptKsn: ByteArray?,
        cardEntryMode: String?,
        amount: Long
    ): AcceptorAuthorisationRequest {
        val exchangeId = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val transactionRef = "TXN${System.currentTimeMillis()}"

        // CVM method from tag 9F34 (CVM Results) or PIN block presence
        val cvmMethod = determineCvmMethod(emvTlvData["9F34"], encryptedPinBlock)

        // Card data from EMV tags
        val expiryDate = emvTlvData["5F24"]?.take(4) ?: ""   // YYMM
        val track2 = emvTlvData["57"] ?: ""
        val panSeqNo = emvTlvData["5F34"] ?: ""

        // Raw BER-TLV ICC data for switch-side EMV processing
        val iccData = emvTlvData["RAW_TLV"]?.let { hexToBytes(it) } ?: ByteArray(0)

        // Map card entry mode string to protobuf enum
        val entryMode = when (cardEntryMode) {
            "ICC" -> CardDataEntryMode.CICC
            "NFC" -> CardDataEntryMode.ECTL_ENTRY
            "MCR" -> CardDataEntryMode.MGST_ENTRY
            else -> CardDataEntryMode.CICC
        }

        // Always send the ISO 4217 NUMERIC code (3 digits) so the switch
        // can't mis-parse alpha labels.
        //   - If config.currencyCode is already numeric ("840" / "0840"),
        //     strip a leading "0" to normalise to 3 digits.
        //   - If it came through as alpha ("USD"), translate.
        val currencyNumeric = run {
            val cc = config.currencyCode.uppercase()
            when {
                cc.length == 4 && cc.startsWith("0") && cc.drop(1).all { it.isDigit() } -> cc.drop(1)
                cc.length == 3 && cc.all { it.isDigit() } -> cc
                else -> when (cc) {
                    "USD" -> "840"
                    "ZWG" -> "924"
                    "ZWL" -> "932"
                    "ZAR" -> "710"
                    "EUR" -> "978"
                    "CNY", "RMB" -> "156"
                    "GBP" -> "826"
                    else  -> "840"
                }
            }
        }

        return AcceptorAuthorisationRequest.newBuilder().apply {
            header = MessageHeader.newBuilder().apply {
                messageFunction = "AUTQ"
                protocolVersion = "14.0"
                this.exchangeId = exchangeId
                creationDateTime = now
                // The switch reads its device identity from here. It read a terminal id for as
                // long as this said config.terminalId, and its device lookup never matched.
                initiatingPartyId = config.deviceId
            }.build()

            environment = Environment.newBuilder().apply {
                merchant = Merchant.newBuilder().apply {
                    id = config.merchantId
                    commonName = config.merchantName
                    categoryCode = config.merchantCategoryCode
                }.build()

                poi = PointOfInteraction.newBuilder().apply {
                    id = config.terminalId
                    this.deviceId = config.deviceId
                    this.terminalId = config.terminalId
                    this.serialNumber = config.serialNumber
                    addCardReadingCapabilities(CardReadingCapability.ICC)
                    addCardReadingCapabilities(CardReadingCapability.ECTL)
                    addCardReadingCapabilities(CardReadingCapability.MGST)
                    addCvmCapabilities(CvmCapability.NPIN)
                    addCvmCapabilities(CvmCapability.NOCV)
                }.build()

                card = CardData.newBuilder().apply {
                    this.pan = pan
                    cardSequenceNumber = panSeqNo
                    this.expiryDate = expiryDate
                    track2EquivalentData = track2
                }.build()

                cardholderAuth = CardholderAuthentication.newBuilder().apply {
                    method = cvmMethod
                    if (encryptedPinBlock != null) {
                        this.encryptedPinBlock = ByteString.copyFrom(encryptedPinBlock)
                        pinFormat = "ISO0"
                    }
                    // When the terminal used ServiceGetDukptPinBlock, attach the
                    // KSN so the switch tells its HSM to derive the matching
                    // per-transaction key instead of using the static MK/SK WK.
                    if (dukptKsn != null && dukptKsn.isNotEmpty()) {
                        this.dukptKsn = ByteString.copyFrom(dukptKsn)
                    }
                }.build()
            }.build()

            context = PaymentContext.newBuilder().apply {
                cardDataEntryMode = entryMode
            }.build()

            transaction = Transaction.newBuilder().apply {
                transactionType = "CRDP"
                transactionDateTime = now
                transactionReference = transactionRef
                currency = currencyNumeric
                this.amount = amount
                if (iccData.isNotEmpty()) {
                    iccRelatedData = ByteString.copyFrom(iccData)
                }
            }.build()
        }.build()
    }

    // ── Map switch response to EMV kernel format ──────────────────────

    private fun mapResponse(response: AcceptorAuthorisationResponse): OnlineProcResult {
        val result = response.result
        val emvResponseCode = result.emvResponseCode.ifEmpty { "3030" }

        val status = when (result.response) {
            ResponseCode.APPR -> 0  // Approved
            ResponseCode.DECL -> 1  // Declined
            ResponseCode.PRMS -> 0  // Partial approval (treat as approved for EMV kernel)
            ResponseCode.TECH -> 2  // Technical error — unable to go online
            else -> 2
        }

        return OnlineProcResult(
            status = status,
            responseTags = arrayOf("8A"),
            responseValues = arrayOf(emvResponseCode),
            authorisationCode = result.authorisationCode.ifEmpty { null },
            displayMessage = response.displayMessage.ifEmpty { null }
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun determineCvmMethod(
        cvmResults: String?,
        encryptedPinBlock: ByteArray?
    ): CvmMethod {
        // If we have an encrypted PIN block, it was online PIN
        if (encryptedPinBlock != null && encryptedPinBlock.isNotEmpty()) {
            return CvmMethod.ONLINE_PIN
        }

        // Parse CVM Results tag (9F34): first byte indicates the method performed
        if (cvmResults != null && cvmResults.length >= 2) {
            val cvmByte = try {
                cvmResults.substring(0, 2).toInt(16)
            } catch (_: Exception) { 0 }

            return when (cvmByte) {
                0x01, 0x02 -> CvmMethod.OFFLINE_PIN
                0x04 -> CvmMethod.ONLINE_PIN
                0x1E -> CvmMethod.SIGNATURE
                0x1F -> CvmMethod.NO_CVM_PERFORMED
                else -> CvmMethod.NO_CVM_PERFORMED
            }
        }

        return CvmMethod.NO_CVM_PERFORMED
    }

    private fun maskPan(pan: String): String {
        if (pan.length < 8) return "****"
        return "${pan.take(6)}${"*".repeat(pan.length - 10)}${pan.takeLast(4)}"
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
