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
                    ?: extractBalance(body)
                val account = template.accountGroup?.let { matcher.groupOrNull(it)?.trim() }
                    ?: extractMaskedAccount(body)

                val paymentMode = refinePaymentMode(body, template.paymentMode)
                val needsReview = template.confidence < 0.8f || merchant.isNullOrBlank()
                val dedupeKey = buildDedupeKey(
                    amountPaise = amount,
                    occurredAt = message.receivedAt,
                    reference = reference,
                    merchant = merchant,
                    sender = message.sender,
                    balanceAfterPaise = balance
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
            val reference = extractReference(body)
            val balance = extractBalance(body)
            return ParsedSmsTransaction(
                amountPaise = amount,
                type = type,
                paymentMode = refinePaymentMode(body, com.fintracker.app.domain.model.PaymentMode.UNKNOWN),
                merchant = null,
                reference = reference,
                balanceAfterPaise = balance,
                maskedAccount = extractMaskedAccount(body),
                bankHint = null,
                confidence = 0.55f,
                sender = message.sender,
                rawSnippet = body.take(280),
                occurredAt = message.receivedAt,
                needsReview = true,
                dedupeKey = buildDedupeKey(
                    amount,
                    message.receivedAt,
                    reference,
                    null,
                    message.sender,
                    balance
                )
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
        return !looksLikeBankAccountAlert(body)
    }

    /**
     * Distinguishes a bank account alert from an AMC/CRA unit confirmation. Investment vocabulary
     * also shows up in genuine bank credits, because the counterparty name is quoted verbatim
     * ("credited ... by Sender TMF REDEMPTION POOL A/C"), so these signals must win over the marker:
     * only the bank prints your closing balance, a UTR is issued for a settled transfer, and a
     * masked account sitting next to a debit/credit verb means money moved through that account.
     */
    private fun looksLikeBankAccountAlert(body: String): Boolean {
        if (extractBalance(body) != null) return true
        if (Regex("(?i)\\bUTR\\b").containsMatchIn(body)) return true
        return Regex(
            "(?i)\\b(?:debited|credited)\\b[^.]{0,40}?(?:A/[Cc]|Acct|Account)?\\s*[Xx*]{2,}[0-9]{2,}|" +
                "(?:A/[Cc]|Acct|Account)\\s*(?:no\\.?)?\\s*[Xx*0-9]{3,}[^.]{0,40}?" +
                "\\b(?:debited|credited|Dr|Cr)\\b"
        ).containsMatchIn(body)
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
            """(?i)we've\s+received\s+your\s+payment""",
            """(?i)payment\s+of\s*(?:Rs\.?|INR|₹)?\s*[0-9,.]+\s*(?:is|has\s+been|was)?\s*(?:received|credited|successful)""",
            """(?i)\bcredited\s+to\s+your\b[^.\n]{0,60}?\b(?:credit\s*card|card)\b""",
            """(?i)thank\s+you\s+for\s+(?:the|your)\s+payment""",
            """(?i)\b(?:debited|paid|payment|Dr\.?)\b[^\n]{0,100}?\b(?:towards|to|for)\b[^\n]{0,50}?(?:credit\s*card|cc\s*bill|card\s*bill|CRED\b|sbicard|scapia)""",
            """(?i)(?:credit\s*card|cc)\s*bill\s*(?:payment|paid)""",
            """(?i)\b(?:to|towards)\s+CRED\s*(?:Club|App)?\b""",
            """(?i)Acct\s+[Xx*0-9]+\s+Dr\.?\s*(?:INR|Rs\.?|₹)?\s*[0-9,.]+\s+on\s+[^\n]{0,40}?\bCRED\b"""
        )
        return confirmations.any { Regex(it).containsMatchIn(body) }
    }

    fun isGenericMerchant(merchant: String?): Boolean = isGenericMerchantStatic(merchant)

    /** IST calendar day bounds for [occurredAt] (inclusive start, inclusive end ms). */
    fun istDayRange(occurredAt: Long): Pair<Long, Long> {
        val dayIndex = (occurredAt + IST_OFFSET_MS) / DAY_MS
        val start = dayIndex * DAY_MS - IST_OFFSET_MS
        val end = start + DAY_MS - 1
        return start to end
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

    /**
     * Exact rupees-to-paise conversion. Going through Double loses a paisa on large amounts
     * (4783179.35 * 100 is 478317934.99999994, which truncates to ...34), so the decimal part is
     * scaled as an integer instead.
     */
    private fun parseAmountToPaise(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace(",", "").replace(" ", "").trim()
        if (!Regex("^[0-9]+(?:\\.[0-9]{1,2})?$").matches(cleaned)) return null
        val rupees = cleaned.substringBefore('.').toLongOrNull() ?: return null
        val paise = when (val frac = cleaned.substringAfter('.', "")) {
            "" -> 0L
            else -> frac.padEnd(2, '0').toLongOrNull() ?: return null
        }
        return rupees * 100 + paise
    }

    private fun extractFirstAmount(body: String): Long? {
        val match = Regex("(?i)(?:Rs\\.?|INR|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)").find(body)
        return parseAmountToPaise(match?.groupValues?.getOrNull(1))
    }

    /**
     * The account the money moved through. Transfer alerts also quote the counterparty's account
     * and an IFSC code ("by Sender TMF REDEMPTION POOL A/C, IFSC HDFC0000240, Sender A/c XXXX9201"),
     * so the masked account next to the debit/credit verb wins, an "A/c" introduced by "Sender" is
     * skipped, and the keyword match may no longer skip across letters into an IFSC code.
     */
    private fun extractMaskedAccount(body: String): String? {
        val masked = "([Xx*]+[0-9]{2,})"
        Regex("(?i)\\b(?:debited|credited)\\b[^.]{0,40}?\\b$masked")
            .find(body)?.groupValues?.getOrNull(1)?.let { return it }
        Regex(
            "(?i)(?<!sender\\s)(?:A/C|A/c|Acct|Acc(?:ount)?)\\s*(?:no\\.?)?[:\\s]*([Xx*0-9]{4,})"
        ).find(body)?.groupValues?.getOrNull(1)?.let { return it }
        return Regex(masked).find(body)?.groupValues?.getOrNull(1)
    }

    private fun extractReference(body: String): String? =
        Regex("(?i)(?:UPI\\s*Ref|Ref(?:erence)?|UTR)[^0-9]*([0-9]{6,})").find(body)?.groupValues?.getOrNull(1)

    /**
     * Closing/available balance printed by most bank alerts ("Total Avail.bal INR 17,88,623.39",
     * "Avl Bal Rs.5,000.00", "available balance is INR ...", "Bal INR 2,41,401.03"). Two otherwise
     * identical same-day debits with different balances are distinct transactions, so this is a
     * key disambiguator.
     */
    fun extractBalance(body: String): Long? {
        val match = Regex(
            "(?i)(?:avl\\.?\\s*bal(?:ance)?|avail(?:able)?\\.?\\s*bal(?:ance)?|" +
                "(?:total\\s+)?avail\\.?bal|bal(?:ance)?)\\s*(?:is|:)?\\s*" +
                "(?:Rs\\.?|INR|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)"
        ).find(body)
        return parseAmountToPaise(match?.groupValues?.getOrNull(1))
    }

    fun buildDedupeKey(
        amountPaise: Long,
        occurredAt: Long,
        reference: String?,
        merchant: String?,
        sender: String,
        balanceAfterPaise: Long? = null
    ): String {
        if (!reference.isNullOrBlank()) {
            return sha1("ref:$reference:$amountPaise")
        }
        // A distinct closing balance means a distinct transaction even at the same amount/time.
        if (balanceAfterPaise != null) {
            val istDay = (occurredAt + IST_OFFSET_MS) / DAY_MS
            return sha1("amtbal:$amountPaise|bal:$balanceAfterPaise|d:$istDay|s:${sender.uppercase(Locale.US)}")
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

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val IST_OFFSET_MS = 5 * 60 * 60 * 1000L + 30 * 60 * 1000L

        fun isGenericMerchantStatic(merchant: String?): Boolean {
            if (merchant.isNullOrBlank()) return true
            val m = merchant.trim().lowercase(Locale.US)
            return m == "transaction" || m == "txn" || m == "trxn" || m == "payment" ||
                m == "credit card payment"
        }
    }
}
