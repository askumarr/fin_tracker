package com.fintracker.app.domain.csv

import com.fintracker.app.data.entity.ImportJobEntity
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.data.repository.AccountRepository
import com.fintracker.app.data.repository.ImportJobRepository
import com.fintracker.app.data.repository.TransactionRepository
import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.ReviewStatus
import com.fintracker.app.domain.model.TransactionSource
import com.fintracker.app.domain.model.TransactionType
import com.fintracker.app.domain.sms.SmsParseEngine
import com.fintracker.app.domain.statement.StatementNarrationParser
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
    val dateFormat: String = "dd-MM-yyyy HH:mm:ss",
    val hasHeader: Boolean = true,
    /** 0-based line index of the header row; rows before it are skipped. */
    val headerLineIndex: Int = 0,
    val paymentMode: PaymentMode = PaymentMode.UNKNOWN,
    val accountId: Long? = null,
    val bankHint: String? = null
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
    private val accountRepository: AccountRepository,
    private val parseEngine: SmsParseEngine
) {
    /**
     * Auto-detect mapping from full file text (handles Canara preamble + Excel `="..."` cells).
     */
    fun detectMappingFromFile(text: String): CsvColumnMapping? {
        val lines = text.lineSequence().map { it.trimEnd() }.toList()
        val headerIdx = lines.indexOfFirst { line ->
            val cols = parseCsvLine(line).map { cleanCell(it).lowercase(Locale.US) }
            cols.any { it.contains("txn date") || it == "date" || it.contains("transaction date") } &&
                cols.any {
                    it.contains("description") || it.contains("narration") ||
                        it.contains("particulars")
                }
        }.takeIf { it >= 0 } ?: return null

        val headers = parseCsvLine(lines[headerIdx]).map { cleanCell(it) }
        val mapping = detectPreset(headers) ?: return null
        val bankHint = when {
            text.contains("CNRB", ignoreCase = true) ||
                text.contains("Canara", ignoreCase = true) -> "CANARA"
            text.contains("HDFC", ignoreCase = true) -> "HDFC"
            text.contains("ICICI", ignoreCase = true) -> "ICICI"
            text.contains("SBI", ignoreCase = true) -> "SBI"
            else -> null
        }
        val masked = Regex("""Account Number[,"=\s]*([Xx0-9*\s]+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
            ?.filter { it.isDigit() }
            ?.takeLast(4)
            ?.takeIf { it.length == 4 }
        return mapping.copy(
            headerLineIndex = headerIdx,
            dateFormat = preferDateFormat(lines, headerIdx, mapping),
            bankHint = when {
                bankHint != null && masked != null -> "$bankHint|$masked"
                else -> bankHint
            }
        )
    }

    suspend fun importAuto(fileName: String, inputStream: InputStream): CsvImportReport {
        val bytes = inputStream.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        val mapping = detectMappingFromFile(text)
            ?: error("Could not detect statement columns. Use manual mapping.")
        val (hint, mask) = splitBankHint(mapping.bankHint)
        val accountId = accountRepository.findOrCreate(hint, mask)
        return import(fileName, bytes.inputStream(), mapping.copy(accountId = accountId, bankHint = hint))
    }

    suspend fun import(
        fileName: String,
        inputStream: InputStream,
        mapping: CsvColumnMapping
    ): CsvImportReport {
        var added = 0
        var skipped = 0
        var failed = 0
        val jobId = importJobRepository.insert(ImportJobEntity(fileName = fileName))
        // Each existing row can absorb at most one statement line, so repeated same-day amounts
        // (three ₹10,000 SIP debits, say) are not collapsed into one.
        val claimedIds = mutableSetOf<Long>()

        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            var lineNumber = -1
            reader.lineSequence().forEach { rawLine ->
                lineNumber++
                if (lineNumber < mapping.headerLineIndex) return@forEach
                if (lineNumber == mapping.headerLineIndex && mapping.hasHeader) return@forEach
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach
                try {
                    val cols = parseCsvLine(line).map { cleanCell(it) }
                    if (cols.all { it.isBlank() }) return@forEach
                    val dateRaw = cols.getOrNull(mapping.dateIndex)?.trim().orEmpty()
                    if (dateRaw.isBlank() || dateRaw.equals("Txn Date", true)) return@forEach
                    val description = cols.getOrNull(mapping.descriptionIndex)?.trim().orEmpty()
                    if (description.isBlank() && cols.size < 4) return@forEach
                    val amountPaise = resolveAmountPaise(cols, mapping) ?: run {
                        failed++
                        return@forEach
                    }
                    val narration = StatementNarrationParser.parse(description)
                    val type = resolveType(cols, mapping, amountPaise, description, narration.typeHint)
                    val absoluteAmount = kotlin.math.abs(amountPaise)
                    val occurredAt = parseDate(dateRaw, mapping.dateFormat) ?: run {
                        failed++
                        return@forEach
                    }
                    val balance = mapping.balanceIndex?.let { idx ->
                        cols.getOrNull(idx)?.let { parseMoneyToPaise(it) }
                    }
                    val merchant = narration.merchant
                        ?: description.take(40).ifBlank { null }
                    val paymentMode = if (mapping.paymentMode != PaymentMode.UNKNOWN) {
                        mapping.paymentMode
                    } else {
                        narration.paymentMode
                    }
                    val dedupeKey = parseEngine.buildDedupeKey(
                        amountPaise = absoluteAmount,
                        occurredAt = occurredAt,
                        reference = narration.reference,
                        merchant = merchant,
                        sender = "CSV:$fileName",
                        balanceAfterPaise = balance
                    )
                    val entity = TransactionEntity(
                        amountPaise = absoluteAmount,
                        type = type,
                        paymentMode = paymentMode,
                        categoryId = transactionRepository.suggestCategory(
                            merchant = merchant,
                            rawText = description,
                            type = type
                        ),
                        accountId = mapping.accountId,
                        merchant = merchant,
                        note = "Imported from $fileName",
                        occurredAt = occurredAt,
                        source = TransactionSource.CSV,
                        confidence = 1f,
                        reviewStatus = ReviewStatus.NONE,
                        reference = narration.reference,
                        balanceAfterPaise = balance,
                        dedupeKey = dedupeKey
                    )
                    val (id, duplicate) = transactionRepository.insertDeduped(entity, claimedIds)
                    if (duplicate || id <= 0L) skipped++ else added++
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
        val normalized = headers.map { cleanCell(it).trim().lowercase(Locale.US) }
        fun idx(vararg names: String): Int? =
            names.firstNotNullOfOrNull { name ->
                normalized.indexOfFirst { it == name || it.contains(name) }.takeIf { it >= 0 }
            }

        val date = idx("txn date", "transaction date", "date", "value date") ?: return null
        val desc = idx("description", "narration", "particulars", "remarks", "details") ?: return null
        val amount = idx("amount")
        val debit = idx("debit", "withdrawal", "withdrawals", "dr")
        val credit = idx("credit", "deposit", "deposits", "cr")
        if (amount == null && debit == null && credit == null) return null
        return CsvColumnMapping(
            dateIndex = date,
            descriptionIndex = desc,
            amountIndex = amount,
            debitIndex = debit,
            creditIndex = credit,
            balanceIndex = idx("balance", "closing"),
            hasHeader = true,
            dateFormat = "dd-MM-yyyy HH:mm:ss"
        )
    }

    private fun preferDateFormat(
        lines: List<String>,
        headerIdx: Int,
        mapping: CsvColumnMapping
    ): String {
        val sample = lines.getOrNull(headerIdx + 1) ?: return mapping.dateFormat
        val cell = cleanCell(parseCsvLine(sample).getOrNull(mapping.dateIndex).orEmpty())
        return when {
            Regex("""\d{1,2}-\d{1,2}-\d{4}\s+\d{1,2}:\d{2}:\d{2}""").containsMatchIn(cell) ->
                "dd-MM-yyyy HH:mm:ss"
            Regex("""\d{1,2}/\d{1,2}/\d{4}\s+\d{1,2}:\d{2}:\d{2}""").containsMatchIn(cell) ->
                "dd/MM/yyyy HH:mm:ss"
            Regex("""\d{1,2}-\d{1,2}-\d{4}""").containsMatchIn(cell) -> "dd-MM-yyyy"
            Regex("""\d{1,2}/\d{1,2}/\d{4}""").containsMatchIn(cell) -> "dd/MM/yyyy"
            else -> mapping.dateFormat
        }
    }

    private fun splitBankHint(raw: String?): Pair<String?, String?> {
        if (raw.isNullOrBlank()) return null to null
        val parts = raw.split('|', limit = 2)
        return parts.getOrNull(0) to parts.getOrNull(1)
    }

    /** Strip Excel CSV wrappers like `="02-01-2026 08:46:47"` and quotes. */
    fun cleanCell(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("=")) s = s.removePrefix("=").trim()
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        return s.trim()
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
        description: String,
        typeHint: TransactionType?
    ): TransactionType {
        if (typeHint == TransactionType.TRANSFER || StatementNarrationParser.isCardBill(description)) {
            return TransactionType.TRANSFER
        }
        mapping.debitIndex?.let { idx ->
            val debit = cols.getOrNull(idx)?.let { parseMoneyToPaise(it) }
            if (debit != null && debit > 0) return TransactionType.EXPENSE
        }
        mapping.creditIndex?.let { idx ->
            val credit = cols.getOrNull(idx)?.let { parseMoneyToPaise(it) }
            if (credit != null && credit > 0) return TransactionType.INCOME
        }
        return if (signedAmount < 0) TransactionType.EXPENSE else TransactionType.INCOME
    }

    private fun parseMoneyToPaise(raw: String): Long? {
        val cleaned = cleanCell(raw)
            .replace("₹", "")
            .replace("INR", "", ignoreCase = true)
            .replace("Rs.", "", ignoreCase = true)
            .replace("Rs", "", ignoreCase = true)
            .replace(",", "")
            .trim()
        if (cleaned.isBlank() || cleaned == "-") return null
        return runCatching {
            BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        }.getOrNull()
    }

    private fun parseDate(raw: String, pattern: String): Long? {
        val cleaned = cleanCell(raw)
        val formats = listOf(
            pattern,
            "dd-MM-yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm:ss",
            "dd-MM-yyyy HH:mm",
            "dd/MM/yyyy HH:mm",
            "dd-MM-yyyy",
            "dd/MM/yyyy",
            "dd MMM yyyy",
            "yyyy-MM-dd"
        )
        for (fmt in formats.distinct()) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                sdf.isLenient = false
                return sdf.parse(cleaned.trim())?.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun parseCsvLine(line: String): List<String> {
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
