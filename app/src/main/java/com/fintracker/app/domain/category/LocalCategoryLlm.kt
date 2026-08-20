package com.fintracker.app.domain.category

import com.fintracker.app.domain.model.TransactionType

/**
 * Tiny on-device categorizer used only after [CategoryClassifier] has no keyword hit.
 *
 * This is not a cloud LLM and it does not download a 1GB model. A generative model that size
 * would bloat the APK and drain the battery on every SMS. Instead this scores the merchant +
 * narration against compact India-focused prototypes (chicken stall, kirana, petrol pump, …)
 * entirely in-process.
 */
object LocalCategoryLlm {

    const val MIN_CONFIDENCE = 0.52f

    fun suggest(
        merchant: String? = null,
        rawText: String? = null,
        type: TransactionType? = null
    ): CategoryClassifier.Suggestion? {
        if (type == TransactionType.TRANSFER) return null

        val hay = CategoryClassifier.normalizeKey(
            listOfNotNull(merchant, rawText).joinToString(" ")
        )
        if (hay.isBlank()) return null

        val tokens = tokenize(hay)
        if (tokens.isEmpty()) return null
        // Person-to-person UPI ("G RAHUL K") has no category signal — leave uncategorized.
        if (looksLikePersonName(tokens) && !hasAnyCue(tokens, hay)) return null

        var bestName: String? = null
        var bestScore = 0f
        for ((category, cues) in PROTOTYPES) {
            if (type == TransactionType.INCOME && category !in INCOME_CATEGORIES) continue
            if (type == TransactionType.EXPENSE && category in INCOME_ONLY) continue
            val score = score(hay, tokens, cues)
            if (score > bestScore) {
                bestScore = score
                bestName = category
            }
        }
        if (bestName == null || bestScore < MIN_CONFIDENCE) return null
        return CategoryClassifier.Suggestion(bestName, bestScore.coerceAtMost(0.88f))
    }

    private fun score(hay: String, tokens: Set<String>, cues: List<Cue>): Float {
        var hitWeight = 0f
        var hits = 0
        for (cue in cues) {
            val matched = when {
                cue.phrase.contains(' ') -> hay.contains(cue.phrase)
                cue.phrase.length <= 3 -> cue.phrase in tokens
                else -> hay.contains(cue.phrase) || tokens.any { it.startsWith(cue.phrase) }
            }
            if (matched) {
                hitWeight += cue.weight
                hits++
            }
        }
        if (hits == 0) return 0f
        // One weak cue is not enough; two cues or one strong cue is.
        val strength = if (hits == 1 && hitWeight < 0.7f) hitWeight * 0.55f else hitWeight
        return (0.35f + strength).coerceAtMost(0.88f)
    }

    private fun tokenize(hay: String): Set<String> =
        hay.split(' ').filter { it.length >= 2 && it !in STOP }.toSet()

    private fun hasAnyCue(tokens: Set<String>, hay: String): Boolean =
        PROTOTYPES.values.any { cues ->
            cues.any { cue ->
                if (cue.phrase.contains(' ')) hay.contains(cue.phrase)
                else cue.phrase in tokens || (cue.phrase.length >= 4 && hay.contains(cue.phrase))
            }
        }

    /** Two or three alphabetic tokens, no digits — typical UPI personal name. */
    private fun looksLikePersonName(tokens: Set<String>): Boolean {
        if (tokens.size !in 2..4) return false
        return tokens.all { token -> token.all { it.isLetter() } && token.length <= 12 }
    }

    private data class Cue(val phrase: String, val weight: Float)

    private val STOP = setOf(
        "upi", "pay", "the", "and", "for", "via", "from", "with", "card", "bank",
        "debit", "credit", "paid", "sent", "to", "on", "at", "in", "of", "a", "an",
        "xxx", "xxxx", "ref", "txn", "neft", "imps", "rtgs", "bbps", "acct", "account",
        "payment", "towards", "using", "inr", "rs", "chq", "dr", "cr"
    )

    private val INCOME_CATEGORIES = setOf("Salary", "Investment", "Other")
    private val INCOME_ONLY = setOf("Salary")

    private val PROTOTYPES: Map<String, List<Cue>> = mapOf(
        "Food" to listOf(
            Cue("chicken", 0.55f), Cue("mutton", 0.5f), Cue("biryani", 0.55f),
            Cue("hotel", 0.4f), Cue("dhaba", 0.5f), Cue("tiffin", 0.5f),
            Cue("bakery", 0.45f), Cue("sweets", 0.45f), Cue("restaurant", 0.55f),
            Cue("cafe", 0.45f), Cue("canteen", 0.45f), Cue("kitchen", 0.4f),
            Cue("chef", 0.5f), Cue("food", 0.5f), Cue("pizza", 0.5f),
            Cue("burger", 0.45f), Cue("tea stall", 0.45f), Cue("juice", 0.35f),
            Cue("mess", 0.4f), Cue("eatery", 0.5f), Cue("diner", 0.4f),
            Cue("fish", 0.35f), Cue("egg", 0.3f)
        ),
        "Groceries" to listOf(
            Cue("kirana", 0.55f), Cue("supermarket", 0.55f), Cue("super market", 0.55f),
            Cue("bazaar", 0.4f), Cue("bazar", 0.4f), Cue("provision", 0.5f),
            Cue("vegetable", 0.5f), Cue("vegetables", 0.5f), Cue("fruit", 0.4f),
            Cue("fruits", 0.4f), Cue("ration", 0.45f), Cue("groc", 0.45f),
            Cue("traders", 0.35f), Cue("trader", 0.35f), Cue("mart", 0.3f),
            Cue("store", 0.25f), Cue("general store", 0.5f)
        ),
        "Fuel" to listOf(
            Cue("petrol", 0.6f), Cue("diesel", 0.6f), Cue("pump", 0.5f),
            Cue("filling", 0.4f), Cue("fuel", 0.55f), Cue("hpcl", 0.5f),
            Cue("bpcl", 0.5f), Cue("iocl", 0.5f), Cue("nayara", 0.5f)
        ),
        "Travel" to listOf(
            Cue("taxi", 0.5f), Cue("cab", 0.4f), Cue("auto", 0.3f),
            Cue("bus", 0.3f), Cue("train", 0.45f), Cue("railway", 0.5f),
            Cue("flight", 0.55f), Cue("airline", 0.5f), Cue("lodge", 0.4f),
            Cue("parking", 0.45f), Cue("toll", 0.45f), Cue("fastag", 0.55f),
            Cue("metro", 0.45f), Cue("hotel booking", 0.5f), Cue("stay", 0.3f),
            Cue("indian ra", 0.45f)
        ),
        "Shopping" to listOf(
            Cue("garment", 0.5f), Cue("textile", 0.5f), Cue("boutique", 0.5f),
            Cue("jewellery", 0.55f), Cue("jewelry", 0.55f), Cue("gold", 0.35f),
            Cue("electronics", 0.5f), Cue("mobile", 0.35f), Cue("footwear", 0.5f),
            Cue("shoes", 0.4f), Cue("clothing", 0.5f), Cue("apparel", 0.5f),
            Cue("mall", 0.35f), Cue("emporium", 0.4f)
        ),
        "Health" to listOf(
            Cue("medical", 0.55f), Cue("pharma", 0.5f), Cue("pharmacy", 0.55f),
            Cue("hospital", 0.55f), Cue("clinic", 0.5f), Cue("dental", 0.5f),
            Cue("optical", 0.45f), Cue("diagnostic", 0.5f), Cue("doctor", 0.45f),
            Cue("lab", 0.3f), Cue("nursing", 0.4f)
        ),
        "Education" to listOf(
            Cue("school", 0.5f), Cue("college", 0.5f), Cue("university", 0.55f),
            Cue("coaching", 0.5f), Cue("tuition", 0.5f), Cue("academy", 0.4f),
            Cue("books", 0.3f), Cue("fee", 0.3f)
        ),
        "Entertainment" to listOf(
            Cue("cinema", 0.55f), Cue("movie", 0.5f), Cue("theatre", 0.45f),
            Cue("theater", 0.45f), Cue("salon", 0.5f), Cue("parlour", 0.45f),
            Cue("parlor", 0.45f), Cue("spa", 0.45f), Cue("gym", 0.45f),
            Cue("club", 0.3f), Cue("game", 0.3f)
        ),
        "Bills/Utilities" to listOf(
            Cue("electricity", 0.55f), Cue("broadband", 0.5f), Cue("wifi", 0.4f),
            Cue("insurance", 0.45f), Cue("premium", 0.35f), Cue("recharge", 0.45f),
            Cue("gas bill", 0.5f), Cue("water bill", 0.5f)
        ),
        "Rent" to listOf(
            Cue("rent", 0.55f), Cue("landlord", 0.5f), Cue("house rent", 0.6f)
        ),
        "EMI" to listOf(
            Cue("emi", 0.55f), Cue("loan", 0.4f), Cue("nbfc", 0.45f), Cue("finance", 0.3f)
        ),
        "Investment" to listOf(
            Cue("sip", 0.5f), Cue("mutual", 0.5f), Cue("nach", 0.4f),
            Cue("clearing", 0.4f), Cue("groww", 0.55f), Cue("shares", 0.4f),
            Cue("zerodha", 0.55f), Cue("invest", 0.4f)
        ),
        "Salary" to listOf(
            Cue("salary", 0.6f), Cue("payroll", 0.55f), Cue("stipend", 0.5f)
        )
    )
}
