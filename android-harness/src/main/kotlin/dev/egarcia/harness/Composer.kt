package ga.egarcia.harness

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import ga.egarcia.harness.voice.MicButton

/** Round send/stop button: an arrow to run the task, a square to stop it. Dimmed while there is nothing to send. */
class ActionButton(context: Context) : ImageView(context) {
    init {
        scaleType = ScaleType.CENTER_INSIDE
        setPadding(context.dp(9), context.dp(9), context.dp(9), context.dp(9))
        render(running = false, canRun = true)
    }

    fun render(running: Boolean, canRun: Boolean) {
        setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_send)
        background = context.rounded(Color.parseColor(if (running) "#202124" else "#3F51B5"), 20)
        alpha = if (running || canRun) 1f else 0.38f
        contentDescription = if (running) "Stop" else "Run"
    }
}

/**
 * The task box: one rounded container holding the text field with the microphone and the run/stop button
 * underneath it, on the right. The text field keeps the full width and grows upwards as the task gets longer.
 */
class Composer(context: Context) : LinearLayout(context) {
    val input = EditText(context).apply {
        hint = "What should I do on this phone?"
        textSize = 16f
        minLines = 1
        maxLines = 5
        background = null
        setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(2))
    }
    val mic = MicButton(context)
    val action = ActionButton(context)

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F1F3F4"))
            setStroke(context.dp(1), Color.parseColor("#DADCE0"))
            cornerRadius = context.dp(26).toFloat()
        }
        setPadding(context.dp(12), context.dp(8), context.dp(8), context.dp(8))
        addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(mic, LayoutParams(context.dp(40), context.dp(40)).apply { marginEnd = context.dp(4) })
            addView(action, LayoutParams(context.dp(40), context.dp(40)))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}
