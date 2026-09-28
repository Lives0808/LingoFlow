package dev.lingoflow.app.data

import dev.lingoflow.app.core.catalog.JsonishParser
import dev.lingoflow.app.core.catalog.JsonValue
import dev.lingoflow.app.core.translate.EngineContext
import dev.lingoflow.app.core.translate.TranslateItem
import dev.lingoflow.app.core.translate.TranslateOutput
import dev.lingoflow.app.core.translate.TranslationEngine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Shared HTTP plumbing with conservative timeouts. */
private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

private val jsonMedia = "application/json; charset=utf-8".toMediaType()

private fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
    val request = Request.Builder()
        .url(url)
        .post(body.toRequestBody(jsonMedia))
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()
    client.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(200)}")
        return text
    }
}

/**
 * Any OpenAI-compatible chat completions endpoint — including a local Ollama or
 * LM Studio server, which keeps everything on the same network.
 */
class OpenAiEngine(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
) : TranslationEngine {
    override val id: String = "openai"
    override val label: String = "OpenAI-compatible endpoint"
    override val network: Boolean = true

    override fun translate(items: List<TranslateItem>, context: EngineContext): List<TranslateOutput> {
        val results = mutableListOf<TranslateOutput>()
        val byLocale = items.groupBy { it.to }
        for ((locale, batch) in byLocale) {
            val payload = batch.joinToString(",") { item ->
                """{"id":${json(item.id)},"text":${json(item.text)}${item.maxChars?.let { ""","maxChars":$it""" } ?: ""}}"""
            }
            val glossaryHint = context.glossary.joinToString("; ") { "${it.source} → ${it.target}" }
            val system = buildString {
                append("You are a senior software localisation engineer. Translate UI strings from ${batch.firstOrNull()?.from ?: "en"} to $locale. ")
                append("Preserve placeholders ({name}, {{count}}, %s, ICU plural blocks) and HTML tags exactly. ")
                append("Keep the same number of line breaks. Reply with JSON only: {\"translations\":[{\"id\":\"…\",\"text\":\"…\"}]}. ")
                if (glossaryHint.isNotEmpty()) append("Glossary (use these): $glossaryHint.")
            }
            val body = """
                {"model":${json(model)},"temperature":0.2,
                 "messages":[{"role":"system","content":${json(system)}},
                             {"role":"user","content":${json("""[${payload}]""")}}]}
            """.trimIndent()
            val response = postJson(
                "${baseUrl.trimEnd('/')}/chat/completions",
                body,
                buildMap {
                    put("content-type", "application/json")
                    if (apiKey.isNotBlank()) put("authorization", "Bearer $apiKey")
                },
            )
            val translated = parseTranslations(response)
            for (item in batch) {
                val text = translated[item.id]
                results += TranslateOutput(item.id, text ?: item.text, id, if (text != null) 0.85 else 0.1)
            }
        }
        return results
    }

    private fun parseTranslations(response: String): Map<String, String> {
        val root = runCatching { JsonishParser(response).parse().value }.getOrNull() ?: return emptyMap()
        val content = (((root as? JsonValue.Obj)?.get("choices") as? JsonValue.Arr)?.items?.firstOrNull() as? JsonValue.Obj)
            ?.get("message")?.let { it as? JsonValue.Obj }?.get("content")?.let { (it as? JsonValue.Str)?.value }
            ?: return emptyMap()
        val cleaned = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")
        val parsed = runCatching { JsonishParser(cleaned).parse().value }.getOrNull() ?: return emptyMap()
        val list = ((parsed as? JsonValue.Obj)?.get("translations") as? JsonValue.Arr)?.items ?: (parsed as? JsonValue.Arr)?.items
        return list.orEmpty().mapNotNull { entry ->
            val object0 = entry as? JsonValue.Obj ?: return@mapNotNull null
            val id = (object0.get("id") as? JsonValue.Str)?.value ?: return@mapNotNull null
            val text = (object0.get("text") as? JsonValue.Str)?.value ?: return@mapNotNull null
            id to text
        }.toMap()
    }

    private fun json(value: String): String = buildString {
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
}

/** Self-hosted LibreTranslate: the privacy-preserving machine translation option. */
class LibreTranslateEngine(private val url: String, private val apiKey: String = "") : TranslationEngine {
    override val id: String = "libretranslate"
    override val label: String = "LibreTranslate"
    override val network: Boolean = true

    override fun translate(items: List<TranslateItem>, context: EngineContext): List<TranslateOutput> {
        val results = mutableListOf<TranslateOutput>()
        for ((pair, batch) in items.groupBy { "${it.from}|${it.to}" }) {
            val (from, to) = pair.split("|")
            val texts = batch.joinToString(",") { jsonQuote(it.text) }
            val body = """{"q":[$texts],"source":"${from.substringBefore('-')}","target":"${normalize(to)}","format":"text"}"""
            val response = postJson(
                "${url.trimEnd('/')}/translate",
                body,
                if (apiKey.isBlank()) mapOf("content-type" to "application/json") else mapOf("content-type" to "application/json", "authorization" to "Bearer $apiKey"),
            )
            val parsed = runCatching { JsonishParser(response).parse().value }.getOrNull()
            val translated = ((parsed as? JsonValue.Obj)?.get("translatedText") as? JsonValue.Arr)?.items
                .orEmpty().mapNotNull { (it as? JsonValue.Str)?.value }
            batch.forEachIndexed { index, item ->
                val text = translated.getOrNull(index)
                results += TranslateOutput(item.id, text ?: item.text, id, if (text != null) 0.8 else 0.1)
            }
        }
        return results
    }

    private fun normalize(locale: String): String = when {
        locale.startsWith("zh", true) && locale.contains("TW", true) -> "zt"
        locale.startsWith("zh", true) -> "zh"
        else -> locale.substringBefore('-').substringBefore('_')
    }

    private fun jsonQuote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(char)
            }
        }
        append('"')
    }
}

/** Template driven custom endpoint for in-house services. */
class CustomHttpEngine(
    private val url: String,
    private val bodyTemplate: String,
    private val responsePath: String,
    private val apiKey: String = "",
) : TranslationEngine {
    override val id: String = "custom"
    override val label: String = "Custom HTTP endpoint"
    override val network: Boolean = true

    override fun translate(items: List<TranslateItem>, context: EngineContext): List<TranslateOutput> = items.map { item ->
        val body = bodyTemplate
            .replace("{{json.text}}", jsonQuote(item.text))
            .replace("{{json.source}}", jsonQuote(item.from))
            .replace("{{json.target}}", jsonQuote(item.to))
            .replace("{{json.key}}", jsonQuote(item.key))
            .replace("{{apiKey}}", apiKey)
        val response = postJson(url, body)
        val parsed = runCatching { JsonishParser(response).parse().value }.getOrNull()
        val text = readPath(parsed, responsePath)
        TranslateOutput(item.id, text ?: item.text, id, if (text != null) 0.8 else 0.1)
    }

    private fun readPath(value: JsonValue?, path: String): String? {
        var cursor: JsonValue? = value
        for (segment in path.split('.')) {
            cursor = when (val node = cursor) {
                is JsonValue.Obj -> node.get(segment)
                is JsonValue.Arr -> node.items.getOrNull(segment.toIntOrNull() ?: -1)
                else -> null
            }
            if (cursor == null) return null
        }
        return (cursor as? JsonValue.Str)?.value
    }

    private fun jsonQuote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(char)
            }
        }
        append('"')
    }
}
