package ga.egarcia.harness.agent

import ga.egarcia.harness.Element
import ga.egarcia.harness.Snapshot

/**
 * Compact text view of a screen for the model, about a third of the JSON size. Deliberately has no
 * snapshot id: the agent loop tracks it, and identical screens must produce identical text (stuck detection).
 */
object SnapshotFormatter {

    fun format(snapshot: Snapshot, maxElements: Int = 250): String = buildString {
        append("package: ").append(snapshot.pkg ?: "unknown")
        snapshot.blind?.let { append("\nBLIND: the screen cannot be read (").append(it).append(")") }
        snapshot.elements.take(maxElements).forEach { append('\n').append(line(it)) }
        val hidden = snapshot.elements.size - maxElements
        if (hidden > 0) append("\n(").append(hidden).append(" more elements omitted; scroll to see them)")
    }

    internal fun line(e: Element): String {
        val sb = StringBuilder(e.ref).append(' ').append(e.role)
        e.text?.let { sb.append(" \"").append(clean(it)).append('"') }
        e.desc?.takeIf { it != e.text }?.let { sb.append(" desc=\"").append(clean(it)).append('"') }
        val flags = buildList {
            if (e.clickable) add("click")
            if (e.editable) add("edit")
            if (e.scrollable) add("scroll")
            e.checked?.let { add(if (it) "checked" else "unchecked") }
            if (e.focused) add("focused")
        }
        if (flags.isNotEmpty()) sb.append(" [").append(flags.joinToString(",")).append(']')
        // Bounds only where the model may need coordinates (swipes inside a scrollable area).
        if (e.scrollable) sb.append(" bounds=").append(e.bounds.joinToString(","))
        return sb.toString()
    }

    private fun clean(text: String) = text.replace('\n', ' ').replace('"', '\'')
}
