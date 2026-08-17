package com.fintracker.app.ui.screens.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fintracker.app.data.entity.BudgetEntity
import com.fintracker.app.data.repository.BudgetRepository
import com.fintracker.app.data.repository.CategoryRepository
import com.fintracker.app.ui.components.ChipLabel
import com.fintracker.app.ui.util.MoneyFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BudgetsViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    categoryRepository: CategoryRepository
) : ViewModel() {
    val statuses = budgetRepository.observeStatusesForCurrentMonth()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = categoryRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(categoryId: Long, limitRupees: String) {
        val rupees = limitRupees.replace(",", "").toDoubleOrNull() ?: return
        viewModelScope.launch {
            budgetRepository.upsert(
                BudgetEntity(
                    categoryId = categoryId,
                    monthKey = "*",
                    limitPaise = (rupees * 100).toLong(),
                    alertRatio = 0.8f
                )
            )
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { budgetRepository.delete(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BudgetsScreen(viewModel: BudgetsViewModel = hiltViewModel()) {
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var selectedCategoryId by remember { mutableStateOf<Long?>(null) }
    var limitText by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("Budgets") }) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "form") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Monthly limits per category (IST calendar month). Alerts at 80% of limit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Category", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.forEach { cat ->
                            FilterChip(
                                selected = selectedCategoryId == cat.id,
                                onClick = {
                                    selectedCategoryId =
                                        if (selectedCategoryId == cat.id) null else cat.id
                                },
                                label = { ChipLabel(cat.name) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = limitText,
                        onValueChange = { limitText = it },
                        label = { Text("Monthly limit (₹)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            selectedCategoryId?.let { viewModel.save(it, limitText) }
                            limitText = ""
                        },
                        enabled = selectedCategoryId != null && limitText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save budget") }
                }
            }

            items(statuses, key = { it.budget.id }) { status ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(status.categoryName, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { viewModel.delete(status.budget.id) }) {
                            Text("Remove")
                        }
                    }
                    Text(
                        "${MoneyFormat.formatPaise(status.spentPaise)} / " +
                            MoneyFormat.formatPaise(status.budget.limitPaise),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { status.ratio.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        color = when {
                            status.overLimit -> MaterialTheme.colorScheme.error
                            status.overAlert -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    if (status.overLimit) {
                        Text(
                            "Over budget",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else if (status.overAlert) {
                        Text(
                            "Near limit (≥80%)",
                            color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
