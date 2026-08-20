package com.fintracker.app.domain.statement

import com.fintracker.app.data.entity.ImportJobEntity
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.data.repository.AccountRepository
import com.fintracker.app.data.repository.ImportJobRepository
import com.fintracker.app.data.repository.TransactionRepository
import com.fintracker.app.domain.csv.CsvImportReport
import com.fintracker.app.domain.model.ReviewStatus
import com.fintracker.app.domain.model.TransactionSource
import com.fintracker.app.domain.sms.SmsParseEngine
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfStatementImportService @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val importJobRepository: ImportJobRepository,
    private val accountRepository: AccountRepository,
    private val parseEngine: SmsParseEngine
) {
    suspend fun import(fileName: String, inputStream: InputStream): CsvImportReport {
        val bytes = inputStream.readBytes()
        val text = PdfTextExtractor.extractText(bytes)
        if (text.length < 40) {
            error("Could not read text from this PDF. Export CSV from net banking if possible.")
        }
        val rows = CanaraPassbookTextParser.parse(text)
        if (rows.isEmpty()) {
            error(
                "No transactions found in PDF. Supported today: Canara e-Passbook. " +
                    "Try CSV export for other banks."
            )
        }

        val masked = Regex("""(?i)A/c\s+([Xx0-9*]+)""").find(text)
            ?.groupValues?.getOrNull(1)
            ?.filter { it.isDigit() }
            ?.takeLast(4)
            ?.takeIf { it.length == 4 }
        val accountId = accountRepository.findOrCreate("CANARA", masked)

        var added = 0
        var skipped = 0
        var failed = 0
        val jobId = importJobRepository.insert(ImportJobEntity(fileName = fileName))
        val claimedIds = mutableSetOf<Long>()

        for (row in rows) {
            try {
                val narration = StatementNarrationParser.parse(row.description)
                val merchant = narration.merchant ?: row.description.take(40)
                val dedupeKey = parseEngine.buildDedupeKey(
                    amountPaise = row.amountPaise,
                    occurredAt = row.occurredAt,
                    reference = row.reference ?: narration.reference,
                    merchant = merchant,
                    sender = "PDF:$fileName",
                    balanceAfterPaise = row.balanceAfterPaise
                )
                val entity = TransactionEntity(
                    amountPaise = row.amountPaise,
                    type = row.type,
                    paymentMode = narration.paymentMode,
                    categoryId = transactionRepository.suggestCategory(
                        merchant = merchant,
                        rawText = row.description,
                        type = row.type
                    ),
                    accountId = accountId,
                    merchant = merchant,
                    note = "Imported from $fileName",
                    occurredAt = row.occurredAt,
                    source = TransactionSource.PDF,
                    confidence = 0.9f,
                    reviewStatus = ReviewStatus.NONE,
                    reference = row.reference ?: narration.reference,
                    balanceAfterPaise = row.balanceAfterPaise,
                    dedupeKey = dedupeKey
                )
                val (id, duplicate) = transactionRepository.insertDeduped(entity, claimedIds)
                if (duplicate || id <= 0L) skipped++ else added++
            } catch (_: Exception) {
                failed++
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
}
