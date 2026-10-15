package app.brokeometer.domain

/** Money that came in, read from a bank alert. Never a payment. */
data class ParsedMoneyIn(
    val amountMinor: Long,
    val currency: String,
    /** Who sent it, as the bank shows it, for example "MORGAN A". */
    val from: String,
    /** Their reference, for example "STARLING-k3pQ9xz", or empty. */
    val reference: String,
    /** The alert's middle part exactly as sent, for matching. */
    val descriptor: String,
    /** "HSBC" or "Starling". */
    val bank: String,
    val account: String = "",
)

sealed interface StarlingResult {
    data class MoneyIn(val money: ParsedMoneyIn) : StarlingResult
    data class Ignored(val reason: String) : StarlingResult
}

/**
 * Reads Starling alerts. So far only money coming in is known:
 *   title: "Starling"
 *   text:  "You received £100.00 from MORGAN A (STARLING-k3pQ9xz)"
 * Card payments and payments out are left for when their wording is known.
 */
object StarlingParser {

    const val BANK = "Starling"

    private val received = Regex(
        """^\s*You received\s+(\D{0,4}?)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s+from\s+(.+?)\s*(?:\(([^()]*)\))?\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(title: String?, text: String?): StarlingResult {
        val body = text.orEmpty().trim()
        val match = received.find(body)
            ?: return StarlingResult.Ignored(if (body.isEmpty()) "Empty alert" else "Not money in")
        val amountMinor = HsbcParser.minorOf(match.groupValues[2]) ?: return StarlingResult.Ignored("Amount not readable")
        if (amountMinor <= 0) return StarlingResult.Ignored("Amount not readable")
        val from = match.groupValues[3].trim().replace(Regex("""\s+"""), " ")
        val reference = match.groupValues[4].trim()
        return StarlingResult.MoneyIn(
            ParsedMoneyIn(
                amountMinor = amountMinor,
                currency = HsbcParser.currencyFor(match.groupValues[1]),
                from = from,
                reference = reference,
                descriptor = listOf(from, reference).filter { it.isNotEmpty() }.joinToString(" "),
                bank = BANK,
            )
        )
    }
}

/** Names and references in bank text, for telling transfers between your own accounts apart. */
object MoneyText {

    private val nameBreaks = Regex("""[\s(),/]+""")
    private val referenceBreaks = Regex("""[\s(),|]+""")

    /** Words made only of letters, in capitals: "Alex Morgan   STARLING-k3pQ9xz" gives ALEX, MORGAN. */
    fun nameTokens(text: String): List<String> =
        text.uppercase().split(nameBreaks)
            .map { it.trim('.', '-', '\'', '*') }
            .filter { it.isNotEmpty() && it.all { c -> c.isLetter() } }

    /** The name part of a bank text: its letter-only words, in order. */
    fun nameOf(text: String): String = nameTokens(text).joinToString(" ")

    /**
     * References: tokens of six or more letters and digits that mix both, with any dashes taken out,
     * in capitals. "STARLING-k3pQ9xz" gives STARLINGK3PQ9XZ.
     */
    fun referencesOf(text: String): List<String> =
        text.uppercase().split(referenceBreaks)
            .map { token -> token.filter { it.isLetterOrDigit() } }
            .filter { t -> t.length >= 6 && t.any { it.isDigit() } && t.any { it.isLetter() } }
            .distinct()

    fun sharesReference(a: String, b: String): Boolean {
        val refs = referencesOf(a).toSet()
        return refs.isNotEmpty() && referencesOf(b).any { it in refs }
    }

    /**
     * True when [text] names the owner: every word of their full name ("ALEX MORGAN"), their surname
     * with their first initial ("MORGAN A"), or a name already learned as theirs.
     */
    fun namesOwner(text: String, ownerName: String, learned: Set<String>): Boolean {
        val words = nameTokens(text)
        if (words.isEmpty()) return false
        val owner = nameTokens(ownerName)
        if (owner.size >= 2) {
            if (owner.all { it in words }) return true
            val first = owner.first()
            val last = owner.last()
            if (last in words && words.any { it.length == 1 && it[0] == first[0] }) return true
        }
        return learned.any { name ->
            val tokens = nameTokens(name)
            tokens.size >= 2 && tokens.all { it in words }
        }
    }
}

/** A payment or transfer that left one of your accounts, as seen by the matcher. */
data class OutSide(
    val amountMinor: Long,
    val currency: String,
    val at: Long,
    /** "HSBC" or "Starling". */
    val bank: String,
    val text: String,
)

/** Money that came into one of your accounts, as seen by the matcher. */
data class InSide(
    val amountMinor: Long,
    val currency: String,
    val at: Long,
    val bank: String,
    val text: String,
)

/** Decides whether a money out and a money in are one transfer between your own accounts. */
object TransferMatch {

    /** Bank alerts for the two sides of a transfer come within minutes; half an hour leaves room. */
    const val WINDOW_MS = 30 * 60 * 1000L

    /** Same amount and currency, at two different banks, close in time. On its own this proves nothing. */
    fun sameMoney(out: OutSide, inn: InSide): Boolean =
        out.amountMinor == inn.amountMinor &&
            out.currency == inn.currency &&
            !out.bank.equals(inn.bank, ignoreCase = true) &&
            kotlin.math.abs(out.at - inn.at) <= WINDOW_MS

    /**
     * A transfer out already known, and money in: one transfer when they share a reference or the money
     * in comes from you. An equal amount alone is not enough: a friend can send £20 just as you move £20.
     */
    fun isPair(out: OutSide, inn: InSide, ownerName: String, learned: Set<String>): Boolean =
        sameMoney(out, inn) &&
            (MoneyText.sharesReference(out.text, inn.text) || MoneyText.namesOwner(inn.text, ownerName, learned))

    /**
     * A payment already saved, and money in: the payment was really a transfer to your own account when
     * they share a reference or the payment names you. Money in from you is not enough on its own, or a
     * £20 shop payment could be taken for the £20 you moved between accounts.
     */
    fun paymentWasTransfer(out: OutSide, inn: InSide, ownerName: String, learned: Set<String>): Boolean =
        sameMoney(out, inn) &&
            (MoneyText.sharesReference(out.text, inn.text) || MoneyText.namesOwner(out.text, ownerName, learned))
}
