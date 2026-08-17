package com.fintracker.app.ui.screens.addedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.data.repository.AccountRepository
import com.fintracker.app.data.repository.CategoryRepository
import com.fintracker.app.data.repository.TransactionRepository
import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.ReviewStatus
import com.fintracker.app.domain.model.TransactionSource
import com.fintracker.app.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddEditUiState(
    val id: Long? = null,
    val amountText: String = "",
    val type: TransactionType = TransactionType.EXPENSE,
    val paymentMode: PaymentMode = PaymentMode.UPI,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val merchant: String = "",
    val note: String = "",
    val occurredAt: Long = System.currentTimeMillis(),
    val source: TransactionSource = TransactionSource.MANUAL,
    val rawSmsSnippet: String? = null,
    val reference: String? = null,
    val saved: Boolean = false
)

@HiltViewModel
class AddEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository,
    accountRepository: AccountRepository
) : ViewModel() {
    private val txnId: Long? = savedStateHandle.get<Long>("txnId")?.takeIf { it > 0 }

    private val _state = MutableStateFlow(AddEditUiState(id = txnId))
    val state: StateFlow<AddEditUiState> = _state.asStateFlow()

    val categories = categoryRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accounts = accountRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (txnId != null) {
            viewModelScope.launch {
                transactionRepository.getById(txnId)?.let { load(it) }
            }
        }
    }

    private fun load(txn: TransactionEntity) {
        _state.value = AddEditUiState(
            id = txn.id,
            amountText = (txn.amountPaise / 100.0).toString(),
            type = txn.type,
            paymentMode = txn.paymentMode,
            categoryId = txn.categoryId,
            accountId = txn.accountId,
            merchant = txn.merchant.orEmpty(),
            note = txn.note.orEmpty(),
            occurredAt = txn.occurredAt,
            source = txn.source,
            rawSmsSnippet = txn.rawSmsSnippet,
            reference = txn.reference
        )
    }

    fun update(block: (AddEditUiState) -> AddEditUiState) {
        _state.update(block)
    }

    fun save() {
        viewModelScope.launch {
            val s = _state.value
            val amount = s.amountText.replace(",", "").toDoubleOrNull() ?: return@launch
            val paise = (amount * 100).toLong()
            val entity = TransactionEntity(
                id = s.id ?: 0,
                amountPaise = paise,
                type = s.type,
                paymentMode = s.paymentMode,
                categoryId = s.categoryId,
                accountId = s.accountId,
                merchant = s.merchant.ifBlank { null },
                note = s.note.ifBlank { null },
                occurredAt = s.occurredAt,
                source = s.source,
                confidence = 1f,
                reviewStatus = ReviewStatus.CONFIRMED,
                reference = s.reference,
                rawSmsSnippet = s.rawSmsSnippet,
                dedupeKey = s.id?.let { "manual-$it" } ?: "manual-${System.currentTimeMillis()}-$paise"
            )
            if (s.id == null) {
                transactionRepository.insert(entity)
            } else {
                transactionRepository.update(entity)
            }
            transactionRepository.rememberMerchantCategory(s.merchant, s.categoryId)
            _state.update { it.copy(saved = true) }
        }
    }

    fun delete() {
        viewModelScope.launch {
            _state.value.id?.let { transactionRepository.delete(it) }
            _state.update { it.copy(saved = true) }
        }
    }
}
