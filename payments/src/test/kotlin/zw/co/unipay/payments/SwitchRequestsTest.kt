package zw.co.unipay.payments

import zw.co.unipay.payments.switching.SwitchRequests
import zw.co.unipay.payments.terminal.Endpoint
import zw.co.unipay.payments.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the switch is told about who is asking.
 *
 * Three payment kinds carried identity three different ways, two of them from literals typed
 * into a screen. Building them in one place is what stops the next payment kind inventing a
 * fourth way, and what lets any of it be checked without a terminal.
 */
class SwitchRequestsTest {

    private val provisioned = TerminalSnapshot(
        deviceId = "DEV-0001",
        terminalId = "TERM-42",
        merchantId = "MERCH-7",
        merchantName = "Redcliff Municipality",
        taxIdentificationNumber = "1234567890",
        qrMerchantId = "600123456789",
        qrOutletNumber = 4,
        endpoint = Endpoint("switch.unipay.co.zw", 3333),
        serialNumber = "SN-123",
    )

    @Test
    fun `a QR payment names the device, the bank's terminal and the serial`() {
        val request = SwitchRequests.qr(
            identity = provisioned,
            paymentReference = "QR123",
            currency = "USD",
            amountMinor = 1250,
            qrPayload = "000201...",
            billNumber = "RCT-00417",
            latitude = -17.8,
            longitude = 31.0,
        )

        assertEquals("DEV-0001", request.deviceId)
        assertEquals("TERM-42", request.terminalId)
        assertEquals("SN-123", request.serialNumber)
        assertEquals("MERCH-7", request.merchantId)
        assertEquals("QR123", request.paymentReference)
        assertEquals(1250L, request.amount)
        // The switch renders this into the payload's bill number, so the number on the slip in
        // the cashier's hand is the one inside the code the customer scanned.
        assertEquals("RCT-00417", request.billNumber)
    }

    @Test
    fun `a mobile money payment names them too`() {
        val request = SwitchRequests.mobileMoney(
            identity = provisioned,
            paymentReference = "MOB123",
            currency = "USD",
            amountMinor = 500,
            mobileNumber = "0771234567",
            latitude = 0.0,
            longitude = 0.0,
        )

        assertEquals("DEV-0001", request.deviceId)
        assertEquals("TERM-42", request.terminalId)
        assertEquals("SN-123", request.serialNumber)
        assertEquals("0771234567", request.mobileNumber)
    }

    @Test
    fun `an unassigned terminal sends an empty terminal id, not a substituted one`() {
        val unassigned = provisioned.copy(terminalId = null)

        val qr = SwitchRequests.qr(unassigned, "QR1", "USD", 100, "payload", "RCT-1", 0.0, 0.0)
        val mobile = SwitchRequests.mobileMoney(unassigned, "MOB1", "USD", 100, "0771234567", 0.0, 0.0)

        // Empty, because the switch resolves the bank's number from the device id. Filling this
        // with the device id or the serial is how the two identifiers got conflated before.
        assertEquals("", qr.terminalId)
        assertEquals("", mobile.terminalId)
        assertEquals("DEV-0001", qr.deviceId)
        assertEquals("DEV-0001", mobile.deviceId)
    }

    @Test
    fun `a card authorisation puts the device id where the switch reads one`() {
        val config = zw.co.unipay.payments.card.TerminalConfig(
            terminalId = "TERM-42",
            merchantId = "MERCH-7",
            merchantName = "Synergy Pharmacy",
            deviceId = "DEV-0001",
            serialNumber = "SN-123",
        )

        val request = zw.co.unipay.payments.switching.SwitchIntegration(
            zw.co.unipay.payments.switching.SwitchClient { null }
        ).buildAuthorisationRequest(
            config = config,
            emvTlvData = emptyMap(),
            pan = "4111111111111111",
            encryptedPinBlock = null,
            dukptKsn = null,
            cardEntryMode = "ICC",
            amount = 1000L,
        )

        // The switch reads the device id from here — it has since the two identifiers were
        // separated. Sending the terminal id was why its device lookup never matched.
        assertEquals("DEV-0001", request.header.initiatingPartyId)
        assertEquals("DEV-0001", request.environment.poi.deviceId)
        assertEquals("TERM-42", request.environment.poi.terminalId)
        assertEquals("SN-123", request.environment.poi.serialNumber)
        assertEquals("MERCH-7", request.environment.merchant.id)
    }

    // ----- the chip cryptogram reaching the issuer ----------------------------------------
    //
    // An online chip authorisation is the issuer verifying a cryptogram the card generated.
    // Everything it needs to do that — the ARQC itself (9F26), the Issuer Application Data,
    // the unpredictable number, the ATC, the AIP, the TVR, the amount and currency — travels
    // as one BER-TLV blob in transaction.icc_related_data, read off the kernel at
    // onOnlineProc and carried here untouched.
    //
    // The only existing card-authorisation test passed an EMPTY tag map, so nothing asserted
    // that the cryptogram arrives at all. A change that emptied RAW_TLV would have kept the
    // suite green while every chip transaction went up with no cryptogram for the issuer to
    // check — which is indistinguishable, from the terminal, from a transaction that works.

    /** A realistic kernel read: the concatenated TLVs, ARQC first. */
    private val kernelTlv = mapOf(
        "RAW_TLV" to (
            "9F2608A1B2C3D4E5F60718" +      // Application Cryptogram (the ARQC)
            "9F2701" + "80" +               // Cryptogram Information Data — ARQC requested
            "9F1007" + "06010A03A00000" +   // Issuer Application Data
            "9F3704" + "11223344" +         // Unpredictable Number
            "9F3602" + "005B" +             // Application Transaction Counter
            "8202" + "5C00" +               // Application Interchange Profile
            "9505" + "0000000000"           // Terminal Verification Results
        ),
        "9F26" to "A1B2C3D4E5F60718",
        "5A" to "4111111111111111",
        "5F24" to "2812",
    )

    private fun authorisationWith(emv: Map<String, String>) =
        zw.co.unipay.payments.switching.SwitchIntegration(
            zw.co.unipay.payments.switching.SwitchClient { null }
        ).buildAuthorisationRequest(
            config = zw.co.unipay.payments.card.TerminalConfig(
                terminalId = "TERM-42", merchantId = "MERCH-7", merchantName = "Redcliff Municipality",
                deviceId = "DEV-0001", serialNumber = "SN-123",
            ),
            emvTlvData = emv,
            pan = "4111111111111111",
            encryptedPinBlock = null,
            dukptKsn = null,
            cardEntryMode = "ICC",
            amount = 5867L,
        )

    @Test
    fun `a chip authorisation carries the card's cryptogram to the issuer`() {
        val icc = authorisationWith(kernelTlv).transaction.iccRelatedData
        assertTrue("a chip transaction must not go up with empty ICC data", !icc.isEmpty)

        val hex = icc.toByteArray().joinToString("") { "%02X".format(it) }
        assertEquals("the blob is the kernel's TLV, byte for byte", kernelTlv["RAW_TLV"], hex)
        assertTrue(
            "the ARQC (9F26) must be present — without it the issuer has nothing to verify",
            hex.contains("9F2608A1B2C3D4E5F60718"),
        )
    }

    @Test
    fun `the tags the issuer needs to verify the cryptogram travel with it`() {
        val hex = authorisationWith(kernelTlv).transaction.iccRelatedData
            .toByteArray().joinToString("") { "%02X".format(it) }
        // An ARQC cannot be checked on its own: the issuer recomputes it from these.
        for ((tag, what) in listOf(
            "9F10" to "Issuer Application Data",
            "9F37" to "Unpredictable Number",
            "9F36" to "Application Transaction Counter",
            "82" to "Application Interchange Profile",
            "95" to "Terminal Verification Results",
        )) {
            assertTrue("$what ($tag) is missing from the ICC data", hex.contains(tag))
        }
    }

    @Test
    fun `a card read with no kernel TLV sends no ICC data, rather than something invented`() {
        // A magstripe read has no cryptogram. Sending an empty field says so; sending a
        // fabricated or partial one would have the issuer reject a valid swipe.
        assertTrue(authorisationWith(emptyMap()).transaction.iccRelatedData.isEmpty)
    }
}

