package ga.egarcia.harness.power

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Belt and braces for "the phone must not sleep mid-task", next to the keep-screen-on overlay window:
 * a partial wake lock keeps the CPU (and so the model requests) running even if the screen is switched off,
 * and a dim screen lock keeps the display on if the overlay window could not be added. Both time out on their
 * own so a crash can never leave the phone awake forever, and both are released when the run ends.
 * (SCREEN_DIM_WAKE_LOCK is deprecated but still honoured; it needs only the normal WAKE_LOCK permission.)
 */
@Suppress("DEPRECATION")
object RunWakeLock {
    private const val TAG = "HarnessAwake"

    /** A run is limited to 10 minutes; the locks outlive it slightly and no more. */
    const val TIMEOUT_MS = 12 * 60_000L

    private var cpu: PowerManager.WakeLock? = null
    private var screen: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context): String {
        val power = context.applicationContext.getSystemService(PowerManager::class.java)
        release()
        return try {
            cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "harness:run").apply { setReferenceCounted(false); acquire(TIMEOUT_MS) }
            screen = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "harness:screen").apply { setReferenceCounted(false); acquire(TIMEOUT_MS) }
            "held"
        } catch (e: RuntimeException) {
            Log.w(TAG, "wake lock failed: ${e.javaClass.simpleName}")
            "failed: ${e.javaClass.simpleName}"
        }
    }

    @Synchronized
    fun release() {
        listOf(cpu, screen).forEach { lock -> runCatching { if (lock?.isHeld == true) lock.release() } }
        cpu = null
        screen = null
    }
}
