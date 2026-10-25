package app.brokeometer.tasks

import app.brokeometer.mail.MailItem
import app.brokeometer.mail.MailPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TaskPromptsTest {

    private val today = LocalDate.of(2026, 10, 25)
    private fun mail(subject: String, text: String) = MailItem(
        uid = 1, messageId = "<$subject@x>", fromName = "EY", fromAddress = "jobs@ey.com", to = "me@gmail.com",
        subject = subject, receivedAt = 1_761_300_000_000L, seen = false, text = text,
    )

    @Test
    fun emailsAreNumberedAndTheirTasksComeBack() {
        val prompt = TaskPrompts.emails(listOf(mail("Interview", "See you Tuesday at 10"), mail("Newsletter", "Our news")), today, ZoneId.of("Europe/London"))
        assertTrue(prompt.contains("e0. From: EY <jobs@ey.com>"))
        assertTrue(prompt.contains("e1. From: EY"))
        val found = TaskPrompts.parse(
            """{"tasks":[{"e":"e0","what":"Go to the EY interview","day":"2026-10-27","time":"10:00"},{"e":"e9","what":"x","day":"2026-10-27"},{"e":"e1","what":"No day","day":""}]}""",
            emails = 2,
        )
        assertEquals(listOf(TaskPrompts.Found(0, MailPrompts.TaskHint("Go to the EY interview", "2026-10-27", "10:00"))), found)
    }

    @Test
    fun aNotesTasks() {
        val prompt = TaskPrompts.note("Admin", "Pay council tax by 1 Nov", today)
        assertTrue(prompt.contains("Title: Admin"))
        assertEquals(
            listOf(TaskPrompts.Found(null, MailPrompts.TaskHint("Pay council tax", "2026-11-01"))),
            TaskPrompts.parse("""```json
{"tasks":[{"what":"Pay council tax","day":"2026-11-01","time":""}]}
```"""),
        )
        assertEquals(emptyList<TaskPrompts.Found>(), TaskPrompts.parse("""{"tasks":[]}"""))
    }
}
