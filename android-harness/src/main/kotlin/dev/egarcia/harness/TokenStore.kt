package ga.egarcia.harness

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/** Generates the mandatory bearer token on first run (ADR 0002) and persists it privately. */
object TokenStore {
    private const val PREFS = "harness"
    private const val KEY = "api_token"

    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        prefs.edit().putString(KEY, token).apply()
        return token
    }

    fun rotate(context: Context): String {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        return getOrCreate(context)
    }
}
