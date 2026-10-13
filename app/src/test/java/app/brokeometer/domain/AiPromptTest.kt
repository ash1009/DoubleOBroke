package app.brokeometer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptTest {

    private val choices = listOf(AiChoice(12, "Food / Lunch"), AiChoice(13, "Food / Dinner"), AiChoice(40, "Bills / Phone"))

    @Test
    fun questionCarriesThePaymentAndTheChoices() {
        val q = AiPrompt.build(AiPayment("Pasta Remoli", "PASTA REMOLI LTD", "London", "£13.50", "Thursday 13:10"), choices)
        assertTrue("Shop: Pasta Remoli" in q)
        assertTrue("Bank text: PASTA REMOLI LTD" in q)
        assertTrue("Town or website: London" in q)
        assertTrue("Amount: £13.50" in q)
        assertTrue("12: Food / Lunch" in q)
        assertTrue("40: Bills / Phone" in q)
    }

    @Test
    fun answersAreRead() {
        assertEquals(AiAnswer(12, 0.9f), AiPrompt.parse("""{"id": 12, "confidence": 0.9}""", choices))
        assertEquals(AiAnswer(40, 0.5f), AiPrompt.parse("""```json
{"id":"40"}
```""", choices))
        assertNull(AiPrompt.parse("""{"id": 99, "confidence": 0.9}""", choices))
        assertNull(AiPrompt.parse("I am not sure", choices))
        assertNull(AiPrompt.parse(null, choices))
    }
}
