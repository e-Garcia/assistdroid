package ga.egarcia.harness

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Local API for the spike. Binds to localhost only and requires a bearer token on every route
 * (ADR 0002). MCP (`/mcp`) is added after the plain API is verified (ADR 0001).
 */
class HarnessServer(
    private val service: HarnessAccessibilityService,
    private val token: String,
) {
    private var engine: EmbeddedServer<*, *>? = null

    fun start() {
        engine = embeddedServer(CIO, host = HOST, port = PORT) {
            install(ContentNegotiation) { json(WireJson) }
            install(bearerAuth(token))
            routing {
                get("/api/health") { call.respond(mapOf("status" to "ok")) }
                get("/api/snapshot") { call.respond(service.takeSnapshot()) }
                post("/api/tap") {
                    val req = call.receive<TapRequest>()
                    call.respond(service.tap(req.snapshotId, req.ref))
                }
                post("/api/type") {
                    val req = call.receive<TypeRequest>()
                    call.respond(service.type(req.snapshotId, req.ref, req.text))
                }
                post("/api/back") { call.respond(service.back()) }
                post("/api/home") { call.respond(service.home()) }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        engine = null
    }

    private fun bearerAuth(expected: String) = createApplicationPlugin("BearerAuth") {
        val expectedBytes = expected.toByteArray()
        onCall { call ->
            val presented = call.request.header("Authorization")?.removePrefix("Bearer ")?.toByteArray()
            if (presented == null || !MessageDigest.isEqual(presented, expectedBytes)) {
                call.respond(HttpStatusCode.Unauthorized, ActionResult(false, "unauthorized"))
            }
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 8427

        val WireJson = Json {
            encodeDefaults = false
            ignoreUnknownKeys = true
        }
    }
}
