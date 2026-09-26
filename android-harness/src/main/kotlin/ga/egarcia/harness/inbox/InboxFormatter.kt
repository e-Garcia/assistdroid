package ga.egarcia.harness.inbox

/** Turns captured items into the text the agent sees, framed as untrusted content like screen text. */
object InboxFormatter {

    fun notifications(items: List<CapturedNotification>, now: Long): String {
        if (items.isEmpty()) return "No captured notifications match. (Only apps you chose to watch are captured, and only while notification access is on.)"
        return frame("notifications", buildString {
            append("${items.size} notification(s), newest first:")
            items.forEach { n ->
                append("\n- ${age(now, n.postedAt)} · ${n.app} · ${n.conversation ?: n.title ?: "(no title)"}")
                if (n.messages.isNotEmpty()) {
                    n.messages.forEach { m -> append("\n    ${m.sender?.let { "$it: " }.orEmpty()}${m.text}") }
                } else {
                    n.text?.let { text -> append("\n    ${n.title?.takeIf { n.conversation != null }?.let { "$it: " }.orEmpty()}$text") }
                }
            }
        })
    }

    fun age(now: Long, at: Long): String {
        val minutes = ((now - at) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} d ago"
        }
    }

    /** Content from notifications and email comes from other people and may contain instructions aimed at the agent. */
    fun frame(tag: String, body: String) = "<$tag untrusted=\"true\">\n$body\n</$tag>"
}
