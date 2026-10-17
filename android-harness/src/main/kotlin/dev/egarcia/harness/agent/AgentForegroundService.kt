package ga.egarcia.harness.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import ga.egarcia.harness.MainActivity
import ga.egarcia.harness.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Shows an ongoing notification with the current step and a STOP action while a task runs.
 * The run itself lives in [AgentRunner]; this service only mirrors it and exits when it goes idle.
 */
class AgentForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Agent activity", NotificationManager.IMPORTANCE_LOW),
        )
        val initial = notification(AgentRunner.state.value.status.ifEmpty { "Working…" })
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, initial, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }
        scope.launch {
            AgentRunner.state.collect { state ->
                if (state.running) {
                    notifications.notify(NOTIFICATION_ID, notification(state.status.ifEmpty { "Working…" }))
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(this, 1, Intent(this, AgentStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("AI Harness is working on your phone")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_agent), "Stop", stop).build())
            .build()
    }

    private companion object {
        const val CHANNEL = "agent_activity"
        const val NOTIFICATION_ID = 1
    }
}

/** Target of the notification's STOP button. Not exported: only our own notification can fire it. */
class AgentStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AgentRunner.stop()
}
