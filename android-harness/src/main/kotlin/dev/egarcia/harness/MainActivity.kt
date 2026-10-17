package dev.egarcia.harness

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.egarcia.harness.voice.Mic
import dev.egarcia.harness.voice.MicButton
import dev.egarcia.harness.voice.VoiceInput
import dev.egarcia.harness.voice.VoiceMode
import dev.egarcia.harness.voice.VoicePrefs
import dev.egarcia.harness.voice.VoiceState
import dev.egarcia.harness.agent.AgentResult
import dev.egarcia.harness.agent.AgentRunner
import dev.egarcia.harness.agent.FollowUp
import dev.egarcia.harness.agent.LineKind
import dev.egarcia.harness.agent.RunState
import dev.egarcia.harness.agent.UiLine
import dev.egarcia.harness.llm.LlmConfigStore
import dev.egarcia.harness.setup.AndroidSetupEnvironment
import dev.egarcia.harness.setup.SetupActions
import dev.egarcia.harness.setup.SetupChecker
import dev.egarcia.harness.setup.SetupFlow
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
    private lateinit var action: ActionButton
    private lateinit var clear: Button
    private lateinit var banner: TextView
    private lateinit var mic: MicButton
    private lateinit var voice: VoiceInput
    private var voiceBase = ""
    private val handler = Handler(Looper.getMainLooper())
    private var statusLine: TextView? = null
    private var seenStatusVersion = -1L
    private var statusSince = 0L
    private val ticker = object : Runnable {
        override fun run() {
            updateStatusLine(AgentRunner.state.value)
            if (AgentRunner.isRunning) handler.postDelayed(this, 1_000)
        }
    }

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
        banner = TextView(this).apply {
            textSize = 14f
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener { SetupActions.openChecklist(this@MainActivity) }
        }

        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(log) }

        // One rounded container: text field on top, microphone and run/stop underneath on the right.
        val composer = Composer(this)
        input = composer.input
        action = composer.action
        mic = composer.mic
        action.setOnClickListener { onActionClicked() }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = renderAction()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        mic.apply {
            setOnClickListener { voice.toggle() }
            setOnLongClickListener { startActivity(Intent(this@MainActivity, VoiceSettingsActivity::class.java)); true }
        }
        voice = VoiceInput(this, object : VoiceInput.Listener {
            override fun onState(state: VoiceState) {
                mic.render(state)
                if (state is VoiceState.Failed) Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                if (state == VoiceState.Listening) voiceBase = input.text.toString().trimEnd()
            }
            override fun onText(text: String, final: Boolean) {
                input.setText(listOf(voiceBase, text.trim()).filter { it.isNotEmpty() }.joinToString(" "))
                input.setSelection(input.text.length)
            }
            override fun onNeedPermission() { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MIC) }
        })
        val inputRow = composer

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(header)
            addView(status)
            addView(banner)
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
        // First run: show the checklist once instead of surprising the user with permission dialogs.
        if (!SetupFlow.seenOnFirstLaunch(this)) {
            SetupFlow.markSeen(this)
            if (SetupChecker.evaluate(AndroidSetupEnvironment(this)).needsAttention) SetupActions.openChecklist(this)
        }
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { uiScope = it }
        scope.launch { AgentRunner.state.collect(::render) }
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        uiScope?.cancel()
        uiScope = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Voice input is optional: the microphone appears only once it is turned on in Settings.
        mic.visibility = if (VoicePrefs.mode(this) == VoiceMode.OFF) View.GONE else View.VISIBLE
        refreshStatus()
    }

    override fun onPause() {
        voice.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        voice.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_MIC) {
            if (Mic.permitted(this)) voice.toggle() else toast("Microphone permission is needed for voice input")
        }
    }

    private fun onActionClicked() {
        voice.cancel()
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
            AgentRunner.StartResult.NO_ACCESSIBILITY -> { toast("Turn on the accessibility service first"); SetupActions.openChecklist(this) }
            AgentRunner.StartResult.NO_LLM -> { toast("Set up the model first"); SetupActions.openChecklist(this) }
            AgentRunner.StartResult.BUSY -> toast("A task is already running")
            AgentRunner.StartResult.LOCKED -> toast("Unlock the phone first")
        }
    }

    private fun renderAction(running: Boolean = AgentRunner.isRunning) {
        action.render(running, canRun = input.text.isNotBlank())
    }

    private fun render(state: RunState) {
        renderAction(state.running)
        input.isEnabled = !state.running
        input.hint = if (state.result is AgentResult.NeedsUser) "Answer the question…" else "What should I do on this phone?"
        clear.visibility = if (state.lines.isEmpty() || state.running) View.GONE else View.VISIBLE
        log.removeAllViews()
        state.lines.forEach { log.addView(lineView(it)) }
        if (state.statusVersion != seenStatusVersion) {
            seenStatusVersion = state.statusVersion
            statusSince = SystemClock.elapsedRealtime()
        }
        statusLine = null
        if (state.running && state.status.isNotEmpty()) {
            statusLine = muted("").also { log.addView(it) }
            updateStatusLine(state)
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, 1_000)
        }
        // scrollTo, not fullScroll(FOCUS_DOWN): the latter moves focus onto the last selectable bubble and highlights it.
        scroll.post { scroll.scrollTo(0, log.bottom) }
        refreshStatus()
    }

    /** "Step 2: waiting for the model · 14s": the shared model server can be slow, so show that we are alive. */
    private fun updateStatusLine(state: RunState) {
        val seconds = (SystemClock.elapsedRealtime() - statusSince) / 1000
        statusLine?.text = if (seconds >= 3) "${state.status} · ${seconds}s" else "${state.status}…"
    }

    private fun updateBanner() {
        val report = SetupChecker.evaluate(AndroidSetupEnvironment(this))
        val problem = report.firstProblem
        if (problem == null) {
            banner.visibility = View.GONE
            return
        }
        val blocking = !report.readyToRun
        banner.text = (if (blocking) "Setup needed: " else "Recommended: ") + problem.title + ". Tap to fix."
        banner.setTextColor(Color.parseColor(if (blocking) "#B3261E" else "#8A5A00"))
        banner.background = rounded(Color.parseColor(if (blocking) "#FDECEA" else "#FFF4E5"), 12)
        banner.visibility = View.VISIBLE
    }

    private fun refreshStatus() {
        updateBanner()
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQUEST_MIC = 77
    }
}
