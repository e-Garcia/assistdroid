package ga.egarcia.harness.diag

import ga.egarcia.harness.agent.AgentEvent
import ga.egarcia.harness.agent.AgentResult
import ga.egarcia.harness.llm.ToolCall
import ga.egarcia.harness.llm.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val done = mutableListOf<RunRecord>()

    private fun recorder(detailed: Boolean = false) =
        SessionRecorder({ "r1" }, { detailed }, { now }, { done += it })

    private fun tool(name: String, args: String) = ToolCall("id", name, args)

    /** One step: 1.5 s model, 60 ms tool, screen afterwards. */
    private fun SessionRecorder.step(n: Int, call: ToolCall, result: String, pkg: String = "com.app", tokens: Int = 2000) {
        onEvent(AgentEvent.ModelRequested(n)); now += 1500
        onEvent(AgentEvent.ModelReplied(n, listOf(call), "thinking out loud", Usage(tokens, 40))); now += 60
        onEvent(AgentEvent.ToolRan(n, call.name, call.arguments, result))
        onEvent(AgentEvent.Observed(n, pkg, 34, null, 12))
    }

    private fun sampleRun(rec: SessionRecorder) {
        rec.onEvent(AgentEvent.Started("Send hello to Ann"))
        rec.onEvent(AgentEvent.Observed(0, "com.launcher", 20, null, 8))
        rec.step(1, tool("open_app", "{\"app\":\"Sample\"}"), "ok: Sample is in the foreground")
        rec.step(2, tool("type", "{\"ref\":\"e4\",\"text\":\"hello Ann, it's Erick\"}"), "error: stale_ref Latest snapshot is s_9", tokens = 2400)
        now += 5
        rec.onEvent(AgentEvent.Finished(AgentResult.Done("I typed the greeting", 2)))
    }

    @Test
    fun recordsTimingsTokensToolsAndOutcomes() {
        sampleRun(recorder())
        val run = done.single()
        assertEquals("done", run.result)
        assertEquals(2, run.steps)
        assertEquals(4400, run.tokensIn)
        assertEquals(80, run.tokensOut)
        assertEquals(3000, run.llmMs)
        assertEquals("com.launcher", run.startPkg)
        val s1 = run.stepsDetail[0]
        assertEquals(1500L, s1.llmMs)
        assertEquals(60L, s1.toolMs)
        assertEquals("open_app(app=Sample)", s1.tool)
        assertEquals("ok", s1.outcome)
        assertEquals("com.app", s1.pkg)
        assertEquals("error: stale_ref", run.stepsDetail[1].outcome)
    }

    @Test
    fun theDefaultRecordHoldsNoTypedTextTaskOrModelProse() {
        sampleRun(recorder(detailed = false))
        val text = Json.encodeToString(RunRecord.serializer(), done.single())
        assertTrue(text.contains("text=<"))
        for (secret in listOf("hello Ann", "Erick", "Send hello to Ann", "thinking out loud", "I typed the greeting", "Latest snapshot")) {
            assertFalse("leaked: $secret", text.contains(secret))
        }
        assertEquals(17, done.single().taskChars)
        assertNull(done.single().task)
    }

    @Test
    fun detailedModeAddsTaskRepliesAndResultsButStillNoTypedText() {
        sampleRun(recorder(detailed = true))
        val run = done.single()
        assertEquals("Send hello to Ann", run.task)
        assertEquals("I typed the greeting", run.summary)
        assertEquals("thinking out loud", run.stepsDetail[0].reply)
        assertTrue(run.stepsDetail[1].result!!.startsWith("error: stale_ref"))
        assertFalse(Json.encodeToString(RunRecord.serializer(), run).contains("hello Ann, it's Erick"))
    }

    @Test
    fun detailLevelIsFixedWhenTheRunStarts() {
        var detailed = false
        val rec = SessionRecorder({ "r1" }, { detailed }, { now }, { done += it })
        rec.onEvent(AgentEvent.Started("task"))
        detailed = true
        rec.onEvent(AgentEvent.Finished(AgentResult.Done("ok", 0)))
        assertFalse(done.single().detailed)
    }

    @Test
    fun anAbortedRunIsRecordedOnceAndAFinishedOneNeverTwice() {
        val rec = recorder()
        rec.onEvent(AgentEvent.Started("t"))
        rec.step(1, tool("tap", "{\"ref\":\"e1\"}"), "ok")
        rec.aborted("stopped_by_user")
        rec.aborted("again")
        assertEquals(1, done.size)
        assertEquals("stopped", done[0].result)
        assertEquals("stopped_by_user", done[0].reason)
        assertEquals(1, done[0].steps)

        rec.onEvent(AgentEvent.Started("t2"))
        rec.onEvent(AgentEvent.Finished(AgentResult.NeedsUser("which one?", 0)))
        rec.aborted("late")
        assertEquals(2, done.size)
        assertEquals("needs_user", done[1].result)
    }

    @Test
    fun modelRetriesAreCounted() {
        val rec = recorder()
        rec.onEvent(AgentEvent.Started("t"))
        rec.onEvent(AgentEvent.ModelRetry(1, 1, 2, "network"))
        rec.onEvent(AgentEvent.ModelRetry(1, 2, 2, "network"))
        rec.onEvent(AgentEvent.Finished(AgentResult.Stopped("llm_error: network timeout", 1)))
        assertEquals(2, done.single().retries)
        assertEquals("llm_error: network timeout", done.single().reason)
    }

    @Test
    fun outcomeNeverCarriesScreenTextFromTheResult() {
        assertEquals("ok", SessionRecorder.outcome("ok: tapped e3"))
        assertEquals("ok", SessionRecorder.outcome("2 notification(s), newest first:\n- Ann: secret plans"))
        assertEquals("error: stale_ref", SessionRecorder.outcome("error: stale_ref Latest snapshot is s_2"))
        assertEquals("blocked: app_denied", SessionRecorder.outcome("blocked: app_denied"))
        assertEquals("user_denied", SessionRecorder.outcome("user_denied: the user said no"))
        assertEquals("timeout", SessionRecorder.outcome("timeout: not found"))
    }

    // --- Store ------------------------------------------------------------------------------------

    @Test
    fun theStoreKeepsOnlyTheNewestRunsAndSurvivesGarbageLines() {
        val file = tmp.newFile("sessions.jsonl")
        val store = SessionStore(file, maxRuns = 3)
        repeat(5) { i -> store.append(RunRecord("r$i", "t", 1, "done", null, 1, 1)) }
        file.appendText("not json\n")
        assertEquals(listOf("r2", "r3", "r4"), store.read().map { it.run })
        store.clear()
        assertTrue(store.read().isEmpty())
    }

    // --- Metrics ----------------------------------------------------------------------------------

    @Test
    fun metricsSummariseLatencyOutcomesAndProblems() {
        sampleRun(recorder())
        done += RunRecord("r2", "t", 4000, "stopped", "stuck", 3, 5, stepsDetail = listOf(StepRecord(1, llmMs = 9000, tokensIn = 3000, tool = "tap(ref=e1)", outcome = "blocked", blind = "app_denied")))
        val m = Metrics.of(done)
        assertEquals(2, m["runs"]!!.jsonPrimitive.content.toInt())
        assertEquals("1", m["done"]!!.jsonPrimitive.content)
        assertEquals("1", m["stop_reasons"]!!.jsonObject["stuck"]!!.jsonPrimitive.content)
        assertEquals("9000", m["llm_ms_max"]!!.jsonPrimitive.content)
        assertEquals("1", m["tool_problems"]!!.jsonObject["tap: blocked"]!!.jsonPrimitive.content)
        assertEquals("1", m["tool_problems"]!!.jsonObject["type: error: stale_ref"]!!.jsonPrimitive.content)
        assertEquals("1", m["blind_screens"]!!.jsonPrimitive.content)
    }

    @Test
    fun percentilesAreNearestRank() {
        assertEquals(0, Metrics.percentile(emptyList(), 95))
        assertEquals(5, Metrics.percentile(listOf(1, 2, 3, 4, 5), 100))
        assertEquals(3, Metrics.percentile(listOf(1, 2, 3, 4, 5), 50))
        assertEquals(10, Metrics.percentile((1L..10L).toList(), 95))
    }

    @Test
    fun metricsOnNothingDoNotCrash() {
        assertEquals("0", Metrics.of(emptyList())["runs"]!!.jsonPrimitive.content)
    }

    // --- Redaction and the report -----------------------------------------------------------------

    private val token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcd"
    private val apiKey = "omlx-Sup3r\$ecretKey!42"

    @Test
    fun redactorMasksKnownSecretsAndCommonShapes() {
        val r = Redactor(listOf(token, apiKey, "short"))
        val text = "API token: $token\nkey=$apiKey\nAuthorization: Bearer abcdefghijklmnop\n\"api_key\":\"zzzzzzzzzz\" sk-proj123456789012 short"
        val out = r.clean(text)
        for (s in listOf(token, apiKey, "abcdefghijklmnop", "zzzzzzzzzz", "sk-proj123456789012")) assertFalse("left: $s", out.contains(s))
        assertTrue("secrets shorter than 8 chars are not treated as secrets", out.contains("short"))
    }

    private fun input(logcat: List<String> = listOf("line"), runs: List<RunRecord> = emptyList(), raw: List<String> = emptyList(), audit: List<String> = emptyList()) =
        ReportInput("2026-09-26T00:00:00Z", linkedMapOf("android" to "16"), linkedMapOf("model" to "Qwen"), listOf("accessibility: OK (required)"), runs, raw, null, audit, logcat)

    @Test
    fun reportHasEverySectionAndMasksSecretsAnywhere() {
        val report = Report.build(input(logcat = listOf("I/Harness: API token: $token"), raw = listOf("{\"run\":\"r1\"}")), Redactor(listOf(token)))
        for (h in listOf("Environment", "Settings", "Setup checklist", "Metrics", "Sessions", "Last crash", "Audit log", "Logcat")) assertTrue(h, report.contains("=== $h"))
        assertFalse(report.contains(token))
        assertTrue(report.contains("android: 16"))
        assertTrue(report.contains("{\"run\":\"r1\"}"))
    }

    @Test
    fun anOversizedReportShedsLogcatFirstAndKeepsTheNewestSessions() {
        val logcat = (1..4000).map { "12-01 00:00:00.000 I/Tag: line number $it with some padding to make it long enough" }
        val raw = (1..10).map { "{\"run\":\"r$it\"}" }
        val report = Report.build(input(logcat = logcat, raw = raw), Redactor(emptyList()), maxChars = 20_000)
        assertTrue(report.length <= 20_000)
        assertTrue("newest log lines survive", report.contains("line number 4000"))
        assertFalse("oldest log lines go first", report.contains("line number 1 with"))
        assertTrue("sessions are kept while logcat can still shrink", report.contains("{\"run\":\"r1\"}"))
    }

    @Test
    fun crashNotesHaveTheTraceButLimitedSize() {
        val error = IllegalStateException("boom", RuntimeException("root cause"))
        val text = CrashFormat.format("2026-09-26T00:00:00Z", "main", error)
        assertTrue(text.contains("IllegalStateException: boom"))
        assertTrue(text.contains("Caused by: java.lang.RuntimeException: root cause"))
        assertNotNull(text)
        assertTrue(CrashFormat.format("t", "main", error, maxChars = 50).length <= 50)
    }

    @Test
    fun endpointKindsNeverShowTheHost() {
        assertEquals("tailscale, http, port 8000, ip", AndroidDiagnostics.endpointKind("http://100.94.145.51:8000/v1"))
        assertEquals("tailscale, http, port 8000, name", AndroidDiagnostics.endpointKind("http://my-server.example.ts.net:8000/v1"))
        assertEquals("emulator host, http, port 8000, ip", AndroidDiagnostics.endpointKind("http://10.0.2.2:8000/v1"))
        assertEquals("local network, http, port default, ip", AndroidDiagnostics.endpointKind("http://192.168.1.5/v1"))
        assertEquals("public internet, https, port default, name", AndroidDiagnostics.endpointKind("https://api.example.com/v1"))
        assertEquals("invalid", AndroidDiagnostics.endpointKind("::::"))
    }
}
