package dev.egarcia.harness.policy

import dev.egarcia.harness.ActionResult
import dev.egarcia.harness.Automation
import dev.egarcia.harness.Direction
import dev.egarcia.harness.Element
import dev.egarcia.harness.ScreenshotResponse
import dev.egarcia.harness.Snapshot
import dev.egarcia.harness.WaitResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Settings the owner controls; read on every call so changes apply immediately. */
data class PolicyConfig(
    val customDenied: List<String> = emptyList(),
    /** Ask before send/post/delete/buy/call taps. Turning this off is logged. */
    val gateEnabled: Boolean = true,
)

/**
 * Enforces ADR 0002 in code, around whatever [delegate] does. The model never sees a denied app's
 * screen, cannot act inside it, cannot type into password fields, and cannot tap a send/post/delete/buy/call
 * target without the user's on-screen approval. Back and Home always work so the agent can leave a blocked app.
 */
class PolicyAutomation(
    private val delegate: Automation,
    private val policy: () -> PolicyConfig,
    private val approver: Approver,
    private val audit: AuditSink = NoAudit,
) : Automation by delegate {

    private var lastSnapshot: Snapshot? = null
    private var lastDeniedPackage: String? = null

    private fun denylist() = AppDenylist(policy().customDenied)

    // --- Observation -----------------------------------------------------------------------

    override fun snapshot(): Snapshot {
        val real = delegate.snapshot()
        val visible = if (denylist().isDenied(real.pkg)) {
            if (real.pkg != lastDeniedPackage) audit.log("app_denied", mapOf("pkg" to real.pkg.orEmpty(), "via" to "snapshot"))
            lastDeniedPackage = real.pkg
            Snapshot(real.snapshotId, real.pkg, emptyList(), blind = BLIND_DENIED)
        } else {
            lastDeniedPackage = null
            real
        }
        lastSnapshot = visible
        return visible
    }

    override suspend fun waitFor(text: String, timeoutMs: Long): WaitResult {
        // Re-implemented over the redacted snapshot: waiting for text must not reveal what a denied app shows.
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
        audit.log("action", mapOf("tool" to "wait_for", "pkg" to last?.pkg.orEmpty(), "result" to if (found != null) "ok" else "timeout"))
        return if (found != null) WaitResult(true, snapshot = found) else WaitResult(false, "timeout", last)
    }

    override suspend fun screenshot(maxSide: Int): ScreenshotResponse {
        deniedForeground()?.let {
            audit.log("action", mapOf("tool" to "screenshot", "pkg" to it, "result" to "app_denied"))
            return ScreenshotResponse(ok = false, error = "app_denied")
        }
        return delegate.screenshot(maxSide)
    }

    // --- Actions ---------------------------------------------------------------------------

    override suspend fun tap(snapshotId: String, ref: String): ActionResult {
        deniedForeground()?.let { return refuseDenied("tap", it) }

        val config = policy()
        val element = lastSnapshot?.takeIf { it.snapshotId == snapshotId }?.elements?.firstOrNull { it.ref == ref }
        val pkg = lastSnapshot?.pkg
        val risk = element?.let { RiskClassifier.classify(it, pkg) }
        if (risk != null && !config.gateEnabled) {
            audit.log("gate_disabled", mapOf("tool" to "tap", "pkg" to pkg.orEmpty(), "risk" to risk.name))
        } else if (risk != null) {
            val target = (element.text ?: element.desc ?: element.id ?: ref).take(40)
            val decision = approver.approve(ApprovalPrompt("Tap \"$target\"", risk, pkg, target))
            audit.log("approval", mapOf("tool" to "tap", "pkg" to pkg.orEmpty(), "risk" to risk.name, "target" to target, "decision" to decision.name))
            if (decision != Decision.APPROVED) {
                return ActionResult(false, "user_denied", "The user did not approve this action (${risk.description}). Do not retry it; report it with ask_user or done.")
            }
        }
        if (risk != null && config.gateEnabled && element != null) {
            // The user may have taken a while, and the dialog covered the app: old node handles can be dead.
            return retapAfterApproval(element, pkg).also { record("tap", pkg, it, "ref" to ref) }
        }
        return delegate.tap(snapshotId, ref).also { record("tap", pkg, it, "ref" to ref) }
    }

    /** Taps the same element (matched by role, text, description and id) on a fresh snapshot. */
    private suspend fun retapAfterApproval(original: Element, pkg: String?): ActionResult {
        // The dialog is still closing when the answer arrives; wait for the app to be in front again.
        if (pkg != null) {
            withTimeoutOrNull(RETURN_TIMEOUT_MS) {
                while (delegate.foregroundPackage() != pkg) delay(RETURN_POLL_MS)
            } ?: return ActionResult(false, "stale_ref", "The app was not in front again after your approval. Look at the screen before retrying.")
            delay(SETTLE_MS)
        }
        val fresh = delegate.snapshot()
        if (denylist().isDenied(fresh.pkg)) return refuseDenied("tap", fresh.pkg.orEmpty())
        val candidates = fresh.elements.filter {
            it.role == original.role && it.text == original.text && it.desc == original.desc && it.id == original.id
        }
        val match = candidates.singleOrNull() ?: candidates.firstOrNull { it.bounds == original.bounds }
            ?: return ActionResult(false, "stale_ref", "The screen changed while waiting for your approval. Look at the screen again before retrying.")
        lastSnapshot = fresh
        return delegate.tap(fresh.snapshotId, match.ref)
    }

    override fun type(snapshotId: String, ref: String, text: String): ActionResult {
        deniedForeground()?.let { return refuseDenied("type", it) }
        val element = lastSnapshot?.takeIf { it.snapshotId == snapshotId }?.elements?.firstOrNull { it.ref == ref }
        if (element?.text == PASSWORD_PLACEHOLDER) {
            audit.log("action", mapOf("tool" to "type", "pkg" to lastSnapshot?.pkg.orEmpty(), "result" to "password_blocked"))
            return ActionResult(false, "blocked", "Typing into password fields is not allowed. Ask the user to enter it.")
        }
        // Only the length is recorded, never the text.
        return delegate.type(snapshotId, ref, text).also { record("type", lastSnapshot?.pkg, it, "chars" to text.length.toString()) }
    }

    override suspend fun scroll(direction: Direction, snapshotId: String?, ref: String?): ActionResult {
        deniedForeground()?.let { return refuseDenied("scroll", it) }
        return delegate.scroll(direction, snapshotId, ref).also { record("scroll", lastSnapshot?.pkg, it) }
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult {
        deniedForeground()?.let { return refuseDenied("swipe", it) }
        // A zero-length or tiny "swipe" is a tap at raw coordinates, which would bypass the confirm gate
        // (it only sees taps by element). Real swipes must start inside a scrollable area and travel far.
        val scrollable = lastSnapshot?.elements.orEmpty().filter { it.scrollable }
        val distance = kotlin.math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
        val startsInScrollable = scrollable.any { x1 in it.bounds[0]..it.bounds[2] && y1 in it.bounds[1]..it.bounds[3] }
        if (distance < MIN_SWIPE_PX || !startsInScrollable) {
            audit.log("action", mapOf("tool" to "swipe", "pkg" to lastSnapshot?.pkg.orEmpty(), "result" to "blocked"))
            return ActionResult(false, "blocked", "Swipes must start inside a scrollable area and be at least ${MIN_SWIPE_PX.toInt()} px long. To press something use tap with its ref; to scroll use scroll.")
        }
        return delegate.swipe(x1, y1, x2, y2, durationMs).also { record("swipe", lastSnapshot?.pkg, it) }
    }

    override fun back(): ActionResult = delegate.back().also { record("back", delegate.foregroundPackage(), it) }

    override fun home(): ActionResult = delegate.home().also { record("home", delegate.foregroundPackage(), it) }

    override fun openApp(app: String): ActionResult {
        if (denylist().isDeniedName(app)) {
            audit.log("app_denied", mapOf("pkg" to app, "via" to "open_app"))
            return ActionResult(false, "app_denied", "This app is blocked by the safety settings. Do not try to work around it; tell the user.")
        }
        return delegate.openApp(app).also { record("open_app", it.detail ?: app, it) }
    }

    // --- Helpers ---------------------------------------------------------------------------

    private fun deniedForeground(): String? = delegate.foregroundPackage()?.takeIf { denylist().isDenied(it) }

    private fun refuseDenied(tool: String, pkg: String): ActionResult {
        audit.log("action", mapOf("tool" to tool, "pkg" to pkg, "result" to "app_denied"))
        return ActionResult(false, "app_denied", "This app is blocked by the safety settings. Go back or home and tell the user.")
    }

    private fun record(tool: String, pkg: String?, result: ActionResult, vararg extra: Pair<String, String>) {
        audit.log("action", mapOf("tool" to tool, "pkg" to pkg.orEmpty(), "result" to if (result.ok) "ok" else (result.error ?: "error")) + extra)
    }

    companion object {
        const val BLIND_DENIED = "app_denied"
        private const val PASSWORD_PLACEHOLDER = "[password]"
        private const val POLL_MS = 300L
        private const val MIN_SWIPE_PX = 150.0
        private const val RETURN_TIMEOUT_MS = 4_000L
        private const val RETURN_POLL_MS = 100L
        private const val SETTLE_MS = 200L
    }
}
