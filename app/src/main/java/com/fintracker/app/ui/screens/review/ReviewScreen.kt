package com.fintracker.app.ui.screens.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintracker.app.ui.components.TransactionRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onEdit: (Long) -> Unit,
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Needs review") }) }
    ) { padding ->
        if (state.items.isEmpty()) {
            Text(
                "All caught up. New ambiguous SMS alerts will appear here.",
                modifier = Modifier
                    .padding(padding)
                    .padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.items, key = { it.id }) { txn ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    TransactionRow(
                        txn = txn,
                        categoryName = txn.categoryId?.let { state.categories[it]?.name }
                    )
                    if (!txn.rawSmsSnippet.isNullOrBlank()) {
                        Text(
                            txn.rawSmsSnippet.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.confirm(txn.id) }) { Text("Confirm") }
                        OutlinedButton(onClick = { onEdit(txn.id) }) { Text("Edit") }
                        TextButton(onClick = { viewModel.dismiss(txn.id) }) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}
