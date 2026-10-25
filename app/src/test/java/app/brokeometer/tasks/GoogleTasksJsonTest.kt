package app.brokeometer.tasks

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class GoogleTasksJsonTest {

    @Test
    fun listsAndTasksAreRead() {
        val lists = JSONObject("""{"items":[{"id":"L1","title":"My Tasks"},{"title":"no id"}]}""")
        assertEquals(listOf("L1" to "My Tasks"), GoogleTasksJson.lists(lists))

        val tasks = JSONObject(
            """{"items":[
              {"id":"a","title":"Pause Uber One","status":"needsAction","due":"2026-10-28T00:00:00.000Z","notes":"before renewal\nsecond line"},
              {"id":"b","title":"Buy milk","status":"needsAction"},
              {"id":"c","title":"Old one","status":"completed","due":"2026-10-20T00:00:00.000Z"},
              {"id":"d","title":"  ","status":"needsAction"}
            ],"nextPageToken":"p2"}""",
        )
        val items = GoogleTasksJson.items(tasks, "My Tasks")
        assertEquals(2, items.size)
        assertEquals("Pause Uber One", items[0].title)
        assertEquals(LocalDate.of(2026, 10, 28), items[0].day)
        assertEquals("My Tasks", items[0].list)
        assertNull(items[1].day)
    }

    @Test
    fun googlesErrorMessageIsKept() {
        assertEquals("Tasks API has not been used", GoogleTasksJson.error("""{"error":{"code":403,"message":"Tasks API has not been used"}}"""))
        assertNull(GoogleTasksJson.error("not json"))
    }
}
