package com.fintracker.app.domain.recurring

import com.fintracker.app.data.repository.TransactionRepository
import com.fintracker.app.domain.model.TransactionType
import com.fintracker.app.ui.util.DateFormatters
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class RecurringPattern(
    val merchantKey: String,
    val displayName: String,
    val amountPaise: Long,
    val cadence: String,
    val occurrences: Int,
    val lastOccurredAt: Long,
    val categoryId: Long?
)

@Singleton
class RecurringDetectionService @Inject constructor(
    private val transactionRepository: TransactionRepository
) {
    suspend fun detect(lookbackMonths: Int = 6): List<RecurringPattern> {
        val end = System.currentTimeMillis()
        val startCal = Calendar.getInstance(DateFormatters.ist)
        startCal.add(Calendar.MONTH, -lookbackMonths)
        val all = transactionRepository.getAllForBackup()
            .filter {
                it.type == TransactionType.EXPENSE &&
                    it.occurredAt in startCal.timeInMillis..end &&
                    !it.merchant.isNullOrBlank()
            }

        val byMerchant = all.groupBy { normalizeMerchant(it.merchant!!) }
        return byMerchant.mapNotNull { (key, rows) ->
            if (rows.size < 3) return@mapNotNull null
            val amounts = rows.map { it.amountPaise }.sorted()
            val median = amounts[amounts.size / 2]
            val similar = rows.filter { abs(it.amountPaise - median) <= median / 20 + 100 }
            if (similar.size < 3) return@mapNotNull null

            val sorted = similar.sortedBy { it.occurredAt }
            val gaps = sorted.zipWithNext { a, b -> b.occurredAt - a.occurredAt }
            val avgGapDays = gaps.map { it / DAY_MS }.average()
            val cadence = when {
                avgGapDays in 25.0..35.0 -> "Monthly"
                avgGapDays in 6.0..8.0 -> "Weekly"
                avgGapDays in 13.0..16.0 -> "Bi-weekly"
                avgGapDays in 85.0..100.0 -> "Quarterly"
                else -> return@mapNotNull null
            }

            RecurringPattern(
                merchantKey = key,
                displayName = sorted.last().merchant ?: key,
                amountPaise = median,
                cadence = cadence,
                occurrences = similar.size,
                lastOccurredAt = sorted.last().occurredAt,
                categoryId = sorted.mapNotNull { it.categoryId }.groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.key
            )
        }.sortedByDescending { it.occurrences }
    }

    private fun normalizeMerchant(raw: String): String =
        raw.trim().lowercase(Locale.US)
            .replace(Regex("""\s+pvt\.?\s*ltd\.?"""), "")
            .replace(Regex("""\s+ltd\.?"""), "")
            .replace(Regex("""[^a-z0-9@._\- ]"""), "")
            .trim()

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
