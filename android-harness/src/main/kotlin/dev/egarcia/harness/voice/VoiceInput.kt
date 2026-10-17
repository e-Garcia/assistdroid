package ga.egarcia.harness.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import ga.egarcia.harness.policy.AuditStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface VoiceState {
    data object Idle : VoiceState
    data object Listening : VoiceState
    data object Transcribing : VoiceState
    data class Failed(val message: String) : VoiceState
}

/**
 * One mic button's worth of dictation: tap to start, tap again (or stop talking) to finish. The text goes to the
 * task box for the user to check; it never runs a task by itself. Uses Google's recognizer or Local Whisper,
 * whichever is chosen in Settings > Voice input. All [Listener] calls happen on the main thread.
 */
class VoiceInput(context: Context, private val listener: Listener) {

    interface Listener {
        fun onState(state: VoiceState)

        /** [final] is false for live partial text (Google only). */
        fun onText(text: String, final: Boolean)
        fun onNeedPermission()
    }

    private val app = context.applicationContext
    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recognizer: SpeechRecognizer? = null
    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    var state: VoiceState = VoiceState.Idle
        private set

    private fun set(next: VoiceState) {
        ui.post { state = next; listener.onState(next) }
    }

    fun toggle() {
        when (state) {
            VoiceState.Listening -> stopListening()
            VoiceState.Transcribing -> Unit
            else -> begin()
        }
    }

    fun cancel() {
        stopRequested = true
        job?.cancel()
        ui.post {
            recognizer?.cancel()
            state = VoiceState.Idle
            listener.onState(VoiceState.Idle)
        }
    }

    fun destroy() {
        cancel()
        ui.post { recognizer?.destroy(); recognizer = null }
        scope.cancel()
    }

    private fun begin() {
        val mode = VoicePrefs.mode(app)
        Mic.problem(app)?.let { set(VoiceState.Failed(it)); return }
        if (!Mic.permitted(app)) { listener.onNeedPermission(); return }
        stopRequested = false
        when (mode) {
            VoiceMode.GOOGLE -> ui.post { startGoogle() }
            VoiceMode.WHISPER -> startWhisper()
            VoiceMode.OFF -> Unit
        }
    }

    private fun stopListening() {
        stopRequested = true
        ui.post { recognizer?.stopListening() }
    }

    // --- Local Whisper -------------------------------------------------------------------------------

    private fun startWhisper() {
        val model = VoicePrefs.model(app)
        val language = VoiceLanguages.effective(model, VoicePrefs.language(app))
        set(VoiceState.Listening)
        job = scope.launch {
            when (val recording = AudioCapture().record { stopRequested }) {
                is Recording.Audio -> {
                    set(VoiceState.Transcribing)
                    val started = System.nanoTime()
                    try {
                        val text = WhisperEngine.transcribe(ModelStore.file(app, model), recording.samples, language)
                        audit("whisper", recording.millis, text.length, (System.nanoTime() - started) / 1_000_000)
                        ui.post { if (text.isNotBlank()) listener.onText(text, true) }
                        set(if (text.isBlank()) VoiceState.Failed("Could not make out any words. Try again, closer to the microphone.") else VoiceState.Idle)
                    } catch (e: IllegalStateException) {
                        set(VoiceState.Failed(e.message.orEmpty()))
                    }
                }
                Recording.NoSpeech -> set(VoiceState.Failed("I did not hear anything. Tap the microphone and speak."))
                Recording.Silenced -> set(VoiceState.Failed("Android is not giving this app the microphone right now (another app may be using it). Try from the app itself."))
                is Recording.Failed -> set(VoiceState.Failed(recording.message))
            }
        }
    }

    // --- Google speech -------------------------------------------------------------------------------

    private fun startGoogle() {
        recognizer?.destroy()
        val offline = VoicePrefs.preferOffline(app)
        val onDevice = offline && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        val rec = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(app) else SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = rec
        var started = 0L
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { started = System.nanoTime(); state = VoiceState.Listening; listener.onState(state) }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { state = VoiceState.Transcribing; listener.onState(state) }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { listener.onText(it, false) }
            }
            override fun onResults(results: android.os.Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                audit(if (onDevice) "google_on_device" else "google", 0, text.length, (System.nanoTime() - started) / 1_000_000)
                if (text.isNotBlank()) listener.onText(text, true)
                state = VoiceState.Idle
                listener.onState(state)
            }
            override fun onError(error: Int) {
                state = VoiceState.Failed(googleError(error))
                listener.onState(state)
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
        state = VoiceState.Listening
        listener.onState(state)
        rec.startListening(intent)
    }

    private fun googleError(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I did not catch that. Tap the microphone and try again."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Google speech needs a network connection (or turn on on-device recognition in Voice settings)."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech service is busy. Try again in a moment."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be used."
        12, 13 -> "The language pack for on-device recognition is not installed."
        else -> "Speech recognition failed (code $code)."
    }

    /** Records that a dictation happened: how, how long, how many characters. Never the words. */
    private fun audit(engine: String, audioMs: Long, chars: Int, processingMs: Long) {
        runCatching {
            AuditStore.get(app).log("voice_input", mapOf("engine" to engine, "audio_ms" to audioMs.toString(), "chars" to chars.toString(), "processing_ms" to processingMs.toString()))
        }
        Log.i("HarnessVoice", "$engine: audio ${audioMs} ms, $chars chars, processed in $processingMs ms")
    }
}
