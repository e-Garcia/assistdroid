package dev.egarcia.harness

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.egarcia.harness.agent.Msg
import dev.egarcia.harness.llm.LlmConfigStore
import dev.egarcia.harness.llm.LlmException
import dev.egarcia.harness.llm.OpenAiCompatibleClient
import dev.egarcia.harness.setup.AndroidSetupEnvironment
import dev.egarcia.harness.setup.Importance
import dev.egarcia.harness.setup.ModelProbe
import dev.egarcia.harness.setup.ModelProbeResult
import dev.egarcia.harness.setup.SetupActions
import dev.egarcia.harness.setup.SetupChecker
import dev.egarcia.harness.setup.SetupFlow
import dev.egarcia.harness.setup.SetupItem
import dev.egarcia.harness.setup.SetupStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The setup checklist: what this app needs, what is missing, and a button that takes the user to the exact
 * system screen or permission dialog. It re-checks every time the user comes back, and the accessibility
 * service brings them back here as soon as it is switched on.
 */
class SetupActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var summary: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        summary = TextView(this).apply { textSize = 15f }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val done = Button(this).apply {
            text = "Done"
            setOnClickListener { finish() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(this@SetupActivity).apply {
                text = "Setup"
                textSize = 22f
                setTextColor(Color.parseColor("#1F1F1F"))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(summary)
            addView(list)
            addView(done)
        }
        val frame = LinearLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(ScrollView(this@SetupActivity).apply { addView(column) })
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

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    private fun refresh() {
        val report = SetupChecker.evaluate(AndroidSetupEnvironment(this))
        summary.text = when {
            !report.needsAttention -> "All set. You can give the agent a task."
            report.readyToRun -> "Ready to run. ${report.okCount} of ${report.counted.size} checks pass; the rest are recommended."
            else -> "${report.okCount} of ${report.counted.size} ready. Finish the required items to start."
        }
        summary.setTextColor(Color.parseColor(if (report.readyToRun) "#1E7B34" else "#B3261E"))
        summary.setPadding(0, dp(4), 0, dp(8))
        list.removeAllViews()
        report.items.forEach { list.addView(card(it)) }
    }

    private fun card(item: SetupItem): View {
        val (mark, color) = when {
            item.status == SetupStatus.OK -> "✓" to "#1E7B34"
            item.importance == Importance.OPTIONAL -> "○" to "#5F6368"
            item.importance == Importance.REQUIRED && item.status == SetupStatus.MISSING -> "✗" to "#B3261E"
            else -> "!" to "#B26A00"
        }
        val badge = TextView(this).apply {
            text = mark
            textSize = 20f
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val heading = TextView(this).apply {
            text = item.title + if (item.status != SetupStatus.OK && item.importance != Importance.OPTIONAL) (if (item.importance == Importance.REQUIRED) "  ·  required" else "  ·  recommended") else ""
            textSize = 16f
            setTextColor(Color.parseColor("#1F1F1F"))
            setTypeface(typeface, Typeface.BOLD)
        }
        val detail = TextView(this).apply {
            text = item.detail
            textSize = 13f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(0, dp(2), 0, dp(4))
        }
        val buttons = LinearLayout(this).apply {
            item.buttons.forEach { b ->
                addView(Button(this@SetupActivity).apply {
                    text = b.label
                    isAllCaps = false
                    setOnClickListener { SetupActions.perform(this@SetupActivity, b.action, ::checkModel) }
                })
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading)
            addView(detail)
            if (item.buttons.isNotEmpty()) addView(buttons)
        }
        return LinearLayout(this).apply {
            background = rounded(Color.parseColor("#F6F7F8"), 14)
            setPadding(dp(12), dp(12), dp(12), dp(8))
            addView(badge)
            addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
    }

    private fun checkModel() {
        val config = LlmConfigStore.load(this) ?: return
        ModelProbe.result = ModelProbeResult.Checking
        refresh()
        scope.launch {
            val started = System.nanoTime()
            val outcome = withContext(Dispatchers.IO) {
                runCatching { OpenAiCompatibleClient(config).use { it.chat(listOf(Msg.user("Reply with the single word OK.")), emptyList()) } }
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            ModelProbe.result = outcome.fold(
                { ModelProbeResult.Ok(millis) },
                { ModelProbeResult.Failed(((it as? LlmException)?.let { e -> "${e.code}: ${e.message}" } ?: it.message ?: "failed").take(120)) },
            )
            refresh()
        }
    }
}
