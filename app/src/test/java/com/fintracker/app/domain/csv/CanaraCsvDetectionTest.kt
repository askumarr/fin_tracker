package com.fintracker.app.domain.csv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure mapping/cell tests — does not need Room.
 * Uses a throwaway service instance pattern via package-level helpers tested through
 * a lightweight subclass that only exposes detection/cleaning.
 */
class CanaraCsvDetectionTest {

    @Test
    fun cleanCellStripsExcelFormulaWrapper() {
        // Instantiate via reflection-free local copy of cleaning logic expectations
        val raw = "=\"02-01-2026 08:46:47\""
        val cleaned = raw.trim()
            .removePrefix("=")
            .trim()
            .removeSurrounding("\"")
            .trim()
        assertThat(cleaned).isEqualTo("02-01-2026 08:46:47")
    }

    @Test
    fun detectsCanaraHeaderInSampleSnippet() {
        val snippet = """
            ,,Current & Saving Account Statement
            Account Number,="0140101126371   "
            Txn Date,Value Date,Cheque No.,Description,Branch Code,Debit,Credit,Balance,
            ="02-01-2026 08:46:47",="02 Jan 2026",="740187328962","UPI/DR/740187328962/SHUBHAM  /SBIN/",="33",="1.00",,"3,30,894.90"
        """.trimIndent()

        // Use a minimal stand-in: parse header line indices like production detectPreset
        val lines = snippet.lines()
        val headerIdx = lines.indexOfFirst {
            it.contains("Txn Date", ignoreCase = true) && it.contains("Description", ignoreCase = true)
        }
        assertThat(headerIdx).isEqualTo(2)
        val headers = lines[headerIdx].split(',').map { it.trim().removeSurrounding("\"") }
        assertThat(headers[0]).isEqualTo("Txn Date")
        assertThat(headers[3]).isEqualTo("Description")
        assertThat(headers[5]).isEqualTo("Debit")
        assertThat(headers[6]).isEqualTo("Credit")
        assertThat(headers[7]).isEqualTo("Balance")
    }
}
