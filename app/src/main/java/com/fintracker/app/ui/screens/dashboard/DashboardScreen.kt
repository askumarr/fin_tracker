package com.fintracker.app.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintracker.app.ui.components.ExpenseTrendChart
import com.fintracker.app.ui.components.SummaryPill
import com.fintracker.app.ui.components.TransactionRow
import com.fintracker.app.ui.util.MoneyFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onAdd: () -> Unit,
    onReview: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("FinTracker", style = MaterialTheme.typography.headlineMedium) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add transaction")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        state.monthLabel.ifBlank { "This month" },
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    )
                    Text(
                        state.monthRangeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = MoneyFormat.formatPaise(state.spentPaise),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "spent · ${MoneyFormat.formatPaise(state.creditedPaise)} credited",
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                    )
                }
            }

            if (state.reviewCount > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "${state.reviewCount} SMS transaction(s) need review",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                        .clickable(onClick = onReview)
                        .padding(14.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryPill(
                    label = "Spent",
                    value = MoneyFormat.formatPaise(state.spentPaise),
                    modifier = Modifier.weight(1f)
                )
                SummaryPill(
                    label = "Credited",
                    value = MoneyFormat.formatPaise(state.creditedPaise),
                    modifier = Modifier.weight(1f)
                )
            }

            if (state.categorySpend.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Text("By category", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                state.categorySpend.forEach { (name, amount) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(name)
                        Text(MoneyFormat.formatPaise(amount), fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (state.monthlyTrend.any { it.spentPaise > 0 }) {
                Spacer(modifier = Modifier.height(20.dp))
                ExpenseTrendChart(points = state.monthlyTrend)
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text("Recent", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))
            if (state.recent.isEmpty()) {
                Text(
                    "No transactions yet. Enable SMS auto-capture or add one manually.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                state.recent.forEach { txn ->
                    Box(modifier = Modifier.clickable { onOpenTransaction(txn.id) }) {
                        TransactionRow(
                            txn = txn,
                            categoryName = txn.categoryId?.let { state.categories[it]?.name }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(88.dp))
        }
    }
}
