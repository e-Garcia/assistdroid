package dev.egarcia.harness.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRepositoryTest {
    private var now = 1_000_000_000L
    private val persistence = MemoryPersistence()
    private fun repo(maxItems: Int = 200, maxAgeMs: Long = 7L * 24 * 3_600_000) = NotificationRepository(persistence, { now }, maxItems, maxAgeMs)

    private val exampleApp = "com.example.messagingapp"
    private val watched = setOf(exampleApp)
    private val none: (String) -> Boolean = { false }

    private fun incoming(
        key: String = "k1", pkg: String = exampleApp, app: String = "ExampleApp", title: String? = "Ann Lee", text: String? = "Are you free for a call?",
        posted: Long = now, conversation: String? = null, messages: List<CapturedMessage> = emptyList(),
        summary: Boolean = false, ongoing: Boolean = false, secret: Boolean = false,
    ) = IncomingNotification(key, pkg, app, posted, title, text, conversation, messages, summary, ongoing, secret)

    @Test
    fun capturesNotificationsOnlyFromWatchedApps() {
        val r = repo()
        assertNotNull(r.onPosted(incoming(), watched, none))
        assertNull("not on the allowlist", r.onPosted(incoming("k2", "com.other.app", "Other"), watched, none))
        assertEquals(1, r.count())
    }

    @Test
    fun nothingIsCapturedByDefault() {
        val r = repo()
        assertNull(r.onPosted(incoming(), emptySet(), none))
        assertEquals(0, r.count())
    }

    @Test
    fun blockedAppsAreNeverCapturedNorListed() {
        val r = repo()
        val denied: (String) -> Boolean = { it == "com.mybank.app" }
        assertNull(r.onPosted(incoming("b", "com.mybank.app", "MyBank", "Balance", "Your code is 123456"), setOf("com.mybank.app"), denied))
        assertEquals(0, r.count())
        assertFalse("a blocked app is not even offered for watching", r.seenApps().any { it.pkg == "com.mybank.app" })
    }

    @Test
    fun ongoingSecretAndGroupSummaryNotificationsAreIgnored() {
        val r = repo()
        assertNull(r.onPosted(incoming("a", ongoing = true), watched, none))
        assertNull(r.onPosted(incoming("b", secret = true), watched, none))
        assertNull(r.onPosted(incoming("c", summary = true), watched, none))
        assertNull("nothing to show", r.onPosted(incoming("d", title = null, text = "  "), watched, none))
        assertEquals(0, r.count())
    }

    @Test
    fun unwatchedAppsAppearInTheSeenListWithoutAnyContent() {
        val r = repo()
        r.onPosted(incoming("x1", "com.google.android.gm", "Gmail", "Boss", "Secret plans"), watched, none)
        r.onPosted(incoming("x2", "com.google.android.gm", "Gmail", "Boss", "More secrets"), watched, none)
        val seen = r.seenApps().single()
        assertEquals("Gmail", seen.label)
        assertEquals(2, seen.count)
        assertFalse("no content is stored for an unwatched app", persistence.read()!!.contains("Secret plans") || persistence.read()!!.contains("Boss"))
    }

    @Test
    fun anUpdatedNotificationReplacesTheOldOneWithTheSameKey() {
        val r = repo()
        r.onPosted(incoming("k", text = "first"), watched, none)
        r.onPosted(incoming("k", text = "second", posted = now + 1000), watched, none)
        assertEquals(listOf("second"), r.recent(null, 86_400_000, 10).map { it.text })
    }

    @Test
    fun conversationMessagesAreKeptAndBounded() {
        val r = repo()
        val msgs = (1..9).map { CapturedMessage("Ann", "message $it", now + it) }
        val stored = r.onPosted(incoming(conversation = "Ann Lee", messages = msgs), watched, none)!!
        assertEquals(NotificationRepository.MAX_MESSAGES, stored.messages.size)
        assertEquals("message 9", stored.messages.last().text)
    }

    @Test
    fun textIsCleanedAndTruncated() {
        val r = repo()
        val stored = r.onPosted(incoming(title = "Ann\u0007  Lee", text = "x".repeat(2000)), watched, none)!!
        assertEquals("Ann Lee", stored.title)
        assertEquals(NotificationRepository.MAX_TEXT, stored.text!!.length)
    }

    @Test
    fun oldNotificationsAndTheOldestOverTheLimitAreDropped() {
        val r = repo(maxItems = 3, maxAgeMs = 60_000)
        r.onPosted(incoming("old", posted = now - 120_000), watched, none)
        assertEquals("older than the retention window", 0, r.count())
        (1..5).forEach { r.onPosted(incoming("n$it", text = "t$it", posted = now + it), watched, none) }
        assertEquals(listOf("t5", "t4", "t3"), r.recent(null, 86_400_000, 10).map { it.text })
    }

    @Test
    fun recentFiltersByAppLabelOrPackageAndByTime() {
        val r = repo()
        val both = setOf(exampleApp, "com.google.android.gm")
        r.onPosted(incoming("a", text = "li", posted = now - 10 * 60_000), both, none)
        r.onPosted(incoming("b", "com.google.android.gm", "Gmail", "Newsletter", "mail", posted = now - 5 * 60_000), both, none)
        assertEquals(listOf("li"), r.recent("example", 3_600_000, 10).map { it.text })
        assertEquals(listOf("mail"), r.recent("com.google.android.gm", 3_600_000, 10).map { it.text })
        assertEquals(listOf("mail", "li"), r.recent(null, 3_600_000, 10).map { it.text })
        assertEquals(listOf("mail"), r.recent(null, 6 * 60_000, 10).map { it.text })
        assertEquals(1, r.recent(null, 3_600_000, 1).size)
    }

    @Test
    fun clearRemovesEverythingOrOneApp() {
        val r = repo()
        val both = setOf(exampleApp, "com.google.android.gm")
        r.onPosted(incoming("a"), both, none)
        r.onPosted(incoming("b", "com.google.android.gm", "Gmail"), both, none)
        r.clear(exampleApp)
        assertEquals(listOf("com.google.android.gm"), r.recent(null, 86_400_000, 10).map { it.pkg })
        r.clear()
        assertEquals(0, r.count())
        assertTrue("the seen list survives so the user can still choose apps", r.seenApps().isNotEmpty())
    }

    @Test
    fun stateSurvivesARestart() {
        repo().onPosted(incoming(text = "kept"), watched, none)
        assertEquals("kept", repo().recent(null, 86_400_000, 5).single().text)
    }

    @Test
    fun formatterShowsAgeAppAndConversationAndFramesItAsUntrusted() {
        val items = listOf(
            CapturedNotification("k", exampleApp, "ExampleApp", now - 12 * 60_000, "Ann Lee", null, "Ann Lee", listOf(CapturedMessage("Ann", "Hi there"), CapturedMessage("Ann", "Free for a call?"))),
            CapturedNotification("j", "com.x", "Gmail", now - 3 * 3_600_000, "Newsletter", "Weekly digest"),
        )
        val text = InboxFormatter.notifications(items, now)
        assertTrue(text, text.startsWith("<notifications untrusted=\"true\">") && text.endsWith("</notifications>"))
        assertTrue(text, "12 min ago · ExampleApp · Ann Lee" in text && "Ann: Free for a call?" in text && "3 h ago · Gmail · Newsletter" in text && "Weekly digest" in text)
    }

    @Test
    fun formatterExplainsAnEmptyResult() {
        assertTrue(InboxFormatter.notifications(emptyList(), now).contains("No captured notifications"))
    }

    @Test
    fun ageIsReadable() {
        assertEquals("just now", InboxFormatter.age(now, now - 20_000))
        assertEquals("45 min ago", InboxFormatter.age(now, now - 45 * 60_000))
        assertEquals("5 h ago", InboxFormatter.age(now, now - 5 * 3_600_000))
        assertEquals("3 d ago", InboxFormatter.age(now, now - 3 * 24 * 3_600_000))
    }
}
