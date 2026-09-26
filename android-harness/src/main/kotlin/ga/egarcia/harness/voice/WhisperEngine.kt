package ga.egarcia.harness.voice

import android.os.Build
import android.util.Log
import java.io.File

/** JNI entry points (see src/main/cpp/jni.c). Only touched through [WhisperEngine]. */
internal object WhisperNative {
    external fun nativeInit(path: String): Long
    external fun nativeFree(ptr: Long)
    external fun nativeTranscribe(ptr: Long, audio: FloatArray, threads: Int, language: String): ByteArray?
}

/**
 * Local Whisper on the phone's CPU. The library is loaded only when first needed, and the model only when the user
 * has downloaded one, so users who never enable the feature pay no memory or start-up cost. One model stays loaded
 * between dictations (loading takes a second or two) and is freed after [IDLE_RELEASE_MS] without use.
 */
object WhisperEngine {
    private const val TAG = "HarnessWhisper"
    private const val IDLE_RELEASE_MS = 90_000L

    private var libraryLoaded = false
    private var ptr = 0L
    private var loadedPath: String? = null
    private var lastUsed = 0L

    fun unsupportedReason(): String? =
        WhisperCpu.unsupportedReason(Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), runCatching { File("/proc/cpuinfo").readText() }.getOrNull())

    /** Returns the transcript. Blocking: call from a background thread. Throws IllegalStateException with a readable message. */
    @Synchronized
    fun transcribe(model: File, samples: FloatArray, language: String): String {
        unsupportedReason()?.let { throw IllegalStateException(it) }
        if (!libraryLoaded) {
            try {
                System.loadLibrary("whisper_jni")
                libraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                throw IllegalStateException("The speech library could not be loaded on this phone.")
            }
        }
        if (ptr == 0L || loadedPath != model.absolutePath) {
            release()
            val started = System.nanoTime()
            ptr = WhisperNative.nativeInit(model.absolutePath)
            if (ptr == 0L) throw IllegalStateException("The speech model could not be loaded. Delete it in Voice settings and download it again.")
            loadedPath = model.absolutePath
            Log.i(TAG, "model loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
        val started = System.nanoTime()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val bytes = WhisperNative.nativeTranscribe(ptr, samples, threads, language)
            ?: throw IllegalStateException("The speech model failed while transcribing.")
        lastUsed = System.currentTimeMillis()
        Log.i(TAG, "transcribed ${samples.size * 1000L / SAMPLE_RATE} ms of audio in ${(System.nanoTime() - started) / 1_000_000} ms, $threads threads")
        return Transcript.clean(String(bytes, Charsets.UTF_8))
    }

    /** Frees the model's memory (a few hundred MB for the larger ones). */
    @Synchronized
    fun release() {
        if (ptr != 0L) WhisperNative.nativeFree(ptr)
        ptr = 0L
        loadedPath = null
    }

    /** Called opportunistically; frees the model if it has been idle for a while. */
    @Synchronized
    fun releaseIfIdle() {
        if (ptr != 0L && System.currentTimeMillis() - lastUsed > IDLE_RELEASE_MS) release()
    }
}
