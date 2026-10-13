package app.brokeometer.limits

/**
 * One element of what's on screen, as Accessibility reports it: the app's own name for it ([id], such
 * as "row_thread_composer_edittext"), its kind ([cls]), and whether it's the selected tab or a text
 * box. Never what's written in it, apart from a text box's hint ("Message...") and a few button labels.
 */
data class ScreenNode(
    val id: String?,
    val cls: String? = null,
    val selected: Boolean = false,
    val editable: Boolean = false,
    val hint: String? = null,
    val desc: String? = null,
)

/** Which part of Instagram is on screen. */
enum class InstaScreen { CHAT, INBOX, PROFILE, FEED, REELS, EXPLORE, STORIES, UNKNOWN }

/**
 * Works out which part of Instagram is on screen from the names Instagram gives its elements. Those
 * names can change when Instagram updates, so anything not recognised is [InstaScreen.UNKNOWN], and
 * what the app saw can be recorded (names only) to put it right.
 */
object InstaScreens {

    const val PACKAGE = "com.instagram.android"

    fun short(id: String?): String? = id?.substringAfter(":id/")

    fun classify(nodes: List<ScreenNode>): InstaScreen {
        val ids = nodes.mapNotNull { short(it.id) }
        fun has(vararg parts: String) = ids.any { id -> parts.any { id.contains(it) } }
        // Full-screen viewers sit on top of everything else.
        if (has("reel_viewer")) return InstaScreen.STORIES
        if (has("clips_viewer", "clips_video", "clips_ufi")) return InstaScreen.REELS
        // A conversation: the box you type a message in.
        if (has("thread_composer", "direct_thread", "row_thread_composer")) return InstaScreen.CHAT
        if (nodes.any { it.editable && it.hint?.trim()?.lowercase()?.startsWith("message") == true }) return InstaScreen.CHAT
        if (has("inbox", "thread_list", "direct_inbox")) return InstaScreen.INBOX
        // Otherwise, the tab that's selected at the bottom.
        val tab = nodes.firstOrNull { it.selected && short(it.id)?.endsWith("_tab") == true }?.let { short(it.id) }
        return when (tab) {
            "feed_tab" -> InstaScreen.FEED
            "clips_tab" -> InstaScreen.REELS
            "search_tab" -> InstaScreen.EXPLORE
            "direct_tab" -> InstaScreen.INBOX
            "profile_tab" -> InstaScreen.PROFILE
            else -> InstaScreen.UNKNOWN
        }
    }

    /** Whether a screen is allowed once the day's time is used up and chats only are open. */
    fun chatAllowed(s: InstaScreen) = s == InstaScreen.CHAT || s == InstaScreen.INBOX

    /** The button for Messages: the tab at the bottom, or the paper plane at the top of the feed. */
    fun isMessagesButton(n: ScreenNode): Boolean {
        val id = short(n.id).orEmpty()
        if (id == "direct_tab" || id == "action_bar_inbox_button" || id.contains("direct_inbox_button")) return true
        val d = n.desc?.trim()?.lowercase() ?: return false
        return d == "messages" || d == "message" || d == "direct" || d == "messenger"
    }

    /** Whether a tap was on Send in a conversation. */
    fun isSend(n: ScreenNode): Boolean {
        val id = short(n.id).orEmpty()
        if (id.contains("send")) return true
        return n.desc?.trim()?.equals("send", ignoreCase = true) == true
    }

    /**
     * Whether an Instagram notification is a message (not a like, comment or post), from its channel,
     * its category, or its message style.
     */
    fun isMessage(channel: String?, category: String?, template: String?): Boolean {
        if (channel?.contains("direct", ignoreCase = true) == true) return true
        if (category == "msg") return true
        return template?.contains("MessagingStyle") == true
    }
}

/**
 * Keeps App limits from being switched off behind its back: in phone Settings, the pages that would
 * stop it (its App info with Force stop and Uninstall, its Accessibility switch, its usage access,
 * its device admin, the uninstall question) send you straight back to the home screen while it's on.
 * The pages to give it a permission it doesn't have yet are let through, so it can be set up.
 */
object Guard {

    enum class Perm { USAGE, ACCESSIBILITY, NOTIFICATIONS, ADMIN }

    val SETTINGS = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )

    /** Whether to leave this screen. [texts] are the words on it; [label] is the app's name. */
    fun shouldLeave(pkg: String, texts: List<String>, label: String, has: Set<Perm>): Boolean {
        if (pkg !in SETTINGS) return false
        val words = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (words.none { it.contains(label, ignoreCase = true) }) return false
        fun any(vararg w: String) = words.any { t -> w.any { t.contains(it, ignoreCase = true) } }
        fun exactly(vararg w: String) = words.any { t -> w.any { t.equals(it, ignoreCase = true) } }
        // Making it a device admin: let it through.
        if (Perm.ADMIN !in has && any("Activate this device admin")) return false
        if (any("Uninstall", "Force stop", "Clear storage", "Clear data") || exactly("Stop")) return true
        if (Perm.ADMIN in has && any("Deactivate this device admin", "Deactivate")) return true
        if (Perm.ACCESSIBILITY in has && any("Use $label", "$label shortcut")) return true
        if (Perm.USAGE in has && any("Permit usage access", "Allow usage access", "Usage access")) return true
        if (Perm.NOTIFICATIONS in has && any("notification access", "Allow notification")) return true
        return false
    }
}
