package zw.co.unipay.payments.card

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a sale is a sale, when the kernel and the bank disagree.
 *
 * The kernel can refuse to complete a transaction the issuer has already authorised: its
 * `importOnlineProcStatus` rejects the issuer data — the switch protocol carries no field for
 * the issuer's response, so a lone tag 8A is synthesised and the kernel wants more — and the
 * transaction ends "Online Decline" with code 6.
 *
 * Until now the terminal followed the kernel and refused the sale. That is the worst of the
 * available outcomes: the issuer is holding an authorisation, the customer has been charged,
 * the council has no receipt and no money, and the till invites a retry that takes a second
 * hold. Following the BANK instead collects money that has already been authorised.
 *
 * What is given up by doing so, stated plainly: the card never performed its second GENERATE
 * AC, so there is no Transaction Certificate for this payment. The authorisation stands on its
 * own. That is a deliberate decision of the owner's, taken because the alternative charges
 * customers for sales that never complete, and it is recorded here so nobody has to guess later
 * whether it was intended.
 */
class TransactionApprovedTest {

    @Test
    fun `the kernel's own approvals are approvals`() {
        for (code in listOf(0, 1, 5)) {
            assertTrue("kernel code $code is an approval", transactionApproved(code, switchApproved = false))
        }
    }

    @Test
    fun `a bank approval the card did not complete is still a payment`() {
        // The -4000 case: switch APPR, kernel "Online Decline" (6).
        assertTrue(transactionApproved(kernelCode = 6, switchApproved = true))
        assertTrue(transactionApproved(kernelCode = 2, switchApproved = true))
    }

    @Test
    fun `a decline the bank agreed with stays declined`() {
        // No authorisation exists, so there is nothing to collect and nothing to reconcile.
        assertFalse(transactionApproved(kernelCode = 6, switchApproved = false))
        assertFalse(transactionApproved(kernelCode = 2, switchApproved = false))
    }

    @Test
    fun `an offline decline is not rescued by an approval that never happened`() {
        assertFalse(transactionApproved(kernelCode = -4000, switchApproved = false))
    }

    @Test
    fun `a card removed or a reader fault is not a payment, whatever the bank said`() {
        // A bank approval cannot make a transaction out of a card that was never read to the
        // end for a hardware reason — those codes mean the terminal, not the card, gave up.
        assertFalse(transactionApproved(kernelCode = -1, switchApproved = true))
        assertFalse(transactionApproved(kernelCode = -2, switchApproved = true))
    }
}
