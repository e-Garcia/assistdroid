package ga.egarcia.harness.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import ga.egarcia.harness.HarnessAccessibilityService
import ga.egarcia.harness.UiPrefs
import ga.egarcia.harness.power.RunWakeLock
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
import ga.egarcia.harness.diag.DevPrefs
import ga.egarcia.harness.diag.DiagStore
import ga.egarcia.harness.diag.SessionRecorder
import ga.egarcia.harness.inbox.AndroidInboxSource
import ga.egarcia.harness.playbook.PlaybookStore
import ga.egarcia.harness.policy.ApprovalUi
import ga.egarcia.harness.policy.AuditStore
import ga.egarcia.harness.policy.PolicyAutomation
import ga.egarcia.harness.policy.PolicyStore

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

    @Volatile
    private var serviceLost = false

    /** The accessibility service went away (the user switched it off): stop with a clear reason. */
    fun serviceLost() {
        if (isRunning) {
            serviceLost = true
            job?.cancel()
        }
    }

    enum class StartResult { STARTED, BUSY, NO_ACCESSIBILITY, NO_LLM, LOCKED }

    /** [display] is what the chat shows as the user's message when [task] carries extra follow-up context. */
    @Synchronized
    fun start(context: Context, task: String, display: String = task, startDelayMs: Long = 0): StartResult {
        if (isRunning) return StartResult.BUSY
        val automation = HarnessAccessibilityService.automation ?: return StartResult.NO_ACCESSIBILITY
        val config = LlmConfigStore.load(context) ?: return StartResult.NO_LLM
        if (automation.isLockedOrAsleep()) return StartResult.LOCKED
        val app = context.applicationContext
        val audit = AuditStore.get(app)
        val policy = PolicyStore.load(app)
        // Every action the agent takes passes through the policy layer (denylist, confirm gate, audit).
        val guarded = PolicyAutomation(automation, { PolicyStore.load(app) }, ApprovalUi.approver(app), audit)

        // Mark running before the service starts, so the service does not see an idle runner and exit.
        _state.update { it.begin(task, display) }
        audit.newRun()
        audit.log("run_started", mapOf("task_chars" to task.length.toString(), "model" to config.model, "gate" to policy.gateEnabled.toString()))
        startForeground(app)
        val dim = UiPrefs.dimLevel(app)
        val wakeLock = RunWakeLock.acquire(app)
        automation.keepAwake(true, dim) { result ->
            Log.i(TAG, "keep awake: $result, wake lock: $wakeLock, dim: ${dim ?: "off"}")
            audit.log("keep_awake", mapOf("screen" to result, "wake_lock" to wakeLock, "dim" to (dim?.let { "${(it * 100).toInt()}%" } ?: "off")))
        }

        val recorder = SessionRecorder({ audit.runId }, { DevPrefs.detailed(app) }, onComplete = { record -> runCatching { DiagStore.sessions(app).append(record) } })

        job = scope.launch {
            val client = OpenAiCompatibleClient(config)
            // The approval dialog must be readable: return to normal brightness while it waits for an answer.
            val brightnessWatcher = launch { ApprovalUi.broker.pending.collect { automation.setDim(if (it != null) null else dim) } }
            try {
                if (startDelayMs > 0) delay(startDelayMs)
                val loop = AgentLoop(
                    guarded, client, AgentConfig(vision = config.vision),
                    onEvent = { event ->
                        Log.i(TAG, event.summary())
                        recorder.onEvent(event)
                        _state.update { it.onEvent(event) }
                        if (event is AgentEvent.Finished) audit.log("run_finished", mapOf("result" to event.result.auditName(), "steps" to event.result.steps.toString()))
                    },
                    hints = { pkg -> PlaybookStore.load(app).hintFor(pkg) },
                    inbox = AndroidInboxSource(app, audit),
                )
                Log.i(TAG, "RESULT: ${loop.run(task)}")
            } catch (e: CancellationException) {
                val lost = serviceLost
                serviceLost = false
                Log.i(TAG, if (lost) "RESULT: stopped, the accessibility service was turned off" else "RESULT: stopped by user")
                recorder.aborted(if (lost) "service_off" else "stopped_by_user")
                audit.log("run_stopped", if (lost) mapOf("reason" to "service_off") else emptyMap())
                _state.update { if (lost) it.stoppedWith("The accessibility service was turned off, so I stopped.") else it.stoppedByUser() }
                throw e
            } catch (e: Exception) {
                recorder.aborted("crash: ${e.javaClass.simpleName}")
                throw e
            } finally {
                brightnessWatcher.cancel()
                RunWakeLock.release()
                automation.keepAwake(false)
                audit.endRun()
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

    private fun AgentResult.auditName() = when (this) {
        is AgentResult.Done -> "done"
        is AgentResult.NeedsUser -> "needs_user"
        is AgentResult.Stopped -> "stopped:$reason".take(60)
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
