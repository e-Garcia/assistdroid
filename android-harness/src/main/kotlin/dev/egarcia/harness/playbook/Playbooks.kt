package dev.egarcia.harness.playbook

import android.content.Context

/**
 * Owner-written notes about how specific apps work, injected into the agent's prompt while that app is
 * in front (ADR 0005). They are plain text: they can help the agent find things, but they cannot loosen the
 * safety rules, which are enforced in code around every action.
 *
 * Format, one entry per package (a line starting with `package.name:`), continuation lines allowed:
 * ```
 * # comment
 * com.example.messagingapp: Messaging is the bottom tab "Messaging".
 *   Unread conversations show a dot; the newest is at the top.
 * ```
 */
class Playbooks(private val entries: List<Entry>) {

    data class Entry(val key: String, val hint: String)

    /** The most specific entry wins: an exact package or the longest package prefix. */
    fun hintFor(pkg: String?): String? {
        val p = pkg?.lowercase() ?: return null
        return entries.filter { p == it.key || p.startsWith(it.key + ".") }
            .maxByOrNull { it.key.length }?.hint?.take(MAX_HINT)
    }

    val size: Int get() = entries.size

    companion object {
        const val MAX_HINT = 1_500

        private val HEADER = Regex("^\\s*([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)\\s*:\\s*(.*)$")

        fun parse(text: String): Playbooks {
            val entries = mutableListOf<Entry>()
            var key: String? = null
            val hint = StringBuilder()
            fun flush() {
                key?.let { k -> hint.toString().trim().takeIf { it.isNotEmpty() }?.let { entries += Entry(k.lowercase(), it) } }
                key = null
                hint.clear()
            }
            for (line in text.lines()) {
                if (line.trimStart().startsWith("#")) continue
                val header = HEADER.matchEntire(line)
                if (header != null) {
                    flush()
                    key = header.groupValues[1]
                    hint.append(header.groupValues[2])
                } else if (key != null && line.isNotBlank()) {
                    hint.append('\n').append(line.trim())
                }
            }
            flush()
            return Playbooks(entries)
        }
    }
}

object PlaybookStore {
    private const val PREFS = "harness_playbooks"
    private const val KEY = "text"

    fun loadText(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun saveText(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, text).apply()
    }

    fun load(context: Context): Playbooks = Playbooks.parse(loadText(context))
}
