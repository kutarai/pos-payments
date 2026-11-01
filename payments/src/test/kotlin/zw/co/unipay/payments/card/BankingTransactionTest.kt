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

    // ----- the cash warning, which is the branch both makes must reach --------------------
    //
    // These four pin the `switchApproved` branch of [declineMessage] itself, not either
    // terminal's route to it. **That branch has twice become unreachable on the ZCS terminal
    // while staying reachable on the CS20**, by two different routes a round apart: first by
    // reading the kernel's ending as "the bank approved, so it is a sale" for every transaction
    // type, and then by letting a cancel that landed after the approval report "nothing has been
    // charged". Both times the same card, broken off or cancelled at the same moment, was a
    // refusal carrying this warning on one make and a Success that opened the drawer on the
    // other — which is exactly what this file's own header says must not happen.
    //
    // The parity rested on each terminal library's own tests, so nothing here failed when they
    // diverged. It does now: deleting or weakening this branch breaks a test in the module that
    // owns the rule.

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

    /** The account may already be credited, so retrying would credit it twice. */
    @Test
    fun `a deposit the bank approved is not retried and the cash waits on the bank`() {
        val message = declineMessage(
            code = 6, desc = null, fallbackLabel = "Transaction declined",
            switchUnavailable = false, switchDisplayMessage = "Approved",
            switchApproved = true, transactionType = CardTransactionType.CASH_DEPOSIT,
        )
        assertTrue(message, message.contains("deposit"))
        assertTrue(message, message.contains("do not retry"))
        assertTrue(message, message.contains("before returning the cash"))
    }

    @Test
    fun `an enquiry the bank answered and the card did not finish is simply retried`() {
        // Nothing moved, so this is the one of the four that may safely be tried again.
        val message = declineMessage(
            code = 6, desc = null, fallbackLabel = "Transaction declined",
            switchUnavailable = false, switchDisplayMessage = "Approved",
            switchApproved = true, transactionType = CardTransactionType.BALANCE_ENQUIRY,
        )
        assertTrue(message, message.contains("Try again"))
        assertFalse(message, message.contains("do not retry"))
    }

    @Test
    fun `no transaction type reports a standing authorisation as nothing having happened`() {
        // The property that matters across all four, stated once: when the bank has approved and
        // the card has not finished, an authorisation is standing at the issuer and the message
        // must say so. A cashier told "cancelled" or "nothing was charged" has no reason to
        // check, and on a withdrawal that is the customer's money gone with the till believing
        // otherwise.
        for (type in CardTransactionType.entries) {
            val message = declineMessage(
                code = 6, desc = null, fallbackLabel = "Transaction declined",
                switchUnavailable = false, switchDisplayMessage = "Approved",
                switchApproved = true, transactionType = type,
            )
            assertTrue("$type: must say the bank answered", message.contains("bank", ignoreCase = true))
            assertFalse("$type: must not read as an approval", message.trim() == "Approved")
            assertFalse("$type: must not claim nothing happened",
                message.contains("Nothing has been charged", ignoreCase = true))
            assertFalse("$type: must not read as a cancellation",
                message.contains("cancelled", ignoreCase = true))
        }
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
