package zw.co.unipay.payments.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Withdrawal, deposit and balance enquiry, on any make of terminal.
 *
 * Banking at the counter differs from a purchase in three ways the terminal enforces: the card
 * is told what kind of transaction it is signing, the cardholder proves themselves with a PIN,
 * and only the bank can approve — never the card alone.
 *
 * These rules came from the CS20's Ciontek processor and moved here, with the helpers they
 * cover, when the ZCS terminal arrived: a withdrawal must not need a PIN on one make of
 * hardware and not the other.
 */
class BankingTransactionTest {

    // ----- what the card signs -------------------------------------------------------------

    @Test
    fun `the card signs the transaction type the bank is told`() {
        for (type in CardTransactionType.entries) {
            val (tags, values) = emvTransactionTlv(2_000L, "0840", 1L, type.emvCode)
            assertEquals("9C for $type", type.emvCode, tags.zip(values).toMap()["9C"])
        }
    }

    @Test
    fun `a caller that says nothing still signs a purchase`() {
        val (tags, values) = emvTransactionTlv(2_000L, "0840", 1L)
        assertEquals("00", tags.zip(values).toMap()["9C"])
    }

    // ----- the PIN -------------------------------------------------------------------------

    @Test
    fun `an online PIN block is a PIN`() {
        assertTrue(pinVerified(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), cvmResults = "1F0000"))
    }

    /** Methods 01, 03, 04 and 05 are the card checking the PIN itself; result 02 is "it was right". */
    @Test
    fun `a PIN the card verified offline is a PIN`() {
        for (method in listOf("01", "03", "04", "05", "41", "44")) {
            assertTrue("9F34 method $method", pinVerified(null, "${method}0302"))
        }
    }

    @Test
    fun `an offline PIN the card did not accept is not`() {
        assertFalse(pinVerified(null, "010301"))   // failed
        assertFalse(pinVerified(null, "010300"))   // unknown
    }

    /** Online PIN with no block to send means the keypad captured nothing. */
    @Test
    fun `online PIN claimed with no block is not a PIN`() {
        assertFalse(pinVerified(null, "020000"))
    }

    @Test
    fun `no verification, a signature or nothing at all is not a PIN`() {
        assertFalse(pinVerified(null, "1F0302"))   // no CVM
        assertFalse(pinVerified(null, "1E0302"))   // signature
        assertFalse(pinVerified(null, null))
        assertFalse(pinVerified(byteArrayOf(), "1F0000"))
        assertFalse(pinVerified(null, "01"))
    }

    // ----- who approves ---------------------------------------------------------------------

    /**
     * The kernel approving offline is fine for a small purchase. For a withdrawal it is cash out
     * of the drawer with no account debited for it.
     */
    @Test
    fun `banking is never approved by the card alone`() {
        for (code in listOf(0, 1, 5)) {
            assertFalse("kernel code $code", transactionApproved(code, switchApproved = false, requiresBank = true))
        }
    }

    @Test
    fun `banking the bank approved is approved`() {
        assertTrue(transactionApproved(0, switchApproved = true, requiresBank = true))
        // And on the same terms as a purchase when the kernel will not finish: the bank's word.
        assertTrue(transactionApproved(6, switchApproved = true, requiresBank = true))
    }

    @Test
    fun `a hardware failure is still a failure`() {
        assertFalse(transactionApproved(-1, switchApproved = true, requiresBank = true))
    }

    // ----- what the agent is told when the card does not finish ----------------------------

    /** The account has been debited, so retrying would debit it again. */
    @Test
    fun `a withdrawal the bank approved is not retried and the cash waits on the bank`() {
        val message = declineMessage(
            code = 6, desc = null, fallbackLabel = "Transaction declined",
            switchUnavailable = false, switchDisplayMessage = "Approved",
            switchApproved = true, transactionType = CardTransactionType.CASH_WITHDRAWAL,
        )
        assertTrue(message, message.contains("withdrawal"))
        assertTrue(message, message.contains("do not retry"))
        assertTrue(message, message.contains("before giving out cash"))
    }

    @Test
    fun `a purchase keeps the message it had`() {
        val message = declineMessage(
            code = 6, desc = null, fallbackLabel = "Transaction declined",
            switchUnavailable = false, switchDisplayMessage = "Approved", switchApproved = true,
        )
        assertTrue(message, message.startsWith("The bank approved this payment"))
    }

    /** A customer who was never asked for a PIN must not be told they typed it wrongly. */
    @Test
    fun `the terminal's own refusal is shown as it was written`() {
        val refusal = "Cash Withdrawal needs the customer's PIN, and this card did not ask for one."
        assertEquals(
            refusal,
            declineMessage(
                code = 2, desc = null, fallbackLabel = "Transaction declined",
                switchUnavailable = false, switchDisplayMessage = null,
                transactionType = CardTransactionType.CASH_WITHDRAWAL, localRefusal = refusal,
            ),
        )
    }
}
