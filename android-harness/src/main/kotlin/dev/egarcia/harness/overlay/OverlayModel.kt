package dev.egarcia.harness.overlay

enum class OverlayMode {
    /** A small draggable button. */
    BUBBLE,

    /** A prompt box to type a task. Focusable, so only shown while no task is running. */
    PROMPT,

    /** A status pill with a Stop button. Never focusable, so it cannot steal focus from the app being operated. */
    RUNNING,

    /** The outcome of the last run, with a way to reply or dismiss. */
    RESULT,
}

object OverlayModel {
    /** A running task always shows the pill; when it ends the outcome card appears; otherwise the user's choice stands. */
    fun next(running: Boolean, current: OverlayMode): OverlayMode = when {
        running -> OverlayMode.RUNNING
        current == OverlayMode.RUNNING -> OverlayMode.RESULT
        else -> current
    }
}
