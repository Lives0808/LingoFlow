package dev.lingoflow.jvm

import dev.lingoflow.shared.domain.ChatEngine
import dev.lingoflow.shared.domain.EngineKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible chat completions.
 *
 * One implementation covers OpenAI, DeepSeek, Groq-style gateways and local
 * servers (Ollama, LM Studio, vLLM) — the user supplies the endpoint and their
 * own key, and the request goes straight from this device to that endpoint.
 */
class OpenAiCompatibleChat(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
    private val kind: EngineKind = EngineKind.OPENAI,
) : ChatEngine {

    override val id: String = if (kind == EngineKind.OFFLINE) "offline" else "model"
    override val label: String = "$model @ ${baseUrl.substringAfter("//").substringBefore('/')}"
    override val requiresKey: Boolean = !isLocal(baseUrl)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun complete(system: String, user: String, temperature: Double): String = withContext(Dispatchers.IO) {
        val body = buildString {
            append("{\"model\":").append(json(model))
            append(",\"temperature\":").append(temperature)
            append(",\"messages\":[")
            append("{\"role\":\"system\",\"content\":").append(json(system)).append("},")
            append("{\"role\":\"user\",\"content\":").append(json(user)).append("}]}")
        }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply {
                if (apiKey.isNotBlank()) header("authorization", "Bearer $apiKey")
                kind.takeIf { it == EngineKind.DEEPSEEK }?.let { header("x-source", "lingoflow") }
            }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${text.take(240)}")
            }
            extractContent(text) ?: error("Model returned no content")
        }
    }

    private fun extractContent(payload: String): String? {
        // Minimal extraction: find the first "content" value inside choices.
        val match = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(payload) ?: return null
        return unescapeJson(match.groupValues[1])
    }

    private fun isLocal(url: String): Boolean =
        url.contains("localhost") || url.contains("127.0.0.1") || url.contains("0.0.0.0") || url.contains("[::1]")

    companion object {
        fun json(value: String): String = buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }

        fun unescapeJson(value: String): String = buildString {
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '\\' && index + 1 < value.length) {
                    when (val next = value[index + 1]) {
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        '"' -> append('"')
                        '\\' -> append('\\')
                        'u' -> {
                            val hex = value.substring(index + 2, (index + 6).coerceAtMost(value.length))
                            append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            index += 4
                        }
                        else -> append(next)
                    }
                    index += 2
                    continue
                }
                append(char)
                index++
            }
        }
    }
}

/** Offline fallback that keeps the whole workflow usable without an API key. */
class OfflineChatEngineAdapter(private val delegate: dev.lingoflow.shared.domain.OfflineChatEngine = dev.lingoflow.shared.domain.OfflineChatEngine()) :
    ChatEngine by delegate
