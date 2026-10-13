package app.brokeometer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategorizerTest {

    private val breakfast = 1L
    private val lunch = 2L
    private val dinner = 3L
    private val foodOthers = 4L
    private val cab = 10L
    private val bus = 11L
    private val metro = 12L
    private val eatery = 20L

    private val codes = mapOf("travel.cab" to cab, "grocery.eatery" to eatery)
    private val learner = Categorizer(MealIds(breakfast, lunch, dinner)) { codes[it] }

    private val starter = listOf(
        Example("UBER EATS", lunch, null, 1760, true), Example("UBER EATS", dinner, null, 1818, true),
        Example("UBER EATS", lunch, null, 1893, true), Example("UBER EATS", dinner, null, 2182, true),
        Example("UBER TRIP", cab, null, 642, true),
        Example("TFL TRAVEL CHARGE", bus, null, 175, true), Example("TFL TRAVEL CHARGE", bus, null, 175, true),
        Example("TFL TRAVEL CHARGE", metro, null, 1560, true),
        Example("SAINSBURYS MKTS", eatery, null, 1015, true),
        Example("SUBWAY", lunch, 15, 1555, true),
        Example("NYX GMGVENDING", foodOthers, null, 300, true),
    )

    private fun guess(key: String, name: String, hour: Int, pence: Long, examples: List<Example> = starter) =
        learner.guess(key, name, hour, 0, pence, examples)

    @Test
    fun theClockDecidesTheMealForAFoodPlace() {
        assertEquals(lunch, guess("UBER EATS", "Uber Eats", 14, 1700).subcategoryId)
        assertEquals(dinner, guess("UBER EATS", "Uber Eats", 20, 1700).subcategoryId)
        assertEquals(breakfast, guess("UBER EATS", "Uber Eats", 9, 900).subcategoryId)
    }

    @Test
    fun amountSeparatesBusFromMetro() {
        assertEquals(bus, guess("TFL TRAVEL CHARGE", "TfL Travel Charge", 9, 175).subcategoryId)
        assertEquals(metro, guess("TFL TRAVEL CHARGE", "TfL Travel Charge", 9, 1070).subcategoryId)
    }

    @Test
    fun aSnackMachineStaysUnderOthersEvenAtLunchTime() {
        assertEquals(foodOthers, guess("NYX GMGVENDING", "NYX GMGVending", 13, 300).subcategoryId)
    }

    @Test
    fun similarNamesBorrowFromKnownOnes() {
        val g = guess("SAINSBURYS", "Sainsburys", 18, 800)
        assertEquals(eatery, g.subcategoryId)
        assertEquals(GuessSource.SIMILAR, g.source)
    }

    @Test
    fun wordHintsCoverMerchantsNeverSeen() {
        assertEquals(eatery, guess("TESCO STORES", "Tesco Stores", 18, 260).subcategoryId)
        assertEquals(lunch, guess("HONEST BURGER HOLB", "Honest Burger Holb", 13, 1480).subcategoryId)
        assertEquals(cab, guess("BOLT", "Bolt", 1, 900).subcategoryId)
    }

    @Test
    fun noGuessWhenNothingMatches() {
        val g = guess("ONLINE STORE", "Online Store", 12, 11500)
        assertNull(g.subcategoryId)
        assertEquals(GuessSource.NONE, g.source)
    }

    @Test
    fun theOwnersCorrectionsWinOverTheStarterExamples() {
        val learned = starter + listOf(
            Example("UBER TRIP", bus, 9, 700, false), Example("UBER TRIP", bus, 9, 650, false),
            Example("UBER TRIP", bus, 10, 720, false),
        )
        val g = guess("UBER TRIP", "Uber Trip", 9, 690, learned)
        assertEquals(bus, g.subcategoryId)
        assertEquals(GuessSource.HISTORY, g.source)
    }

    @Test
    fun mealBoundaries() {
        assertEquals(Meal.DINNER, mealForHour(4, 59))
        assertEquals(Meal.BREAKFAST, mealForHour(5, 0))
        assertEquals(Meal.BREAKFAST, mealForHour(10, 59))
        assertEquals(Meal.LUNCH, mealForHour(11, 0))
        assertEquals(Meal.LUNCH, mealForHour(15, 30))
        assertEquals(Meal.LUNCH, mealForHour(16, 29))
        assertEquals(Meal.DINNER, mealForHour(16, 30))
        assertEquals(Meal.DINNER, mealForHour(23, 0))
    }
}
