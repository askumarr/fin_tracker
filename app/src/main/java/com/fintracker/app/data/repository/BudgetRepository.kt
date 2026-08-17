package com.fintracker.app.data.repository

import com.fintracker.app.data.dao.BudgetDao
import com.fintracker.app.data.entity.BudgetEntity
import com.fintracker.app.domain.model.TransactionType
import com.fintracker.app.ui.util.DateFormatters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

data class BudgetStatus(
    val budget: BudgetEntity,
    val spentPaise: Long,
    val categoryName: String,
    val ratio: Float,
    val overAlert: Boolean,
    val overLimit: Boolean
)

@Singleton
class BudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository
) {
    fun observeAll(): Flow<List<BudgetEntity>> = budgetDao.observeAll()

    suspend fun upsert(budget: BudgetEntity): Long = budgetDao.upsert(budget)

    suspend fun delete(id: Long) = budgetDao.deleteById(id)

    fun observeStatusesForCurrentMonth(): Flow<List<BudgetStatus>> {
        val month = DateFormatters.currentMonthPeriod()
        val monthKey = "%04d-%02d".format(month.year, month.month)
        return combine(
            budgetDao.observeAll(),
            transactionRepository.observeInRange(month.startMs, month.endMs),
            categoryRepository.observeActive()
        ) { budgets, txns, categories ->
            val catMap = categories.associateBy { it.id }
            budgets.mapNotNull { budget ->
                if (budget.monthKey != "*" && budget.monthKey != monthKey) return@mapNotNull null
                val spent = txns
                    .filter {
                        it.type == TransactionType.EXPENSE && it.categoryId == budget.categoryId
                    }
                    .sumOf { it.amountPaise }
                val ratio = if (budget.limitPaise <= 0L) {
                    0f
                } else {
                    (spent.toDouble() / budget.limitPaise.toDouble()).toFloat()
                }
                BudgetStatus(
                    budget = budget,
                    spentPaise = spent,
                    categoryName = catMap[budget.categoryId]?.name ?: "Category",
                    ratio = ratio,
                    overAlert = ratio >= budget.alertRatio,
                    overLimit = spent >= budget.limitPaise
                )
            }
        }
    }
}
