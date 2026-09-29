package dev.lingoflow.shared.domain

import dev.lingoflow.shared.domain.doc.Document
import kotlin.math.min

/**
 * Terminology: extraction + forced matching.
 *
 * Extraction runs entirely on-device: candidate terms are capitalised or
 * repeated noun phrases with a frequency score, so "join the checklist" style
 * sentence starts never win over acronyms or repeated product names.
 */
object TermExtractor {

    private val stopwords = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "by", "for", "with", "from", "is", "are",
        "was", "were", "be", "been", "you", "your", "we", "our", "it", "its", "this", "that", "these", "those",
        "as", "if", "then", "than", "so", "not", "no", "yes", "can", "will", "would", "should", "could", "do",
        "does", "did", "has", "have", "but", "also", "very", "more", "most", "such", "when", "where", "which",
        "how", "what", "who", "why", "all", "any", "some", "each", "other", "into", "over", "under", "only",
    )

    data class Candidate(val text: String, val count: Int, val score: Double, val kind: Kind) {
        enum class Kind { ACRONYM, PHRASE, WORD, CJK }
    }

    fun extract(document: Document, minCount: Int = 2, existing: Set<String> = emptySet(), limit: Int = 60): List<Candidate> {
        val text = document.translatableSegments.joinToString("\n") { it.source }
        val counts = LinkedHashMap<String, Int>()
        val kinds = LinkedHashMap<String, Candidate.Kind>()

        // 1) Capitalised phrases up to four words ("Kotlin Multiplatform", "API key").
        Regex("\\b([A-Z][\\p{L}\\p{N}+#.-]*(?:\\s+(?:of|the|and)?\\s*[A-Z][\\p{L}\\p{N}+#.-]*){0,3})")
            .findAll(text)
            .forEach { match ->
                val phrase = match.value.trim()
                if (phrase.length < 3) return@forEach
                val words = phrase.split(Regex("\\s+"))
                val hasAcronym = phrase.length in 2..6 && phrase.all { !it.isLetter() || it.isUpperCase() }
                val firstWord = words.first().lowercase()
                if (words.size == 1 && (firstWord in stopwords || words[0].length < 3)) return@forEach
                if (words.all { it.lowercase() in stopwords }) return@forEach
                // Sentence starts are noisy: only keep them when the term repeats anyway.
                counts[phrase] = (counts[phrase] ?: 0) + 1
                kinds[phrase] = when {
                    hasAcronym -> Candidate.Kind.ACRONYM
                    words.size > 1 -> Candidate.Kind.PHRASE
                    else -> Candidate.Kind.WORD
                }
            }

        // 2) CJK terms: repeated 2-4 character sequences.
        Regex("[\\p{Script=Han}\\p{Script=Kana}]{2,6}").findAll(text).forEach { match ->
            val value = match.value
            if (value.length < 2) return@forEach
            counts[value] = (counts[value] ?: 0) + 1
            kinds.putIfAbsent(value, Candidate.Kind.CJK)
        }

        return counts.entries
            .asSequence()
            .filter { (term, count) -> count >= minCount && term.lowercase() !in existing && existing.none { it.equals(term, true) } }
            .map { (term, count) ->
                val kind = kinds[term] ?: Candidate.Kind.WORD
                Candidate(
                    text = term,
                    count = count,
                    score = score(term, count, kind),
                    kind = kind,
                )
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    private fun score(term: String, count: Int, kind: Candidate.Kind): Double {
        val lengthBonus = min(term.length, 24) / 24.0
        val kindBonus = when (kind) {
            Candidate.Kind.ACRONYM -> 0.35
            Candidate.Kind.PHRASE -> 0.25
            Candidate.Kind.CJK -> 0.2
            Candidate.Kind.WORD -> 0.1
        }
        return count * 1.0 + lengthBonus + kindBonus
    }
}

/** Forced terminology matching used before engine calls and in the UI highlight. */
object TermMatcher {

    fun matches(source: String, terms: List<Term>, targetLocale: String? = null): List<Term> = terms.filter { term ->
        term.enabled && (term.locale == null || term.locale == targetLocale) && contains(source, term)
    }

    fun contains(source: String, term: Term): Boolean {
        if (term.source.isBlank()) return false
        return if (term.caseSensitive) {
            source.contains(term.source)
        } else {
            source.contains(term.source, ignoreCase = true)
        }
    }

    /** Terms compiled into a regex for shielding (see core.RuleEngine.protect). */
    fun toShieldTerms(terms: List<Term>, targetLocale: String?): List<dev.lingoflow.shared.core.rules.GlossaryTerm> =
        terms.filter { it.enabled && (it.locale == null || it.locale == targetLocale) }
            .map {
                dev.lingoflow.shared.core.rules.GlossaryTerm(
                    locale = it.locale,
                    source = it.source,
                    target = it.target,
                    priority = 70,
                    matchCase = it.caseSensitive,
                    wholeWord = true,
                    origin = it.origin,
                )
            }
}

/** Parallel corpus lookup: reuse the translator's own past decisions. */
object CorpusMatcher {

    data class Hit(val entry: CorpusEntry, val score: Double)

    fun best(source: String, entries: List<CorpusEntry>, threshold: Double = 0.75): Hit? {
        var best: Hit? = null
        for (entry in entries) {
            if (entry.source.isBlank() || entry.target.isBlank()) continue
            val score = dev.lingoflow.shared.core.tm.Fuzzy.score(source, entry.source)
            if (score < threshold) continue
            if (best == null || score > best.score) best = Hit(entry, score)
        }
        return best
    }

    /**
     * Builds a memory block for the prompt: the closest confirmed translations,
     * so the model imitates the user's own wording instead of inventing a new one.
     */
    fun memoryBlock(source: String, entries: List<CorpusEntry>, limit: Int = 5): String {
        val scored = entries
            .asSequence()
            .filter { it.confirmed && it.source.isNotBlank() && it.target.isNotBlank() && it.source != source }
            .map { it to dev.lingoflow.shared.core.tm.Fuzzy.score(source, it.source) }
            .filter { (_, score) -> score >= 0.4 }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .toList()
        if (scored.isEmpty()) return ""
        return buildString {
            append("The translator already approved these pairs — reuse their terminology and phrasing:\n")
            for ((entry, score) in scored) {
                append("- [").append((score * 100).toInt()).append("%] ")
                    .append(entry.source.replace("\n", " ").take(160))
                    .append("  ⇒  ")
                    .append(entry.target.replace("\n", " ").take(160))
                    .append('\n')
            }
        }
    }
}
