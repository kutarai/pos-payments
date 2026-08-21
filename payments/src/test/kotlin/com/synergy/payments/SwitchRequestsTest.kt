package com.synergy.payments

import com.synergy.payments.switching.SwitchRequests
import com.synergy.payments.terminal.Endpoint
import com.synergy.payments.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
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
            latitude = -17.8,
            longitude = 31.0,
        )

        assertEquals("DEV-0001", request.deviceId)
        assertEquals("TERM-42", request.terminalId)
        assertEquals("SN-123", request.serialNumber)
        assertEquals("MERCH-7", request.merchantId)
        assertEquals("QR123", request.paymentReference)
        assertEquals(1250L, request.amount)
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

        val qr = SwitchRequests.qr(unassigned, "QR1", "USD", 100, "payload", 0.0, 0.0)
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
        val config = com.synergy.payments.card.TerminalConfig(
            terminalId = "TERM-42",
            merchantId = "MERCH-7",
            merchantName = "Synergy Pharmacy",
            deviceId = "DEV-0001",
            serialNumber = "SN-123",
        )

        val request = com.synergy.payments.switching.SwitchIntegration(
            com.synergy.payments.switching.SwitchClient { null }
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
}
