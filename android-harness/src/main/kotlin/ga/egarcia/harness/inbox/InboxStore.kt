package ga.egarcia.harness.inbox

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import ga.egarcia.harness.llm.SecretBox
import java.io.File

/** Captured notifications are encrypted at rest with an Android Keystore key (same box as the API key). */
class EncryptedFilePersistence(private val file: File) : Persistence {
    override fun read(): String? = file.takeIf { it.exists() }?.readText()?.let(SecretBox::decrypt)
    override fun write(text: String) = file.writeText(SecretBox.encrypt(text))
}

/** The shared repository plus the user's choices: which apps to watch. Nothing is watched until the user says so. */
object InboxStore {
    private const val PREFS = "harness_inbox"

    @Volatile
    private var repository: NotificationRepository? = null

    fun repository(context: Context): NotificationRepository = repository ?: synchronized(this) {
        repository ?: NotificationRepository(EncryptedFilePersistence(File(context.applicationContext.filesDir, "notifications.enc")))
            .also { repository = it }
    }

    fun watched(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("watched", emptySet()).orEmpty().toSet()

    /** Stops capturing for removed apps and deletes what was already captured from them. */
    fun setWatched(context: Context, packages: Set<String>) {
        val removed = watched(context) - packages
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("watched", packages).apply()
        removed.forEach { repository(context).clear(it) }
    }

    fun setWatching(context: Context, pkg: String, watching: Boolean) =
        setWatched(context, if (watching) watched(context) + pkg else watched(context) - pkg)

    /** True when the user has granted this app notification access in system settings. */
    fun listenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val mine = ComponentName(context, HarnessNotificationListener::class.java)
        return flat.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == mine }
    }
}
