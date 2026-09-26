package ga.egarcia.harness.diag

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import ga.egarcia.harness.BuildConfig
import ga.egarcia.harness.HarnessAccessibilityService
import ga.egarcia.harness.TokenStore
import ga.egarcia.harness.UiPrefs
import ga.egarcia.harness.inbox.EmailStore
import ga.egarcia.harness.inbox.InboxStore
import ga.egarcia.harness.llm.CleartextPolicy
import ga.egarcia.harness.llm.LlmConfigStore
import ga.egarcia.harness.playbook.PlaybookStore
import ga.egarcia.harness.policy.AuditStore
import ga.egarcia.harness.policy.PolicyStore
import ga.egarcia.harness.setup.AndroidSetupEnvironment
import ga.egarcia.harness.setup.ModelProbe
import ga.egarcia.harness.setup.SetupChecker
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Developer switches. Detailed records are off by default because they include the task text and model replies. */
object DevPrefs {
    private const val PREFS = "harness_dev"

    fun detailed(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("detailed", false)

    fun setDetailed(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("detailed", on).apply()
    }
}

/** Where session records and the crash note live. One shared store instance so two writers never interleave. */
object DiagStore {
    @Volatile
    private var sessions: SessionStore? = null

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "diagnostics").also { it.mkdirs() }

    fun sessions(context: Context): SessionStore = sessions ?: synchronized(this) {
        sessions ?: SessionStore(File(dir(context), "sessions.jsonl")).also { sessions = it }
    }

    fun crashFile(context: Context) = File(dir(context), "last_crash.txt")

    /** Reports go to app-specific external storage: readable with `adb pull` and shareable through the FileProvider. */
    fun exportDir(context: Context): File =
        File(context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir, "diagnostics").also { it.mkdirs() }

    fun clear(context: Context) {
        sessions(context).clear()
        crashFile(context).delete()
        AuditStore.get(context).clear()
        exportDir(context).listFiles()?.forEach { it.delete() }
    }
}

/** Writes the last uncaught exception to disk, then lets Android handle the crash as usual. */
object CrashRecorder {
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { DiagStore.crashFile(app).writeText(CrashFormat.format(Instant.now().toString(), thread.name, error)) }
            previous?.uncaughtException(thread, error)
        }
    }
}

/** Collects the report from the real device. Nothing here reads screen contents. */
object AndroidDiagnostics {

    fun build(context: Context, maxChars: Int = Report.DEFAULT_MAX_CHARS): String {
        val app = context.applicationContext
        val secrets = buildList {
            add(TokenStore.getOrCreate(app))
            LlmConfigStore.load(app)?.let { add(it.apiKey) }
            EmailStore.load(app)?.let { add(it.password) }
        }
        val store = DiagStore.sessions(app)
        val crash = DiagStore.crashFile(app).takeIf { it.exists() }?.readText()
        val input = ReportInput(
            generatedAt = Instant.now().toString(),
            environment = environment(app),
            settings = settings(app),
            setup = SetupChecker.evaluate(AndroidSetupEnvironment(app)).items.map { "${it.id}: ${it.status} (${it.importance.name.lowercase()})" },
            runs = store.read(),
            rawRuns = store.rawLines(),
            crash = crash,
            audit = AuditStore.get(app).read(300),
            logcat = logcat(),
        )
        return Report.build(input, Redactor(secrets), maxChars)
    }

    /** Writes the report into the export folder (newest five kept) and returns the file. */
    fun writeReport(context: Context): File {
        val dir = DiagStore.exportDir(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "harness-diagnostics-$stamp.txt")
        file.writeText(build(context))
        dir.listFiles { f -> f.name.startsWith("harness-diagnostics-") }?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = DiagFileProvider.uriFor(context.packageName, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "AI Harness diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri("diagnostics", uri)
        return Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun environment(context: Context): Map<String, String> {
        val metrics = context.resources.displayMetrics
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) }
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 }
        return linkedMapOf(
            "app" to "${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE}, commit ${BuildConfig.GIT_SHA})",
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, patch ${Build.VERSION.SECURITY_PATCH})",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), abi ${Build.SUPPORTED_ABIS.firstOrNull()}",
            "build_fingerprint" to Build.FINGERPRINT,
            "display" to "${metrics.widthPixels}x${metrics.heightPixels} px, ${metrics.densityDpi} dpi",
            "screen_off_timeout" to (runCatching { android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT) / 1000 }.getOrNull()?.let { "$it s" } ?: "?"),
            "locale" to Locale.getDefault().toLanguageTag(),
            "timezone" to TimeZone.getDefault().id,
            "memory" to "${memory.availMem / MB} MB free of ${memory.totalMem / MB} MB${if (memory.lowMemory) " (LOW)" else ""}",
            "battery" to "${level ?: "?"}%${if (charging == true) " charging" else ""}",
            "battery_unrestricted" to context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName).toString(),
            "accessibility_connected" to (HarnessAccessibilityService.automation != null).toString(),
            "last_model_probe" to ModelProbe.result.toString(),
        )
    }

    private fun settings(context: Context): Map<String, String> {
        val llm = LlmConfigStore.load(context)
        val policy = PolicyStore.load(context)
        return linkedMapOf(
            "model" to (llm?.model ?: "(not set)"),
            "model_endpoint" to (llm?.let { endpointKind(it.baseUrl) } ?: "(not set)"),
            "vision" to llm?.vision.toString(),
            "thinking" to llm?.thinking.toString(),
            "max_tokens" to llm?.maxTokens.toString(),
            "confirm_gate" to policy.gateEnabled.toString(),
            "extra_blocked_entries" to policy.customDenied.size.toString(),
            "app_notes" to PlaybookStore.load(context).size.toString(),
            "dim_while_running" to (UiPrefs.dimLevel(context)?.let { "${(it * 100).toInt()}%" } ?: "off"),
            "floating_button" to UiPrefs.overlayEnabled(context).toString(),
            "notification_access" to InboxStore.listenerEnabled(context).toString(),
            "notification_apps_watched" to InboxStore.watched(context).size.toString(),
            "notifications_stored" to InboxStore.repository(context).count().toString(),
            "email_digest_configured" to (EmailStore.load(context) != null).toString(),
            "detailed_session_records" to DevPrefs.detailed(context).toString(),
        )
    }

    /** Where the model runs, without printing the host name or address. */
    fun endpointKind(baseUrl: String): String {
        val uri = runCatching { java.net.URI(baseUrl.trim()) }.getOrNull() ?: return "invalid"
        val host = uri.host?.lowercase() ?: return "invalid"
        val kind = when {
            host == "10.0.2.2" -> "emulator host"
            host.endsWith(".ts.net") || Regex("^100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.").containsMatchIn(host) -> "tailscale"
            CleartextPolicy.isLocalHost(host) -> "local network"
            else -> "public internet"
        }
        return "$kind, ${uri.scheme}, port ${if (uri.port == -1) "default" else uri.port.toString()}, ${if (host.first().isDigit()) "ip" else "name"}"
    }

    /** An app can read only its own log lines; this returns the newest ones. */
    private fun logcat(): List<String> = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "800").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.destroy()
        lines.filterNot { it.startsWith("---") }
    }.getOrDefault(emptyList())

    private const val MB = 1024 * 1024
}
