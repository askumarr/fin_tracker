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
import androidx.compose.material3.OutlinedButton
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
import com.fintracker.app.domain.statement.PdfStatementImportService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ImportCsvViewModel @Inject constructor(
    private val csvImportService: CsvImportService,
    private val pdfImportService: PdfStatementImportService
) : ViewModel() {
    private val _report = MutableStateFlow<CsvImportReport?>(null)
    val report = _report.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    private val _detected = MutableStateFlow<String?>(null)
    val detected = _detected.asStateFlow()

    fun importAuto(fileName: String, bytes: ByteArray) {
        viewModelScope.launch {
            _error.value = null
            _status.value = "Importing…"
            _report.value = null
            try {
                val lower = fileName.lowercase()
                val isPdfFile = lower.endsWith(".pdf") ||
                    bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-"
                val result = when {
                    isPdfFile ->
                        pdfImportService.import(fileName, bytes.inputStream())
                    else -> csvImportService.importAuto(fileName, bytes.inputStream())
                }
                _report.value = result
                _status.value =
                    "Added ${result.added}, skipped ${result.skipped}, failed ${result.failed}"
            } catch (e: Exception) {
                _error.value = e.message ?: "Import failed"
                _status.value = null
            }
        }
    }

    fun importManual(fileName: String, bytes: ByteArray, mapping: CsvColumnMapping) {
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

    fun previewDetection(fileName: String, bytes: ByteArray) {
        if (fileName.lowercase().endsWith(".pdf") ||
            (bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-")
        ) {
            _detected.value = "Detected: PDF e-Passbook (Canara-style text statement)"
            return
        }
        val mapping = csvImportService.detectMappingFromFile(bytes.toString(Charsets.UTF_8))
        _detected.value = if (mapping != null) {
            "Detected CSV · date col ${mapping.dateIndex}, desc ${mapping.descriptionIndex}, " +
                "debit ${mapping.debitIndex}, credit ${mapping.creditIndex} · " +
                "header line ${mapping.headerLineIndex + 1}"
        } else {
            "Could not auto-detect columns — set them manually below"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportCsvScreen(viewModel: ImportCsvViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val report by viewModel.report.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val detected by viewModel.detected.collectAsStateWithLifecycle()

    var dateIndex by remember { mutableStateOf("0") }
    var descIndex by remember { mutableStateOf("3") }
    var amountIndex by remember { mutableStateOf("") }
    var debitIndex by remember { mutableStateOf("5") }
    var creditIndex by remember { mutableStateOf("6") }
    var dateFormat by remember { mutableStateOf("dd-MM-yyyy HH:mm:ss") }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isPdf by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "statement"
        selectedName = name
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        pendingBytes = bytes
        if (bytes == null) return@rememberLauncherForActivityResult
        isPdf = name.lowercase().endsWith(".pdf") ||
            (bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-")
        viewModel.previewDetection(name, bytes)
        if (!isPdf) {
            val text = bytes.toString(Charsets.UTF_8)
            val headerLine = text.lineSequence().firstOrNull { line ->
                line.contains("Txn Date", ignoreCase = true) &&
                    line.contains("Description", ignoreCase = true)
            }
            if (headerLine != null) {
                val headers = headerLine.split(',').map {
                    it.trim().removeSurrounding("\"").removePrefix("=").removeSurrounding("\"")
                }
                fun idx(vararg names: String): Int? =
                    names.firstNotNullOfOrNull { n ->
                        headers.indexOfFirst { it.contains(n, ignoreCase = true) }.takeIf { it >= 0 }
                    }
                idx("Txn Date", "Date")?.let { dateIndex = it.toString() }
                idx("Description", "Narration", "Particulars")?.let { descIndex = it.toString() }
                idx("Debit", "Withdrawal")?.let { debitIndex = it.toString() }
                idx("Credit", "Deposit")?.let { creditIndex = it.toString() }
                amountIndex = ""
                dateFormat = "dd-MM-yyyy HH:mm:ss"
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Import statement") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Import Canara (and similar) account CSV or e-Passbook PDF. " +
                    "Duplicates with SMS are skipped when amount/day/balance match.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = {
                    picker.launch(
                        arrayOf(
                            "text/*",
                            "text/csv",
                            "application/pdf",
                            "application/vnd.ms-excel",
                            "*/*"
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Choose CSV or PDF")
            }
            selectedName?.let { Text("File: $it") }
            detected?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }

            Button(
                onClick = {
                    val bytes = pendingBytes ?: return@Button
                    viewModel.importAuto(selectedName ?: "statement", bytes)
                },
                enabled = pendingBytes != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isPdf) "Import PDF" else "Auto-import CSV")
            }

            if (!isPdf) {
                Text(
                    "Manual column mapping (only if auto-detect fails)",
                    style = MaterialTheme.typography.titleSmall
                )
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
                OutlinedButton(
                    onClick = {
                        val bytes = pendingBytes ?: return@OutlinedButton
                        val mapping = CsvColumnMapping(
                            dateIndex = dateIndex.toIntOrNull() ?: 0,
                            descriptionIndex = descIndex.toIntOrNull() ?: 1,
                            amountIndex = amountIndex.toIntOrNull(),
                            debitIndex = debitIndex.toIntOrNull(),
                            creditIndex = creditIndex.toIntOrNull(),
                            dateFormat = dateFormat,
                            paymentMode = PaymentMode.UNKNOWN,
                            headerLineIndex = 0
                        )
                        viewModel.importManual(selectedName ?: "statement.csv", bytes, mapping)
                    },
                    enabled = pendingBytes != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import with manual mapping") }
            }

            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            report?.let {
                Text("Job #${it.jobId}: +${it.added} / skip ${it.skipped} / fail ${it.failed}")
            }
        }
    }
}
