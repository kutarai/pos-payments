package zw.co.unipay.payments

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import zw.co.unipay.payments.terminal.PinEncryptionScheme
import zw.co.unipay.payments.terminal.PinEncryptionScheme.DUKPT
import zw.co.unipay.payments.terminal.PinEncryptionScheme.MKSK

/**
 * Which PIN scheme the Device Owner asked for, and what a terminal does when they did not say.
 *
 * The default belongs to the caller, because it belongs to the make: the Ciontek terminals in the
 * field are all DUKPT, the ZCS ones are MKSK. A default hard-coded here would silently put one
 * make on the other's scheme, which is a well-formed, undecryptable PIN block.
 */
class PinEncryptionSchemeTest {

    /** A context whose managed configuration says [policy], or says nothing when it is null. */
    private fun contextWithPolicy(policy: String?): Context {
        val bundle = mock(Bundle::class.java)
        Mockito.`when`(bundle.getString(PinEncryptionScheme.POLICY_KEY)).thenReturn(policy)
        val restrictions = mock(RestrictionsManager::class.java)
        Mockito.`when`(restrictions.applicationRestrictions).thenReturn(bundle)
        val context = mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.getSystemService(Context.RESTRICTIONS_SERVICE)).thenReturn(restrictions)
        return context
    }

    @Test
    fun `an absent policy yields the caller's default, not DUKPT`() {
        assertEquals(MKSK, PinEncryptionScheme.fromPolicy(contextWithPolicy(null), default = MKSK))
    }

    @Test
    fun `an unrecognised policy value yields the caller's default`() {
        assertEquals(MKSK, PinEncryptionScheme.fromPolicy(contextWithPolicy("RUBBISH"), default = MKSK))
    }

    @Test
    fun `MKSK MK_SK and MS_TDESKSK all parse as MKSK`() {
        for (value in listOf("MKSK", "MK_SK", "MS_TDESKSK")) {
            assertEquals(value, MKSK, PinEncryptionScheme.fromPolicy(contextWithPolicy(value), default = DUKPT))
        }
    }

    @Test
    fun `DUKPT parses as DUKPT whatever the caller's default`() {
        assertEquals(DUKPT, PinEncryptionScheme.fromPolicy(contextWithPolicy("DUKPT"), default = MKSK))
    }
}
