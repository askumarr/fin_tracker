package com.fintracker.app.ui.screens.backup

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fintracker.app.data.repository.TransactionRepository
import com.fintracker.app.domain.backup.BackupService
import com.fintracker.app.domain.model.TransactionSource
import com.fintracker.app.domain.sms.SmsInboxScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupService: BackupService,
    private val inboxScanner: SmsInboxScanner,
    private val transactionRepository: TransactionRepository
) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _rescanning = MutableStateFlow(false)
    val rescanning = _rescanning.asStateFlow()

    fun rescanInbox(days: Int) {
        if (_rescanning.value) return
        viewModelScope.launch {
            _rescanning.value = true
            _message.value = "Rescanning last $days days of SMS…"
            try {
                val result = inboxScanner.scanRecent(TimeUnit.DAYS.toMillis(days.toLong()))
                _message.value = "Scanned ${result.scanned} SMS · added ${result.imported} · " +
                    "already known ${result.duplicates}"
            } catch (e: Exception) {
                _message.value = e.message ?: "Rescan failed"
            } finally {
                _rescanning.value = false
            }
        }
    }

    fun rebuildFromSms(days: Int) {
        if (_rescanning.value) return
        viewModelScope.launch {
            _rescanning.value = true
            _message.value = "Rebuilding last $days days from SMS…"
            try {
                val windowMs = TimeUnit.DAYS.toMillis(days.toLong())
                val removed = transactionRepository.deleteSmsImportedSince(
                    System.currentTimeMillis() - windowMs
                )
                val result = inboxScanner.scanRecent(windowMs)
                val merged = transactionRepository.mergeSameDaySmsDuplicates()
                _message.value = "Removed $removed old SMS entries · re-added ${result.imported} " +
                    "from ${result.scanned} messages · merged $merged same-day duplicates"
            } catch (e: Exception) {
                _message.value = e.message ?: "Rebuild failed"
            } finally {
                _rescanning.value = false
            }
        }
    }

    fun clearPdfImports() {
        viewModelScope.launch {
            val removed = transactionRepository.deleteImportedFrom(TransactionSource.PDF)
            _message.value = "Removed $removed PDF-imported entry(s). Import the statement again."
        }
    }

    fun mergeDuplicatesNow() {
        viewModelScope.launch {
            val sms = transactionRepository.mergeSameDaySmsDuplicates()
            val statement = transactionRepository.mergeStatementSmsDuplicates()
            _message.value = "Merged $sms same-day SMS duplicate(s) and " +
                "$statement statement/SMS duplicate(s)"
        }
    }

    fun exportEncrypted(uri: Uri, passphrase: String, open: (Uri) -> java.io.OutputStream?) {
        viewModelScope.launch {
            try {
                open(uri)?.use { out ->
                    backupService.exportEncrypted(passphrase.toCharArray(), out)
                }
                _message.value = "Encrypted backup saved."
            } catch (e: Exception) {
                _message.value = e.message ?: "Backup failed"
            }
        }
    }

    fun importEncrypted(uri: Uri, passphrase: String, open: (Uri) -> java.io.InputStream?) {
        viewModelScope.launch {
            try {
                open(uri)?.use { input ->
                    backupService.importEncrypted(passphrase.toCharArray(), input)
                }
                _message.value = "Backup restored."
            } catch (e: Exception) {
                _message.value = e.message ?: "Restore failed"
            }
        }
    }

    fun exportCsv(uri: Uri, open: (Uri) -> java.io.OutputStream?) {
        viewModelScope.launch {
            try {
                open(uri)?.use { out -> backupService.exportPlainCsv(out) }
                _message.value = "CSV exported (unencrypted)."
            } catch (e: Exception) {
                _message.value = e.message ?: "CSV export failed"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(viewModel: BackupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val message by viewModel.message.collectAsStateWithLifecycle()
    val rescanning by viewModel.rescanning.collectAsStateWithLifecycle()
    var passphrase by remember { mutableStateOf("") }
    var rescanDays by remember { mutableIntStateOf(30) }
    var showRebuildDialog by remember { mutableStateOf(false) }
    var smsPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        smsPermissionGranted = result[Manifest.permission.READ_SMS] == true
        if (smsPermissionGranted) viewModel.rescanInbox(rescanDays)
    }

    val createEncrypted = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && passphrase.isNotBlank()) {
            viewModel.exportEncrypted(uri, passphrase) {
                context.contentResolver.openOutputStream(it)
            }
        }
    }
    val openEncrypted = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && passphrase.isNotBlank()) {
            viewModel.importEncrypted(uri, passphrase) {
                context.contentResolver.openInputStream(it)
            }
        }
    }
    val createCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            viewModel.exportCsv(uri) { context.contentResolver.openOutputStream(it) }
        }
    }

    if (showRebuildDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildDialog = false },
            title = { Text("Rebuild last $rescanDays days?") },
            text = {
                Text(
                    "Auto-captured SMS transactions from the last $rescanDays days will be deleted " +
                        "and re-imported with the current parsers. Manual edits to those entries " +
                        "will be lost. Manual and CSV entries are not affected."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRebuildDialog = false
                    viewModel.rebuildFromSms(rescanDays)
                }) { Text("Rebuild") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRebuildDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Backup & restore") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Rescan SMS inbox",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Re-reads past bank SMS with the latest parsers. Existing entries are skipped, " +
                    "so nothing is duplicated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 90, 180).forEach { days ->
                    FilterChip(
                        selected = rescanDays == days,
                        onClick = { rescanDays = days },
                        label = { Text("$days days") }
                    )
                }
            }
            Button(
                onClick = {
                    if (smsPermissionGranted) {
                        viewModel.rescanInbox(rescanDays)
                    } else {
                        smsPermissionLauncher.launch(
                            arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                        )
                    }
                },
                enabled = !rescanning,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (rescanning) "Rescanning…" else "Rescan last $rescanDays days") }

            OutlinedButton(
                onClick = { showRebuildDialog = true },
                enabled = !rescanning && smsPermissionGranted,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clear SMS entries & rebuild") }

            OutlinedButton(
                onClick = viewModel::mergeDuplicatesNow,
                enabled = !rescanning,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Merge duplicates (SMS + statement)") }

            OutlinedButton(
                onClick = viewModel::clearPdfImports,
                enabled = !rescanning,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Remove PDF-imported entries") }

            Text(
                "Rebuild deletes auto-captured SMS entries in the window and re-reads them, so " +
                    "wrongly classified ones are corrected. Manual and CSV entries are untouched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                "Data stays on this device. Encrypted backups use AES-GCM with your passphrase.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    createEncrypted.launch("fintracker-backup.ftbk")
                },
                enabled = passphrase.length >= 6,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export encrypted backup") }

            Button(
                onClick = {
                    openEncrypted.launch(arrayOf("application/octet-stream", "*/*"))
                },
                enabled = passphrase.length >= 6,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restore encrypted backup") }

            OutlinedButton(
                onClick = { createCsv.launch("fintracker-export.csv") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export plain CSV (unencrypted)") }

            Text(
                "Plain CSV is not encrypted — use only if you need spreadsheet access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
