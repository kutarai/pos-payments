package zw.co.unipay.payments.ui

import java.math.BigDecimal
import java.math.RoundingMode
import zw.co.unipay.payments.model.Money

/**
 * What a cash tender comes to: the change, in the sale's currency and in the currency the
 * cashier hands it back in.
 *
 * One place does this arithmetic so the dialog's figures and the recorded outcome cannot
 * disagree, and so a tender in one currency with change in another is never subtracted
 * across currencies.
 */
data class CashChange(
    /** As handed over, in its own currency. */
    val tendered: Money,
    /** [tendered] converted into the sale's currency, 2 dp. */
    val tenderedInSale: Money,
    /** [tenderedInSale] less the total, in the sale's currency, 2 dp, never negative. */
    val changeInSale: Money,
    /** The change handed back, in the currency it is given in, 2 dp. */
    val changeGiven: Money,
    /**
     * True when there was no rate for the chosen change currency, so [changeGiven] is in the
     * sale's.
     */
    val changeCurrencyFellBack: Boolean,
) {
    companion object {
        /**
         * Null when the tender cannot be converted into the sale's currency, or is short.
         *
         * The tender is converted unrounded, the change is worked out from that, and the change
         * is converted unrounded again; each reported value is rounded half-up, once.
         * [convert] must therefore not round.
         */
        suspend fun compute(
            total: Money,
            tendered: Money,
            changeCurrency: String,
            convert: suspend (Money, String) -> Money?,
        ): CashChange? {
            if (!total.amount.isFinite() || !tendered.amount.isFinite()) return null
            val saleCurrency = total.currency
            val tenderedRaw = convertOrNull(tendered, saleCurrency, convert) ?: return null
            val tenderedInSale = tenderedRaw.round()
            val totalRounded = total.amount.toBigDecimal().round()
            if (tenderedInSale < totalRounded) return null

            val changeRaw = (tenderedRaw - total.amount.toBigDecimal()).max(BigDecimal.ZERO)
            val changeInSale = Money(changeRaw.round().toDouble(), saleCurrency)

            val givenRaw = convertOrNull(
                Money(changeRaw.toDouble(), saleCurrency),
                changeCurrency,
                convert,
            )
            val fellBack = givenRaw == null
            val changeGiven = Money(
                (givenRaw ?: changeRaw).round().toDouble(),
                if (fellBack) saleCurrency else changeCurrency,
            )

            return CashChange(
                tendered = tendered,
                tenderedInSale = Money(tenderedInSale.toDouble(), saleCurrency),
                changeInSale = changeInSale,
                changeGiven = changeGiven,
                changeCurrencyFellBack = fellBack,
            )
        }

        private suspend fun convertOrNull(
            money: Money,
            to: String,
            convert: suspend (Money, String) -> Money?,
        ): BigDecimal? =
            if (money.currency == to) money.amount.toBigDecimal()
            else convert(money, to)?.amount?.takeIf { it.isFinite() }?.toBigDecimal()

        private fun BigDecimal.round(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

        private fun Double.toBigDecimal(): BigDecimal = BigDecimal.valueOf(this)
    }
}

/** The outcome the flow reports for a completed cash sale. */
internal fun CashChange.toOutcome(): PaymentOutcome.CashCompleted =
    PaymentOutcome.CashCompleted(
        tenderedAmount = tendered,
        changeAmount = changeInSale,
        changeGiven = changeGiven,
    )
