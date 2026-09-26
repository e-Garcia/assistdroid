package ga.egarcia.harness

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import ga.egarcia.harness.agent.AgentRunner
import ga.egarcia.harness.policy.ApprovalUi
import ga.egarcia.harness.policy.AuditStore
import ga.egarcia.harness.policy.PolicyAutomation
import ga.egarcia.harness.policy.PolicyStore

/** Owns the process-wide [Automation] and, for driven mode, the optional localhost server. */
class HarnessAccessibilityService : AccessibilityService() {

    private var server: HarnessServer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val automation = AccessibilityAutomation(this)
        Companion.automation = automation
        val token = TokenStore.getOrCreate(this)
        // TODO(verify): debug-only; the token must never be logged in release builds (ADR 0002).
        if (BuildConfig.DEBUG) Log.i(TAG, "API token: $token")
        val guarded = PolicyAutomation(automation, { PolicyStore.load(this) }, ApprovalUi.approver(this), AuditStore.get(this))
        server = HarnessServer(guarded, token, isBusy = { AgentRunner.isRunning }).also { it.start() }
        Log.i(TAG, "Harness server listening on ${HarnessServer.HOST}:${HarnessServer.PORT}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stop()
        return super.onUnbind(intent)
    }

    private fun stop() {
        server?.stop()
        server = null
        automation = null
    }

    companion object {
        private const val TAG = "Harness"

        /** Non-null only while the accessibility service is connected. */
        @Volatile
        var automation: Automation? = null
            private set
    }
}
