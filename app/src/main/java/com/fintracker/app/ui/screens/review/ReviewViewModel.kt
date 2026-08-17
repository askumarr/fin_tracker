package com.fintracker.app.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintracker.app.data.entity.CategoryEntity
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.data.repository.CategoryRepository
import com.fintracker.app.data.repository.TransactionRepository
import com.fintracker.app.domain.model.ReviewStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewUiState(
    val items: List<TransactionEntity> = emptyList(),
    val categories: Map<Long, CategoryEntity> = emptyMap()
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository
) : ViewModel() {
    val uiState = combine(
        transactionRepository.observeNeedsReview(),
        categoryRepository.observeActive()
    ) { items, cats ->
        ReviewUiState(items, cats.associateBy { it.id })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    fun confirm(id: Long) {
        viewModelScope.launch {
            val txn = transactionRepository.getById(id) ?: return@launch
            transactionRepository.update(txn.copy(reviewStatus = ReviewStatus.CONFIRMED, confidence = 1f))
            transactionRepository.rememberMerchantCategory(txn.merchant, txn.categoryId)
        }
    }

    fun dismiss(id: Long) {
        viewModelScope.launch {
            val txn = transactionRepository.getById(id) ?: return@launch
            transactionRepository.update(txn.copy(reviewStatus = ReviewStatus.DISMISSED))
        }
    }
}
