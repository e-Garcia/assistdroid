package ga.egarcia.harness.agent

import ga.egarcia.harness.llm.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStateTest {
    private fun replied(step: Int, name: String, args: String) =
        AgentEvent.ModelReplied(step, listOf(ToolCall("c", name, args)), null, null)

    @Test
    fun beginAddsTheUsersLineAndMarksRunning() {
        val s = RunState().begin("full task", display = "shown")
        assertTrue(s.running)
        assertEquals("full task", s.task)
        assertEquals(listOf(UiLine(LineKind.USER, "shown")), s.lines)
        assertNull(s.result)
    }

    @Test
    fun toolCallsBecomeReadableStepsAndTheNotificationStatus() {
        var s = RunState().begin("t")
        s = s.onEvent(replied(1, "open_app", """{"app":"Settings"}"""))
        s = s.onEvent(replied(2, "tap", """{"ref":"e6"}"""))
        s = s.onEvent(replied(3, "type", """{"ref":"e2","text":"Bluetooth"}"""))
        assertEquals(
            listOf("Step 1: Open Settings", "Step 2: Tap e6", "Step 3: Type \"Bluetooth\" into e2"),
            s.lines.filter { it.kind == LineKind.STEP }.map { it.text },
        )
        assertEquals("the notification status has no tool arguments", "Step 3: typing", s.status)
    }

    @Test
    fun notificationStatusNeverEchoesSearchTextOrRefs() {
        for ((name, args) in listOf("wait_for" to """{"text":"secret-word"}""", "type" to """{"ref":"e2","text":"secret-word"}""", "open_app" to """{"app":"secret-word"}""")) {
            val s = RunState().begin("t").onEvent(replied(1, name, args))
            assertFalse(name, s.status.contains("secret-word"))
        }
    }

    @Test
    fun terminalToolsDoNotAddStepLines() {
        val s = RunState().begin("t").onEvent(replied(1, "done", """{"summary":"x"}"""))
        assertEquals(1, s.lines.size)
    }

    @Test
    fun onlyFailuresFromToolsAreShown() {
        var s = RunState().begin("t")
        s = s.onEvent(AgentEvent.ToolRan(1, "tap", "{}", "ok"))
        s = s.onEvent(AgentEvent.ToolRan(2, "tap", "{}", "error: stale_ref - Latest snapshot is s_4"))
        assertEquals(listOf(UiLine(LineKind.WARN, "error: stale_ref - Latest snapshot is s_4")), s.lines.filter { it.kind == LineKind.WARN })
    }

    @Test
    fun finishedResultsBecomeResultQuestionOrError() {
        val base = RunState().begin("t")
        val done = base.onEvent(AgentEvent.Finished(AgentResult.Done("It is 16", 3)))
        assertFalse(done.running)
        assertEquals(UiLine(LineKind.RESULT, "It is 16"), done.lines.last())

        val ask = base.onEvent(AgentEvent.Finished(AgentResult.NeedsUser("Which account?", 2)))
        assertEquals(UiLine(LineKind.QUESTION, "Which account?"), ask.lines.last())
        assertEquals(AgentResult.NeedsUser("Which account?", 2), ask.result)

        val stop = base.onEvent(AgentEvent.Finished(AgentResult.Stopped("stuck", 3)))
        assertEquals(LineKind.ERROR, stop.lines.last().kind)
        assertTrue(stop.lines.last().text.contains("stuck"))
    }

    @Test
    fun linesSurviveANewRunSoAnswersAppearUnderTheirQuestion() {
        val first = RunState().begin("a").onEvent(AgentEvent.Finished(AgentResult.NeedsUser("Q?", 1)))
        val second = first.begin("a + answer", display = "my answer")
        assertEquals(listOf(LineKind.USER, LineKind.QUESTION, LineKind.USER), second.lines.map { it.kind })
        assertNull(second.result)
    }

    @Test
    fun stoppedByUserEndsTheRunWithAMessage() {
        val s = RunState().begin("t").stoppedByUser()
        assertFalse(s.running)
        assertEquals(UiLine(LineKind.ERROR, "Stopped."), s.lines.last())
    }

    @Test
    fun stopReasonsAreFriendly() {
        assertEquals("Could not reach the model (http_401 bad key).", Describe.stopReason("llm_error: http_401 bad key"))
        assertTrue(Describe.stopReason("max_steps").contains("step limit"))
        assertEquals("Stopped: weird", Describe.stopReason("weird"))
    }

    @Test
    fun followUpKeepsTheOriginalTaskQuestionAndAnswer() {
        val text = FollowUp.compose("Reply to Ann", "Which Ann?", "Ann Lee")
        assertTrue(text.startsWith("Reply to Ann"))
        assertTrue(text.contains("You asked: Which Ann?") && text.contains("answer: Ann Lee"))
    }
}
