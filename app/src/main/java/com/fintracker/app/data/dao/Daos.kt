package com.fintracker.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fintracker.app.data.entity.AccountEntity
import com.fintracker.app.data.entity.BudgetEntity
import com.fintracker.app.data.entity.CategoryEntity
import com.fintracker.app.data.entity.ImportJobEntity
import com.fintracker.app.data.entity.MerchantCategoryRuleEntity
import com.fintracker.app.data.entity.SmsSenderRuleEntity
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.domain.model.ReviewStatus
import com.fintracker.app.domain.model.TransactionSource
import com.fintracker.app.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY name")
    fun observeActive(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY name")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByName(name: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        SELECT * FROM accounts
        WHERE bankHint IS NOT NULL AND UPPER(TRIM(bankHint)) = UPPER(TRIM(:bankHint))
        ORDER BY id ASC
        LIMIT 1
        """
    )
    suspend fun findByBankHint(bankHint: String): AccountEntity?

    @Query(
        """
        SELECT * FROM accounts
        WHERE maskedNumber IS NOT NULL AND UPPER(TRIM(maskedNumber)) = UPPER(TRIM(:masked))
        ORDER BY id ASC
        LIMIT 1
        """
    )
    suspend fun findByMasked(masked: String): AccountEntity?

    @Query(
        """
        SELECT * FROM accounts
        WHERE UPPER(TRIM(name)) = UPPER(TRIM(:name))
        ORDER BY id ASC
        LIMIT 1
        """
    )
    suspend fun findByName(name: String): AccountEntity?
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE reviewStatus != 'DISMISSED' ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE reviewStatus != 'DISMISSED'
          AND occurredAt BETWEEN :start AND :end
        ORDER BY occurredAt DESC
        """
    )
    fun observeInRange(start: Long, end: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE reviewStatus = :status ORDER BY occurredAt DESC")
    fun observeByReviewStatus(status: ReviewStatus): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE reviewStatus = :status")
    fun observeReviewCount(status: ReviewStatus): Flow<Int>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE dedupeKey = :key LIMIT 1")
    suspend fun findByDedupeKey(key: String): TransactionEntity?

    @Query(
        """
        SELECT * FROM transactions
        WHERE source = 'SMS'
          AND amountPaise = :amountPaise
          AND type = :type
          AND occurredAt BETWEEN :dayStart AND :dayEnd
          AND reviewStatus != 'DISMISSED'
        ORDER BY confidence DESC, id ASC
        """
    )
    suspend fun findSameDaySms(
        amountPaise: Long,
        type: TransactionType,
        dayStart: Long,
        dayEnd: Long
    ): List<TransactionEntity>

    /**
     * Any-source candidates for the same amount on the same IST day. Used to pair a statement
     * (CSV/PDF) row with the SMS row for the same transaction.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amountPaise = :amountPaise
          AND occurredAt BETWEEN :dayStart AND :dayEnd
          AND reviewStatus != 'DISMISSED'
        ORDER BY id ASC
        """
    )
    suspend fun findSameDayAnySource(
        amountPaise: Long,
        dayStart: Long,
        dayEnd: Long
    ): List<TransactionEntity>

    @Query(
        """
        SELECT * FROM transactions
        WHERE source = 'SMS'
          AND reference IS NOT NULL
          AND reference = :reference
          AND amountPaise = :amountPaise
        LIMIT 1
        """
    )
    suspend fun findByReferenceAndAmount(reference: String, amountPaise: Long): TransactionEntity?

    @Query(
        """
        SELECT * FROM transactions
        WHERE reference IS NOT NULL
          AND reference = :reference
          AND amountPaise = :amountPaise
        ORDER BY id ASC
        LIMIT 1
        """
    )
    suspend fun findAnySourceByReferenceAndAmount(
        reference: String,
        amountPaise: Long
    ): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE source = 'SMS' ORDER BY occurredAt DESC")
    suspend fun getAllSms(): List<TransactionEntity>

    @Query("DELETE FROM transactions WHERE source = :source")
    suspend fun deleteBySource(source: TransactionSource): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE transactions SET accountId = :toId WHERE accountId = :fromId")
    suspend fun reassignAccount(fromId: Long, toId: Long)

    @Query(
        """
        SELECT COALESCE(SUM(amountPaise), 0) FROM transactions
        WHERE type = :type
          AND reviewStatus IN ('NONE', 'CONFIRMED')
          AND occurredAt BETWEEN :start AND :end
        """
    )
    fun observeSum(type: TransactionType, start: Long, end: Long): Flow<Long>

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    suspend fun getAllForBackup(): List<TransactionEntity>

    @Query(
        """
        SELECT * FROM transactions
        WHERE categoryId IS NULL
          AND reviewStatus != 'DISMISSED'
        ORDER BY occurredAt DESC
        """
    )
    suspend fun findUncategorized(): List<TransactionEntity>

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("DELETE FROM transactions WHERE source = 'SMS' AND occurredAt >= :since")
    suspend fun deleteSmsImportedSince(since: Long): Int
}

@Dao
interface MerchantCategoryRuleDao {
    @Query("SELECT * FROM merchant_category_rules WHERE merchantKey = :key LIMIT 1")
    suspend fun find(key: String): MerchantCategoryRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: MerchantCategoryRuleEntity)

    @Query("SELECT * FROM merchant_category_rules")
    suspend fun getAll(): List<MerchantCategoryRuleEntity>

    @Query("DELETE FROM merchant_category_rules")
    suspend fun deleteAll()
}

@Dao
interface SmsSenderRuleDao {
    @Query("SELECT * FROM sms_sender_rules")
    suspend fun getAll(): List<SmsSenderRuleEntity>

    @Query("SELECT * FROM sms_sender_rules WHERE senderPattern = :pattern LIMIT 1")
    suspend fun findByPattern(pattern: String): SmsSenderRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: SmsSenderRuleEntity)

    @Query("DELETE FROM sms_sender_rules")
    suspend fun deleteAll()
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets ORDER BY categoryId")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets")
    suspend fun getAll(): List<BudgetEntity>

    @Query(
        """
        SELECT * FROM budgets
        WHERE categoryId = :categoryId AND (monthKey = :monthKey OR monthKey = '*')
        ORDER BY CASE WHEN monthKey = :monthKey THEN 0 ELSE 1 END
        LIMIT 1
        """
    )
    suspend fun findForCategoryMonth(categoryId: Long, monthKey: String): BudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity): Long

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ImportJobDao {
    @Insert
    suspend fun insert(job: ImportJobEntity): Long

    @Update
    suspend fun update(job: ImportJobEntity)

    @Query("SELECT * FROM import_jobs ORDER BY startedAt DESC LIMIT 20")
    fun observeRecent(): Flow<List<ImportJobEntity>>
}
