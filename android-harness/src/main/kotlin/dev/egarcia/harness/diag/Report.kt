package dev.egarcia.harness.diag

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlin.math.ceil

/** Hides secrets that could end up in exported text: literal secrets we know, bearer tokens and API-key shapes. */
class Redactor(secrets: Collection<String>) {
    private val literals = secrets.map { it.trim() }.filter { it.length >= MIN_SECRET }.distinct().sortedByDescending { it.length }

    fun clean(text: String): String {
        var out = text
        literals.forEach { out = out.replace(it, "<redacted>") }
        return PATTERNS.fold(out) { acc, (regex, replacement) -> regex.replace(acc, replacement) }
    }

    private companion object {
        const val MIN_SECRET = 8
        val PATTERNS = listOf(
            Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}") to "$1<redacted>",
            Regex("(?i)(api[_ -]?(?:token|key)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',}]{6,}") to "$1<redacted>",
            Regex("\\bsk-[A-Za-z0-9_-]{10,}") to "<redacted>",
            Regex("(?i)(authorization[\"']?\\s*[:=]\\s*)[^\\n]{6,}") to "$1<redacted>",
        )
    }
}

/** Summary numbers across recorded sessions, so a glance shows where time and failures go. */
object Metrics {

    fun of(runs: List<RunRecord>): JsonObject {
        val steps = runs.flatMap { it.stepsDetail }
        val llm = steps.mapNotNull { it.llmMs }.sorted()
        val tokensIn = steps.mapNotNull { it.tokensIn }
        val tools = steps.mapNotNull { s -> s.tool?.substringBefore('(')?.let { it to (s.outcome ?: "ok") } }
        return buildJsonObject {
            put("runs", runs.size)
            put("done", runs.count { it.result == "done" })
            put("needs_user", runs.count { it.result == "needs_user" })
            put("stopped", runs.count { it.result == "stopped" })
            put("stop_reasons", buildJsonObject {
                runs.filter { it.result == "stopped" }.groupingBy { (it.reason ?: "?").substringBefore(':') }.eachCount().forEach { (k, v) -> put(k, v) }
            })
            put("avg_steps_per_run", if (runs.isEmpty()) 0.0 else round1(runs.sumOf { it.steps }.toDouble() / runs.size))
            put("avg_run_seconds", if (runs.isEmpty()) 0.0 else round1(runs.sumOf { it.durationMs } / 1000.0 / runs.size))
            put("model_retries", runs.sumOf { it.retries })
            put("llm_ms_p50", percentile(llm, 50))
            put("llm_ms_p95", percentile(llm, 95))
            put("llm_ms_max", llm.lastOrNull() ?: 0L)
            put("tokens_in_avg_per_step", if (tokensIn.isEmpty()) 0 else tokensIn.sum() / tokensIn.size)
            put("tokens_out_total", runs.sumOf { it.tokensOut })
            put("snapshot_ms_p95", percentile(steps.mapNotNull { it.snapshotMs }.filter { it > 0 }.sorted(), 95))
            put("tool_calls", buildJsonObject {
                tools.groupingBy { it.first }.eachCount().toSortedMap().forEach { (k, v) -> put(k, v) }
            })
            put("tool_problems", buildJsonObject {
                tools.filter { it.second != "ok" }.groupingBy { "${it.first}: ${it.second}" }.eachCount().toSortedMap().forEach { (k, v) -> put(k, v) }
            })
            put("blind_screens", steps.count { it.blind != null })
        }
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    /** Nearest-rank percentile of an ascending list; 0 when empty. */
    fun percentile(sorted: List<Long>, p: Int): Long =
        if (sorted.isEmpty()) 0 else sorted[(ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
}

/** Everything the report needs, already collected. Strings only, so the layout is testable without Android. */
data class ReportInput(
    val generatedAt: String,
    val environment: Map<String, String>,
    val settings: Map<String, String>,
    val setup: List<String>,
    val runs: List<RunRecord>,
    val rawRuns: List<String>,
    val crash: String?,
    val audit: List<String>,
    val logcat: List<String>,
)

object Report {
    const val DEFAULT_MAX_CHARS = 300_000

    /**
     * Plain text meant to be pasted or attached. If it exceeds [maxChars] the logcat is shortened first, then the
     * audit log, then the oldest sessions, so the newest evidence survives.
     */
    fun build(input: ReportInput, redactor: Redactor, maxChars: Int = DEFAULT_MAX_CHARS): String {
        var logcat = input.logcat
        var audit = input.audit
        var raw = input.rawRuns
        var text = render(input, logcat, audit, raw)
        while (text.length > maxChars && (logcat.size > 20 || audit.size > 20 || raw.size > 3)) {
            if (logcat.size > 20) logcat = logcat.takeLast(maxOf(20, logcat.size / 2))
            else if (audit.size > 20) audit = audit.take(maxOf(20, audit.size / 2))
            else raw = raw.takeLast(maxOf(3, raw.size / 2))
            text = render(input, logcat, audit, raw)
        }
        return redactor.clean(text)
    }

    private fun render(i: ReportInput, logcat: List<String>, audit: List<String>, raw: List<String>) = buildString {
        appendLine("=== AssistDroid diagnostics ===")
        appendLine("generated: ${i.generatedAt}")
        appendLine("Contains no API key, token, password, screen text or screenshots. Package names, tool calls and timings are included.")
        appendLine()
        appendLine("=== Environment ===")
        i.environment.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        appendLine("=== Settings (no secrets) ===")
        i.settings.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        appendLine("=== Setup checklist ===")
        i.setup.forEach(::appendLine)
        appendLine()
        appendLine("=== Metrics across ${i.runs.size} recorded run(s) ===")
        appendLine(Metrics.of(i.runs).toString())
        appendLine()
        appendLine("=== Sessions, oldest first (JSON lines: one run each, steps inside) ===")
        if (raw.isEmpty()) appendLine("(none recorded yet)") else raw.forEach(::appendLine)
        appendLine()
        appendLine("=== Last crash ===")
        appendLine(i.crash ?: "(none)")
        appendLine()
        appendLine("=== Audit log, newest first ===")
        if (audit.isEmpty()) appendLine("(empty)") else audit.forEach(::appendLine)
        appendLine()
        appendLine("=== Logcat of this app, oldest first ===")
        if (logcat.isEmpty()) appendLine("(unavailable)") else logcat.forEach(::appendLine)
    }
}

/** Formats an uncaught exception for the crash file. Kept pure so it can be tested. */
object CrashFormat {
    fun format(at: String, thread: String, error: Throwable, maxChars: Int = 8_000): String {
        val trace = generateSequence(error) { it.cause }.take(4).joinToString("\nCaused by: ") { e ->
            "${e.javaClass.name}: ${e.message.orEmpty().take(300)}\n" + e.stackTrace.take(25).joinToString("\n") { "    at $it" }
        }
        return "time: $at\nthread: $thread\n$trace".take(maxChars)
    }
}
