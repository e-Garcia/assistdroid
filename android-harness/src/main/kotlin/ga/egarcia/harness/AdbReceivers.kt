package ga.egarcia.harness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ga.egarcia.harness.agent.AgentRunner
import ga.egarcia.harness.llm.LlmConfig
import ga.egarcia.harness.llm.LlmConfigStore
import ga.egarcia.harness.inbox.InboxStore
import ga.egarcia.harness.playbook.PlaybookStore
import ga.egarcia.harness.policy.PolicyConfig
import ga.egarcia.harness.policy.PolicyStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Developer entry points, callable only from `adb shell` because the manifest gates them on the DUMP
 * permission (same approach as android-remote-control-mcp). Phase 3 adds the real chat UI.
 *
 *   adb shell am broadcast -a ga.egarcia.harness.ADB_CONFIGURE -n ga.egarcia.harness/.AdbConfigReceiver \
 *       --es base_url http://host:8000/v1 --es model NAME --es api_key KEY [--ez vision true] [--ez thinking false] \
 *       [--ei max_tokens 1024] [--es extra_body '{"repetition_penalty":1.1}']
 *   adb shell am broadcast -a ga.egarcia.harness.RUN_TASK -n ga.egarcia.harness/.AdbAgentReceiver --es task "..."
 *   adb shell am broadcast -a ga.egarcia.harness.STOP_TASK -n ga.egarcia.harness/.AdbAgentReceiver
 */
class AdbConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val old = LlmConfigStore.load(context)
        val baseUrl = intent.getStringExtra("base_url") ?: old?.baseUrl
        val model = intent.getStringExtra("model") ?: old?.model
        val apiKey = intent.getStringExtra("api_key") ?: old?.apiKey
        if (baseUrl == null || model == null || apiKey == null) {
            Log.w(TAG, "base_url, model and api_key are required the first time")
            return
        }
        val extra: JsonObject = intent.getStringExtra("extra_body")
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: old?.extraBody
            ?: JsonObject(emptyMap())
        val config = LlmConfig(
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            vision = if (intent.hasExtra("vision")) intent.getBooleanExtra("vision", true) else old?.vision ?: true,
            thinking = if (intent.hasExtra("thinking")) intent.getBooleanExtra("thinking", false) else old?.thinking ?: false,
            maxTokens = if (intent.hasExtra("max_tokens")) intent.getIntExtra("max_tokens", 1024) else old?.maxTokens ?: 1024,
            temperature = old?.temperature ?: 0.0,
            extraBody = extra,
        )
        LlmConfigStore.save(context, config)
        Log.i(TAG, "LLM configured: $config") // toString redacts the key
    }

    private companion object {
        const val TAG = "HarnessAgent"
    }
}

/**
 *   adb shell am broadcast -a ga.egarcia.harness.ADB_POLICY -n ga.egarcia.harness/.AdbPolicyReceiver \
 *       [--es denied 'com.example.one,keyword'] [--ez gate true] [--es notif_apps 'com.pkg.one,com.pkg.two'] [--ez notif_clear true] [--es playbooks 'pkg: hint\\n  more'] [--ez overlay true]
 */
class AdbPolicyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val old = PolicyStore.load(context)
        val config = PolicyConfig(
            customDenied = intent.getStringExtra("denied")?.let(PolicyStore::parseEntries) ?: old.customDenied,
            gateEnabled = if (intent.hasExtra("gate")) intent.getBooleanExtra("gate", true) else old.gateEnabled,
        )
        PolicyStore.save(context, config)
        intent.getStringExtra("playbooks")?.let { PlaybookStore.saveText(context, it.replace("\\n", "\n")) }
        if (intent.hasExtra("overlay")) UiPrefs.setOverlayEnabled(context, intent.getBooleanExtra("overlay", false))
        intent.getStringExtra("notif_apps")?.let { InboxStore.setWatched(context, PolicyStore.parseEntries(it).toSet()) }
        if (intent.getBooleanExtra("notif_clear", false)) InboxStore.repository(context).clear()
        Log.i("HarnessAgent", "Policy: denied=${config.customDenied} gate=${config.gateEnabled} notes=${PlaybookStore.load(context).size} overlay=${UiPrefs.overlayEnabled(context)}")
    }
}

class AdbAgentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "ga.egarcia.harness.RUN_TASK" -> {
                val task = intent.getStringExtra("task")?.trim()
                if (task.isNullOrEmpty()) {
                    Log.w("HarnessAgent", "RUN_TASK needs --es task \"...\"")
                } else {
                    Log.i("HarnessAgent", "start: ${AgentRunner.start(context.applicationContext, task)}")
                }
            }
            "ga.egarcia.harness.STOP_TASK" -> AgentRunner.stop()
        }
    }
}

/**
 * Writes a diagnostics report to the app's external files folder and logs its path.
 *   adb shell am broadcast -a ga.egarcia.harness.ADB_DIAG -n ga.egarcia.harness/.AdbDiagReceiver [--ez detailed true] [--ez clear true]
 *   adb pull /sdcard/Android/data/ga.egarcia.harness/files/diagnostics/
 */
class AdbDiagReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("detailed")) ga.egarcia.harness.diag.DevPrefs.setDetailed(context, intent.getBooleanExtra("detailed", false))
        if (intent.getBooleanExtra("clear", false)) {
            ga.egarcia.harness.diag.DiagStore.clear(context)
            Log.i("HarnessDiag", "diagnostics cleared")
            return
        }
        val pending = goAsync()
        Thread {
            try {
                val file = ga.egarcia.harness.diag.AndroidDiagnostics.writeReport(context)
                Log.i("HarnessDiag", "report written: ${file.absolutePath} (${file.length()} bytes)")
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/**
 * Developer checks for voice input, without a microphone. adb only (DUMP permission).
 *   adb shell am broadcast -a ga.egarcia.harness.ADB_VOICE -n ga.egarcia.harness/.AdbVoiceReceiver --es mode whisper|google|off --es model base.en --es language en
 *   ... --ez download true            start the model download (progress: adb logcat -s HarnessVoice)
 *   ... --es wav <file in the app's external files dir>   transcribe a 16-bit WAV with the chosen model and log the result
 */
class AdbVoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val voice = ga.egarcia.harness.voice.VoicePrefs
        intent.getStringExtra("mode")?.let { m -> voice.setMode(context, runCatching { ga.egarcia.harness.voice.VoiceMode.valueOf(m.uppercase()) }.getOrDefault(ga.egarcia.harness.voice.VoiceMode.OFF)) }
        intent.getStringExtra("model")?.let { voice.setModel(context, ga.egarcia.harness.voice.WhisperModels.byId(it)) }
        intent.getStringExtra("language")?.let { voice.setLanguage(context, it) }
        val model = voice.model(context)
        if (intent.getBooleanExtra("download", false)) {
            ga.egarcia.harness.voice.ModelDownloads.start(context, model)
            Log.i("HarnessVoice", "download of ${model.id} started")
        }
        val wav = intent.getStringExtra("wav") ?: return
        val pending = goAsync()
        Thread {
            try {
                val file = java.io.File(context.getExternalFilesDir(null), wav)
                val samples = ga.egarcia.harness.voice.Wav.read(file)
                val started = System.nanoTime()
                val text = ga.egarcia.harness.voice.WhisperEngine.transcribe(
                    ga.egarcia.harness.voice.ModelStore.file(context, model), samples,
                    ga.egarcia.harness.voice.VoiceLanguages.effective(model, voice.language(context)),
                )
                Log.i("HarnessVoice", "TRANSCRIPT (${model.id}, ${samples.size / 16} ms audio, ${(System.nanoTime() - started) / 1_000_000} ms total incl. load): $text")
            } catch (e: Exception) {
                Log.w("HarnessVoice", "transcription failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
