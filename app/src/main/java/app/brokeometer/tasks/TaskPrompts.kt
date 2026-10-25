package app.brokeometer.tasks

import app.brokeometer.mail.MailItem
import app.brokeometer.mail.MailPrompts
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the AI is asked to find tasks in: emails from the last two weeks, and notes. */
object TaskPrompts {

    const val SYSTEM = "You find the things a person must do or go to, and their dates, in their emails and notes. You answer with JSON only."

    /** A task the AI found; [index] says which email, for a batch of emails. */
    data class Found(val index: Int?, val task: MailPrompts.TaskHint)

    /** Several emails at once, each numbered "e0", "e1" and so on. */
    fun emails(items: List<MailItem>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine("Today is ${TaskRules.longDay(today)}.")
        appendLine()
        items.forEachIndexed { i, it ->
            val came = Instant.ofEpochMilli(it.receivedAt).atZone(zone).toLocalDate()
            appendLine("e$i. From: ${it.fromName} <${it.fromAddress}>. Came on $came. Subject: ${it.subject.take(150)}")
            appendLine(it.text.take(EMAIL_TEXT).replace(Regex("\\s+"), " "))
            appendLine()
        }
        appendLine("For each email: ${MailPrompts.TASK_RULE}")
        appendLine("Only things still to come, today or later. Leave out emails with no task.")
        append("""Answer with JSON only: {"tasks":[{"e":"e0","what":"...","day":"YYYY-MM-DD","time":"HH:MM, or empty"}]}, or {"tasks":[]} when there are none.""")
    }

    /** One note, in its own words. */
    fun note(title: String, text: String, today: LocalDate): String = buildString {
        appendLine("Today is ${TaskRules.longDay(today)}.")
        appendLine("A note the person wrote:")
        if (title.isNotBlank()) appendLine("Title: ${title.take(200)}")
        appendLine(text.take(NOTE_TEXT))
        appendLine()
        appendLine("List anything in it the person must do or go to on or by a date: a deadline, an appointment, a booking, a bill.")
        appendLine("Use the day it is due or happens. Only things still to come, today or later. Leave out anything with no date.")
        append("""Answer with JSON only: {"tasks":[{"what":"under 10 words, starting with a verb","day":"YYYY-MM-DD","time":"HH:MM, or empty"}]}, or {"tasks":[]}.""")
    }

    /** The tasks in an answer; for a batch of [emails], each with the email it came from. */
    fun parse(text: String, emails: Int? = null): List<Found> {
        val list = MailPrompts.jsonIn(text)?.optJSONArray("tasks") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val hint = MailPrompts.parseTask(o) ?: return@mapNotNull null
            val index = if (emails == null) null else {
                o.opt("e")?.toString()?.trim()?.removePrefix("e")?.removePrefix("E")?.toIntOrNull()?.takeIf { it in 0 until emails }
                    ?: return@mapNotNull null
            }
            Found(index, hint)
        }
    }

    private const val EMAIL_TEXT = 600
    private const val NOTE_TEXT = 3000
}
