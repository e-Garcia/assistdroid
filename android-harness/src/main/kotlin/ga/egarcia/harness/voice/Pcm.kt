package ga.egarcia.harness.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000

/** Decides when the speaker has finished, from the loudness of consecutive audio chunks (energy based, no model). */
class EndpointDetector(
    private val chunkMs: Int = 100,
    private val trailingSilenceMs: Int = 1_200,
    private val noSpeechTimeoutMs: Int = 8_000,
    private val maxMs: Int = 30_000,
) {
    enum class Verdict { CONTINUE, SPEECH_ENDED, NO_SPEECH, TOO_LONG }

    private var elapsed = 0
    private var silentRun = 0
    private var noiseFloor = 0f
    private var chunks = 0
    var heardSpeech = false
        private set

    fun feed(rms: Float): Verdict {
        elapsed += chunkMs
        // The first chunks calibrate the room noise; speech is clearly louder than it.
        if (chunks < CALIBRATION_CHUNKS) noiseFloor = max(noiseFloor, rms).also { chunks++ }
        val threshold = max(MIN_SPEECH_RMS, noiseFloor * 2.5f)
        if (rms >= threshold && chunks >= CALIBRATION_CHUNKS) {
            heardSpeech = true
            silentRun = 0
        } else if (chunks >= CALIBRATION_CHUNKS || heardSpeech) {
            silentRun += chunkMs
        }
        return when {
            elapsed >= maxMs -> Verdict.TOO_LONG
            heardSpeech && silentRun >= trailingSilenceMs -> Verdict.SPEECH_ENDED
            !heardSpeech && elapsed >= noSpeechTimeoutMs -> Verdict.NO_SPEECH
            else -> Verdict.CONTINUE
        }
    }

    private companion object {
        const val CALIBRATION_CHUNKS = 2
        const val MIN_SPEECH_RMS = 0.012f
    }
}

object Pcm {
    fun rms(samples: ShortArray, count: Int = samples.size): Float {
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / count).toFloat()
    }

    fun toFloat(samples: ShortArray, count: Int = samples.size) = FloatArray(count) { samples[it] / 32768f }

    /** True if the recording is exactly silent, which is what Android delivers when it blocks microphone access. */
    fun isDigitalSilence(samples: ShortArray, count: Int = samples.size): Boolean {
        for (i in 0 until count) if (samples[i].toInt() != 0) return false
        return true
    }

    fun peak(samples: FloatArray): Float = samples.maxOfOrNull { abs(it) } ?: 0f
}

/** Minimal WAV reader (PCM 16-bit, mono or stereo, any rate) that returns 16 kHz mono floats. Used by tests and the adb check. */
object Wav {
    fun read(file: File): FloatArray = parse(file.readBytes())

    fun parse(bytes: ByteArray): FloatArray {
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Not a WAV file" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1
        var rate = SAMPLE_RATE
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            if (id == "fmt ") {
                require(buf.getShort(pos + 8).toInt() == 1) { "Only PCM WAV is supported" }
                channels = buf.getShort(pos + 10).toInt()
                rate = buf.getInt(pos + 12)
                bits = buf.getShort(pos + 22).toInt()
            } else if (id == "data") {
                require(bits == 16) { "Only 16-bit WAV is supported" }
                val end = minOf(bytes.size, pos + 8 + size)
                val frames = (end - pos - 8) / (2 * channels)
                val mono = FloatArray(frames) { f ->
                    var sum = 0f
                    for (c in 0 until channels) sum += buf.getShort(pos + 8 + (f * channels + c) * 2) / 32768f
                    sum / channels
                }
                return resample(mono, rate)
            }
            pos += 8 + size + (size and 1)
        }
        throw IllegalArgumentException("No audio data in the WAV file")
    }

    /** Linear resampling; good enough for speech. */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int = SAMPLE_RATE): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val outLength = (input.size.toLong() * toRate / fromRate).toInt()
        return FloatArray(outLength) { i ->
            val pos = i.toDouble() * fromRate / toRate
            val i0 = pos.toInt().coerceAtMost(input.lastIndex)
            val i1 = (i0 + 1).coerceAtMost(input.lastIndex)
            val frac = (pos - i0).toFloat()
            input[i0] * (1 - frac) + input[i1] * frac
        }
    }
}

/** Cleans Whisper output: drops sound annotations such as [BLANK_AUDIO] or (music) and tidies spaces. */
object Transcript {
    private val ANNOTATION = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*""")

    fun clean(raw: String): String = raw.replace(ANNOTATION, " ").replace(Regex("\\s+"), " ").trim()
}

/** Whether this phone can run the Local Whisper library, decided from the ABI and CPU flags before loading it. */
object WhisperCpu {
    /** Null when supported, otherwise a reason to show the user. */
    fun unsupportedReason(abi: String, cpuInfo: String?): String? = when (abi) {
        "x86_64" -> null
        "arm64-v8a" -> {
            val info = cpuInfo.orEmpty()
            if (info.contains("fphp") && info.contains("asimddp")) null
            else "This phone's processor lacks half-precision or dot-product instructions that Local Whisper needs. Use Google speech instead."
        }
        else -> "Local Whisper supports 64-bit ARM phones only."
    }
}
