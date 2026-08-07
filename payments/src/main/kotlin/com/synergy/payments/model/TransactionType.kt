package com.synergy.payments.model

import kotlinx.serialization.Serializable

/**
 * What kind of transaction this is, and how far it has got.
 *
 * Kept beside the payment types rather than with a point-of-sale's own records: a refund and a
 * void mean the same thing to an acquirer whether the thing being refunded was a basket of
 * groceries, a utility bill or a bus ticket.
 */
enum class TransactionType {
    SALE, RETURN, VOID, REFUND, ADJUSTMENT
}

@Serializable
enum class TransactionStatus {
    PENDING, PROCESSING, COMPLETED, CANCELLED, FAILED, REFUNDED, VOIDED
}
