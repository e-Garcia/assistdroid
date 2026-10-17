package ga.egarcia.harness.inbox

/**
 * IMAP account for the email digest. Read-only by construction: the client only ever opens the folder read-only and
 * never sends, deletes, flags or moves anything. Use an app password (Gmail, Fastmail, ...), never the main password.
 */
data class EmailConfig(
    val host: String,
    val port: Int = 993,
    val username: String,
    val password: String,
    val folder: String = "INBOX",
    /** Addresses (`ann@example.com`) or domains (`example.com`, `@example.com`). Empty means every sender. */
    val senders: List<String> = emptyList(),
    val unreadOnly: Boolean = true,
    val maxAgeDays: Int = 7,
) {
    override fun toString() = "EmailConfig(host=$host, port=$port, username=$username, password=<redacted>, folder=$folder, senders=${senders.size}, unreadOnly=$unreadOnly)"

    /** Null when usable, otherwise what to fix. The connection is always TLS, so there is no plaintext option. */
    fun problem(): String? = when {
        host.isBlank() -> "Enter the IMAP server, for example imap.gmail.com."
        host.any { it.isWhitespace() || it == '/' || it == ':' } -> "The server is a host name only, without https:// or a port."
        port !in 1..65535 -> "The port must be between 1 and 65535 (993 for TLS)."
        username.isBlank() -> "Enter the account's email address."
        password.isBlank() -> "Enter an app password."
        folder.isBlank() -> "Enter the folder to read (INBOX)."
        else -> null
    }
}

/** One message, reduced to what a digest needs. [snippet] is untrusted text written by the sender. */
data class EmailSummary(
    val from: String,
    val subject: String,
    val receivedAt: Long,
    val unread: Boolean,
    val snippet: String,
)

class EmailException(message: String) : Exception(message)

/** Which senders the owner allowed. Matching is on the address, never on the display name (which anyone can forge). */
object SenderFilter {

    fun matches(allowed: List<String>, address: String?): Boolean {
        val entries = allowed.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (entries.isEmpty()) return true
        val a = address?.trim()?.lowercase()?.takeIf { it.contains('@') } ?: return false
        val domain = a.substringAfterLast('@')
        return entries.any { e ->
            when {
                '@' !in e -> domain == e || domain.endsWith(".$e")
                e.startsWith("@") -> domain == e.drop(1) || domain.endsWith(".${e.drop(1)}")
                else -> a == e
            }
        }
    }

    /** `"Ann Lee" <ann@example.com>` and `ann@example.com` both give the address; anything else gives null. */
    fun addressOf(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        val inBrackets = Regex("<([^<>\\s]+@[^<>\\s]+)>").findAll(text).lastOrNull()?.groupValues?.get(1)
        return (inBrackets ?: text.takeIf { Regex("^[^\\s<>@]+@[^\\s<>@]+$").matches(it) })?.lowercase()
    }
}

/** Turns messages into the text the agent sees. */
object EmailFormatter {
    const val MAX_SNIPPET = 300
    const val MAX_SUBJECT = 150

    fun digest(items: List<EmailSummary>, now: Long, scope: String): String {
        if (items.isEmpty()) return "No email matches ($scope)."
        return InboxFormatter.frame("email_digest", buildString {
            append("${items.size} message(s), newest first ($scope):")
            items.forEach { m ->
                append("\n- ${InboxFormatter.age(now, m.receivedAt)} · ${if (m.unread) "unread" else "read"} · from ${m.from}")
                append("\n    Subject: ${m.subject}")
                if (m.snippet.isNotBlank()) append("\n    ${m.snippet}")
            }
        })
    }

    /** One line, no control characters, capped: the sender controls this text. */
    fun oneLine(text: String?, max: Int): String {
        val cleaned = text.orEmpty().replace(Regex("[\\p{Cntrl}\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]+"), " ").replace(Regex("\\s+"), " ").trim()
        return if (cleaned.length <= max) cleaned else cleaned.take(max - 1).trimEnd() + "…"
    }

    /** Best-effort plain text from an HTML-only message: drops scripts, styles and tags, decodes the common entities. */
    fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>"), "\n")
        .replace(Regex("(?s)<[^>]*>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
}
