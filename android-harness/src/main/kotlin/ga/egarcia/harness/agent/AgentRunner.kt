package ga.egarcia.harness.agent

import android.content.Context
import android.util.Log
import ga.egarcia.harness.HarnessAccessibilityService
import ga.egarcia.harness.llm.LlmConfigStore
import ga.egarcia.harness.llm.OpenAiCompatibleClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/**
 * Runs at most one agent task at a time inside the accessibility service's process.
 * Phase 2: started from adb; the chat screen and foreground service come in phase 3.
 */
object AgentRunner {
    private const val TAG = "HarnessAgent"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    @Synchronized
    fun start(context: Context, task: String) {
        if (isRunning) {
            Log.w(TAG, "A task is already running; stop it first")
            return
        }
        val automation = HarnessAccessibilityService.automation
        if (automation == null) {
            Log.w(TAG, "Accessibility service is not connected")
            return
        }
        val config = LlmConfigStore.load(context)
        if (config == null) {
            Log.w(TAG, "LLM is not configured (see AdbConfigReceiver)")
            return
        }
        val audit = File(context.applicationContext.filesDir, "agent-audit.log")
        job = scope.launch {
            val client = OpenAiCompatibleClient(config)
            try {
                val loop = AgentLoop(automation, client, AgentConfig(vision = config.vision)) { event ->
                    Log.i(TAG, event.summary())
                    // TODO(verify): phase 4 replaces this with the structured audit log; tool args may contain typed text.
                    runCatching { audit.appendText("${Instant.now()} ${event.summary()}\n") }
                }
                Log.i(TAG, "RESULT: ${loop.run(task)}")
            } catch (e: CancellationException) {
                Log.i(TAG, "RESULT: stopped by user")
                throw e
            } finally {
                client.close()
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
