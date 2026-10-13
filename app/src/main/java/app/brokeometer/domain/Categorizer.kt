package app.brokeometer.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** One past decision the phone can learn from. */
data class Example(
    val merchantKey: String,
    val subcategoryId: Long,
    /** Hour of day 0..23, or null when the time is not known. */
    val hour: Int?,
    val amountMinor: Long?,
    /** True for the examples the app ships with, false for the user's own choices. */
    val starter: Boolean,
    val ageDays: Int = 0,
)

enum class GuessSource { HISTORY, SIMILAR, STARTER, KEYWORD, NONE }

data class Guess(val subcategoryId: Long?, val confidence: Float, val source: GuessSource) {
    companion object {
        val NONE = Guess(null, 0f, GuessSource.NONE)
    }
}

/** Sub-categories the time-of-day rule applies to. */
data class MealIds(val breakfast: Long, val lunch: Long, val dinner: Long) {
    operator fun contains(id: Long) = id == breakfast || id == lunch || id == dinner

    fun forHour(hour: Int, minute: Int = 0): Long = when (mealForHour(hour, minute)) {
        Meal.BREAKFAST -> breakfast
        Meal.LUNCH -> lunch
        Meal.DINNER -> dinner
    }
}

enum class Meal { BREAKFAST, LUNCH, DINNER }

/** Breakfast until 11:00, lunch until 16:30, dinner after that and through the night. */
fun mealForHour(hour: Int, minute: Int = 0): Meal {
    val t = hour * 60 + minute
    return when {
        t >= 5 * 60 && t < 11 * 60 -> Meal.BREAKFAST
        t >= 11 * 60 && t < 16 * 60 + 30 -> Meal.LUNCH
        else -> Meal.DINNER
    }
}

/** Word hints used only when nothing in the history matches. Values are sub-category codes. */
object KeywordHints {
    const val FOOD_BY_TIME = "food.bytime"

    private val food = listOf(
        "KITCHEN", "BURGER", "PIZZA", "CAFE", "COFFEE", "RESTAURANT", "GRILL", "BIRYANI", "CHICKEN",
        "KEBAB", "SUSHI", "NOODLE", "RAMEN", "BAKERY", "DESSERT", "DINER", "TACO", "CURRY", "KFC",
        "MCDONALD", "NANDO", "GREGGS", "PRET", "SUBWAY", "DOMINO", "DELIVEROO", "JUST EAT", "EATS",
        "WINGSTOP", "WAGAMAMA", "LEON", "ITSU", "DISHOOM", "STARBUCKS", "COSTA",
    )
    private val grocery = listOf(
        "TESCO", "SAINSBURY", "ASDA", "LIDL", "ALDI", "MORRISON", "WAITROSE", "CO-OP", "COOP",
        "NISA", "ICELAND", "SPAR", "LONDIS", "COSTCUTTER",
    )
    private val rules: List<Pair<List<String>, String>> = listOf(
        listOf("UBER TRIP", "BOLT", "FREENOW", "FREE NOW", "ADDISON LEE") to "travel.cab",
        listOf("TRAINLINE", "LNER", "GWR", "AVANTI", "SOUTHEASTERN", "THAMESLINK", "RAILWAY") to "travel.train",
        listOf("RYANAIR", "EASYJET", "BRITISH AIRWAYS", "WIZZ", "AIR INDIA", "EMIRATES") to "travel.flight",
        listOf("LAUNDRY", "LAUNDRETTE", "CIRCUIT") to "personalcare.laundry",
        listOf("BARBER") to "personalcare.barber",
        listOf("PHARMACY", "BOOTS", "SUPERDRUG") to "health.medicine",
        listOf("CINEMA", "ODEON", "VUE", "CINEWORLD") to "entertainment.movie",
        listOf("NETFLIX", "SPOTIFY", "DISNEY", "YOUTUBE", "PRIME VIDEO", "OPENAI", "CHATGPT", "ANTHROPIC", "CLAUDE") to
            "entertainment.subscriptions",
        grocery to "grocery.eatery",
        food to FOOD_BY_TIME,
    )

    fun codeFor(merchantKey: String, merchantName: String): String? {
        val haystack = " ${merchantKey.uppercase()} ${merchantName.uppercase()} "
        for ((words, code) in rules) {
            // Match at the start of a word, so "NANDO" finds "NANDOS" but "LEON" does not find "GALLEON".
            if (words.any { haystack.contains(" $it") }) return code
        }
        return null
    }
}

/**
 * The on-phone learner. It has no model file: it scores the user's own past choices
 * (same merchant first, then similar names) by time of day and amount.
 */
class Categorizer(
    private val meals: MealIds?,
    private val subcategoryForCode: (String) -> Long? = { null },
) {

    fun guess(
        merchantKey: String,
        merchantName: String,
        hour: Int,
        minute: Int,
        amountMinor: Long,
        examples: List<Example>,
    ): Guess {
        val exact = examples.filter { it.merchantKey == merchantKey }
        if (exact.isNotEmpty()) {
            val real = exact.any { !it.starter }
            return decide(
                scored = exact.map { it to 1.0 },
                hour = hour, minute = minute, amountMinor = amountMinor,
                source = if (real) GuessSource.HISTORY else GuessSource.STARTER,
                timedMealEvidence = exact.count { !it.starter && it.hour != null && meals != null && it.subcategoryId in meals },
            )
        }

        val tokens = tokensOf(merchantKey)
        val similar = examples.mapNotNull { ex ->
            val s = similarity(tokens, tokensOf(ex.merchantKey))
            if (s >= SIMILAR_ENOUGH) ex to s else null
        }
        if (similar.isNotEmpty()) {
            val g = decide(similar, hour, minute, amountMinor, GuessSource.SIMILAR, timedMealEvidence = 0)
            return g.copy(confidence = g.confidence * 0.7f)
        }

        val code = KeywordHints.codeFor(merchantKey, merchantName) ?: return Guess.NONE
        val id = if (code == KeywordHints.FOOD_BY_TIME) meals?.forHour(hour, minute) else subcategoryForCode(code)
        return if (id == null) Guess.NONE else Guess(id, 0.4f, GuessSource.KEYWORD)
    }

    private fun decide(
        scored: List<Pair<Example, Double>>,
        hour: Int,
        minute: Int,
        amountMinor: Long,
        source: GuessSource,
        timedMealEvidence: Int,
    ): Guess {
        val totals = HashMap<Long, Double>()
        for ((ex, sim) in scored) {
            val hourFit = ex.hour?.let { hourKernel(hour, it) } ?: 0.5
            val amountFit = ex.amountMinor?.let { amountKernel(amountMinor, it) } ?: 0.5
            var w = sim * sim
            if (ex.starter) w *= 0.6
            w *= 0.25 + 0.75 * hourFit
            w *= 0.25 + 0.75 * amountFit
            w *= 1.0 / (1.0 + ex.ageDays / 365.0)
            totals[ex.subcategoryId] = (totals[ex.subcategoryId] ?: 0.0) + w
        }
        val best = totals.maxByOrNull { it.value } ?: return Guess.NONE
        val sum = totals.values.sum()
        var id = best.key
        var confidence = (best.value / sum) * (best.value / (best.value + 0.5))

        // A food place at 2pm is lunch. Until this merchant has a few timed examples of its own,
        // the clock decides which meal it is.
        if (meals != null && id in meals && timedMealEvidence < 3) {
            val byClock = meals.forHour(hour, minute)
            if (byClock != id) {
                id = byClock
                confidence = min(confidence, 0.6)
            }
        }
        return Guess(id, confidence.toFloat().coerceIn(0f, 1f), source)
    }

    companion object {
        private const val SIMILAR_ENOUGH = 0.5

        fun tokensOf(key: String): Set<String> = key.split(' ').filter { it.isNotEmpty() }.toSet()

        /** Share of words the two names have in common (0 to 1). */
        fun similarity(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val common = a.count { it in b }
            if (common == 0) return 0.0
            return common.toDouble() / (a.size + b.size - common)
        }

        /** 1 when the hours match, falling to about 0.1 five hours apart. Wraps round midnight. */
        fun hourKernel(a: Int, b: Int): Double {
            val d = abs(a - b).let { min(it, 24 - it) }.toDouble()
            return exp(-(d * d) / (2 * 2.5 * 2.5))
        }

        /** 1 for equal amounts, about 0.5 when one is 1.5 times the other. */
        fun amountKernel(a: Long, b: Long): Double {
            if (a <= 0 || b <= 0) return 0.5
            val d = abs(ln(a.toDouble() / b.toDouble()))
            return exp(-(d * d) / (2 * 0.35 * 0.35))
        }
    }
}
