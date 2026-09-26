package ga.egarcia.harness.voice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val model: WhisperModel, val done: Long, val total: Long) : DownloadState
    data class Failed(val model: WhisperModel, val message: String) : DownloadState
    data class Finished(val model: WhisperModel) : DownloadState
}

/**
 * One download at a time, owned by the app process so it keeps going when the settings screen is closed.
 * A cancelled or interrupted download leaves a partial file that the next attempt resumes.
 */
object ModelDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    @Synchronized
    fun start(context: Context, model: WhisperModel) {
        if (job?.isActive == true) return
        val dir = ModelStore.dir(context)
        _state.value = DownloadState.Running(model, ModelStore.partialBytes(context, model), model.bytes)
        job = scope.launch {
            try {
                ModelDownloader().download(model, dir) { done, total -> _state.value = DownloadState.Running(model, done, total) }
                _state.value = DownloadState.Finished(model)
            } catch (e: CancellationException) {
                _state.value = DownloadState.Idle
                throw e
            } catch (e: ModelDownloadException) {
                _state.value = DownloadState.Failed(model, e.message.orEmpty())
            } catch (e: Exception) {
                _state.value = DownloadState.Failed(model, "Download failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun acknowledge() {
        if (_state.value !is DownloadState.Running) _state.value = DownloadState.Idle
    }
}
