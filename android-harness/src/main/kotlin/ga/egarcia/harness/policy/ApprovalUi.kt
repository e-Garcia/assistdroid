package ga.egarcia.harness.policy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import ga.egarcia.harness.ApprovalActivity
import ga.egarcia.harness.R

/**
 * Puts a pending approval in front of the user: a full dialog on top of whatever app the agent is in,
 * plus a heads-up notification with Approve and Deny as a fallback. The agent is suspended meanwhile,
 * so it never observes either. The notification text is generic on purpose (it is on screen).
 */
object ApprovalUi {
    val broker = ApprovalBroker()

    private const val CHANNEL = "approvals"
    private const val NOTIFICATION_ID = 2
    private const val TAG = "HarnessApproval"

    fun approver(context: Context): Approver {
        val app = context.applicationContext
        return Approver { prompt ->
            try {
                broker.request(prompt) { show(app) }
            } finally {
                app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
        }
    }

    private fun show(context: Context) {
        val pending = broker.pending.value ?: return
        Log.i(TAG, "approval requested (id=${pending.id}, risk=${pending.prompt.risk})") // no content: logcat is not private
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Approvals", NotificationManager.IMPORTANCE_HIGH))

        val activity = Intent(context, ApprovalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, 10, activity, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun answer(code: Int, approved: Boolean, label: String): Notification.Action {
            val intent = Intent(context, ApprovalActionReceiver::class.java).putExtra("id", pending.id).putExtra("approved", approved)
            val pi = PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_agent), label, pi).build()
        }
        notifications.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentTitle("AI Harness needs your approval")
                .setContentText("Tap to review the action the agent wants to take")
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(answer(11, true, "Approve"))
                .addAction(answer(12, false, "Deny"))
                .build(),
        )
        try {
            context.startActivity(activity)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not open the approval dialog; the notification remains: ${e.message}")
        }
    }
}

/** Target of the notification's Approve and Deny buttons. Not exported. */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ApprovalUi.broker.respond(intent.getLongExtra("id", -1), intent.getBooleanExtra("approved", false))
    }
}
