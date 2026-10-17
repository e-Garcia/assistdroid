package dev.egarcia.harness

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.egarcia.harness.policy.AuditStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The local activity log (NFR-05): what the agent did, newest first. It never contains screen text or typed text. */
class AuditActivity : Activity() {

    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.parseColor("#1F1F1F"))
            setTextIsSelectable(true)
        }
        val refresh = Button(this).apply { text = "Refresh"; setOnClickListener { load() } }
        val clear = Button(this).apply {
            text = "Clear log"
            setOnClickListener {
                AuditStore.get(this@AuditActivity).clear()
                load()
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(this@AuditActivity).apply {
                text = "Activity log"
                textSize = 22f
                setTextColor(Color.parseColor("#1F1F1F"))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@AuditActivity).apply {
                text = "What the agent did, newest first. Stored only on this phone; it holds no screen text or typed text."
                textSize = 12f
                setTextColor(Color.parseColor("#5F6368"))
                setPadding(0, dp(4), 0, dp(8))
            })
            addView(LinearLayout(this@AuditActivity).apply { addView(refresh); addView(clear) })
            addView(ScrollView(this@AuditActivity).apply { addView(body) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val frame = LinearLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(column, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        applySystemInsets(frame)
        setContentView(frame)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val lines = AuditStore.get(this).read(300)
        body.text = if (lines.isEmpty()) "Nothing logged yet." else lines.joinToString("\n") { format(it) }
    }

    /** `2026-09-25T12:00:01Z {run,event,...}` becomes `12:00:01 r1 action tool=tap pkg=... result=ok`. */
    private fun format(line: String): String {
        val json: JsonObject = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return line
        fun field(name: String) = json[name]?.jsonPrimitive?.content.orEmpty()
        val time = field("ts").substringAfter('T').substringBefore('.').removeSuffix("Z")
        val rest = json.entries.filter { it.key !in setOf("ts", "run", "event") }.joinToString(" ") { "${it.key}=${it.value.jsonPrimitive.content}" }
        return "$time ${field("run")} ${field("event")} $rest".trim()
    }
}
