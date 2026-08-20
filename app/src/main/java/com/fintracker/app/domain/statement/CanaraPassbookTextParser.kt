package com.fintracker.app.domain.statement

import com.fintracker.app.domain.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Parses Canara Bank e-Passbook text (from PDF extraction) into statement rows.
 *
 * Works with line-oriented text and with flattened single-line PDF extractions text.
 */
object CanaraPassbookTextParser {

    data class Row(
        val occurredAt: Long,
        val description: String,
        val amountPaise: Long,
        val type: TransactionType,
        val balanceAfterPaise: Long?,
        val reference: String?
    )

    // Canara e-Passbook txn dates use dd-MM-yyyy. Embedded UPI stamps use dd/MM/yyyy — ignore those.
    private val dateToken = Regex("""(?<![0-9/])(\d{1,2}-\d{1,2}-\d{4})(?!\d)""")
    private val money = Regex("""\d{1,3}(?:,\d{2})*(?:,\d{3})*\.\d{2}|\d+\.\d{2}""")
    private val chq = Regex("""(?i)Chq:\s*([0-9A-Za-z]+)""")

    /** Every passbook row ends with "Chq: <n> <amount> <balance>", so amounts start after it. */
    private val chqMarker = Regex("""(?i)Chq:""")
    private val trailingTotals = Regex("""(?i)(?:Closing|Opening)\s+Balance\b[\s\S]*$""")

    fun parse(text: String): List<Row> {
        val normalized = text
            .replace(Regex("""(?i)page\s+\d+"""), " ")
            .replace(Regex("""(?i)--\s*\d+\s+of\s+\d+\s*--"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val dates = dateToken.findAll(normalized).toList()
        if (dates.isEmpty()) return emptyList()

        val rows = mutableListOf<Row>()
        for (idx in dates.indices) {
            val dateMatch = dates[idx]
            val occurredAt = parseDate(dateMatch.groupValues[1]) ?: continue
            val chunkStart = dateMatch.range.last + 1
            val chunkEnd = dates.getOrNull(idx + 1)?.range?.first ?: normalized.length
            if (chunkStart >= chunkEnd) continue
            // "Closing Balance 17,70,851.80" trails the last row and would be read as an amount.
            val chunk = normalized.substring(chunkStart, chunkEnd)
                .replace(trailingTotals, " ")
                .trim()
            if (chunk.startsWith("Particulars", ignoreCase = true)) continue

            // Amount and balance are the first two money tokens after the cheque-number column.
            // Falling back to the last two would pick up decimals printed inside the narration.
            val amountsFrom = chqMarker.find(chunk)?.range?.last?.plus(1) ?: 0
            val afterChq = money.findAll(chunk.substring(amountsFrom)).toList()
            val moneyMatches = if (afterChq.size >= 2) {
                afterChq.take(2).map { it.value to it.range.first + amountsFrom }
            } else {
                money.findAll(chunk).toList().takeLast(2).map { it.value to it.range.first }
            }
            if (moneyMatches.size < 2) continue
            val movementMatch = moneyMatches[0]
            val movement = parseMoney(movementMatch.first) ?: continue
            val balance = parseMoney(moneyMatches[1].first)

            var description = chunk.substring(0, movementMatch.second).trim()
            description = description
                .replace(Regex("""(?i)Date Particulars Deposits Withdrawals Balance"""), " ")
                .replace(Regex("""(?i)Opening Balance\s+[\d,]+\.\d{2}"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (description.length < 3) continue

            val reference = chq.find(description)?.groupValues?.getOrNull(1)
                ?.takeIf { it != "0" }
            description = description.replace(chq, " ").replace(Regex("\\s+"), " ").trim()

            val narration = StatementNarrationParser.parse(description)
            val type = when {
                narration.typeHint == TransactionType.TRANSFER ||
                    StatementNarrationParser.isCardBill(description) -> TransactionType.TRANSFER
                narration.typeHint == TransactionType.INCOME -> TransactionType.INCOME
                Regex("(?i)(?:UPI/CR|/CR/|NEFT\\s*CR|RTGS\\s*CR|\\bCR-)").containsMatchIn(description) ->
                    TransactionType.INCOME
                else -> TransactionType.EXPENSE
            }

            rows += Row(
                occurredAt = occurredAt,
                description = description,
                amountPaise = movement,
                type = type,
                balanceAfterPaise = balance,
                reference = reference ?: narration.reference
            )
        }
        return rows
    }

    private fun parseMoney(raw: String): Long? =
        runCatching {
            BigDecimal(raw.replace(",", ""))
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
        }.getOrNull()

    private fun parseDate(raw: String): Long? {
        val formats = listOf("dd-MM-yyyy", "dd/MM/yyyy", "dd-MMM-yyyy")
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                sdf.isLenient = false
                return sdf.parse(raw.trim())?.time
            } catch (_: Exception) {
            }
        }
        return null
    }
}
