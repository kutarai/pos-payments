package zw.co.unipay.payments.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import zw.co.unipay.payments.model.Money

class CashChangeTest {

    // 1 USD = 26.00 ZWG, and the inverse. Anything else has no rate.
    private val convert: suspend (Money, String) -> Money? = { money, to ->
        when {
            money.currency == to -> money
            money.currency == "USD" && to == "ZWG" -> Money(money.amount * 26.0, "ZWG")
            money.currency == "ZWG" && to == "USD" -> Money(money.amount / 26.0, "USD")
            else -> null
        }
    }

    private fun compute(total: Money, tendered: Money, changeCurrency: String) =
        runBlocking { CashChange.compute(total, tendered, changeCurrency, convert) }

    private val usd10 = Money(10.0, "USD")

    @Test fun sameCurrencyChange() {
        val c = compute(usd10, Money(20.0, "USD"), "USD")!!
        assertEquals(Money(10.0, "USD"), c.changeInSale)
        assertEquals(Money(10.0, "USD"), c.changeGiven)
        assertFalse(c.changeCurrencyFellBack)
    }

    @Test fun foreignTenderChangeInSaleCurrency() {
        val c = compute(usd10, Money(300.0, "ZWG"), "USD")!!
        assertEquals(Money(300.0, "ZWG"), c.tendered)
        assertEquals(Money(11.54, "USD"), c.tenderedInSale)
        assertEquals(Money(1.54, "USD"), c.changeInSale)
        assertEquals(Money(1.54, "USD"), c.changeGiven)
    }

    @Test fun foreignTenderAndChangeRoundsOnce() {
        val c = compute(usd10, Money(300.0, "ZWG"), "ZWG")!!
        assertEquals(Money(40.0, "ZWG"), c.changeGiven)
        assertEquals(Money(1.54, "USD"), c.changeInSale)
    }

    // CashChange.compute documents that `convert` must not round. A converter that does round
    // to 2 dp (as the till's display conversion does) makes the change 1.54 x 26 = 40.04.
    @Test fun aRoundingConverterRoundsTwiceAndGivesTheWrongChange() {
        val rounding: suspend (Money, String) -> Money? = { money, to ->
            convert(money, to)?.let { Money(Math.round(it.amount * 100.0) / 100.0, it.currency) }
        }
        val c = runBlocking { CashChange.compute(usd10, Money(300.0, "ZWG"), "ZWG", rounding) }!!
        assertEquals(Money(40.04, "ZWG"), c.changeGiven)
    }

    @Test fun saleCurrencyTenderForeignChange() {
        val c = compute(usd10, Money(20.0, "USD"), "ZWG")!!
        assertEquals(Money(260.0, "ZWG"), c.changeGiven)
        assertFalse(c.changeCurrencyFellBack)
    }

    @Test fun missingChangeRateFallsBackToSaleCurrency() {
        val c = compute(usd10, Money(20.0, "USD"), "XYZ")!!
        assertEquals(Money(10.0, "USD"), c.changeGiven)
        assertTrue(c.changeCurrencyFellBack)
    }

    @Test fun exactTenderNoChange() {
        val c = compute(usd10, Money(10.0, "USD"), "ZWG")!!
        assertEquals(0.0, c.changeGiven.amount, 0.0)
        assertEquals(0.0, c.changeInSale.amount, 0.0)
    }

    @Test fun shortTenderGivesNull() {
        assertNull(compute(usd10, Money(9.99, "USD"), "USD"))
    }

    @Test fun unconvertibleTenderGivesNull() {
        assertNull(compute(usd10, Money(500.0, "XYZ"), "USD"))
    }

    @Test fun completedOutcomeCarriesBothChanges() {
        val c = compute(usd10, Money(300.0, "ZWG"), "ZWG")!!
        val outcome = c.toOutcome()
        assertEquals(Money(300.0, "ZWG"), outcome.tenderedAmount)
        assertEquals(Money(1.54, "USD"), outcome.changeAmount)
        assertEquals(Money(40.0, "ZWG"), outcome.changeGiven)
    }

    @Test fun nonFiniteTenderGivesNullWithoutThrowing() {
        assertNull(compute(usd10, Money(Double.POSITIVE_INFINITY, "USD"), "USD"))
    }

    @Test fun nonFiniteTotalGivesNull() {
        assertNull(compute(Money(Double.POSITIVE_INFINITY, "USD"), Money(20.0, "USD"), "USD"))
    }

    @Test fun nonFiniteConvertedAmountGivesNull() {
        val broken: suspend (Money, String) -> Money? = { m, _ -> Money(Double.POSITIVE_INFINITY, m.currency) }
        assertNull(runBlocking { CashChange.compute(usd10, Money(300.0, "ZWG"), "USD", broken) })
    }

    @Test fun nonFiniteChangeConversionFallsBack() {
        val broken: suspend (Money, String) -> Money? = { m, to ->
            if (m.currency == to) m else Money(Double.POSITIVE_INFINITY, to)
        }
        val c = runBlocking { CashChange.compute(usd10, Money(20.0, "USD"), "ZWG", broken) }!!
        assertTrue(c.changeCurrencyFellBack)
        assertEquals(Money(10.0, "USD"), c.changeGiven)
    }
}
