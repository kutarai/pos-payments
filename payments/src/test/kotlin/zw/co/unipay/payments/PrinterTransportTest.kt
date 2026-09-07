package zw.co.unipay.payments

import zw.co.unipay.payments.printing.PrinterTransport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a stored transport is read back.
 *
 * Every till in the estate was set up before there was a choice, so its preferences hold no
 * transport at all. Reading that as anything but Bluetooth would stop those tills printing —
 * silently, since a receipt that does not come out is only noticed at the counter.
 */
class PrinterTransportTest {

    @Test
    fun `a till set up before there was a choice still prints over Bluetooth`() {
        assertEquals(PrinterTransport.BLUETOOTH, PrinterTransport.named(null))
    }

    @Test
    fun `a value this build does not know means Bluetooth rather than nothing`() {
        // What a rollback looks like: a newer build stored a transport this one has never heard
        // of. Falling back to the printer every terminal has beats refusing to print.
        assertEquals(PrinterTransport.BLUETOOTH, PrinterTransport.named("USB"))
        assertEquals(PrinterTransport.BLUETOOTH, PrinterTransport.named(""))
    }

    @Test
    fun `a stored transport is read back as itself`() {
        PrinterTransport.values().forEach { transport ->
            assertEquals(transport, PrinterTransport.named(transport.name))
        }
    }
}
