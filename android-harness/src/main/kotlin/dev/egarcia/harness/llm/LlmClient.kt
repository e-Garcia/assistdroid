package ga.egarcia.harness.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ToolCall(val id: String, val name: String, val arguments: String)

data class Usage(val promptTokens: Int?, val completionTokens: Int?)

data class LlmResponse(
    val content: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    val usage: Usage?,
)

/** [code] is a short machine-readable reason; messages never contain the API key. */
class LlmException(val code: String, message: String) : Exception(message) {
    /** Transient: network trouble, timeouts, server errors, rate limits. Auth and request errors are not. */
    val retryable: Boolean
        get() = code == "network" || code == "http_429" || code == "http_408" || code.startsWith("http_5")
}

interface LlmClient {
    /** [messages] and [tools] are already in OpenAI chat-completions JSON form. */
    suspend fun chat(messages: List<JsonObject>, tools: List<JsonObject>): LlmResponse
}

class OpenAiCompatibleClient(
    private val config: LlmConfig,
    private val http: HttpClient = defaultHttpClient(),
) : LlmClient, AutoCloseable {

    override suspend fun chat(messages: List<JsonObject>, tools: List<JsonObject>): LlmResponse {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        if (!CleartextPolicy.isAllowed(url)) {
            throw LlmException("cleartext_blocked", "Plain HTTP is only allowed to local or tailnet hosts; use https for $url")
        }
        val body = buildJsonObject {
            put("model", config.model)
            put("messages", JsonArray(messages))
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools))
                put("tool_choice", "auto")
            }
            put("max_tokens", config.maxTokens)
            put("temperature", config.temperature)
            if (!config.thinking) {
                put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
            }
            config.extraBody.forEach { (key, value) -> put(key, value) }
        }

        val response = try {
            http.post(url) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("network", redact(e.message ?: e::class.simpleName ?: "request failed"))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw LlmException("http_${response.status.value}", redact(text.take(300)))
        }
        return parse(text)
    }

    private fun parse(text: String): LlmResponse {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw LlmException("bad_response", "Response was not a JSON object")
        }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw LlmException("bad_response", "No choices in response")
        val message = choice["message"]?.jsonObject
            ?: throw LlmException("bad_response", "No message in first choice")

        val content = (message["content"] as? JsonPrimitive)?.contentOrNull
            ?.replace(THINK_BLOCK, "")?.trim()?.ifEmpty { null }
        val calls = message["tool_calls"]?.jsonArray.orEmpty().mapIndexedNotNull { index, element ->
            val call = element.jsonObject
            val function = call["function"]?.jsonObject ?: return@mapIndexedNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            val arguments = when (val raw = function["arguments"]) {
                null, is JsonNull -> "{}"
                is JsonPrimitive -> raw.content
                else -> raw.toString()
            }
            ToolCall(call["id"]?.jsonPrimitive?.contentOrNull ?: "call_$index", name, arguments)
        }
        val usage = root["usage"]?.jsonObject?.let {
            Usage(it["prompt_tokens"]?.jsonPrimitive?.intOrNull, it["completion_tokens"]?.jsonPrimitive?.intOrNull)
        }
        return LlmResponse(content, calls, choice["finish_reason"]?.jsonPrimitive?.contentOrNull, usage)
    }

    private fun redact(text: String): String = if (config.apiKey.isEmpty()) text else text.replace(config.apiKey, "<key>")

    override fun close() = http.close()

    private companion object {
        val THINK_BLOCK = Regex("(?s)<think>.*?</think>")

        fun defaultHttpClient() = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 180_000
                socketTimeoutMillis = 180_000
            }
        }
    }
}
