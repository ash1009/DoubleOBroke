package app.brokeometer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LogKindsTest {

    @Test
    fun eachLineGoesUnderItsKind() {
        assertEquals(LogKinds.Kind.EMAIL, LogKinds.kindOf("com.google.android.gm", "Email: the agent is checking it"))
        assertEquals(LogKinds.Kind.NEWS, LogKinds.kindOf(LogKinds.NEWS_SOURCE, "News: briefing ready, 9 stories"))
        assertEquals(LogKinds.Kind.PAYMENTS, LogKinds.kindOf("com.starlingbank.android", "Saved, waiting for you"))
        assertEquals(LogKinds.Kind.PAYMENTS, LogKinds.kindOf("uk.co.hsbc.hsbcukmobilebanking", "Money in: asking if it pays you back"))
        assertEquals(LogKinds.Kind.PAYMENTS, LogKinds.kindOf("com.google.android.apps.messaging", "Skipped: not a payment"))
    }
}
