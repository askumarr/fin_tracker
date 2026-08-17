package com.fintracker.app.domain.sms

import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.TransactionType

data class SmsTemplate(
    val id: String,
    val bankHint: String?,
    val senderPatterns: List<String>,
    val bodyPatterns: List<String>,
    val amountGroup: Int = 1,
    val merchantGroup: Int? = null,
    val referenceGroup: Int? = null,
    val balanceGroup: Int? = null,
    val accountGroup: Int? = null,
    val type: TransactionType,
    val paymentMode: PaymentMode,
    val confidence: Float = 0.9f
)

data class ParsedSmsTransaction(
    val amountPaise: Long,
    val type: TransactionType,
    val paymentMode: PaymentMode,
    val merchant: String?,
    val reference: String?,
    val balanceAfterPaise: Long?,
    val maskedAccount: String?,
    val bankHint: String?,
    val confidence: Float,
    val sender: String,
    val rawSnippet: String,
    val occurredAt: Long,
    val needsReview: Boolean,
    val dedupeKey: String
)

data class SmsMessage(
    val sender: String,
    val body: String,
    val receivedAt: Long
)
