package ga.egarcia.harness

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the agent (and the optional HTTP server) can do to the device. Implemented in-process
 * by [AccessibilityAutomation]; tests and the agent loop depend only on this interface.
 */
interface Automation {
    fun snapshot(): Snapshot
    suspend fun tap(snapshotId: String, ref: String): ActionResult
    fun type(snapshotId: String, ref: String, text: String): ActionResult

    /** Reveal content in [direction] ("scroll down" = see what is further down). */
    suspend fun scroll(direction: Direction, snapshotId: String?, ref: String?): ActionResult
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult
    fun back(): ActionResult
    fun home(): ActionResult

    /** [app] is a package id ("com.android.settings") or a launcher label ("Settings"). */
    fun openApp(app: String): ActionResult

    /** Polls until an element's text or description contains [text] (case-insensitive) or the timeout hits. */
    suspend fun waitFor(text: String, timeoutMs: Long): WaitResult

    /** Downscaled JPEG of the screen, for vision-capable models. API 30+. */
    suspend fun screenshot(maxSide: Int): ScreenshotResponse

    /** Keep the display on while an agent run is active. */
    fun keepAwake(enabled: Boolean) {}

    /** Package of the focused window right now, without touching snapshot state. */
    fun foregroundPackage(): String? = null
}

enum class Direction {
    UP, DOWN, LEFT, RIGHT;

    companion object {
        fun parse(value: String): Direction? = entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
}

@Serializable
data class ScrollRequest(
    @SerialName("snapshot_id") val snapshotId: String? = null,
    val ref: String? = null,
    val direction: String,
)

@Serializable
data class SwipeRequest(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    @SerialName("duration_ms") val durationMs: Long = 300,
)

@Serializable
data class WaitForRequest(
    val text: String,
    @SerialName("timeout_ms") val timeoutMs: Long = 10_000,
)

@Serializable
data class OpenAppRequest(val app: String)

@Serializable
data class WaitResult(
    val ok: Boolean,
    val error: String? = null,
    val snapshot: Snapshot? = null,
)

@Serializable
data class ScreenshotResponse(
    val ok: Boolean,
    val error: String? = null,
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("image_base64") val imageBase64: String? = null,
)
