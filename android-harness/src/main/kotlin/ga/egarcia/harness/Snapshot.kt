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
    val packageName: String? = null,
    val elements: List<Element> = emptyList(),
    /** Non-null when the harness cannot see the screen (FR-09). */
    val blind: String? = null,
)

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
