package dev.egarcia.harness.llm

import kotlinx.serialization.json.JsonObject

/** Connection settings for one OpenAI-compatible chat-completions endpoint. */
data class LlmConfig(
    /** Includes the version prefix, e.g. `http://my-server:8000/v1`. */
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    /** Send screenshots (image_url parts) to this model. */
    val vision: Boolean = true,
    /** Let the model emit reasoning tokens. Off by default: much faster and enough for UI steps. */
    val thinking: Boolean = false,
    val maxTokens: Int = 1024,
    val temperature: Double = 0.0,
    /** Extra top-level request fields for the server, e.g. `{"repetition_penalty": 1.1}`. */
    val extraBody: JsonObject = JsonObject(emptyMap()),
) {
    /** Never prints the key, so configs are safe to log. */
    override fun toString(): String =
        "LlmConfig(baseUrl=$baseUrl, model=$model, apiKey=<redacted>, vision=$vision, thinking=$thinking, " +
            "maxTokens=$maxTokens, temperature=$temperature, extraBody=$extraBody)"
}

/**
 * ADR 0005: plain HTTP is allowed only to local or tailnet hosts; everything else needs HTTPS.
 * The Ktor CIO client does not go through Android's network security config, so this is enforced here.
 */
object CleartextPolicy {
    fun isAllowed(url: String): Boolean {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> isLocalHost(host)
            else -> false
        }
    }

    internal fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host == "[::1]" || host == "::1") return true
        if (host.endsWith(".ts.net") || host.endsWith(".local") || host.endsWith(".lan")) return true
        // A single-label name ("my-server") can only be a LAN or MagicDNS name.
        if (!host.contains('.') && !host.contains(':')) return true
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 100 && b in 64..127) // Tailscale CGNAT range 100.64.0.0/10
    }
}
