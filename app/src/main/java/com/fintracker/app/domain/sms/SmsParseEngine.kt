package com.fintracker.app.domain.sms

import java.security.MessageDigest
import java.util.Locale
import java.util.regex.Pattern

class SmsParseEngine(
    private val templatesProvider: () -> List<SmsTemplate>
) {
    fun parse(message: SmsMessage): ParsedSmsTransaction? {
        val body = message.body.trim()
        if (body.isBlank()) return null
        if (isOtpOrNoise(body)) return null

        // Checked before templates: a bill payment reads like a credit on the card side and like a
        // spend on the bank side, but it is neither — the card's purchases were already captured.
        if (isCreditCardBillPayment(body)) {
            val amount = extractFirstAmount(body)
            if (amount != null) {
                val reference = extractReference(body)
                return ParsedSmsTransaction(
                    amountPaise = amount,
                    type = com.fintracker.app.domain.model.TransactionType.TRANSFER,
                    paymentMode = billPaymentMode(body),
                    merchant = "Credit card payment",
                    reference = reference,
                    balanceAfterPaise = null,
                    maskedAccount = extractMaskedAccount(body),
                    bankHint = null,
                    confidence = 0.85f,
                    sender = message.sender,
                    rawSnippet = body.take(280),
                    occurredAt = message.receivedAt,
                    needsReview = false,
                    dedupeKey = buildCardPaymentDedupeKey(amount, message.receivedAt)
                )
            }
        }

        val templates = templatesProvider()
        val senderUpper = message.sender.uppercase(Locale.US)

        val candidates = templates.filter { template ->
            template.senderPatterns.any { pattern ->
                Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(senderUpper).find() ||
                    Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(message.sender).find()
            }
        }.ifEmpty { templates.filter { it.id.startsWith("generic_") } }

        for (template in candidates.sortedByDescending { it.confidence }) {
            for (bodyPattern in template.bodyPatterns) {
                val matcher = Pattern.compile(bodyPattern).matcher(body)
                if (!matcher.find()) continue
                val amount = parseAmountToPaise(matcher.groupOrNull(template.amountGroup)) ?: continue
                val merchant = template.merchantGroup?.let { matcher.groupOrNull(it)?.trim() }
                val reference = template.referenceGroup?.let { matcher.groupOrNull(it)?.trim() }
                val balance = template.balanceGroup?.let { parseAmountToPaise(matcher.groupOrNull(it)) }
                val account = template.accountGroup?.let { matcher.groupOrNull(it)?.trim() }
                    ?: extractMaskedAccount(body)

                val paymentMode = refinePaymentMode(body, template.paymentMode)
                val needsReview = template.confidence < 0.8f || merchant.isNullOrBlank()
                val dedupeKey = buildDedupeKey(
                    amountPaise = amount,
                    occurredAt = message.receivedAt,
                    reference = reference,
                    merchant = merchant,
                    sender = message.sender
                )

                return ParsedSmsTransaction(
                    amountPaise = amount,
                    type = template.type,
                    paymentMode = paymentMode,
                    merchant = merchant,
                    reference = reference,
                    balanceAfterPaise = balance,
                    maskedAccount = account,
                    bankHint = template.bankHint,
                    confidence = template.confidence,
                    sender = message.sender,
                    rawSnippet = body.take(280),
                    occurredAt = message.receivedAt,
                    needsReview = needsReview,
                    dedupeKey = dedupeKey
                )
            }
        }

        if (looksFinancial(body)) {
            val amount = extractFirstAmount(body) ?: return null
            val type = resolveDirection(body)
            return ParsedSmsTransaction(
                amountPaise = amount,
                type = type,
                paymentMode = refinePaymentMode(body, com.fintracker.app.domain.model.PaymentMode.UNKNOWN),
                merchant = null,
                reference = extractReference(body),
                balanceAfterPaise = null,
                maskedAccount = extractMaskedAccount(body),
                bankHint = null,
                confidence = 0.55f,
                sender = message.sender,
                rawSnippet = body.take(280),
                occurredAt = message.receivedAt,
                needsReview = true,
                dedupeKey = buildDedupeKey(amount, message.receivedAt, null, null, message.sender)
            )
        }
        return null
    }

    fun isOtpOrNoise(body: String): Boolean =
        isOtpMessage(body) || isStatementOrReminder(body) || isInvestmentConfirmation(body)

    /**
     * OTP alerts quote the amount of a transaction that has not happened yet ("851278 is One-Time
     * Password for INR 860.00 transaction towards AMAZON"). Banks never put an OTP in a completed
     * transaction alert, so any OTP marker is enough to reject the message.
     */
    private fun isOtpMessage(body: String): Boolean =
        Regex(
            "(?i)\\b(?:otps?|one[\\s-]?time\\s+(?:password|passcode)|verification\\s+code|" +
                "secure\\s+code|passcode)\\b"
        ).containsMatchIn(body)

    /** Statement / bill-due notices quote balances and due amounts, but move no money. */
    private fun isStatementOrReminder(body: String): Boolean {
        val marker = Regex(
            "(?i)(?:e-?statement|statement\\s+(?:is|has\\s+been|for|of)|" +
                "min(?:imum)?\\s*(?:amt|amount)?\\s*due|total\\s+due|\\bdue\\s+(?:by|on)\\b|" +
                "payment\\s+is\\s+due|bill\\s+(?:is\\s+)?generated|unbilled)"
        ).containsMatchIn(body)
        if (!marker) return false
        // Card alerts often print the revised due amount alongside a real spend or refund; only
        // reject when nothing actually moved.
        return !isCreditCardBillPayment(body) && !hasSettledMovement(body)
    }

    /** Money that has already moved, as opposed to an amount merely quoted as due or pending. */
    private fun hasSettledMovement(body: String): Boolean =
        Regex(
            "(?i)\\b(?:debited|spent|withdrawn|credited|refund(?:ed)?|reversed|reversal)\\b"
        ).containsMatchIn(body)

    /**
     * Allotment / redemption notices from an AMC or CRA confirm units, not a bank transaction — the
     * matching bank debit or credit SMS is the real record. Identified by investment-only vocabulary
     * (NAV, folio, PRAN, units) with no account movement, so a bank SIP debit still counts.
     */
    private fun isInvestmentConfirmation(body: String): Boolean {
        val marker = Regex(
            "(?i)\\b(?:folio|NAV|PRAN|mutual\\s*fund|redemption|allotment|allotted|" +
                "units?\\s+(?:of|for|are|have|were))\\b"
        ).containsMatchIn(body)
        if (!marker) return false
        val bankMovement = Regex(
            "(?i)(?:A/[Cc]|Acct|Account)\\s*(?:no\\.?)?\\s*[Xx*0-9]{3,}[^.]{0,40}?" +
                "\\b(?:debited|credited|Dr|Cr)\\b|\\bAvl\\s*Bal\\b|\\bavailable\\s+balance\\b"
        ).containsMatchIn(body)
        return !bankMovement
    }

    private fun looksFinancial(body: String): Boolean =
        Regex("(?i)(?:Rs\\.?|INR|₹)\\s*[0-9]").containsMatchIn(body) &&
            Regex(
                "(?i)\\b(?:debited|credited|spent|withdrawn|purchase|charged|paid|payment|" +
                    "sent|txn|transaction|trxn|UPI|NEFT|IMPS|RTGS)\\b"
            ).containsMatchIn(body) &&
            !isPromotional(body)

    /**
     * True for credit-card bill settlements, seen from either side:
     *   card side  — "We have received payment of Rs.1,284.00 via BBPS & the same has been
     *                 credited to your SBI Credit Card."
     *   bank side  — "Rs.1,284.00 debited from A/c X1234 towards SBI Card payment."
     *
     * Requires a completed-payment confirmation, so statement and due-date reminders are ignored,
     * and excludes merchant refunds credited to the card (those really are money coming back).
     */
    fun isCreditCardBillPayment(body: String): Boolean {
        if (Regex("(?i)\\brefund(?:ed)?\\b|\\breversal\\b|\\breversed\\b").containsMatchIn(body)) {
            return false
        }
        // "CRED" is matched case-sensitively so it cannot hit "credited"/"Credit".
        val mentionsCard = Regex("(?i)\\b(?:credit\\s*card|cc\\s*bill|card\\s*bill)\\b")
            .containsMatchIn(body) || Regex("\\bCRED\\b").containsMatchIn(body)
        if (!mentionsCard) return false
        val confirmations = listOf(
            """(?i)received\s+(?:your\s+)?payment""",
            """(?i)payment\s+of\s*(?:Rs\.?|INR|₹)?\s*[0-9,.]+\s*(?:is|has\s+been|was)?\s*(?:received|credited|successful)""",
            """(?i)\bcredited\s+to\s+your\b[^.\n]{0,60}?\b(?:credit\s*card|card)\b""",
            """(?i)thank\s+you\s+for\s+(?:the|your)\s+payment""",
            """(?i)\b(?:debited|paid|payment|Dr\.?)\b[^\n]{0,80}?\b(?:towards|to|for)\b[^\n]{0,40}?(?:credit\s*card|cc\s*bill|card\s*bill|CRED\b)""",
            """(?i)(?:credit\s*card|cc)\s*bill\s*(?:payment|paid)"""
        )
        return confirmations.any { Regex(it).containsMatchIn(body) }
    }

    /** For a bill payment the useful mode is how it was funded, not the card being settled. */
    private fun billPaymentMode(body: String): com.fintracker.app.domain.model.PaymentMode {
        val lower = body.lowercase(Locale.US)
        return when {
            "upi" in lower -> com.fintracker.app.domain.model.PaymentMode.UPI
            "bbps" in lower || "neft" in lower || "imps" in lower || "rtgs" in lower ||
                "net banking" in lower || "netbanking" in lower || "nach" in lower ||
                "auto pay" in lower || "autopay" in lower ->
                com.fintracker.app.domain.model.PaymentMode.NET_BANKING
            else -> com.fintracker.app.domain.model.PaymentMode.UNKNOWN
        }
    }

    /**
     * Direction is decided by whichever money-movement verb appears first: bank alerts lead with
     * the action, and a debit alert may still mention a credit later ("credited to the merchant",
     * "Avl Bal ... Cr", "credit card").
     */
    private fun resolveDirection(body: String): com.fintracker.app.domain.model.TransactionType {
        val debitAt = Regex(
            "(?i)\\b(?:debited|debit|spent|withdrawn|purchase|purchased|charged|paid|payment|" +
                "sent|txn|trxn)\\b"
        ).find(body)?.range?.first
        val creditAt = Regex(
            "(?i)\\b(?:credited|received|refund|refunded|deposited|reversed|reversal|cashback)\\b"
        ).find(body)?.range?.first
        return when {
            creditAt == null -> com.fintracker.app.domain.model.TransactionType.EXPENSE
            debitAt == null || creditAt < debitAt ->
                com.fintracker.app.domain.model.TransactionType.INCOME
            else -> com.fintracker.app.domain.model.TransactionType.EXPENSE
        }
    }

    private fun isPromotional(body: String): Boolean =
        Regex(
            "(?i)(?:apply now|pre-?approved|eligible for|offers? on|loan offer|" +
                "download the app|t&c|terms apply|limited period|click here)"
        ).containsMatchIn(body) &&
            !Regex("(?i)\\b(?:debited|credited|spent|withdrawn|txn|transaction)\\b")
                .containsMatchIn(body)

    private fun refinePaymentMode(
        body: String,
        fallback: com.fintracker.app.domain.model.PaymentMode
    ): com.fintracker.app.domain.model.PaymentMode {
        val lower = body.lowercase(Locale.US)
        return when {
            "credit card" in lower -> com.fintracker.app.domain.model.PaymentMode.CREDIT_CARD
            "debit card" in lower || "atm" in lower ->
                com.fintracker.app.domain.model.PaymentMode.DEBIT_CARD
            "upi" in lower -> com.fintracker.app.domain.model.PaymentMode.UPI
            "net banking" in lower || "neft" in lower || "imps" in lower || "rtgs" in lower ->
                com.fintracker.app.domain.model.PaymentMode.NET_BANKING
            else -> fallback
        }
    }

    private fun parseAmountToPaise(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace(",", "").trim()
        val value = cleaned.toDoubleOrNull() ?: return null
        return (value * 100).toLong()
    }

    private fun extractFirstAmount(body: String): Long? {
        val match = Regex("(?i)(?:Rs\\.?|INR|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)").find(body)
        return parseAmountToPaise(match?.groupValues?.getOrNull(1))
    }

    private fun extractMaskedAccount(body: String): String? =
        Regex("(?i)(?:A/C|A/c|Acc(?:ount)?)[^0-9X*]*([Xx*0-9]{4,})").find(body)?.groupValues?.getOrNull(1)

    private fun extractReference(body: String): String? =
        Regex("(?i)(?:UPI\\s*Ref|Ref(?:erence)?|UTR)[^0-9]*([0-9]{6,})").find(body)?.groupValues?.getOrNull(1)

    fun buildDedupeKey(
        amountPaise: Long,
        occurredAt: Long,
        reference: String?,
        merchant: String?,
        sender: String
    ): String {
        if (!reference.isNullOrBlank()) {
            return sha1("ref:$reference:$amountPaise")
        }
        val bucket = occurredAt / (3 * 60 * 1000)
        val merchantPart = merchant?.trim()?.lowercase(Locale.US).orEmpty()
        return sha1("amt:$amountPaise|t:$bucket|m:$merchantPart|s:${sender.uppercase(Locale.US)}")
    }

    /**
     * One bill payment can produce several alerts (bank debit, card credit, wallet confirmation)
     * with different senders and references, so transfers dedupe on amount plus IST calendar day.
     */
    fun buildCardPaymentDedupeKey(amountPaise: Long, occurredAt: Long): String {
        val istDay = (occurredAt + IST_OFFSET_MS) / DAY_MS
        return sha1("ccpay:$amountPaise:$istDay")
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun java.util.regex.Matcher.groupOrNull(index: Int): String? =
        try {
            if (groupCount() >= index) group(index) else null
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val IST_OFFSET_MS = 5 * 60 * 60 * 1000L + 30 * 60 * 1000L
    }
}
