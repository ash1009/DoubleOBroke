package app.brokeometer.mail

/**
 * Finds a one-time code (OTP) in an email, such as "Your verification code is 482913". Runs on the
 * phone with no AI, so code emails can be shown at once. Only a number near words like "code",
 * "OTP" or "verify" counts, so order numbers and prices are left alone.
 */
object MailCodes {

    private val words = Regex(
        """\b(otp|one[- ]?time|pass ?code|pin|codes?|verif\w*|authenticat\w*|2fa|two[- ]factor|security code|sign[- ]?in|log[- ]?in)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** 4 to 8 digits, or two groups of 3 or 4 split by a space or dash ("482 913", "4829-1305"), or "G-482913". */
    private val candidates = Regex("""(?<![\w£$€₹.,/:+#-])(?:[A-Z]-)?(\d{3,4}[ -]\d{3,4}|\d{4,8})(?![\w%]|[.,/:]\d|[ -]\d)""")

    private const val NEAR = 80

    fun find(subject: String, text: String): String? {
        val all = subject + "\n" + text.take(3000)
        val hits = words.findAll(all).map { it.range.first..it.range.last }.toList()
        if (hits.isEmpty()) return null
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (m in candidates.findAll(all)) {
            val raw = m.groupValues[1]
            val digits = raw.filter { it.isDigit() }
            if (digits.length !in 4..8) continue
            if (looksLikeSomethingElse(all, m.range, digits)) continue
            val distance = hits.minOf { w ->
                when {
                    m.range.last < w.first -> w.first - m.range.last
                    m.range.first > w.last -> m.range.first - w.last
                    else -> 0
                }
            }
            if (distance <= NEAR && distance < bestDistance) {
                best = digits
                bestDistance = distance
            }
        }
        return best
    }

    private fun looksLikeSomethingElse(all: String, range: IntRange, digits: String): Boolean {
        val before = all.substring((range.first - 14).coerceAtLeast(0), range.first).lowercase()
        val after = all.substring(range.last + 1, (range.last + 12).coerceAtMost(all.length)).lowercase()
        // A year in a date or a copyright line.
        if (digits.length == 4 && digits.toInt() in 1950..2099 &&
            (Regex("""(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\w*\.?\s*\d{0,2},?\s*$""").containsMatchIn(before) ||
                before.contains("©") || before.contains("copyright"))
        ) return true
        // Money, times and counts.
        if (Regex("""(gbp|usd|eur|inr|rs\.?|pounds?)\s*$""").containsMatchIn(before)) return true
        if (Regex("""^\s*(pounds?|gbp|usd|eur|inr|rupees?|%|am\b|pm\b|minutes?|mins?|hours?|days?|items?|points?)""").containsMatchIn(after)) return true
        // Order, account, phone and card numbers.
        if (Regex("""(order|account|acct|a/c|ref\w*|invoice|phone|tel|call|card|ending( in)?|no\.?|number|#)\s*:?\s*$""").containsMatchIn(before)) return true
        return false
    }
}
