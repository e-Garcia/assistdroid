package ga.egarcia.harness

import android.content.Context
import android.content.SharedPreferences

/** Small UI preferences. The floating button is off by default: it is on top of every app. */
object UiPrefs {
    private const val PREFS = "harness_ui"
    const val KEY_OVERLAY = "overlay_enabled"

    fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun overlayEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_OVERLAY, false)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY, enabled).apply()
    }

    /** Dim the screen while a task runs (the phone stays on, but is easier on the eyes and the battery). */
    fun dimEnabled(context: Context): Boolean = prefs(context).getBoolean("dim_enabled", true)

    fun setDimEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("dim_enabled", enabled).apply()
    }

    fun dimPercent(context: Context): Int = prefs(context).getInt("dim_percent", DEFAULT_DIM_PERCENT).coerceIn(MIN_DIM_PERCENT, MAX_DIM_PERCENT)

    fun setDimPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt("dim_percent", percent.coerceIn(MIN_DIM_PERCENT, MAX_DIM_PERCENT)).apply()
    }

    /** The brightness to request during a run (0.02 to 0.6), or null when dimming is off. */
    fun dimLevel(context: Context): Float? = if (dimEnabled(context)) dimPercent(context) / 100f else null

    const val MIN_DIM_PERCENT = 2
    const val MAX_DIM_PERCENT = 60
    const val DEFAULT_DIM_PERCENT = 20
}
