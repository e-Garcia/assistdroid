package ga.egarcia.harness

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

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
        server = HarnessServer(automation, token).also { it.start() }
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
