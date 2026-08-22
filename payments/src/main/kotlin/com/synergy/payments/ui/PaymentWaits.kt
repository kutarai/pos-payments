package com.synergy.payments.ui

/**
 * How long a payment screen waits, in one place.
 *
 * These were three different numbers in three files — the card screen gave the switch twenty
 * seconds, mobile money twenty, QR thirty — for what is, from the counter's side, the same
 * wait: the money has left the terminal and the bank has not answered yet. A cashier who has
 * learned how long that takes on one method was learning the wrong number for the next.
 */
internal object PaymentWaits {

    /**
     * Waiting on the switch: an authorisation sent, an answer owed.
     *
     * Matches SwitchClient's own deadline, deliberately. A screen that counts past the deadline
     * shows a wait that cannot end in an answer, and one that counts short of it gives up on a
     * bank that was about to reply.
     */
    const val SWITCH_SECONDS = 30
}
