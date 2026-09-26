package ga.egarcia.harness.inbox

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ga.egarcia.harness.policy.AppDenylist
import ga.egarcia.harness.policy.PolicyStore
import ga.egarcia.harness.setup.SetupActions
import ga.egarcia.harness.setup.SetupFlow

/**
 * Receives every notification the system posts (that is what notification access means) but keeps only those from
 * apps the user chose to watch, and never from blocked apps. Everything else is dropped immediately.
 */
class HarnessNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        // The user came from the setup checklist to grant access: bring them back to it.
        if (SetupFlow.consumeNotificationReturn()) SetupActions.openChecklist(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val denylist = AppDenylist(PolicyStore.load(this).customDenied)
        if (denylist.isDenied(sbn.packageName)) return
        val n = sbn.notification ?: return
        val extras = n.extras
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
            .map { m ->
                CapturedMessage(
                    sender = m.senderPerson?.name?.toString() ?: m.sender?.toString(),
                    text = m.text?.toString().orEmpty(),
                    at = m.timestamp,
                )
            }
        InboxStore.repository(this).onPosted(
            IncomingNotification(
                key = sbn.key,
                pkg = sbn.packageName,
                appLabel = appLabel(sbn.packageName, extras.getParcelable("android.appInfo", ApplicationInfo::class.java)),
                postedAt = sbn.postTime,
                title = extras.getCharSequence(Notification.EXTRA_TITLE),
                text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT),
                conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
                messages = messages,
                isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                isOngoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
                isSecret = n.visibility == Notification.VISIBILITY_SECRET,
            ),
            InboxStore.watched(this),
        ) { denylist.isDenied(it) }
    }

    /** The notification carries its app's info, so the label resolves even without package visibility. */
    private fun appLabel(pkg: String, info: ApplicationInfo?): String =
        runCatching { (info ?: packageManager.getApplicationInfo(pkg, 0)).loadLabel(packageManager).toString() }.getOrDefault(pkg)
}
