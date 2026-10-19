package dev.egarcia.harness.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.egarcia.harness.agent.GuideStep

/**
 * Draws a highlight box around the element a guide-mode step is about, plus a short instruction bubble.
 * PoC-minimal on purpose: no animation, no handling yet for a target near the screen edge or on a second
 * display (foldables) -- see docs/requirements.md's guide-mode FRs for the follow-up.
 */
class GuideOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    // GuideLoop runs on Dispatchers.Default (GuideRunner), but View/WindowManager calls need a thread with a
    // prepared Looper -- same reason OverlayController posts through a main-thread Handler. Without this,
    // addView throws "Can't create handler inside thread ... that has not called Looper.prepare()" and the
    // step is silently narrated in logcat with no highlight ever shown (caught live, not by inspection).
    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null

    fun show(step: GuideStep) {
        main.post {
            hideNow()
            // Despite FLAG_LAYOUT_IN_SCREEN/NO_LIMITS and SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN, this window type
            // still has the status bar's height baked into its content origin on every device tested -- caught
            // live (not by inspection): the box rendered consistently, exactly, the status bar's height lower
            // than the element's real bounds. Rather than fight window-inset flag semantics further, measure
            // the real offset and subtract it directly; Element.bounds (getBoundsInScreen) is always true
            // screen coordinates, so this is the one number that needs compensating.
            val topInset = statusBarHeightPx()
            val bounds = HighlightBounds.from(step.bounds)
            val frame = FrameLayout(service)
            if (bounds != null) frame.addView(highlightBox(), boxLayoutParams(bounds, topInset))
            frame.addView(instructionBubble(step.instruction), bubbleLayoutParams(bounds, topInset))
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            runCatching { wm.addView(frame, params) }
                .onSuccess { root = frame }
                .onFailure { Log.w(TAG, "could not add guide overlay: ${it.message}") }
        }
    }

    // Lint flags this (InternalInsetResource, DiscouragedApi) and it's right that WindowInsetsCompat is the
    // supported way to do this -- but that's an androidx.core type, and this app deliberately has no
    // androidx/Compose dependency (CLAUDE.md). The WindowManager.LayoutParams flags that are supposed to avoid
    // needing this (FLAG_LAYOUT_IN_SCREEN/NO_LIMITS, SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) were tried first and
    // measured, live, to not change this window type's content origin at all. This does, exactly: verified
    // pixel-for-pixel against a real element's accessibility bounds on a real emulator.
    @Suppress("InternalInsetResource", "DiscouragedApi")
    private fun statusBarHeightPx(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else 0
    }

    fun hide() {
        main.post { hideNow() }
    }

    private fun hideNow() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun highlightBox() = View(service).apply {
        background = GradientDrawable().apply {
            setStroke(STROKE_PX, Color.parseColor("#FF5252"))
            setColor(Color.TRANSPARENT)
            cornerRadius = 8f
        }
    }

    private fun boxLayoutParams(bounds: HighlightBounds, topInset: Int) = FrameLayout.LayoutParams(
        bounds.right - bounds.left, bounds.bottom - bounds.top,
    ).apply {
        leftMargin = bounds.left
        topMargin = bounds.top - topInset
    }

    private fun instructionBubble(instruction: String) = TextView(service).apply {
        text = instruction
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(32, 24, 32, 24)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#DD212121"))
            cornerRadius = 20f
        }
    }

    /** Below the highlighted element if there is one, otherwise a fixed position near the top. */
    private fun bubbleLayoutParams(bounds: HighlightBounds?, topInset: Int) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        leftMargin = bounds?.left ?: DEFAULT_MARGIN_PX
        topMargin = if (bounds != null) bounds.bottom + BUBBLE_GAP_PX - topInset else DEFAULT_MARGIN_PX
    }

    private companion object {
        const val TAG = "HarnessGuideOverlay"
        const val STROKE_PX = 6
        const val BUBBLE_GAP_PX = 16
        const val DEFAULT_MARGIN_PX = 64
    }
}
