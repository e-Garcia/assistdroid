package ga.egarcia.harness.voice

import android.content.Context
import android.graphics.Color
import android.widget.ImageView
import ga.egarcia.harness.R
import ga.egarcia.harness.dp
import ga.egarcia.harness.rounded

/** Round microphone button: grey when idle, red while listening, amber while transcribing. */
class MicButton(context: Context) : ImageView(context) {
    init {
        setImageResource(R.drawable.ic_mic)
        scaleType = ScaleType.CENTER_INSIDE
        setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
        contentDescription = "Dictate with your voice"
        render(VoiceState.Idle)
    }

    fun render(state: VoiceState) {
        val (background, tint, description) = when (state) {
            VoiceState.Listening -> Triple("#D93025", Color.WHITE, "Listening. Tap to finish.")
            VoiceState.Transcribing -> Triple("#F9AB00", Color.parseColor("#3C2A00"), "Turning speech into text")
            else -> Triple("#E8EAED", Color.parseColor("#3C4043"), "Dictate with your voice")
        }
        this.background = context.rounded(Color.parseColor(background), 20)
        setColorFilter(tint)
        contentDescription = description
    }
}
