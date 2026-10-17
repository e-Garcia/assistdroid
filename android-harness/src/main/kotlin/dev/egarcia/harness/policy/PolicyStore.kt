package dev.egarcia.harness.policy

import android.content.Context
import java.io.File

/** Persists the owner's safety settings. Editable only in the app UI or over adb, never by the agent. */
object PolicyStore {
    private const val PREFS = "harness_policy"

    fun load(context: Context): PolicyConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PolicyConfig(
            customDenied = parseEntries(p.getString("custom_denied", "").orEmpty()),
            gateEnabled = p.getBoolean("gate_enabled", true),
        )
    }

    fun save(context: Context, config: PolicyConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("custom_denied", config.customDenied.joinToString("\n"))
            .putBoolean("gate_enabled", config.gateEnabled)
            .apply()
    }

    /** One entry per line or comma; blank lines and `#` comments are dropped. */
    fun parseEntries(text: String): List<String> =
        text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
}

/** One shared instance: two writers on the same file would interleave lines. */
object AuditStore {
    @Volatile
    private var instance: AuditLog? = null

    fun get(context: Context): AuditLog = instance ?: synchronized(this) {
        instance ?: AuditLog(File(context.applicationContext.filesDir, "audit.jsonl")).also { instance = it }
    }
}
