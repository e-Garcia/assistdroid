package ga.egarcia.harness.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class AuditLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun log(maxLines: Int = 2_000) = AuditLog(tmp.newFile(), maxLines) { Instant.parse("2026-09-25T12:00:00Z") }

    @Test
    fun writesOneJsonObjectPerLineWithTimeRunAndEvent() {
        val audit = log()
        val run = audit.newRun()
        audit.log("action", mapOf("tool" to "tap", "pkg" to "com.app", "result" to "ok"))
        val entry = Json.parseToJsonElement(audit.read().single()).jsonObject
        assertEquals("2026-09-25T12:00:00Z", entry["ts"]!!.jsonPrimitive.content)
        assertEquals(run, entry["run"]!!.jsonPrimitive.content)
        assertEquals("action", entry["event"]!!.jsonPrimitive.content)
        assertEquals("tap", entry["tool"]!!.jsonPrimitive.content)
    }

    @Test
    fun readsNewestFirstAndHonoursTheLimit() {
        val audit = log()
        (1..5).forEach { audit.log("e$it", emptyMap()) }
        assertEquals(listOf("e5", "e4"), audit.read(2).map { Json.parseToJsonElement(it).jsonObject["event"]!!.jsonPrimitive.content })
    }

    @Test
    fun longValuesAreTruncatedAsABackstop() {
        val audit = log()
        audit.log("x", mapOf("note" to "a".repeat(500)))
        assertEquals(80, Json.parseToJsonElement(audit.read().single()).jsonObject["note"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun trimsOldLinesWhenTheFileGrowsPastTheLimit() {
        val audit = log(maxLines = 10)
        repeat(100) { audit.log("e$it", emptyMap()) }
        assertTrue(audit.read(1_000).size <= 10 + 100)
        assertEquals("newest entry survives", "e99", Json.parseToJsonElement(audit.read(1).single()).jsonObject["event"]!!.jsonPrimitive.content)
        assertFalse("oldest entry was trimmed", audit.read(1_000).any { "\"e0\"" in it })
    }

    @Test
    fun clearRemovesEverything() {
        val audit = log()
        audit.log("x", emptyMap())
        audit.clear()
        assertTrue(audit.read().isEmpty())
    }
}
