package ga.egarcia.harness

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ga.egarcia.harness.agent.AgentResult
import ga.egarcia.harness.agent.AgentRunner
import ga.egarcia.harness.agent.FollowUp
import ga.egarcia.harness.agent.LineKind
import ga.egarcia.harness.agent.RunState
import ga.egarcia.harness.agent.UiLine
import ga.egarcia.harness.llm.LlmConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Chat screen: type a task, watch the agent's steps, stop it. The agent runs while this screen is in the background. */
class MainActivity : Activity() {

    private var uiScope: CoroutineScope? = null
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var log: LinearLayout
    private lateinit var input: EditText
    private lateinit var action: Button
    private lateinit var clear: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)

        val title = TextView(this).apply {
            text = "AI Harness"
            textSize = 22f
            setTextColor(Color.parseColor("#1F1F1F"))
            setTypeface(typeface, Typeface.BOLD)
        }
        clear = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Clear"
            setOnClickListener { AgentRunner.clear() }
        }
        val settings = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(clear)
            addView(settings)
        }

        status = TextView(this).apply { textSize = 13f }

        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(log) }

        input = EditText(this).apply {
            hint = "What should I do on this phone?"
            maxLines = 4
            background = rounded(Color.parseColor("#F1F3F4"), 20)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        action = Button(this).apply { setOnClickListener { onActionClicked() } }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(action)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(header)
            addView(status)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            })
            addView(inputRow)
        }
        val padded = LinearLayout(this).apply {
            setPadding(pad, pad, pad, pad)
            addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        // Insets go on a full-size wrapper so the bars and keyboard push the whole screen, not just the input.
        val frame = LinearLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(padded, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        applySystemInsets(frame)
        setContentView(frame)
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { uiScope = it }
        scope.launch { AgentRunner.state.collect(::render) }
    }

    override fun onStop() {
        uiScope?.cancel()
        uiScope = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun onActionClicked() {
        if (AgentRunner.isRunning) {
            AgentRunner.stop()
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val state = AgentRunner.state.value
        val pending = state.result as? AgentResult.NeedsUser
        val task = if (pending != null) FollowUp.compose(state.task.orEmpty(), pending.question, text) else text

        // Leave the chat first so the agent's first snapshot is not of this screen.
        val result = AgentRunner.start(applicationContext, task, display = text, startDelayMs = 700)
        when (result) {
            AgentRunner.StartResult.STARTED -> {
                input.text.clear()
                moveTaskToBack(true)
            }
            AgentRunner.StartResult.NO_ACCESSIBILITY -> toast("Turn on the AI Harness accessibility service first (Settings)")
            AgentRunner.StartResult.NO_LLM -> toast("Set up the model first (Settings)")
            AgentRunner.StartResult.BUSY -> toast("A task is already running")
        }
    }

    private fun render(state: RunState) {
        action.text = if (state.running) "Stop" else "Run"
        input.isEnabled = !state.running
        input.hint = if (state.result is AgentResult.NeedsUser) "Answer the question…" else "What should I do on this phone?"
        clear.visibility = if (state.lines.isEmpty() || state.running) View.GONE else View.VISIBLE
        log.removeAllViews()
        state.lines.forEach { log.addView(lineView(it)) }
        if (state.running && state.status.isNotEmpty()) log.addView(muted("${state.status}…".replace("……", "…")))
        // scrollTo, not fullScroll(FOCUS_DOWN): the latter moves focus onto the last selectable bubble and highlights it.
        scroll.post { scroll.scrollTo(0, log.bottom) }
        refreshStatus()
    }

    private fun refreshStatus() {
        val accessibility = HarnessAccessibilityService.automation != null
        val model = LlmConfigStore.load(this)?.model
        status.text = "Accessibility: ${if (accessibility) "on" else "OFF"} · Model: ${model ?: "not set"}"
        status.setTextColor(if (accessibility && model != null) Color.parseColor("#5F6368") else Color.parseColor("#B3261E"))
    }

    private fun lineView(line: UiLine): View = when (line.kind) {
        LineKind.USER -> bubble(line.text, "#DCEBFF", Gravity.END)
        LineKind.RESULT -> bubble(line.text, "#DDF3E4", Gravity.START)
        LineKind.QUESTION -> bubble(line.text, "#FFF1CC", Gravity.START)
        LineKind.ERROR -> muted(line.text, "#B3261E")
        LineKind.WARN -> muted("  ↳ ${line.text}", "#B26A00")
        LineKind.STEP -> muted(line.text)
    }

    private fun bubble(text: String, color: String, gravity: Int) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextIsSelectable(true)
        setTextColor(Color.parseColor("#1F1F1F"))
        background = rounded(Color.parseColor(color))
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            this.gravity = gravity
            topMargin = dp(8)
            if (gravity == Gravity.END) marginStart = dp(48) else marginEnd = dp(48)
        }
    }

    private fun muted(text: String, color: String = "#5F6368") = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor(color))
        setPadding(dp(4), dp(4), dp(4), dp(0))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
