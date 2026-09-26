package ga.egarcia.harness

/** Platform-free view of one accessibility node; [handle] is the platform object to act on later. */
data class RawNode(
    val className: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val viewId: String? = null,
    val bounds: List<Int> = listOf(0, 0, 0, 0),
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val password: Boolean = false,
    val visible: Boolean = true,
    val children: List<RawNode> = emptyList(),
    val handle: Any? = null,
)

data class BuiltSnapshot(val snapshot: Snapshot, val handles: Map<String, Any?>)

object SnapshotBuilder {
    private const val PASSWORD_PLACEHOLDER = "[password]"
    private const val MAX_TEXT = 200

    fun build(snapshotId: String, packageName: String?, root: RawNode?): BuiltSnapshot {
        if (root == null) {
            return BuiltSnapshot(Snapshot(snapshotId, packageName, blind = "no_active_window"), emptyMap())
        }
        val elements = mutableListOf<Element>()
        val handles = mutableMapOf<String, Any?>()
        walk(root, elements, handles)
        val blind = if (elements.isEmpty()) "empty_tree" else null
        return BuiltSnapshot(Snapshot(snapshotId, packageName, elements, blind), handles)
    }

    private fun walk(node: RawNode, out: MutableList<Element>, handles: MutableMap<String, Any?>) {
        if (!node.visible) return
        val own = ownText(node)
        val actionable = node.clickable || node.editable || node.scrollable || node.checkable

        // A clickable container without its own label (list row, card) takes its descendants'
        // text as its label, and those passive text leaves are not emitted separately.
        if (node.clickable && own == null && !node.editable) {
            val labels = mutableListOf<String>()
            collectPassiveText(node, labels)
            val merged = labels.joinToString(" · ").ifBlank { null }?.take(MAX_TEXT)
            emit(node, merged, out, handles)
            node.children.forEach { walkActionableOnly(it, out, handles) }
            return
        }

        if (own != null || actionable || node.desc != null) emit(node, own, out, handles)
        node.children.forEach { walk(it, out, handles) }
    }

    /** Inside a merged clickable container only nested interactive elements are still emitted. */
    private fun walkActionableOnly(node: RawNode, out: MutableList<Element>, handles: MutableMap<String, Any?>) {
        if (!node.visible) return
        if (node.clickable || node.editable || node.checkable) {
            emit(node, ownText(node), out, handles)
        }
        node.children.forEach { walkActionableOnly(it, out, handles) }
    }

    private fun collectPassiveText(node: RawNode, into: MutableList<String>) {
        node.children.forEach { child ->
            if (!child.visible) return@forEach
            if (!(child.clickable || child.editable || child.checkable)) {
                ownText(child)?.let(into::add)
                collectPassiveText(child, into)
            }
        }
    }

    private fun ownText(node: RawNode): String? {
        if (node.password) return PASSWORD_PLACEHOLDER
        return node.text?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)
    }

    private fun emit(node: RawNode, text: String?, out: MutableList<Element>, handles: MutableMap<String, Any?>) {
        val ref = "e${out.size + 1}"
        out += Element(
            ref = ref,
            role = role(node),
            text = text,
            desc = node.desc?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT),
            id = node.viewId?.substringAfter(":id/", node.viewId),
            bounds = node.bounds,
            clickable = node.clickable,
            editable = node.editable,
            scrollable = node.scrollable,
            checked = if (node.checkable) node.checked else null,
            focused = node.focused,
        )
        handles[ref] = node.handle
    }

    internal fun role(node: RawNode): String {
        if (node.editable) return "input"
        val simple = node.className?.substringAfterLast('.') ?: return "view"
        return when {
            simple.contains("Button") -> "button"
            simple.contains("CheckBox") -> "checkbox"
            simple.contains("Switch") -> "switch"
            simple.contains("RadioButton") -> "radio"
            simple.contains("ImageView") -> "image"
            simple.contains("RecyclerView") || simple.contains("ListView") -> "list"
            simple.contains("ScrollView") -> "scroll"
            simple.contains("TabLayout") || simple.contains("TabWidget") -> "tabs"
            simple.contains("TextView") -> "text"
            else -> "view"
        }
    }
}
