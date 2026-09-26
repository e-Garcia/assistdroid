package ga.egarcia.harness

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ga.egarcia.harness.policy.ApprovalBroker
import ga.egarcia.harness.policy.ApprovalUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Asks the user to approve one risky action. Shown on top of the app the agent is working in while the
 * agent is suspended. Leaving the dialog any other way counts as Deny.
 */
class ApprovalActivity : Activity() {

    private var scope: CoroutineScope? = null
    private var pending: ApprovalBroker.Pending? = null
    private lateinit var risk: TextView
    private lateinit var action: TextView
    private lateinit var where: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        risk = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#B3261E"))
        }
        action = TextView(this).apply {
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1F1F1F"))
            setPadding(0, dp(8), 0, dp(8))
        }
        where = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#5F6368"))
        }
        val approve = Button(this).apply {
            text = "Approve"
            setOnClickListener { answer(true) }
        }
        val deny = Button(this).apply {
            text = "Deny"
            setOnClickListener { answer(false) }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 20)
            setPadding(dp(24), dp(24), dp(24), dp(16))
            addView(TextView(this@ApprovalActivity).apply {
                text = "The agent wants to:"
                textSize = 14f
                setTextColor(Color.parseColor("#5F6368"))
            })
            addView(action)
            addView(risk)
            addView(where)
            addView(LinearLayout(this@ApprovalActivity).apply {
                gravity = Gravity.END
                setPadding(0, dp(20), 0, 0)
                addView(deny)
                addView(approve)
            })
        }
        val frame = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#66000000"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        applySystemInsets(frame)
        setContentView(frame)
    }

    override fun onStart() {
        super.onStart()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        s.launch {
            ApprovalUi.broker.pending.collect { p ->
                pending = p
                if (p == null) {
                    finish()
                } else {
                    risk.text = "This would ${p.prompt.risk.description}."
                    action.text = p.prompt.action
                    where.text = "In ${p.prompt.pkg ?: "the current app"}"
                }
            }
        }
    }

    override fun onStop() {
        scope?.cancel()
        scope = null
        // Anything other than an explicit Approve counts as a refusal.
        pending?.let { ApprovalUi.broker.respond(it.id, false) }
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = answer(false)

    private fun answer(approved: Boolean) {
        pending?.let { ApprovalUi.broker.respond(it.id, approved) }
        finish()
    }
}
