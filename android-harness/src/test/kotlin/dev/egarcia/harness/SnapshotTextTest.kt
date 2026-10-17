package dev.egarcia.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTextTest {

    private fun element(ref: String, text: String? = null, desc: String? = null) =
        Element(ref = ref, role = "view", text = text, desc = desc, bounds = listOf(0, 0, 1, 1))

    private val snapshot = Snapshot(
        "s_1", "pkg",
        listOf(element("e1", text = "Search Settings"), element("e2", desc = "Battery charging, 100 percent")),
    )

    @Test
    fun matchesTextCaseInsensitively() = assertTrue(snapshot.containsText("search SETTINGS"))

    @Test
    fun matchesContentDescription() = assertTrue(snapshot.containsText("battery charging"))

    @Test
    fun missingTextDoesNotMatch() = assertFalse(snapshot.containsText("Bluetooth"))

    @Test
    fun blankQueryNeverMatches() {
        assertFalse(snapshot.containsText(""))
        assertFalse(snapshot.containsText("   "))
    }

    @Test
    fun directionParsesCaseInsensitively() {
        assertEquals(Direction.DOWN, Direction.parse("Down"))
        assertEquals(Direction.LEFT, Direction.parse(" left "))
        assertNull(Direction.parse("sideways"))
    }

    @Test
    fun snapshotSerializesPackageKey() {
        val json = HarnessServer.WireJson.encodeToString(Snapshot.serializer(), snapshot)
        assertTrue(json, json.contains("\"package\":\"pkg\""))
        assertFalse(json, json.contains("packageName"))
    }
}
