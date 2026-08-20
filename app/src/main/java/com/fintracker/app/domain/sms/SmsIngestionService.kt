package com.fintracker.app.domain.sms

import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.data.repository.AccountRepository
import com.fintracker.app.data.repository.TransactionRepository
import com.fintracker.app.domain.model.ReviewStatus
import com.fintracker.app.domain.model.TransactionSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsIngestionService @Inject constructor(
    private val parseEngine: SmsParseEngine,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val senderLearning: SenderLearningService
) {
    data class Result(
        val savedId: Long?,
        val duplicate: Boolean,
        val parsed: Boolean
    )

    suspend fun ingest(message: SmsMessage): Result {
        when (senderLearning.actionFor(message.sender)) {
            com.fintracker.app.domain.model.SenderRuleAction.IGNORE ->
                return Result(null, false, false)
            else -> Unit
        }
        val parsed = parseEngine.parse(message) ?: return Result(null, false, false)
        val forcedExpense =
            senderLearning.actionFor(message.sender) ==
                com.fintracker.app.domain.model.SenderRuleAction.FORCE_EXPENSE
        val type = if (forcedExpense) {
            com.fintracker.app.domain.model.TransactionType.EXPENSE
        } else {
            parsed.type
        }
        val accountId = accountRepository.findOrCreate(
            bankHint = parsed.bankHint,
            masked = parsed.maskedAccount,
            sender = parsed.sender
        )
        val categoryId = transactionRepository.suggestCategory(
            merchant = parsed.merchant,
            rawText = parsed.rawSnippet,
            type = type
        )
        val entity = TransactionEntity(
            amountPaise = parsed.amountPaise,
            type = type,
            paymentMode = parsed.paymentMode,
            categoryId = categoryId,
            accountId = accountId,
            merchant = parsed.merchant,
            note = null,
            occurredAt = parsed.occurredAt,
            source = TransactionSource.SMS,
            confidence = parsed.confidence,
            reviewStatus = if (parsed.needsReview) ReviewStatus.NEEDS_REVIEW else ReviewStatus.NONE,
            reference = parsed.reference,
            balanceAfterPaise = parsed.balanceAfterPaise,
            rawSmsSnippet = parsed.rawSnippet,
            smsSender = parsed.sender,
            dedupeKey = parsed.dedupeKey
        )
        val (id, duplicate) = transactionRepository.insertDeduped(entity)
        return Result(savedId = id.takeIf { it > 0 }, duplicate = duplicate, parsed = true)
    }
}
