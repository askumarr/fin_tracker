package com.fintracker.app.domain.sms

import com.fintracker.app.data.dao.SmsSenderRuleDao
import com.fintracker.app.data.entity.SmsSenderRuleEntity
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.domain.model.SenderRuleAction
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SenderLearningService @Inject constructor(
    private val smsSenderRuleDao: SmsSenderRuleDao
) {
    suspend fun rememberFromDismiss(txn: TransactionEntity) {
        val sender = txn.smsSender?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val key = normalizeSender(sender)
        smsSenderRuleDao.upsert(
            SmsSenderRuleEntity(
                id = smsSenderRuleDao.findByPattern(key)?.id ?: 0,
                senderPattern = key,
                allowed = false,
                action = SenderRuleAction.IGNORE.name,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun rememberFromConfirm(txn: TransactionEntity) {
        val sender = txn.smsSender?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val key = normalizeSender(sender)
        // Confirm means this sender produces real money movement — allow future SMS.
        smsSenderRuleDao.upsert(
            SmsSenderRuleEntity(
                id = smsSenderRuleDao.findByPattern(key)?.id ?: 0,
                senderPattern = key,
                allowed = true,
                action = SenderRuleAction.ALLOW.name,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun actionFor(sender: String): SenderRuleAction {
        val key = normalizeSender(sender)
        val rule = smsSenderRuleDao.findByPattern(key) ?: return SenderRuleAction.ALLOW
        return runCatching { SenderRuleAction.valueOf(rule.action) }
            .getOrElse {
                if (rule.allowed) SenderRuleAction.ALLOW else SenderRuleAction.IGNORE
            }
    }

    fun normalizeSender(sender: String): String =
        sender.trim().uppercase(Locale.US)
            .removePrefix("AD-")
            .removePrefix("AX-")
            .removePrefix("VM-")
            .removePrefix("VK-")
            .removePrefix("TX-")
            .removePrefix("JD-")
            .substringBefore('-')
            .ifBlank { sender.trim().uppercase(Locale.US) }
}
