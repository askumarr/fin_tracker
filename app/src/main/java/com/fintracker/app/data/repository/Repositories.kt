package com.fintracker.app.data.repository

import com.fintracker.app.data.UserPreferences
import com.fintracker.app.data.dao.AccountDao
import com.fintracker.app.data.dao.CategoryDao
import com.fintracker.app.data.dao.ImportJobDao
import com.fintracker.app.data.dao.MerchantCategoryRuleDao
import com.fintracker.app.data.dao.SmsSenderRuleDao
import com.fintracker.app.data.dao.TransactionDao
import com.fintracker.app.data.entity.AccountEntity
import com.fintracker.app.data.entity.CategoryEntity
import com.fintracker.app.data.entity.ImportJobEntity
import com.fintracker.app.data.entity.MerchantCategoryRuleEntity
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.domain.category.CategoryClassifier
import com.fintracker.app.domain.category.LocalCategoryLlm
import com.fintracker.app.domain.dedupe.TransactionMatcher
import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.ReviewStatus
import com.fintracker.app.domain.model.TransactionSource
import com.fintracker.app.domain.model.TransactionType
import com.fintracker.app.domain.model.isStatement
import com.fintracker.app.domain.sms.SmsParseEngine
import com.fintracker.app.ui.util.DateFormatters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val merchantCategoryRuleDao: MerchantCategoryRuleDao,
    private val categoryDao: CategoryDao,
    private val preferences: UserPreferences
) {
    private val insertLock = Mutex()
    private val categoryIdByName = Mutex()
    @Volatile private var categoryNameCache: Map<String, Long> = emptyMap()

    fun observeAll(): Flow<List<TransactionEntity>> = transactionDao.observeAll()

    fun observeInRange(start: Long, end: Long): Flow<List<TransactionEntity>> =
        transactionDao.observeInRange(start, end)

    fun observeNeedsReview(): Flow<List<TransactionEntity>> =
        transactionDao.observeByReviewStatus(ReviewStatus.NEEDS_REVIEW)

    fun observeReviewCount(): Flow<Int> =
        transactionDao.observeReviewCount(ReviewStatus.NEEDS_REVIEW)

    fun observeSum(type: TransactionType, start: Long, end: Long): Flow<Long> =
        transactionDao.observeSum(type, start, end)

    suspend fun getById(id: Long): TransactionEntity? = transactionDao.getById(id)

    suspend fun findByDedupeKey(key: String): TransactionEntity? =
        transactionDao.findByDedupeKey(key)

    /**
     * @param claimedIds ids already matched during the current import run. A statement row only
     *   pairs with a row nobody else claimed, so three ₹10,000 debits on one day stay three rows.
     * @return Pair(id, wasDuplicate)
     */
    suspend fun insertDeduped(
        transaction: TransactionEntity,
        claimedIds: MutableSet<Long>? = null
    ): Pair<Long, Boolean> =
        insertLock.withLock {
            transaction.dedupeKey?.let { key ->
                val existing = transactionDao.findByDedupeKey(key)
                if (existing != null) {
                    maybeEnrich(existing, transaction)
                    claimedIds?.add(existing.id)
                    return@withLock existing.id to true
                }
            }

            if (transaction.source == TransactionSource.SMS) {
                findSameDaySibling(transaction)?.let { sibling ->
                    maybeEnrich(sibling, transaction)
                    claimedIds?.add(sibling.id)
                    return@withLock sibling.id to true
                }
            } else if (transaction.source.isStatement()) {
                findStatementSibling(transaction, claimedIds)?.let { sibling ->
                    maybeEnrich(sibling, transaction)
                    claimedIds?.add(sibling.id)
                    return@withLock sibling.id to true
                }
            }

            val id = transactionDao.insert(transaction)
            if (id == -1L) {
                val existingId = transaction.dedupeKey?.let {
                    transactionDao.findByDedupeKey(it)?.id
                } ?: 0L
                return@withLock existingId to true
            }
            claimedIds?.add(id)
            return@withLock id to false
        }

    suspend fun insert(transaction: TransactionEntity): Long =
        insertDeduped(transaction).first

    suspend fun update(transaction: TransactionEntity) =
        transactionDao.update(transaction.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(id: Long) = transactionDao.deleteById(id)

    suspend fun setReviewStatus(id: Long, status: ReviewStatus) {
        val existing = transactionDao.getById(id) ?: return
        transactionDao.update(
            existing.copy(reviewStatus = status, updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun setReviewStatusBulk(ids: List<Long>, status: ReviewStatus) {
        ids.forEach { setReviewStatus(it, status) }
    }

    /** Rows must be removed rather than dismissed, so a rescan is free to re-create them. */
    suspend fun deleteSmsImportedSince(since: Long): Int =
        transactionDao.deleteSmsImportedSince(since)

    /** Statement rows are always re-creatable from the source file, so a re-import can start clean. */
    suspend fun deleteImportedFrom(source: TransactionSource): Int =
        transactionDao.deleteBySource(source)

    /**
     * Collapse historical SMS duplicates: same amount + type + IST day when at least one
     * is a transfer or has a generic merchant.
     */
    suspend fun mergeSameDaySmsDuplicates(): Int = insertLock.withLock {
        val all = transactionDao.getAllSms().filter { it.reviewStatus != ReviewStatus.DISMISSED }
        // Rows carrying a distinct closing balance are distinct transactions, so keep them in
        // separate buckets; rows without a balance share an "unknown" bucket that can still merge.
        val groups = all.groupBy {
            val balPart = it.balanceAfterPaise?.toString() ?: "?"
            "${it.amountPaise}|${DateFormatters.dayKey(it.occurredAt)}|$balPart"
        }
        var removed = 0
        for ((_, rows) in groups) {
            if (rows.size < 2) continue
            val refs = rows.mapNotNull { it.reference?.takeIf { r -> r.isNotBlank() } }.toSet()
            val merge = when {
                rows.all { it.type == TransactionType.TRANSFER } -> true
                refs.size == 1 && rows.size > 1 -> true
                rows.any { it.type == TransactionType.TRANSFER } &&
                    rows.any {
                        it.type == TransactionType.TRANSFER ||
                            SmsParseEngine.isGenericMerchantStatic(it.merchant)
                    } &&
                    rows.all {
                        it.type == TransactionType.TRANSFER ||
                            SmsParseEngine.isGenericMerchantStatic(it.merchant)
                    } -> true
                rows.all { SmsParseEngine.isGenericMerchantStatic(it.merchant) } &&
                    rows.map { it.type }.toSet().size == 1 -> true
                else -> false
            }
            if (!merge) continue

            val keeper = rows.maxWith(
                compareBy<TransactionEntity> {
                    if (it.type == TransactionType.TRANSFER) 2 else 0
                }
                    .thenBy { it.confidence }
                    .thenByDescending {
                        if (SmsParseEngine.isGenericMerchantStatic(it.merchant)) 0 else 1
                    }
                    .thenBy { it.id }
            )
            for (extra in rows.filter { it.id != keeper.id }) {
                transactionDao.deleteById(extra.id)
                removed++
            }
        }
        removed
    }

    private suspend fun findSameDaySibling(transaction: TransactionEntity): TransactionEntity? {
        transaction.reference?.takeIf { it.isNotBlank() }?.let { ref ->
            transactionDao.findByReferenceAndAmount(ref, transaction.amountPaise)?.let { return it }
        }

        val (dayStart, dayEnd) = DateFormatters.istDayRange(transaction.occurredAt)
        val sameType = transactionDao.findSameDaySms(
            amountPaise = transaction.amountPaise,
            type = transaction.type,
            dayStart = dayStart,
            dayEnd = dayEnd
        )

        val allowLoose = transaction.type == TransactionType.TRANSFER ||
            SmsParseEngine.isGenericMerchantStatic(transaction.merchant)

        sameType.firstOrNull { existing ->
            when {
                // Distinct closing balances => genuinely distinct transactions, never a duplicate.
                balancesDiffer(transaction, existing) -> false
                transaction.type == TransactionType.TRANSFER -> true
                !transaction.reference.isNullOrBlank() &&
                    transaction.reference == existing.reference -> true
                allowLoose && SmsParseEngine.isGenericMerchantStatic(existing.merchant) -> true
                else -> false
            }
        }?.let { return it }

        // Cross-type: bill payment TRANSFER vs bank EXPENSE with generic merchant
        if (transaction.type == TransactionType.TRANSFER || allowLoose) {
            val otherType = if (transaction.type == TransactionType.TRANSFER) {
                TransactionType.EXPENSE
            } else {
                TransactionType.TRANSFER
            }
            val cross = transactionDao.findSameDaySms(
                amountPaise = transaction.amountPaise,
                type = otherType,
                dayStart = dayStart,
                dayEnd = dayEnd
            )
            return cross.firstOrNull { existing ->
                !balancesDiffer(transaction, existing) &&
                    (existing.type == TransactionType.TRANSFER ||
                        SmsParseEngine.isGenericMerchantStatic(existing.merchant))
            }
        }
        return null
    }

    /**
     * Find the row that already represents this statement (CSV/PDF) line — normally the SMS alert
     * captured when the payment happened. Candidates are same amount + same IST day; the rest of
     * the decision lives in [TransactionMatcher].
     */
    private suspend fun findStatementSibling(
        transaction: TransactionEntity,
        claimedIds: Set<Long>?
    ): TransactionEntity? {
        transaction.reference?.takeIf { it.isNotBlank() }?.let { ref ->
            transactionDao.findAnySourceByReferenceAndAmount(ref, transaction.amountPaise)
                ?.takeIf { claimedIds?.contains(it.id) != true }
                ?.let { return it }
        }

        val (dayStart, dayEnd) = DateFormatters.istDayRange(transaction.occurredAt)
        val candidates = transactionDao.findSameDayAnySource(
            amountPaise = transaction.amountPaise,
            dayStart = dayStart,
            dayEnd = dayEnd
        ).filter { claimedIds?.contains(it.id) != true }

        return candidates
            .mapNotNull { existing ->
                TransactionMatcher.score(transaction.toCandidate(), existing.toCandidate())
                    ?.let { existing to it }
            }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Collapse duplicates that already exist because a statement was imported before statement
     * rows knew how to pair with SMS rows.
     */
    suspend fun mergeStatementSmsDuplicates(): Int = insertLock.withLock {
        val all = transactionDao.getAllForBackup()
            .filter { it.reviewStatus != ReviewStatus.DISMISSED }
        val groups = all.groupBy { "${it.amountPaise}|${DateFormatters.dayKey(it.occurredAt)}" }
        var removed = 0
        for ((_, rows) in groups) {
            if (rows.size < 2) continue
            val sms = rows.filter { it.source == TransactionSource.SMS }
            val statements = rows.filter { it.source.isStatement() }
            if (sms.isEmpty() || statements.isEmpty()) continue

            val claimed = mutableSetOf<Long>()
            for (statement in statements.sortedBy { it.id }) {
                val match = sms
                    .filter { it.id !in claimed }
                    .mapNotNull { candidate ->
                        TransactionMatcher.score(statement.toCandidate(), candidate.toCandidate())
                            ?.let { candidate to it }
                    }
                    .maxByOrNull { it.second }
                    ?.first ?: continue
                claimed.add(match.id)
                maybeEnrich(match, statement)
                transactionDao.deleteById(statement.id)
                removed++
            }
        }
        removed
    }

    private fun TransactionEntity.toCandidate() = TransactionMatcher.Candidate(
        type = type,
        merchant = merchant,
        reference = reference,
        balanceAfterPaise = balanceAfterPaise,
        occurredAt = occurredAt
    )

    /** True when both rows carry a closing balance and those balances are different. */
    private fun balancesDiffer(a: TransactionEntity, b: TransactionEntity): Boolean {
        val ab = a.balanceAfterPaise
        val bb = b.balanceAfterPaise
        return ab != null && bb != null && ab != bb
    }

    private suspend fun maybeEnrich(existing: TransactionEntity, incoming: TransactionEntity) {
        var next = existing
        var changed = false
        if (SmsParseEngine.isGenericMerchantStatic(existing.merchant) &&
            !SmsParseEngine.isGenericMerchantStatic(incoming.merchant)
        ) {
            next = next.copy(merchant = incoming.merchant)
            changed = true
        }
        if (incoming.confidence > existing.confidence) {
            next = next.copy(confidence = incoming.confidence)
            changed = true
        }
        if (existing.rawSmsSnippet.isNullOrBlank() && !incoming.rawSmsSnippet.isNullOrBlank()) {
            next = next.copy(rawSmsSnippet = incoming.rawSmsSnippet)
            changed = true
        }
        if (existing.reference.isNullOrBlank() && !incoming.reference.isNullOrBlank()) {
            next = next.copy(reference = incoming.reference)
            changed = true
        }
        // A statement line knows the real payment mode and closing balance where an SMS often
        // does not, so let those fill the gaps instead of being dropped with the duplicate.
        if (existing.paymentMode == PaymentMode.UNKNOWN &&
            incoming.paymentMode != PaymentMode.UNKNOWN
        ) {
            next = next.copy(paymentMode = incoming.paymentMode)
            changed = true
        }
        if (existing.balanceAfterPaise == null && incoming.balanceAfterPaise != null) {
            next = next.copy(balanceAfterPaise = incoming.balanceAfterPaise)
            changed = true
        }
        if (existing.categoryId == null && incoming.categoryId != null) {
            next = next.copy(categoryId = incoming.categoryId)
            changed = true
        }
        if (existing.accountId == null && incoming.accountId != null) {
            next = next.copy(accountId = incoming.accountId)
            changed = true
        }
        if (changed) {
            transactionDao.update(next.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun rememberMerchantCategory(merchant: String?, categoryId: Long?) {
        if (merchant.isNullOrBlank() || categoryId == null) return
        val full = CategoryClassifier.normalizeKey(merchant)
        if (full.isBlank()) return
        merchantCategoryRuleDao.upsert(
            MerchantCategoryRuleEntity(merchantKey = full, categoryId = categoryId)
        )
        CategoryClassifier.merchantRoot(merchant)?.let { root ->
            if (root != full) {
                merchantCategoryRuleDao.upsert(
                    MerchantCategoryRuleEntity(merchantKey = root, categoryId = categoryId)
                )
            }
        }
    }

    /**
     * Suggest a category id: learned exact/root rules first, then built-in India keyword map,
     * then type heuristics (transfers / salary).
     */
    suspend fun suggestCategory(
        merchant: String?,
        rawText: String? = null,
        type: TransactionType? = null
    ): Long? {
        if (type == TransactionType.TRANSFER) {
            return categoryIdForName("Transfers")
        }

        val full = merchant?.let { CategoryClassifier.normalizeKey(it) }.orEmpty()
        if (full.isNotBlank()) {
            merchantCategoryRuleDao.find(full)?.categoryId?.let { return it }
            CategoryClassifier.merchantRoot(merchant.orEmpty())?.let { root ->
                merchantCategoryRuleDao.find(root)?.categoryId?.let { return it }
            }
            // Learned root / phrase contained as a whole token in this merchant
            // (e.g. rule "amazon" matches "amazon pay in e").
            val tokens = full.split(' ').filter { it.isNotBlank() }.toSet()
            for (rule in merchantCategoryRuleDao.getAll()) {
                val key = rule.merchantKey
                if (key.length < 3) continue
                if (key in tokens || tokens.any { it.startsWith(key) && it.length <= key.length + 2 }) {
                    return rule.categoryId
                }
                if (full.startsWith("$key ") || full.contains(" $key ")) return rule.categoryId
            }
        }

        val suggestion = CategoryClassifier.suggestCategoryName(
            merchant = merchant,
            rawText = rawText,
            type = type
        ) ?: if (preferences.localLlmEnabled.first()) {
            LocalCategoryLlm.suggest(merchant, rawText, type)
        } else {
            null
        } ?: return null
        val id = categoryIdForName(suggestion.categoryName)
        // Remember LLM/heuristic hits so the next similar merchant skips the scorer.
        if (id != null && suggestion.confidence < 0.93f) {
            rememberMerchantCategory(merchant, id)
        }
        return id
    }

    /**
     * Fill categories on rows the keyword parser left blank, using the on-device scorer.
     */
    suspend fun recategorizeUncategorized(): Int {
        if (!preferences.localLlmEnabled.first()) return 0
        var filled = 0
        for (txn in transactionDao.findUncategorized()) {
            val id = suggestCategory(
                merchant = txn.merchant,
                rawText = listOfNotNull(txn.note, txn.rawSmsSnippet).joinToString(" ")
                    .ifBlank { null },
                type = txn.type
            ) ?: continue
            transactionDao.update(
                txn.copy(categoryId = id, updatedAt = System.currentTimeMillis())
            )
            filled++
        }
        return filled
    }

    private suspend fun categoryIdForName(name: String): Long? {
        val key = name.lowercase(Locale.US)
        categoryNameCache[key]?.let { return it }
        return categoryIdByName.withLock {
            categoryNameCache[key]?.let { return@withLock it }
            val refreshed = categoryDao.getAll()
                .associate { it.name.lowercase(Locale.US) to it.id }
            categoryNameCache = refreshed
            refreshed[key] ?: categoryDao.findByName(name)?.id
        }
    }

    suspend fun getAllForBackup(): List<TransactionEntity> = transactionDao.getAllForBackup()

    suspend fun replaceAll(transactions: List<TransactionEntity>) {
        transactionDao.deleteAll()
        transactions.forEach { transactionDao.insert(it.copy(id = 0)) }
    }
}

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao
) {
    fun observeActive(): Flow<List<CategoryEntity>> = categoryDao.observeActive()
    suspend fun getAll(): List<CategoryEntity> = categoryDao.getAll()
    suspend fun getById(id: Long): CategoryEntity? = categoryDao.getById(id)
    suspend fun insert(category: CategoryEntity): Long = categoryDao.insert(category)
    suspend fun update(category: CategoryEntity) = categoryDao.update(category)
}

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao
) {
    private val lock = Mutex()

    fun observeActive(): Flow<List<AccountEntity>> = accountDao.observeActive()
    suspend fun getAll(): List<AccountEntity> = accountDao.getAll()
    suspend fun getById(id: Long): AccountEntity? = accountDao.getById(id)
    suspend fun insert(account: AccountEntity): Long = accountDao.insert(account)
    suspend fun update(account: AccountEntity) = accountDao.update(account)

    /**
     * One logical bank/account per normalized bank hint (or masked number when no bank is known).
     * Serialized so parallel SMS workers cannot create the same bank twice.
     */
    suspend fun findOrCreate(
        bankHint: String?,
        masked: String?,
        sender: String? = null
    ): Long? = lock.withLock {
        val hint = normalizeBankHint(bankHint) ?: inferBankHintFromSender(sender)
        val mask = normalizeMasked(masked)
        if (hint == null && mask == null) return@withLock null

        findExisting(hint, mask)?.let { existing ->
            enrichIfNeeded(existing, hint, mask)
            return@withLock existing.id
        }

        val name = when {
            hint != null && mask != null -> "$hint ·$mask"
            hint != null -> hint
            else -> "Card ·${mask}"
        }
        accountDao.insert(
            AccountEntity(
                name = name,
                bankHint = hint,
                maskedNumber = mask
            )
        )
    }

    /** Collapse historical duplicates created by the old always-insert path. */
    suspend fun mergeDuplicates(): Int = lock.withLock {
        val all = accountDao.getAll()
        val groups = all.groupBy { mergeKey(it) }.filter { it.value.size > 1 }
        var removed = 0
        for ((_, dupes) in groups) {
            val keeper = dupes.minBy { it.id }
            for (extra in dupes.filter { it.id != keeper.id }) {
                transactionDao.reassignAccount(extra.id, keeper.id)
                accountDao.deleteById(extra.id)
                removed++
            }
            val bestHint = dupes.mapNotNull { normalizeBankHint(it.bankHint) }.firstOrNull()
                ?: normalizeBankHint(keeper.bankHint)
            val bestMask = dupes.mapNotNull { normalizeMasked(it.maskedNumber) }.firstOrNull()
                ?: normalizeMasked(keeper.maskedNumber)
            val bestName = when {
                bestHint != null && bestMask != null -> "$bestHint ·$bestMask"
                bestHint != null -> bestHint
                bestMask != null -> "Card ·$bestMask"
                else -> keeper.name
            }
            if (keeper.bankHint != bestHint || keeper.maskedNumber != bestMask || keeper.name != bestName) {
                accountDao.update(
                    keeper.copy(
                        name = bestName,
                        bankHint = bestHint,
                        maskedNumber = bestMask
                    )
                )
            }
        }
        removed
    }

    private suspend fun findExisting(hint: String?, mask: String?): AccountEntity? {
        if (hint != null) {
            accountDao.findByBankHint(hint)?.let { return it }
            accountDao.findByName(hint)?.let { return it }
        }
        if (mask != null) {
            accountDao.findByMasked(mask)?.let { return it }
            if (hint != null) {
                accountDao.findByName("$hint ·$mask")?.let { return it }
            }
        }
        return null
    }

    private suspend fun enrichIfNeeded(existing: AccountEntity, hint: String?, mask: String?) {
        var next = existing
        var changed = false
        if (existing.bankHint.isNullOrBlank() && hint != null) {
            next = next.copy(bankHint = hint)
            changed = true
        }
        if (existing.maskedNumber.isNullOrBlank() && mask != null) {
            next = next.copy(maskedNumber = mask)
            changed = true
        }
        val preferredName = when {
            next.bankHint != null && next.maskedNumber != null ->
                "${next.bankHint} ·${next.maskedNumber}"
            next.bankHint != null -> next.bankHint!!
            next.maskedNumber != null -> "Card ·${next.maskedNumber}"
            else -> next.name
        }
        if (next.name != preferredName) {
            next = next.copy(name = preferredName)
            changed = true
        }
        if (changed) accountDao.update(next)
    }

    private fun mergeKey(account: AccountEntity): String {
        normalizeBankHint(account.bankHint)?.let { return "bank:$it" }
        val nameHead = account.name.trim().uppercase(Locale.US)
            .substringBefore(" ·")
            .substringBefore('·')
            .trim()
        normalizeBankHint(nameHead)
            ?.takeUnless { it in setOf("CARD", "ACCOUNT", "BANK") }
            ?.let { return "bank:$it" }
        normalizeMasked(account.maskedNumber)?.let { return "mask:$it" }
        return "name:${nameHead.ifBlank { "id-${account.id}" }}"
    }

    companion object {
        fun normalizeBankHint(raw: String?): String? =
            raw?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() }

        fun normalizeMasked(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val digits = raw.filter { it.isDigit() }
            return when {
                digits.length >= 4 -> digits.takeLast(4)
                else -> raw.trim().uppercase(Locale.US).takeLast(8).takeIf { it.isNotEmpty() }
            }
        }

        fun inferBankHintFromSender(sender: String?): String? {
            if (sender.isNullOrBlank()) return null
            val s = sender.uppercase(Locale.US)
            return when {
                "HDFC" in s -> "HDFC"
                "SBICRD" in s || "SBIINB" in s || Regex("""\bSBI\b""").containsMatchIn(s) -> "SBI"
                "ICICI" in s -> "ICICI"
                "AXIS" in s -> "AXIS"
                "KOTAK" in s -> "KOTAK"
                "FEDSCP" in s || "FEDBNK" in s || "FEDERAL" in s || "SCAPIA" in s -> "FEDERAL"
                "YESBNK" in s || "YESBANK" in s -> "YES"
                "IDFC" in s -> "IDFC"
                "CANBNK" in s || "CANARA" in s -> "CANARA"
                "KVB" in s -> "KVB"
                "INDUS" in s -> "INDUSIND"
                else -> null
            }
        }
    }
}

@Singleton
class ImportJobRepository @Inject constructor(
    private val importJobDao: ImportJobDao,
    private val smsSenderRuleDao: SmsSenderRuleDao,
    private val merchantCategoryRuleDao: MerchantCategoryRuleDao
) {
    fun observeRecent(): Flow<List<ImportJobEntity>> = importJobDao.observeRecent()
    suspend fun insert(job: ImportJobEntity): Long = importJobDao.insert(job)
    suspend fun update(job: ImportJobEntity) = importJobDao.update(job)
    suspend fun getMerchantRules() = merchantCategoryRuleDao.getAll()
    suspend fun getSenderRules() = smsSenderRuleDao.getAll()
    suspend fun replaceMerchantRules(rules: List<MerchantCategoryRuleEntity>) {
        merchantCategoryRuleDao.deleteAll()
        rules.forEach { merchantCategoryRuleDao.upsert(it.copy(id = 0)) }
    }
}
