package zw.co.unipay.payments

import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import zw.co.unipay.payments.card.CardTransactionType
import zw.co.unipay.payments.card.TerminalConfig
import zw.co.unipay.payments.grpc.payment.AcceptorReversalRequest
import zw.co.unipay.payments.grpc.payment.AcceptorReversalResponse
import zw.co.unipay.payments.switching.SwitchClient
import zw.co.unipay.payments.switching.SwitchIntegration
import zw.co.unipay.payments.switching.TerminalReversals
import zw.co.unipay.payments.switching.TerminalReversals.Delivery

/**
 * Undoing a withdrawal nobody answered.
 *
 * The bank may have debited the account while the terminal heard nothing. The switch can reverse
 * it, once told — and it is usually being told at the moment the line has just failed, so the
 * advice is retried until the switch says it has it.
 */
class TerminalReversalsTest {

    private val advice: AcceptorReversalRequest = AcceptorReversalRequest.newBuilder()
        .setDeviceId("DEV-0001").setExchangeId("EX-1").setPan("4111111111111111").build()

    private val accepted = AcceptorReversalResponse.newBuilder().setAccepted(true).build()
    private val notQueued = AcceptorReversalResponse.newBuilder().setAccepted(false).setMessage("retry").build()

    /** A sender whose answers are scripted, and which records how long it was told to wait. */
    private fun sender(vararg answers: () -> AcceptorReversalResponse): Triple<TerminalReversals, MutableList<Long>, () -> Int> {
        val waits = mutableListOf<Long>()
        var calls = 0
        val reversals = TerminalReversals(
            deliver = { answers[minOf(calls++, answers.size - 1)]() },
            schedule = listOf(0L, 5_000L, 15_000L),
            pause = { waits += it },
            report = { _, _, _ -> },
        )
        return Triple(reversals, waits, { calls })
    }

    @Test
    fun `an advice the switch accepts is sent once, straight away`() = runBlocking {
        val (reversals, waits, calls) = sender({ accepted })

        assertEquals(Delivery.ACCEPTED, reversals.deliverWithRetries(advice))
        assertEquals(1, calls())
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun `an unreachable switch is tried again on the schedule`() = runBlocking {
        val (reversals, waits, calls) = sender(
            { throw StatusRuntimeException(Status.UNAVAILABLE) },
            { throw StatusRuntimeException(Status.DEADLINE_EXCEEDED) },
            { accepted },
        )

        assertEquals(Delivery.ACCEPTED, reversals.deliverWithRetries(advice))
        assertEquals(3, calls())
        assertEquals(listOf(5_000L, 15_000L), waits)
    }

    /** The switch answering "could not queue it" is not an answer to stop on. */
    @Test
    fun `a switch that could not queue it is asked again`() = runBlocking {
        val (reversals, _, calls) = sender({ notQueued }, { accepted })

        assertEquals(Delivery.ACCEPTED, reversals.deliverWithRetries(advice))
        assertEquals(2, calls())
    }

    @Test
    fun `a refusal that no retry will change ends it`() = runBlocking {
        val (reversals, _, calls) = sender({ throw StatusRuntimeException(Status.INVALID_ARGUMENT) })

        assertEquals(Delivery.REFUSED, reversals.deliverWithRetries(advice))
        assertEquals(1, calls())
    }

    @Test
    fun `it gives up only after the whole schedule`() = runBlocking {
        val (reversals, _, calls) = sender({ throw StatusRuntimeException(Status.UNAVAILABLE) })

        assertEquals(Delivery.GAVE_UP, reversals.deliverWithRetries(advice))
        assertEquals(3, calls())
    }

    // ----- when a reversal is sent ------------------------------------------------------------

    @Test
    fun `an unanswered withdrawal is reversed`() {
        for (code in listOf(Status.Code.DEADLINE_EXCEEDED, Status.Code.UNAVAILABLE, Status.Code.UNKNOWN, Status.Code.INTERNAL)) {
            assertTrue("$code", SwitchIntegration.shouldReverse(CardTransactionType.CASH_WITHDRAWAL, code))
        }
    }

    /** A refusal is a definite no: nothing happened, so nothing needs undoing. */
    @Test
    fun `a withdrawal the switch refused is not`() {
        for (code in listOf(Status.Code.INVALID_ARGUMENT, Status.Code.PERMISSION_DENIED, Status.Code.UNIMPLEMENTED)) {
            assertFalse("$code", SwitchIntegration.shouldReverse(CardTransactionType.CASH_WITHDRAWAL, code))
        }
    }

    @Test
    fun `only withdrawals are reversed for now`() {
        for (type in CardTransactionType.entries.filter { it != CardTransactionType.CASH_WITHDRAWAL }) {
            assertFalse("$type", SwitchIntegration.shouldReverse(type, Status.Code.DEADLINE_EXCEEDED))
        }
    }

    // ----- what the advice says ---------------------------------------------------------------

    /** The switch finds the original by exchange id and has no PAN of its own to reverse with. */
    @Test
    fun `the advice names the original exchange and carries what the switch cannot recover`() {
        val config = TerminalConfig(
            terminalId = "TERM-42", merchantId = "MERCH-7", merchantName = "Agent 7",
            deviceId = "DEV-0001", serialNumber = "SN-123",
        )
        val integration = SwitchIntegration(SwitchClient { null }, TerminalReversals(deliver = { accepted }))
        val original = integration.buildAuthorisationRequest(
            config = config, emvTlvData = emptyMap(), pan = "4111111111111111",
            encryptedPinBlock = null, dukptKsn = null, cardEntryMode = "ICC",
            amount = 2_000L, transactionType = CardTransactionType.CASH_WITHDRAWAL,
        )

        val reversal = integration.reversalFor(original, "4111111111111111", config, "terminal timeout")

        assertEquals(original.header.exchangeId, reversal.exchangeId)
        assertEquals(original.transaction.transactionReference, reversal.transactionReference)
        assertEquals("DEV-0001", reversal.deviceId)
        assertEquals("MERCH-7", reversal.merchantId)
        assertEquals("4111111111111111", reversal.pan)
        assertEquals(2_000L, reversal.amount)
        assertEquals(original.transaction.currency, reversal.currency)
        assertEquals("terminal timeout", reversal.reason)
    }
}
