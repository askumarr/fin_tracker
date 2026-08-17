package com.fintracker.app.domain.csv

import com.fintracker.app.data.entity.ImportJobEntity
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.data.repository.ImportJobRepository
import com.fintracker.app.data.repository.TransactionRepository
import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.ReviewStatus
import com.fintracker.app.domain.model.TransactionSource
import com.fintracker.app.domain.model.TransactionType
import com.fintracker.app.domain.sms.SmsParseEngine
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class CsvColumnMapping(
    val dateIndex: Int,
    val descriptionIndex: Int,
    val amountIndex: Int? = null,
    val debitIndex: Int? = null,
    val creditIndex: Int? = null,
    val balanceIndex: Int? = null,
    val dateFormat: String = "dd/MM/yyyy",
    val hasHeader: Boolean = true,
    val paymentMode: PaymentMode = PaymentMode.UNKNOWN,
    val accountId: Long? = null
)

data class CsvImportReport(
    val added: Int,
    val skipped: Int,
    val failed: Int,
    val jobId: Long
)

@Singleton
class CsvImportService @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val importJobRepository: ImportJobRepository,
    private val parseEngine: SmsParseEngine
) {
    suspend fun import(
        fileName: String,
        inputStream: InputStream,
        mapping: CsvColumnMapping
    ): CsvImportReport {
        var added = 0
        var skipped = 0
        var failed = 0
        val jobId = importJobRepository.insert(
            ImportJobEntity(fileName = fileName)
        )

        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            var lineNumber = 0
            reader.lineSequence().forEach { rawLine ->
                lineNumber++
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach
                if (mapping.hasHeader && lineNumber == 1) return@forEach
                try {
                    val cols = parseCsvLine(line)
                    val dateRaw = cols.getOrNull(mapping.dateIndex)?.trim().orEmpty()
                    val description = cols.getOrNull(mapping.descriptionIndex)?.trim().orEmpty()
                    val amountPaise = resolveAmountPaise(cols, mapping) ?: run {
                        failed++
                        return@forEach
                    }
                    val type = resolveType(cols, mapping, amountPaise, description)
                    val absoluteAmount = kotlin.math.abs(amountPaise)
                    val occurredAt = parseDate(dateRaw, mapping.dateFormat) ?: run {
                        failed++
                        return@forEach
                    }
                    val balance = mapping.balanceIndex?.let { idx ->
                        cols.getOrNull(idx)?.let { parseMoneyToPaise(it) }
                    }
                    val dedupeKey = parseEngine.buildDedupeKey(
                        amountPaise = absoluteAmount,
                        occurredAt = occurredAt,
                        reference = null,
                        merchant = description.take(40),
                        sender = "CSV:$fileName",
                        balanceAfterPaise = balance
                    )
                    val entity = TransactionEntity(
                        amountPaise = absoluteAmount,
                        type = type,
                        paymentMode = mapping.paymentMode,
                        categoryId = transactionRepository.suggestCategory(description),
                        accountId = mapping.accountId,
                        merchant = description.ifBlank { null },
                        note = "Imported from $fileName",
                        occurredAt = occurredAt,
                        source = TransactionSource.CSV,
                        confidence = 1f,
                        reviewStatus = ReviewStatus.NONE,
                        balanceAfterPaise = balance,
                        dedupeKey = dedupeKey
                    )
                    val id = transactionRepository.insert(entity)
                    if (id <= 0L) skipped++ else {
                        // Check if it was a duplicate by seeing if same key existed conceptually
                        // insert returns row id; OnConflict IGNORE returns -1 on Room for conflict
                        if (id == -1L) skipped++ else added++
                    }
                } catch (_: Exception) {
                    failed++
                }
            }
        }

        importJobRepository.update(
            ImportJobEntity(
                id = jobId,
                fileName = fileName,
                finishedAt = System.currentTimeMillis(),
                addedCount = added,
                skippedCount = skipped,
                failedCount = failed
            )
        )
        return CsvImportReport(added, skipped, failed, jobId)
    }

    fun detectPreset(headers: List<String>): CsvColumnMapping? {
        val normalized = headers.map { it.trim().lowercase(Locale.US) }
        fun idx(vararg names: String): Int? =
            names.firstNotNullOfOrNull { name ->
                normalized.indexOfFirst { it.contains(name) }.takeIf { it >= 0 }
            }

        val date = idx("date", "txn date", "transaction date", "value date") ?: return null
        val desc = idx("description", "narration", "particulars", "remarks", "details") ?: return null
        val amount = idx("amount")
        val debit = idx("debit", "withdrawal", "dr")
        val credit = idx("credit", "deposit", "cr")
        if (amount == null && debit == null && credit == null) return null
        return CsvColumnMapping(
            dateIndex = date,
            descriptionIndex = desc,
            amountIndex = amount,
            debitIndex = debit,
            creditIndex = credit,
            balanceIndex = idx("balance", "closing"),
            hasHeader = true
        )
    }

    private fun resolveAmountPaise(cols: List<String>, mapping: CsvColumnMapping): Long? {
        mapping.debitIndex?.let { idx ->
            val debit = cols.getOrNull(idx)?.let { parseMoneyToPaise(it) }
            if (debit != null && debit != 0L) return -debit
        }
        mapping.creditIndex?.let { idx ->
            val credit = cols.getOrNull(idx)?.let { parseMoneyToPaise(it) }
            if (credit != null && credit != 0L) return credit
        }
        mapping.amountIndex?.let { idx ->
            return cols.getOrNull(idx)?.let { parseMoneyToPaise(it) }
        }
        return null
    }

    private fun resolveType(
        cols: List<String>,
        mapping: CsvColumnMapping,
        signedAmount: Long,
        description: String
    ): TransactionType {
        mapping.debitIndex?.let { idx ->
            val debit = cols.getOrNull(idx)?.let { parseMoneyToPaise(it) }
            if (debit != null && debit > 0) {
                return if (isCardBillNarration(description)) {
                    TransactionType.TRANSFER
                } else {
                    TransactionType.EXPENSE
                }
            }
        }
        mapping.creditIndex?.let { idx ->
            val credit = cols.getOrNull(idx)?.let { parseMoneyToPaise(it) }
            if (credit != null && credit > 0) return TransactionType.INCOME
        }
        return when {
            signedAmount < 0 && isCardBillNarration(description) -> TransactionType.TRANSFER
            signedAmount < 0 -> TransactionType.EXPENSE
            else -> TransactionType.INCOME
        }
    }

    private fun isCardBillNarration(description: String): Boolean =
        Regex(
            "(?i)(?:credit\\s*card\\s*(?:bill|pay)|cc\\s*(?:bill|payment)|card\\s*bill|" +
                "sbicard|bbps[^a-z0-9]{0,3}(?:.*card)?)"
        ).containsMatchIn(description)

    private fun parseMoneyToPaise(raw: String): Long? {
        val cleaned = raw.replace("\"", "")
            .replace("₹", "")
            .replace("INR", "", ignoreCase = true)
            .replace("Rs.", "", ignoreCase = true)
            .replace("Rs", "", ignoreCase = true)
            .replace(",", "")
            .trim()
        if (cleaned.isBlank() || cleaned == "-") return null
        // BigDecimal keeps the conversion exact; via Double, 4783179.35 * 100 truncates to ...34.
        return runCatching {
            BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        }.getOrNull()
    }

    private fun parseDate(raw: String, pattern: String): Long? {
        val formats = listOf(
            pattern,
            "dd/MM/yyyy",
            "dd-MM-yyyy",
            "yyyy-MM-dd",
            "dd/MM/yyyy HH:mm:ss",
            "dd-MM-yyyy HH:mm"
        )
        for (fmt in formats.distinct()) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                sdf.isLenient = false
                return sdf.parse(raw.trim())?.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result += current.toString()
        return result
    }
}
