package com.fintracker.app.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintracker.app.data.entity.CategoryEntity
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.data.repository.CategoryRepository
import com.fintracker.app.data.repository.TransactionRepository
import com.fintracker.app.domain.model.TransactionType
import com.fintracker.app.ui.components.MonthlySpendPoint
import com.fintracker.app.ui.util.DateFormatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DashboardUiState(
    val monthLabel: String = "",
    val monthRangeLabel: String = "",
    val spentPaise: Long = 0,
    val creditedPaise: Long = 0,
    val reviewCount: Int = 0,
    val recent: List<TransactionEntity> = emptyList(),
    val categories: Map<Long, CategoryEntity> = emptyMap(),
    val categorySpend: List<Pair<String, Long>> = emptyList(),
    val monthlyTrend: List<MonthlySpendPoint> = emptyList()
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository
) : ViewModel() {
    private val month = DateFormatters.currentMonthPeriod()
    private val trendMonths = DateFormatters.lastNMonths(12)

    private val lists = combine(
        transactionRepository.observeInRange(month.startMs, month.endMs),
        categoryRepository.observeActive(),
        transactionRepository.observeInRange(trendMonths.first().startMs, trendMonths.last().endMs)
    ) { monthTxns, categories, trendTxns ->
        Triple(monthTxns, categories, trendTxns)
    }

    val uiState = combine(
        transactionRepository.observeSum(TransactionType.EXPENSE, month.startMs, month.endMs),
        transactionRepository.observeSum(TransactionType.INCOME, month.startMs, month.endMs),
        transactionRepository.observeReviewCount(),
        lists
    ) { spent, credited, reviewCount, lists ->
        val (txns, categories, trendTxns) = lists
        val catMap = categories.associateBy { it.id }
        val spendByCat = txns
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.categoryId }
            .map { (id, list) ->
                val name = id?.let { catMap[it]?.name } ?: "Uncategorized"
                name to list.sumOf { it.amountPaise }
            }
            .sortedByDescending { it.second }
            .take(6)

        val spendByMonthKey = trendTxns
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { DateFormatters.monthKeyFor(it.occurredAt) }
            .mapValues { (_, list) -> list.sumOf { it.amountPaise } }

        val monthlyTrend = trendMonths.map { period ->
            MonthlySpendPoint(
                period = period,
                spentPaise = spendByMonthKey[period.key] ?: 0L
            )
        }

        DashboardUiState(
            monthLabel = month.label,
            monthRangeLabel = DateFormatters.periodDayRangeLabel(month.startMs, month.endMs),
            spentPaise = spent,
            creditedPaise = credited,
            reviewCount = reviewCount,
            recent = txns.take(12),
            categories = catMap,
            categorySpend = spendByCat,
            monthlyTrend = monthlyTrend
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DashboardUiState(
            monthLabel = month.label,
            monthRangeLabel = DateFormatters.periodDayRangeLabel(month.startMs, month.endMs)
        )
    )
}
