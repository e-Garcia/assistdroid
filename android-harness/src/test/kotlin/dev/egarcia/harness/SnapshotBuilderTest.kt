package dev.egarcia.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotBuilderTest {

    private fun text(t: String) = RawNode(className = "android.widget.TextView", text = t, handle = t)

    @Test
    fun nullRootIsBlind() {
        val built = SnapshotBuilder.build("s_1", null, null)
        assertEquals("no_active_window", built.snapshot.blind)
    }

    @Test
    fun emptyTreeIsBlind() {
        val root = RawNode(className = "android.widget.FrameLayout")
        val built = SnapshotBuilder.build("s_1", "pkg", root)
        assertEquals("empty_tree", built.snapshot.blind)
    }

    @Test
    fun invisibleNodesAreDropped() {
        val root = RawNode(children = listOf(text("shown"), text("hidden").copy(visible = false)))
        val els = SnapshotBuilder.build("s_1", "pkg", root).snapshot.elements
        assertEquals(listOf("shown"), els.map { it.text })
    }

    @Test
    fun clickableRowTakesChildTextAndSuppressesPassiveChildren() {
        val row = RawNode(
            className = "android.view.ViewGroup",
            clickable = true,
            children = listOf(text("Ada Lovelace"), text("Unread"), text("Re: role")),
        )
        val built = SnapshotBuilder.build("s_1", "pkg", RawNode(children = listOf(row)))
        val els = built.snapshot.elements
        assertEquals(1, els.size)
        assertEquals("Ada Lovelace · Unread · Re: role", els[0].text)
        assertTrue(els[0].clickable)
    }

    @Test
    fun nestedButtonInsideClickableRowStaysSeparate() {
        val row = RawNode(
            clickable = true,
            children = listOf(
                text("Title"),
                RawNode(className = "android.widget.Button", text = "Reply", clickable = true),
            ),
        )
        val els = SnapshotBuilder.build("s_1", "pkg", RawNode(children = listOf(row))).snapshot.elements
        assertEquals(listOf("Title", "Reply"), els.map { it.text })
        assertEquals("button", els[1].role)
    }

    @Test
    fun passwordValueIsNeverExposed() {
        val pw = RawNode(className = "android.widget.EditText", editable = true, password = true, text = "hunter2")
        val el = SnapshotBuilder.build("s_1", "pkg", RawNode(children = listOf(pw))).snapshot.elements.single()
        assertEquals("[password]", el.text)
        assertEquals("input", el.role)
    }

    @Test
    fun refsMapBackToHandles() {
        val root = RawNode(children = listOf(text("a"), text("b")))
        val built = SnapshotBuilder.build("s_1", "pkg", root)
        assertEquals(listOf("e1", "e2"), built.snapshot.elements.map { it.ref })
        assertEquals("b", built.handles["e2"])
        assertNull(built.handles["e3"])
    }
}
