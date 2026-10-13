package app.brokeometer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoicePaymentTest {

    @Test
    fun readsAmountsOnThePhone() {
        val d = VoicePayment.local("3.4 pounds for metro at Vauxhall")
        assertEquals(340L, d.amountMinor)
        assertEquals("GBP", d.currency)
        assertEquals("Vauxhall", d.where)
        assertEquals("Metro", d.note)
        assertEquals(0, d.daysAgo)

        assertEquals(340L, VoicePayment.local("£3.40 for metro at Vauxhall").amountMinor)
        assertEquals(340L, VoicePayment.local("3 pounds 40 for the bus").amountMinor)
        assertEquals(45L, VoicePayment.local("45p for a banana").amountMinor)
        assertEquals(1200L, VoicePayment.local("12 quid on lunch at Pret yesterday").amountMinor)
        assertEquals(1, VoicePayment.local("12 quid on lunch at Pret yesterday").daysAgo)
        assertEquals("Pret", VoicePayment.local("12 quid on lunch at Pret yesterday").where)

        val rupees = VoicePayment.local("250 rupees for auto")
        assertEquals(25000L, rupees.amountMinor)
        assertEquals("INR", rupees.currency)
        assertEquals("Auto", rupees.where)

        val bare = VoicePayment.local("5.5 for coffee")
        assertEquals(550L, bare.amountMinor)
        assertNull(bare.currency)
        assertNull(VoicePayment.local("coffee at Pret").amountMinor)
    }

    @Test
    fun readsTheAisAnswer() {
        val choices = listOf(AiChoice(7, "Transport / Tube"), AiChoice(9, "Food / Lunch"))
        val ai = VoicePayment.parseAi(
            """Sure: {"amount": 3.4, "currency": "GBP", "where": "Metro", "place": "Vauxhall", "note": "", "id": 7, "days_ago": 0}""",
            choices,
        )!!
        assertEquals(340L, ai.amountMinor)
        assertEquals(7L, ai.subcategoryId)
        assertEquals("Metro", ai.where)
        assertEquals("Vauxhall", ai.place)
        assertNull(VoicePayment.parseAi("""{"amount": 3.4, "id": 99}""", choices)!!.subcategoryId)
        assertNull(VoicePayment.parseAi("no idea", choices))

        // The phone's amount wins over the AI's.
        val merged = VoicePayment.merge(VoicePayment.local("£3.40 for metro at Vauxhall"), ai.copy(amountMinor = 34000))
        assertEquals(340L, merged.amountMinor)
        assertEquals("Metro", merged.where)
        assertEquals("Vauxhall", merged.place)
        assertEquals(7L, merged.subcategoryId)
    }
}
