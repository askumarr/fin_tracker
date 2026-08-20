package com.fintracker.app.domain.dedupe

import com.fintracker.app.domain.model.TransactionType
import java.util.Locale

/**
 * Decides whether a bank-statement row (CSV/PDF) and an existing row describe the same
 * transaction. Amount and IST day are matched by the caller; this adds the softer signals.
 *
 * The two sources describe one payment differently:
 *   SMS      "G RAHUL KUMA"          (truncated merchant, often no reference, mode UNKNOWN)
 *   CSV      "G RAHUL K"             (own truncation, UPI reference, mode UPI)
 *   SMS      "Credit card payment"   (bill settlement seen from the card side)
 *   CSV      "CRED Club"             (same settlement seen from the bank side)
 */
object TransactionMatcher {

    /** Merchant text that carries no identifying information. */
    fun isGenericMerchant(merchant: String?): Boolean {
        val m = merchant?.trim().orEmpty()
        if (m.isEmpty()) return true
        val lower = m.lowercase(Locale.US)
        return lower in GENERIC_MERCHANTS ||
            lower.length < 3 ||
            lower.all { !it.isLetter() } ||
            isPaymentAggregator(m)
    }

    /**
     * Statements often name the payment switch instead of the shop ("CREDPAYSW" for a Swiggy order
     * paid through CRED). That name identifies no counterparty, so it must not block a match.
     */
    fun isPaymentAggregator(merchant: String?): Boolean {
        val squashed = normalize(merchant).replace(" ", "")
        if (squashed.isBlank()) return false
        return AGGREGATORS.any { squashed.startsWith(it) || squashed == it }
    }

    fun isCardBillMerchant(merchant: String?): Boolean {
        val lower = merchant?.lowercase(Locale.US).orEmpty()
        if (lower.isBlank()) return false
        return CARD_BILL_HINTS.any { lower.contains(it) }
    }

    /**
     * Money moving the same direction. A credit-card bill settlement is booked as TRANSFER by the
     * SMS parser and can arrive as EXPENSE from a statement (or vice versa), so those pair up.
     */
    fun typesCompatible(a: TransactionType, b: TransactionType): Boolean {
        if (a == b) return true
        val outgoing = setOf(TransactionType.EXPENSE, TransactionType.TRANSFER)
        return a in outgoing && b in outgoing
    }

    /**
     * Both sides truncate merchant names differently, so accept a prefix relation or a shared
     * word. Two clearly different names block the match.
     */
    fun merchantsCompatible(a: String?, b: String?): Boolean {
        if (isGenericMerchant(a) || isGenericMerchant(b)) return true
        if (isCardBillMerchant(a) && isCardBillMerchant(b)) return true
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isBlank() || nb.isBlank()) return true
        if (na == nb) return true
        if (na.startsWith(nb) || nb.startsWith(na)) return true

        val tokensA = na.split(' ').filter { it.length >= 3 }.toSet()
        val tokensB = nb.split(' ').filter { it.length >= 3 }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return false
        if (tokensA.intersect(tokensB).isNotEmpty()) return true
        // "G RAHUL KUMA" vs "G RAHUL K": one side's last token is a cut-off of the other's.
        return tokensA.any { ta -> tokensB.any { tb -> ta.startsWith(tb) || tb.startsWith(ta) } }
    }

    /**
     * A shared reference proves one payment. Two different references only prove two payments when
     * they are the same kind of identifier: an SMS may quote a 17-digit RTGS UTR where the statement
     * quotes the 12-digit UPI RRN for that very transaction, and those must stay comparable.
     */
    fun referenceVerdict(a: String?, b: String?): Verdict {
        val ra = a?.trim().orEmpty()
        val rb = b?.trim().orEmpty()
        if (ra.isBlank() || rb.isBlank()) return Verdict.UNKNOWN
        if (ra.equals(rb, ignoreCase = true)) return Verdict.SAME
        // A statement prints the full UTR (HDFCR52026072888542568) where the SMS quotes it without
        // the bank prefix (52026072888542568); one ending with the other is the same transfer.
        val (longer, shorter) = if (ra.length >= rb.length) ra to rb else rb to ra
        if (shorter.length >= 8 && longer.endsWith(shorter, ignoreCase = true)) return Verdict.SAME
        val comparable = ra.length == rb.length &&
            ra.all { it.isDigit() } == rb.all { it.isDigit() }
        return if (comparable) Verdict.DIFFERENT else Verdict.UNKNOWN
    }

    /** Distinct closing balances mean distinct payments, even at the same amount and day. */
    fun balanceVerdict(a: Long?, b: Long?): Verdict = when {
        a == null || b == null -> Verdict.UNKNOWN
        a == b -> Verdict.SAME
        else -> Verdict.DIFFERENT
    }

    enum class Verdict { SAME, DIFFERENT, UNKNOWN }

    data class Candidate(
        val type: TransactionType,
        val merchant: String?,
        val reference: String?,
        val balanceAfterPaise: Long?,
        val occurredAt: Long
    )

    /**
     * Score for pairing [incoming] with [existing], or null when they cannot be the same payment.
     * Higher wins, so the strongest evidence (matching reference or balance) is preferred.
     */
    fun score(incoming: Candidate, existing: Candidate): Int? {
        if (!typesCompatible(incoming.type, existing.type)) return null

        val refVerdict = referenceVerdict(incoming.reference, existing.reference)
        val balVerdict = balanceVerdict(incoming.balanceAfterPaise, existing.balanceAfterPaise)
        if (refVerdict == Verdict.DIFFERENT) return null
        if (balVerdict == Verdict.DIFFERENT) return null

        if (refVerdict == Verdict.SAME) return 100
        if (!merchantsCompatible(incoming.merchant, existing.merchant)) return null

        var s = 0
        if (balVerdict == Verdict.SAME) s += 50
        if (incoming.type == existing.type) s += 10
        if (!isGenericMerchant(incoming.merchant) && !isGenericMerchant(existing.merchant)) s += 5
        // Closer in time is more likely the same alert/settlement pair.
        val hoursApart = Math.abs(incoming.occurredAt - existing.occurredAt) / 3_600_000L
        s += (24 - hoursApart.coerceAtMost(24L)).toInt()
        return s
    }

    private fun normalize(raw: String?): String =
        raw?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

    private val GENERIC_MERCHANTS = setOf(
        "upi", "upi payment", "payment", "bank", "unknown", "n a", "na", "-",
        "credit", "debit", "transfer", "txn", "transaction", "imps", "neft", "rtgs", "nach", "ecs"
    )

    private val CARD_BILL_HINTS = listOf(
        "credit card payment", "card payment", "cred club", "cred", "cc bill", "card bill",
        "bbps", "sbicard"
    )

    /** Payment switches / PSPs that appear where a merchant name should be. */
    private val AGGREGATORS = listOf(
        "credpay", "credclub", "gokiwi", "kiwi", "razorpay", "billdesk", "ccavenue", "payu",
        "bbps", "juspay", "cashfree", "easebuzz", "worldline", "pinelabs", "bharatpe",
        "paytm", "phonepe", "googlepay", "gpay", "upiswitch", "nppl", "npci"
    )
}
