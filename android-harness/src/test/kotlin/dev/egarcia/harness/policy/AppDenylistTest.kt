package ga.egarcia.harness.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDenylistTest {
    private val list = AppDenylist()

    @Test
    fun blocksThisAppAndItsSubPackages() {
        assertTrue(list.isDenied("ga.egarcia.harness"))
        assertTrue(list.isDenied("ga.egarcia.harness.debug"))
    }

    @Test
    fun doesNotBlockUnrelatedPackagesThatMerelyShareAPrefix() {
        assertFalse(list.isDenied("ga.egarcia.sampleapp"))
        assertFalse(list.isDenied("ga.egarcia.harnessish"))
    }

    @Test
    fun blocksPasswordManagersAuthenticatorsAndWallets() {
        for (pkg in listOf("com.x8bit.bitwarden", "com.agilebits.onepassword", "com.google.android.apps.authenticator2",
            "com.azure.authenticator", "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.paypal.android.p2pmobile")) {
            assertTrue(pkg, list.isDenied(pkg))
        }
    }

    @Test
    fun blocksBankingAppsByKeyword() {
        assertTrue(list.isDenied("com.chase.sig.android.bank"))
        assertTrue(list.isDenied("com.mybank.mobile"))
        assertTrue(list.isDeniedName("Bank of Somewhere"))
    }

    @Test
    fun allowsOrdinaryApps() {
        for (pkg in listOf("com.android.settings", "com.example.messagingapp", "com.google.android.apps.messaging", "com.slack")) {
            assertFalse(pkg, list.isDenied(pkg))
        }
        assertFalse(list.isDenied(null))
        assertFalse(list.isDenied(""))
    }

    @Test
    fun customEntriesAreCaseInsensitivePackagesOrKeywords() {
        val custom = AppDenylist(listOf("Com.Android.Settings", "  photos ", "# a comment", ""))
        assertTrue(custom.isDenied("com.android.settings"))
        assertTrue(custom.isDenied("com.android.settings.intelligence"))
        assertTrue(custom.isDenied("com.google.android.apps.photos"))
        assertFalse("comment lines are ignored", custom.isDenied("a comment"))
        assertFalse(custom.isDenied("com.android.camera2"))
    }

    @Test
    fun matchingIsByPackageBoundary_notBySubstringForDottedEntries() {
        val custom = AppDenylist(listOf("com.example.app"))
        assertTrue(custom.isDenied("com.example.app"))
        assertFalse(custom.isDenied("com.example.application"))
    }
}
