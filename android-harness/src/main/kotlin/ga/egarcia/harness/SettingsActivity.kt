package ga.egarcia.harness

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import ga.egarcia.harness.agent.Msg
import ga.egarcia.harness.llm.CleartextPolicy
import ga.egarcia.harness.llm.LlmConfig
import ga.egarcia.harness.llm.LlmConfigStore
import ga.egarcia.harness.llm.LlmException
import ga.egarcia.harness.llm.OpenAiCompatibleClient
import ga.egarcia.harness.playbook.PlaybookStore
import ga.egarcia.harness.setup.ModelProbe
import ga.egarcia.harness.setup.ModelProbeResult
import ga.egarcia.harness.policy.PolicyConfig
import ga.egarcia.harness.policy.PolicyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Model connection, accessibility status and the optional driven-mode token. */
class SettingsActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var existing: LlmConfig? = null

    private lateinit var baseUrl: EditText
    private lateinit var model: EditText
    private lateinit var apiKey: EditText
    private lateinit var extraBody: EditText
    private lateinit var maxTokens: EditText
    private lateinit var vision: Switch
    private lateinit var thinking: Switch
    private lateinit var gate: Switch
    private lateinit var denied: EditText
    private lateinit var notes: EditText
    private lateinit var floatingSwitch: Switch
    private lateinit var testResult: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var token: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        existing = LlmConfigStore.load(this)

        baseUrl = field("Base URL, e.g. http://my-server:8000/v1", existing?.baseUrl)
        model = field("Model name", existing?.model)
        apiKey = field(if (existing != null) "API key (leave empty to keep the saved key)" else "API key", null).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        extraBody = field("Extra request fields as JSON (optional)", existing?.extraBody?.takeIf { it.isNotEmpty() }?.toString())
        maxTokens = field("Max output tokens", (existing?.maxTokens ?: 1024).toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        vision = Switch(this).apply {
            text = "Send screenshots (model supports images)"
            isChecked = existing?.vision ?: true
        }
        thinking = Switch(this).apply {
            text = "Let the model think (slower, sometimes more accurate)"
            isChecked = existing?.thinking ?: false
        }
        testResult = TextView(this).apply { textSize = 13f }
        val policy = PolicyStore.load(this)
        floatingSwitch = Switch(this).apply {
            text = "Show a floating button over other apps"
            isChecked = UiPrefs.overlayEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> UiPrefs.setOverlayEnabled(this@SettingsActivity, checked) }
        }
        notes = field("App notes (optional)", PlaybookStore.loadText(this)).apply {
            isSingleLine = false
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        gate = Switch(this).apply {
            text = "Ask me before send, post, delete, buy or call (recommended)"
            isChecked = policy.gateEnabled
        }
        denied = field("Extra blocked apps: package names or words, one per line", policy.customDenied.joinToString("\n")).apply {
            isSingleLine = false
            minLines = 2
            gravity = android.view.Gravity.TOP
        }

        val save = Button(this).apply { text = "Save"; setOnClickListener { save() } }
        val test = Button(this).apply { text = "Test connection"; setOnClickListener { test() } }
        val saveNotes = Button(this).apply {
            text = "Save app notes"
            setOnClickListener {
                PlaybookStore.saveText(this@SettingsActivity, notes.text.toString())
                testResult.text = "Saved ${PlaybookStore.load(this@SettingsActivity).size} app note(s)."
                testResult.setTextColor(Color.parseColor("#1E7B34"))
            }
        }
        val checklist = Button(this).apply {
            text = "Setup checklist"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, SetupActivity::class.java)) }
        }
        val saveSafety = Button(this).apply { text = "Save safety settings"; setOnClickListener { saveSafety() } }
        val viewLog = Button(this).apply {
            text = "View activity log"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, AuditActivity::class.java)) }
        }

        accessibilityStatus = TextView(this).apply { textSize = 14f }
        val openAccessibility = Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val openAppInfo = Button(this).apply {
            text = "App info (allow restricted settings)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        token = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        val rotate = Button(this).apply {
            text = "Rotate token"
            setOnClickListener {
                TokenStore.rotate(this@SettingsActivity)
                refresh()
                Toast.makeText(this@SettingsActivity, "New token applies after the accessibility service restarts", Toast.LENGTH_LONG).show()
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            listOf(
                TextView(this@SettingsActivity).apply {
                    text = "Settings"
                    textSize = 22f
                    setTextColor(Color.parseColor("#1F1F1F"))
                    setTypeface(typeface, Typeface.BOLD)
                },
                heading("Model (OpenAI-compatible)"),
                baseUrl, model, apiKey, extraBody, maxTokens, vision, thinking,
                save, test, testResult,
                checklist,
                heading("Floating button"),
                hint("A small button over any app to give a task without opening this app. While a task runs it shows a Stop pill. Needs the accessibility service to be on."),
                floatingSwitch,
                heading("Safety"),
                hint("Always blocked: this app, password managers, authenticators, wallets and payment apps, and any app whose name or package contains \"bank\". The agent sees a blocked app as an empty screen and cannot act in it. It can never type into password fields."),
                gate, denied, saveSafety, viewLog,
                heading("App notes"),
                hint("Tell the agent how an app works. One entry per app, starting with its package name and a colon; extra lines continue the entry. Example:\ncom.example.messagingapp: Messaging is the bottom tab.\n  Unread threads show a dot."),
                notes, saveNotes,
                heading("Accessibility service"),
                accessibilityStatus,
                hint("On Samsung and Android 13+, a sideloaded app must be allowed under \"App info > ⋮ > Allow restricted settings\" before its accessibility service can be turned on."),
                openAccessibility, openAppInfo,
                heading("Driven mode (advanced)"),
                hint("Optional local API for tools on your computer (through adb forward). Requests need this token; the API is off-network by default and is blocked while an agent task runs."),
                token, rotate,
            ).forEach(::addView)
        }
        val frame = LinearLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(ScrollView(this@SettingsActivity).apply { addView(content) })
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
        val on = HarnessAccessibilityService.automation != null
        accessibilityStatus.text = if (on) "Service is running." else "Service is OFF. The agent cannot operate the phone until it is turned on."
        accessibilityStatus.setTextColor(if (on) Color.parseColor("#1E7B34") else Color.parseColor("#B3261E"))
        token.text = "Token:\n${TokenStore.getOrCreate(this)}"
    }

    /** Reads and validates the form; shows the problem and returns null if it is not usable. */
    private fun readForm(): LlmConfig? {
        val url = baseUrl.text.toString().trim()
        val name = model.text.toString().trim()
        val key = apiKey.text.toString().ifEmpty { existing?.apiKey.orEmpty() }
        val extra: JsonObject = extraBody.text.toString().trim().let {
            if (it.isEmpty()) JsonObject(emptyMap())
            else runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() ?: return problem("Extra request fields must be a JSON object")
        }
        return when {
            url.isEmpty() || name.isEmpty() || key.isEmpty() -> problem("Base URL, model and API key are required")
            !CleartextPolicy.isAllowed(url) -> problem("Plain HTTP is only allowed to local or Tailscale hosts. Use https:// for others.")
            else -> LlmConfig(
                baseUrl = url,
                model = name,
                apiKey = key,
                vision = vision.isChecked,
                thinking = thinking.isChecked,
                maxTokens = maxTokens.text.toString().toIntOrNull()?.coerceIn(64, 8192) ?: 1024,
                temperature = existing?.temperature ?: 0.0,
                extraBody = extra,
            )
        }
    }

    private fun saveSafety() {
        PolicyStore.save(this, PolicyConfig(customDenied = PolicyStore.parseEntries(denied.text.toString()), gateEnabled = gate.isChecked))
        testResult.text = "Safety settings saved."
        testResult.setTextColor(Color.parseColor("#1E7B34"))
    }

    private fun problem(message: String): LlmConfig? {
        testResult.text = message
        testResult.setTextColor(Color.parseColor("#B3261E"))
        return null
    }

    private fun save() {
        val config = readForm() ?: return
        LlmConfigStore.save(this, config)
        ModelProbe.result = ModelProbeResult.Unknown // the old connection check no longer applies
        existing = config
        apiKey.text.clear()
        testResult.text = "Saved."
        testResult.setTextColor(Color.parseColor("#1E7B34"))
    }

    private fun test() {
        val config = readForm() ?: return
        testResult.text = "Testing…"
        testResult.setTextColor(Color.parseColor("#5F6368"))
        scope.launch {
            val started = System.nanoTime()
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    OpenAiCompatibleClient(config).use { it.chat(listOf(Msg.user("Reply with the single word OK.")), emptyList()) }
                }
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            outcome.onSuccess {
                testResult.text = "Connected in $ms ms. Model said: ${it.content?.take(60) ?: "(no text)"}"
                testResult.setTextColor(Color.parseColor("#1E7B34"))
            }.onFailure {
                val reason = (it as? LlmException)?.let { e -> "${e.code}: ${e.message}" } ?: (it.message ?: "failed")
                testResult.text = "Failed: $reason"
                testResult.setTextColor(Color.parseColor("#B3261E"))
            }
        }
    }

    private fun field(hint: String, value: String?) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#5F6368"))
        setPadding(0, dp(2), 0, dp(6))
        visibility = View.VISIBLE
    }
}
