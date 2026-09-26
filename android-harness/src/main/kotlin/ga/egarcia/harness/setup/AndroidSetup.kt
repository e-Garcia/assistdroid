package ga.egarcia.harness.setup

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import ga.egarcia.harness.HarnessAccessibilityService
import ga.egarcia.harness.SettingsActivity
import ga.egarcia.harness.SetupActivity
import ga.egarcia.harness.InboxSettingsActivity
import ga.egarcia.harness.inbox.HarnessNotificationListener
import ga.egarcia.harness.inbox.InboxStore
import ga.egarcia.harness.llm.LlmConfigStore

/** Reads the real device state for [SetupChecker]. */
class AndroidSetupEnvironment(private val context: Context) : SetupEnvironment {
    override val sdkInt: Int = Build.VERSION.SDK_INT
    override val manufacturer: String = Build.MANUFACTURER.orEmpty()

    override fun accessibilityEnabledInSettings(): Boolean {
        val resolver = context.contentResolver
        val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val on = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        return AccessibilitySetting.isEnabled(services, on, serviceComponent(context).flattenToString())
    }

    override fun accessibilityConnected(): Boolean = HarnessAccessibilityService.automation != null

    /** Android 13+ marks sideloaded apps as restricted (app-op mode "ignored") until the user allows it. */
    override fun restrictedSettingsBlocking(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            ops.unsafeCheckOpNoThrow("android:access_restricted_settings", Process.myUid(), context.packageName) == AppOpsManager.MODE_IGNORED
        }.getOrDefault(false)
    }

    override fun notificationsAllowed(): Boolean = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    override fun batteryUnrestricted(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    override fun modelConfigured(): Boolean = LlmConfigStore.load(context) != null

    override fun modelProbe(): ModelProbeResult = ModelProbe.result

    override fun notificationListenerEnabled(): Boolean = InboxStore.listenerEnabled(context)
}

fun serviceComponent(context: Context) = ComponentName(context, HarnessAccessibilityService::class.java)

/** Result of the last "Check connection", kept in memory so every screen shows the same answer. */
object ModelProbe {
    @Volatile
    var result: ModelProbeResult = ModelProbeResult.Unknown
}

/**
 * Remembers that the user was sent to Accessibility settings, so that when the service connects the app can bring
 * them back to the checklist instead of leaving them in system settings.
 */
object SetupFlow {
    private const val PREFS = "harness_setup"
    private const val RETURN_WINDOW_MS = 10 * 60_000L

    @Volatile
    private var awaitingSince = 0L

    fun expectAccessibilityToggle() {
        awaitingSince = System.currentTimeMillis()
    }

    @Volatile
    private var awaitingNotificationSince = 0L

    fun expectNotificationAccessToggle() {
        awaitingNotificationSince = System.currentTimeMillis()
    }

    fun consumeNotificationReturn(): Boolean {
        val recent = awaitingNotificationSince != 0L && System.currentTimeMillis() - awaitingNotificationSince < RETURN_WINDOW_MS
        awaitingNotificationSince = 0L
        return recent
    }

    /** True once, if the user recently went to enable the service. */
    fun consumeReturn(): Boolean {
        val recent = awaitingSince != 0L && System.currentTimeMillis() - awaitingSince < RETURN_WINDOW_MS
        awaitingSince = 0L
        return recent
    }

    fun seenOnFirstLaunch(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("seen", false)

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("seen", true).apply()
    }

    fun askedForNotifications(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("asked_notifications", false)

    fun markAskedForNotifications(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply()
    }
}

/** Takes the user to the exact system screen or permission dialog for a checklist button. */
object SetupActions {

    const val REQUEST_CODE_NOTIFICATIONS = 42

    fun perform(activity: Activity, action: SetupAction, checkModel: () -> Unit) {
        val pkg = activity.packageName
        when (action) {
            SetupAction.OPEN_ACCESSIBILITY -> {
                SetupFlow.expectAccessibilityToggle()
                openFirst(activity, accessibilityIntents(activity))
            }
            SetupAction.OPEN_APP_INFO -> openFirst(activity, listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))))
            SetupAction.REQUEST_NOTIFICATIONS -> requestNotifications(activity)
            SetupAction.OPEN_NOTIFICATION_SETTINGS -> openFirst(activity, listOf(notificationSettings(pkg)))
            SetupAction.REQUEST_BATTERY_EXEMPTION -> openFirst(
                activity,
                listOf(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                ),
            )
            SetupAction.OPEN_NOTIFICATION_ACCESS -> {
                SetupFlow.expectNotificationAccessToggle()
                openFirst(activity, notificationAccessIntents(activity))
            }
            SetupAction.OPEN_INBOX_SETTINGS -> activity.startActivity(Intent(activity, InboxSettingsActivity::class.java))
            SetupAction.OPEN_MODEL_SETTINGS -> activity.startActivity(Intent(activity, SettingsActivity::class.java))
            SetupAction.CHECK_MODEL -> checkModel()
        }
    }

    /** The system permission dialog when Android will still show it; otherwise the app's notification settings. */
    private fun requestNotifications(activity: Activity) {
        val pkg = activity.packageName
        if (Build.VERSION.SDK_INT < 33) {
            openFirst(activity, listOf(notificationSettings(pkg)))
            return
        }
        val canAsk = activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ||
            !SetupFlow.askedForNotifications(activity)
        if (canAsk && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            SetupFlow.markAskedForNotifications(activity)
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIFICATIONS)
        } else {
            // Asked and refused twice, or blocked in system settings: Android will not show the dialog again.
            openFirst(activity, listOf(notificationSettings(pkg)))
        }
    }

    private fun notificationSettings(pkg: String) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)

    /** Android 14+ can open this service's own page; older versions open the general list. */
    private fun accessibilityIntents(activity: Activity): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= 34) {
            // Not a public constant; the system Settings app handles this action. Devices that do not fall back to the list.
            add(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, serviceComponent(activity).flattenToString()))
        }
        add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** Android 11+ can open this listener's own page; otherwise the general "Notification access" list. */
    private fun notificationAccessIntents(activity: Activity): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= 30) {
            add(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(activity, HarnessNotificationListener::class.java).flattenToString()),
            )
        }
        add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun openFirst(activity: Activity, intents: List<Intent>) {
        for (intent in intents) {
            try {
                // Their own task: system settings must not pile up inside ours (relaunching the app would resume them).
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // try the next, more general screen
            } catch (_: SecurityException) {
                // some OEM builds refuse specific screens
            }
        }
    }

    /** Opens the checklist from anywhere (chat, overlay, service). */
    fun openChecklist(context: Context) {
        // CLEAR_TOP pops any system settings screens left above it, so coming back lands cleanly on the checklist.
        context.startActivity(Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
