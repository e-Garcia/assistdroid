package ga.egarcia.harness.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import ga.egarcia.harness.HarnessAccessibilityService
import ga.egarcia.harness.llm.LlmConfigStore
import ga.egarcia.harness.llm.OpenAiCompatibleClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/**
 * Runs at most one agent task at a time inside the accessibility service's process (the run lock)
 * and publishes [state] for the chat screen and the foreground notification.
 */
object AgentRunner {
    private const val TAG = "HarnessAgent"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(RunState())

    val state: StateFlow<RunState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    val isRunning: Boolean get() = _state.value.running

    enum class StartResult { STARTED, BUSY, NO_ACCESSIBILITY, NO_LLM }

    /** [display] is what the chat shows as the user's message when [task] carries extra follow-up context. */
    @Synchronized
    fun start(context: Context, task: String, display: String = task, startDelayMs: Long = 0): StartResult {
        if (isRunning) return StartResult.BUSY
        val automation = HarnessAccessibilityService.automation ?: return StartResult.NO_ACCESSIBILITY
        val config = LlmConfigStore.load(context) ?: return StartResult.NO_LLM
        val app = context.applicationContext
        val audit = File(app.filesDir, "agent-audit.log")

        // Mark running before the service starts, so the service does not see an idle runner and exit.
        _state.update { it.begin(task, display) }
        startForeground(app)
        automation.keepAwake(true)

        job = scope.launch {
            val client = OpenAiCompatibleClient(config)
            try {
                if (startDelayMs > 0) delay(startDelayMs)
                val loop = AgentLoop(automation, client, AgentConfig(vision = config.vision)) { event ->
                    Log.i(TAG, event.summary())
                    _state.update { it.onEvent(event) }
                    // TODO(verify): phase 4 replaces this with the structured audit log; tool args may contain typed text.
                    runCatching { audit.appendText("${Instant.now()} ${event.summary()}\n") }
                }
                Log.i(TAG, "RESULT: ${loop.run(task)}")
            } catch (e: CancellationException) {
                Log.i(TAG, "RESULT: stopped by user")
                _state.update { it.stoppedByUser() }
                throw e
            } finally {
                automation.keepAwake(false)
                client.close()
                _state.update { it.finished() }
            }
        }
        return StartResult.STARTED
    }

    fun stop() {
        job?.cancel()
    }

    /** Clears the chat log (not allowed mid-run). */
    fun clear() {
        _state.update { if (it.running) it else RunState() }
    }

    private fun startForeground(context: Context) {
        try {
            context.startForegroundService(Intent(context, AgentForegroundService::class.java))
        } catch (e: RuntimeException) {
            // The accessibility service keeps the process alive anyway; the notification is a convenience.
            Log.w(TAG, "Could not start the foreground service: ${e.message}")
        }
    }
}
