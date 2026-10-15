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
     * Made-up examples of common shops, so a new phone can guess from the very first payment.
     * The owner's own choices take over as soon as a few payments are filed.
     */
    val starters: List<Starter> = listOf(
        s("UBER EATS", "food.lunch", 1650), s("UBER EATS", "food.dinner", 1900),
        s("UBER TRIP", "travel.cab", 1200),
        s("TFL TRAVEL CHARGE", "travel.metro", 290), s("TFL TRAVEL CHARGE", "travel.bus", 175),
        s("TFL TRAVEL CH", "travel.metro", 290),
        s("TESCO STORES", "grocery.eatery", 1200), s("SAINSBURYS", "grocery.eatery", 900),
        s("PRET A MANGER", "food.breakfast", 450, hour = 9), s("PRET A MANGER", "food.lunch", 750, hour = 13),
        s("GREGGS", "food.breakfast", 350, hour = 8),
        s("NANDOS", "food.dinner", 1600),
        s("SUBWAY", "food.lunch", 650, hour = 13),
        s("EE", "bills.phone", 1500),
        s("SPOTIFY", "entertainment.subscriptions", 1199),
        s("NETFLIX", "entertainment.subscriptions", 1099),
        s("BOOTS", "health.medicine", 600),
        s("TRAINLINE", "travel.train", 2500),
    )
}
