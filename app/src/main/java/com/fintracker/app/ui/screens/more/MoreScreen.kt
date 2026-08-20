package com.fintracker.app.ui.screens.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fintracker.app.data.UserPreferences
import com.fintracker.app.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MoreViewModel @Inject constructor(
    private val preferences: UserPreferences,
    private val transactionRepository: TransactionRepository
) : ViewModel() {
    val autoCapture = preferences.autoCaptureEnabled
    val localLlm = preferences.localLlmEnabled

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun setAutoCapture(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoCapture(enabled) }
    }

    fun setLocalLlm(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setLocalLlmEnabled(enabled)
            if (enabled) {
                val n = transactionRepository.recategorizeUncategorized()
                _message.value = if (n == 0) {
                    "On-device categorizer on · new SMS will use it when rules miss"
                } else {
                    "On-device categorizer on · filled $n existing transaction(s)"
                }
            } else {
                _message.value = "On-device categorizer off · keyword rules only"
            }
        }
    }

    fun recategorizeNow() {
        viewModelScope.launch {
            val n = transactionRepository.recategorizeUncategorized()
            _message.value = "Filled $n uncategorized transaction(s)"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onAccounts: () -> Unit,
    onImport: () -> Unit,
    onBackup: () -> Unit,
    onReview: () -> Unit,
    onBudgets: () -> Unit,
    onRecurring: () -> Unit,
    viewModel: MoreViewModel = hiltViewModel()
) {
    val autoCapture by viewModel.autoCapture.collectAsStateWithLifecycle(initialValue = true)
    val localLlm by viewModel.localLlm.collectAsStateWithLifecycle(initialValue = true)
    val message by viewModel.message.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SMS auto-capture", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Parse incoming bank/UPI SMS automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoCapture,
                    onCheckedChange = viewModel::setAutoCapture
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("On-device AI categorizer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tiny local model fills a category when keyword rules miss. " +
                            "Nothing is sent off the phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = localLlm,
                    onCheckedChange = viewModel::setLocalLlm
                )
            }
            if (localLlm) {
                TextButton(
                    onClick = viewModel::recategorizeNow,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text("Fill uncategorized transactions")
                }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            ListItem(
                headlineContent = { Text("Accounts") },
                leadingContent = { Icon(Icons.Default.AccountBalance, null) },
                trailingContent = { TextButton(onClick = onAccounts) { Text("Open") } }
            )
            ListItem(
                headlineContent = { Text("Budgets") },
                supportingContent = { Text("Category monthly limits & alerts") },
                leadingContent = { Icon(Icons.Default.AccountBalance, null) },
                trailingContent = { TextButton(onClick = onBudgets) { Text("Open") } }
            )
            ListItem(
                headlineContent = { Text("Recurring") },
                supportingContent = { Text("SIP, rent, EMI, subscriptions") },
                leadingContent = { Icon(Icons.Default.RateReview, null) },
                trailingContent = { TextButton(onClick = onRecurring) { Text("Open") } }
            )
            ListItem(
                headlineContent = { Text("Import statement") },
                supportingContent = { Text("CSV or Canara e-Passbook PDF") },
                leadingContent = { Icon(Icons.Default.UploadFile, null) },
                trailingContent = { TextButton(onClick = onImport) { Text("Open") } }
            )
            ListItem(
                headlineContent = { Text("Backup & restore") },
                leadingContent = { Icon(Icons.Default.Backup, null) },
                trailingContent = { TextButton(onClick = onBackup) { Text("Open") } }
            )
            ListItem(
                headlineContent = { Text("SMS review queue") },
                leadingContent = { Icon(Icons.Default.RateReview, null) },
                trailingContent = { TextButton(onClick = onReview) { Text("Open") } }
            )
        }
    }
}
