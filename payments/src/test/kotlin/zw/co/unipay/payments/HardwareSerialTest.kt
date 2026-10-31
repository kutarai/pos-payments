package zw.co.unipay.payments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import zw.co.unipay.payments.terminal.HardwareSerial

/**
 * The serial number is how a terminal identifies itself when activating, so a placeholder
 * that merely looks like a serial is worse than none at all: it activates against the wrong
 * device record, or against none, and the operator has nothing to compare with the serial
 * printed on the unit.
 */
class HardwareSerialTest {

    @Test
    fun `accepts a vendor serial`() {
        assertTrue(HardwareSerial.isPlausible("A50-42GB-4F00315", model = "CS20"))
        assertTrue(HardwareSerial.isPlausible("80042517060004", model = "CS20"))
    }

    @Test
    fun `the Z108's serial is plausible`() {
        assertTrue(HardwareSerial.isPlausible("0520842010378191", "Z108"))
    }

    @Test
    fun `rejects a missing serial`() {
        assertFalse(HardwareSerial.isPlausible(null, model = "CS20"))
        assertFalse(HardwareSerial.isPlausible("", model = "CS20"))
        assertFalse(HardwareSerial.isPlausible("   ", model = "CS20"))
    }

    @Test
    fun `rejects placeholders that stand in for a serial`() {
        // Build.getSerial() answers "unknown" rather than failing when it is not permitted.
        assertFalse(HardwareSerial.isPlausible("unknown", model = "CS20"))
        assertFalse(HardwareSerial.isPlausible("UNKNOWN", model = "CS20"))
        assertFalse(HardwareSerial.isPlausible("UNKNOWN_SN", model = "CS20"))
        assertFalse(HardwareSerial.isPlausible("null", model = "CS20"))
        assertFalse(HardwareSerial.isPlausible("n/a", model = "CS20"))
        assertFalse(HardwareSerial.isPlausible("000000000000", model = "CS20"))
    }

    @Test
    fun `rejects the model name`() {
        // SysgetDeviceId hands back the model on units that have no programmed serial. Every
        // terminal of that model would then claim the same identity.
        assertFalse(HardwareSerial.isPlausible("CS20", model = "CS20"))
        assertFalse(HardwareSerial.isPlausible("cs20", model = "CS20"))
    }

    @Test
    fun `rejects a value too short to be a serial`() {
        assertFalse(HardwareSerial.isPlausible("12", model = "CS20"))
    }

    @Test
    fun `takes the first plausible candidate in order`() {
        val serial = HardwareSerial.firstPlausible(
            listOf(null, "", "unknown", "A50-42GB-4F00315", "3e88073a"),
            model = "CS20"
        )

        assertEquals("A50-42GB-4F00315", serial)
    }

    @Test
    fun `trims surrounding whitespace and NUL padding`() {
        // SpReadSN fills a fixed-size buffer, so the value arrives padded.
        assertEquals(
            "A50-42GB-4F00315",
            HardwareSerial.firstPlausible(listOf(" A50-42GB-4F00315\u0000\u0000 "), model = "CS20")
        )
    }

    @Test
    fun `answers nothing when no candidate is usable`() {
        assertNull(HardwareSerial.firstPlausible(listOf(null, "", "unknown", "CS20"), model = "CS20"))
    }
}
