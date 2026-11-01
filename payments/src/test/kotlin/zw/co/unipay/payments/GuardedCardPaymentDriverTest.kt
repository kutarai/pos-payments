package zw.co.unipay.payments

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import zw.co.unipay.payments.card.CardFlowUpdate
import zw.co.unipay.payments.card.CardPaymentDriver
import zw.co.unipay.payments.card.CardTransactionType
import zw.co.unipay.payments.card.GuardedCardPaymentDriver
import zw.co.unipay.payments.ui.CardNetwork
import zw.co.unipay.payments.ui.CardPaymentResult

class GuardedCardPaymentDriverTest {

    private class Recording : CardPaymentDriver {
        var payments = 0
        var cancels = 0
        override suspend fun processPayment(
            amount: Long, cardNetwork: CardNetwork, currency: String,
            transactionType: CardTransactionType, onUpdate: ((CardFlowUpdate) -> Unit)?,
        ): CardPaymentResult { payments++; return CardPaymentResult.Cancelled }

        override suspend fun cancel() { cancels++ }
    }

    @Test fun `a met requirement passes the payment through`() = runBlocking {
        val inner = Recording()
        val result = GuardedCardPaymentDriver(inner) { null }.processPayment(1_000)
        assertEquals(CardPaymentResult.Cancelled, result)
        assertEquals(1, inner.payments)
    }

    @Test fun `an unmet requirement refuses and never reaches the driver`() = runBlocking {
        val inner = Recording()
        val result = GuardedCardPaymentDriver(inner) { "no merchant configuration was given" }.processPayment(1_000)
        assertTrue(result is CardPaymentResult.Error)
        assertTrue((result as CardPaymentResult.Error).errorMessage.contains("no merchant configuration"))
        assertEquals(0, inner.payments)
    }

    @Test fun `a requirement that throws refuses instead of crashing`() = runBlocking {
        val inner = Recording()
        val result = GuardedCardPaymentDriver(inner) { throw IllegalStateException("bad config") }.processPayment(1_000)
        assertTrue(result is CardPaymentResult.Error)
        assertEquals(0, inner.payments)
    }

    @Test fun `the requirement is asked afresh for each payment`() = runBlocking {
        val inner = Recording()
        var missing: String? = "no merchant configuration was given"
        val guarded = GuardedCardPaymentDriver(inner) { missing }
        assertTrue(guarded.processPayment(1_000) is CardPaymentResult.Error)
        missing = null
        assertEquals(CardPaymentResult.Cancelled, guarded.processPayment(1_000))
        missing = "gone again"
        assertTrue(guarded.processPayment(1_000) is CardPaymentResult.Error)
        assertEquals(1, inner.payments)
    }

    @Test fun `cancel reaches the driver whatever the requirement says, and never asks it`() = runBlocking {
        val inner = Recording()
        var asked = 0
        val guarded = GuardedCardPaymentDriver(inner) { asked++; "unmet" }
        guarded.cancel()
        assertEquals(1, inner.cancels)
        assertEquals(0, asked)
    }

    @Test fun `cancel reaches the driver even when the requirement would throw`() = runBlocking {
        val inner = Recording()
        GuardedCardPaymentDriver(inner) { throw IllegalStateException("bad config") }.cancel()
        assertEquals(1, inner.cancels)
    }
}
