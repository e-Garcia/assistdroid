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
import ga.egarcia.harness.inbox.InboxSource
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Optional driven-mode API (debugging with curl, external agents). Binds to localhost only and
 * requires a bearer token on every route (ADR 0002). The on-device agent does not use this.
 */
class HarnessServer(
    private val automation: Automation,
    private val token: String,
    private val isBusy: () -> Boolean = { false },
    private val inbox: InboxSource? = null,
) {
    private var engine: EmbeddedServer<*, *>? = null

    fun start() {
        engine = embeddedServer(CIO, host = HOST, port = PORT) {
            install(ContentNegotiation) { json(WireJson) }
            install(bearerAuth(token))
            install(runLock())
            routing {
                get("/api/health") { call.respond(mapOf("status" to "ok")) }
                get("/api/snapshot") { call.respond(automation.snapshot()) }
                get("/api/notifications") {
                    val q = call.request.queryParameters
                    call.respond(mapOf("text" to (inbox?.notifications(q["app"], q["minutes"]?.toIntOrNull() ?: 1_440, q["limit"]?.toIntOrNull() ?: 10) ?: "Inbox is not available.")))
                }
                get("/api/screenshot") {
                    val maxSide = call.request.queryParameters["max_side"]?.toIntOrNull() ?: 1280
                    call.respond(automation.screenshot(maxSide))
                }
                post("/api/tap") {
                    val req = call.receive<TapRequest>()
                    call.respond(automation.tap(req.snapshotId, req.ref))
                }
                post("/api/type") {
                    val req = call.receive<TypeRequest>()
                    call.respond(automation.type(req.snapshotId, req.ref, req.text))
                }
                post("/api/scroll") {
                    val req = call.receive<ScrollRequest>()
                    val direction = Direction.parse(req.direction)
                    if (direction == null) {
                        call.respond(HttpStatusCode.BadRequest, ActionResult(false, "bad_request", "direction must be up|down|left|right"))
                    } else {
                        call.respond(automation.scroll(direction, req.snapshotId, req.ref))
                    }
                }
                post("/api/swipe") {
                    val req = call.receive<SwipeRequest>()
                    call.respond(automation.swipe(req.x1, req.y1, req.x2, req.y2, req.durationMs))
                }
                post("/api/wait_for") {
                    val req = call.receive<WaitForRequest>()
                    call.respond(automation.waitFor(req.text, req.timeoutMs))
                }
                post("/api/open_app") {
                    val req = call.receive<OpenAppRequest>()
                    call.respond(automation.openApp(req.app))
                }
                post("/api/back") { call.respond(automation.back()) }
                post("/api/home") { call.respond(automation.home()) }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        engine = null
    }

    /** One controller at a time (ADR 0005): the on-device agent owns the phone while it runs. */
    private fun runLock() = createApplicationPlugin("RunLock") {
        onCall { call ->
            val path = call.request.local.uri
            if (path.startsWith("/api/") && path != "/api/health" && isBusy()) {
                call.respond(HttpStatusCode.Conflict, ActionResult(false, "busy", "An on-device agent task is running"))
            }
        }
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
