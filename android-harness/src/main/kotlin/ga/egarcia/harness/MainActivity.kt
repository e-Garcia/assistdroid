package ga.egarcia.harness

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Minimal control screen: service status, API token, shortcut to accessibility settings. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var token: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply { textSize = 18f }
        token = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        val settings = Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val rotate = Button(this).apply {
            text = "Rotate token"
            setOnClickListener {
                TokenStore.rotate(this@MainActivity)
                refresh()
            }
        }
        listOf(status, token, settings, rotate).forEach(column::addView)
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val running = HarnessAccessibilityService.instance != null
        status.text = if (running) {
            "Service running on ${HarnessServer.HOST}:${HarnessServer.PORT}"
        } else {
            "Service not running. Enable it in accessibility settings."
        }
        token.text = "Token (changes apply after the service restarts):\n${TokenStore.getOrCreate(this)}"
    }
}
