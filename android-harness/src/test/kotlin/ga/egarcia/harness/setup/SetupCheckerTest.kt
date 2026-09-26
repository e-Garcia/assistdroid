package ga.egarcia.harness.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCheckerTest {

    private data class Env(
        override val sdkInt: Int = 34,
        override val manufacturer: String = "Google",
        val enabled: Boolean = true,
        val connected: Boolean = true,
        val restricted: Boolean = false,
        val notifications: Boolean = true,
        val battery: Boolean = true,
        val configured: Boolean = true,
        val probe: ModelProbeResult = ModelProbeResult.Unknown,
    ) : SetupEnvironment {
        override fun accessibilityEnabledInSettings() = enabled
        override fun accessibilityConnected() = connected
        override fun restrictedSettingsBlocking() = restricted
        override fun notificationsAllowed() = notifications
        override fun batteryUnrestricted() = battery
        override fun modelConfigured() = configured
        override fun modelProbe() = probe
    }

    private fun item(report: SetupReport, id: String) = report.items.single { it.id == id }

    @Test
    fun everythingInPlaceMeansReadyAndNothingNeedsAttention() {
        val report = SetupChecker.evaluate(Env())
        assertTrue(report.readyToRun)
        assertFalse(report.needsAttention)
        assertNull(report.firstProblem)
        assertEquals(4, report.okCount)
    }

    @Test
    fun itemsAreOrderedWithRequiredFirst() {
        assertEquals(listOf("accessibility", "model", "notifications", "battery"), SetupChecker.evaluate(Env()).items.map { it.id })
    }

    // --- Accessibility -----------------------------------------------------------------------

    @Test
    fun aDisabledServiceIsMissingAndBlocksRunning() {
        val report = SetupChecker.evaluate(Env(enabled = false, connected = false))
        val a = item(report, "accessibility")
        assertEquals(SetupStatus.MISSING, a.status)
        assertEquals(Importance.REQUIRED, a.importance)
        assertFalse(report.readyToRun)
        assertEquals(a, report.firstProblem)
        assertEquals(SetupAction.OPEN_ACCESSIBILITY, a.buttons.first().action)
    }

    @Test
    fun onAndroid13PlusRestrictedSettingsIsOfferedAsASecondStep() {
        val a = item(SetupChecker.evaluate(Env(sdkInt = 34, enabled = false, connected = false)), "accessibility")
        assertEquals(listOf(SetupAction.OPEN_ACCESSIBILITY, SetupAction.OPEN_APP_INFO), a.buttons.map { it.action })
        assertTrue(a.detail.contains("Restricted"))
    }

    @Test
    fun whenAndroidIsActuallyBlockingTheAppInfoStepComesFirst() {
        val a = item(SetupChecker.evaluate(Env(restricted = true, enabled = false, connected = false)), "accessibility")
        assertEquals(SetupAction.OPEN_APP_INFO, a.buttons.first().action)
        assertTrue(a.detail.contains("blocking"))
    }

    @Test
    fun beforeAndroid13ThereIsNoRestrictedSettingsStep() {
        val a = item(SetupChecker.evaluate(Env(sdkInt = 31, enabled = false, connected = false)), "accessibility")
        assertEquals(listOf(SetupAction.OPEN_ACCESSIBILITY), a.buttons.map { it.action })
        assertFalse(a.detail.contains("Restricted"))
    }

    @Test
    fun enabledButNotRunningIsCalledOutSeparately() {
        val a = item(SetupChecker.evaluate(Env(enabled = true, connected = false)), "accessibility")
        assertEquals(SetupStatus.MISSING, a.status)
        assertTrue(a.detail.contains("not running"))
    }

    @Test
    fun samsungGetsAutoBlockerAndSleepingAppsAdvice() {
        val report = SetupChecker.evaluate(Env(manufacturer = "samsung", enabled = false, connected = false, battery = false))
        assertTrue(item(report, "accessibility").detail.contains("Auto Blocker"))
        assertTrue(item(report, "battery").detail.contains("Sleeping apps"))
        val other = SetupChecker.evaluate(Env(manufacturer = "Google", enabled = false, connected = false, battery = false))
        assertFalse(item(other, "accessibility").detail.contains("Auto Blocker"))
        assertFalse(item(other, "battery").detail.contains("Sleeping apps"))
    }

    // --- Model ---------------------------------------------------------------------------------

    @Test
    fun anUnconfiguredModelIsRequired() {
        val report = SetupChecker.evaluate(Env(configured = false))
        assertEquals(SetupStatus.MISSING, item(report, "model").status)
        assertFalse(report.readyToRun)
        assertEquals(SetupAction.OPEN_MODEL_SETTINGS, item(report, "model").buttons.single().action)
    }

    @Test
    fun anUnreachableModelWarnsButDoesNotBlockTrying() {
        val report = SetupChecker.evaluate(Env(probe = ModelProbeResult.Failed("Connection refused")))
        val m = item(report, "model")
        assertEquals(SetupStatus.WARNING, m.status)
        assertTrue(m.detail.contains("Connection refused") && m.detail.contains("Tailscale"))
        assertTrue("a warning still lets the user try", report.readyToRun)
        assertTrue(report.needsAttention)
    }

    @Test
    fun modelProbeStatesMapToReadableDetails() {
        assertTrue(item(SetupChecker.evaluate(Env(probe = ModelProbeResult.Ok(312))), "model").detail.contains("312 ms"))
        assertTrue(item(SetupChecker.evaluate(Env(probe = ModelProbeResult.Ok(12_550))), "model").detail.contains("slow") && item(SetupChecker.evaluate(Env(probe = ModelProbeResult.Ok(12_550))), "model").detail.contains("12.6 s"))
        assertFalse(item(SetupChecker.evaluate(Env(probe = ModelProbeResult.Ok(312))), "model").detail.contains("slow"))
        assertTrue(item(SetupChecker.evaluate(Env(probe = ModelProbeResult.Checking)), "model").detail.contains("Checking"))
        assertTrue(item(SetupChecker.evaluate(Env(probe = ModelProbeResult.Unknown)), "model").detail.contains("not tested"))
        assertTrue(item(SetupChecker.evaluate(Env(probe = ModelProbeResult.Checking)), "model").buttons.isEmpty())
    }

    // --- Recommended items ---------------------------------------------------------------------

    @Test
    fun missingNotificationsAndBatteryAreRecommendedNotRequired() {
        val report = SetupChecker.evaluate(Env(notifications = false, battery = false))
        assertTrue("recommended items never block", report.readyToRun)
        assertTrue(report.needsAttention)
        assertEquals(Importance.RECOMMENDED, item(report, "notifications").importance)
        assertEquals(Importance.RECOMMENDED, item(report, "battery").importance)
        assertEquals(SetupAction.REQUEST_BATTERY_EXEMPTION, item(report, "battery").buttons.single().action)
    }

    @Test
    fun notificationsUseTheRuntimePermissionOnAndroid13PlusAndSettingsBefore() {
        assertEquals(SetupAction.REQUEST_NOTIFICATIONS, item(SetupChecker.evaluate(Env(sdkInt = 34, notifications = false)), "notifications").buttons.single().action)
        assertEquals(SetupAction.OPEN_NOTIFICATION_SETTINGS, item(SetupChecker.evaluate(Env(sdkInt = 31, notifications = false)), "notifications").buttons.single().action)
    }

    @Test
    fun theFirstProblemPrefersARequiredItemOverARecommendedOne() {
        val report = SetupChecker.evaluate(Env(notifications = false, configured = false))
        assertEquals("model", report.firstProblem!!.id)
        assertNotNull(SetupChecker.evaluate(Env(notifications = false)).firstProblem)
        assertEquals("notifications", SetupChecker.evaluate(Env(notifications = false)).firstProblem!!.id)
    }

    // --- Reading the accessibility setting ---------------------------------------------------

    private val component = "ga.egarcia.harness/ga.egarcia.harness.HarnessAccessibilityService"

    @Test
    fun theServiceIsFoundInShortOrFullFormAndAmongOthers() {
        assertTrue(AccessibilitySetting.isEnabled(component, true, component))
        assertTrue(AccessibilitySetting.isEnabled("ga.egarcia.harness/.HarnessAccessibilityService", true, component.replace("ga.egarcia.harness.Harness", ".Harness")))
        assertTrue(AccessibilitySetting.isEnabled("com.other/.Svc:$component:com.third/.Svc", true, component))
        assertTrue(AccessibilitySetting.isEnabled("GA.egarcia.harness/ga.egarcia.harness.harnessaccessibilityservice", true, component))
    }

    @Test
    fun otherServicesOrAnOffSwitchMeanNotEnabled() {
        assertFalse(AccessibilitySetting.isEnabled("com.other/.Svc", true, component))
        assertFalse(AccessibilitySetting.isEnabled(null, true, component))
        assertFalse(AccessibilitySetting.isEnabled("", true, component))
        assertFalse("global accessibility switch is off", AccessibilitySetting.isEnabled(component, false, component))
        assertFalse(AccessibilitySetting.isEnabled("ga.egarcia.harness/.Other", true, component))
    }
}
