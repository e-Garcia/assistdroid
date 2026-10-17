package ga.egarcia.harness.inbox

import android.content.Context
import ga.egarcia.harness.llm.SecretBox

/** Persists the email account; the app password is encrypted with the Android Keystore key. Opt-in: no config, no email tool. */
object EmailStore {
    private const val PREFS = "harness_email"

    fun load(context: Context): EmailConfig? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = p.getString("host", null) ?: return null
        val password = p.getString("password", null)?.let(SecretBox::decrypt) ?: return null
        return EmailConfig(
            host = host,
            port = p.getInt("port", 993),
            username = p.getString("username", "").orEmpty(),
            password = password,
            folder = p.getString("folder", "INBOX").orEmpty(),
            senders = p.getString("senders", "").orEmpty().split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() },
            unreadOnly = p.getBoolean("unread_only", true),
            maxAgeDays = p.getInt("max_age_days", 7),
        ).takeIf { it.problem() == null }
    }

    fun save(context: Context, config: EmailConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host", config.host.trim())
            .putInt("port", config.port)
            .putString("username", config.username.trim())
            .putString("password", SecretBox.encrypt(config.password))
            .putString("folder", config.folder.trim())
            .putString("senders", config.senders.joinToString(","))
            .putBoolean("unread_only", config.unreadOnly)
            .putInt("max_age_days", config.maxAgeDays)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
