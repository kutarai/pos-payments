package zw.co.unipay.payments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import zw.co.unipay.payments.card.AccountBalance
import zw.co.unipay.payments.card.CardTransactionType
import zw.co.unipay.payments.card.TerminalConfig
import zw.co.unipay.payments.grpc.payment.AcceptorAuthorisationResponse
import zw.co.unipay.payments.grpc.payment.MobileMoneyOperation
import zw.co.unipay.payments.grpc.payment.MobileMoneyPaymentUpdate
import zw.co.unipay.payments.switching.SwitchRequests
import zw.co.unipay.payments.terminal.Endpoint
import zw.co.unipay.payments.terminal.TerminalSnapshot
import zw.co.unipay.payments.ui.mobileBalanceOf
import zw.co.unipay.payments.switching.SwitchClient
import zw.co.unipay.payments.switching.SwitchIntegration
import zw.co.unipay.payments.ui.CardTransactionRequest
import zw.co.unipay.payments.ui.parseAmountMinor

/**
 * Withdrawal, deposit and balance enquiry: what the switch is told, and what comes back.
 *
 * The switch routes all four card transactions down one path and tells them apart by the type
 * and the amount alone, so those two are the whole difference between a purchase and handing
 * a customer cash.
 */
class BankingTransactionsTest {

    private val integration = SwitchIntegration(SwitchClient { null })

    private fun request(type: CardTransactionType, amount: Long = 2_000L) =
        integration.buildAuthorisationRequest(
            config = TerminalConfig(
                terminalId = "TERM-42", merchantId = "MERCH-7", merchantName = "Agent 7",
                deviceId = "DEV-0001", serialNumber = "SN-123",
            ),
            emvTlvData = emptyMap(),
            pan = "4111111111111111",
            encryptedPinBlock = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            dukptKsn = null,
            cardEntryMode = "ICC",
            amount = amount,
            transactionType = type,
        ).transaction

    @Test
    fun `each type reaches the switch under the code it routes on`() {
        assertEquals("CRDP", request(CardTransactionType.PURCHASE).transactionType)
        assertEquals("CSHW", request(CardTransactionType.CASH_WITHDRAWAL).transactionType)
        assertEquals("CSHD", request(CardTransactionType.CASH_DEPOSIT).transactionType)
        assertEquals("BALC", request(CardTransactionType.BALANCE_ENQUIRY).transactionType)
    }

    @Test
    fun `a withdrawal and a deposit carry their amount`() {
        assertEquals(2_000L, request(CardTransactionType.CASH_WITHDRAWAL).amount)
        assertEquals(2_000L, request(CardTransactionType.CASH_DEPOSIT).amount)
    }

    /** Whatever the caller was holding, a balance enquiry must not look like a debit. */
    @Test
    fun `a balance enquiry is sent with no amount`() {
        assertEquals(0L, request(CardTransactionType.BALANCE_ENQUIRY, amount = 9_999L).amount)
    }

    /** Not cosmetic: the card signs 9C, and the switch sends the same two digits as DE 3. */
    @Test
    fun `the code the card signs matches the processing code the bank is sent`() {
        assertEquals("00", CardTransactionType.PURCHASE.emvCode)
        assertEquals("01", CardTransactionType.CASH_WITHDRAWAL.emvCode)
        assertEquals("21", CardTransactionType.CASH_DEPOSIT.emvCode)
        assertEquals("31", CardTransactionType.BALANCE_ENQUIRY.emvCode)
    }

    @Test
    fun `only a purchase is not banking`() {
        assertFalse(CardTransactionType.PURCHASE.isBanking)
        CardTransactionType.entries.filter { it != CardTransactionType.PURCHASE }
            .forEach { assertTrue("$it is banking", it.isBanking) }
    }

    // ----- the balance coming back ---------------------------------------------------------

    private fun response(available: Long, ledger: Long, currency: String) =
        AcceptorAuthorisationResponse.newBuilder()
            .setAvailableBalance(available)
            .setLedgerBalance(ledger)
            .setBalanceCurrency(currency)
            .build()

    @Test
    fun `a balance the bank sent is read with both figures`() {
        assertEquals(
            AccountBalance(available = 12_345L, ledger = 15_000L, currency = "USD"),
            integration.balanceOf(response(12_345L, 15_000L, "USD")),
        )
    }

    /** Proto3 has no "absent" for a number; the currency is what says a balance came back. */
    @Test
    fun `an account at zero still has a balance`() {
        assertEquals(
            AccountBalance(available = 0L, ledger = null, currency = "USD"),
            integration.balanceOf(response(0L, 0L, "USD")),
        )
    }

    @Test
    fun `no currency means the bank sent no balance`() {
        assertNull(integration.balanceOf(response(0L, 0L, "")))
    }

    // ----- the amount an agent types -------------------------------------------------------

    @Test
    fun `an amount typed in major units becomes minor units`() {
        assertEquals(1_250L, parseAmountMinor("12.50"))
        assertEquals(1_250L, parseAmountMinor("12.5"))
        assertEquals(1_200L, parseAmountMinor("12"))
        assertEquals(5L, parseAmountMinor(" 0.05 "))
    }

    /** A third decimal is a slip of the finger, not a fraction of a cent to round away. */
    @Test
    fun `anything that is not a positive two-decimal amount is refused`() {
        for (bad in listOf("", "  ", "0", "0.00", "-5", "12.345", "abc", "1,000")) {
            assertNull("'$bad' should be refused", parseAmountMinor(bad))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a withdrawal cannot be started without an amount`() {
        CardTransactionRequest.withdrawal(0L, "USD")
    }

    @Test
    fun `a balance enquiry needs none`() {
        assertEquals(0L, CardTransactionRequest.balanceEnquiry("USD").amountMinor)
    }

    // ----- mobile wallet ---------------------------------------------------------------------

    private val agent = TerminalSnapshot(
        deviceId = "DEV-0001",
        terminalId = "TERM-42",
        merchantId = "MERCH-7",
        merchantName = "Agent 7",
        taxIdentificationNumber = "1234567890",
        qrMerchantId = null,
        qrOutletNumber = 0,
        endpoint = Endpoint("switch.unipay.co.zw", 3333),
        serialNumber = "SN-123",
    )

    private fun mobile(type: CardTransactionType, amount: Long = 2_000L) = SwitchRequests.mobileMoneyBanking(
        identity = agent,
        transactionType = type,
        paymentReference = "MOB_1",
        currency = "USD",
        amountMinor = amount,
        mobileNumber = "0771234567",
        latitude = 0.0,
        longitude = 0.0,
    )

    /** The switch refuses a request with no operation; each type must name its own. */
    @Test
    fun `each banking type names its wallet operation`() {
        assertEquals(MobileMoneyOperation.MOBILE_CASH_OUT, mobile(CardTransactionType.CASH_WITHDRAWAL).operation)
        assertEquals(MobileMoneyOperation.MOBILE_CASH_IN, mobile(CardTransactionType.CASH_DEPOSIT).operation)
        assertEquals(MobileMoneyOperation.MOBILE_BALANCE_ENQUIRY, mobile(CardTransactionType.BALANCE_ENQUIRY).operation)
    }

    @Test
    fun `a mobile request carries the agent's identity and the wallet`() {
        val request = mobile(CardTransactionType.CASH_WITHDRAWAL)
        assertEquals("DEV-0001", request.deviceId)
        assertEquals("TERM-42", request.terminalId)
        assertEquals("MERCH-7", request.merchantId)
        assertEquals("0771234567", request.mobileNumber)
        assertEquals(2_000L, request.amount)
    }

    @Test
    fun `a mobile balance enquiry is sent with no amount`() {
        assertEquals(0L, mobile(CardTransactionType.BALANCE_ENQUIRY, amount = 9_999L).amount)
    }

    /** A purchase by mobile money is a payment, on the payment call — never this one. */
    @Test(expected = IllegalArgumentException::class)
    fun `a purchase cannot be sent as mobile banking`() {
        mobile(CardTransactionType.PURCHASE)
    }

    @Test
    fun `a mobile balance is read by its currency`() {
        assertNull(mobileBalanceOf(MobileMoneyPaymentUpdate.getDefaultInstance()))
        assertEquals(
            AccountBalance(available = 0L, ledger = null, currency = "USD"),
            mobileBalanceOf(MobileMoneyPaymentUpdate.newBuilder().setBalanceCurrency("USD").build()),
        )
        assertEquals(
            AccountBalance(available = 500L, ledger = 700L, currency = "ZWG"),
            mobileBalanceOf(
                MobileMoneyPaymentUpdate.newBuilder()
                    .setAvailableBalance(500L).setLedgerBalance(700L).setBalanceCurrency("ZWG").build()
            ),
        )
    }
}
