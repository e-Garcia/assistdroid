package dev.egarcia.harness.policy

import dev.egarcia.harness.ActionResult
import dev.egarcia.harness.Automation
import dev.egarcia.harness.Direction
import dev.egarcia.harness.Element
import dev.egarcia.harness.ScreenshotResponse
import dev.egarcia.harness.Snapshot
import dev.egarcia.harness.WaitResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PolicyAutomationTest {

    /** Records what actually reaches the device. */
    private class Phone : Automation {
        var foreground: String? = "com.app"
        var screenPackage = "com.app"
        var elements: List<Element> = emptyList()
        val calls = mutableListOf<String>()
        val tapSnapshotIds = mutableListOf<String>()
        private var n = 0
        private var staleBefore = 0

        /** Simulates the approval dialog stopping the app: every handle from an earlier snapshot is dead. */
        fun invalidateExisting() { staleBefore = n + 1 }

        override fun snapshot() = Snapshot("s_${++n}", screenPackage, elements)
        override suspend fun tap(snapshotId: String, ref: String): ActionResult {
            if (snapshotId.removePrefix("s_").toInt() < staleBefore) return ActionResult(false, "stale_ref", "Element $ref is gone")
            calls += "tap $ref"
            tapSnapshotIds += snapshotId
            return ActionResult(true)
        }
        override fun type(snapshotId: String, ref: String, text: String) = ActionResult(true).also { calls += "type $ref" }
        override suspend fun scroll(direction: Direction, snapshotId: String?, ref: String?) = ActionResult(true).also { calls += "scroll" }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult(true).also { calls += "swipe" }
        override fun back() = ActionResult(true).also { calls += "back" }
        override fun home() = ActionResult(true).also { calls += "home" }
        override fun openApp(app: String) = ActionResult(true, detail = app).also { calls += "open_app $app" }
        override suspend fun waitFor(text: String, timeoutMs: Long) = WaitResult(true, snapshot = snapshot()).also { calls += "wait_for" }
        override suspend fun screenshot(maxSide: Int) = ScreenshotResponse(true, imageBase64 = "AAAA").also { calls += "screenshot" }
        /** While > 0, the approval dialog (our own app) is still the focused window. */
        var dialogPolls = 0
        override fun foregroundPackage(): String? = if (dialogPolls > 0) { dialogPolls--; "dev.egarcia.harness" } else foreground
    }

    private class Recorder : AuditSink {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        override fun log(event: String, fields: Map<String, String>) { events += event to fields }
        fun dump() = events.joinToString("\n")
    }

    private fun el(ref: String, text: String? = null, desc: String? = null) =
        Element(ref, "button", text, desc, null, listOf(0, 0, 1, 1), clickable = true)

    private val prompts = mutableListOf<ApprovalPrompt>()
    private fun approver(decision: Decision) = Approver { prompts += it; decision }

    private fun guarded(
        phone: Phone,
        decision: Decision = Decision.APPROVED,
        audit: AuditSink = NoAudit,
        config: () -> PolicyConfig = { PolicyConfig() },
    ) = PolicyAutomation(phone, config, approver(decision), audit)

    // --- Denied apps ----------------------------------------------------------------------

    @Test
    fun aDeniedAppsScreenIsRedactedButKeepsItsPackage() {
        val phone = Phone().apply { screenPackage = "com.bitwarden.vault"; elements = listOf(el("e1", "Vault password 123")) }
        val seen = guarded(phone).snapshot()
        assertEquals("app_denied", seen.blind)
        assertEquals("com.bitwarden.vault", seen.pkg)
        assertTrue(seen.elements.isEmpty())
    }

    @Test
    fun actionsInsideADeniedAppNeverReachTheDevice() = runTest {
        val phone = Phone().apply { foreground = "com.mybank.app"; screenPackage = "com.mybank.app"; elements = listOf(el("e1", "Transfer")) }
        val policy = guarded(phone)
        val id = policy.snapshot().snapshotId
        for (result in listOf(
            policy.tap(id, "e1"), policy.type(id, "e1", "x"), policy.scroll(Direction.DOWN, null, null), policy.swipe(0, 0, 1, 1, 100),
        )) {
            assertEquals("app_denied", result.error)
        }
        val shot = policy.screenshot(800)
        assertEquals("app_denied", shot.error)
        assertNull("no image and no stray fields", shot.imageBase64)
        assertNull(shot.mime)
        assertTrue("nothing was sent to the device: ${phone.calls}", phone.calls.isEmpty())
    }

    @Test
    fun backAndHomeStillWorkSoTheAgentCanLeave() {
        val phone = Phone().apply { foreground = "com.mybank.app" }
        val policy = guarded(phone)
        assertTrue(policy.back().ok)
        assertTrue(policy.home().ok)
        assertEquals(listOf("back", "home"), phone.calls)
    }

    @Test
    fun launchingADeniedAppIsRefusedByPackageOrByName() {
        val phone = Phone()
        val policy = guarded(phone)
        assertEquals("app_denied", policy.openApp("com.bitwarden.vault").error)
        assertEquals("app_denied", policy.openApp("PayPal").error)
        assertEquals("app_denied", policy.openApp("AI Harness").error)
        assertTrue(policy.openApp("Settings").ok)
        assertEquals(listOf("open_app Settings"), phone.calls)
    }

    @Test
    fun waitingForTextCannotRevealWhatADeniedAppShows() = runTest {
        val phone = Phone().apply { screenPackage = "com.mybank.app"; elements = listOf(el("e1", "Balance 5,000")) }
        val result = guarded(phone).waitFor("Balance", 500)
        assertFalse(result.ok)
        assertEquals("timeout", result.error)
        assertTrue(result.snapshot!!.elements.isEmpty())
    }

    @Test
    fun customDeniedEntriesApplyImmediately() {
        val phone = Phone().apply { screenPackage = "com.android.settings"; foreground = "com.android.settings" }
        var config = PolicyConfig()
        val policy = guarded(phone, config = { config })
        assertNull(policy.snapshot().blind)
        config = PolicyConfig(customDenied = listOf("com.android.settings"))
        assertEquals("app_denied", policy.snapshot().blind)
    }

    // --- Confirm gate ---------------------------------------------------------------------

    @Test
    fun aRiskyTapWaitsForApprovalAndThenRuns() = runTest {
        val phone = Phone().apply { elements = listOf(el("e1", "Send")) }
        val policy = guarded(phone, Decision.APPROVED)
        val id = policy.snapshot().snapshotId
        assertTrue(policy.tap(id, "e1").ok)
        assertEquals(listOf("tap e1"), phone.calls)
        val prompt = prompts.single()
        assertEquals(Risk.SEND, prompt.risk)
        assertEquals("Send", prompt.target)
        assertEquals("com.app", prompt.pkg)
    }

    @Test
    fun anApprovedTapStillWorksWhenTheDialogKilledTheOldNodeHandles() = runTest {
        val phone = Phone().apply { elements = listOf(el("e1", "Search"), el("e2", "Send")) }
        val policy = PolicyAutomation(phone, { PolicyConfig() }, { phone.invalidateExisting(); Decision.APPROVED })
        val id = policy.snapshot().snapshotId
        val result = policy.tap(id, "e2")
        assertTrue(result.error, result.ok)
        assertEquals(listOf("tap e2"), phone.calls)
        assertTrue("tapped on a fresh snapshot, not the stale one", phone.tapSnapshotIds.single() != id)
    }

    @Test
    fun theTapWaitsUntilTheApprovalDialogHasClosedAndTheAppIsBackInFront() = runTest {
        val phone = Phone().apply { elements = listOf(el("e1", "Send")) }
        val policy = PolicyAutomation(phone, { PolicyConfig() }, { phone.invalidateExisting(); phone.dialogPolls = 5; Decision.APPROVED })
        val result = policy.tap(policy.snapshot().snapshotId, "e1")
        assertTrue(result.error, result.ok)
        assertEquals(listOf("tap e1"), phone.calls)
    }

    @Test
    fun ifTheAppNeverComesBackNothingIsTapped() = runTest {
        val phone = Phone().apply { elements = listOf(el("e1", "Send")) }
        val policy = PolicyAutomation(phone, { PolicyConfig() }, { phone.dialogPolls = Int.MAX_VALUE; Decision.APPROVED })
        assertEquals("stale_ref", policy.tap(policy.snapshot().snapshotId, "e1").error)
        assertTrue(phone.calls.isEmpty())
    }

    @Test
    fun ifTheElementIsGoneAfterApprovalNothingIsTapped() = runTest {
        val phone = Phone().apply { elements = listOf(el("e1", "Send")) }
        val policy = PolicyAutomation(phone, { PolicyConfig() }, { phone.elements = emptyList(); Decision.APPROVED })
        val result = policy.tap(policy.snapshot().snapshotId, "e1")
        assertEquals("stale_ref", result.error)
        assertTrue(phone.calls.isEmpty())
    }

    @Test
    fun ifTheAppBecameBlockedAfterApprovalNothingIsTapped() = runTest {
        val phone = Phone().apply { elements = listOf(el("e1", "Send")) }
        val policy = PolicyAutomation(phone, { PolicyConfig() }, { phone.screenPackage = "com.mybank.app"; Decision.APPROVED })
        assertEquals("app_denied", policy.tap(policy.snapshot().snapshotId, "e1").error)
        assertTrue(phone.calls.isEmpty())
    }

    @Test
    fun aDeniedOrUnansweredApprovalBlocksTheTap() = runTest {
        for (decision in listOf(Decision.DENIED, Decision.TIMED_OUT)) {
            val phone = Phone().apply { elements = listOf(el("e1", "Delete conversation")) }
            val policy = guarded(phone, decision)
            val result = policy.tap(policy.snapshot().snapshotId, "e1")
            assertEquals("user_denied", result.error)
            assertTrue(decision.name, phone.calls.isEmpty())
        }
    }

    @Test
    fun aHarmlessTapNeverAsksAnything() = runTest {
        val phone = Phone().apply { elements = listOf(el("e1", "Search"), el("e2", "Reply")) }
        val policy = guarded(phone)
        val id = policy.snapshot().snapshotId
        assertTrue(policy.tap(id, "e1").ok)
        assertTrue(policy.tap(id, "e2").ok)
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun theGateCanBeTurnedOff_andThatIsLogged() = runTest {
        val phone = Phone().apply { elements = listOf(el("e1", "Buy now")) }
        val audit = Recorder()
        val policy = guarded(phone, audit = audit, config = { PolicyConfig(gateEnabled = false) })
        assertTrue(policy.tap(policy.snapshot().snapshotId, "e1").ok)
        assertTrue(prompts.isEmpty())
        assertTrue(audit.dump(), audit.events.any { it.first == "gate_disabled" && it.second["risk"] == "PURCHASE" })
    }

    @Test
    fun grantingAPermissionAsksTheUser_butRefusingDoesNot() = runTest {
        val phone = Phone().apply {
            screenPackage = "com.google.android.permissioncontroller"
            foreground = screenPackage
            elements = listOf(el("e1", "While using the app"), el("e2", "Don't allow"))
        }
        val policy = guarded(phone, Decision.APPROVED)
        val id = policy.snapshot().snapshotId
        assertTrue(policy.tap(id, "e2").ok)
        assertTrue("refusing needs no approval", prompts.isEmpty())
        assertTrue(policy.tap(policy.snapshot().snapshotId, "e1").ok)
        assertEquals(Risk.PERMISSION, prompts.single().risk)
    }

    @Test
    fun aZeroLengthOrTinySwipeCannotBeUsedAsATapToBypassTheGate() = runTest {
        val phone = Phone().apply {
            elements = listOf(
                Element("e1", "button", "Send", null, null, listOf(100, 500, 400, 600), clickable = true),
                Element("e2", "scroll", null, null, null, listOf(0, 700, 1000, 2000), scrollable = true),
            )
        }
        val policy = guarded(phone)
        policy.snapshot()
        assertEquals("tap emulated on the Send button", "blocked", policy.swipe(250, 550, 250, 550, 300).error)
        assertEquals("tiny drag", "blocked", policy.swipe(250, 1000, 260, 1010, 300).error)
        assertEquals("long swipe outside any scrollable area", "blocked", policy.swipe(250, 300, 250, 100, 300).error)
        assertTrue("nothing reached the device: ${phone.calls}", phone.calls.isEmpty())
        assertTrue("a real scroll-style swipe inside a scrollable area is fine", policy.swipe(500, 1800, 500, 900, 300).ok)
        assertEquals(listOf("swipe"), phone.calls)
    }

    @Test
    fun swipesNeedAScreenToBeChecked() = runTest {
        val phone = Phone()
        val policy = guarded(phone)
        assertEquals("no snapshot means no scrollable area to swipe in", "blocked", policy.swipe(0, 1000, 0, 100, 300).error)
    }

    // --- Passwords and audit --------------------------------------------------------------

    @Test
    fun typingIntoAPasswordFieldIsBlocked() {
        val phone = Phone().apply { elements = listOf(el("e1", "[password]")) }
        val policy = guarded(phone)
        val result = policy.type(policy.snapshot().snapshotId, "e1", "hunter2")
        assertEquals("blocked", result.error)
        assertTrue(phone.calls.isEmpty())
    }

    @Test
    fun theAuditLogRecordsActionsButNeverTypedText() = runTest {
        val phone = Phone().apply { elements = listOf(el("e1", "Name"), el("e2", "Send")) }
        val audit = Recorder()
        val policy = guarded(phone, audit = audit)
        val id = policy.snapshot().snapshotId
        policy.type(id, "e1", "my very secret note")
        policy.tap(id, "e2")
        val log = audit.dump()
        assertFalse(log, "my very secret note" in log || "secret" in log)
        assertTrue(log, audit.events.any { it.first == "action" && it.second["tool"] == "type" && it.second["chars"] == "19" })
        assertTrue(log, audit.events.any { it.first == "approval" && it.second["decision"] == "APPROVED" && it.second["target"] == "Send" })
    }

    @Test
    fun aDeniedAppIsLoggedOncePerVisit() {
        val phone = Phone().apply { screenPackage = "com.mybank.app" }
        val audit = Recorder()
        val policy = guarded(phone, audit = audit)
        repeat(3) { policy.snapshot() }
        assertEquals(1, audit.events.count { it.first == "app_denied" })
        assertNotNull(audit.events.first().second["pkg"])
    }
}
