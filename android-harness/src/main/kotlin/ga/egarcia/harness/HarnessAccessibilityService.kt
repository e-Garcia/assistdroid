package ga.egarcia.harness

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

class HarnessAccessibilityService : AccessibilityService() {

    private var server: HarnessServer? = null
    private val snapshotCounter = AtomicLong()

    @Volatile
    private var latest: BuiltSnapshot? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val token = TokenStore.getOrCreate(this)
        // TODO(verify): debug-only; the token must never be logged in release builds (ADR 0002).
        if (BuildConfig.DEBUG) Log.i(TAG, "API token: $token")
        server = HarnessServer(this, token).also { it.start() }
        Log.i(TAG, "Harness server listening on ${HarnessServer.HOST}:${HarnessServer.PORT}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopServer()
        return super.onUnbind(intent)
    }

    private fun stopServer() {
        server?.stop()
        server = null
        instance = null
    }

    // --- Snapshot -------------------------------------------------------------------------

    fun takeSnapshot(): Snapshot {
        val id = "s_${snapshotCounter.incrementAndGet()}"
        val root = rootInActiveWindow
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

    // --- Actions --------------------------------------------------------------------------

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

    suspend fun tap(snapshotId: String, ref: String): ActionResult {
        val (node, error) = resolve(snapshotId, ref)
        if (node == null) return error!!

        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return ActionResult(true)
        }
        val rect = Rect().also(node::getBoundsInScreen)
        return if (gestureTap(rect.exactCenterX(), rect.exactCenterY())) {
            ActionResult(true, detail = "gesture")
        } else {
            ActionResult(false, "action_failed", "Click and gesture both failed")
        }
    }

    fun type(snapshotId: String, ref: String, text: String): ActionResult {
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

    fun back(): ActionResult = global(GLOBAL_ACTION_BACK)

    fun home(): ActionResult = global(GLOBAL_ACTION_HOME)

    private fun global(action: Int): ActionResult =
        if (performGlobalAction(action)) ActionResult(true) else ActionResult(false, "action_failed")

    private suspend fun gestureTap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    companion object {
        private const val TAG = "Harness"
        private const val MAX_DEPTH = 60

        @Volatile
        var instance: HarnessAccessibilityService? = null
            private set
    }
}
