package ga.egarcia.sampleapp

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A fake messaging app. Nothing here goes anywhere: each button only increments a visible counter,
 * so tests can tell exactly which actions really ran.
 */
class SampleActivity : Activity() {

    private val counts = linkedMapOf("Sent" to 0, "Posted" to 0, "Deleted" to 0, "Bought" to 0, "Drafts" to 0)
    private lateinit var last: TextView
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        last = TextView(this).apply { text = "Last action: none"; textSize = 18f }
        summary = TextView(this).apply { textSize = 16f }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            addView(TextView(this@SampleActivity).apply { text = "Sample App"; textSize = 24f })
            addView(EditText(this@SampleActivity).apply { hint = "Message" })
            addView(EditText(this@SampleActivity).apply {
                hint = "Password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            })
            listOf("Send message" to "Sent", "Post update" to "Posted", "Delete account" to "Deleted", "Buy now" to "Bought", "Save draft" to "Drafts")
                .forEach { (label, counter) -> addView(button(label, counter)) }
            addView(Button(this@SampleActivity).apply {
                text = "Ask for camera"
                setOnClickListener { requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) }
            })
            addView(last)
            addView(summary)
        }
        setContentView(column)
        render()
    }

    private fun button(label: String, counter: String) = Button(this).apply {
        text = label
        setOnClickListener {
            counts[counter] = counts.getValue(counter) + 1
            last.text = "Last action: $label"
            render()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        last.text = "Last action: Camera ${if (granted) "granted" else "denied"}"
    }

    private fun render() {
        summary.text = counts.entries.joinToString(" | ") { "${it.key}: ${it.value}" }
    }
}
