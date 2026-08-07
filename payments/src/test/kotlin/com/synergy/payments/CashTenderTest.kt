package com.synergy.payments

import com.synergy.payments.cash.CashTender
import com.synergy.payments.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taking cash: what is owed, what was handed over, and what goes back.
 *
 * The arithmetic is trivial and the mistakes are not. Change that can go negative reaches a
 * drawer as a demand for money from the till, and a tender a cent short that is accepted anyway
 * is a shortfall nobody discovers until the cashup.
 */
class CashTenderTest {

    private fun usd(amount: Double) = Money(amount, "USD")

    @Test
    fun `change is what is left after the bill`() {
        val tender = CashTender.of(due = usd(11.91), tendered = usd(20.00))

        assertEquals(8.09, tender.change.amount, 0.001)
        assertTrue(tender.isSettled)
    }

    @Test
    fun `the exact amount leaves no change`() {
        val tender = CashTender.of(due = usd(11.91), tendered = usd(11.91))

        assertEquals(0.0, tender.change.amount, 0.001)
        assertTrue(tender.isSettled)
    }

    @Test
    fun `too little is not settled, and asks for the difference`() {
        val tender = CashTender.of(due = usd(11.91), tendered = usd(10.00))

        assertFalse(tender.isSettled)
        assertEquals(1.91, tender.shortfall.amount, 0.001)
        assertEquals(0.0, tender.change.amount, 0.001)
    }

    @Test
    fun `change never runs backwards`() {
        // Money in this system cannot be negative, and a till cannot hand back less than nothing.
        val tender = CashTender.of(due = usd(50.00), tendered = usd(5.00))

        assertEquals(0.0, tender.change.amount, 0.001)
    }

    @Test
    fun `change comes back in the currency it was tendered in`() {
        // A customer paying in rand gets rand back, whatever the sale was priced in.
        val tender = CashTender.of(due = Money(100.0, "ZAR"), tendered = Money(200.0, "ZAR"))

        assertEquals("ZAR", tender.change.currency)
    }

    @Test
    fun `a tender in the wrong currency is refused rather than quietly converted`() {
        // Converting here would use a rate nobody chose. The caller converts first, deliberately.
        val failure = runCatching { CashTender.of(due = usd(10.0), tendered = Money(10.0, "ZAR")) }

        assertTrue(failure.isFailure)
    }

    @Test
    fun `rounding does not invent a cent`() {
        val tender = CashTender.of(due = usd(0.10 + 0.20), tendered = usd(0.30))

        assertEquals(0.0, tender.change.amount, 0.0001)
        assertTrue("0.1 + 0.2 must still count as paid", tender.isSettled)
    }
}
