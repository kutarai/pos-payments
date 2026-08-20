package com.synergy.payments.model

/**
 * A currency a customer can hand over.
 *
 * Named for the tender rather than just "Currency" because applications have
 * their own Currency, and a screen that wildcard-imports both this package and
 * its own model gets an ambiguous name it did nothing to deserve.
 *
 * Cash in Zimbabwe is routinely tendered in a currency other than the one a bill
 * is denominated in, so the cash step needs the list of what a counter accepts
 * and a way to convert — the amount due stays in the bill's currency and only
 * the tender changes.
 */
data class TenderCurrency(
    val code: String,
    val name: String,
    val symbol: String,
    val isActive: Boolean = true,
)
