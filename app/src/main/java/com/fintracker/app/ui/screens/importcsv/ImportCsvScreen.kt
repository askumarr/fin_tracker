package com.fintracker.app.ui.screens.importcsv

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fintracker.app.domain.csv.CsvColumnMapping
import com.fintracker.app.domain.csv.CsvImportReport
import com.fintracker.app.domain.csv.CsvImportService
import com.fintracker.app.domain.model.PaymentMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ImportCsvViewModel @Inject constructor(
    private val csvImportService: CsvImportService
) : ViewModel() {
    private val _report = MutableStateFlow<CsvImportReport?>(null)
    val report = _report.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    fun import(fileName: String, bytes: ByteArray, mapping: CsvColumnMapping) {
        viewModelScope.launch {
            _error.value = null
            _status.value = "Importing…"
            try {
                val result = csvImportService.import(fileName, bytes.inputStream(), mapping)
                _report.value = result
                _status.value =
                    "Added ${result.added}, skipped ${result.skipped}, failed ${result.failed}"
            } catch (e: Exception) {
                _error.value = e.message ?: "Import failed"
                _status.value = null
            }
        }
    }

    fun detectMapping(headerLine: String): CsvColumnMapping? {
        val headers = headerLine.split(',').map { it.trim().removeSurrounding("\"") }
        return csvImportService.detectPreset(headers)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportCsvScreen(viewModel: ImportCsvViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val report by viewModel.report.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    var dateIndex by remember { mutableStateOf("0") }
    var descIndex by remember { mutableStateOf("1") }
    var amountIndex by remember { mutableStateOf("2") }
    var debitIndex by remember { mutableStateOf("") }
    var creditIndex by remember { mutableStateOf("") }
    var dateFormat by remember { mutableStateOf("dd/MM/yyyy") }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment ?: "statement.csv"
        selectedName = name
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        pendingBytes = bytes
        val firstLine = bytes?.toString(Charsets.UTF_8)?.lineSequence()?.firstOrNull()
        if (firstLine != null) {
            viewModel.detectMapping(firstLine)?.let { mapping ->
                dateIndex = mapping.dateIndex.toString()
                descIndex = mapping.descriptionIndex.toString()
                amountIndex = mapping.amountIndex?.toString().orEmpty()
                debitIndex = mapping.debitIndex?.toString().orEmpty()
                creditIndex = mapping.creditIndex?.toString().orEmpty()
                dateFormat = mapping.dateFormat
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Import CSV") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Import bank statement CSV. Map columns if the preset is not detected.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { picker.launch(arrayOf("text/*", "text/csv", "*/*")) }) {
                Text("Choose CSV file")
            }
            selectedName?.let { Text("File: $it") }
            OutlinedTextField(
                value = dateIndex,
                onValueChange = { dateIndex = it },
                label = { Text("Date column index") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = descIndex,
                onValueChange = { descIndex = it },
                label = { Text("Description column index") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = amountIndex,
                onValueChange = { amountIndex = it },
                label = { Text("Amount column (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = debitIndex,
                onValueChange = { debitIndex = it },
                label = { Text("Debit column (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = creditIndex,
                onValueChange = { creditIndex = it },
                label = { Text("Credit column (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = dateFormat,
                onValueChange = { dateFormat = it },
                label = { Text("Date format") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val bytes = pendingBytes ?: return@Button
                    val mapping = CsvColumnMapping(
                        dateIndex = dateIndex.toIntOrNull() ?: 0,
                        descriptionIndex = descIndex.toIntOrNull() ?: 1,
                        amountIndex = amountIndex.toIntOrNull(),
                        debitIndex = debitIndex.toIntOrNull(),
                        creditIndex = creditIndex.toIntOrNull(),
                        dateFormat = dateFormat,
                        paymentMode = PaymentMode.UNKNOWN
                    )
                    viewModel.import(selectedName ?: "statement.csv", bytes, mapping)
                },
                enabled = pendingBytes != null
            ) { Text("Import") }

            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            report?.let {
                Text("Job #${it.jobId}: +${it.added} / skip ${it.skipped} / fail ${it.failed}")
            }
        }
    }
}
