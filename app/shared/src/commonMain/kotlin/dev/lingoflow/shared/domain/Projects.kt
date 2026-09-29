package dev.lingoflow.shared.domain

import dev.lingoflow.shared.core.util.Sha256

/**
 * Project scoping. Every translation project owns its terminology, parallel
 * corpus, vocabulary and documents — all of it stored as plain files on the
 * device, so projects are portable and never touch a server.
 */
data class TranslationProject(
    val id: String,
    val name: String,
    val sourceLang: String,
    val targetLang: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val note: String = "",
)

data class Term(
    val id: String,
    val source: String,
    val target: String,
    val note: String = "",
    /** Restrict the term to one locale pair, null = every target in the project. */
    val locale: String? = null,
    val caseSensitive: Boolean = false,
    val enabled: Boolean = true,
    val origin: String = "manual",
    /** How often the term was matched in the current project. */
    val hits: Int = 0,
) {
    val key: String get() = "${source.lowercase()}\u0000$target\u0000${locale ?: "*"}"
}

data class CorpusEntry(
    val id: String,
    val source: String,
    val target: String,
    val sourceLang: String,
    val targetLang: String,
    val projectId: String,
    val documentId: String = "",
    /** True when a human wrote/edited this translation. */
    val confirmed: Boolean = true,
    val createdAt: Long = 0,
    val hits: Int = 0,
)

data class VocabEntry(
    val id: String,
    val word: String,
    val phonetic: String = "",
    val meaning: String = "",
    val example: String = "",
    val sourceLang: String = "en",
    val projectId: String = "",
    val createdAt: Long = 0,
    val tags: List<String> = emptyList(),
)

enum class EngineKind(val label: String) {
    OFFLINE("Built-in offline"),
    OPENAI("OpenAI-compatible"),
    DEEPSEEK("DeepSeek"),
    CUSTOM("Custom endpoint"),
}

data class StylePreset(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val temperature: Double = 0.2,
)

/** Translation style presets (P2 feature, shipped with sane defaults). */
object StylePresets {
    val all: List<StylePreset> = listOf(
        StylePreset(
            id = "general",
            name = "通用翻译",
            description = "Natural, faithful translation for everyday text.",
            systemPrompt = "You are a professional translator. Translate faithfully and naturally.",
        ),
        StylePreset(
            id = "technical",
            name = "技术文档",
            description = "Keeps API names, code identifiers and CLI flags untouched.",
            systemPrompt = "You are a senior technical writer localising developer documentation. Never translate identifiers, CLI flags, file paths or code. Keep Markdown syntax intact.",
            temperature = 0.1,
        ),
        StylePreset(
            id = "oss",
            name = "开源 README",
            description = "GitHub-flavoured technical prose, badges and headings preserved.",
            systemPrompt = "You localise open-source README files. Keep badges, links, anchors and code fences unchanged; use concise developer-facing wording.",
            temperature = 0.1,
        ),
        StylePreset(
            id = "academic",
            name = "学术论文",
            description = "Formal academic register with precise terminology.",
            systemPrompt = "You translate academic papers. Use a formal register, keep citations, formulas and figures untouched, and prefer established field terminology.",
            temperature = 0.15,
        ),
        StylePreset(
            id = "literary",
            name = "文学翻译",
            description = "Preserves voice, rhythm and imagery over literal wording.",
            systemPrompt = "You translate literature. Preserve the author's voice, rhythm and imagery; prefer evocative wording over literal fidelity.",
            temperature = 0.5,
        ),
        StylePreset(
            id = "casual",
            name = "日常口语",
            description = "Conversational, short and friendly.",
            systemPrompt = "Translate conversationally, as a native speaker would say it out loud. Prefer short sentences.",
            temperature = 0.4,
        ),
        StylePreset(
            id = "business",
            name = "商务正式",
            description = "Polished business register for emails and proposals.",
            systemPrompt = "Translate business correspondence. Keep a polished, respectful and unambiguous register.",
            temperature = 0.2,
        ),
    )

    fun byId(id: String): StylePreset = all.firstOrNull { it.id == id } ?: all.first()
}

/** Polish/rewrite mode: not translation, but a related writing task. */
object PolishPresets {
    val all: List<StylePreset> = listOf(
        StylePreset(
            id = "rewrite",
            name = "英文改写",
            description = "Rewrites the source in clearer English.",
            systemPrompt = "Rewrite the text in clear, idiomatic English. Keep the meaning and the paragraph structure; return only the rewritten text.",
            temperature = 0.4,
        ),
        StylePreset(
            id = "formalize",
            name = "学术书面化",
            description = "Raises the register to formal academic English.",
            systemPrompt = "Rewrite the text in formal academic English: precise terminology, no contractions, no colloquialisms. Return only the rewritten text.",
            temperature = 0.2,
        ),
        StylePreset(
            id = "concise",
            name = "精简",
            description = "Cuts filler while keeping every fact.",
            systemPrompt = "Make the text as concise as possible without losing information. Return only the rewritten text.",
            temperature = 0.2,
        ),
    )
}

data class AppSettings(
    val engine: EngineKind = EngineKind.OFFLINE,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val stylePresetId: String = "general",
    /** Segments of previous context handed to the model for terminology consistency. */
    val contextWindow: Int = 6,
    val maxSegmentsPerRequest: Int = 8,
    val temperature: Double = 0.2,
    /** Ask the model for a short explanation per segment (hover card in the UI). */
    val explainSegments: Boolean = true,
    val offlineOnly: Boolean = true,
    val darkTheme: Boolean = true,
    val ocrLanguage: String = "auto",
)

/** Result of an OCR run. */
data class OcrResult(val text: String, val lines: List<String> = emptyList()) {
    val isEmpty: Boolean get() = text.isBlank()
}

/** Platform hook for camera/photo OCR (Android: ML Kit, desktop: optional Tesseract). */
interface OcrEngine {
    val available: Boolean
    val description: String

    suspend fun recognize(imageBytes: ByteArray): OcrResult
}

/** Hook for an "explain this segment" request; the LLM engines implement it. */
interface ExplanationEngine {
    suspend fun explain(segment: String, sourceLang: String, targetLang: String): String?
}

fun newId(prefix: String, seed: String): String = "$prefix-" + Sha256.hex(seed + System.currentTimeMillis().toString()).take(10)
