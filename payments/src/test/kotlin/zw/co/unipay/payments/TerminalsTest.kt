package zw.co.unipay.payments

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import zw.co.unipay.payments.terminal.Terminal
import zw.co.unipay.payments.terminal.Terminals

/**
 * The registry an application uses to say which terminal it is running on.
 *
 * Terminals are mocked rather than faked: the registry only hands back what its factory built, so
 * identity is all these tests need to observe.
 */
class TerminalsTest {

    private val context: Context = mock(Context::class.java)

    @After
    fun tearDown() = Terminals.reset()

    @Test
    fun `create throws with a message naming registration when nothing is registered`() {
        val thrown = assertThrows(IllegalStateException::class.java) { Terminals.create(context) }
        assertTrue(thrown.message!!.contains("Terminals.register"))
    }

    @Test
    fun `isRegistered is false before registration and true after`() {
        assertFalse(Terminals.isRegistered())
        Terminals.register { mock(Terminal::class.java) }
        assertTrue(Terminals.isRegistered())
    }

    @Test
    fun `create returns what the registered factory built`() {
        val built = mock(Terminal::class.java)
        Terminals.register { built }
        assertSame(built, Terminals.create(context))
    }

    @Test
    fun `a second register replaces the first`() {
        val first = mock(Terminal::class.java)
        val second = mock(Terminal::class.java)
        Terminals.register { first }
        Terminals.register { second }
        assertSame(second, Terminals.create(context))
    }

    @Test
    fun `reset clears the registration`() {
        Terminals.register { mock(Terminal::class.java) }
        Terminals.reset()
        assertFalse(Terminals.isRegistered())
    }
}
