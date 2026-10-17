package dev.egarcia.harness.policy

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

fun interface AuditSink {
    /** Callers must not pass screen contents or typed text; [fields] values are truncated as a backstop. */
    fun log(event: String, fields: Map<String, String>)
}

object NoAudit : AuditSink {
    override fun log(event: String, fields: Map<String, String>) = Unit
}

/**
 * Local JSON-lines record of what the agent did: when, in which app, which action, and the outcome.
 * It never stores screen contents, screenshots or typed text (ADR 0002). The owner can read and clear it in the app.
 */
class AuditLog(
    private val file: File,
    private val maxLines: Int = 2_000,
    private val clock: () -> Instant = { Instant.now() },
) : AuditSink {

    @Volatile
    var runId: String = "-"
        private set

    private var writes = 0

    fun newRun(): String {
        runId = "r" + java.lang.Long.toString(clock().toEpochMilli(), 36)
        return runId
    }

    /** Entries outside an agent run (driven mode) are attributed to "-". */
    fun endRun() {
        runId = "-"
    }

    @Synchronized
    override fun log(event: String, fields: Map<String, String>) {
        val line = buildJsonObject {
            put("ts", clock().toString())
            put("run", runId)
            put("event", event)
            fields.forEach { (key, value) -> put(key, value.take(MAX_VALUE)) }
        }.toString()
        file.appendText(line + "\n")
        if (++writes % TRIM_EVERY == 0) trim()
    }

    /** Newest first. */
    @Synchronized
    fun read(limit: Int = 200): List<String> =
        if (file.exists()) file.readLines().takeLast(limit).reversed() else emptyList()

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun trim() {
        val lines = file.readLines()
        if (lines.size > maxLines) file.writeText(lines.takeLast(maxLines / 2).joinToString("\n") + "\n")
    }

    private companion object {
        const val MAX_VALUE = 80
        const val TRIM_EVERY = 100
    }
}
