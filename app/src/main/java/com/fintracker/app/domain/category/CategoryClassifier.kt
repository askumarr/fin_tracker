package com.fintracker.app.domain.category

import com.fintracker.app.domain.model.TransactionType
import java.util.Locale

/**
 * On-device category suggestion from merchant + SMS text.
 *
 * Priority for callers: learned merchant rules first, then [suggestCategoryName] for built-ins /
 * type heuristics. Matching is keyword-based (India-focused) — no network / ML required.
 */
object CategoryClassifier {

    data class Suggestion(
        val categoryName: String,
        /** 0–1; built-in keyword hits are typically 0.7–0.9 */
        val confidence: Float
    )

    /**
     * Lowercase, strip punctuation noise, collapse whitespace.
     * "AMAZON PAY IN E." → "amazon pay in e"
     * "@UPI_XXX yyy" → "upi xxx yyy"
     */
    fun normalizeKey(raw: String): String =
        raw.lowercase(Locale.US)
            .replace('@', ' ')
            .replace(Regex("[^a-z0-9\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Stable root used for fuzzy learning: first meaningful token of at least 3 letters,
     * skipping fillers like "upi", "pay", "the".
     * "amazon pay in e" → "amazon"; "swiggy instamart" → "swiggy"
     */
    fun merchantRoot(raw: String): String? {
        val tokens = normalizeKey(raw).split(' ').filter { it.isNotBlank() }
        return tokens.firstOrNull { token ->
            token.length >= 3 && token !in ROOT_STOPWORDS && !token.all { it.isDigit() }
        }
    }

    fun suggestCategoryName(
        merchant: String? = null,
        rawText: String? = null,
        type: TransactionType? = null
    ): Suggestion? {
        when (type) {
            TransactionType.TRANSFER ->
                return Suggestion("Transfers", 0.95f)
            TransactionType.INCOME -> {
                val hay = haystack(merchant, rawText)
                if (containsAny(hay, SALARY_HINTS)) {
                    return Suggestion("Salary", 0.85f)
                }
                if (containsAny(hay, INVESTMENT_HINTS)) {
                    return Suggestion("Investment", 0.8f)
                }
            }
            else -> Unit
        }

        val hay = haystack(merchant, rawText)
        if (hay.isBlank()) return null

        var best: Pair<String, Int>? = null
        for ((keyword, category) in BUILTIN_RULES) {
            if (!matchesKeyword(hay, keyword)) continue
            val score = keyword.length
            if (best == null || score > best.second) {
                best = category to score
            }
        }
        return best?.let { (name, score) ->
            Suggestion(name, (0.55f + (score.coerceAtMost(20) / 40f)).coerceAtMost(0.92f))
        }
    }

    /** Prefer whole-token matches for short keywords so "emi" does not hit inside "premium". */
    private fun matchesKeyword(hay: String, keyword: String): Boolean {
        val needle = keyword.trim()
        if (needle.isEmpty()) return false
        if (needle.length >= 5) return hay.contains(needle)
        return Regex("\\b${Regex.escape(needle)}\\b").containsMatchIn(hay)
    }

    private fun haystack(merchant: String?, rawText: String?): String {
        val parts = buildList {
            merchant?.let { add(normalizeKey(it)) }
            rawText?.let { add(normalizeKey(it)) }
        }
        return parts.joinToString(" ")
    }

    private fun containsAny(hay: String, hints: List<String>): Boolean =
        hints.any { hay.contains(it) }

    private val ROOT_STOPWORDS = setOf(
        "upi", "pay", "the", "and", "for", "via", "from", "with", "card", "bank",
        "debit", "credit", "paid", "sent", "to", "on", "at", "in", "of", "a", "an",
        "xxx", "xxxx", "ref", "txn", "trs", "neft", "imps", "rtgs", "bbps"
    )

    private val SALARY_HINTS = listOf(
        "salary", "sal cred", "payroll", "wages", "stipend"
    )

    private val INVESTMENT_HINTS = listOf(
        "redemption", "dividend", "mutual fund", "groww", "zerodha", "upstox",
        "kuvera", "coin by zerodha", "nps", "ppf", "fd interest", "interest credited"
    )

    /**
     * Longer keywords first so "amazon pay" / "swiggy instamart" beat shorter overlaps.
     * Values must match [com.fintracker.app.data.db.DefaultCategories] names.
     */
    private val BUILTIN_RULES: List<Pair<String, String>> = listOf(
        // Food / delivery
        "swiggy instamart" to "Groceries",
        "swiggy" to "Food",
        "zomato" to "Food",
        "eatclub" to "Food",
        "magicpin" to "Food",
        "dominos" to "Food",
        "domino s" to "Food",
        "pizza hut" to "Food",
        "mcdonald" to "Food",
        "kfc" to "Food",
        "burger king" to "Food",
        "starbucks" to "Food",
        "cafe coffee day" to "Food",
        "haldiram" to "Food",
        "behrouz" to "Food",
        "faasos" to "Food",
        "box8" to "Food",
        "eatsure" to "Food",
        "chicken" to "Food",
        "biryani" to "Food",

        // Groceries
        "blinkit" to "Groceries",
        "zepto" to "Groceries",
        "bigbasket" to "Groceries",
        "bbnow" to "Groceries",
        "dmart" to "Groceries",
        "d mart" to "Groceries",
        "reliance fresh" to "Groceries",
        "nature s basket" to "Groceries",
        "more supermarket" to "Groceries",
        "spencer" to "Groceries",
        "jiomart" to "Groceries",
        "jio mart" to "Groceries",

        // Shopping
        "amazon pay" to "Shopping",
        "amazon" to "Shopping",
        "flipkart" to "Shopping",
        "myntra" to "Shopping",
        "ajio" to "Shopping",
        "meesho" to "Shopping",
        "nykaa" to "Shopping",
        "tatacliq" to "Shopping",
        "tata cliq" to "Shopping",
        "snapdeal" to "Shopping",
        "shopify" to "Shopping",
        "croma" to "Shopping",
        "reliance digital" to "Shopping",
        "vijay sales" to "Shopping",
        "ikea" to "Shopping",
        "decathlon" to "Shopping",
        "lifestyle stores" to "Shopping",
        "westside" to "Shopping",
        "pantaloons" to "Shopping",

        // Travel
        "uber" to "Travel",
        "ola cabs" to "Travel",
        "ola" to "Travel",
        "rapido" to "Travel",
        "irctc" to "Travel",
        "makemytrip" to "Travel",
        "make my trip" to "Travel",
        "goibibo" to "Travel",
        "cleartrip" to "Travel",
        "ixigo" to "Travel",
        "redbus" to "Travel",
        "red bus" to "Travel",
        "indigo" to "Travel",
        "air india" to "Travel",
        "vistara" to "Travel",
        "spicejet" to "Travel",
        "akasa" to "Travel",
        "yatra" to "Travel",
        "booking com" to "Travel",
        "airbnb" to "Travel",
        "metro" to "Travel",
        "fastag" to "Travel",
        "parking" to "Travel",

        // Fuel
        "indian oil" to "Fuel",
        "bharat petroleum" to "Fuel",
        "hindustan petroleum" to "Fuel",
        "hpcl" to "Fuel",
        "bpcl" to "Fuel",
        "iocl" to "Fuel",
        "shell" to "Fuel",
        "nayara" to "Fuel",
        "jio bp" to "Fuel",
        "petrol" to "Fuel",
        "diesel" to "Fuel",

        // Bills / utilities / telecom
        "jiocinema" to "Entertainment",
        "jio recharge" to "Bills/Utilities",
        "jio" to "Bills/Utilities",
        "airtel" to "Bills/Utilities",
        "vodafone" to "Bills/Utilities",
        "vi prepaid" to "Bills/Utilities",
        "bsnl" to "Bills/Utilities",
        "tatasky" to "Bills/Utilities",
        "tata play" to "Bills/Utilities",
        "dish tv" to "Bills/Utilities",
        "act fibernet" to "Bills/Utilities",
        "hathway" to "Bills/Utilities",
        "bescom" to "Bills/Utilities",
        "tneb" to "Bills/Utilities",
        "msedcl" to "Bills/Utilities",
        "adani electricity" to "Bills/Utilities",
        "tata power" to "Bills/Utilities",
        "bbps" to "Bills/Utilities",
        "electricity" to "Bills/Utilities",
        "gas bill" to "Bills/Utilities",
        "water bill" to "Bills/Utilities",
        "broadband" to "Bills/Utilities",
        "recharge" to "Bills/Utilities",

        // Rent / EMI / transfers
        "house rent" to "Rent",
        "rent payment" to "Rent",
        "cred" to "Transfers",
        "card payment" to "Transfers",
        "credit card bill" to "Transfers",
        "loan emi" to "EMI",
        "emi" to "EMI",
        "bajaj finserv" to "EMI",
        "home loan" to "EMI",
        "car loan" to "EMI",

        // Health
        "pharmeasy" to "Health",
        "1mg" to "Health",
        "netmeds" to "Health",
        "apollo" to "Health",
        "practo" to "Health",
        "medplus" to "Health",
        "pharmacy" to "Health",
        "hospital" to "Health",
        "clinic" to "Health",

        // Education
        "byju" to "Education",
        "unacademy" to "Education",
        "vedantu" to "Education",
        "coursera" to "Education",
        "udemy" to "Education",
        "school fee" to "Education",
        "tuition" to "Education",

        // Entertainment
        "netflix" to "Entertainment",
        "spotify" to "Entertainment",
        "hotstar" to "Entertainment",
        "disney" to "Entertainment",
        "prime video" to "Entertainment",
        "amazon prime" to "Entertainment",
        "sony liv" to "Entertainment",
        "sonyliv" to "Entertainment",
        "zee5" to "Entertainment",
        "youtube premium" to "Entertainment",
        "youtube" to "Entertainment",
        "bookmyshow" to "Entertainment",
        "book my show" to "Entertainment",
        "pvr" to "Entertainment",
        "inox" to "Entertainment",
        "gaana" to "Entertainment",
        "wynk" to "Entertainment",

        // Investment
        "groww" to "Investment",
        "zerodha" to "Investment",
        "upstox" to "Investment",
        "angel one" to "Investment",
        "angelone" to "Investment",
        "kuvera" to "Investment",
        "coin app" to "Investment",
        "smallcase" to "Investment",
        "mutual fund" to "Investment",
        "sip" to "Investment",
        "nps" to "Investment",
        "ppf" to "Investment"
    ).sortedByDescending { it.first.length }
}
