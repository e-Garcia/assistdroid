package dev.egarcia.harness.setup

enum class SetupStatus { OK, WARNING, MISSING }

enum class Importance { REQUIRED, RECOMMENDED, OPTIONAL }

enum class SetupAction {
    OPEN_ACCESSIBILITY,
    OPEN_APP_INFO,
    REQUEST_NOTIFICATIONS,
    OPEN_NOTIFICATION_SETTINGS,
    REQUEST_BATTERY_EXEMPTION,
    OPEN_NOTIFICATION_ACCESS,
    OPEN_INBOX_SETTINGS,
    OPEN_MODEL_SETTINGS,
    CHECK_MODEL,
}

data class SetupButton(val label: String, val action: SetupAction)

data class SetupItem(
    val id: String,
    val title: String,
    val status: SetupStatus,
    val detail: String,
    val importance: Importance,
    val buttons: List<SetupButton> = emptyList(),
)

sealed interface ModelProbeResult {
    data object Unknown : ModelProbeResult
    data object Checking : ModelProbeResult
    data class Ok(val millis: Long) : ModelProbeResult
    data class Failed(val reason: String) : ModelProbeResult
}

/** Everything the checklist needs to know about the device, so the rules can be tested without Android. */
interface SetupEnvironment {
    val sdkInt: Int
    val manufacturer: String
    fun accessibilityEnabledInSettings(): Boolean
    fun accessibilityConnected(): Boolean

    /** True if Android 13+ is likely blocking this app's accessibility service until "Allow restricted settings". */
    fun restrictedSettingsBlocking(): Boolean

    /** The app may post notifications (on Android 13+ this includes the runtime permission). */
    fun notificationsAllowed(): Boolean
    fun batteryUnrestricted(): Boolean
    fun modelConfigured(): Boolean
    fun modelProbe(): ModelProbeResult

    /** Android's "Notification access" is on for this app (opt-in, only needed for the notification digest). */
    fun notificationListenerEnabled(): Boolean
}

data class SetupReport(val items: List<SetupItem>) {
    /** Blocked only by a missing required item; a warning (e.g. an unreachable model) does not stop the user trying. */
    val readyToRun: Boolean get() = items.none { it.importance == Importance.REQUIRED && it.status == SetupStatus.MISSING }

    /** Optional features (the digests) are the owner's choice: they never count as a problem or nag. */
    val counted: List<SetupItem> get() = items.filter { it.importance != Importance.OPTIONAL }
    val needsAttention: Boolean get() = counted.any { it.status != SetupStatus.OK }
    val okCount: Int get() = counted.count { it.status == SetupStatus.OK }
    val firstProblem: SetupItem?
        get() = counted.firstOrNull { it.importance == Importance.REQUIRED && it.status == SetupStatus.MISSING }
            ?: counted.firstOrNull { it.status != SetupStatus.OK }
}

object SetupChecker {
    /** A reply to a one-word prompt slower than this means every agent step will feel slow. */
    private const val SLOW_MS = 8_000L

    fun evaluate(env: SetupEnvironment): SetupReport =
        SetupReport(listOf(accessibility(env), model(env), notifications(env), battery(env), notificationDigest(env)))

    private fun accessibility(env: SetupEnvironment): SetupItem {
        val title = "Accessibility service"
        val samsung = env.manufacturer.equals("samsung", ignoreCase = true)
        val enable = SetupButton("Turn on", SetupAction.OPEN_ACCESSIBILITY)
        val allow = SetupButton("Allow restricted settings", SetupAction.OPEN_APP_INFO)
        if (env.accessibilityConnected()) {
            return SetupItem("accessibility", title, SetupStatus.OK, "Running. The agent can read the screen and operate apps.", Importance.REQUIRED)
        }
        if (env.accessibilityEnabledInSettings()) {
            return SetupItem(
                "accessibility", title, SetupStatus.MISSING,
                "It is switched on but not running. Switch it off and on again in Accessibility settings.",
                Importance.REQUIRED, listOf(SetupButton("Open accessibility settings", SetupAction.OPEN_ACCESSIBILITY)),
            )
        }
        val restricted = env.sdkInt >= 33
        val detail = buildString {
            if (env.restrictedSettingsBlocking()) {
                append("Android is blocking this app's accessibility service because it was installed outside an app store. ")
                append("First tap \"Allow restricted settings\" (in App info, tap the ⋮ menu at the top right and choose Allow restricted settings), then turn the service on.")
            } else {
                append("Turn on \"AI Harness\" in Accessibility settings (under Installed apps). It lets the agent read the screen and tap for you.")
                if (restricted) append(" If the switch is greyed out or says \"Restricted setting\", use Allow restricted settings first.")
            }
            if (samsung) append(" On Samsung phones, if the app will not install or update, turn off Auto Blocker in Settings > Security and privacy.")
        }
        val buttons = when {
            env.restrictedSettingsBlocking() -> listOf(allow, enable)
            restricted -> listOf(enable, allow)
            else -> listOf(enable)
        }
        return SetupItem("accessibility", title, SetupStatus.MISSING, detail, Importance.REQUIRED, buttons)
    }

    private fun model(env: SetupEnvironment): SetupItem {
        val title = "AI model"
        val open = SetupButton("Model settings", SetupAction.OPEN_MODEL_SETTINGS)
        val check = SetupButton("Check connection", SetupAction.CHECK_MODEL)
        if (!env.modelConfigured()) {
            return SetupItem("model", title, SetupStatus.MISSING, "Choose the AI model the agent should use: its address, name and key.", Importance.REQUIRED, listOf(open))
        }
        return when (val probe = env.modelProbe()) {
            ModelProbeResult.Unknown -> SetupItem("model", title, SetupStatus.OK, "Set up. Connection not tested yet.", Importance.REQUIRED, listOf(check, open))
            ModelProbeResult.Checking -> SetupItem("model", title, SetupStatus.OK, "Checking the connection…", Importance.REQUIRED)
            is ModelProbeResult.Ok -> SetupItem(
                "model", title, SetupStatus.OK,
                if (probe.millis >= SLOW_MS) "Reachable, but slow (${"%.1f".format(probe.millis / 1000.0)} s). The server may be busy with other work; tasks will take longer."
                else "Reachable (${probe.millis} ms).",
                Importance.REQUIRED, listOf(SetupButton("Check again", SetupAction.CHECK_MODEL), open),
            )
            is ModelProbeResult.Failed -> SetupItem(
                "model", title, SetupStatus.WARNING,
                "Could not reach the model: ${probe.reason}. If it runs on your computer, make sure Tailscale (or the network) is connected on this phone.",
                Importance.REQUIRED, listOf(SetupButton("Try again", SetupAction.CHECK_MODEL), open),
            )
        }
    }

    private fun notifications(env: SetupEnvironment): SetupItem {
        val title = "Notifications"
        if (env.notificationsAllowed()) {
            return SetupItem("notifications", title, SetupStatus.OK, "Allowed. You will see the Stop button and approval requests.", Importance.RECOMMENDED)
        }
        val action = if (env.sdkInt >= 33) SetupAction.REQUEST_NOTIFICATIONS else SetupAction.OPEN_NOTIFICATION_SETTINGS
        return SetupItem(
            "notifications", title, SetupStatus.MISSING,
            "Needed to show the Stop button and to ask your approval while the agent works in another app.",
            Importance.RECOMMENDED, listOf(SetupButton("Allow notifications", action)),
        )
    }

    private fun notificationDigest(env: SetupEnvironment): SetupItem {
        val title = "Notification digest (optional)"
        if (env.notificationListenerEnabled()) {
            return SetupItem(
                "notification_digest", title, SetupStatus.OK,
                "On. The agent can read notifications from the apps you chose to watch, without opening them.",
                Importance.OPTIONAL, listOf(SetupButton("Choose apps", SetupAction.OPEN_INBOX_SETTINGS)),
            )
        }
        val detail = buildString {
            append("Lets the agent summarise messages from notifications instead of operating a messaging app. ")
            append("Nothing is read until you switch on Notification access and pick the apps to watch.")
            if (env.sdkInt >= 33) append(" On Android 13+, if the switch is greyed out, use Allow restricted settings first.")
        }
        val buttons = buildList {
            add(SetupButton("Notification access", SetupAction.OPEN_NOTIFICATION_ACCESS))
            if (env.sdkInt >= 33) add(SetupButton("Allow restricted settings", SetupAction.OPEN_APP_INFO))
        }
        return SetupItem("notification_digest", title, SetupStatus.MISSING, detail, Importance.OPTIONAL, buttons)
    }

    private fun battery(env: SetupEnvironment): SetupItem {
        val title = "Background battery use"
        if (env.batteryUnrestricted()) {
            return SetupItem("battery", title, SetupStatus.OK, "Unrestricted, so long tasks are not cut short.", Importance.RECOMMENDED)
        }
        val samsung = env.manufacturer.equals("samsung", ignoreCase = true)
        val detail = buildString {
            append("Phones may stop apps that run in the background, which can end a task half-way. Allow unrestricted use for this app.")
            if (samsung) append(" On Samsung, also make sure it is not listed under Settings > Battery > Background usage limits > Sleeping apps.")
        }
        return SetupItem("battery", title, SetupStatus.MISSING, detail, Importance.RECOMMENDED, listOf(SetupButton("Allow", SetupAction.REQUEST_BATTERY_EXEMPTION)))
    }
}

/** Reads Android's `enabled_accessibility_services` setting without touching Android classes. */
object AccessibilitySetting {
    /** [enabledServices] is the colon-separated setting; entries may use the short (`pkg/.Class`) or full form. */
    fun isEnabled(enabledServices: String?, globalSwitchOn: Boolean, component: String): Boolean {
        if (!globalSwitchOn || enabledServices.isNullOrBlank()) return false
        val wanted = normalize(component)
        return enabledServices.split(':').any { normalize(it) == wanted }
    }

    private fun normalize(entry: String): String {
        val trimmed = entry.trim()
        val pkg = trimmed.substringBefore('/', missingDelimiterValue = "")
        val cls = trimmed.substringAfter('/', missingDelimiterValue = "")
        if (pkg.isEmpty() || cls.isEmpty()) return trimmed.lowercase()
        return (pkg + "/" + if (cls.startsWith(".")) pkg + cls else cls).lowercase()
    }
}
