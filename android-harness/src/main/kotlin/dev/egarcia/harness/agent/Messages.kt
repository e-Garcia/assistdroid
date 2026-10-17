package dev.egarcia.harness.agent

import dev.egarcia.harness.llm.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builders for OpenAI chat-completions message objects. */
internal object Msg {
    fun system(text: String) = buildJsonObject {
        put("role", "system")
        put("content", text)
    }

    fun user(text: String) = buildJsonObject {
        put("role", "user")
        put("content", text)
    }

    fun userWithImage(text: String, mime: String, base64: String) = buildJsonObject {
        put("role", "user")
        put(
            "content",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                    buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", "data:$mime;base64,$base64") })
                    },
                ),
            ),
        )
    }

    fun assistantText(text: String) = buildJsonObject {
        put("role", "assistant")
        put("content", text)
    }

    fun assistantToolCalls(calls: List<ToolCall>) = buildJsonObject {
        put("role", "assistant")
        put("content", JsonNull)
        put(
            "tool_calls",
            JsonArray(
                calls.map { call ->
                    buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        put(
                            "function",
                            buildJsonObject {
                                put("name", call.name)
                                put("arguments", call.arguments)
                            },
                        )
                    }
                },
            ),
        )
    }

    fun toolResult(callId: String, text: String) = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", callId)
        put("content", text)
    }
}

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

internal fun JsonObject.int(name: String): Int? = long(name)?.toInt()
