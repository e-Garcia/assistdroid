package dev.egarcia.harness.agent

import android.content.Context
import android.util.Log
import dev.egarcia.harness.Automation
import dev.egarcia.harness.HarnessAccessibilityService
import dev.egarcia.harness.Snapshot
import dev.egarcia.harness.llm.LlmConfigStore
import dev.egarcia.harness.llm.OpenAiCompatibleClient
import dev.egarcia.harness.playbook.PlaybookStore
import dev.egarcia.harness.policy.AuditStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything a future chat-UI integration would show. Not wired into any screen yet (see
 * docs/requirements.md's guide-mode FRs) -- today's PoC surfaces results through logcat, same as [AgentRunner]
 * before it had a UI. */
data class GuideRunState(
    val running: Boolean = false,
    val task: String? = null,
    val current: GuideStep? = null,
    val result: AgentResult? = null,
)

/**
 * Runs guide mode: one task at a time, sharing the run lock with [AgentRunner] so a do-mode task and a
 * guide-mode session never overlap one controller touching the phone while another is mid-explanation.
 *
 * PoC scope, deliberately not yet done (see docs/requirements.md): no wake lock (the user is expected to be
 * actively using the phone, unlike a do-mode run), no foreground-notification integration, no chat-UI wiring.
 */
object GuideRunner {
    private const val TAG = "HarnessGuide"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(GuideRunState())
    val state: StateFlow<GuideRunState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    val isRunning: Boolean get() = _state.value.running

    @Synchronized
    fun start(context: Context, task: String): AgentRunner.StartResult {
        if (isRunning || AgentRunner.isRunning) return AgentRunner.StartResult.BUSY
        val automation = HarnessAccessibilityService.automation ?: return AgentRunner.StartResult.NO_ACCESSIBILITY
        val overlay = HarnessAccessibilityService.guideOverlay ?: return AgentRunner.StartResult.NO_ACCESSIBILITY
        val config = LlmConfigStore.load(context) ?: return AgentRunner.StartResult.NO_LLM
        if (automation.isLockedOrAsleep()) return AgentRunner.StartResult.LOCKED
        val app = context.applicationContext
        val audit = AuditStore.get(app)

        _state.update { GuideRunState(running = true, task = task) }
        audit.newRun()
        audit.log("guide_run_started", mapOf("task_chars" to task.length.toString(), "model" to config.model))

        job = scope.launch {
            val client = OpenAiCompatibleClient(config)
            try {
                val loop = GuideLoop(
                    observe = { automation.snapshot() },
                    llm = client,
                    config = AgentConfig(vision = false),
                    onEvent = { event ->
                        Log.i(TAG, event.toString())
                        when (event) {
                            is GuideEvent.Step -> {
                                overlay.show(event.step)
                                _state.update { it.copy(current = event.step) }
                            }
                            is GuideEvent.Finished -> audit.log("guide_run_finished", mapOf("result" to event.result.toString().take(60)))
                            is GuideEvent.Started -> {}
                        }
                    },
                    hints = { pkg -> PlaybookStore.load(app).hintFor(pkg) },
                    awaitAdvance = { shown -> awaitScreenChange(automation, shown) },
                )
                val result = loop.run(task)
                Log.i(TAG, "RESULT: $result")
                _state.update { it.copy(result = result) }
            } catch (e: CancellationException) {
                audit.log("guide_run_stopped", emptyMap())
                Log.i(TAG, "RESULT: stopped by user")
                throw e
            } finally {
                overlay.hide()
                client.close()
                _state.update { it.copy(running = false) }
            }
        }
        return AgentRunner.StartResult.STARTED
    }

    fun stop() {
        job?.cancel()
    }

    /** Polls until the screen changes (a different foreground package, or a different element signature) or
     * a long timeout passes. The user, not the agent, controls the pace, so this is intentionally patient. */
    private suspend fun awaitScreenChange(automation: Automation, shown: Snapshot) {
        val before = shown.pkg to shown.elements.map { it.ref to it.text }
        val deadline = System.currentTimeMillis() + ADVANCE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            val now = automation.snapshot()
            if (now.pkg != before.first || now.elements.map { it.ref to it.text } != before.second) return
        }
    }

    private const val POLL_INTERVAL_MS = 500L
    private const val ADVANCE_TIMEOUT_MS = 5 * 60_000L
}
