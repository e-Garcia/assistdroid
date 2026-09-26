package ga.egarcia.harness.agent

import ga.egarcia.harness.llm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

enum class LineKind { USER, STEP, WARN, RESULT, QUESTION, ERROR }

data class UiLine(val kind: LineKind, val text: String)

/**
 * Everything the chat screen and the notification show. Pure data: [AgentRunner] feeds it agent events.
 * Lines persist across runs until cleared, so a follow-up answer appears under its question.
 */
data class RunState(
    val running: Boolean = false,
    val task: String? = null,
    val lines: List<UiLine> = emptyList(),
    /** Short current activity, shown in the notification. */
    val status: String = "",
    /** Bumped whenever [status] changes, so the UI can show how long the current status has lasted. */
    val statusVersion: Long = 0,
    val result: AgentResult? = null,
) {
    private fun withStatus(text: String) = copy(status = text, statusVersion = statusVersion + 1)

    fun begin(task: String, display: String = task) = copy(
        running = true,
        task = task,
        lines = lines + UiLine(LineKind.USER, display),
        result = null,
    ).withStatus("Starting…")

    fun onEvent(event: AgentEvent): RunState = when (event) {
        is AgentEvent.Started -> this
        is AgentEvent.ModelRequested -> withStatus("Step ${event.step}: waiting for the model")
        is AgentEvent.ModelRetry -> withStatus("Model busy, retrying (${event.attempt} of ${event.of})")
        is AgentEvent.ModelReplied -> {
            val action = event.toolCalls.firstOrNull { it.name != "done" && it.name != "ask_user" }
            when {
                action != null -> {
                    val text = "Step ${event.step}: ${Describe.tool(action)}"
                    // The notification status must not echo tool arguments: it is on screen, so text the agent
                    // waits for or searches would be found in our own notification.
                    copy(lines = lines + UiLine(LineKind.STEP, text)).withStatus("Step ${event.step}: ${Describe.toolShort(action)}")
                }
                event.toolCalls.isEmpty() -> withStatus("Thinking…")
                else -> this
            }
        }
        is AgentEvent.ToolRan ->
            if (event.result.startsWith("error") || event.result.startsWith("timeout")) {
                copy(lines = lines + UiLine(LineKind.WARN, event.result))
            } else {
                this
            }
        is AgentEvent.Finished -> {
            val line = when (val r = event.result) {
                is AgentResult.Done -> UiLine(LineKind.RESULT, r.summary.ifBlank { "Done." })
                is AgentResult.NeedsUser -> UiLine(LineKind.QUESTION, r.question)
                is AgentResult.Stopped -> UiLine(LineKind.ERROR, Describe.stopReason(r.reason))
            }
            copy(running = false, status = "", lines = lines + line, result = event.result)
        }
    }

    fun stoppedByUser() = stoppedWith("Stopped.")

    fun stoppedWith(message: String) = copy(running = false, status = "", lines = lines + UiLine(LineKind.ERROR, message), result = null)

    fun finished() = if (running) copy(running = false, status = "") else this
}

internal object Describe {
    fun tool(call: ToolCall): String {
        val args = runCatching { Json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        return when (call.name) {
            "tap" -> "Tap ${args.str("ref") ?: "?"}"
            "type" -> "Type \"${args.str("text").orEmpty().take(40)}\" into ${args.str("ref") ?: "?"}"
            "scroll" -> "Scroll ${args.str("direction").orEmpty()}".trim()
            "swipe" -> "Swipe"
            "back" -> "Go back"
            "home" -> "Go to the home screen"
            "open_app" -> "Open ${args.str("app") ?: "an app"}"
            "wait_for" -> "Wait for \"${args.str("text").orEmpty().take(40)}\""
            "look" -> "Look at the screen"
            else -> call.name
        }
    }

    private val SENSITIVE_ARGS = setOf("text", "question", "summary")

    /** For logs: keeps refs and app names, replaces typed text, questions and summaries with their length. */
    fun redactedCall(call: ToolCall): String {
        val args = runCatching { Json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject }.getOrNull()
            ?: return "${call.name}(<unparsed>)"
        return call.name + args.entries.joinToString(", ", "(", ")") { (key, value) ->
            val raw = (value as? JsonPrimitive)?.content ?: value.toString()
            if (key in SENSITIVE_ARGS) "$key=<${raw.length} chars>" else "$key=$raw"
        }
    }

    /** Argument-free wording for surfaces the agent can also see (the notification). */
    fun toolShort(call: ToolCall): String = when (call.name) {
        "tap" -> "tapping"
        "type" -> "typing"
        "scroll", "swipe" -> "scrolling"
        "back" -> "going back"
        "home" -> "going home"
        "open_app" -> "opening an app"
        "wait_for" -> "waiting"
        "look" -> "looking at the screen"
        else -> "working"
    }

    fun stopReason(reason: String): String = when {
        reason == "stuck" -> "I got stuck repeating the same action."
        reason == "max_steps" -> "I reached the step limit before finishing."
        reason == "timeout" -> "The task took too long and was stopped."
        reason == "no_tool_call" -> "The model did not choose an action."
        reason.startsWith("llm_error") -> "Could not reach the model (${reason.removePrefix("llm_error:").trim()})."
        else -> "Stopped: $reason"
    }
}

/** Builds the task for a follow-up run after the agent asked the user something. */
object FollowUp {
    fun compose(originalTask: String, question: String, answer: String) =
        "$originalTask\n\nYou asked: $question\nThe user's answer: $answer\n\nContinue the task."
}
