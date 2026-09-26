package ga.egarcia.harness.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Tool schemas the model sees (OpenAI function-calling format). */
internal object AgentTools {

    fun definitions(vision: Boolean): List<JsonObject> = buildList {
        add(tool("tap", "Tap the element with this ref (from the latest screen).", mapOf("ref" to string("Element ref such as e3")), listOf("ref")))
        add(
            tool(
                "type",
                "Replace the text of an editable element (role input). Does not press Enter or Send.",
                mapOf("ref" to string("Ref of an element marked edit"), "text" to string("Text to enter")),
                listOf("ref", "text"),
            ),
        )
        add(
            tool(
                "scroll",
                "Scroll to reveal more content in the given direction ('down' shows content further down). Optionally scope it to a scrollable element ref.",
                mapOf(
                    "direction" to string("Where to reveal content", listOf("up", "down", "left", "right")),
                    "ref" to string("Optional ref of a scrollable element"),
                ),
                listOf("direction"),
            ),
        )
        add(
            tool(
                "swipe",
                "Swipe between two screen points in pixels (use bounds of a scrollable element). Prefer scroll.",
                mapOf(
                    "x1" to integer("Start x"), "y1" to integer("Start y"),
                    "x2" to integer("End x"), "y2" to integer("End y"),
                    "duration_ms" to integer("Optional duration, default 300"),
                ),
                listOf("x1", "y1", "x2", "y2"),
            ),
        )
        add(tool("back", "Press the Back button.", emptyMap(), emptyList()))
        add(tool("home", "Go to the home screen.", emptyMap(), emptyList()))
        add(
            tool(
                "open_app",
                "Open an app by launcher name (e.g. Settings) or package id.",
                mapOf("app" to string("App name or package id")),
                listOf("app"),
            ),
        )
        add(
            tool(
                "wait_for",
                "Wait until some text appears on screen (e.g. after a page load), up to a timeout.",
                mapOf("text" to string("Text to wait for"), "timeout_ms" to integer("Timeout in ms, default 10000")),
                listOf("text"),
            ),
        )
        if (vision) {
            add(tool("look", "Attach a screenshot of the current screen to the conversation. Use when the element list is not enough (icons, images, custom views).", emptyMap(), emptyList()))
        }
        add(tool("ask_user", "Stop and ask the user a question or hand back control (login, captcha, permission, missing information, a decision).", mapOf("question" to string("What you need from the user")), listOf("question")))
        add(tool("done", "Finish the task. Put any requested result or draft text in summary.", mapOf("summary" to string("Result for the user")), listOf("summary")))
    }

    private fun tool(name: String, description: String, properties: Map<String, JsonObject>, required: List<String>) =
        buildJsonObject {
            put("type", "function")
            put(
                "function",
                buildJsonObject {
                    put("name", name)
                    put("description", description)
                    put(
                        "parameters",
                        buildJsonObject {
                            put("type", "object")
                            put("properties", JsonObject(properties))
                            put("required", JsonArray(required.map(::JsonPrimitive)))
                        },
                    )
                },
            )
        }

    private fun string(description: String, enum: List<String>? = null) = buildJsonObject {
        put("type", "string")
        put("description", description)
        enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
    }

    private fun integer(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }
}
