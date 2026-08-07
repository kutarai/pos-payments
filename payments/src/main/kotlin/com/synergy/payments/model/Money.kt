package com.synergy.payments.model

import kotlinx.serialization.Serializable

@Serializable
data class Money(
    val amount: Double,
    val currency: String = "ZWG"
) : Comparable<Money> {
    init {
        require(amount >= 0) { "Amount cannot be negative" }
        require(currency.isNotBlank()) { "Currency cannot be blank" }
    }
    
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Cannot add different currencies" }
        return Money(amount + other.amount, currency)
    }
    
    operator fun minus(other: Money): Money {
        require(currency == other.currency) { "Cannot subtract different currencies" }
        return Money(amount - other.amount, currency)
    }
    
    operator fun times(factor: Double): Money {
        return Money(amount * factor, currency)
    }
    
    operator fun times(quantity: Int): Money {
        return Money(amount * quantity, currency)
    }
    
    operator fun div(factor: Double): Money {
        require(factor != 0.0) { "Cannot divide by zero" }
        return Money(amount / factor, currency)
    }
    
    fun format(): String {
        return String.format("%s %.2f", currency, amount)
    }
    
    val cents: Long
        get() = (amount * 100).toLong()
    
    override fun compareTo(other: Money): Int {
        require(currency == other.currency) { "Cannot compare different currencies" }
        return amount.compareTo(other.amount)
    }
    
    companion object {
        val ZERO = Money(0.0, "ZWG")

        fun fromString(value: String, currency: String = "ZWG"): Money? {
            return try {
                val amount = value.replace("[^\\d.]".toRegex(), "").toDouble()
                Money(amount, currency)
            } catch (e: Exception) {
                null
            }
        }
    }
}