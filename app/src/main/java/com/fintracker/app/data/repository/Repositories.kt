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
    private val accountDao: AccountDao
) {
    fun observeActive(): Flow<List<AccountEntity>> = accountDao.observeActive()
    suspend fun getAll(): List<AccountEntity> = accountDao.getAll()
    suspend fun getById(id: Long): AccountEntity? = accountDao.getById(id)
    suspend fun insert(account: AccountEntity): Long = accountDao.insert(account)
    suspend fun update(account: AccountEntity) = accountDao.update(account)
    suspend fun findOrCreate(bankHint: String?, masked: String?): Long? {
        if (bankHint.isNullOrBlank() && masked.isNullOrBlank()) return null
        bankHint?.let { hint ->
            accountDao.findByBankHint(hint)?.let { return it.id }
        }
        val name = buildString {
            if (!bankHint.isNullOrBlank()) append(bankHint)
            if (!masked.isNullOrBlank()) {
                if (isNotEmpty()) append(' ')
                append(masked)
            }
        }.ifBlank { "Account" }
        return accountDao.insert(
            AccountEntity(name = name, bankHint = bankHint, maskedNumber = masked)
        )
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
