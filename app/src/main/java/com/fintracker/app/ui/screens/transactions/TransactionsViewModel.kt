package com.fintracker.app.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintracker.app.data.entity.CategoryEntity
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.data.repository.CategoryRepository
import com.fintracker.app.data.repository.TransactionRepository
import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.TransactionType
import com.fintracker.app.ui.util.DateFormatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

enum class HistoryScope { MONTH, YEAR }

data class MonthSummary(
    val period: DateFormatters.MonthPeriod,
    val spentPaise: Long,
    val creditedPaise: Long,
    val transferPaise: Long,
    val count: Int
)

data class DayGroup(
    val dayLabel: String,
    val dayKey: String,
    val items: List<TransactionEntity>
)

data class TransactionsUiState(
    val scope: HistoryScope = HistoryScope.MONTH,
    val selectedMonth: DateFormatters.MonthPeriod = DateFormatters.currentMonthPeriod(),
    val selectedYear: DateFormatters.YearPeriod = DateFormatters.currentYearPeriod(),
    val availableMonths: List<DateFormatters.MonthPeriod> = DateFormatters.lastNMonths(18),
    val availableYears: List<Int> = emptyList(),
    val typeFilter: TransactionType? = null,
    val modeFilter: PaymentMode? = null,
    val categoryFilterId: Long? = null,
    val searchQuery: String = "",
    val spentPaise: Long = 0,
    val creditedPaise: Long = 0,
    val transferPaise: Long = 0,
    val items: List<TransactionEntity> = emptyList(),
    val dayGroups: List<DayGroup> = emptyList(),
    val categorySpend: List<Pair<String, Long>> = emptyList(),
    val monthSummaries: List<MonthSummary> = emptyList(),
    val categories: Map<Long, CategoryEntity> = emptyMap(),
    val periodLabel: String = "",
    val periodRangeLabel: String = ""
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository
) : ViewModel() {
    private val selection = MutableStateFlow(
        Selection(
            scope = HistoryScope.MONTH,
            month = DateFormatters.currentMonthPeriod(),
            year = DateFormatters.currentYearPeriod(),
            typeFilter = null,
            modeFilter = null,
            categoryFilterId = null,
            searchQuery = ""
        )
    )

    private data class Selection(
        val scope: HistoryScope,
        val month: DateFormatters.MonthPeriod,
        val year: DateFormatters.YearPeriod,
        val typeFilter: TransactionType?,
        val modeFilter: PaymentMode?,
        val categoryFilterId: Long?,
        val searchQuery: String
    )

    val uiState = combine(
        selection,
        transactionRepository.observeAll(),
        categoryRepository.observeActive()
    ) { sel, all, categories ->
        val catMap = categories.associateBy { it.id }
        val years = all
            .map { DateFormatters.yearFor(it.occurredAt) }
            .toMutableSet()
            .apply { add(DateFormatters.currentYearPeriod().year) }
            .sortedDescending()

        val availableMonths = buildAvailableMonths(all)

        when (sel.scope) {
            HistoryScope.MONTH -> {
                val inMonth = all.filter {
                    it.occurredAt in sel.month.startMs..sel.month.endMs
                }
                val filtered = inMonth.filter { matchesFilters(it, sel) }
                val dayGroups = filtered
                    .groupBy { DateFormatters.dayKey(it.occurredAt) }
                    .entries
                    .sortedByDescending { it.key }
                    .map { (key, list) ->
                        DayGroup(
                            dayLabel = DateFormatters.day(list.first().occurredAt),
                            dayKey = key,
                            items = list.sortedByDescending { it.occurredAt }
                        )
                    }
                val categorySpend = inMonth
                    .filter { it.type == TransactionType.EXPENSE }
                    .groupBy { it.categoryId }
                    .map { (id, list) ->
                        (id?.let { catMap[it]?.name } ?: "Uncategorized") to
                            list.sumOf { it.amountPaise }
                    }
                    .sortedByDescending { it.second }
                TransactionsUiState(
                    scope = HistoryScope.MONTH,
                    selectedMonth = sel.month,
                    selectedYear = sel.year,
                    availableMonths = availableMonths,
                    availableYears = years,
                    typeFilter = sel.typeFilter,
                    modeFilter = sel.modeFilter,
                    categoryFilterId = sel.categoryFilterId,
                    searchQuery = sel.searchQuery,
                    spentPaise = inMonth.filter { it.type == TransactionType.EXPENSE }
                        .sumOf { it.amountPaise },
                    creditedPaise = inMonth.filter { it.type == TransactionType.INCOME }
                        .sumOf { it.amountPaise },
                    transferPaise = inMonth.filter { it.type == TransactionType.TRANSFER }
                        .sumOf { it.amountPaise },
                    items = filtered,
                    dayGroups = dayGroups,
                    categorySpend = categorySpend,
                    categories = catMap,
                    periodLabel = sel.month.label,
                    periodRangeLabel = DateFormatters.periodDayRangeLabel(
                        sel.month.startMs,
                        sel.month.endMs
                    )
                )
            }

            HistoryScope.YEAR -> {
                val inYear = all.filter {
                    it.occurredAt in sel.year.startMs..sel.year.endMs
                }
                val current = DateFormatters.currentMonthPeriod()
                val summaries = (1..12)
                    .map { m ->
                        val period = DateFormatters.monthPeriod(sel.year.year, m)
                        val monthTxns = inYear.filter {
                            it.occurredAt in period.startMs..period.endMs
                        }
                        MonthSummary(
                            period = period,
                            spentPaise = monthTxns.filter { it.type == TransactionType.EXPENSE }
                                .sumOf { it.amountPaise },
                            creditedPaise = monthTxns.filter { it.type == TransactionType.INCOME }
                                .sumOf { it.amountPaise },
                            transferPaise = monthTxns.filter { it.type == TransactionType.TRANSFER }
                                .sumOf { it.amountPaise },
                            count = monthTxns.size
                        )
                    }
                    .filter { summary ->
                        summary.count > 0 ||
                            (
                                summary.period.year == current.year &&
                                    summary.period.month == current.month
                                )
                    }
                    .reversed()

                TransactionsUiState(
                    scope = HistoryScope.YEAR,
                    selectedMonth = sel.month,
                    selectedYear = sel.year,
                    availableMonths = availableMonths,
                    availableYears = years,
                    typeFilter = sel.typeFilter,
                    modeFilter = sel.modeFilter,
                    categoryFilterId = sel.categoryFilterId,
                    searchQuery = sel.searchQuery,
                    spentPaise = inYear.filter { it.type == TransactionType.EXPENSE }
                        .sumOf { it.amountPaise },
                    creditedPaise = inYear.filter { it.type == TransactionType.INCOME }
                        .sumOf { it.amountPaise },
                    transferPaise = inYear.filter { it.type == TransactionType.TRANSFER }
                        .sumOf { it.amountPaise },
                    monthSummaries = summaries,
                    categories = catMap,
                    periodLabel = sel.year.label,
                    periodRangeLabel = "Jan–Dec ${sel.year.year} · IST"
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    fun setScope(scope: HistoryScope) {
        selection.update { it.copy(scope = scope) }
    }

    fun selectMonth(period: DateFormatters.MonthPeriod) {
        selection.update {
            it.copy(
                scope = HistoryScope.MONTH,
                month = period,
                year = DateFormatters.yearPeriod(period.year)
            )
        }
    }

    fun shiftMonth(delta: Int) {
        selection.update {
            val next = DateFormatters.shiftMonth(it.month.year, it.month.month, delta)
            it.copy(
                scope = HistoryScope.MONTH,
                month = next,
                year = DateFormatters.yearPeriod(next.year)
            )
        }
    }

    fun selectYear(year: Int) {
        selection.update {
            it.copy(
                scope = HistoryScope.YEAR,
                year = DateFormatters.yearPeriod(year),
                month = DateFormatters.monthPeriod(year, it.month.month)
            )
        }
    }

    fun shiftYear(delta: Int) {
        selection.update {
            val year = it.year.year + delta
            it.copy(
                scope = HistoryScope.YEAR,
                year = DateFormatters.yearPeriod(year),
                month = DateFormatters.monthPeriod(year, it.month.month)
            )
        }
    }

    fun setSearchQuery(query: String) {
        selection.update { it.copy(searchQuery = query) }
    }

    fun setTypeFilter(type: TransactionType?) {
        selection.update { it.copy(typeFilter = type) }
    }

    fun setModeFilter(mode: PaymentMode?) {
        selection.update { it.copy(modeFilter = mode) }
    }

    fun setCategoryFilter(categoryId: Long?) {
        selection.update { it.copy(categoryFilterId = categoryId) }
    }

    private fun matchesFilters(txn: TransactionEntity, sel: Selection): Boolean {
        if (sel.typeFilter != null && txn.type != sel.typeFilter) return false
        if (sel.modeFilter != null && txn.paymentMode != sel.modeFilter) return false
        if (sel.categoryFilterId != null && txn.categoryId != sel.categoryFilterId) return false
        val q = sel.searchQuery.trim()
        if (q.isNotEmpty()) {
            val haystack = listOfNotNull(
                txn.merchant,
                txn.note,
                txn.reference,
                txn.rawSmsSnippet,
                txn.amountPaise.toString(),
                (txn.amountPaise / 100.0).toString()
            ).joinToString(" ").lowercase()
            if (!haystack.contains(q.lowercase())) return false
        }
        return true
    }

    private fun buildAvailableMonths(all: List<TransactionEntity>): List<DateFormatters.MonthPeriod> {
        val keys = all.map { DateFormatters.monthKeyFor(it.occurredAt) }.toMutableSet()
        val current = DateFormatters.currentMonthPeriod()
        keys += current.key
        return keys
            .mapNotNull { key ->
                val parts = key.split("-")
                if (parts.size != 2) return@mapNotNull null
                DateFormatters.monthPeriod(parts[0].toInt(), parts[1].toInt())
            }
            .sortedByDescending { it.startMs }
            .take(24)
    }
}
