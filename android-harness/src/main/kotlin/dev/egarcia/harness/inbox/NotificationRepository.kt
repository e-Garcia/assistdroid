package dev.egarcia.harness.inbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CapturedMessage(val sender: String? = null, val text: String, val at: Long = 0)

@Serializable
data class CapturedNotification(
    val key: String,
    val pkg: String,
    val app: String,
    val postedAt: Long,
    val title: String? = null,
    val text: String? = null,
    val conversation: String? = null,
    val messages: List<CapturedMessage> = emptyList(),
)

/** An app that has posted notifications, so the user can choose to watch it. Holds no notification content. */
@Serializable
data class SeenApp(val pkg: String, val label: String, val lastSeenAt: Long, val count: Int)

/** A notification as the listener service hands it over, before any filtering. */
data class IncomingNotification(
    val key: String,
    val pkg: String,
    val appLabel: String,
    val postedAt: Long,
    val title: CharSequence?,
    val text: CharSequence?,
    val conversation: CharSequence?,
    val messages: List<CapturedMessage>,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean,
    val isSecret: Boolean,
)

interface Persistence {
    fun read(): String?
    fun write(text: String)
}

class MemoryPersistence : Persistence {
    private var value: String? = null
    override fun read() = value
    override fun write(text: String) {
        value = text
    }
}

@Serializable
private data class State(val items: List<CapturedNotification> = emptyList(), val seen: List<SeenApp> = emptyList())

/**
 * Notifications from the apps the user chose to watch, kept locally for a short time so the agent can read them
 * without operating the app. Opt-in only: nothing is stored from an app that is not on the allowlist, apps that
 * the safety policy blocks (banking, password managers, this app) are never stored or even listed, and secret,
 * ongoing (media, foreground service) and group-summary notifications are ignored.
 */
class NotificationRepository(
    private val persistence: Persistence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxItems: Int = 200,
    private val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var state: State? = null

    @Synchronized
    private fun load(): State = state ?: (persistence.read()?.let { runCatching { json.decodeFromString<State>(it) }.getOrNull() } ?: State()).also { state = it }

    @Synchronized
    private fun save(next: State) {
        state = next
        persistence.write(json.encodeToString(State.serializer(), next))
    }

    /** Returns the stored notification, or null if it was not captured. */
    @Synchronized
    fun onPosted(n: IncomingNotification, allowed: Set<String>, isDenied: (String) -> Boolean): CapturedNotification? {
        if (n.isGroupSummary || n.isOngoing || n.isSecret || isDenied(n.pkg)) return null
        val title = clean(n.title, MAX_TITLE)
        val text = clean(n.text, MAX_TEXT)
        val messages = n.messages.takeLast(MAX_MESSAGES).mapNotNull { m ->
            clean(m.text, MAX_TEXT)?.let { CapturedMessage(clean(m.sender, MAX_SENDER), it, m.at) }
        }
        if (title == null && text == null && messages.isEmpty()) return null

        val now = clock()
        val current = load()
        val seen = (current.seen.filter { it.pkg != n.pkg } +
            SeenApp(n.pkg, n.appLabel, now, (current.seen.firstOrNull { it.pkg == n.pkg }?.count ?: 0) + 1))
            .sortedByDescending { it.lastSeenAt }.take(MAX_SEEN)
        if (n.pkg !in allowed) {
            save(current.copy(seen = seen))
            return null
        }
        val captured = CapturedNotification(n.key, n.pkg, n.appLabel, n.postedAt, title, text, clean(n.conversation, MAX_TITLE), messages)
        // The same key is an update of the same notification (a conversation gaining messages): replace it.
        val items = (current.items.filter { it.key != n.key } + captured).let { pruned(it, now) }
        save(State(items, seen))
        return captured
    }

    /** Newest first. [app] matches a package name exactly or an app label loosely. */
    @Synchronized
    fun recent(app: String?, sinceMs: Long, limit: Int): List<CapturedNotification> {
        val cutoff = clock() - sinceMs
        val wanted = app?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return load().items
            .filter { it.postedAt >= cutoff }
            .filter { wanted == null || it.pkg.lowercase() == wanted || it.app.lowercase().contains(wanted) }
            .sortedByDescending { it.postedAt }
            .take(limit.coerceIn(1, MAX_RESULT))
    }

    @Synchronized
    fun seenApps(): List<SeenApp> = load().seen

    @Synchronized
    fun count(): Int = load().items.size

    /** Removes everything, or only what came from [pkg] (for example when the user stops watching that app). */
    @Synchronized
    fun clear(pkg: String? = null) {
        val current = load()
        save(if (pkg == null) State(seen = current.seen) else current.copy(items = current.items.filter { it.pkg != pkg }))
    }

    private fun pruned(items: List<CapturedNotification>, now: Long) =
        items.filter { now - it.postedAt <= maxAgeMs }.sortedByDescending { it.postedAt }.take(maxItems)

    /** Notification text is untrusted and may carry control characters or spans; keep plain, bounded text. */
    private fun clean(value: CharSequence?, max: Int): String? =
        value?.toString()?.replace(Regex("[\\p{Cntrl}&&[^\\n]]"), " ")?.replace(Regex("[ \\t]+"), " ")?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    companion object {
        const val MAX_TITLE = 120
        const val MAX_TEXT = 600
        const val MAX_SENDER = 80
        const val MAX_MESSAGES = 5
        const val MAX_SEEN = 60
        const val MAX_RESULT = 50
    }
}
