package app.brokeometer.mail

import org.json.JSONException
import org.json.JSONObject

/** A sender as the AI sees it: who, how much, and what they write about. */
data class SenderSummary(
    val name: String,
    val address: String,
    val emails: Int,
    val mailingList: Boolean,
    val subjects: List<String>,
)

data class CategoryIdea(val name: String, val description: String, val important: Boolean)

/** Words for the AI, and reading its answers. Kept apart from the network so it can be tested. */
object MailPrompts {

    /** What the owner said matters, in his words. */
    val IMPORTANT = listOf(
        "Emails a real person wrote to me",
        "Login codes I asked for",
        "Bank, card or account security alerts",
        "Bills due, failed payments, refunds",
        "Government stuff (visa, HMRC, council, NHS)",
        "Job or interview emails",
        "Booking changes (flight delays, cancellations)",
        "Delivery problems (not routine \"out for delivery\")",
    )
    val NOT_IMPORTANT = listOf("Newsletters", "Promos", "Social media", "Receipts", "Routine order updates")

    const val SYSTEM =
        "You help one person sort their email. You are careful, practical and you answer with JSON only, no other text."

    private fun line(i: String, s: SenderSummary): String {
        val who = if (s.name.isNotBlank()) "${s.name.take(60)} <${s.address}>" else "<${s.address}>"
        val list = if (s.mailingList) "mailing list" else "direct"
        val subjects = s.subjects.take(3).joinToString(" ; ") { it.take(90) }
        return "$i. $who | ${s.emails} emails | $list | $subjects"
    }

    fun designCategories(senders: List<SenderSummary>): String = buildString {
        appendLine("Below are the people and companies who email this person most: how many emails, whether it is a mailing list, and recent subjects.")
        appendLine()
        appendLine("Make a list of 10 to 20 categories that together cover nearly all of their email.")
        appendLine("Each category will get a switch: on means \"notify me\", off means \"mark read and archive\".")
        appendLine()
        appendLine("What they told me matters to them:")
        appendLine("Important: " + IMPORTANT.joinToString("; ") + ".")
        appendLine("Not important: " + NOT_IMPORTANT.joinToString("; ") + ".")
        appendLine()
        appendLine("Rules:")
        appendLine("- Have one category for each item on the important list, and categories for the not important kinds.")
        appendLine("- Add others only when their email clearly needs them (for example a school, club, hobby or work).")
        appendLine("- Names are 1 to 3 plain words. Descriptions are one short line saying what goes in.")
        appendLine("- Set important to true only for kinds on the important list.")
        appendLine()
        appendLine("Senders:")
        senders.forEachIndexed { i, s -> appendLine(line("${i + 1}", s)) }
        appendLine()
        append("""Answer with JSON only: {"categories":[{"name":"...","description":"...","important":true}]}""")
    }

    fun parseCategories(text: String): List<CategoryIdea> {
        val root = jsonIn(text) ?: return emptyList()
        val arr = root.optJSONArray("categories") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim().take(40)
            if (name.isEmpty()) return@mapNotNull null
            CategoryIdea(name, o.optString("description").trim().take(160), o.optBoolean("important", false))
        }.distinctBy { it.name.lowercase() }.take(30)
    }

    fun assignSenders(categories: List<Pair<String, String>>, senders: List<SenderSummary>): String = buildString {
        appendLine("Categories:")
        categories.forEachIndexed { i, (name, description) -> appendLine("$i. $name: $description") }
        appendLine()
        appendLine("Put each sender below in the category their emails usually belong to.")
        appendLine("If a sender sends a mix, pick the most common kind. A person writing from a personal address goes in the category for real people.")
        appendLine()
        appendLine("Senders:")
        senders.forEachIndexed { i, s -> appendLine(line("s$i", s)) }
        appendLine()
        append("""Answer with JSON only, every sender once, values are category numbers: {"a":{"s0":0,"s1":3}}""")
    }

    /**
     * Sender index to category index. Accepts the asked-for {"a":{"s0":3}} and the shapes AIs often use
     * instead: no "a", plain numbers as keys, lists of pairs, or category names in place of numbers.
     * Anything out of range is dropped.
     */
    fun parseAssignments(text: String, senders: Int, categories: Int, names: List<String> = emptyList()): Map<Int, Int> {
        val root = jsonIn(text) ?: return emptyMap()
        val out = HashMap<Int, Int>()
        fun sender(key: Any?): Int? = key?.toString()?.trim()?.removePrefix("s")?.removePrefix("S")?.toIntOrNull()
        fun category(value: Any?): Int? {
            val raw = value?.toString()?.trim() ?: return null
            raw.toIntOrNull()?.let { return it }
            raw.toDoubleOrNull()?.let { return it.toInt() }
            val i = names.indexOfFirst { it.equals(raw, ignoreCase = true) }
            return i.takeIf { it >= 0 }
        }
        fun put(s: Int?, c: Int?) {
            if (s != null && c != null && s in 0 until senders && c in 0 until categories) out[s] = c
        }
        fun fromObject(o: JSONObject) {
            for (key in o.keys()) put(sender(key), category(o.opt(key)))
        }
        fun fromArray(arr: org.json.JSONArray) {
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                when (item) {
                    is org.json.JSONArray -> if (item.length() >= 2) put(sender(item.opt(0)), category(item.opt(1)))
                    is JSONObject -> {
                        val s = listOf("sender", "s", "id", "index").firstNotNullOfOrNull { k -> item.opt(k)?.let { sender(it) } }
                        val c = listOf("category", "c", "cat").firstNotNullOfOrNull { k -> item.opt(k)?.let { category(it) } }
                        if (s != null && c != null) put(s, c) else fromObject(item)
                    }
                }
            }
        }
        val inner = root.opt("a") ?: root.opt("assignments") ?: root.opt("senders")
        when (inner) {
            is JSONObject -> fromObject(inner)
            is org.json.JSONArray -> fromArray(inner)
            else -> fromObject(root)
        }
        return out
    }

    /** Several older emails at once, judged on who sent them and the subject. Answer keys are s0, s1... */
    fun tidyBatch(categories: List<MailCategoryEntity>, emails: List<MailItem>): String = buildString {
        appendLine("Categories:")
        categories.forEachIndexed { i, c -> appendLine("$i. ${c.name}: ${c.description}") }
        appendLine()
        appendLine("These are older unread emails. Pick the category each one belongs to from what the email itself says,")
        appendLine("not who sent it: a bank can send a promo, a shop or an estate agent can send news about your own request.")
        appendLine()
        appendLine("Emails:")
        emails.forEachIndexed { i, e ->
            val who = if (e.fromName.isNotBlank()) "${e.fromName.take(60)} <${e.fromAddress}>" else "<${e.fromAddress}>"
            val text = e.text.replace(Regex("\\s+"), " ").trim().take(200)
            appendLine("s$i. $who | ${e.subject.take(120)}" + if (text.isNotEmpty()) " | $text" else "")
        }
        appendLine()
        appendLine(PERSONAL_RULE)
        append("""Answer with JSON only, every email once, values are category numbers, "p" lists the personal ones: {"a":{"s0":0,"s1":3},"p":["s1"]}""")
    }

    /**
     * About the person themself, so it always reaches them whatever its category: news on something
     * they asked for or are part of.
     */
    private const val PERSONAL_RULE =
        "Personal means written about the person's own matter: a reply to them, or news on something they asked for or are part of " +
            "(an application, a request, a viewing, a booking, an order, an appointment, a job, their account, money, health or home), " +
            "or written by a real person to them. Offers, sales, newsletters and general updates are not personal."

    /** Which category one new email belongs to, whether it's personal, and a line about it for the notification. */
    /**
     * The question for one new email. With [today], it also asks for a task: something the email gives the
     * person to do or go to by a date, for Tasks.
     */
    fun decideEmail(categories: List<MailCategoryEntity>, item: MailItem, today: java.time.LocalDate? = null): String = buildString {
        appendLine("Categories:")
        categories.forEachIndexed { i, c -> appendLine("$i. ${c.name}: ${c.description}") }
        appendLine()
        appendLine("Email:")
        appendLine("From: ${item.fromName} <${item.fromAddress}>")
        appendLine("To: ${item.to.take(200)}")
        appendLine("Subject: ${item.subject.take(200)}")
        appendLine("Text (start):")
        appendLine(item.text.take(MailSession.TEXT_LIMIT))
        appendLine()
        appendLine("Pick the one category this email belongs to, from what this email says, not who sent it: a bank can send a promo, a shop or an estate agent can send news about your own request.")
        appendLine(PERSONAL_RULE)
        appendLine("Write a summary of what it is or what it asks for, under 12 words, no greeting.")
        if (today == null) {
            append("""Answer with JSON only: {"category":0,"personal":false,"summary":"..."}""")
        } else {
            val came = java.time.Instant.ofEpochMilli(item.receivedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            appendLine("Today is ${app.brokeometer.tasks.TaskRules.longDay(today)}. The email came on ${app.brokeometer.tasks.TaskRules.longDay(came)}.")
            appendLine(TASK_RULE)
            append("""Answer with JSON only: {"category":0,"personal":false,"summary":"...","task":null}""")
        }
    }

    /** What makes something in an email or a note a task, and how to write it. */
    const val TASK_RULE =
        "If it gives the person something they must do or go to on or by a date (a deadline, an interview, an assessment or " +
            "form to finish, a bill or payment due, an appointment, a booking, a delivery to be in for), add " +
            "\"task\": {\"what\":\"under 10 words, starting with a verb\",\"day\":\"YYYY-MM-DD\",\"time\":\"HH:MM, or empty\"}, " +
            "using the day it is due or happens. Otherwise \"task\" is null: for offers, sales, newsletters, receipts, events " +
            "they didn't sign up for, anything already past, and anything with no date."

    /** Something to do or go to, as the AI wrote it: [day] is "YYYY-MM-DD", [time] "HH:MM" or null. */
    data class TaskHint(val what: String, val day: String, val time: String? = null)

    data class Decision(val category: Int, val summary: String, val personal: Boolean = false, val task: TaskHint? = null)

    fun parseDecision(text: String, categories: Int): Decision? {
        val root = jsonIn(text) ?: return null
        val c = root.optInt("category", -1)
        if (c !in 0 until categories) return null
        return Decision(c, root.optString("summary").trim().take(120), root.optBoolean("personal", false), parseTask(root.optJSONObject("task")))
    }

    /** A task the AI wrote, when it has words and a real day. */
    fun parseTask(o: JSONObject?): TaskHint? {
        if (o == null) return null
        val what = o.optString("what").trim().take(120)
        val day = o.optString("day").trim()
        if (what.isEmpty() || app.brokeometer.tasks.TaskRules.day(day) == null) return null
        val time = o.optString("time").trim().takeIf { app.brokeometer.tasks.TaskRules.time(it) != null }
        return TaskHint(what, day, time)
    }

    /** The emails a tidy-up answer marked as personal ("p": ["s1", "s4"]). */
    fun parsePersonal(text: String, emails: Int): Set<Int> {
        val list = jsonIn(text)?.optJSONArray("p") ?: return emptySet()
        return (0 until list.length()).mapNotNull { i ->
            list.opt(i)?.toString()?.trim()?.removePrefix("s")?.removePrefix("S")?.toIntOrNull()?.takeIf { it in 0 until emails }
        }.toSet()
    }

    /** Is an email in the spam folder really spam? */
    fun checkSpam(item: MailItem): String = buildString {
        appendLine("This email is in the spam folder.")
        appendLine("From: ${item.fromName} <${item.fromAddress}>")
        appendLine("Subject: ${item.subject.take(200)}")
        appendLine("Text (start):")
        appendLine(item.text.take(1500))
        appendLine()
        appendLine("Is it real spam (a scam, phishing, or junk the person never asked for)? Or might the person want it")
        appendLine("(a real person, their bank, a bill, an order, a booking, a job, a login code, a school or a government office)?")
        appendLine("When unsure, say it is not spam.")
        append("""Answer with JSON only: {"spam":true,"why":"under 12 words"}""")
    }

    data class SpamVerdict(val spam: Boolean, val why: String)

    /** Anything unclear counts as not spam, so nothing wanted is ever binned. */
    fun parseSpam(text: String): SpamVerdict? {
        val root = jsonIn(text) ?: return null
        if (!root.has("spam")) return null
        return SpamVerdict(root.optBoolean("spam", false), root.optString("why").trim().take(120))
    }

    /** The JSON object in an answer, even when the AI wraps it in a code fence or adds words. */
    fun jsonIn(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(text.substring(start, end + 1))
        } catch (e: JSONException) {
            null
        }
    }
}

/** How to leave a mailing list, read from its List-Unsubscribe header. */
data class UnsubscribeWays(val web: String?, val mail: String?, val oneClick: Boolean) {
    val any: Boolean get() = web != null || mail != null

    companion object {
        fun parse(header: String?, oneClick: Boolean): UnsubscribeWays {
            if (header.isNullOrBlank()) return UnsubscribeWays(null, null, false)
            val bracketed = Regex("<([^>]+)>").findAll(header).map { it.groupValues[1].trim() }.toList()
            val parts = bracketed.ifEmpty { header.split(',').map { it.trim() } }
            val web = parts.firstOrNull { it.startsWith("https://", ignoreCase = true) }
                ?: parts.firstOrNull { it.startsWith("http://", ignoreCase = true) }
            val mail = parts.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
            return UnsubscribeWays(web, mail, oneClick && web?.startsWith("https://", ignoreCase = true) == true)
        }
    }
}
