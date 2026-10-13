package app.brokeometer.domain

import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/** What was understood from a spoken payment, such as "3.4 pounds for metro at Vauxhall". */
data class VoiceDraft(
    val amountMinor: Long?,
    /** Null when no currency was said; GBP is used then. */
    val currency: String?,
    /** The shop or service, shown as "Where". */
    val where: String,
    /** The area, station or town, if said. */
    val place: String,
    val note: String,
    val daysAgo: Int,
    val subcategoryId: Long?,
)

/**
 * Turns a spoken payment into an entry. The amount, currency and "yesterday" are read on the phone,
 * so they never depend on the AI; the AI picks the category and splits the words into where, place
 * and note. Only the spoken sentence goes to the AI.
 */
object VoicePayment {

    const val SYSTEM = "You turn spoken notes about payments into records. Reply with JSON only."

    private const val NUM = """(\d+(?:[.,]\d{1,2})?)"""

    /** Reads what can be read without the AI. */
    fun local(text: String): VoiceDraft {
        val t0 = " " + text.lowercase().replace("£", " £ ").replace("₹", " ₹ ").replace(Regex("""\s+"""), " ") + " "
        val t = t0.replace(Regex("""\bquid\b"""), "pounds")

        var currency: String? = null
        var minor: Long? = null
        var used: IntRange? = null
        fun take(m: MatchResult, value: Long, cur: String?) {
            minor = value
            currency = cur
            used = m.range
        }
        val pounds = """(?:pounds?|gbp)"""
        val rupees = """(?:rupees?|rs\.?|inr)"""
        Regex("""(\d+) ?$pounds (?:and )?(\d{1,2}) ?(?:p|pence)?\b""").find(t)?.let { m ->
            take(m, m.groupValues[1].toLong() * 100 + m.groupValues[2].toLong(), "GBP")
        }
        if (minor == null) Regex("""£ ?$NUM""").find(t)?.let { m -> take(m, minorOf(m.groupValues[1]), "GBP") }
        if (minor == null) Regex("""$NUM ?$pounds\b""").find(t)?.let { m -> take(m, minorOf(m.groupValues[1]), "GBP") }
        if (minor == null) Regex("""(?:₹|\b$rupees) ?$NUM""").find(t)?.let { m -> take(m, minorOf(m.groupValues[1]), "INR") }
        if (minor == null) Regex("""$NUM ?$rupees\b""").find(t)?.let { m -> take(m, minorOf(m.groupValues[1]), "INR") }
        if (minor == null) Regex("""(\d+) ?(?:p|pence)\b""").find(t)?.let { m -> take(m, m.groupValues[1].toLong(), "GBP") }
        if (minor == null) Regex("""(?<![\w.])$NUM(?![\w.])""").find(t)?.let { m -> take(m, minorOf(m.groupValues[1]), null) }

        val rest = used?.let { t.removeRange(it) } ?: t
        val daysAgo = when {
            Regex("""\bday before yesterday\b""").containsMatchIn(rest) -> 2
            Regex("""\byesterday\b""").containsMatchIn(rest) -> 1
            else -> 0
        }
        val clean = rest.replace(Regex("""\b(day before yesterday|yesterday|today|i paid|paid|spent|on)\b"""), " ")
        val stop = """(?=\s(?:for|at|in)\s|\s*$)"""
        val forPart = Regex("""\bfor\s+(.+?)$stop""").find(clean)?.groupValues?.get(1)?.trim().orEmpty()
        val atPart = Regex("""\b(?:at|in)\s+(.+?)$stop""").find(clean)?.groupValues?.get(1)?.trim().orEmpty()
        return VoiceDraft(
            amountMinor = minor,
            currency = currency,
            where = title(atPart.ifEmpty { forPart }),
            place = "",
            note = if (atPart.isNotEmpty()) title(forPart) else "",
            daysAgo = daysAgo,
            subcategoryId = null,
        )
    }

    private fun minorOf(number: String): Long =
        BigDecimal(number.replace(',', '.')).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()

    private fun title(text: String): String =
        text.split(' ').filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    fun prompt(spoken: String, choices: List<AiChoice>, today: String): String = buildString {
        appendLine("One person, a student living in London, said this about a payment they made:")
        appendLine("\"$spoken\"")
        appendLine("Today is $today.")
        appendLine()
        appendLine("Sub-categories (id: Main / Sub):")
        choices.forEach { appendLine("${it.id}: ${it.label}") }
        appendLine()
        appendLine("""Reply with JSON only, like {"amount": 3.40, "currency": "GBP", "where": "Metro", "place": "Vauxhall", "note": "", "id": 12, "days_ago": 0}.""")
        appendLine("""- amount: the number paid. "3.4 pounds" is 3.40, "3 pounds 40" is 3.40, "40p" is 0.40.""")
        appendLine("- currency: GBP, unless they said rupees (INR), euros (EUR) or dollars (USD).")
        appendLine("- where: the shop or service they paid, in their own words, short, in Title Case.")
        appendLine("""- place: the area, station or town if they said one ("at Vauxhall"), else "".""")
        appendLine("""- note: anything else worth keeping, else "".""")
        appendLine("- id: the single best sub-category from the list.")
        append("- days_ago: 0, or 1 for yesterday, or how many days back a day of the week they named was.")
    }

    /** Reads the AI's JSON. Null when it is not usable. */
    fun parseAi(reply: String?, choices: List<AiChoice>): VoiceDraft? {
        if (reply.isNullOrBlank()) return null
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(reply.substring(start, end + 1))
            val amount = o.optString("amount").replace(',', '.').toBigDecimalOrNull()
            val id = o.optString("id").toLongOrNull()?.takeIf { want -> choices.any { it.id == want } }
            VoiceDraft(
                amountMinor = amount?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP)?.toLong()?.takeIf { it > 0 },
                currency = o.optString("currency").uppercase().takeIf { it.length == 3 },
                where = o.optString("where").trim().take(60),
                place = o.optString("place").trim().take(60),
                note = o.optString("note").trim().take(140),
                daysAgo = o.optInt("days_ago", 0).coerceIn(0, 31),
                subcategoryId = id,
            )
        } catch (e: JSONException) {
            null
        }
    }

    /** The phone's reading of the amount wins; the AI fills in the rest. */
    fun merge(local: VoiceDraft, ai: VoiceDraft?): VoiceDraft {
        if (ai == null) return local
        return VoiceDraft(
            amountMinor = local.amountMinor ?: ai.amountMinor,
            currency = local.currency ?: ai.currency,
            where = ai.where.ifBlank { local.where },
            place = ai.place,
            note = if (ai.where.isBlank()) local.note else ai.note,
            daysAgo = if (local.daysAgo > 0) local.daysAgo else ai.daysAgo,
            subcategoryId = ai.subcategoryId,
        )
    }
}
