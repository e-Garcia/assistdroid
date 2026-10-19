package dev.egarcia.harness.agent

import dev.egarcia.harness.Element
import dev.egarcia.harness.Snapshot
import dev.egarcia.harness.llm.LlmClient
import dev.egarcia.harness.llm.LlmResponse
import dev.egarcia.harness.llm.ToolCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guide mode's own safety story, checked here rather than asserted: [GuideLoop] is given only a read-only
 * [observe] lambda, never an [dev.egarcia.harness.Automation]. There is no tap/type/scroll/swipe call anywhere
 * in GuideLoop.kt or in this test -- the only way these tests could exercise an acting call is if GuideLoop's
 * source actually had one, which `grep -L "automation\.\(tap\|type\|scroll\|swipe\)" GuideLoop.kt` confirms it
 * does not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideLoopTest {

    /** Serves a fixed sequence of screens; each [observe] call advances to the next one (clamped at the end). */
    private class FakeObserver(private val screens: List<Snapshot>) {
        var calls = 0
        var advanceCalls = 0
        suspend fun observe(): Snapshot {
            calls++
            return screens[minOf(calls - 1, screens.lastIndex)]
        }
        suspend fun awaitAdvance(@Suppress("UNUSED_PARAMETER") shown: Snapshot) {
            advanceCalls++
        }
    }

    private class ScriptedLlm(private val replies: List<() -> LlmResponse>) : LlmClient {
        val requests = mutableListOf<List<JsonObject>>()
        override suspend fun chat(messages: List<JsonObject>, tools: List<JsonObject>): LlmResponse {
            requests += messages.toList()
            return replies[minOf(requests.size - 1, replies.lastIndex)]()
        }
    }

    private fun call(name: String, args: String = "{}") = LlmResponse(null, listOf(ToolCall("c_$name", name, args)), "tool_calls", null)
    private fun text(t: String?) = LlmResponse(t, emptyList(), "stop", null)

    private fun screen(pkg: String, vararg texts: String) = Snapshot("x", pkg, texts.mapIndexed { i, t ->
        Element("e${i + 1}", "view", t, null, null, listOf(10 * i, 20 * i, 10 * i + 100, 20 * i + 50), clickable = true)
    })

    @Test
    fun pointToHighlightsTheNamedElementAndWaitsBeforeAdvancing() = runTest {
        val observer = FakeObserver(listOf(screen("mail", "Compose"), screen("mail", "To field"), screen("mail", "Sent")))
        val events = mutableListOf<GuideEvent>()
        val llm = ScriptedLlm(
            listOf(
                { call("point_to", "{\"ref\":\"e1\",\"instruction\":\"Tap Compose\"}") },
                { call("done", "{\"summary\":\"sent\"}") },
            ),
        )
        val result = GuideLoop(observer::observe, llm, onEvent = { events += it }, awaitAdvance = observer::awaitAdvance).run("send an email")

        assertEquals(AgentResult.Done("sent", 2), result)
        val step = events.filterIsInstance<GuideEvent.Step>().single().step
        assertEquals("e1", step.ref)
        assertEquals(listOf(0, 0, 100, 50), step.bounds) // e1's bounds on the first screen
        assertEquals("Tap Compose", step.instruction)
        assertEquals("exactly one wait-for-the-user between the two screens", 1, observer.advanceCalls)
        assertEquals("initial observe + one after the step", 2, observer.calls)
    }

    @Test
    fun pointToWithoutARefShowsNoHighlight() = runTest {
        val observer = FakeObserver(listOf(screen("launcher", "Apps")))
        val events = mutableListOf<GuideEvent>()
        val llm = ScriptedLlm(listOf({ call("point_to", "{\"instruction\":\"Swipe up from the bottom\"}") }, { call("done", "{\"summary\":\"x\"}") }))
        GuideLoop(observer::observe, llm, onEvent = { events += it }, awaitAdvance = observer::awaitAdvance).run("open the app drawer")

        val step = events.filterIsInstance<GuideEvent.Step>().single().step
        assertNull(step.ref)
        assertNull(step.bounds)
    }

    @Test
    fun aRefThatDoesNotMatchAnyElementOmitsBoundsWithoutCrashing() = runTest {
        val observer = FakeObserver(listOf(screen("app", "Thing")))
        val events = mutableListOf<GuideEvent>()
        val llm = ScriptedLlm(listOf({ call("point_to", "{\"ref\":\"e9\",\"instruction\":\"Tap it\"}") }, { call("done", "{\"summary\":\"x\"}") }))
        GuideLoop(observer::observe, llm, onEvent = { events += it }, awaitAdvance = observer::awaitAdvance).run("t")

        val step = events.filterIsInstance<GuideEvent.Step>().single().step
        assertEquals("e9", step.ref)
        assertNull(step.bounds)
    }

    @Test
    fun askUserStopsAndReturnsTheQuestion() = runTest {
        val observer = FakeObserver(listOf(screen("bank", "Login")))
        val llm = ScriptedLlm(listOf({ call("ask_user", "{\"question\":\"What is your contact's name?\"}") }))
        val result = GuideLoop(observer::observe, llm, awaitAdvance = observer::awaitAdvance).run("t")
        assertEquals(AgentResult.NeedsUser("What is your contact's name?", 1), result)
        assertEquals("ask_user never waits for an advance", 0, observer.advanceCalls)
    }

    @Test
    fun anActingToolNameIsRejectedNotExecuted() = runTest {
        // Simulates a model that ignores the tool schema it was given and tries to act directly. GuideLoop has
        // no Automation reference at all, so there is no code path for this to do anything except error out.
        val observer = FakeObserver(listOf(screen("app", "Button")))
        val llm = ScriptedLlm(listOf({ call("tap", "{\"ref\":\"e1\"}") }, { call("done", "{\"summary\":\"x\"}") }))
        GuideLoop(observer::observe, llm, awaitAdvance = observer::awaitAdvance).run("t")
        assertTrue(llm.requests[1].joinToString { it.toString() }.contains("unknown tool 'tap'"))
    }

    @Test
    fun repeatingTheSameStepOnAScreenThatNeverChangesStops() = runTest {
        val observer = FakeObserver(listOf(screen("app", "Same"))) // observe() always returns this one screen
        val llm = ScriptedLlm(listOf({ call("point_to", "{\"ref\":\"e1\",\"instruction\":\"Tap it\"}") }))
        val result = GuideLoop(observer::observe, llm, AgentConfig(stuckLimit = 3), awaitAdvance = observer::awaitAdvance).run("t")
        assertEquals(AgentResult.Stopped("stuck", 3), result)
    }

    @Test
    fun stopsAtMaxSteps() = runTest {
        val observer = FakeObserver((1..10).map { screen("app", "S$it") })
        var n = 0
        val llm = ScriptedLlm(listOf({ call("point_to", "{\"ref\":\"e1\",\"instruction\":\"Step ${++n}\"}") }))
        val result = GuideLoop(observer::observe, llm, AgentConfig(maxSteps = 4, stuckLimit = 100), awaitAdvance = observer::awaitAdvance).run("t")
        assertEquals(AgentResult.Stopped("max_steps", 4), result)
    }

    @Test
    fun noToolCallTwiceWithTextEndsTheSessionWithThatText() = runTest {
        val observer = FakeObserver(listOf(screen("app", "S")))
        val llm = ScriptedLlm(listOf({ text("Here is what I found.") }, { text("Here is what I found.") }))
        val result = GuideLoop(observer::observe, llm, awaitAdvance = observer::awaitAdvance).run("t")
        assertEquals(AgentResult.Done("Here is what I found.", 2), result)
    }
}
