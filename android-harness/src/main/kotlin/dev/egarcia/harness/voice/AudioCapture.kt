package ga.egarcia.harness.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

sealed interface Recording {
    data class Audio(val samples: FloatArray, val millis: Long) : Recording
    data object NoSpeech : Recording

    /** Android delivered pure silence: microphone access is blocked (another app holds it, or the app is not allowed to record right now). */
    data object Silenced : Recording
    data class Failed(val message: String) : Recording
}

/** Records 16 kHz mono speech until the speaker stops, [shouldStop] turns true, or 30 seconds pass. Blocking. */
class AudioCapture(private val detector: EndpointDetector = EndpointDetector()) {

    @SuppressLint("MissingPermission") // checked by the caller before recording starts
    fun record(shouldStop: () -> Boolean): Recording {
        val chunk = SAMPLE_RATE / 10 // 100 ms
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return Recording.Failed("This phone cannot record 16 kHz audio.")
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, chunk * 2 * 4))
        } catch (e: SecurityException) {
            return Recording.Failed("Microphone permission is missing.")
        } catch (e: IllegalArgumentException) {
            return Recording.Failed("The microphone could not be opened.")
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return Recording.Failed("The microphone is busy or unavailable.")
        }
        val all = ShortArray(SAMPLE_RATE * MAX_SECONDS)
        var total = 0
        var silentChunks = 0
        try {
            recorder.startRecording()
            val buffer = ShortArray(chunk)
            while (total + chunk <= all.size) {
                val n = recorder.read(buffer, 0, chunk)
                if (n < 0) return Recording.Failed("The microphone stopped delivering audio.")
                if (n == 0) continue
                System.arraycopy(buffer, 0, all, total, n)
                total += n
                silentChunks = if (Pcm.isDigitalSilence(buffer, n)) silentChunks + 1 else 0
                if (silentChunks >= SILENCED_AFTER_CHUNKS && total <= silentChunks * chunk) return Recording.Silenced
                val verdict = detector.feed(Pcm.rms(buffer, n))
                if (verdict == EndpointDetector.Verdict.NO_SPEECH) return Recording.NoSpeech
                if (verdict != EndpointDetector.Verdict.CONTINUE) break
                if (shouldStop()) break
            }
        } catch (e: IllegalStateException) {
            return Recording.Failed("The microphone could not be started.")
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        if (!detector.heardSpeech) return Recording.NoSpeech
        return Recording.Audio(Pcm.toFloat(all, total), total * 1000L / SAMPLE_RATE)
    }

    private companion object {
        const val MAX_SECONDS = 30
        const val SILENCED_AFTER_CHUNKS = 10 // one second of exact zeros from the start
    }
}
