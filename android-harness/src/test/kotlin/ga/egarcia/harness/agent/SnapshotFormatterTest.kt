package ga.egarcia.harness.agent

import ga.egarcia.harness.Element
import ga.egarcia.harness.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotFormatterTest {
    private fun el(ref: String, role: String, text: String? = null, desc: String? = null, clickable: Boolean = false,
                   editable: Boolean = false, scrollable: Boolean = false, checked: Boolean? = null) =
        Element(ref, role, text, desc, null, listOf(1, 2, 3, 4), clickable, editable, scrollable, checked)

    @Test
    fun formatsElementsCompactly() {
        val s = Snapshot("s_9", "com.android.settings", listOf(
            el("e1", "view", "Search Settings", clickable = true),
            el("e2", "scroll", scrollable = true),
            el("e3", "input", "wifi", editable = true, clickable = true),
            el("e4", "switch", "Wi-Fi", checked = true, clickable = true),
        ))
        val lines = SnapshotFormatter.format(s).lines()
        assertEquals("package: com.android.settings", lines[0])
        assertEquals("e1 view \"Search Settings\" [click]", lines[1])
        assertEquals("e2 scroll [scroll] bounds=1,2,3,4", lines[2])
        assertEquals("e3 input \"wifi\" [click,edit]", lines[3])
        assertEquals("e4 switch \"Wi-Fi\" [click,checked]", lines[4])
    }

    @Test
    fun outputHasNoSnapshotIdSoIdenticalScreensMatch() {
        val a = Snapshot("s_1", "p", listOf(el("e1", "view", "Hi")))
        val b = Snapshot("s_2", "p", listOf(el("e1", "view", "Hi")))
        assertEquals(SnapshotFormatter.format(a), SnapshotFormatter.format(b))
        assertFalse(SnapshotFormatter.format(a).contains("s_1"))
    }

    @Test
    fun descriptionShownOnlyWhenDifferentFromText() {
        assertTrue(SnapshotFormatter.line(el("e1", "image", desc = "Battery 100%")).contains("desc=\"Battery 100%\""))
        assertFalse(SnapshotFormatter.line(el("e1", "view", "Same", desc = "Same")).contains("desc="))
    }

    @Test
    fun quotesAndNewlinesAreCleaned() {
        val line = SnapshotFormatter.line(el("e1", "text", "He said \"hi\"\nthere"))
        assertEquals("e1 text \"He said 'hi' there\"", line)
    }

    @Test
    fun blindScreensAreFlagged() {
        val out = SnapshotFormatter.format(Snapshot("s_1", null, blind = "no_active_window"))
        assertTrue(out, out.contains("BLIND"))
    }

    @Test
    fun longListsAreTruncatedWithANote() {
        val s = Snapshot("s_1", "p", (1..10).map { el("e$it", "view", "x$it") })
        val out = SnapshotFormatter.format(s, maxElements = 3)
        assertTrue(out, out.contains("e3 view") && !out.contains("e4 view") && out.contains("7 more elements omitted"))
    }
}
