package dev.egarcia.harness.inbox

import android.content.Context
import dev.egarcia.harness.policy.AuditSink
import dev.egarcia.harness.policy.NoAudit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * What the agent can read without operating another app: notifications from apps the user chose to watch and
 * (optionally) a digest of email from senders the user chose. Read-only. Results are framed as untrusted content.
 */
interface InboxSource {
    val notificationsAvailable: Boolean
    val emailAvailable: Boolean
    suspend fun notifications(app: String?, sinceMinutes: Int, limit: Int): String
    suspend fun email(limit: Int): String
}

class AndroidInboxSource(private val context: Context, private val audit: AuditSink = NoAudit) : InboxSource {

    override val notificationsAvailable: Boolean
        get() = InboxStore.watched(context).isNotEmpty() && InboxStore.listenerEnabled(context)

    override val emailAvailable: Boolean get() = EmailStore.load(context) != null

    override suspend fun notifications(app: String?, sinceMinutes: Int, limit: Int): String {
        if (!InboxStore.listenerEnabled(context)) {
            return "Notification access is off, so no notifications were captured. Ask the user to turn it on in the app's setup checklist."
        }
        if (InboxStore.watched(context).isEmpty()) {
            return "No apps are being watched. Ask the user to choose apps in Settings > Notification digest."
        }
        val items = InboxStore.repository(context).recent(app, sinceMinutes.coerceIn(1, 7 * 24 * 60) * 60_000L, limit)
        audit.log("notifications_read", mapOf("app" to (app ?: "all"), "count" to items.size.toString()))
        return InboxFormatter.notifications(items, System.currentTimeMillis())
    }

    override suspend fun email(limit: Int): String {
        val config = EmailStore.load(context) ?: return "Email digest is not set up. Ask the user to add an account in Settings > Email digest."
        return try {
            val items = withContext(Dispatchers.IO) { withTimeout(EMAIL_TIMEOUT_MS) { ImapEmailClient(config).digest(limit) } }
            audit.log("email_digest_read", mapOf("count" to items.size.toString()))
            val scope = buildString {
                append(if (config.unreadOnly) "unread only" else "read and unread")
                append(", last ${config.maxAgeDays} days")
                if (config.senders.isNotEmpty()) append(", chosen senders only")
            }
            EmailFormatter.digest(items, System.currentTimeMillis(), scope)
        } catch (e: EmailException) {
            audit.log("email_digest_failed", emptyMap())
            "Could not read email: ${e.message}"
        } catch (_: TimeoutCancellationException) {
            audit.log("email_digest_failed", emptyMap())
            "Could not read email: the mail server took too long to answer."
        }
    }

    private companion object {
        const val EMAIL_TIMEOUT_MS = 45_000L
    }
}
