package app.brokeometer.data

/** One card on Home, shown or not. */
data class HomeCard(val key: String, val on: Boolean = true)

/**
 * Where each feature shows: its card on Home (in the order picked, switched on or off), and whether it
 * has a place in the bottom bar (up to [MAX_BAR] of them; Home and More are always there). The ones
 * in the bar are also the order you swipe through; the rest are under More, in the same list's order.
 * No Android in here, so it can be tested on its own.
 */
object Layout {

    /** Every feature, in the order the app starts with. */
    val FEATURES = listOf("email", "money", "travel", "news", "limits", "notes", "passwords", "tasks")

    /** Places in the bottom bar besides Home and More. */
    const val MAX_BAR = 3

    fun name(key: String): String = when (key) {
        "email" -> "Email"
        "money" -> "Money"
        "travel" -> "Travel"
        "news" -> "News"
        "limits" -> "App limits"
        "notes" -> "Notes"
        "passwords" -> "Passwords"
        "tasks" -> "Tasks"
        else -> key
    }

    /** Known cards once each, in order; any feature missing goes at the end, switched on. */
    fun cleanCards(cards: List<HomeCard>): List<HomeCard> {
        val known = cards.filter { it.key in FEATURES }.distinctBy { it.key }
        return known + FEATURES.filter { f -> known.none { it.key == f } }.map { HomeCard(it) }
    }

    fun encodeCards(cards: List<HomeCard>): String = cards.joinToString(",") { "${it.key}:${if (it.on) 1 else 0}" }

    fun decodeCards(text: String?): List<HomeCard>? {
        if (text.isNullOrBlank()) return null
        return cleanCards(text.split(',').mapNotNull { part ->
            val bits = part.trim().split(':')
            if (bits.isEmpty() || bits[0].isBlank()) null else HomeCard(bits[0], bits.getOrNull(1) != "0")
        })
    }

    /** Every feature once, in order. */
    fun cleanOrder(order: List<String>): List<String> = (order.filter { it in FEATURES } + FEATURES).distinct()

    /** At most [MAX_BAR] ticked, the first ones in [order] kept. */
    fun cleanTicked(order: List<String>, ticked: Set<String>): Set<String> =
        cleanOrder(order).filter { it in ticked }.take(MAX_BAR).toSet()

    /** The features in the bar, in order. */
    fun inBar(order: List<String>, ticked: Set<String>): List<String> = cleanOrder(order).filter { it in ticked }

    /** The features under More, in order. */
    fun underMore(order: List<String>, ticked: Set<String>): List<String> = cleanOrder(order).filter { it !in ticked }

    /** Ticks or unticks [key]. Ticking a fourth does nothing: one has to come out first. */
    fun tick(order: List<String>, ticked: Set<String>, key: String, on: Boolean): Set<String> = when {
        !on -> ticked - key
        key in ticked || ticked.size >= MAX_BAR -> ticked
        else -> ticked + key
    }

    /** Moves [key] up (-1) or down (+1). */
    fun <T> move(list: List<T>, index: Int, by: Int): List<T> {
        val to = index + by
        if (index !in list.indices || to !in list.indices) return list
        val out = list.toMutableList()
        val item = out.removeAt(index)
        out.add(to, item)
        return out
    }

    /**
     * From the settings before this: the agents in their order (Email, Money and Travel had places in
     * the bar; App limits only a Home card) and More's order.
     */
    fun fromOld(agentOrder: List<String>, moreOrder: List<String>): Triple<List<HomeCard>, List<String>, Set<String>> {
        val barAgents = agentOrder.filter { it in setOf("email", "money", "travel") }
        val order = cleanOrder(barAgents + moreOrder)
        val cards = cleanCards(agentOrder.map { HomeCard(it) })
        return Triple(cards, order, barAgents.take(MAX_BAR).toSet())
    }
}
