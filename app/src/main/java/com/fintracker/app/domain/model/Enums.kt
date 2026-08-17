package com.fintracker.app.domain.model

enum class PaymentMode {
    UPI,
    DEBIT_CARD,
    CREDIT_CARD,
    NET_BANKING,
    CASH,
    UNKNOWN
}

enum class TransactionType {
    EXPENSE,
    INCOME,

    /**
     * Money moved between the user's own accounts (e.g. savings -> credit card bill payment).
     * Excluded from spend/income totals: the card purchases it settles are already expenses.
     */
    TRANSFER
}

enum class TransactionSource {
    SMS,
    CSV,
    MANUAL
}

enum class ReviewStatus {
    NONE,
    NEEDS_REVIEW,
    CONFIRMED,
    DISMISSED
}

/** Learned behavior for an SMS sender after review decisions. */
enum class SenderRuleAction {
    ALLOW,
    IGNORE,
    FORCE_EXPENSE
}
