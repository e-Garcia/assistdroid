package ga.egarcia.harness.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import java.io.File

enum class VoiceMode { OFF, GOOGLE, WHISPER }

/** Voice input is optional and off by default. Nothing is recorded, and no model is downloaded, until the user turns it on. */
object VoicePrefs {
    private const val PREFS = "harness_voice"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): VoiceMode = runCatching { VoiceMode.valueOf(prefs(context).getString("mode", "OFF").orEmpty()) }.getOrDefault(VoiceMode.OFF)

    fun setMode(context: Context, mode: VoiceMode) = prefs(context).edit().putString("mode", mode.name).apply()

    fun model(context: Context): WhisperModel = WhisperModels.byId(prefs(context).getString("model", null))

    fun setModel(context: Context, model: WhisperModel) = prefs(context).edit().putString("model", model.id).apply()

    fun language(context: Context): String = prefs(context).getString("language", "auto").orEmpty()

    fun setLanguage(context: Context, language: String) = prefs(context).edit().putString("language", language).apply()

    /** Ask Google's recognizer to use its on-device model (no audio leaves the phone) when the language pack is installed. */
    fun preferOffline(context: Context): Boolean = prefs(context).getBoolean("prefer_offline", false)

    fun setPreferOffline(context: Context, on: Boolean) = prefs(context).edit().putBoolean("prefer_offline", on).apply()
}

/** Where downloaded models live: app-private storage, removed with the app. */
object ModelStore {
    fun dir(context: Context) = File(context.applicationContext.filesDir, "whisper").also { it.mkdirs() }

    fun file(context: Context, model: WhisperModel) = File(dir(context), model.file)

    fun isInstalled(context: Context, model: WhisperModel) = file(context, model).let { it.isFile && it.length() == model.bytes }

    fun partialBytes(context: Context, model: WhisperModel) = File(dir(context), model.file + ".part").takeIf { it.isFile }?.length() ?: 0L

    fun delete(context: Context, model: WhisperModel) {
        WhisperEngine.release()
        file(context, model).delete()
        File(dir(context), model.file + ".part").delete()
    }

    fun installedBytes(context: Context) = dir(context).listFiles()?.sumOf { it.length() } ?: 0L
}

object Mic {
    fun permitted(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Why the mic button cannot work right now, or null when it is ready. */
    fun problem(context: Context): String? {
        val app = context.applicationContext
        return when (VoicePrefs.mode(app)) {
            VoiceMode.OFF -> "Voice input is off. Turn it on in Settings > Voice input."
            VoiceMode.GOOGLE ->
                if (!SpeechRecognizer.isRecognitionAvailable(app)) "This phone has no speech recognition service. Use Local Whisper instead (Settings > Voice input)." else null
            VoiceMode.WHISPER ->
                WhisperEngine.unsupportedReason()
                    ?: if (!ModelStore.isInstalled(app, VoicePrefs.model(app))) "Download the speech model first (Settings > Voice input)." else null
        }
    }
}
