package ga.egarcia.harness.llm

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Talks to a real local HTTP server to check the exact request shape and response parsing. */
class OpenAiCompatibleClientTest {

    private class FakeServer(private val status: HttpStatusCode, private val reply: String) : AutoCloseable {
        var authHeader: String? = null
        var body: JsonObject? = null
        private val server = embeddedServer(CIO, port = 0) {
            routing {
                post("/v1/chat/completions") {
                    authHeader = call.request.header("Authorization")
                    body = Json.parseToJsonElement(call.receiveText()).jsonObject
                    call.respondText(reply, ContentType.Application.Json, status)
                }
            }
        }.also { it.start(wait = false) }
        val baseUrl: String = runBlocking { "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}/v1" }
        override fun close() = server.stop(0, 0)
    }

    private val messages = listOf(buildJsonObject { put("role", "user"); put("content", "hi") })
    private val toolReply = """
        {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"<think>hmm</think>",
         "tool_calls":[{"id":"call_1","type":"function","function":{"name":"tap","arguments":"{\"ref\":\"e3\"}"}}]}}],
         "usage":{"prompt_tokens":120,"completion_tokens":27}}
    """.trimIndent()

    @Test
    fun sendsAuthModelToolsAndThinkingFlag_andParsesToolCalls() = runBlocking {
        FakeServer(HttpStatusCode.OK, toolReply).use { server ->
            val config = LlmConfig(server.baseUrl, "test-model", "secret-key",
                extraBody = buildJsonObject { put("repetition_penalty", 1.1) })
            val tool = buildJsonObject { put("type", "function") }
            val response = OpenAiCompatibleClient(config).use { it.chat(messages, listOf(tool)) }

            assertEquals("Bearer secret-key", server.authHeader)
            val sent = server.body!!
            assertEquals("test-model", sent["model"]!!.jsonPrimitive.content)
            assertEquals("auto", sent["tool_choice"]!!.jsonPrimitive.content)
            assertEquals(1, sent["tools"]!!.jsonArray.size)
            assertEquals("false", sent["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.content)
            assertEquals("1.1", sent["repetition_penalty"]!!.jsonPrimitive.content)

            assertEquals("tool_calls", response.finishReason)
            assertNull("think block stripped, nothing left", response.content)
            assertEquals(listOf(ToolCall("call_1", "tap", "{\"ref\":\"e3\"}")), response.toolCalls)
            assertEquals(Usage(120, 27), response.usage)
        }
    }

    @Test
    fun thinkingEnabledOmitsTheDisableFlag_andNoToolsMeansNoToolChoice() = runBlocking {
        FakeServer(HttpStatusCode.OK, """{"choices":[{"message":{"content":"hello"}}]}""").use { server ->
            val config = LlmConfig(server.baseUrl, "m", "k", thinking = true)
            val response = OpenAiCompatibleClient(config).use { it.chat(messages, emptyList()) }
            assertFalse(server.body!!.containsKey("chat_template_kwargs"))
            assertFalse(server.body!!.containsKey("tools"))
            assertEquals("hello", response.content)
            assertTrue(response.toolCalls.isEmpty())
        }
    }

    @Test
    fun httpErrorsBecomeLlmExceptionWithoutLeakingTheKey() = runBlocking {
        FakeServer(HttpStatusCode.Unauthorized, """{"error":"bad key secret-key"}""").use { server ->
            val config = LlmConfig(server.baseUrl, "m", "secret-key")
            try {
                OpenAiCompatibleClient(config).use { it.chat(messages, emptyList()) }
                fail("expected LlmException")
            } catch (e: LlmException) {
                assertEquals("http_401", e.code)
                assertFalse(e.message!!, e.message!!.contains("secret-key"))
            }
        }
    }

    @Test
    fun publicCleartextIsRefusedBeforeAnyRequest() = runBlocking {
        val config = LlmConfig("http://example.com/v1", "m", "k")
        try {
            OpenAiCompatibleClient(config).use { it.chat(messages, emptyList()) }
            fail("expected LlmException")
        } catch (e: LlmException) {
            assertEquals("cleartext_blocked", e.code)
        }
    }

    @Test
    fun configToStringNeverContainsTheKey() {
        assertFalse(LlmConfig("http://h/v1", "m", "topsecret").toString().contains("topsecret"))
    }
}
