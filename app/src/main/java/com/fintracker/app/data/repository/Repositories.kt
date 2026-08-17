package com.fintracker.app.data.repository

import com.fintracker.app.data.dao.AccountDao
import com.fintracker.app.data.dao.CategoryDao
import com.fintracker.app.data.dao.ImportJobDao
import com.fintracker.app.data.dao.MerchantCategoryRuleDao
import com.fintracker.app.data.dao.SmsSenderRuleDao
import com.fintracker.app.data.dao.TransactionDao
import com.fintracker.app.data.entity.AccountEntity
import com.fintracker.app.data.entity.CategoryEntity
import com.fintracker.app.data.entity.ImportJobEntity
import com.fintracker.app.data.entity.MerchantCategoryRuleEntity
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.domain.model.ReviewStatus
import com.fintracker.app.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val merchantCategoryRuleDao: MerchantCategoryRuleDao
) {
    fun observeAll(): Flow<List<TransactionEntity>> = transactionDao.observeAll()

    fun observeInRange(start: Long, end: Long): Flow<List<TransactionEntity>> =
        transactionDao.observeInRange(start, end)

    fun observeNeedsReview(): Flow<List<TransactionEntity>> =
        transactionDao.observeByReviewStatus(ReviewStatus.NEEDS_REVIEW)

    fun observeReviewCount(): Flow<Int> =
        transactionDao.observeReviewCount(ReviewStatus.NEEDS_REVIEW)

    fun observeSum(type: TransactionType, start: Long, end: Long): Flow<Long> =
        transactionDao.observeSum(type, start, end)

    suspend fun getById(id: Long): TransactionEntity? = transactionDao.getById(id)

    suspend fun findByDedupeKey(key: String): TransactionEntity? =
        transactionDao.findByDedupeKey(key)

    /** @return Pair(id, wasDuplicate) */
    suspend fun insertDeduped(transaction: TransactionEntity): Pair<Long, Boolean> {
        transaction.dedupeKey?.let { key ->
            val existing = transactionDao.findByDedupeKey(key)
            if (existing != null) return existing.id to true
        }
        val id = transactionDao.insert(transaction)
        if (id == -1L) {
            val existingId = transaction.dedupeKey?.let { transactionDao.findByDedupeKey(it)?.id } ?: 0L
            return existingId to true
        }
        return id to false
    }

    suspend fun insert(transaction: TransactionEntity): Long =
        insertDeduped(transaction).first

    suspend fun update(transaction: TransactionEntity) =
        transactionDao.update(transaction.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(id: Long) = transactionDao.deleteById(id)

    /** Rows must be removed rather than dismissed, so a rescan is free to re-create them. */
    suspend fun deleteSmsImportedSince(since: Long): Int =
        transactionDao.deleteSmsImportedSince(since)

    suspend fun rememberMerchantCategory(merchant: String?, categoryId: Long?) {
        if (merchant.isNullOrBlank() || categoryId == null) return
        merchantCategoryRuleDao.upsert(
            MerchantCategoryRuleEntity(
                merchantKey = merchant.trim().lowercase(),
                categoryId = categoryId
            )
        )
    }

    suspend fun suggestCategory(merchant: String?): Long? {
        if (merchant.isNullOrBlank()) return null
        return merchantCategoryRuleDao.find(merchant.trim().lowercase())?.categoryId
    }

    suspend fun getAllForBackup(): List<TransactionEntity> = transactionDao.getAllForBackup()

    suspend fun replaceAll(transactions: List<TransactionEntity>) {
        transactionDao.deleteAll()
        transactions.forEach { transactionDao.insert(it.copy(id = 0)) }
    }
}

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao
) {
    fun observeActive(): Flow<List<CategoryEntity>> = categoryDao.observeActive()
    suspend fun getAll(): List<CategoryEntity> = categoryDao.getAll()
    suspend fun getById(id: Long): CategoryEntity? = categoryDao.getById(id)
    suspend fun insert(category: CategoryEntity): Long = categoryDao.insert(category)
    suspend fun update(category: CategoryEntity) = categoryDao.update(category)
}

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao
) {
    private val lock = Mutex()

    fun observeActive(): Flow<List<AccountEntity>> = accountDao.observeActive()
    suspend fun getAll(): List<AccountEntity> = accountDao.getAll()
    suspend fun getById(id: Long): AccountEntity? = accountDao.getById(id)
    suspend fun insert(account: AccountEntity): Long = accountDao.insert(account)
    suspend fun update(account: AccountEntity) = accountDao.update(account)

    /**
     * One logical bank/account per normalized bank hint (or masked number when no bank is known).
     * Serialized so parallel SMS workers cannot create the same bank twice.
     */
    suspend fun findOrCreate(
        bankHint: String?,
        masked: String?,
        sender: String? = null
    ): Long? = lock.withLock {
        val hint = normalizeBankHint(bankHint) ?: inferBankHintFromSender(sender)
        val mask = normalizeMasked(masked)
        if (hint == null && mask == null) return@withLock null

        findExisting(hint, mask)?.let { existing ->
            enrichIfNeeded(existing, hint, mask)
            return@withLock existing.id
        }

        val name = when {
            hint != null && mask != null -> "$hint ·$mask"
            hint != null -> hint
            else -> "Card ·${mask}"
        }
        accountDao.insert(
            AccountEntity(
                name = name,
                bankHint = hint,
                maskedNumber = mask
            )
        )
    }

    /** Collapse historical duplicates created by the old always-insert path. */
    suspend fun mergeDuplicates(): Int = lock.withLock {
        val all = accountDao.getAll()
        val groups = all.groupBy { mergeKey(it) }.filter { it.value.size > 1 }
        var removed = 0
        for ((_, dupes) in groups) {
            val keeper = dupes.minBy { it.id }
            for (extra in dupes.filter { it.id != keeper.id }) {
                transactionDao.reassignAccount(extra.id, keeper.id)
                accountDao.deleteById(extra.id)
                removed++
            }
            val bestHint = dupes.mapNotNull { normalizeBankHint(it.bankHint) }.firstOrNull()
                ?: normalizeBankHint(keeper.bankHint)
            val bestMask = dupes.mapNotNull { normalizeMasked(it.maskedNumber) }.firstOrNull()
                ?: normalizeMasked(keeper.maskedNumber)
            val bestName = when {
                bestHint != null && bestMask != null -> "$bestHint ·$bestMask"
                bestHint != null -> bestHint
                bestMask != null -> "Card ·$bestMask"
                else -> keeper.name
            }
            if (keeper.bankHint != bestHint || keeper.maskedNumber != bestMask || keeper.name != bestName) {
                accountDao.update(
                    keeper.copy(
                        name = bestName,
                        bankHint = bestHint,
                        maskedNumber = bestMask
                    )
                )
            }
        }
        removed
    }

    private suspend fun findExisting(hint: String?, mask: String?): AccountEntity? {
        if (hint != null) {
            accountDao.findByBankHint(hint)?.let { return it }
            accountDao.findByName(hint)?.let { return it }
        }
        if (mask != null) {
            accountDao.findByMasked(mask)?.let { return it }
            if (hint != null) {
                accountDao.findByName("$hint ·$mask")?.let { return it }
            }
        }
        return null
    }

    private suspend fun enrichIfNeeded(existing: AccountEntity, hint: String?, mask: String?) {
        var next = existing
        var changed = false
        if (existing.bankHint.isNullOrBlank() && hint != null) {
            next = next.copy(bankHint = hint)
            changed = true
        }
        if (existing.maskedNumber.isNullOrBlank() && mask != null) {
            next = next.copy(maskedNumber = mask)
            changed = true
        }
        val preferredName = when {
            next.bankHint != null && next.maskedNumber != null ->
                "${next.bankHint} ·${next.maskedNumber}"
            next.bankHint != null -> next.bankHint!!
            next.maskedNumber != null -> "Card ·${next.maskedNumber}"
            else -> next.name
        }
        if (next.name != preferredName) {
            next = next.copy(name = preferredName)
            changed = true
        }
        if (changed) accountDao.update(next)
    }

    private fun mergeKey(account: AccountEntity): String {
        normalizeBankHint(account.bankHint)?.let { return "bank:$it" }
        val nameHead = account.name.trim().uppercase(Locale.US)
            .substringBefore(" ·")
            .substringBefore('·')
            .trim()
        normalizeBankHint(nameHead)
            ?.takeUnless { it in setOf("CARD", "ACCOUNT", "BANK") }
            ?.let { return "bank:$it" }
        normalizeMasked(account.maskedNumber)?.let { return "mask:$it" }
        return "name:${nameHead.ifBlank { "id-${account.id}" }}"
    }

    companion object {
        fun normalizeBankHint(raw: String?): String? =
            raw?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() }

        fun normalizeMasked(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val digits = raw.filter { it.isDigit() }
            return when {
                digits.length >= 4 -> digits.takeLast(4)
                else -> raw.trim().uppercase(Locale.US).takeLast(8).takeIf { it.isNotEmpty() }
            }
        }

        fun inferBankHintFromSender(sender: String?): String? {
            if (sender.isNullOrBlank()) return null
            val s = sender.uppercase(Locale.US)
            return when {
                "HDFC" in s -> "HDFC"
                "SBICRD" in s || "SBIINB" in s || Regex("""\bSBI\b""").containsMatchIn(s) -> "SBI"
                "ICICI" in s -> "ICICI"
                "AXIS" in s -> "AXIS"
                "KOTAK" in s -> "KOTAK"
                "FEDSCP" in s || "FEDBNK" in s || "FEDERAL" in s || "SCAPIA" in s -> "FEDERAL"
                "YESBNK" in s || "YESBANK" in s -> "YES"
                "IDFC" in s -> "IDFC"
                "CANBNK" in s || "CANARA" in s -> "CANARA"
                "KVB" in s -> "KVB"
                "INDUS" in s -> "INDUSIND"
                else -> null
            }
        }
    }
}

@Singleton
class ImportJobRepository @Inject constructor(
    private val importJobDao: ImportJobDao,
    private val smsSenderRuleDao: SmsSenderRuleDao,
    private val merchantCategoryRuleDao: MerchantCategoryRuleDao
) {
    fun observeRecent(): Flow<List<ImportJobEntity>> = importJobDao.observeRecent()
    suspend fun insert(job: ImportJobEntity): Long = importJobDao.insert(job)
    suspend fun update(job: ImportJobEntity) = importJobDao.update(job)
    suspend fun getMerchantRules() = merchantCategoryRuleDao.getAll()
    suspend fun getSenderRules() = smsSenderRuleDao.getAll()
    suspend fun replaceMerchantRules(rules: List<MerchantCategoryRuleEntity>) {
        merchantCategoryRuleDao.deleteAll()
        rules.forEach { merchantCategoryRuleDao.upsert(it.copy(id = 0)) }
    }
}
