package com.synergy.payments.model

/**
 * A currency a customer can hand over.
 *
 * Cash in Zimbabwe is routinely tendered in a currency other than the one a bill
 * is denominated in, so the cash step needs the list of what a counter accepts
 * and a way to convert — the amount due stays in the bill's currency and only
 * the tender changes.
 */
data class Currency(
    val code: String,
    val name: String,
    val symbol: String,
    val isActive: Boolean = true,
)
