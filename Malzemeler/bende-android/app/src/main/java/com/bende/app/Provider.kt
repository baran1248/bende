package com.bende.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProviderKind(val label: String) { OPENAI("OpenAI uyumlu"), ANTHROPIC("Anthropic") }

data class ProviderConfig(
    val kind: ProviderKind = ProviderKind.OPENAI,
    val baseUrl: String = "https://api.openai.com",
    val apiKey: String = "",
    val model: String = ""
)

object ProviderStore {
    private const val PREFS = "provider"
    private const val KEY_ALIAS = "bende_provider_key_v1"

    fun load(context: Context): ProviderConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kind = runCatching { ProviderKind.valueOf(prefs.getString("kind", "OPENAI")!!) }.getOrDefault(ProviderKind.OPENAI)
        val encrypted = prefs.getString("api_key", null)
        return ProviderConfig(
            kind = kind,
            baseUrl = prefs.getString("base_url", "https://api.openai.com") ?: "https://api.openai.com",
            apiKey = encrypted?.let { runCatching { decrypt(it) }.getOrDefault("") } ?: "",
            model = prefs.getString("model", "") ?: ""
        )
    }

    fun save(context: Context, config: ProviderConfig) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentKey = prefs.getString("api_key", null)
        val key = when {
            config.apiKey.isEmpty() -> currentKey
            else -> encrypt(config.apiKey)
        }
        prefs.edit().putString("kind", config.kind.name).putString("base_url", normalizeBaseUrl(config.baseUrl))
            .putString("model", config.model.trim()).apply {
                if (key == null) remove("api_key") else putString("api_key", key)
            }.apply()
    }

    fun clearKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("api_key").apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}

object ProviderApi {
    suspend fun listModels(config: ProviderConfig): List<String> = withContext(Dispatchers.IO) {
        require(config.apiKey.isNotBlank()) { "Önce API anahtarını girin." }
        val url = endpoint(config, "/v1/models")
        val response = request(config, url, "GET")
        val data = JSONObject(response).optJSONArray("data") ?: JSONObject(response).optJSONArray("models")
            ?: throw IllegalStateException("Model listesi yanıt biçimi tanınmadı.")
        (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            (item.optString("id").takeIf { it.isNotBlank() } ?: item.optString("name").takeIf { it.isNotBlank() })
        }.distinct().sorted()
    }

    suspend fun complete(context: Context, config: ProviderConfig, history: List<Message>): String = withContext(Dispatchers.IO) {
        require(config.apiKey.isNotBlank()) { "Ayarlar bölümünden sağlayıcı API anahtarı ekleyin." }
        require(config.model.isNotBlank()) { "Ayarlar bölümünden bir model seçin." }
        val url = if (config.kind == ProviderKind.ANTHROPIC) endpoint(config, "/v1/messages") else endpoint(config, "/v1/chat/completions")
        val prepared = history.map { message ->
            val attachment = message.attachment?.let { item -> readAttachment(context, item) }
            message to attachment
        }
        val body = if (config.kind == ProviderKind.ANTHROPIC) anthropicBody(config, prepared) else openAiBody(config, prepared)
        val response = request(config, url, "POST", body)
        val json = JSONObject(response)
        if (config.kind == ProviderKind.ANTHROPIC) {
            val content = json.optJSONArray("content") ?: JSONArray()
            (0 until content.length()).mapNotNull { content.optJSONObject(it)?.takeIf { part -> part.optString("type") == "text" }?.optString("text") }
                .joinToString("\n").ifBlank { "Model boş yanıt verdi." }
        } else {
            json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Model yanıtı boş veya desteklenmeyen biçimde.")
        }
    }

    private data class PreparedAttachment(val mime: String, val text: String?, val imageData: String?)

    private fun readAttachment(context: Context, attachment: Attachment): PreparedAttachment? {
        val uri = attachment.uri?.let(android.net.Uri::parse) ?: return null
        val mime = context.contentResolver.getType(uri)?.lowercase() ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(attachment.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val image = mime.startsWith("image/")
        val textual = mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/yaml", "application/x-yaml", "application/javascript", "application/x-sh", "application/toml") || attachment.name.substringAfterLast('.', "").lowercase() in setOf("kt", "java", "gradle", "kts", "md", "txt", "xml", "json", "yaml", "yml", "toml", "py", "js", "ts", "html", "css", "sh", "properties", "sql", "c", "h", "cpp", "rs", "go")
        if (!image && !textual) return PreparedAttachment(mime, "[${attachment.name}: Dosya türü metin olarak okunmadı.]", null)
        val limit = if (image) 5 * 1024 * 1024 else 256 * 1024
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit) { "${attachment.name} ${if (image) "5 MB" else "256 KB"} sınırını aşıyor." }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IllegalStateException("${attachment.name} dosyası okunamıyor. Dosya erişim iznini yeniden verin.")
        return if (image) PreparedAttachment(mime, null, Base64.encodeToString(bytes, Base64.NO_WRAP))
        else PreparedAttachment(mime, "[Dosya: ${attachment.name}]\n${String(bytes, Charsets.UTF_8)}", null)
    }

    private fun openAiBody(config: ProviderConfig, history: List<Pair<Message, PreparedAttachment?>>): JSONObject = JSONObject()
        .put("model", config.model)
        .put("messages", JSONArray().apply {
            history.forEach { (message, attachment) ->
                val parts = JSONArray()
                if (message.text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", message.text))
                attachment?.text?.let { parts.put(JSONObject().put("type", "text").put("text", it)) }
                attachment?.imageData?.let { parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${attachment.mime};base64,$it"))) }
                val content: Any = if (parts.length() == 0) message.text else parts
                put(JSONObject().put("role", if (message.user) "user" else "assistant").put("content", content))
            }
        })

    private fun anthropicBody(config: ProviderConfig, history: List<Pair<Message, PreparedAttachment?>>): JSONObject = JSONObject()
        .put("model", config.model).put("max_tokens", 2048)
        .put("messages", JSONArray().apply {
            history.forEach { (message, attachment) ->
                val parts = JSONArray()
                if (message.text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", message.text))
                attachment?.text?.let { parts.put(JSONObject().put("type", "text").put("text", it)) }
                attachment?.imageData?.let { parts.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", attachment.mime).put("data", it))) }
                val content: Any = if (parts.length() == 0) message.text else parts
                put(JSONObject().put("role", if (message.user) "user" else "assistant").put("content", content))
            }
        })

    private fun request(config: ProviderConfig, url: String, method: String, body: JSONObject? = null): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("Accept", "application/json")
            if (config.kind == ProviderKind.ANTHROPIC) {
                setRequestProperty("x-api-key", config.apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
            } else setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") ?: JSONObject(text).optString("message") }.getOrNull()
                throw IllegalStateException("Sağlayıcı HTTP $code${detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
            }
            text
        } finally { connection.disconnect() }
    }

    private fun endpoint(config: ProviderConfig, path: String): String = normalizeBaseUrl(config.baseUrl) + path
}

private fun normalizeBaseUrl(raw: String): String {
    val base = raw.trim().trimEnd('/')
    val parsed = try { URI(base) } catch (_: Exception) { throw IllegalArgumentException("API adresi geçerli bir HTTPS adresi olmalı.") }
    require(parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank() && parsed.userInfo == null && parsed.query == null && parsed.fragment == null) {
        "API adresi HTTPS olmalı; kullanıcı bilgisi, sorgu ve # bölümü içermemeli."
    }
    return when {
        base.endsWith("/v1", ignoreCase = true) -> base.dropLast(3)
        else -> base
    }.trimEnd('/')
}
