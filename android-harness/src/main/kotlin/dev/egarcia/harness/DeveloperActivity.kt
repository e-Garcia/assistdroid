package ga.egarcia.harness

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import ga.egarcia.harness.diag.AndroidDiagnostics
import ga.egarcia.harness.diag.DevPrefs
import ga.egarcia.harness.diag.DiagStore
import ga.egarcia.harness.diag.Metrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Export what happened (timings, tool calls, outcomes, logs) so problems can be diagnosed from a paste or a file. */
class DeveloperActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var summary: TextView
    private lateinit var result: TextView
    private lateinit var detailed: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        summary = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#3C4043")); typeface = Typeface.MONOSPACE }
        result = TextView(this).apply { textSize = 13f; setPadding(0, dp(8), 0, 0) }
        detailed = Switch(this).apply {
            text = "Detailed session records"
            isChecked = DevPrefs.detailed(this@DeveloperActivity)
            setOnCheckedChangeListener { _, on -> DevPrefs.setDetailed(this@DeveloperActivity, on) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            listOf(
                TextView(this@DeveloperActivity).apply {
                    text = "Developer"
                    textSize = 22f
                    setTextColor(Color.parseColor("#1F1F1F"))
                    setTypeface(typeface, Typeface.BOLD)
                },
                text("Every agent task is recorded with its timings, token counts, tool calls (typed text replaced by its length), outcomes and the app that was in front. The report adds device and setup details, the activity log, the last crash and this app's log lines."),
                text("At the default level it never contains your API key, tokens or passwords (they are masked), screenshots or screen text."),
                heading("Recorded so far"),
                summary,
                heading("Detail level"),
                detailed,
                text("Off: metrics only. On: also the task you typed, the model's replies and the first 300 characters of each tool result (which can include message text). Turn it on only for a run you are about to share, and off again afterwards."),
                heading("Export"),
                button("Share report") { share() },
                button("Copy report to clipboard") { copy() },
                button("Save report on the phone (for adb pull)") { save() },
                text("Share opens Android's share sheet: pick Nextcloud, Drive, email or a messaging app. Or copy it and paste it into the chat. From a computer: adb pull /sdcard/Android/data/$packageName/files/diagnostics/"),
                result,
                heading("Clear"),
                button("Delete recorded sessions, logs and reports") { clear() },
            ).forEach(::addView)
        }
        val frame = LinearLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(ScrollView(this@DeveloperActivity).apply { addView(content) })
        }
        applySystemInsets(frame)
        setContentView(frame)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        val runs = DiagStore.sessions(this).read()
        val m = Metrics.of(runs)
        val crash = DiagStore.crashFile(this).takeIf { it.exists() }
        summary.text = buildString {
            append("runs: ${runs.size}  (done ${m["done"]}, needs you ${m["needs_user"]}, stopped ${m["stopped"]})\n")
            append("model latency p50/p95: ${m["llm_ms_p50"]} / ${m["llm_ms_p95"]} ms\n")
            append("avg steps per run: ${m["avg_steps_per_run"]}\n")
            append("last crash: ${crash?.let { java.util.Date(it.lastModified()) } ?: "none"}")
        }
    }

    private fun share() = export("Preparing the report…") { file ->
        startActivity(AndroidDiagnostics.shareIntent(this, file))
        "Report ready (${file.length() / 1024} KB): ${file.name}"
    }

    private fun save() = export("Saving…") { file -> "Saved ${file.length() / 1024} KB to ${file.absolutePath}" }

    private fun copy() {
        show("Preparing the report…", null)
        scope.launch {
            // Clipboard transfers are limited to about 1 MB, so the pasted version is shorter than the file.
            val text = withContext(Dispatchers.IO) { AndroidDiagnostics.build(this@DeveloperActivity, maxChars = 100_000) }
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AI Harness diagnostics", text))
            show("Copied ${text.length / 1000} thousand characters. Paste them into the chat.", true)
        }
    }

    private fun export(message: String, done: (java.io.File) -> String) {
        show(message, null)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { AndroidDiagnostics.writeReport(this@DeveloperActivity) } }
            outcome.onSuccess { show(done(it), true) }.onFailure { show("Could not create the report: ${it.message}", false) }
        }
    }

    private fun clear() {
        DiagStore.clear(this)
        refresh()
        show("Sessions, activity log, crash note and reports deleted.", true)
    }

    private fun show(message: String, ok: Boolean?) {
        result.text = message
        result.setTextColor(Color.parseColor(when (ok) { true -> "#1E7B34"; false -> "#B3261E"; null -> "#5F6368" }))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun text(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#5F6368"))
        setPadding(0, dp(4), 0, dp(6))
    }
}
