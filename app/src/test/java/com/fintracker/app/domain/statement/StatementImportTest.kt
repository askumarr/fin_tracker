package com.fintracker.app.domain.statement

import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StatementNarrationParserTest {

    @Test
    fun extractsUpiDebitMerchantAndRef() {
        val n = StatementNarrationParser.parse(
            "UPI/DR/740187328962/SHUBHAM  /SBIN/**r0641@ybl/Payment //YBLda17bc09b8044422b8e1b2c566d8f2d8/02/01/2026 08:46:47"
        )
        assertThat(n.merchant).isEqualTo("SHUBHAM")
        assertThat(n.reference).isEqualTo("740187328962")
        assertThat(n.paymentMode).isEqualTo(PaymentMode.UPI)
        assertThat(n.typeHint).isEqualTo(TransactionType.EXPENSE)
    }

    @Test
    fun extractsUpiCreditMerchant() {
        val n = StatementNarrationParser.parse(
            "UPI/CR/728384187483/AMIT KUMAR/HDFC/**16349@pz/remark//PAZ23510a265f184dcda7d0358932420a03"
        )
        assertThat(n.merchant).isEqualTo("AMIT KUMAR")
        assertThat(n.typeHint).isEqualTo(TransactionType.INCOME)
    }

    /** PDF line-wraps put spaces around the hyphens the CSV writes tight; both must parse. */
    @Test
    fun extractsWireTransferCounterpartyAndUtrFromWrappedText() {
        val n = StatementNarrationParser.parse(
            "RTGS CR- HDFCR52026072888542568- HDFC0000240-TMF REDEMPTION POOL A/C-- " +
                "//TATA MF - 14555913-//TATA MF - 14555913"
        )
        assertThat(n.merchant).isEqualTo("TMF REDEMPTION POOL A/C")
        assertThat(n.reference).isEqualTo("HDFCR52026072888542568")
        assertThat(n.typeHint).isEqualTo(TransactionType.INCOME)
        assertThat(n.paymentMode).isEqualTo(PaymentMode.NET_BANKING)
    }

    @Test
    fun extractsWireTransferCounterpartyFromTightCsvText() {
        val n = StatementNarrationParser.parse(
            "NEFT Cr-HDFCH01144826417-HDFC0000240-SILABS INDIA PRIVATE LIMITED--0001-" +
                "DOMNEFT01 SILABS INDIA PRIVATE LIMI-Salary"
        )
        assertThat(n.merchant).isEqualTo("SILABS INDIA PRIVATE LIMITED")
        assertThat(n.reference).isEqualTo("HDFCH01144826417")
    }

    /** A hyphen inside the name ("2025-26") must not be read as a field separator. */
    @Test
    fun keepsHyphenInsideCounterpartyName() {
        val n = StatementNarrationParser.parse(
            "NEFT Cr-SBIN226009371420-SBIN0000TBU-ITDTAX REFUND 2025-26 DXNPK6002H--/ATTN//INB"
        )
        assertThat(n.merchant).isEqualTo("ITDTAX REFUND 2025-26 DXNPK6002H")
    }

    @Test
    fun extractsIbNeftCounterparty() {
        val n = StatementNarrationParser.parse(
            "IB NEFT DR CNRBH00148610576 PANKAJ KUMAR SBIN0001216 33832240330 LAND PURCHASE"
        )
        assertThat(n.merchant).isEqualTo("PANKAJ KUMAR")
        assertThat(n.reference).isEqualTo("CNRBH00148610576")
        assertThat(n.typeHint).isEqualTo(TransactionType.EXPENSE)
    }

    @Test
    fun dropsMandateIdsFromNachMerchant() {
        val n = StatementNarrationParser.parse(
            "NACH GROWWINVESTTECHPR KILYEQT35RIBSM CNRB7020705230007293"
        )
        assertThat(n.merchant).isEqualTo("GROWWINVESTTECHPR")
    }

    @Test
    fun credClubIsTransfer() {
        val n = StatementNarrationParser.parse(
            "UPI/DR/637916320381/CRED Club/UTIB/**.club@axisb/payment //ACD..."
        )
        assertThat(n.typeHint).isEqualTo(TransactionType.TRANSFER)
        assertThat(StatementNarrationParser.isCardBill(n.merchant + " CRED Club")).isTrue()
    }
}

class CanaraPassbookTextParserTest {

    @Test
    fun parsesWithdrawalAndDepositRows() {
        val text = """
            Statement for A/c XXXXXXXXX6371 for the period 18-Jul-2026 to 17-Aug-2026
            Date Particulars Deposits Withdrawals Balance
            Opening Balance 1,42,971.76
            17-07-2026
            UPI/DR/870304488228/YANDAM URI/YESB/**AO8JY@PTY/PAYMENT
            Chq: 870304488228
            120.00 1,42,851.76
            21-07-2026
            RTGS CR-ICICR22026072117617936-ICIC0099999-ICICI BANK LTD
            Chq: 0
            14,89,971.18 16,07,483.27
            page 1
        """.trimIndent()

        val rows = CanaraPassbookTextParser.parse(text)
        assertThat(rows.size).isAtLeast(2)
        val first = rows.first { it.reference == "870304488228" || it.description.contains("YANDAM") }
        assertThat(first.amountPaise).isEqualTo(12_000L)
        assertThat(first.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(first.balanceAfterPaise).isEqualTo(14_285_176L)

        val deposit = rows.first { it.description.contains("RTGS", ignoreCase = true) }
        assertThat(deposit.amountPaise).isEqualTo(148_997_118L)
        assertThat(deposit.type).isEqualTo(TransactionType.INCOME)
        assertThat(deposit.balanceAfterPaise).isEqualTo(160_748_327L)
    }

    /** The trailing "Closing Balance" used to be read as the last row's amount. */
    @Test
    fun ignoresClosingBalanceOnFinalRow() {
        val text =
            "15-08-2026 UPI/DR/659330709883/CRED CLUB/UTIB/**.CLUB@AXISB/PAYMENT " +
                "Chq: 659330709883 1,203.49 17,71,051.80 " +
                "17-08-2026 UPI/DR/214728741581/G RAHUL K/HDFC/**69327@YBL/PAYMENT " +
                "Chq: 214728741581 200.00 17,70,851.80 Closing Balance 17,70,851.80 page 7"
        val rows = CanaraPassbookTextParser.parse(text)
        assertThat(rows).hasSize(2)
        val last = rows.last()
        assertThat(last.amountPaise).isEqualTo(20_000L)
        assertThat(last.balanceAfterPaise).isEqualTo(177_085_180L)
        assertThat(last.reference).isEqualTo("214728741581")
    }

    /** Decimals printed inside the narration must not be mistaken for the amount. */
    @Test
    fun readsAmountAfterChequeColumnNotFromNarration() {
        val text = "28-07-2026 RTGS 00.00 TO 11.00 ABOVE 5L SC Chq: 0 58.00 37,83,121.35"
        val rows = CanaraPassbookTextParser.parse(text)
        assertThat(rows).hasSize(1)
        assertThat(rows[0].amountPaise).isEqualTo(5_800L)
        assertThat(rows[0].balanceAfterPaise).isEqualTo(378_312_135L)
    }

    @Test
    fun parsesNachRowWithEmptyChequeColumn() {
        val text =
            "12-08-2026 NACH GROWWINVESTTECHPR KILYEQT35RIBSM CNRB7020705230007293 " +
                "Chq: 10,000.00 17,88,623.39"
        val rows = CanaraPassbookTextParser.parse(text)
        assertThat(rows).hasSize(1)
        assertThat(rows[0].amountPaise).isEqualTo(1_000_000L)
        assertThat(rows[0].type).isEqualTo(TransactionType.EXPENSE)
    }

    @Test
    fun parsesFlattenedPdfStyleText() {
        val text =
            "Opening Balance 1,42,971.76 17-07-2026 UPI/DR/870304488228/YANDAM URI/YESB " +
                "Chq: 870304488228 120.00 1,42,851.76 18-07-2026 UPI/DR/100131682021/ICICI PRU " +
                "Chq: 100131682021 3,959.00 1,38,892.76"
        val rows = CanaraPassbookTextParser.parse(text)
        assertThat(rows).hasSize(2)
        assertThat(rows[0].amountPaise).isEqualTo(12_000L)
        assertThat(rows[1].amountPaise).isEqualTo(395_900L)
    }
}
