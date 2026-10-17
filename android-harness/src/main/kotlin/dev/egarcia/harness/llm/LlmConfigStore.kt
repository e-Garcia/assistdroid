package ga.egarcia.harness.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persists the LLM settings; the API key is encrypted with an Android Keystore key (ADR 0005). */
object LlmConfigStore {
    private const val PREFS = "harness_llm"

    fun load(context: Context): LlmConfig? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val baseUrl = p.getString("base_url", null) ?: return null
        val model = p.getString("model", null) ?: return null
        val key = p.getString("api_key", null)?.let(SecretBox::decrypt) ?: return null
        val extra = p.getString("extra_body", null)
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        return LlmConfig(
            baseUrl = baseUrl,
            model = model,
            apiKey = key,
            vision = p.getBoolean("vision", true),
            thinking = p.getBoolean("thinking", false),
            maxTokens = p.getInt("max_tokens", 1024),
            temperature = p.getFloat("temperature", 0f).toDouble(),
            extraBody = extra,
        )
    }

    fun save(context: Context, config: LlmConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("base_url", config.baseUrl)
            .putString("model", config.model)
            .putString("api_key", SecretBox.encrypt(config.apiKey))
            .putBoolean("vision", config.vision)
            .putBoolean("thinking", config.thinking)
            .putInt("max_tokens", config.maxTokens)
            .putFloat("temperature", config.temperature.toFloat())
            .putString("extra_body", config.extraBody.toString())
            .apply()
    }
}

/** AES-GCM with a non-exportable Keystore key. Stored form: base64(iv + ciphertext). */
internal object SecretBox {
    private const val ALIAS = "harness_llm_key"
    private const val PROVIDER = "AndroidKeyStore"
    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES)) }
        String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES))
    }.getOrNull()
}
