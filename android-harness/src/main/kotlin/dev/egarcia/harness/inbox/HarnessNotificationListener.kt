package dev.egarcia.harness.inbox

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.content.pm.ApplicationInfo
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.egarcia.harness.policy.AppDenylist
import dev.egarcia.harness.policy.PolicyStore
import dev.egarcia.harness.setup.SetupActions
import dev.egarcia.harness.setup.SetupFlow

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
        val messages = messagesFrom(extras)
        InboxStore.repository(this).onPosted(
            IncomingNotification(
                key = sbn.key,
                pkg = sbn.packageName,
                appLabel = appLabel(sbn.packageName, appInfoFrom(extras)),
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

    /**
     * Chat notifications carry their last messages as bundles; the keys are the documented ones behind
     * `Notification.MessagingStyle.Message`, read directly so this works from API 26.
     */
    private fun messagesFrom(extras: Bundle): List<CapturedMessage> {
        val bundles: Array<Parcelable>? = if (Build.VERSION.SDK_INT >= 33) extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)?.map { it as Parcelable }?.toTypedArray()
        else @Suppress("DEPRECATION") extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        return bundles.orEmpty().filterIsInstance<Bundle>().map { m ->
            CapturedMessage(
                sender = m.getCharSequence("sender")?.toString() ?: personName(m),
                text = m.getCharSequence("text")?.toString().orEmpty(),
                at = m.getLong("time"),
            )
        }
    }

    private fun personName(message: Bundle): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        val person = if (Build.VERSION.SDK_INT >= 33) message.getParcelable("sender_person", Person::class.java)
        else @Suppress("DEPRECATION") message.getParcelable<Person>("sender_person")
        return person?.name?.toString()
    }

    private fun appInfoFrom(extras: Bundle): ApplicationInfo? =
        if (Build.VERSION.SDK_INT >= 33) extras.getParcelable("android.appInfo", ApplicationInfo::class.java)
        else @Suppress("DEPRECATION") extras.getParcelable("android.appInfo")
}
