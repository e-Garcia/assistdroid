package ga.egarcia.harness.diag

import ga.egarcia.harness.agent.AgentEvent
import ga.egarcia.harness.agent.AgentResult
import ga.egarcia.harness.agent.Describe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/** One agent step: what the model cost, what it chose, how the tool did, what the screen looked like afterwards. */
@Serializable
data class StepRecord(
    val n: Int,
    val llmMs: Long? = null,
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    /** Tool call with typed text, questions and summaries replaced by their length. */
    val tool: String? = null,
    val toolMs: Long? = null,
    /** ok, or the error/blocked word the tool returned. */
    val outcome: String? = null,
    /** The screen after the tool ran. Package names only, never screen text. */
    val pkg: String? = null,
    val elements: Int? = null,
    val blind: String? = null,
    val snapshotMs: Long? = null,
    /** Detailed mode only. */
    val reply: String? = null,
    val result: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RunRecord(
    val run: String,
    val startedAt: String,
    val durationMs: Long,
    /** done, needs_user or stopped. */
    val result: String,
    val reason: String? = null,
    val steps: Int,
    val taskChars: Int,
    val retries: Int = 0,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val llmMs: Long = 0,
    val startPkg: String? = null,
    val startElements: Int? = null,
    val startBlind: String? = null,
    val detailed: Boolean = false,
    /** Detailed mode only. */
    val task: String? = null,
    val summary: String? = null,
    val stepsDetail: List<StepRecord> = emptyList(),
)

/**
 * Turns the agent's event stream into a [RunRecord]. By default it keeps metrics only (timings, token counts, tool
 * names with redacted arguments, outcomes, package names). With [detailed] it also keeps the task text, the model's
 * prose and the first 300 characters of each tool result. It never keeps screen contents or screenshots.
 */
class SessionRecorder(
    private val runId: () -> String,
    private val detailed: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onComplete: (RunRecord) -> Unit,
) {
    private var startedAt = 0L
    private var task = ""
    private var full = false
    private var steps = sortedMapOf<Int, StepRecord>()
    private var requestedAt = 0L
    private var repliedAt = 0L
    private var retries = 0
    private var start: AgentEvent.Observed? = null
    private var active = false

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Started -> {
                startedAt = clock(); task = event.task; full = detailed(); steps = sortedMapOf(); retries = 0; start = null; active = true
            }
            is AgentEvent.ModelRequested -> requestedAt = clock()
            is AgentEvent.ModelRetry -> retries++
            is AgentEvent.ModelReplied -> {
                repliedAt = clock()
                val first = event.toolCalls.firstOrNull()
                update(event.step) {
                    it.copy(
                        llmMs = repliedAt - requestedAt,
                        tokensIn = event.usage?.promptTokens,
                        tokensOut = event.usage?.completionTokens,
                        tool = first?.let(Describe::redactedCall),
                        reply = if (full) event.text?.take(TEXT_CAP) else null,
                    )
                }
            }
            is AgentEvent.ToolRan -> update(event.step) {
                it.copy(toolMs = clock() - repliedAt, outcome = outcome(event.result), result = if (full) event.result.take(TEXT_CAP) else null)
            }
            is AgentEvent.Observed ->
                if (event.step == 0) start = event
                else update(event.step) { it.copy(pkg = event.pkg, elements = event.elements, blind = event.blind, snapshotMs = event.millis) }
            is AgentEvent.Finished -> finish(event.result)
        }
    }

    /** The run was cancelled (Stop, or the accessibility service went away) before the loop could finish. */
    fun aborted(reason: String) {
        if (active) finish(AgentResult.Stopped(reason, steps.keys.maxOrNull() ?: 0))
    }

    private fun update(n: Int, change: (StepRecord) -> StepRecord) {
        steps[n] = change(steps[n] ?: StepRecord(n))
    }

    private fun finish(result: AgentResult) {
        if (!active) return
        active = false
        val list = steps.values.toList()
        val (kind, reason) = when (result) {
            is AgentResult.Done -> "done" to null
            is AgentResult.NeedsUser -> "needs_user" to null
            is AgentResult.Stopped -> "stopped" to result.reason.take(80)
        }
        onComplete(
            RunRecord(
                run = runId(),
                startedAt = Instant.ofEpochMilli(startedAt).toString(),
                durationMs = clock() - startedAt,
                result = kind,
                reason = reason,
                steps = result.steps,
                taskChars = task.length,
                retries = retries,
                tokensIn = list.sumOf { it.tokensIn ?: 0 },
                tokensOut = list.sumOf { it.tokensOut ?: 0 },
                llmMs = list.sumOf { it.llmMs ?: 0 },
                startPkg = start?.pkg,
                startElements = start?.elements,
                startBlind = start?.blind,
                detailed = full,
                task = if (full) task.take(TEXT_CAP) else null,
                summary = if (full) when (result) {
                    is AgentResult.Done -> result.summary.take(TEXT_CAP)
                    is AgentResult.NeedsUser -> result.question.take(TEXT_CAP)
                    is AgentResult.Stopped -> null
                } else null,
                stepsDetail = list,
            ),
        )
    }

    companion object {
        private const val TEXT_CAP = 300
        private val WORDS = listOf("error", "blocked", "user_denied", "timeout", "warning", "denied", "stale_ref")

        /** `error: stale_ref ...` becomes `error: stale_ref`, prose is dropped and ordinary results become `ok`. Never returns screen text. */
        fun outcome(result: String): String {
            val head = result.trim().lowercase()
            val hit = WORDS.firstOrNull { head.startsWith(it) } ?: return "ok"
            val code = head.removePrefix(hit).trimStart(':', ' ').takeWhile { it.isLetterOrDigit() || it == '_' }.take(30)
            // Reason codes look like stale_ref or app_denied; a plain word after the colon is prose, not a code.
            return if ('_' !in code || hit == code) hit else "$hit: $code"
        }
    }
}

/** Newest runs kept on disk as JSON lines, capped by count. */
class SessionStore(private val file: File, private val maxRuns: Int = 50) {
    private val json = Json { encodeDefaults = false }

    @Synchronized
    fun append(record: RunRecord) {
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(record) + "\n")
        val lines = file.readLines()
        if (lines.size > maxRuns) file.writeText(lines.takeLast(maxRuns).joinToString("\n") + "\n")
    }

    /** Oldest first. Unreadable lines are skipped. */
    @Synchronized
    fun read(): List<RunRecord> =
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { runCatching { json.decodeFromString<RunRecord>(it) }.getOrNull() }

    @Synchronized
    fun rawLines(): List<String> = if (file.exists()) file.readLines() else emptyList()

    @Synchronized
    fun clear() {
        file.delete()
    }
}
