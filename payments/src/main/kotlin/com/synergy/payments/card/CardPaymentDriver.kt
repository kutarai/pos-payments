package com.synergy.payments.card

import android.content.Context
import com.synergy.payments.ui.CardPaymentResult
import com.synergy.payments.ui.CardNetwork

/**
 * How far a card payment has got, in the terms a cashier's screen needs.
 *
 * Deliberately coarse. A screen wants to say "insert your card", "enter your PIN", "contacting
 * the bank" — it does not want to know about candidate lists, cryptogram types or which of the
 * EMV kernel's eighty states the terminal is in, and a screen written against those would have to
 * be rewritten for every make of terminal.
 */
enum class CardFlowUpdate {
    /** A card has been inserted, tapped or swiped. */
    CARD_DETECTED,

    /** The customer is being asked for a PIN on the terminal's own keypad. */
    ENTER_PIN,

    /** The terminal is working: reading the card, building the cryptogram. */
    PROCESSING,

    /** The transaction has gone to the switch and an answer is awaited. */
    ONLINE_AUTH
}

/**
 * Taking a card payment on whatever terminal this application is running on.
 *
 * This is the whole of what the card screen needs from the hardware. A new make of terminal
 * implements this and the screen, the switch call, the receipt and the flow are all unchanged —
 * which is the point of keeping it this small.
 */
interface CardPaymentDriver {

    /**
     * Runs a card payment to its conclusion.
     *
     * @param amount   in minor units, because that is what an acquirer settles in and floating
     *                 point has no business anywhere near an authorisation.
     * @param onUpdate progress for the screen, called on whatever thread the kernel uses.
     */
    suspend fun processPayment(
        amount: Long,
        cardNetwork: CardNetwork = CardNetwork.VISA_MASTERCARD,
        currency: String = "USD",
        onUpdate: ((CardFlowUpdate) -> Unit)? = null
    ): CardPaymentResult

    /**
     * Abandons a payment in progress — the customer walked away, or the cashier pressed cancel.
     *
     * Must be safe to call when nothing is in progress, because a screen closing cannot know.
     */
    suspend fun cancel()
}

/**
 * Where the application says which terminal it is running on.
 *
 * A registry rather than constructor injection because the card screen is an Activity, started by
 * the platform with an Intent, and cannot be handed dependencies. The application sets this once
 * at start-up — in SynergyPOS that is `SynergyApplication` — and payment screens ask for whatever
 * was registered. An application that registers nothing gets a clear failure at the point of sale
 * rather than a crash inside a vendor SDK.
 */
object CardPaymentDrivers {

    @Volatile
    private var factory: ((Context) -> CardPaymentDriver)? = null

    fun register(factory: (Context) -> CardPaymentDriver) {
        this.factory = factory
    }

    fun isRegistered(): Boolean = factory != null

    /** @throws IllegalStateException if the application never said what terminal it has. */
    fun create(context: Context): CardPaymentDriver =
        factory?.invoke(context) ?: error(
            "No card payment driver registered. The application must call " +
                "CardPaymentDrivers.register { … } at start-up, naming the terminal it runs on."
        )
}
