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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fintracker.app.data.entity.CategoryEntity
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.data.repository.CategoryRepository
import com.fintracker.app.data.repository.TransactionRepository
import com.fintracker.app.domain.model.ReviewStatus
import com.fintracker.app.domain.sms.SenderLearningService
import com.fintracker.app.ui.components.TransactionRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewUiState(
    val items: List<TransactionEntity> = emptyList(),
    val categories: Map<Long, CategoryEntity> = emptyMap(),
    val selectedIds: Set<Long> = emptySet(),
    val feedback: String? = null,
    val undoDismissIds: List<Long> = emptyList()
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val senderLearning: SenderLearningService,
    categoryRepository: CategoryRepository
) : ViewModel() {
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val feedback = MutableStateFlow<String?>(null)
    private val undoDismissIds = MutableStateFlow<List<Long>>(emptyList())

    val uiState = combine(
        transactionRepository.observeNeedsReview(),
        categoryRepository.observeActive(),
        selectedIds,
        feedback,
        undoDismissIds
    ) { items, cats, selected, message, undo ->
        ReviewUiState(
            items = items,
            categories = cats.associateBy { it.id },
            selectedIds = selected.filter { id -> items.any { it.id == id } }.toSet(),
            feedback = message,
            undoDismissIds = undo
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    fun toggle(id: Long) {
        selectedIds.update { if (id in it) it - id else it + id }
    }

    fun selectAll(ids: List<Long>) {
        selectedIds.value = ids.toSet()
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun confirm(id: Long) {
        viewModelScope.launch {
            val txn = transactionRepository.getById(id) ?: return@launch
            transactionRepository.update(
                txn.copy(reviewStatus = ReviewStatus.CONFIRMED, confidence = 1f)
            )
            transactionRepository.rememberMerchantCategory(txn.merchant, txn.categoryId)
            senderLearning.rememberFromConfirm(txn)
            selectedIds.update { it - id }
            feedback.value = "Confirmed 1 transaction"
        }
    }

    fun dismiss(id: Long) {
        viewModelScope.launch {
            val txn = transactionRepository.getById(id) ?: return@launch
            transactionRepository.update(txn.copy(reviewStatus = ReviewStatus.DISMISSED))
            senderLearning.rememberFromDismiss(txn)
            selectedIds.update { it - id }
            undoDismissIds.value = listOf(id)
            feedback.value = "Dismissed 1 · tap Undo to restore"
        }
    }

    fun confirmSelected() {
        viewModelScope.launch {
            val ids = selectedIds.value.toList()
            if (ids.isEmpty()) return@launch
            ids.forEach { id ->
                val txn = transactionRepository.getById(id) ?: return@forEach
                transactionRepository.update(
                    txn.copy(reviewStatus = ReviewStatus.CONFIRMED, confidence = 1f)
                )
                transactionRepository.rememberMerchantCategory(txn.merchant, txn.categoryId)
                senderLearning.rememberFromConfirm(txn)
            }
            selectedIds.value = emptySet()
            feedback.value = "Confirmed ${ids.size} transaction(s)"
        }
    }

    fun dismissSelected() {
        viewModelScope.launch {
            val ids = selectedIds.value.toList()
            if (ids.isEmpty()) return@launch
            ids.forEach { id ->
                val txn = transactionRepository.getById(id) ?: return@forEach
                transactionRepository.update(txn.copy(reviewStatus = ReviewStatus.DISMISSED))
                senderLearning.rememberFromDismiss(txn)
            }
            undoDismissIds.value = ids
            selectedIds.value = emptySet()
            feedback.value = "Dismissed ${ids.size} · tap Undo to restore"
        }
    }

    fun undoDismiss() {
        viewModelScope.launch {
            val ids = undoDismissIds.value
            if (ids.isEmpty()) return@launch
            ids.forEach { id ->
                val txn = transactionRepository.getById(id) ?: return@forEach
                transactionRepository.update(
                    txn.copy(reviewStatus = ReviewStatus.NEEDS_REVIEW)
                )
            }
            undoDismissIds.value = emptyList()
            feedback.value = "Restored ${ids.size} to review"
        }
    }

    fun consumeFeedback() {
        feedback.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onEdit: (Long) -> Unit,
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.feedback) {
        val msg = state.feedback ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = msg,
            actionLabel = if (state.undoDismissIds.isNotEmpty()) "Undo" else null,
            withDismissAction = true
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDismiss()
        }
        viewModel.consumeFeedback()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.selectedIds.isEmpty()) {
                            "Needs review"
                        } else {
                            "${state.selectedIds.size} selected"
                        }
                    )
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                if (state.selectedIds.size == state.items.size) {
                                    viewModel.clearSelection()
                                } else {
                                    viewModel.selectAll(state.items.map { it.id })
                                }
                            }
                        ) {
                            Text(
                                if (state.selectedIds.size == state.items.size) "Clear" else "Select all"
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.selectedIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = viewModel::confirmSelected,
                        modifier = Modifier.weight(1f)
                    ) { Text("Confirm selected") }
                    OutlinedButton(
                        onClick = viewModel::dismissSelected,
                        modifier = Modifier.weight(1f)
                    ) { Text("Dismiss selected") }
                }
            }
        }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(
                        checked = txn.id in state.selectedIds,
                        onCheckedChange = { viewModel.toggle(txn.id) }
                    )
                    Column(modifier = Modifier.weight(1f)) {
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
}
