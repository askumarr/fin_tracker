package com.fintracker.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fintracker.app.data.dao.AccountDao
import com.fintracker.app.data.dao.BudgetDao
import com.fintracker.app.data.dao.CategoryDao
import com.fintracker.app.data.dao.ImportJobDao
import com.fintracker.app.data.dao.MerchantCategoryRuleDao
import com.fintracker.app.data.dao.SmsSenderRuleDao
import com.fintracker.app.data.dao.TransactionDao
import com.fintracker.app.data.entity.AccountEntity
import com.fintracker.app.data.entity.BudgetEntity
import com.fintracker.app.data.entity.CategoryEntity
import com.fintracker.app.data.entity.ImportJobEntity
import com.fintracker.app.data.entity.MerchantCategoryRuleEntity
import com.fintracker.app.data.entity.SmsSenderRuleEntity
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.data.repository.AccountRepository
import com.fintracker.app.data.repository.TransactionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        CategoryEntity::class,
        AccountEntity::class,
        TransactionEntity::class,
        MerchantCategoryRuleEntity::class,
        SmsSenderRuleEntity::class,
        BudgetEntity::class,
        ImportJobEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class FinTrackerDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun merchantCategoryRuleDao(): MerchantCategoryRuleDao
    abstract fun smsSenderRuleDao(): SmsSenderRuleDao
    abstract fun budgetDao(): BudgetDao
    abstract fun importJobDao(): ImportJobDao

    companion object {
        const val NAME = "fin_tracker.db"

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS budgets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        categoryId INTEGER NOT NULL,
                        monthKey TEXT NOT NULL,
                        limitPaise INTEGER NOT NULL,
                        alertRatio REAL NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_categoryId_monthKey " +
                        "ON budgets(categoryId, monthKey)"
                )
                try {
                    db.execSQL(
                        "ALTER TABLE sms_sender_rules ADD COLUMN action TEXT NOT NULL DEFAULT 'ALLOW'"
                    )
                } catch (_: Exception) {
                }
                try {
                    db.execSQL(
                        "ALTER TABLE sms_sender_rules ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0"
                    )
                } catch (_: Exception) {
                }
                try {
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_sms_sender_rules_senderPattern " +
                            "ON sms_sender_rules(senderPattern)"
                    )
                } catch (_: Exception) {
                }
            }
        }
    }
}

object DefaultCategories {
    val all = listOf(
        CategoryEntity(name = "Food", iconKey = "food", isDefault = true),
        CategoryEntity(name = "Groceries", iconKey = "groceries", isDefault = true),
        CategoryEntity(name = "Travel", iconKey = "travel", isDefault = true),
        CategoryEntity(name = "Fuel", iconKey = "fuel", isDefault = true),
        CategoryEntity(name = "Shopping", iconKey = "shopping", isDefault = true),
        CategoryEntity(name = "Bills/Utilities", iconKey = "bills", isDefault = true),
        CategoryEntity(name = "Rent", iconKey = "rent", isDefault = true),
        CategoryEntity(name = "EMI", iconKey = "emi", isDefault = true),
        CategoryEntity(name = "Health", iconKey = "health", isDefault = true),
        CategoryEntity(name = "Education", iconKey = "education", isDefault = true),
        CategoryEntity(name = "Entertainment", iconKey = "entertainment", isDefault = true),
        CategoryEntity(name = "Transfers", iconKey = "transfers", isDefault = true),
        CategoryEntity(name = "Salary", iconKey = "salary", isDefault = true),
        CategoryEntity(name = "Investment", iconKey = "investment", isDefault = true),
        CategoryEntity(name = "Cash", iconKey = "cash", isDefault = true),
        CategoryEntity(name = "Other", iconKey = "other", isDefault = true)
    )
}

class DatabaseSeeder(
    private val categoryDao: CategoryDao,
    private val accountRepository: AccountRepository? = null,
    private val transactionRepository: TransactionRepository? = null
) {
    suspend fun seedIfNeeded() {
        if (categoryDao.count() == 0) {
            categoryDao.insertAll(DefaultCategories.all)
        }
        accountRepository?.mergeDuplicates()
        transactionRepository?.mergeSameDaySmsDuplicates()
    }

    fun seedAsync() {
        CoroutineScope(Dispatchers.IO).launch { seedIfNeeded() }
    }
}
