package dev.egarcia.harness

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import dev.egarcia.harness.voice.DownloadState
import dev.egarcia.harness.voice.Mic
import dev.egarcia.harness.voice.MicPermissionActivity
import dev.egarcia.harness.voice.ModelDownloads
import dev.egarcia.harness.voice.ModelStore
import dev.egarcia.harness.voice.VoiceLanguages
import dev.egarcia.harness.voice.VoiceMode
import dev.egarcia.harness.voice.VoicePrefs
import dev.egarcia.harness.voice.WhisperEngine
import dev.egarcia.harness.voice.WhisperModel
import dev.egarcia.harness.voice.WhisperModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Optional voice input. Off by default. Google speech uses Android's recognizer; Local Whisper runs a small model on
 * this phone, downloaded from here (never bundled in the app) after the user chooses it.
 */
class VoiceSettingsActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var modeGroup: RadioGroup
    private lateinit var status: TextView
    private lateinit var permission: TextView
    private lateinit var allowMic: Button
    private lateinit var googleBox: LinearLayout
    private lateinit var whisperBox: LinearLayout
    private lateinit var offline: Switch
    private lateinit var modelGroup: RadioGroup
    private lateinit var languageRow: LinearLayout
    private lateinit var language: Spinner
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var download: Button
    private lateinit var cancel: Button
    private lateinit var delete: Button
    private lateinit var storage: TextView
    private val modelButtons = mutableMapOf<String, RadioButton>()
    private var building = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(8)) }
        permission = TextView(this).apply { textSize = 13f }

        allowMic = Button(this).apply {
            text = "Allow microphone"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@VoiceSettingsActivity, MicPermissionActivity::class.java)) }
        }
        modeGroup = RadioGroup(this).apply {
            listOf(
                VoiceMode.OFF to "Off",
                VoiceMode.GOOGLE to "Google speech (Android's recognizer)",
                VoiceMode.WHISPER to "Local Whisper (runs on this phone)",
            ).forEach { (mode, label) -> addView(RadioButton(this@VoiceSettingsActivity).apply { id = View.generateViewId(); tag = mode; text = label }) }
            setOnCheckedChangeListener { group, checkedId ->
                if (building) return@setOnCheckedChangeListener
                val mode = group.findViewById<View>(checkedId).tag as VoiceMode
                VoicePrefs.setMode(this@VoiceSettingsActivity, mode)
                if (mode != VoiceMode.OFF && !Mic.permitted(this@VoiceSettingsActivity)) startActivity(Intent(this@VoiceSettingsActivity, MicPermissionActivity::class.java))
                if (mode == VoiceMode.WHISPER) offerDownload(VoicePrefs.model(this@VoiceSettingsActivity))
                refresh()
            }
        }

        offline = Switch(this).apply {
            text = "Prefer on-device recognition"
            isChecked = VoicePrefs.preferOffline(this@VoiceSettingsActivity)
            setOnCheckedChangeListener { _, on -> VoicePrefs.setPreferOffline(this@VoiceSettingsActivity, on) }
        }
        googleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint("Uses the speech service that comes with your phone. By default your audio is sent to Google for recognition. On-device recognition keeps it on the phone, but needs the language pack downloaded in Google's settings (Settings > Google > All services > Speech services)."))
            addView(offline)
        }

        modelGroup = RadioGroup(this).apply {
            WhisperModels.all.forEach { model ->
                val button = RadioButton(this@VoiceSettingsActivity).apply { id = View.generateViewId(); tag = model }
                modelButtons[model.id] = button
                addView(button)
            }
            setOnCheckedChangeListener { group, checkedId ->
                if (building) return@setOnCheckedChangeListener
                VoicePrefs.setModel(this@VoiceSettingsActivity, group.findViewById<View>(checkedId).tag as WhisperModel)
                refresh()
            }
        }
        language = Spinner(this).apply {
            adapter = ArrayAdapter(this@VoiceSettingsActivity, android.R.layout.simple_spinner_dropdown_item, VoiceLanguages.choices.map { it.second })
            setSelection(VoiceLanguages.choices.indexOfFirst { it.first == VoicePrefs.language(this@VoiceSettingsActivity) }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!building) VoicePrefs.setLanguage(this@VoiceSettingsActivity, VoiceLanguages.choices[position].first)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        languageRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(caption("Spoken language"))
            addView(language)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        progressText = TextView(this).apply { textSize = 13f }
        download = Button(this).apply { text = "Download model"; isAllCaps = false; setOnClickListener { offerDownload(VoicePrefs.model(this@VoiceSettingsActivity)) } }
        cancel = Button(this).apply { text = "Cancel download"; isAllCaps = false; setOnClickListener { ModelDownloads.cancel() } }
        delete = Button(this).apply {
            text = "Delete this model"
            isAllCaps = false
            setOnClickListener { ModelStore.delete(this@VoiceSettingsActivity, VoicePrefs.model(this@VoiceSettingsActivity)); ModelDownloads.acknowledge(); refresh() }
        }
        storage = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#5F6368")) }
        whisperBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint("Speech is recognised on this phone: nothing is sent anywhere. The model is not part of the app. It is downloaded once, from the whisper.cpp model repository, checked against a fixed SHA-256 and kept in this app's private storage until you delete it. Loading it uses some memory only while you dictate."))
            addView(modelGroup)
            addView(languageRow)
            addView(progress)
            addView(progressText)
            addView(download)
            addView(cancel)
            addView(delete)
            addView(storage)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            listOf(
                TextView(this@VoiceSettingsActivity).apply { text = "Voice input"; textSize = 22f; setTextColor(Color.parseColor("#1F1F1F")); setTypeface(typeface, Typeface.BOLD) },
                hint("Adds a microphone button above Run in the chat, and next to Close in the floating prompt. Your words appear in the task box so you can check them before pressing Run. Off by default."),
                modeGroup,
                status,
                permission,
                allowMic,
                googleBox,
                whisperBox,
            ).forEach(::addView)
        }
        val frame = LinearLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(ScrollView(this@VoiceSettingsActivity).apply { addView(content) })
        }
        applySystemInsets(frame)
        setContentView(frame)
        building = false
        scope.launch { ModelDownloads.state.collect { refresh() } }
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
        building = true
        val mode = VoicePrefs.mode(this)
        val model = VoicePrefs.model(this)
        (0 until modeGroup.childCount).map { modeGroup.getChildAt(it) as RadioButton }.forEach { it.isChecked = it.tag == mode }
        googleBox.visibility = if (mode == VoiceMode.GOOGLE) View.VISIBLE else View.GONE
        whisperBox.visibility = if (mode == VoiceMode.WHISPER) View.VISIBLE else View.GONE

        val running = ModelDownloads.state.value as? DownloadState.Running
        WhisperModels.all.forEach { m ->
            modelButtons.getValue(m.id).apply {
                isChecked = m.id == model.id
                isEnabled = running == null
                val state = when {
                    ModelStore.isInstalled(this@VoiceSettingsActivity, m) -> "downloaded"
                    ModelStore.partialBytes(this@VoiceSettingsActivity, m) > 0 -> "partly downloaded"
                    else -> "not downloaded"
                }
                text = "${m.label} · ${m.megabytes} MB · $state\n${m.note}"
            }
        }
        languageRow.visibility = if (model.englishOnly) View.GONE else View.VISIBLE
        val installed = ModelStore.isInstalled(this, model)
        download.visibility = if (!installed && running == null) View.VISIBLE else View.GONE
        cancel.visibility = if (running != null) View.VISIBLE else View.GONE
        delete.visibility = if (installed && running == null) View.VISIBLE else View.GONE
        val state = ModelDownloads.state.value
        progress.visibility = if (running != null) View.VISIBLE else View.GONE
        progressText.setTextColor(Color.parseColor(if (state is DownloadState.Failed) "#B3261E" else "#5F6368"))
        progressText.text = when (state) {
            is DownloadState.Running -> {
                progress.progress = (state.done * 1000 / state.total.coerceAtLeast(1)).toInt()
                "Downloading ${state.model.label}: ${state.done / 1_000_000} of ${state.total / 1_000_000} MB"
            }
            is DownloadState.Failed -> state.message
            is DownloadState.Finished -> "${state.model.label} is ready."
            DownloadState.Idle -> ""
        }
        storage.text = "Speech models on this phone: ${ModelStore.installedBytes(this) / 1_000_000} MB"

        val problem = if (mode == VoiceMode.OFF) null else Mic.problem(this)
        status.text = when {
            mode == VoiceMode.OFF -> "Voice input is off."
            problem != null -> problem
            else -> "Ready. Use the microphone button in the chat or the floating prompt."
        }
        status.setTextColor(Color.parseColor(if (mode == VoiceMode.OFF) "#5F6368" else if (problem != null) "#B26A00" else "#1E7B34"))
        permission.visibility = if (mode == VoiceMode.OFF) View.GONE else View.VISIBLE
        permission.text = if (Mic.permitted(this)) "Microphone permission: allowed" else "Microphone permission: not allowed yet"
        permission.setTextColor(Color.parseColor(if (Mic.permitted(this)) "#1E7B34" else "#B26A00"))
        allowMic.visibility = if (mode == VoiceMode.OFF || Mic.permitted(this)) View.GONE else View.VISIBLE
        building = false
    }

    private fun offerDownload(model: WhisperModel) {
        if (ModelStore.isInstalled(this, model) || ModelDownloads.state.value is DownloadState.Running) return
        val metered = getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        AlertDialog.Builder(this)
            .setTitle("Download ${model.label}?")
            .setMessage("${model.megabytes} MB, downloaded once and kept on this phone (not in the app)." +
                if (metered) "\n\nYou are on a metered connection (mobile data); Wi-Fi is better." else "")
            .setPositiveButton("Download") { _, _ -> ModelDownloads.start(this, model); refresh() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun caption(text: String) = TextView(this).apply { this.text = text; textSize = 13f; setPadding(0, dp(10), 0, 0) }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#5F6368"))
        setPadding(0, dp(4), 0, dp(8))
    }
}
