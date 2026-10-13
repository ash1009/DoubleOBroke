package app.brokeometer.domain

data class MerchantInfo(
    /** Readable name, for example "Subway". */
    val name: String,
    /** Stable form used for learning, for example "SUBWAY". */
    val key: String,
    /** Town or website from the alert, may be empty. */
    val place: String,
)

/** Turns the fixed-width card descriptor in a bank alert into a clean merchant. */
object Merchants {

    /** Name, town and country are separated by runs of three or more spaces. */
    private val gap = Regex("""\s{3,}""")
    private val space = Regex("""\s+""")
    private val abroadPrefix = Regex("""^INT'?L(?:\s+CARD)?\b\s*""", RegexOption.IGNORE_CASE)
    private val countryCode = Regex("""^[A-Z]{2}$""")

    fun fromDescriptor(raw: String): MerchantInfo {
        var rest = raw.trim()

        // Payments abroad start with "INTL CARD <reference>".
        val abroad = abroadPrefix.find(rest)
        if (abroad != null) {
            rest = rest.substring(abroad.range.last + 1).trim()
            val firstToken = rest.split(space, limit = 2).firstOrNull().orEmpty()
            if (looksLikeReference(firstToken)) rest = rest.substring(firstToken.length).trim()
            if (rest.isEmpty()) return MerchantInfo("Card payment abroad", "INTL CARD", "")
        }

        var segments = rest.split(gap).map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return MerchantInfo(collapse(raw), keyOf(raw), "")

        // Bank charges read "CHARGE   NON-STERLING   CASH FEE".
        if (segments.first().equals("CHARGE", ignoreCase = true) && segments.size > 1) {
            val what = segments.drop(1).joinToString(" ")
            return MerchantInfo(sentenceCase(what), keyOf("CHARGE $what"), "")
        }

        // Trailing country, either "GB" or "ON CA" (region then country).
        val lastTokens = segments.last().split(space).toMutableList()
        if (countryCode.matches(lastTokens.last()) && (segments.size > 1 || lastTokens.size > 2)) {
            lastTokens.removeAt(lastTokens.lastIndex)
            val somethingElseRemains = segments.size > 1 || lastTokens.size > 1
            if (somethingElseRemains && lastTokens.isNotEmpty() && countryCode.matches(lastTokens.last())) {
                lastTokens.removeAt(lastTokens.lastIndex)
            }
            segments = if (lastTokens.isEmpty()) segments.dropLast(1) else segments.dropLast(1) + lastTokens.joinToString(" ")
        }
        if (segments.isEmpty()) return MerchantInfo(collapse(raw), keyOf(raw), "")

        val merchantPart = segments.first()
        val place = segments.drop(1).joinToString(" ") { cleanPlace(it) }.trim()

        val name = prettyName(merchantPart).ifBlank { collapse(merchantPart) }
        val key = keyOf(merchantPart).ifBlank { collapse(merchantPart).uppercase() }
        return MerchantInfo(name, key, place)
    }

    /** Uppercase words only, with store numbers and references removed. */
    fun keyOf(text: String): String =
        text.uppercase()
            .replace(Regex("""[^A-Z0-9]+"""), " ")
            .trim()
            .split(space)
            .filter { token -> token.length > 1 && token.none { it.isDigit() } }
            .joinToString(" ")

    private fun looksLikeReference(token: String): Boolean =
        token.length >= 6 && token.any { it.isDigit() } && token.all { it.isLetterOrDigit() }

    private fun collapse(text: String): String = text.trim().replace(space, " ")

    private fun cleanPlace(segment: String): String {
        val text = segment.split(space)
            .filterNot { token -> token.count { it.isDigit() } >= 4 }
            .joinToString(" ")
        return when {
            text.contains('.') -> text.lowercase()          // a website such as "ee.co.uk"
            text.any { it.isLowerCase() } -> text
            else -> titleCase(text)
        }
    }

    private fun prettyName(part: String): String {
        val tokens = part.replace('*', ' ').replace('_', ' ').split(space).filter { it.isNotBlank() }
        val kept = tokens.mapIndexedNotNull { index, original ->
            var token = original
            // "FARE/SRR8XS" keeps "FARE".
            val slash = token.indexOf('/')
            if (slash > 0 && token.substring(slash + 1).any { it.isDigit() }) token = token.substring(0, slash)
            val digits = token.count { it.isDigit() }
            val letters = token.count { it.isLetter() }
            when {
                digits > 0 && letters == 0 -> null                          // store number
                digits > 0 && token.length >= 6 && index > 0 -> null        // reference code
                else -> token
            }
        }
        return kept.joinToString(" ") { token ->
            if (token.length > 3 && token.none { it.isLowerCase() }) titleCase(token) else token
        }
    }

    private fun titleCase(text: String): String {
        val out = StringBuilder(text.length)
        var startOfWord = true
        for (c in text) {
            out.append(if (startOfWord) c.uppercaseChar() else c.lowercaseChar())
            startOfWord = !c.isLetter()
        }
        return out.toString()
    }

    private fun sentenceCase(text: String): String {
        val lower = collapse(text).lowercase()
        return lower.replaceFirstChar { it.uppercaseChar() }
    }
}
