package com.fintracker.app.ui.screens.addedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.TransactionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditScreen(
    onDone: () -> Unit,
    viewModel: AddEditViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.id == null) "Add transaction" else "Edit transaction")
                },
                actions = {
                    if (state.id != null) {
                        TextButton(onClick = viewModel::delete) { Text("Delete") }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.amountText,
                onValueChange = { viewModel.update { s -> s.copy(amountText = it) } },
                label = { Text("Amount (₹)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.type == TransactionType.EXPENSE,
                    onClick = { viewModel.update { it.copy(type = TransactionType.EXPENSE) } },
                    label = { Text("Expense") }
                )
                FilterChip(
                    selected = state.type == TransactionType.INCOME,
                    onClick = { viewModel.update { it.copy(type = TransactionType.INCOME) } },
                    label = { Text("Income") }
                )
                FilterChip(
                    selected = state.type == TransactionType.TRANSFER,
                    onClick = { viewModel.update { it.copy(type = TransactionType.TRANSFER) } },
                    label = { Text("Transfer") }
                )
            }
            Text("Payment mode", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentMode.entries.filter { it != PaymentMode.UNKNOWN }.forEach { mode ->
                    FilterChip(
                        selected = state.paymentMode == mode,
                        onClick = { viewModel.update { it.copy(paymentMode = mode) } },
                        label = { Text(mode.name.replace('_', ' ')) }
                    )
                }
            }
            OutlinedTextField(
                value = state.merchant,
                onValueChange = { viewModel.update { s -> s.copy(merchant = it) } },
                label = { Text("Merchant / party") },
                modifier = Modifier.fillMaxWidth()
            )
            DropdownField(
                label = "Category",
                options = categories.map { it.id to it.name },
                selectedId = state.categoryId,
                onSelect = { viewModel.update { s -> s.copy(categoryId = it) } }
            )
            DropdownField(
                label = "Account",
                options = accounts.map { it.id to it.name },
                selectedId = state.accountId,
                onSelect = { viewModel.update { s -> s.copy(accountId = it) } }
            )
            OutlinedTextField(
                value = state.note,
                onValueChange = { viewModel.update { s -> s.copy(note = it) } },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth()
            )
            if (!state.rawSmsSnippet.isNullOrBlank()) {
                Text("Source SMS", style = MaterialTheme.typography.labelLarge)
                Text(
                    state.rawSmsSnippet.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    options: List<Pair<Long, String>>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedId == null,
                onClick = { onSelect(null) },
                label = { Text("None") }
            )
        }
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (id, name) ->
                    FilterChip(
                        selected = selectedId == id,
                        onClick = { onSelect(id) },
                        label = { Text(name) }
                    )
                }
            }
        }
    }
}
