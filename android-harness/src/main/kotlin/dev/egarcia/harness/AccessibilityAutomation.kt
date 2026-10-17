package dev.egarcia.harness

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.os.PowerManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** [Automation] backed by the accessibility service that owns it. */
class AccessibilityAutomation(private val service: AccessibilityService) : Automation {

    private val snapshotCounter = AtomicLong()

    @Volatile
    private var latest: BuiltSnapshot? = null

    // --- Snapshot -------------------------------------------------------------------------

    override fun snapshot(): Snapshot {
        val id = "s_${snapshotCounter.incrementAndGet()}"
        val root = service.rootInActiveWindow
        val built = SnapshotBuilder.build(id, root?.packageName?.toString(), root?.let { toRaw(it, 0) })
        latest = built
        return built.snapshot
    }

    private fun toRaw(node: AccessibilityNodeInfo, depth: Int): RawNode {
        val bounds = Rect().also(node::getBoundsInScreen)
        val children = if (depth >= MAX_DEPTH) {
            emptyList()
        } else {
            (0 until node.childCount).mapNotNull { i -> node.getChild(i)?.let { toRaw(it, depth + 1) } }
        }
        return RawNode(
            className = node.className?.toString(),
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            bounds = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            focused = node.isFocused,
            password = node.isPassword,
            visible = node.isVisibleToUser,
            children = children,
            handle = node,
        )
    }

    private fun resolve(snapshotId: String, ref: String): Pair<AccessibilityNodeInfo?, ActionResult?> {
        val current = latest
            ?: return null to ActionResult(false, "no_snapshot", "Call snapshot first")
        if (current.snapshot.snapshotId != snapshotId) {
            return null to ActionResult(false, "stale_ref", "Latest snapshot is ${current.snapshot.snapshotId}")
        }
        val node = current.handles[ref] as? AccessibilityNodeInfo
            ?: return null to ActionResult(false, "unknown_ref", "No element $ref in $snapshotId")
        if (!node.refresh()) return null to ActionResult(false, "stale_ref", "Element $ref is gone")
        return node to null
    }

    // --- Actions --------------------------------------------------------------------------

    override suspend fun tap(snapshotId: String, ref: String): ActionResult {
        val (node, error) = resolve(snapshotId, ref)
        if (node == null) return error!!

        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return ActionResult(true)
        }
        val rect = Rect().also(node::getBoundsInScreen)
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        return if (gesture(path, TAP_MS)) {
            ActionResult(true, detail = "gesture")
        } else {
            ActionResult(false, "action_failed", "Click and gesture both failed")
        }
    }

    override fun type(snapshotId: String, ref: String, text: String): ActionResult {
        val (node, error) = resolve(snapshotId, ref)
        if (node == null) return error!!
        if (!node.isEditable) return ActionResult(false, "not_actionable", "$ref is not editable")
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            ActionResult(true)
        } else {
            ActionResult(false, "action_failed", "SET_TEXT rejected")
        }
    }

    override suspend fun scroll(direction: Direction, snapshotId: String?, ref: String?): ActionResult {
        var target: AccessibilityNodeInfo? = null
        if (ref != null) {
            if (snapshotId == null) return ActionResult(false, "bad_request", "snapshot_id is required with ref")
            val (node, error) = resolve(snapshotId, ref)
            if (node == null) return error!!
            target = node
            while (target != null && !target.isScrollable) target = target.parent
        } else {
            val current = latest ?: run { snapshot(); latest }
            val biggest = current?.snapshot?.elements?.filter { it.scrollable }?.maxByOrNull(::area)
            target = biggest?.let { current.handles[it.ref] as? AccessibilityNodeInfo }?.takeIf { it.refresh() }
        }

        val action = when (direction) {
            Direction.UP -> AccessibilityAction.ACTION_SCROLL_UP
            Direction.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN
            Direction.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT
            Direction.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (target != null && target.performAction(action.id)) return ActionResult(true)

        // Fallback: swipe in the opposite direction inside the target (or the whole screen).
        val rect = Rect().also { r ->
            if (target != null) target.getBoundsInScreen(r) else {
                val m = service.resources.displayMetrics
                r.set(0, 0, m.widthPixels, m.heightPixels)
            }
        }
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val dx = rect.width() * 0.3f
        val dy = rect.height() * 0.3f
        val path = Path()
        when (direction) {
            Direction.DOWN -> { path.moveTo(cx, cy + dy); path.lineTo(cx, cy - dy) }
            Direction.UP -> { path.moveTo(cx, cy - dy); path.lineTo(cx, cy + dy) }
            Direction.RIGHT -> { path.moveTo(cx + dx, cy); path.lineTo(cx - dx, cy) }
            Direction.LEFT -> { path.moveTo(cx - dx, cy); path.lineTo(cx + dx, cy) }
        }
        return if (gesture(path, SWIPE_MS)) {
            ActionResult(true, detail = "gesture")
        } else {
            ActionResult(false, "action_failed", "Scroll action and gesture both failed")
        }
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult {
        val path = Path().apply {
            moveTo(x1.coerceAtLeast(0).toFloat(), y1.coerceAtLeast(0).toFloat())
            lineTo(x2.coerceAtLeast(0).toFloat(), y2.coerceAtLeast(0).toFloat())
        }
        return if (gesture(path, durationMs.coerceIn(50, 3_000))) {
            ActionResult(true)
        } else {
            ActionResult(false, "action_failed", "Gesture rejected or cancelled")
        }
    }

    override fun back(): ActionResult = global(AccessibilityService.GLOBAL_ACTION_BACK)

    override fun home(): ActionResult = global(AccessibilityService.GLOBAL_ACTION_HOME)

    private fun global(action: Int): ActionResult =
        if (service.performGlobalAction(action)) ActionResult(true) else ActionResult(false, "action_failed")

    override fun openApp(app: String): ActionResult {
        val pm = service.packageManager
        val query = app.trim()
        if (query.isEmpty()) return ActionResult(false, "bad_request", "app is empty")

        var launch: Intent? = if ('.' in query) pm.getLaunchIntentForPackage(query) else null
        var resolved = query
        if (launch == null) {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(main, 0).map {
                it.loadLabel(pm).toString() to it.activityInfo.packageName
            }
            val exact = apps.filter { it.first.equals(query, ignoreCase = true) }
            val matches = exact.ifEmpty { apps.filter { it.first.contains(query, ignoreCase = true) } }
            when {
                matches.isEmpty() -> return ActionResult(false, "app_not_found", "No launchable app matches '$query'")
                matches.size > 1 -> return ActionResult(
                    false, "ambiguous_app",
                    matches.joinToString { "${it.first} (${it.second})" },
                )
            }
            resolved = matches.single().second
            launch = pm.getLaunchIntentForPackage(resolved)
        }
        if (launch == null) return ActionResult(false, "app_not_found", "$resolved has no launch intent")
        return try {
            service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult(true, detail = resolved)
        } catch (e: RuntimeException) {
            ActionResult(false, "action_failed", e.message)
        }
    }

    override suspend fun waitFor(text: String, timeoutMs: Long): WaitResult {
        var last: Snapshot? = null
        val found = withTimeoutOrNull(timeoutMs.coerceIn(100, 60_000)) {
            while (true) {
                val s = snapshot()
                last = s
                if (s.containsText(text)) return@withTimeoutOrNull s
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        return if (found != null) WaitResult(true, snapshot = found) else WaitResult(false, "timeout", last)
    }

    override suspend fun screenshot(maxSide: Int): ScreenshotResponse {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotResponse(false, "unsupported", "Screenshots need Android 11+ (API 30)")
        }
        // Android enforces a minimum interval between screenshots; wait it out instead of failing the agent.
        var captured = capture()
        var attempts = 1
        while (captured.second == "rate_limited" && attempts < SCREENSHOT_ATTEMPTS) {
            delay(SCREENSHOT_RETRY_MS)
            captured = capture()
            attempts++
        }
        val bitmap = captured.first
            ?: return ScreenshotResponse(false, captured.second ?: "capture_failed")
        return withContext(Dispatchers.Default) {
            val scale = maxSide.coerceIn(200, 4096).toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            ScreenshotResponse(
                ok = true,
                mime = "image/jpeg",
                width = scaled.width,
                height = scaled.height,
                imageBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
            )
        }
    }

    /** Returns a software bitmap, or null plus an error code. */
    @android.annotation.SuppressLint("UseRequiresApi") // no AndroidX here; only called after the SDK_INT check in screenshot()
    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private suspend fun capture(): Pair<Bitmap?, String?> = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (cont.isActive) cont.resume(bitmap to if (bitmap == null) "capture_failed" else null)
                }

                override fun onFailure(errorCode: Int) {
                    val name = when (errorCode) {
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "rate_limited"
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no_accessibility_access"
                        else -> "capture_failed_$errorCode"
                    }
                    if (cont.isActive) cont.resume(null to name)
                }
            },
        )
    }

    override fun foregroundPackage(): String? = service.rootInActiveWindow?.packageName?.toString()

    override fun isLockedOrAsleep(): Boolean {
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        val power = service.getSystemService(PowerManager::class.java)
        return keyguard.isKeyguardLocked || !power.isInteractive
    }

    // --- Keep awake -----------------------------------------------------------------------

    private val main = Handler(Looper.getMainLooper())
    private var awakeOverlay: View? = null
    private var awakeParams: WindowManager.LayoutParams? = null
    private var awakeWanted = false

    /**
     * A 1x1, untouchable accessibility overlay with FLAG_KEEP_SCREEN_ON keeps the display on without an extra
     * permission (an accessibility service may add TYPE_ACCESSIBILITY_OVERLAY windows). The same window can carry a
     * brightness override, which Android drops by itself when the window goes away. Right after the service
     * (re)connects the window token may not be ready yet (BadTokenException), so adding is retried.
     */
    override fun keepAwake(enabled: Boolean, dim: Float?, onResult: (String) -> Unit) {
        main.post {
            awakeWanted = enabled
            if (enabled) {
                attachAwake(dim, 0, onResult)
            } else {
                val windows = service.getSystemService(WindowManager::class.java)
                awakeOverlay?.let { runCatching { windows.removeView(it) } }
                awakeOverlay = null
                awakeParams = null
                onResult("released")
            }
        }
    }

    override fun setDim(dim: Float?) {
        main.post {
            val view = awakeOverlay ?: return@post
            val params = awakeParams ?: return@post
            params.screenBrightness = dim ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            runCatching { service.getSystemService(WindowManager::class.java).updateViewLayout(view, params) }
        }
    }

    private fun attachAwake(dim: Float?, attempt: Int, onResult: (String) -> Unit) {
        if (!awakeWanted) return
        val windows = service.getSystemService(WindowManager::class.java)
        if (awakeOverlay != null) {
            setDim(dim)
            onResult(if (dim != null) "overlay_dimmed" else "overlay")
            return
        }
        val view = View(service)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        if (dim != null) params.screenBrightness = dim
        try {
            windows.addView(view, params)
            awakeOverlay = view
            awakeParams = params
            onResult(if (dim != null) "overlay_dimmed" else "overlay")
        } catch (e: RuntimeException) {
            if (attempt < AWAKE_RETRIES) {
                main.postDelayed({ attachAwake(dim, attempt + 1, onResult) }, AWAKE_RETRY_MS)
            } else {
                onResult("overlay_failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // --- Helpers --------------------------------------------------------------------------

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val dispatched = service.dispatchGesture(description, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    private fun area(e: Element): Long =
        (e.bounds[2] - e.bounds[0]).toLong() * (e.bounds[3] - e.bounds[1]).toLong()

    private companion object {
        const val MAX_DEPTH = 60
        const val TAP_MS = 50L
        const val SWIPE_MS = 300L
        const val POLL_MS = 300L
        const val JPEG_QUALITY = 70
        const val SCREENSHOT_ATTEMPTS = 4
        const val SCREENSHOT_RETRY_MS = 400L
        const val AWAKE_RETRIES = 4
        const val AWAKE_RETRY_MS = 500L
    }
}
