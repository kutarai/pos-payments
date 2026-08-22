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

    /**
     * Waiting for a customer to present a card.
     *
     * The backstop, not the normal path: the driver has its own detect window and is expected
     * to answer first with something specific — a reader error, a code, a reason. This only
     * fires when the driver says nothing at all, so it has to outlast the driver's own bound
     * rather than expire alongside it. At one point both were thirty and they raced, and the
     * screen's generic "no card presented" won every time, throwing away the driver's answer.
     *
     * CS20 detects for 25 and gives up hard at 28, so this stays clear of both.
     */
    const val CARD_PRESENTATION_SECONDS = 30
}
