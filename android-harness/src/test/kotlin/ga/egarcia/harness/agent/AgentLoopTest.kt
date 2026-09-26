package ga.egarcia.harness.agent

import ga.egarcia.harness.ActionResult
import ga.egarcia.harness.Automation
import ga.egarcia.harness.Direction
import ga.egarcia.harness.Element
import ga.egarcia.harness.ScreenshotResponse
import ga.egarcia.harness.Snapshot
import ga.egarcia.harness.WaitResult
import ga.egarcia.harness.llm.LlmClient
import ga.egarcia.harness.llm.LlmException
import ga.egarcia.harness.llm.LlmResponse
import ga.egarcia.harness.llm.ToolCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopTest {

    private class FakeAutomation(private val screens: List<Snapshot>) : Automation {
        val calls = mutableListOf<String>()
        private var counter = 0
        private var latestId = ""
        var staleOnce = false
        /** The phone locks after this many snapshots (null = never). */
        var lockAfterSnapshots: Int? = null
        var screen = 0

        override fun isLockedOrAsleep() = lockAfterSnapshots?.let { counter >= it } ?: false

        override fun snapshot(): Snapshot {
            latestId = "s_${++counter}"
            return screens[minOf(screen, screens.lastIndex)].copy(snapshotId = latestId)
        }

        private fun act(desc: String, snapshotId: String? = null, advance: Boolean = true): ActionResult {
            calls += desc
            if (snapshotId != null && snapshotId != latestId) return ActionResult(false, "stale_ref", "Latest snapshot is $latestId")
            if (staleOnce) { staleOnce = false; return ActionResult(false, "stale_ref", "forced") }
            if (advance) screen++
            return ActionResult(true)
        }

        override suspend fun tap(snapshotId: String, ref: String) = act("tap $ref", snapshotId)
        override fun type(snapshotId: String, ref: String, text: String) = act("type $ref $text", snapshotId)
        override suspend fun scroll(direction: Direction, snapshotId: String?, ref: String?) = act("scroll $direction", snapshotId)
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = act("swipe")
        override fun back() = act("back")
        override fun home() = act("home")
        override fun openApp(app: String): ActionResult { val r = act("open_app $app"); return if (r.ok) r.copy(detail = openAppPackage) else r }
        var openAppPackage: String? = null
        override suspend fun waitFor(text: String, timeoutMs: Long): WaitResult { calls += "wait_for $text"; return WaitResult(true, snapshot = snapshot()) }
        override suspend fun screenshot(maxSide: Int): ScreenshotResponse { calls += "screenshot"; return ScreenshotResponse(true, mime = "image/jpeg", imageBase64 = "AAAA") }
    }

    private class ScriptedLlm(private val replies: List<() -> LlmResponse>) : LlmClient {
        val requests = mutableListOf<List<JsonObject>>()
        override suspend fun chat(messages: List<JsonObject>, tools: List<JsonObject>): LlmResponse {
            requests += messages.toList()
            return replies[minOf(requests.size - 1, replies.lastIndex)]()
        }
    }

    private fun call(name: String, args: String = "{}", id: String = "c_$name") = LlmResponse(null, listOf(ToolCall(id, name, args)), "tool_calls", null)
    private fun text(t: String?) = LlmResponse(t, emptyList(), "stop", null)

    private fun screen(vararg texts: String) = Snapshot("x", "app", texts.mapIndexed { i, t ->
        Element("e${i + 1}", "view", t, null, null, listOf(0, 0, 1, 1), clickable = true)
    })

    private val three = listOf(screen("Home"), screen("Settings"), screen("Wi-Fi"), screen("Done"))

    @Test
    fun happyPath_usesLatestSnapshotIdAndFinishes() = runTest {
        val phone = FakeAutomation(three)
        val llm = ScriptedLlm(listOf({ call("open_app", "{\"app\":\"Settings\"}") }, { call("tap", "{\"ref\":\"e1\"}") }, { call("done", "{\"summary\":\"opened wifi\"}") }))
        val result = AgentLoop(phone, llm).run("open wifi")
        assertEquals(AgentResult.Done("opened wifi", 3), result)
        assertEquals(listOf("open_app Settings", "tap e1"), phone.calls)
    }

    @Test
    fun onlyTheLatestScreenStaysInTheConversation() = runTest {
        val phone = FakeAutomation(three)
        val llm = ScriptedLlm(listOf({ call("tap", "{\"ref\":\"e1\"}") }, { call("tap", "{\"ref\":\"e1\"}") }, { call("scroll", "{\"direction\":\"down\"}") }, { call("done", "{\"summary\":\"x\"}") }))
        AgentLoop(phone, llm).run("t")
        val last = llm.requests.last().joinToString("\n") { it.toString() }
        assertEquals("exactly one live screen frame in the final request", 1, Regex("untrusted=").findAll(last).count())
        assertTrue(last.contains("(earlier screen omitted)"))
    }

    @Test
    fun failedActionsAreFedBackToTheModel() = runTest {
        val phone = FakeAutomation(three).apply { staleOnce = true }
        val llm = ScriptedLlm(listOf({ call("tap", "{\"ref\":\"e1\"}") }, { call("done", "{\"summary\":\"ok\"}") }))
        AgentLoop(phone, llm).run("t")
        assertTrue(llm.requests[1].joinToString { it.toString() }.contains("error: stale_ref"))
    }

    @Test
    fun repeatingTheSameActionOnTheSameScreenStops() = runTest {
        val phone = FakeAutomation(listOf(screen("A"))) // screen never changes
        val llm = ScriptedLlm(listOf({ call("tap", "{\"ref\":\"e1\"}") }))
        val result = AgentLoop(phone, llm, AgentConfig(stuckLimit = 3)).run("t")
        assertEquals(AgentResult.Stopped("stuck", 3), result)
    }

    @Test
    fun stopsAtMaxSteps() = runTest {
        val phone = FakeAutomation((1..10).map { screen("S$it") })
        var n = 0
        val llm = ScriptedLlm(listOf({ call("tap", "{\"ref\":\"e${++n}\"}") }))
        val result = AgentLoop(phone, llm, AgentConfig(maxSteps = 4)).run("t")
        assertEquals(AgentResult.Stopped("max_steps", 4), result)
    }

    @Test
    fun askUserEndsTheRun() = runTest {
        val llm = ScriptedLlm(listOf({ call("ask_user", "{\"question\":\"Which account?\"}") }))
        assertEquals(AgentResult.NeedsUser("Which account?", 1), AgentLoop(FakeAutomation(three), llm).run("t"))
    }

    @Test
    fun proseAnswerTwiceCountsAsDone_emptyThriceIsAFailure() = runTest {
        val prose = ScriptedLlm(listOf({ text("The answer is 42") }))
        assertEquals(AgentResult.Done("The answer is 42", 2), AgentLoop(FakeAutomation(three), prose).run("t"))
        val empty = ScriptedLlm(listOf({ text(null) }))
        assertEquals(AgentResult.Stopped("no_tool_call", 3), AgentLoop(FakeAutomation(three), empty).run("t"))
    }

    /** Snapshots report [staleFor] calls of the old app before the launched app appears. */
    private class SlowLaunch(private val inner: FakeAutomation, private val staleFor: Int, private val newPackage: String) : Automation by inner {
        var polls = 0
        override fun snapshot(): Snapshot {
            val s = inner.snapshot()
            return if (inner.calls.any { it.startsWith("open_app") } && polls++ < staleFor) s.copy(pkg = "com.old.app") else s.copy(pkg = newPackage)
        }
    }

    @Test
    fun openAppWaitsUntilTheAppIsInTheForeground() = runTest {
        val inner = FakeAutomation(three).apply { openAppPackage = "com.android.settings" }
        val phone = SlowLaunch(inner, staleFor = 3, newPackage = "com.android.settings")
        val llm = ScriptedLlm(listOf({ call("open_app", "{\"app\":\"Settings\"}") }, { call("done", "{\"summary\":\"k\"}") }))
        AgentLoop(phone, llm).run("t")
        val second = llm.requests[1].joinToString("\n") { it.toString() }
        assertTrue(second, second.contains("ok: com.android.settings is in the foreground"))
        assertTrue("the screen shown to the model is the new app", second.contains("package: com.android.settings"))
        assertTrue(!second.contains("com.old.app"))
    }

    @Test
    fun openAppWarnsWhenTheAppNeverAppears() = runTest {
        val inner = FakeAutomation(three).apply { openAppPackage = "com.android.settings" }
        val phone = SlowLaunch(inner, staleFor = Int.MAX_VALUE, newPackage = "x")
        val llm = ScriptedLlm(listOf({ call("open_app", "{\"app\":\"Settings\"}") }, { call("done", "{\"summary\":\"k\"}") }))
        AgentLoop(phone, llm, AgentConfig(openAppTimeoutMs = 1_000)).run("t")
        val second = llm.requests[1].joinToString("\n") { it.toString() }
        assertTrue(second, second.contains("warning: com.android.settings did not reach the foreground"))
        assertTrue(second.contains("still shows com.old.app"))
    }

    @Test
    fun repeatedlyLandingInABlockedAppStopsTheRunWithAnExplanation() = runTest {
        val blocked = Snapshot("x", "com.mybank.app", emptyList(), blind = "app_denied")
        val phone = FakeAutomation(listOf(blocked))
        val llm = ScriptedLlm(listOf({ call("back") }))
        val result = AgentLoop(phone, llm).run("t") as AgentResult.NeedsUser
        assertEquals(2, result.steps)
        assertTrue(result.question, result.question.contains("blocked") && result.question.contains("com.mybank.app"))
    }

    @Test
    fun logLinesNeverContainTypedTextOrModelProse() {
        val typed = AgentEvent.ModelReplied(1, listOf(ToolCall("c", "type", """{"ref":"e2","text":"hunter2"}""")), null, null).summary()
        assertTrue(typed, "hunter2" !in typed && "ref=e2" in typed && "text=<7 chars>" in typed)
        val waited = AgentEvent.ModelReplied(2, listOf(ToolCall("c", "wait_for", """{"text":"account balance"}""")), null, null).summary()
        assertTrue(waited, "account balance" !in waited)
        val prose = AgentEvent.ModelReplied(3, emptyList(), "The password is swordfish", null).summary()
        assertTrue(prose, "swordfish" !in prose)
        val app = AgentEvent.ModelReplied(4, listOf(ToolCall("c", "open_app", """{"app":"Settings"}""")), null, null).summary()
        assertTrue(app, "app=Settings" in app)
    }

    @Test
    fun aLockedPhoneStopsTheRunBeforeAnyModelCall() = runTest {
        val phone = FakeAutomation(three).apply { lockAfterSnapshots = 0 }
        val llm = ScriptedLlm(listOf({ call("done", "{\"summary\":\"k\"}") }))
        val result = AgentLoop(phone, llm).run("t") as AgentResult.NeedsUser
        assertTrue(result.question, result.question.contains("locked"))
        assertEquals(0, llm.requests.size)
    }

    @Test
    fun theRunStopsWhenThePhoneLocksMidTask() = runTest {
        val phone = FakeAutomation((1..8).map { screen("S$it") }).apply { lockAfterSnapshots = 3 }
        var n = 0
        val llm = ScriptedLlm(listOf({ call("tap", "{\"ref\":\"e${++n}\"}") }))
        val result = AgentLoop(phone, llm).run("t")
        assertTrue(result.toString(), result is AgentResult.NeedsUser)
        assertTrue("it acted before the lock, then stopped", phone.calls.isNotEmpty() && llm.requests.size in 1..3)
    }

    @Test
    fun transientModelErrorsAreRetriedThenTheRunContinues() = runTest {
        val events = mutableListOf<AgentEvent>()
        val llm = ScriptedLlm(listOf({ throw LlmException("network", "timeout") }, { throw LlmException("http_503", "busy") }, { call("done", "{\"summary\":\"ok\"}") }))
        val result = AgentLoop(FakeAutomation(three), llm, AgentConfig(llmRetryDelaysMs = listOf(10, 10)), events::add).run("t")
        assertEquals(AgentResult.Done("ok", 1), result)
        assertEquals(3, llm.requests.size)
        assertEquals(listOf(1, 2), events.filterIsInstance<AgentEvent.ModelRetry>().map { it.attempt })
    }

    @Test
    fun retriesGiveUpAfterTheConfiguredNumberAndReportTheReason() = runTest {
        val llm = ScriptedLlm(listOf({ throw LlmException("network", "down") }))
        val result = AgentLoop(FakeAutomation(three), llm, AgentConfig(llmRetryDelaysMs = listOf(10, 10))).run("t") as AgentResult.Stopped
        assertEquals(3, llm.requests.size)
        assertTrue(result.reason, result.reason.startsWith("llm_error: network"))
    }

    @Test
    fun permanentModelErrorsAreNotRetried() = runTest {
        for (code in listOf("http_401", "http_400", "cleartext_blocked", "bad_response")) {
            val llm = ScriptedLlm(listOf({ throw LlmException(code, "nope") }))
            AgentLoop(FakeAutomation(three), llm, AgentConfig(llmRetryDelaysMs = listOf(10, 10))).run("t")
            assertEquals(code, 1, llm.requests.size)
        }
    }

    @Test
    fun proseDoneIsRecognizedImmediately() = runTest {
        for ((written, expected) in listOf(
            "done: The version is 16." to "The version is 16.",
            "Done: \"Bluetooth\"" to "Bluetooth",
            "done(All finished)" to "All finished",
            "done(\"quoted result\")" to "quoted result",
        )) {
            val result = AgentLoop(FakeAutomation(three), ScriptedLlm(listOf({ text(written) }))).run("t")
            assertEquals(written, AgentResult.Done(expected, 1), result)
        }
    }

    @Test
    fun ordinaryProseIsNotMistakenForDone() = runTest {
        val loop = AgentLoop(FakeAutomation(three), ScriptedLlm(listOf({ text("x") })))
        assertEquals(null, loop.proseDone("I am done with the first part, next I will tap"))
        assertEquals(null, loop.proseDone("Undone: nothing"))
        assertEquals(null, loop.proseDone("done"))
    }

    @Test
    fun llmErrorsStopTheRunWithTheReason() = runTest {
        val llm = ScriptedLlm(listOf({ throw LlmException("http_500", "boom") }))
        val result = AgentLoop(FakeAutomation(three), llm).run("t") as AgentResult.Stopped
        assertTrue(result.reason, result.reason.startsWith("llm_error: http_500"))
    }

    @Test
    fun onlyTheFirstOfSeveralToolCallsRuns_othersAreAnswered() = runTest {
        val phone = FakeAutomation(three)
        val two = LlmResponse(null, listOf(ToolCall("a", "tap", "{\"ref\":\"e1\"}"), ToolCall("b", "back", "{}")), "tool_calls", null)
        val llm = ScriptedLlm(listOf({ two }, { call("done", "{\"summary\":\"k\"}") }))
        AgentLoop(phone, llm).run("t")
        assertEquals(listOf("tap e1"), phone.calls)
        val second = llm.requests[1].joinToString("\n") { it.toString() }
        assertTrue(second.contains("\"tool_call_id\":\"b\"") || second.contains("tool_call_id\":\"b"))
        assertTrue(second.contains("skipped"))
    }

    @Test
    fun badArgumentsAndUnknownToolsAreReportedNotCrashed() = runTest {
        val llm = ScriptedLlm(listOf({ call("tap", "not json") }, { call("fly", "{}") }, { call("tap", "{}") }, { call("done", "{\"summary\":\"k\"}") }))
        val phone = FakeAutomation((1..6).map { screen("S$it") })
        assertEquals(AgentResult.Done("k", 4), AgentLoop(phone, llm).run("t"))
        assertTrue(phone.calls.isEmpty())
    }

    @Test
    fun screenshotIsAttachedOnceAndOlderOnesArePruned() = runTest {
        val phone = FakeAutomation(three)
        val llm = ScriptedLlm(listOf({ call("look") }, { call("tap", "{\"ref\":\"e1\"}") }, { call("done", "{\"summary\":\"k\"}") }))
        AgentLoop(phone, llm, AgentConfig(vision = true)).run("t")
        assertTrue(llm.requests[1].joinToString { it.toString() }.contains("data:image/jpeg;base64,AAAA"))
        val final = llm.requests[2].joinToString { it.toString() }
        assertTrue(!final.contains("data:image") && final.contains("(earlier screenshot omitted)"))
    }
}
