package zw.co.unipay.payments.card

import org.junit.Assert.assertEquals
import org.junit.Test
import zw.co.unipay.payments.model.CardType

/**
 * The card brand printed on a receipt.
 *
 * Nothing about an authorisation turns on this, which is exactly why it existed twice — once in
 * each make of terminal's own driver — until the second copy was written. A BIN table that
 * differed between makes would put a different brand on two receipts for the same card at the
 * same counter, and neither receipt would say which was right.
 *
 * So it is one function now, and these are the prefixes it reads. They are asserted rather than
 * trusted because a `when` whose branches are in the wrong order silently reassigns a whole
 * range: `2` is Mastercard's second series and `34`/`37` are American Express, so a `3` branch
 * placed above them would take every Amex card.
 */
class CardSchemeTest {

    @Test
    fun `each scheme is named from the digits it is issued under`() {
        assertEquals(CardType.VISA, cardSchemeOf("4761739001010119"))
        assertEquals(CardType.MASTERCARD, cardSchemeOf("5187108106590784"))
        // Mastercard's 2-series, issued since 2017 and the one a stale table misses.
        assertEquals(CardType.MASTERCARD, cardSchemeOf("2221000000000009"))
        assertEquals(CardType.AMEX, cardSchemeOf("340000000000009"))
        assertEquals(CardType.AMEX, cardSchemeOf("370000000000002"))
        assertEquals(CardType.DISCOVER, cardSchemeOf("6011000000000004"))
        assertEquals(CardType.DISCOVER, cardSchemeOf("6500000000000002"))
        assertEquals(CardType.UNIONPAY, cardSchemeOf("6200000000000005"))
        assertEquals(CardType.JCB, cardSchemeOf("3530111333300000"))
        assertEquals(CardType.DINERS, cardSchemeOf("36000000000008"))
        assertEquals(CardType.DINERS, cardSchemeOf("38000000000006"))
    }

    @Test
    fun `a card number with no scheme this estate knows is other, and so is none at all`() {
        // Never a throw and never a guess: an unknown brand prints as OTHER, which is honest.
        assertEquals(CardType.OTHER, cardSchemeOf("7000000000000000"))
        assertEquals(CardType.OTHER, cardSchemeOf("1"))
        assertEquals(CardType.OTHER, cardSchemeOf(""))
    }

    @Test
    fun `the leading digits are enough, which is what a caller holding a BIN has`() {
        assertEquals(CardType.VISA, cardSchemeOf("4"))
        assertEquals(CardType.AMEX, cardSchemeOf("37"))
        assertEquals(CardType.DISCOVER, cardSchemeOf("6011"))
    }
}
