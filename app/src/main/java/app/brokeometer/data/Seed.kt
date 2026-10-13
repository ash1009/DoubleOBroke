package app.brokeometer.data

/** The fixed category list and the examples the learner starts with. */
object Seed {

    data class Category(val name: String, val subs: List<String>)

    /** 11 categories and 54 sub-categories, taken from the owner's expense sheet. */
    val categories: List<Category> = listOf(
        Category("Food", listOf("Breakfast", "Lunch", "Dinner", "Others")),
        Category("Grocery", listOf("Cleaning Products", "Eatery", "Others")),
        Category("Health", listOf("Hospital", "Medicine", "Eye", "Dental", "Insurance", "Others")),
        Category("Bills", listOf("Maid", "WiFi", "Phone", "Others")),
        Category(
            "Travel",
            listOf("Cab", "Parking", "Metro", "Bus", "Train", "Ferry", "Flight", "Visa", "Petrol", "Insurance", "Others"),
        ),
        Category("Personal Care", listOf("Barber", "Perfume", "Bath Products", "Laundry", "Skin Care", "Others")),
        Category("Entertainment", listOf("Game", "Merch", "Movie", "Subscriptions", "Activity", "Others")),
        Category("Fashion", listOf("Clothes", "Accessory", "Others")),
        Category("Bike", listOf("Accessory", "Care", "Others")),
        Category("Electronics", listOf("Phone", "Laptop", "Earphone", "Appliance", "Accessory", "Camera", "Others")),
        Category("Other", listOf("Miscellaneous")),
    )

    /** "Personal Care" + "Skin Care" becomes "personalcare.skincare". */
    fun code(category: String, sub: String): String = "${slug(category)}.${slug(sub)}"

    private fun slug(text: String): String = text.lowercase().filter { it.isLetter() }

    data class Starter(val merchantKey: String, val code: String, val hour: Int?, val amountMinor: Long?)

    private fun s(key: String, code: String, amountMinor: Long, hour: Int? = null) = Starter(key, code, hour, amountMinor)

    /**
     * How the owner filed real payments between August and October 2026, found by matching
     * the HSBC statement against the expense sheet. Statements carry no time of day, so most
     * examples have no hour and the clock decides between lunch and dinner.
     */
    val starters: List<Starter> = listOf(
        s("UBER EATS", "food.lunch", 1760), s("UBER EATS", "food.lunch", 1893), s("UBER EATS", "food.lunch", 1703),
        s("UBER EATS", "food.lunch", 1495), s("UBER EATS", "food.lunch", 1912), s("UBER EATS", "food.lunch", 1893),
        s("UBER EATS", "food.dinner", 1818), s("UBER EATS", "food.dinner", 2182), s("UBER EATS", "food.dinner", 2102),
        s("UBER EATS", "food.dinner", 1730), s("UBER EATS", "food.dinner", 1427),
        s("UBER TRIP", "travel.cab", 8162), s("UBER TRIP", "travel.cab", 642), s("UBER TRIP", "travel.cab", 608),
        s("TFL TRAVEL CHARGE", "travel.bus", 175), s("TFL TRAVEL CHARGE", "travel.bus", 175),
        s("TFL TRAVEL CHARGE", "travel.metro", 1560),
        s("TFL TRAVEL CH", "travel.bus", 175), s("TFL TRAVEL CH", "travel.metro", 1560),
        s("SAINSBURYS MKTS", "grocery.eatery", 1015), s("SAINSBURYS MKTS", "grocery.eatery", 655),
        s("EE TOPUP VESTA", "bills.phone", 1000),
        s("GOOGLE PLAY APPS", "entertainment.game", 499),
        s("SUBWAY", "food.lunch", 1555, hour = 15),
        s("ORIGINAL BIRYANIWA", "food.dinner", 1300),
        s("NYX GMGVENDING", "food.others", 300),
        s("DONDON LTD", "food.lunch", 1500),
        s("HEAVENLY DESSERTS", "food.dinner", 1190),
        s("LEBANESE FOOD", "food.others", 150),
        s("LEBANESE KITCHEN", "food.dinner", 700),
        s("SUMUP BIRYANI GUY", "food.dinner", 1398),
        s("TASTE OF LAHORE", "food.lunch", 2250),
        s("SSP", "food.lunch", 1631),
        s("WH SMITH CURIOC", "food.others", 469),
        s("MASABI GRT PAY", "travel.bus", 160),
    )
}
