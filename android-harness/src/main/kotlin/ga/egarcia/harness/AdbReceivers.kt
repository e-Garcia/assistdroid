package ga.egarcia.harness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ga.egarcia.harness.agent.AgentRunner
import ga.egarcia.harness.llm.LlmConfig
import ga.egarcia.harness.llm.LlmConfigStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Developer entry points, callable only from `adb shell` because the manifest gates them on the DUMP
 * permission (same approach as android-remote-control-mcp). Phase 3 adds the real chat UI.
 *
 *   adb shell am broadcast -a ga.egarcia.harness.ADB_CONFIGURE -n ga.egarcia.harness/.AdbConfigReceiver \
 *       --es base_url http://host:8000/v1 --es model NAME --es api_key KEY [--ez vision true] [--ez thinking false] \
 *       [--ei max_tokens 1024] [--es extra_body '{"repetition_penalty":1.1}']
 *   adb shell am broadcast -a ga.egarcia.harness.RUN_TASK -n ga.egarcia.harness/.AdbAgentReceiver --es task "..."
 *   adb shell am broadcast -a ga.egarcia.harness.STOP_TASK -n ga.egarcia.harness/.AdbAgentReceiver
 */
class AdbConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val old = LlmConfigStore.load(context)
        val baseUrl = intent.getStringExtra("base_url") ?: old?.baseUrl
        val model = intent.getStringExtra("model") ?: old?.model
        val apiKey = intent.getStringExtra("api_key") ?: old?.apiKey
        if (baseUrl == null || model == null || apiKey == null) {
            Log.w(TAG, "base_url, model and api_key are required the first time")
            return
        }
        val extra: JsonObject = intent.getStringExtra("extra_body")
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: old?.extraBody
            ?: JsonObject(emptyMap())
        val config = LlmConfig(
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            vision = if (intent.hasExtra("vision")) intent.getBooleanExtra("vision", true) else old?.vision ?: true,
            thinking = if (intent.hasExtra("thinking")) intent.getBooleanExtra("thinking", false) else old?.thinking ?: false,
            maxTokens = if (intent.hasExtra("max_tokens")) intent.getIntExtra("max_tokens", 1024) else old?.maxTokens ?: 1024,
            temperature = old?.temperature ?: 0.0,
            extraBody = extra,
        )
        LlmConfigStore.save(context, config)
        Log.i(TAG, "LLM configured: $config") // toString redacts the key
    }

    private companion object {
        const val TAG = "HarnessAgent"
    }
}

class AdbAgentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "ga.egarcia.harness.RUN_TASK" -> {
                val task = intent.getStringExtra("task")?.trim()
                if (task.isNullOrEmpty()) {
                    Log.w("HarnessAgent", "RUN_TASK needs --es task \"...\"")
                } else {
                    Log.i("HarnessAgent", "start: ${AgentRunner.start(context.applicationContext, task)}")
                }
            }
            "ga.egarcia.harness.STOP_TASK" -> AgentRunner.stop()
        }
    }
}
