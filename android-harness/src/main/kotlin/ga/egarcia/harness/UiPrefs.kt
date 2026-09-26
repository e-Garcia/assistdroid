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
}
