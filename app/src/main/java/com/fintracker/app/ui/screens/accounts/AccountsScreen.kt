package com.fintracker.app.ui.screens.accounts

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
import com.fintracker.app.data.entity.AccountEntity
import com.fintracker.app.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountRepository: AccountRepository
) : ViewModel() {
    val accounts = accountRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun add(name: String, bankHint: String, masked: String) {
        viewModelScope.launch {
            val hint = bankHint.ifBlank { null } ?: name.ifBlank { null }
            if (hint.isNullOrBlank() && masked.isBlank()) return@launch
            val id = accountRepository.findOrCreate(hint, masked.ifBlank { null }) ?: return@launch
            if (name.isNotBlank()) {
                accountRepository.getById(id)?.let { existing ->
                    if (existing.name != name.trim()) {
                        accountRepository.update(existing.copy(name = name.trim()))
                    }
                }
            }
        }
    }

    fun mergeDuplicates() {
        viewModelScope.launch {
            val removed = accountRepository.mergeDuplicates()
            _message.value = if (removed == 0) {
                "No duplicate accounts found"
            } else {
                "Merged and removed $removed duplicate account(s)"
            }
        }
    }

    fun archive(account: AccountEntity) {
        viewModelScope.launch {
            accountRepository.update(account.copy(isArchived = true))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(viewModel: AccountsViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var bank by remember { mutableStateOf("") }
    var masked by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("Accounts") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Account name") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = bank,
                onValueChange = { bank = it },
                label = { Text("Bank (e.g. HDFC)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = masked,
                onValueChange = { masked = it },
                label = { Text("Masked number") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                viewModel.add(name, bank, masked)
                name = ""; bank = ""; masked = ""
            }) { Text("Add account") }

            TextButton(onClick = viewModel::mergeDuplicates) {
                Text("Merge duplicate accounts")
            }
            message?.let { Text(it) }

            LazyColumn {
                items(accounts, key = { it.id }) { acc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(acc.name)
                            Text(
                                listOfNotNull(acc.bankHint, acc.maskedNumber).joinToString(" · ")
                            )
                        }
                        TextButton(onClick = { viewModel.archive(acc) }) { Text("Archive") }
                    }
                }
            }
        }
    }
}
