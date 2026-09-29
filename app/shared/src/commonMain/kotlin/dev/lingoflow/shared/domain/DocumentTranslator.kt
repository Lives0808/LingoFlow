package dev.lingoflow.shared.domain

import dev.lingoflow.shared.core.translate.EngineContext
import dev.lingoflow.shared.core.translate.OfflineEngine
import dev.lingoflow.shared.core.translate.TranslateItem
import dev.lingoflow.shared.core.translate.TranslationEngine
import dev.lingoflow.shared.core.rules.RuleEngine
import dev.lingoflow.shared.domain.doc.BlockKind
import dev.lingoflow.shared.domain.doc.Document
import dev.lingoflow.shared.domain.doc.Segment

/**
 * Chat-style model access. LLM engines implement this; the offline engine offers
 * a deterministic fallback so the whole workflow is usable without a key.
 */
interface ChatEngine {
    val id: String
    val label: String
    val requiresKey: Boolean

    suspend fun complete(system: String, user: String, temperature: Double): String
}

/** Wraps the ported offline engine so documents work with zero configuration. */
class OfflineChatEngine : ChatEngine {
    override val id: String = "offline"
    override val label: String = "Built-in offline"
    override val requiresKey: Boolean = false

    private val engine: TranslationEngine = OfflineEngine()

    override suspend fun complete(system: String, user: String, temperature: Double): String {
        // The prompt already carries "SOURCE: …" lines; translate each one.
        val sources = user.lineSequence()
            .filter { it.startsWith("SOURCE:") }
            .map { it.removePrefix("SOURCE:").trim() }
            .toList()
        if (sources.isEmpty()) return user
        val target = Regex("TARGET LANGUAGE:\\s*(\\S+)").find(system)?.groupValues?.get(1) ?: "zh-CN"
        val outputs = engine.translate(
            sources.mapIndexed { index, text -> TranslateItem(id = index.toString(), key = "", text = text, from = "en", to = target) },
            EngineContext(),
        )
        return outputs.joinToString("\n") { "${it.id}: ${it.text}" }
    }
}

data class TranslateOptions(
    val stylePreset: StylePreset = StylePresets.byId("general"),
    val contextWindow: Int = 6,
    val batchSize: Int = 8,
    val explain: Boolean = false,
    val reuseCorpus: Boolean = true,
    val corpusThreshold: Double = 0.85,
    val skipLocked: Boolean = true,
)

data class TranslateProgress(
    val done: Int,
    val total: Int,
    val message: String = "",
)

data class TranslateReport(
    val translated: Int,
    val fromCorpus: Int,
    val skipped: Int,
    val errors: List<String>,
)

/**
 * Document translation with context memory.
 *
 * For every batch the prompt receives:
 *  - the project glossary (forced terminology),
 *  - the closest entries of the user's own parallel corpus,
 *  - the previous segments' source *and* translation (context memory), so the
 *    model keeps names, tone and pronouns consistent across the document.
 */
class DocumentTranslator(
    private val chat: ChatEngine,
    private val terms: List<Term>,
    private val corpus: List<CorpusEntry>,
    private val settings: AppSettings,
    private val options: TranslateOptions = TranslateOptions(),
) {
    /**
     * Translates the missing segments of [document].
     * [onSegment] receives each finished segment so the UI can stream results.
     */
    suspend fun translate(
        document: Document,
        onProgress: (TranslateProgress) -> Unit = {},
        onSegment: (Segment) -> Unit = {},
        onCorpusHit: (Segment, CorpusEntry) -> Unit = { _, _ -> },
    ): Pair<Document, TranslateReport> {
        val targets = document.segments.filter { segment ->
            segment.translatable &&
                segment.target.isBlank() &&
                !(options.skipLocked && segment.locked)
        }
        if (targets.isEmpty()) {
            return document to TranslateReport(0, 0, document.segments.size, emptyList())
        }

        var working = document
        val errors = mutableListOf<String>()
        var translated = 0
        var fromCorpus = 0
        val style = options.stylePreset

        // 1) Corpus first: the user's own wording beats any model.
        val remaining = mutableListOf<Segment>()
        for (segment in targets) {
            val hit = if (options.reuseCorpus) CorpusMatcher.best(segment.source, corpus, options.corpusThreshold) else null
            if (hit != null) {
                val updated = segment.copy(target = hit.entry.target, origin = "corpus", matchedTerms = matchedNames(segment.source))
                working = working.updateSegment(segment.id, { updated })
                onSegment(updated)
                onCorpusHit(updated, hit.entry)
                fromCorpus++
                onProgress(TranslateProgress(fromCorpus + translated, targets.size, "corpus"))
            } else {
                remaining += segment
            }
        }

        // 2) Engine for the rest, in batches that share context.
        val batchSize = options.batchSize.coerceAtLeast(1)
        for (batchStart in remaining.indices step batchSize) {
            val batch = remaining.subList(batchStart, minOf(batchStart + batchSize, remaining.size))
            val context = contextBlock(working, batch.first())
            val output = try {
                val prompt = buildPrompt(working, batch, context)
                val answer = chat.complete(prompt.system, prompt.user, style.temperature)
                parseAnswer(answer, batch)
            } catch (error: Exception) {
                errors += error.message ?: "engine error"
                emptyMap()
            }
            for (segment in batch) {
                val text = output[segment.id]
                if (text.isNullOrBlank()) {
                    errors += "no translation for segment ${segment.id}"
                    continue
                }
                val protected = protectTerms(segment.source, text)
                val updated = segment.copy(target = protected, origin = chat.id, matchedTerms = matchedNames(segment.source))
                working = working.updateSegment(segment.id, { updated })
                onSegment(updated)
                translated++
                onProgress(TranslateProgress(fromCorpus + translated, targets.size, chat.label))
            }
        }

        val skipped = document.segments.count { it.translatable && it.target.isNotBlank() }
        return working to TranslateReport(translated, fromCorpus, skipped, errors)
    }

    /** Polish mode: rewrite segments with a writing prompt instead of translating. */
    suspend fun polish(
        document: Document,
        preset: StylePreset,
        onProgress: (TranslateProgress) -> Unit = {},
        onSegment: (Segment) -> Unit = {},
    ): Pair<Document, TranslateReport> {
        val targets = document.segments.filter { it.translatable && !it.locked }
        var working = document
        var done = 0
        val errors = mutableListOf<String>()
        for (segment in targets) {
            try {
                val system = buildString {
                    append("SOURCE LANGUAGE: ").append(document.sourceLang).append('\n')
                    append(preset.systemPrompt)
                }
                val answer = chat.complete(system, segment.source, preset.temperature).trim()
                if (answer.isNotBlank()) {
                    val updated = segment.copy(target = answer, origin = "polish:${preset.id}")
                    working = working.updateSegment(segment.id, { updated })
                    onSegment(updated)
                    done++
                }
            } catch (error: Exception) {
                errors += error.message ?: "engine error"
            }
            onProgress(TranslateProgress(done, targets.size, preset.name))
        }
        return working to TranslateReport(done, 0, document.segments.size - targets.size, errors)
    }

    /** Hover/tap explanation for one segment (feature: 鼠标悬浮查看AI释义). */
    suspend fun explain(document: Document, segment: Segment): String? = try {
        val system = buildString {
            append("You are a bilingual glossary assistant. Explain the following ")
                .append(document.sourceLang).append(" text in ").append(document.targetLang)
                .append(": list the key terms, a one-line meaning, and any idiom. Max 4 short lines. Plain text only.")
        }
        chat.complete(system, segment.source, 0.2).trim().ifBlank { null }
    } catch (error: Exception) {
        null
    }

    private data class Prompt(val system: String, val user: String)

    private fun buildPrompt(document: Document, batch: List<Segment>, context: String): Prompt {
        val glossary = TermMatcher.matches(document.translatableSegments.joinToString("\n") { it.source }, terms)
            .distinctBy { it.key }
        val glossaryBlock = if (glossary.isEmpty()) "" else buildString {
            append("GLOSSARY (must be used exactly):\n")
            glossary.take(60).forEach { append("- ").append(it.source).append(" ⇒ ").append(it.target).append('\n') }
        }
        val memoryBlock = CorpusMatcher.memoryBlock(batch.first().source, corpus)
        val system = buildString {
            append("SOURCE LANGUAGE: ").append(document.sourceLang).append('\n')
            append("TARGET LANGUAGE: ").append(document.targetLang).append('\n')
            append(options.stylePreset.systemPrompt).append('\n')
            append("Rules:\n")
            append("- Translate only the text after SOURCE: on each line.\n")
            append("- Keep Markdown, placeholders ({name}, %s, {{count}}) and code identifiers unchanged.\n")
            append("- Keep terminology consistent with the CONTEXT block and previous segments.\n")
            append("- Reply with one line per segment formatted exactly as `<segment-id>: <translation>`; no extra lines.\n")
            if (glossaryBlock.isNotEmpty()) append(glossaryBlock)
            if (memoryBlock.isNotEmpty()) append(memoryBlock)
            if (context.isNotEmpty()) append("CONTEXT (already translated, keep names/pronouns consistent):\n").append(context)
        }
        val user = buildString {
            append("Translate these segments:\n")
            batch.forEach { segment ->
                append("SOURCE: ").append(segment.source.replace("\n", "\\n")).append('\n')
                append("ID: ").append(segment.id).append('\n')
            }
            append("Reply with `<id>: <translation>` lines only.")
        }
        return Prompt(system, user)
    }

    private fun contextBlock(document: Document, first: Segment): String {
        if (options.contextWindow <= 0) return ""
        val index = document.segments.indexOfFirst { it.id == first.id }
        if (index <= 0) return ""
        val start = maxOf(0, index - options.contextWindow)
        return document.segments.subList(start, index)
            .filter { it.translatable && it.target.isNotBlank() }
            .joinToString("\n") { "- ${it.source.replace("\n", " ").take(120)} ⇒ ${it.target.replace("\n", " ").take(120)}" }
    }

    private fun parseAnswer(answer: String, batch: List<Segment>): Map<Int, String> {
        val result = LinkedHashMap<Int, String>()
        val byId = batch.associateBy { it.id }
        answer.lineSequence().forEach { line ->
            val match = Regex("^\\s*(\\d+)\\s*[:：]\\s*(.+)$").find(line) ?: return@forEach
            val id = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (!byId.containsKey(id)) return@forEach
            val text = match.groupValues[2].trim().replace("\\n", "\n")
            if (text.isNotEmpty()) result[id] = text
        }
        // Some models answer without ids when there is a single segment.
        if (result.isEmpty() && batch.size == 1) {
            val cleaned = answer.trim().lines().firstOrNull()?.trim().orEmpty()
            if (cleaned.isNotEmpty() && !cleaned.startsWith("SOURCE:")) result[batch.first().id] = cleaned
        }
        return result
    }

    /** Forced terminology: after the model answers, restore every glossary term. */
    private fun protectTerms(source: String, translation: String): String {
        val relevant = TermMatcher.matches(source, terms)
        if (relevant.isEmpty()) return translation
        var text = translation
        for (term in relevant) {
            if (term.target.isBlank()) continue
            val shield = RuleEngine.protect(source, TermMatcher.toShieldTerms(listOf(term), null), emptyList())
            if (shield.tokens.isEmpty()) continue
            // The model translated the term itself; replace its translation with ours.
            val translatedTerm = shield.tokens.values.first().target
            if (translatedTerm.isNotBlank() && !text.contains(term.target)) {
                text = text.replace(translatedTerm, term.target, ignoreCase = !term.caseSensitive)
            }
        }
        return text
    }

    private fun matchedNames(source: String): List<String> =
        TermMatcher.matches(source, terms).map { it.source }
}

/** Segment helpers shared by the UI. */
object SegmentOps {
    fun highlightTerms(segment: Segment, terms: List<Term>): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        for (term in terms) {
            var index = segment.source.indexOf(term.source, ignoreCase = !term.caseSensitive)
            while (index >= 0) {
                ranges += index to index + term.source.length
                index = segment.source.indexOf(term.source, index + term.source.length, ignoreCase = !term.caseSensitive)
            }
        }
        return ranges.sortedBy { it.first }
    }

    fun wordCount(segment: Segment): Int = Regex("[\\p{L}\\p{N}]+").findAll(segment.source).count()

    fun isStructural(segment: Segment): Boolean = segment.kind == BlockKind.CODE
}
