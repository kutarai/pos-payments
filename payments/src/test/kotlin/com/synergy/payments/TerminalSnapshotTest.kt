package com.synergy.payments

import com.synergy.payments.terminal.Endpoint
import com.synergy.payments.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the terminal believes about itself, read from managed configuration.
 *
 * The values arrive from a policy written elsewhere, so every one of them can be absent,
 * blank, or malformed. A device that reads a half-set policy as provisioned would transact
 * under an identity nobody assigned it.
 */
class TerminalSnapshotTest {

    private fun values(
        deviceId: String? = "DEV-0001",
        terminalId: String? = "TERM-42",
        merchantId: String? = "MERCH-7",
        merchantName: String? = "Redcliff Municipality",
        tin: String? = "1234567890",
        endpoint: String? = "switch.unipay.co.zw:3333",
    ) = mapOf(
        TerminalSnapshot.KEY_DEVICE_ID to deviceId,
        TerminalSnapshot.KEY_TERMINAL_ID to terminalId,
        TerminalSnapshot.KEY_MERCHANT_ID to merchantId,
        TerminalSnapshot.KEY_MERCHANT_NAME to merchantName,
        TerminalSnapshot.KEY_MERCHANT_TIN to tin,
        TerminalSnapshot.KEY_SWITCH_ENDPOINT to endpoint,
    )

    @Test
    fun `a full policy provisions the terminal`() {
        val snapshot = TerminalSnapshot.parse(values(), serialNumber = "SN-123")

        assertEquals("DEV-0001", snapshot.deviceId)
        assertEquals("TERM-42", snapshot.terminalId)
        assertEquals("MERCH-7", snapshot.merchantId)
        assertEquals("Redcliff Municipality", snapshot.merchantName)
        assertEquals("1234567890", snapshot.taxIdentificationNumber)
        assertEquals(Endpoint("switch.unipay.co.zw", 3333), snapshot.endpoint)
        assertEquals("SN-123", snapshot.serialNumber)
        assertTrue(snapshot.isProvisioned)
    }

    @Test
    fun `the bank's terminal number may be absent and the terminal still transacts`() {
        val snapshot = TerminalSnapshot.parse(values(terminalId = null), serialNumber = "SN-123")

        assertNull(snapshot.terminalId)
        assertTrue(snapshot.isProvisioned)
    }

    @Test
    fun `no device id is not provisioned`() {
        assertFalse(TerminalSnapshot.parse(values(deviceId = null), "SN-123").isProvisioned)
    }

    @Test
    fun `no merchant is not provisioned`() {
        // A QR code with no merchant is a code no wallet can pay.
        assertFalse(TerminalSnapshot.parse(values(merchantId = null), "SN-123").isProvisioned)
    }

    @Test
    fun `no switch to reach is not provisioned`() {
        assertFalse(TerminalSnapshot.parse(values(endpoint = null), "SN-123").isProvisioned)
    }

    @Test
    fun `blank is the same as absent`() {
        val snapshot = TerminalSnapshot.parse(values(deviceId = "   ", terminalId = ""), "SN-123")

        assertNull(snapshot.deviceId)
        assertNull(snapshot.terminalId)
        assertFalse(snapshot.isProvisioned)
    }

    @Test
    fun `values are trimmed`() {
        val snapshot = TerminalSnapshot.parse(values(deviceId = "  DEV-0001  "), "SN-123")

        assertEquals("DEV-0001", snapshot.deviceId)
    }

    @Test
    fun `a missing key reads as absent`() {
        val snapshot = TerminalSnapshot.parse(emptyMap(), serialNumber = "SN-123")

        assertNull(snapshot.deviceId)
        assertNull(snapshot.endpoint)
        assertEquals("SN-123", snapshot.serialNumber)
        assertFalse(snapshot.isProvisioned)
    }

    @Test
    fun `an endpoint is a host and a port`() {
        assertEquals(Endpoint("switch.unipay.co.zw", 3333), Endpoint.parse("switch.unipay.co.zw:3333"))
        assertEquals(Endpoint("10.0.0.5", 443), Endpoint.parse("  10.0.0.5:443  "))
    }

    @Test
    fun `an endpoint the terminal cannot dial is no endpoint`() {
        assertNull(Endpoint.parse(null))
        assertNull(Endpoint.parse(""))
        assertNull(Endpoint.parse("switch.unipay.co.zw"))     // no port
        assertNull(Endpoint.parse("switch.unipay.co.zw:"))    // empty port
        assertNull(Endpoint.parse(":3333"))                   // no host
        assertNull(Endpoint.parse("switch.unipay.co.zw:zero"))// not a number
        assertNull(Endpoint.parse("switch.unipay.co.zw:0"))   // out of range
        assertNull(Endpoint.parse("switch.unipay.co.zw:70000"))
    }


    @Test
    fun `paperwork missing does not stop a payment`() {
        // The name goes on a receipt and the TIN goes to the revenue authority. Neither decides
        // where money lands, so a policy that omits them yields a worse receipt, not a closed
        // counter - refusing to trade over a missing line would turn a typo into lost takings.
        val snapshot = TerminalSnapshot.parse(
            values(merchantName = null, tin = null),
            serialNumber = "SN-123",
        )

        assertNull(snapshot.merchantName)
        assertNull(snapshot.taxIdentificationNumber)
        assertTrue(snapshot.isProvisioned)
    }
}
