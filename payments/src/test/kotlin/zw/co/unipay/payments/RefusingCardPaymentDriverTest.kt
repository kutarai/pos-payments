package zw.co.unipay.payments

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import zw.co.unipay.payments.card.CardTransactionType
import zw.co.unipay.payments.card.RefusingCardPaymentDriver
import zw.co.unipay.payments.ui.CardPaymentResult

class RefusingCardPaymentDriverTest {

    private val driver = RefusingCardPaymentDriver("no switch is configured")

    @Test fun `a purchase is refused, never approved`() = runBlocking {
        val result = driver.processPayment(amount = 1_000)
        assertTrue(result is CardPaymentResult.Error)
        assertFalse(result is CardPaymentResult.Success)
    }

    @Test fun `every transaction type is refused, banking included`() = runBlocking {
        for (type in CardTransactionType.values()) {
            val result = driver.processPayment(amount = 1_000, transactionType = type)
            assertTrue("$type must be refused", result is CardPaymentResult.Error)
        }
    }

    @Test fun `the refusal says what is missing`() = runBlocking {
        val message = (driver.processPayment(1_000) as CardPaymentResult.Error).errorMessage
        assertTrue(message, message.contains("no switch is configured"))
    }

    @Test fun `the message stays out from under the screen's network headings`() = runBlocking {
        // The card screen routes an error on these phrases; a configuration fault must not be
        // shown as a network one.
        val message = (driver.processPayment(1_000) as CardPaymentResult.Error).errorMessage
        for (word in listOf("bank offline", "unavailable", "did not respond", "deadline_exceeded", "timeout",
            "unable to go online", "no card detected")) {
            assertFalse("contains '$word'", message.contains(word, ignoreCase = true))
        }
    }

    @Test fun `no progress is reported because no card is read`() = runBlocking {
        val seen = mutableListOf<Any>()
        driver.processPayment(1_000, onUpdate = { seen += it })
        assertEquals(emptyList<Any>(), seen)
    }

    @Test fun `cancel with nothing in progress is harmless`() = runBlocking {
        driver.cancel()
        driver.cancel()
    }
}
