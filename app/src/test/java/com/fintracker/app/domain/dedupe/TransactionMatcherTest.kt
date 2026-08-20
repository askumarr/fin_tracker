package com.fintracker.app.domain.dedupe

import com.fintracker.app.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransactionMatcherTest {

    private val day = 1_786_959_000_000L

    private fun candidate(
        amountLabel: String = "",
        type: TransactionType = TransactionType.EXPENSE,
        merchant: String? = null,
        reference: String? = null,
        balance: Long? = null,
        at: Long = day
    ) = TransactionMatcher.Candidate(
        type = type,
        merchant = merchant ?: amountLabel.ifBlank { null },
        reference = reference,
        balanceAfterPaise = balance,
        occurredAt = at
    )

    /** Statement rows pair with the SMS row nobody claimed yet, mirroring the repository loop. */
    private fun pair(
        statements: List<TransactionMatcher.Candidate>,
        existing: List<TransactionMatcher.Candidate>
    ): Int {
        val claimed = mutableSetOf<Int>()
        var merged = 0
        for (statement in statements) {
            val best = existing.withIndex()
                .filter { it.index !in claimed }
                .mapNotNull { (index, row) ->
                    TransactionMatcher.score(statement, row)?.let { index to it }
                }
                .maxByOrNull { it.second }
            if (best != null) {
                claimed.add(best.first)
                merged++
            }
        }
        return merged
    }

    @Test
    fun pairsTruncatedUpiMerchantAcrossSources() {
        val sms = candidate(merchant = "G RAHUL KUMA", at = 1_786_959_143_654L)
        val csv = candidate(merchant = "G RAHUL K", reference = "214728741581", at = 1_786_959_083_000L)
        assertThat(TransactionMatcher.score(csv, sms)).isNotNull()
    }

    @Test
    fun pairsCardBillTransferWithCredClub() {
        val sms = candidate(type = TransactionType.TRANSFER, merchant = "Credit card payment")
        val csv = candidate(
            type = TransactionType.TRANSFER,
            merchant = "CRED Club",
            reference = "659330709883"
        )
        assertThat(TransactionMatcher.score(csv, sms)).isNotNull()
    }

    @Test
    fun pairsStatementMerchantWithMerchantlessSms() {
        val sms = candidate(merchant = null, at = 1_787_065_420_000L)
        val csv = candidate(merchant = "ICICI Pru", at = 1_787_069_521_000L)
        assertThat(TransactionMatcher.score(csv, sms)).isNotNull()
    }

    @Test
    fun keepsDistinctMerchantsSeparate() {
        val sms = candidate(merchant = "Golden Dragon Chef")
        val csv = candidate(merchant = "OBULESWAR")
        assertThat(TransactionMatcher.score(csv, sms)).isNull()
    }

    @Test
    fun keepsDifferentReferencesSeparate() {
        val sms = candidate(merchant = "RAM REDDY", reference = "390867023565")
        val csv = candidate(merchant = "RAM REDDY", reference = "029644276678")
        assertThat(TransactionMatcher.score(csv, sms)).isNull()
    }

    @Test
    fun pairsWhenSmsQuotesUtrAndStatementQuotesRrn() {
        val sms = candidate(
            type = TransactionType.INCOME,
            merchant = "XXXX6371 on 27/07/2026 towards RTGS by Sender FIDELITY STOCK",
            reference = "52026072700700408"
        )
        val csv = candidate(
            type = TransactionType.INCOME,
            merchant = "FIDELITY STOCK PLAN SERVICES LLC",
            reference = "919102323136"
        )
        assertThat(TransactionMatcher.referenceVerdict(csv.reference, sms.reference))
            .isEqualTo(TransactionMatcher.Verdict.UNKNOWN)
        assertThat(TransactionMatcher.score(csv, sms)).isNotNull()
    }

    @Test
    fun treatsBankPrefixedUtrAsSameReference() {
        assertThat(
            TransactionMatcher.referenceVerdict("HDFCR52026072888542568", "52026072888542568")
        ).isEqualTo(TransactionMatcher.Verdict.SAME)
        assertThat(TransactionMatcher.referenceVerdict("CITIN26702887448", "26702887448"))
            .isEqualTo(TransactionMatcher.Verdict.SAME)
        // Two distinct 12-digit UPI references are still two different payments.
        assertThat(TransactionMatcher.referenceVerdict("390867023565", "029644276678"))
            .isEqualTo(TransactionMatcher.Verdict.DIFFERENT)
    }

    @Test
    fun pairsAggregatorNameWithRealMerchant() {
        // Statement names the switch (CRED pay) for an order the SMS attributes to Swiggy.
        val sms = candidate(merchant = "Swiggy")
        val csv = candidate(merchant = "CREDPAYSW", reference = "653130267204")
        assertThat(TransactionMatcher.isPaymentAggregator("CREDPAYSW")).isTrue()
        assertThat(TransactionMatcher.score(csv, sms)).isNotNull()
    }

    @Test
    fun pairsKiwiCardBillWithTransfer() {
        val sms = candidate(type = TransactionType.TRANSFER, merchant = "Credit card payment")
        val csv = candidate(merchant = "Gokiwi Te", reference = "616482986160")
        assertThat(TransactionMatcher.score(csv, sms)).isNotNull()
    }

    @Test
    fun keepsRealMerchantsApartEvenWithAggregatorVocabulary() {
        assertThat(TransactionMatcher.isPaymentAggregator("Paytm Services")).isTrue()
        assertThat(TransactionMatcher.isPaymentAggregator("Ms Aishwarya")).isFalse()
        val sms = candidate(merchant = "Ms Aishwarya")
        val csv = candidate(merchant = "Kusum Kir", reference = "655904453756")
        assertThat(TransactionMatcher.score(csv, sms)).isNull()
    }

    @Test
    fun keepsDifferentBalancesSeparate() {
        val sms = candidate(merchant = null, balance = 4_783_179_35L)
        val csv = candidate(merchant = "GROWWINVESTTECHPR", balance = 4_683_179_35L)
        assertThat(TransactionMatcher.score(csv, sms)).isNull()
    }

    @Test
    fun keepsIncomeApartFromExpense() {
        val sms = candidate(type = TransactionType.INCOME, merchant = "SHIVAMASH")
        val csv = candidate(type = TransactionType.EXPENSE, merchant = "SHIVAMASH")
        assertThat(TransactionMatcher.score(csv, sms)).isNull()
    }

    @Test
    fun prefersReferenceMatchOverMerchantMatch() {
        val exact = candidate(merchant = "RAM REDDY", reference = "390867023565")
        val loose = candidate(merchant = "RAM REDDY")
        val incoming = candidate(merchant = "RAM REDDY CHICKEN", reference = "390867023565")
        val exactScore = TransactionMatcher.score(incoming, exact)!!
        val looseScore = TransactionMatcher.score(incoming, loose)!!
        assertThat(exactScore).isGreaterThan(looseScore)
    }

    @Test
    fun threeSameDayNachDebitsStayThree() {
        // Three ₹10,000 SIP debits: SMS captured all three without a merchant, the statement
        // restates them with merchants. Each statement row must claim its own SMS row.
        val sms = listOf(
            candidate(merchant = null, at = day),
            candidate(merchant = null, at = day + 11_000),
            candidate(merchant = null, at = day + 45_000)
        )
        val statement = listOf(
            candidate(merchant = "GROWWINVESTTECHPR YSPWZWE18GALJ8 CNRB702", at = day),
            candidate(merchant = "GROWWINVESTTECHPR KILYEQT35RIBSM CNRB702", at = day + 16_000),
            candidate(merchant = "IndianClearingCorp 0000WDZJPB3OWGYSJQ262", at = day + 156_000)
        )
        assertThat(pair(statement, sms)).isEqualTo(3)
    }

    @Test
    fun extraStatementRowSurvivesWhenSmsMissedIt() {
        val sms = listOf(candidate(merchant = null))
        val statement = listOf(
            candidate(merchant = "GROWWINVESTTECHPR", at = day),
            candidate(merchant = "IndianClearingCorp", at = day + 60_000)
        )
        // Only one pairing is possible, so the second statement row is inserted as a new row.
        assertThat(pair(statement, sms)).isEqualTo(1)
    }
}
