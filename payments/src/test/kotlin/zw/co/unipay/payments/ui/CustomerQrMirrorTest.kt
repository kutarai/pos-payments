package zw.co.unipay.payments.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import zw.co.unipay.payments.terminal.CustomerDisplay

class CustomerQrMirrorTest {

    private class Fake(var accepts: Boolean = true, var throws: Boolean = false) : CustomerDisplay {
        val calls = mutableListOf<String>()
        override suspend fun showQr(payload: String, caption: List<String>): Boolean {
            if (throws) error("screen gone")
            calls += "show:$payload:${caption.joinToString("|")}"
            return accepts
        }
        override suspend fun clear() { calls += "clear" }
    }

    @Test fun `a code goes up and comes down when the cashier's screen stops showing it`() = runBlocking {
        val screen = Fake()
        val mirror = CustomerQrMirror { screen }
        mirror.show("P1", listOf("Scan to pay", "USD 1.00"))
        mirror.show(null, emptyList())
        assertEquals(listOf("show:P1:Scan to pay|USD 1.00", "clear"), screen.calls)
    }

    @Test fun `nothing is cleared when nothing was shown`() = runBlocking {
        val screen = Fake()
        CustomerQrMirror { screen }.show(null, emptyList())
        assertEquals(emptyList<String>(), screen.calls)
    }

    @Test fun `a terminal without a customer screen is left alone`() = runBlocking {
        val mirror = CustomerQrMirror { null }
        mirror.show("P1", emptyList())
        mirror.show(null, emptyList())
    }

    @Test fun `a screen that refused or failed the image is still cleared`() = runBlocking {
        val refused = Fake(accepts = false)
        CustomerQrMirror { refused }.apply { show("P1", emptyList()); show(null, emptyList()) }
        assertEquals("clear", refused.calls.last())

        val broken = Fake(throws = true)
        val mirror = CustomerQrMirror { broken }
        mirror.show("P1", emptyList())   // must not throw into the payment screen
        broken.throws = false
        mirror.show(null, emptyList())
        assertEquals(listOf("clear"), broken.calls)
    }

    @Test fun `only one clear is sent however many times the code is taken down`() = runBlocking {
        val screen = Fake()
        val mirror = CustomerQrMirror { screen }
        mirror.show("P1", emptyList())
        mirror.show(null, emptyList())
        mirror.show(null, emptyList())
        assertEquals(1, screen.calls.count { it == "clear" })
    }
}
