package ga.egarcia.harness.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ga.egarcia.harness.MainActivity
import ga.egarcia.harness.R
import ga.egarcia.harness.UiPrefs
import ga.egarcia.harness.agent.AgentResult
import ga.egarcia.harness.agent.AgentRunner
import ga.egarcia.harness.agent.FollowUp
import ga.egarcia.harness.agent.LineKind
import ga.egarcia.harness.agent.RunState
import ga.egarcia.harness.dp
import ga.egarcia.harness.rounded
import ga.egarcia.harness.setup.SetupActions
import ga.egarcia.harness.voice.MicButton
import ga.egarcia.harness.voice.MicPermissionActivity
import ga.egarcia.harness.voice.VoiceInput
import ga.egarcia.harness.voice.VoiceMode
import ga.egarcia.harness.voice.VoicePrefs
import ga.egarcia.harness.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The floating button: prompt, status and Stop over any app, drawn as accessibility overlay windows (no
 * extra permission). While a task runs only the non-focusable pill is shown, so the app being operated keeps
 * focus and this app's own windows never become what the agent reads. Off by default (Settings).
 */
class OverlayController(private val service: AccessibilityService) {

    private val windows = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var root: View? = null
    private var statusView: TextView? = null
    private var voice: VoiceInput? = null
    private var mode = OverlayMode.BUBBLE
    private var enabled = false

    // Bubble position, as offsets from the top-right corner; kept across redraws so it stays where the user put it.
    private var bubbleX = 0
    private var bubbleY = service.dp(240)

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == UiPrefs.KEY_OVERLAY) main.post { apply() }
    }

    fun start() {
        UiPrefs.prefs(service).registerOnSharedPreferenceChangeListener(prefsListener)
        main.post { apply() }
    }

    fun stop() {
        UiPrefs.prefs(service).unregisterOnSharedPreferenceChangeListener(prefsListener)
        main.post { teardown() }
    }

    private fun apply() {
        val want = UiPrefs.overlayEnabled(service)
        if (want && !enabled) setup() else if (!want && enabled) teardown()
    }

    private fun setup() {
        enabled = true
        mode = OverlayMode.BUBBLE
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch { AgentRunner.state.collect(::onState) }
        }
        show(OverlayMode.BUBBLE)
    }

    private fun teardown() {
        enabled = false
        scope?.cancel()
        scope = null
        removeRoot()
    }

    private fun onState(state: RunState) {
        if (!enabled) return
        val next = OverlayModel.next(state.running, mode)
        when {
            next != mode -> show(next)
            mode == OverlayMode.RUNNING -> statusView?.text = state.status.ifEmpty { "Working…" }
        }
    }

    private fun show(newMode: OverlayMode) {
        removeRoot()
        mode = newMode
        val (view, params) = when (newMode) {
            OverlayMode.BUBBLE -> bubble()
            OverlayMode.PROMPT -> prompt()
            OverlayMode.RUNNING -> pill()
            OverlayMode.RESULT -> resultCard()
        }
        runCatching { windows.addView(view, params) }.onSuccess { root = view }
    }

    private fun removeRoot() {
        voice?.destroy()
        voice = null
        root?.let { runCatching { windows.removeView(it) } }
        root = null
        statusView = null
    }

    // --- Views ---------------------------------------------------------------------------------------

    private fun params(width: Int, focusable: Boolean, gravity: Int, x: Int = 0, y: Int = 0) = WindowManager.LayoutParams(
        width, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).also {
        it.gravity = gravity
        it.x = x
        it.y = y
        if (focusable) it.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
    }

    private fun bubble(): Pair<View, WindowManager.LayoutParams> {
        val size = service.dp(52)
        val view = ImageView(service).apply {
            setImageResource(R.drawable.ic_stat_agent)
            background = service.rounded(Color.parseColor("#3F51B5"), 26)
            setPadding(service.dp(12), service.dp(12), service.dp(12), service.dp(12))
            elevation = service.dp(6).toFloat()
        }
        val lp = params(size, focusable = false, gravity = Gravity.TOP or Gravity.END, x = bubbleX, y = bubbleY)
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                        moved = true
                        lp.x = startX - dx.toInt() // gravity END: moving left increases x
                        lp.y = startY + dy.toInt()
                        bubbleX = lp.x; bubbleY = lp.y
                        runCatching { windows.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) show(OverlayMode.PROMPT)
            }
            true
        }
        return view to lp
    }

    private fun prompt(): Pair<View, WindowManager.LayoutParams> {
        val pending = AgentRunner.state.value.result as? AgentResult.NeedsUser
        val input = EditText(service).apply {
            hint = if (pending != null) "Answer the question…" else "What should I do on this phone?"
            maxLines = 3
            setTextColor(Color.parseColor("#1F1F1F"))
            setHintTextColor(Color.parseColor("#80868B"))
        }
        val run = ga.egarcia.harness.ActionButton(service)
        val close = Button(service, null, android.R.attr.borderlessButtonStyle).apply { text = "Close" }
        run.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            val state = AgentRunner.state.value
            val task = (state.result as? AgentResult.NeedsUser)?.let { FollowUp.compose(state.task.orEmpty(), it.question, text) } ?: text
            // Drop focus (pill is not focusable) before the agent looks at the screen; the delay covers the swap.
            hideKeyboard(input)
            voice?.cancel()
            when (AgentRunner.start(service, task, display = text, startDelayMs = 700)) {
                AgentRunner.StartResult.STARTED -> show(OverlayMode.RUNNING)
                AgentRunner.StartResult.NO_LLM -> { toast("Set up the model first"); SetupActions.openChecklist(service) }
                AgentRunner.StartResult.LOCKED -> toast("Unlock the phone first")
                AgentRunner.StartResult.BUSY -> toast("A task is already running")
                AgentRunner.StartResult.NO_ACCESSIBILITY -> { toast("Turn on the accessibility service first"); SetupActions.openChecklist(service) }
            }
        }
        close.setOnClickListener { hideKeyboard(input); show(OverlayMode.BUBBLE) }
        // Optional voice input: the microphone sits to the left of Close, so the text box keeps its width.
        var mic: MicButton? = null
        if (VoicePrefs.mode(service) != VoiceMode.OFF) {
            var base = ""
            val button = MicButton(service).also { mic = it }
            val session = VoiceInput(service, object : VoiceInput.Listener {
                override fun onState(state: VoiceState) {
                    button.render(state)
                    if (state is VoiceState.Failed) toast(state.message)
                    if (state == VoiceState.Listening) base = input.text.toString().trimEnd()
                }
                override fun onText(text: String, final: Boolean) {
                    input.setText(listOf(base, text.trim()).filter { it.isNotEmpty() }.joinToString(" "))
                    input.setSelection(input.text.length)
                }
                override fun onNeedPermission() {
                    service.startActivity(android.content.Intent(service, MicPermissionActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
            voice = session
            button.setOnClickListener { session.toggle() }
        }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = service.rounded(Color.WHITE, 18)
            setPadding(service.dp(16), service.dp(12), service.dp(16), service.dp(8))
            elevation = service.dp(8).toFloat()
            addView(input)
            addView(LinearLayout(service).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                mic?.let { addView(it, LinearLayout.LayoutParams(service.dp(40), service.dp(40)).apply { marginEnd = service.dp(4) }) }
                addView(close)
                addView(run, LinearLayout.LayoutParams(service.dp(40), service.dp(40)).apply { marginStart = service.dp(4) })
            })
        }
        input.post { input.requestFocus(); service.getSystemService(InputMethodManager::class.java).showSoftInput(input, 0) }
        return card to params(service.resources.displayMetrics.widthPixels - service.dp(32), focusable = true, gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL, y = service.dp(96))
    }

    private fun pill(): Pair<View, WindowManager.LayoutParams> {
        val status = TextView(service).apply {
            text = AgentRunner.state.value.status.ifEmpty { "Working…" }
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, service.dp(12), 0)
        }
        statusView = status
        val stop = Button(service, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Stop"
            setTextColor(Color.parseColor("#FF8A80"))
            setOnClickListener { AgentRunner.stop() }
        }
        val pill = LinearLayout(service).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = service.rounded(Color.parseColor("#E6202124"), 26)
            setPadding(service.dp(18), service.dp(4), service.dp(8), service.dp(4))
            elevation = service.dp(8).toFloat()
            addView(status)
            addView(stop)
        }
        return pill to params(WindowManager.LayoutParams.WRAP_CONTENT, focusable = false, gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL, y = service.dp(56))
    }

    private fun resultCard(): Pair<View, WindowManager.LayoutParams> {
        val state = AgentRunner.state.value
        val last = state.lines.lastOrNull { it.kind == LineKind.RESULT || it.kind == LineKind.QUESTION || it.kind == LineKind.ERROR }
        val asked = state.result is AgentResult.NeedsUser
        val text = TextView(service).apply {
            this.text = last?.text ?: "Finished."
            textSize = 15f
            maxLines = 8
            setTextColor(Color.parseColor("#1F1F1F"))
        }
        val reply = Button(service, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = "Reply"
            setOnClickListener { show(OverlayMode.PROMPT) }
        }
        val openChat = Button(service, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = "Open"
            setOnClickListener {
                show(OverlayMode.BUBBLE)
                service.startActivity(android.content.Intent(service, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val dismiss = Button(service, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = "Close"
            setOnClickListener { show(OverlayMode.BUBBLE) }
        }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = service.rounded(if (asked) Color.parseColor("#FFF1CC") else Color.parseColor("#DDF3E4"), 18)
            setPadding(service.dp(16), service.dp(12), service.dp(16), service.dp(4))
            elevation = service.dp(8).toFloat()
            addView(text)
            addView(LinearLayout(service).apply {
                gravity = Gravity.END
                if (asked) addView(reply)
                addView(openChat)
                addView(dismiss)
            })
        }
        return card to params(service.resources.displayMetrics.widthPixels - service.dp(32), focusable = false, gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL, y = service.dp(96))
    }

    private fun hideKeyboard(view: View) {
        service.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun toast(message: String) = Toast.makeText(service, message, Toast.LENGTH_LONG).show()
}
