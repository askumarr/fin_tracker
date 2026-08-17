package com.fintracker.app.ui.screens.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.TransactionType
import com.fintracker.app.ui.components.CategorySlice
import com.fintracker.app.ui.components.CategorySpendPie
import com.fintracker.app.ui.components.ChipLabel
import com.fintracker.app.ui.components.SummaryPill
import com.fintracker.app.ui.components.TransactionRow
import com.fintracker.app.ui.components.categoryPalette
import com.fintracker.app.ui.components.paymentModeLabel
import com.fintracker.app.ui.util.MoneyFormat

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TransactionsScreen(
    onOpenTransaction: (Long) -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val monthScope = state.scope == HistoryScope.MONTH

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("History", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${state.periodLabel} · ${MoneyFormat.formatPaise(state.spentPaise)} spent",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (monthScope) viewModel.shiftMonth(-1) else viewModel.shiftYear(-1)
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Previous period"
                        )
                    }
                    IconButton(
                        onClick = {
                            if (monthScope) viewModel.shiftMonth(1) else viewModel.shiftYear(1)
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Next period"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            item(key = "scope") {
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = monthScope,
                        onClick = { viewModel.setScope(HistoryScope.MONTH) },
                        label = { Text("Month") }
                    )
                    FilterChip(
                        selected = !monthScope,
                        onClick = { viewModel.setScope(HistoryScope.YEAR) },
                        label = { Text("Year") }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        state.periodRangeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }

            item(key = "period-picker") {
                if (monthScope) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        items(state.availableMonths, key = { it.key }) { month ->
                            FilterChip(
                                selected = month.key == state.selectedMonth.key,
                                onClick = { viewModel.selectMonth(month) },
                                label = { Text(month.label) }
                            )
                        }
                    }
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        items(state.availableYears, key = { it }) { year ->
                            FilterChip(
                                selected = year == state.selectedYear.year,
                                onClick = { viewModel.selectYear(year) },
                                label = { Text(year.toString()) }
                            )
                        }
                    }
                }
            }

            item(key = "totals") {
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryPill(
                        label = "Spent",
                        value = MoneyFormat.formatPaise(state.spentPaise),
                        modifier = Modifier.weight(1f),
                        compact = true
                    )
                    SummaryPill(
                        label = "Credited",
                        value = MoneyFormat.formatPaise(state.creditedPaise),
                        modifier = Modifier.weight(1f),
                        compact = true
                    )
                    if (state.transferPaise > 0) {
                        SummaryPill(
                            label = "Transfers",
                            value = MoneyFormat.formatPaise(state.transferPaise),
                            modifier = Modifier.weight(1f),
                            compact = true
                        )
                    }
                }
            }

            if (monthScope && state.categorySpend.isNotEmpty()) {
                item(key = "pie") {
                    CategorySpendPie(
                        slices = state.categorySpend.mapIndexed { index, (name, amount) ->
                            CategorySlice(name, amount, categoryPalette(index))
                        },
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            }

            if (monthScope) {
                item(key = "search") {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        label = { Text("Search merchant, note, amount") },
                        singleLine = true
                    )
                }
            }

            if (monthScope) {
                stickyHeader(key = "filters") {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(vertical = 4.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = state.typeFilter == null,
                                onClick = { viewModel.setTypeFilter(null) },
                                label = { ChipLabel("All") },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        items(TransactionType.entries) { type ->
                            FilterChip(
                                selected = state.typeFilter == type,
                                onClick = {
                                    viewModel.setTypeFilter(
                                        if (state.typeFilter == type) null else type
                                    )
                                },
                                label = {
                                    ChipLabel(
                                        type.name.lowercase().replaceFirstChar { it.uppercase() }
                                    )
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        items(PaymentMode.entries.filter { it != PaymentMode.CASH }) { mode ->
                            FilterChip(
                                selected = state.modeFilter == mode,
                                onClick = {
                                    viewModel.setModeFilter(
                                        if (state.modeFilter == mode) null else mode
                                    )
                                },
                                label = { ChipLabel(paymentModeLabel(mode)) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }

                items(state.dayGroups, key = { it.dayKey }) { group ->
                    Text(
                        group.dayLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                    group.items.forEach { txn ->
                        Column(modifier = Modifier.clickable { onOpenTransaction(txn.id) }) {
                            TransactionRow(
                                txn = txn,
                                categoryName = txn.categoryId?.let { state.categories[it]?.name }
                            )
                        }
                    }
                }
                if (state.dayGroups.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No transactions in ${state.periodLabel}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            } else {
                items(state.monthSummaries, key = { it.period.key }) { summary ->
                    MonthSummaryRow(
                        summary = summary,
                        onClick = { viewModel.selectMonth(summary.period) }
                    )
                }
                if (state.monthSummaries.isEmpty()) {
                    item(key = "empty-year") {
                        Text(
                            "No transactions in ${state.periodLabel}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthSummaryRow(
    summary: MonthSummary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                summary.period.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                "${summary.count} txn · ${MoneyFormat.formatPaise(summary.creditedPaise)} in",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            MoneyFormat.formatPaise(summary.spentPaise),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error
        )
    }
}
