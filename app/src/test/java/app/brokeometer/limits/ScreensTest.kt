package app.brokeometer.limits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensTest {

    private fun id(name: String, selected: Boolean = false) = ScreenNode("com.instagram.android:id/$name", selected = selected)
    private val tabs = listOf("feed_tab", "clips_tab", "direct_tab", "search_tab", "profile_tab")
    private fun on(tab: String) = tabs.map { id(it, selected = it == tab) }

    @Test
    fun knowsTheScreens() {
        assertEquals(InstaScreen.FEED, InstaScreens.classify(on("feed_tab")))
        assertEquals(InstaScreen.REELS, InstaScreens.classify(on("clips_tab")))
        assertEquals(InstaScreen.EXPLORE, InstaScreens.classify(on("search_tab")))
        assertEquals(InstaScreen.PROFILE, InstaScreens.classify(on("profile_tab")))
        assertEquals(InstaScreen.INBOX, InstaScreens.classify(on("direct_tab")))
        assertEquals(InstaScreen.CHAT, InstaScreens.classify(listOf(id("row_thread_composer_edittext"))))
        assertEquals(InstaScreen.CHAT, InstaScreens.classify(listOf(ScreenNode(null, editable = true, hint = "Message..."))))
        // A reel opened from a chat is a reel, though the chat is still underneath.
        assertEquals(InstaScreen.REELS, InstaScreens.classify(listOf(id("row_thread_composer_edittext"), id("clips_viewer_view_pager"))))
        assertEquals(InstaScreen.STORIES, InstaScreens.classify(on("feed_tab") + id("reel_viewer_root")))
        assertEquals(InstaScreen.UNKNOWN, InstaScreens.classify(listOf(id("something_new"))))
        assertTrue(InstaScreens.chatAllowed(InstaScreen.INBOX))
        assertFalse(InstaScreens.chatAllowed(InstaScreen.REELS))
    }

    @Test
    fun knowsMessages() {
        assertTrue(InstaScreens.isMessage("ig_direct", null, null))
        assertTrue(InstaScreens.isMessage(null, "msg", null))
        assertTrue(InstaScreens.isMessage("other", null, "android.app.Notification\$MessagingStyle"))
        assertFalse(InstaScreens.isMessage("ig_likes", "social", null))
        assertTrue(InstaScreens.isMessagesButton(ScreenNode(null, desc = "Messages")))
        assertTrue(InstaScreens.isSend(ScreenNode("com.instagram.android:id/row_thread_composer_send_button_container")))
    }

    private val me = "Double O Broke"
    private val all = Guard.Perm.entries.toSet()

    @Test
    fun guardsTheWaysToSwitchItOff() {
        assertTrue(Guard.shouldLeave("com.android.settings", listOf("App info", me, "Uninstall", "Force stop"), me, all))
        assertTrue(Guard.shouldLeave("com.google.android.packageinstaller", listOf(me, "Do you want to uninstall this app?"), me, all))
        assertTrue(Guard.shouldLeave("com.android.settings", listOf(me, "Use $me", "Accessibility"), me, all))
        assertTrue(Guard.shouldLeave("com.android.settings", listOf(me, "Permit usage access"), me, all))
        assertTrue(Guard.shouldLeave("com.android.settings", listOf(me, "Deactivate this device admin app"), me, all))
        // Someone else's App info, the list of accessibility apps, and other apps are fine.
        assertFalse(Guard.shouldLeave("com.android.settings", listOf("App info", "Instagram", "Uninstall", "Force stop"), me, all))
        assertFalse(Guard.shouldLeave("com.android.settings", listOf("Accessibility", me, "TalkBack"), me, all))
        assertFalse(Guard.shouldLeave("com.instagram.android", listOf(me, "Uninstall"), me, all))
    }

    @Test
    fun letsYouGiveWhatItDoesntHave() {
        val none = emptySet<Guard.Perm>()
        assertFalse(Guard.shouldLeave("com.android.settings", listOf(me, "Permit usage access"), me, none))
        assertFalse(Guard.shouldLeave("com.android.settings", listOf(me, "Activate this device admin app", "Uninstall app"), me, none))
        assertTrue(Guard.shouldLeave("com.android.settings", listOf(me, "Activate this device admin app", "Uninstall app"), me, all))
    }
}
