package ga.egarcia.harness

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire model: what agents see. Defaults are omitted on the wire to save tokens. */
@Serializable
data class Element(
    val ref: String,
    val role: String,
    val text: String? = null,
    val desc: String? = null,
    val id: String? = null,
    val bounds: List<Int>,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val focused: Boolean = false,
)

@Serializable
data class Snapshot(
    @SerialName("snapshot_id") val snapshotId: String,
    @SerialName("package") val pkg: String? = null,
    val elements: List<Element> = emptyList(),
    /** Non-null when the harness cannot see the screen (FR-09). */
    val blind: String? = null,
) {
    /** True if any element's text or description contains [query] (case-insensitive). */
    fun containsText(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        return elements.any {
            it.text?.contains(q, ignoreCase = true) == true || it.desc?.contains(q, ignoreCase = true) == true
        }
    }
}

@Serializable
data class ActionResult(
    val ok: Boolean,
    val error: String? = null,
    val detail: String? = null,
)

@Serializable
data class TapRequest(@SerialName("snapshot_id") val snapshotId: String, val ref: String)

@Serializable
data class TypeRequest(
    @SerialName("snapshot_id") val snapshotId: String,
    val ref: String,
    val text: String,
)
