package zw.co.unipay.payments.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a cashier reads when a card is not approved.
 *
 * The switch is the only party that knows why — "Insufficient funds", "Invalid PIN". The EMV
 * kernel knows only which step stopped, and says so in vendor terms ("Online Decline", code 6).
 * A terminal that shows the second when it holds the first is telling the person at the till
 * nothing they can act on, and the customer nothing they can fix.
 */
class DeclineMessageTest {

    @Test
    fun `the bank's reason is what the cashier sees`() {
        assertEquals(
            "Transaction declined by bank",
            declineMessage(
                code = 6, desc = "Online Decline", fallbackLabel = "Transaction declined",
                switchUnavailable = false, switchDisplayMessage = "Transaction declined by bank",
            ),
        )
    }

    @Test
    fun `a PIN decline says the customer may try again`() {
        assertEquals(
            "Incorrect PIN. Try again with the correct PIN.",
            declineMessage(
                code = 6, desc = "Online Decline", fallbackLabel = "Transaction declined",
                switchUnavailable = false, switchDisplayMessage = "Invalid PIN",
            ),
        )
    }

    @Test
    fun `with nothing from the switch, the kernel's own words and code stand`() {
        assertEquals(
            "Transaction declined: Online Decline (code=6)",
            declineMessage(
                code = 6, desc = "Online Decline", fallbackLabel = "Transaction declined",
                switchUnavailable = false, switchDisplayMessage = null,
            ),
        )
    }

    @Test
    fun `an unreachable switch reports the transport, not the card`() {
        assertEquals(
            "Bank offline — try again",
            declineMessage(
                code = 6, desc = "Online Decline", fallbackLabel = "Transaction declined",
                switchUnavailable = true, switchDisplayMessage = "Bank offline — try again",
            ),
        )
        assertEquals(
            "Bank offline",
            declineMessage(
                code = 6, desc = "Online Decline", fallbackLabel = "Transaction declined",
                switchUnavailable = true, switchDisplayMessage = null,
            ),
        )
    }

    @Test
    fun `a kernel failure that is not a decline keeps its own label`() {
        assertEquals(
            "Transaction failed: Card blocked (code=-50024)",
            declineMessage(
                code = -50024, desc = "Card blocked", fallbackLabel = "Transaction failed",
                switchUnavailable = false, switchDisplayMessage = null,
            ),
        )
    }

    @Test
    fun `an unknown kernel description still carries the code`() {
        assertEquals(
            "Transaction declined: Unknown (code=2)",
            declineMessage(
                code = 2, desc = null, fallbackLabel = "Transaction declined",
                switchUnavailable = false, switchDisplayMessage = null,
            ),
        )
    }

    // ----- the bank said yes and the card did not finish ----------------------------------
    //
    // The kernel can refuse to complete after the switch has approved — its
    // importOnlineProcStatus rejects the issuer data and the transaction ends "Online
    // Decline". The switch's own words were then shown as the failure, so the till read
    // "Payment Error: Approved", which is nonsense to whoever is holding the card.
    //
    // Worse than nonsense: an authorisation is standing at the issuer. Inviting a retry takes
    // a second hold on the customer's account for a sale they are being told did not happen,
    // and there is no reversal anywhere in this codebase to release the first.

    @Test
    fun `an approval the card did not complete is not reported as an approval`() {
        val msg = declineMessage(
            code = 6, desc = "Online Decline", fallbackLabel = "Transaction declined",
            switchUnavailable = false, switchDisplayMessage = "Approved",
            switchApproved = true,
        )
        assertTrue("must not read as an approval: was \"$msg\"",
            !msg.trim().equals("Approved", ignoreCase = true))
        assertTrue("must say the bank approved", msg.contains("bank", ignoreCase = true))
        assertTrue("must warn against taking payment again", msg.contains("again", ignoreCase = true))
    }

    @Test
    fun `a real decline still shows the bank's reason`() {
        // Unchanged: when the switch declined, its reason is the useful thing to show.
        assertEquals(
            "Insufficient funds",
            declineMessage(
                code = 6, desc = "Online Decline", fallbackLabel = "Transaction declined",
                switchUnavailable = false, switchDisplayMessage = "Insufficient funds",
                switchApproved = false,
            ),
        )
    }

    @Test
    fun `an unreachable switch is still about the line, not the card`() {
        assertEquals(
            "Bank offline",
            declineMessage(
                code = 6, desc = "Online Decline", fallbackLabel = "Transaction declined",
                switchUnavailable = true, switchDisplayMessage = "Bank offline",
                switchApproved = false,
            ),
        )
    }
}

