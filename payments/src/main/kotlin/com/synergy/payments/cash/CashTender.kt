package com.synergy.payments.cash

import com.synergy.payments.model.Money
import kotlin.math.abs

/**
 * Cash handed over against an amount due.
 *
 * Small enough to look obvious and worth having in one place anyway: change that can go negative
 * reaches a drawer as a demand for money from the till, and a tender a cent short that is
 * accepted anyway is a shortfall nobody finds until the cashup.
 */
data class CashTender(
    val due: Money,
    val tendered: Money,
    val change: Money,
    val shortfall: Money
) {
    val isSettled: Boolean get() = shortfall.amount <= 0.0

    companion object {
        /**
         * A cent's tolerance, because these amounts arrive as doubles and 0.10 + 0.20 is not
         * exactly 0.30 on any machine. Without it a customer paying the displayed total to the
         * cent is told they are short.
         */
        private const val TOLERANCE = 0.005

        fun of(due: Money, tendered: Money): CashTender {
            require(due.currency == tendered.currency) {
                "Cash tendered in ${tendered.currency} against a bill in ${due.currency}: " +
                    "convert it deliberately, at a rate somebody chose, before tendering."
            }

            val difference = tendered.amount - due.amount
            val settled = difference > -TOLERANCE

            return CashTender(
                due = due,
                tendered = tendered,
                // Change is paid in what was handed over, whatever the sale was priced in.
                change = Money(if (settled && difference > TOLERANCE) difference else 0.0, tendered.currency),
                shortfall = Money(if (settled) 0.0 else abs(difference), due.currency)
            )
        }
    }
}
