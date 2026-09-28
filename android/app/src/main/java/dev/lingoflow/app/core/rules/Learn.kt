package dev.lingoflow.app.core.rules

import dev.lingoflow.app.core.model.RuleKind
import dev.lingoflow.app.core.model.RuleRow
import java.security.MessageDigest

/**
 * Learning from history — the "防返工" core, ported from the CLI:
 * reviewer edits become correction rules, approved copy becomes style rules and
 * repeated term pairs become glossary candidates.
 */
object Learn {

    data class Edit(val machine: String, val human: String, val source: String, val locale: String)

    data class Change(val from: String, val to: String)

    private val stopwords = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "by", "for", "with", "from", "is", "are",
        "was", "were", "be", "been", "you", "your", "we", "our", "it", "its", "this", "that", "these", "those",
        "as", "if", "then", "than", "so", "not", "no", "yes", "new", "old", "more", "less", "all", "any", "some",
        "can", "will", "would", "should", "could", "do", "does", "did", "has", "have", "der", "die", "das", "und",
        "oder", "ein", "eine", "le", "les", "des", "du", "et", "ou", "dans", "pour", "avec", "sur",
    )

    /** Word level diff: equal-length regions become individual reusable changes. */
    fun wordDiff(before: String, after: String): List<Change> {
        val a = tokenize(before)
        val b = tokenize(after)
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size - 1
        var endB = b.size - 1
        while (endA >= start && endB >= start && a[endA] == b[endB]) {
            endA--
            endB--
        }
        val removed = a.subList(start, endA + 1)
        val added = b.subList(start, endB + 1)
        return when {
            removed.isEmpty() && added.isEmpty() -> emptyList()
            removed.size == added.size -> removed.indices.mapNotNull { index ->
                val from = removed[index]
                val to = added[index]
                if (from == to) null else Change(from, to)
            }
            else -> listOf(Change(removed.joinToString(" "), added.joinToString(" ")))
        }
    }

    fun learnCorrectionsFromEdits(edits: List<Edit>, minOccurrences: Int = 2): List<RuleRow> {
        data class Count(var from: String, var to: String, var locale: String, var count: Int)

        val counts = LinkedHashMap<String, Count>()
        for (edit in edits) {
            for (change in wordDiff(edit.machine, edit.human)) {
                val from = change.from.trim()
                val to = change.to.trim()
                if (from.isEmpty() || to.isEmpty() || from.length > 60 || to.length > 60) continue
                if (Regex("\\{[^}]*\\}").containsMatchIn(from) || Regex("\\{[^}]*\\}").containsMatchIn(to)) continue
                if (from.none { it.isLetter() } || to.none { it.isLetter() }) continue
                val id = "${edit.locale}\u0000$from\u0000$to"
                val entry = counts.getOrPut(id) { Count(from, to, edit.locale, 0) }
                entry.count++
            }
        }
        return counts.values.map { entry ->
            val cjk = isCjk(entry.locale)
            val pattern = if (cjk) {
                Regex.escape(entry.from)
            } else {
                "(?<![\\p{L}\\p{N}])${Regex.escape(entry.from)}(?![\\p{L}\\p{N}])"
            }
            val enabled = entry.count >= minOccurrences
            RuleRow(
                kind = RuleKind.CORRECTION,
                locale = entry.locale,
                pattern = pattern,
                value = """{"to":${jsonString(entry.to)},"regex":true}""",
                priority = 70,
                enabled = enabled,
                origin = "learned",
                confidence = if (enabled) 0.9 else 0.5,
                note = if (enabled) "reviewer applied this ${entry.count} times" else "seen once — enable after a second review",
            )
        }.sortedByDescending { it.confidence }
    }

    /** Detects the house style of already translated copy. */
    fun learnStyles(entries: List<Triple<String, String, String>>): List<RuleRow> {
        val rules = mutableListOf<RuleRow>()
        val byLocale = entries.groupBy { it.third }
        for ((locale, samples) in byLocale) {
            val values = samples.map { it.first }.filter { it.isNotBlank() }
            if (values.size < 8) continue

            val short = values.filter { it.length <= 40 && tokenize(it).size <= 6 }
            if (short.size >= 8) {
                val withPeriod = short.count { it.trimEnd().endsWith(".") || it.trimEnd().endsWith("。") }
                if (withPeriod.toDouble() / short.size <= 0.1) {
                    rules += styleRule(locale, RuleSet.STYLE_TRAILING, "never", 1 - withPeriod.toDouble() / short.size)
                } else if (withPeriod.toDouble() / short.size >= 0.8) {
                    rules += styleRule(locale, RuleSet.STYLE_TRAILING, "always", withPeriod.toDouble() / short.size)
                }
            }
            val unicodeEllipsis = values.count { it.contains("…") }
            val asciiEllipsis = values.count { it.contains("...") }
            if (unicodeEllipsis + asciiEllipsis >= 3) {
                when {
                    unicodeEllipsis >= asciiEllipsis * 2 -> rules += styleRule(locale, RuleSet.STYLE_ELLIPSIS, "unicode", 0.9)
                    asciiEllipsis > unicodeEllipsis -> rules += styleRule(locale, RuleSet.STYLE_ELLIPSIS, "ascii", 0.9)
                }
            }
            if (isCjk(locale)) {
                val fullwidth = values.count { Regex("[，。！？：；]").containsMatchIn(it) }
                val halfwidth = values.count { Regex("[,.!?]").containsMatchIn(it) }
                if (fullwidth >= 3 && fullwidth >= halfwidth * 2) rules += styleRule(locale, RuleSet.STYLE_CJK_FULLWIDTH, "true", 0.9)
                val spaced = values.count { Regex("[\\p{Script=Han}]\\s[\\p{L}\\p{N}]|[\\p{L}\\p{N}]\\s[\\p{Script=Han}]").containsMatchIn(it) }
                when {
                    spaced.toDouble() / values.size >= 0.6 -> rules += styleRule(locale, RuleSet.STYLE_CJK_LATIN_SPACE, "true", spaced.toDouble() / values.size)
                    spaced.toDouble() / values.size <= 0.1 -> rules += styleRule(locale, RuleSet.STYLE_CJK_LATIN_SPACE, "false", 0.9)
                }
            }
            if (locale.lowercase().startsWith("zh")) {
                val formal = values.count { it.contains("您") }
                val informal = values.count { it.contains("你") }
                if (formal + informal >= 5) {
                    when {
                        formal >= informal * 2 -> rules += styleRule(locale, RuleSet.STYLE_FORMALITY, "formal", 0.9)
                        informal >= formal * 2 -> rules += styleRule(locale, RuleSet.STYLE_FORMALITY, "informal", 0.9)
                    }
                }
            }
        }
        return rules
    }

    /** Mines glossary candidates from trusted translation pairs. */
    fun learnGlossary(
        pairs: List<Pair<String, String>>,
        targetLocale: String,
        sourceLocale: String,
        minOccurrences: Int = 3,
        minConsistency: Double = 0.7,
    ): List<RuleRow> {
        val usable = pairs.filter { it.first.isNotBlank() && it.second.isNotBlank() }
        if (usable.size < minOccurrences) return emptyList()
        val sourceCjk = isCjk(sourceLocale)
        val targetCjk = isCjk(targetLocale)

        val candidates = LinkedHashSet<String>()
        for ((source, _) in usable) {
            val normalized = if (sourceCjk) source else source.lowercase()
            candidates += ngrams(normalized, if (sourceCjk) listOf(2, 3, 4) else listOf(1, 2, 3))
        }

        val results = mutableListOf<RuleRow>()
        val maxPairs = maxOf(3, (usable.size * 0.6).toInt())
        for (candidate in candidates) {
            if (isStopword(candidate, sourceCjk)) continue
            val containing = usable.filter { pair ->
                val text = if (sourceCjk) pair.first else pair.first.lowercase()
                containsTerm(text, candidate, sourceCjk)
            }
            if (containing.size < minOccurrences || containing.size > maxPairs) continue

            val targetCounts = LinkedHashMap<String, Int>()
            for ((_, target) in containing) {
                val normalized = if (targetCjk) target else target.lowercase()
                ngrams(normalized, if (targetCjk) listOf(2, 3, 4) else listOf(1, 2, 3)).distinct().forEach { ngram ->
                    targetCounts[ngram] = (targetCounts[ngram] ?: 0) + 1
                }
            }
            val best = targetCounts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
                .firstOrNull() ?: continue
            val consistency = best.value.toDouble() / containing.size
            if (consistency < minConsistency) continue
            if (best.key.equals(candidate, ignoreCase = true)) continue
            val confidence = minOf(0.98, consistency * minOf(1.0, 0.7 + best.value * 0.1))
            results += RuleRow(
                kind = RuleKind.GLOSSARY,
                locale = targetLocale,
                pattern = candidate,
                value = """{"target":${jsonString(best.key)}}""",
                priority = 60,
                enabled = consistency >= 0.85 && best.value >= minOccurrences,
                origin = "learned",
                confidence = (confidence * 1000).toInt() / 1000.0,
                note = "observed ${best.value}/${containing.size} times in history",
            )
        }
        // Prefer longer, more confident terms; drop overlapping weaker ones.
        val sorted = results.sortedWith(compareByDescending<RuleRow> { it.confidence }.thenByDescending { it.pattern.length })
        val kept = mutableListOf<RuleRow>()
        for (rule in sorted) {
            val conflict = kept.firstOrNull {
                it.locale == rule.locale && (it.pattern.contains(rule.pattern, true) || rule.pattern.contains(it.pattern, true))
            }
            if (conflict != null) {
                if (conflict.value == rule.value) continue
                if (conflict.pattern.length >= rule.pattern.length) continue
            }
            kept += rule
            if (kept.size >= 200) break
        }
        return kept
    }

    fun sourceHash(sourceLocale: String, source: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest("$sourceLocale\u0000$source".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun styleRule(locale: String, name: String, value: String, confidence: Double): RuleRow = RuleRow(
        kind = RuleKind.STYLE,
        locale = locale,
        pattern = name,
        value = value,
        priority = 80,
        enabled = true,
        origin = "learned",
        confidence = (confidence * 1000).toInt() / 1000.0,
        note = "derived from approved translations",
    )

    private fun tokenize(text: String): List<String> = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value }.toList()

    private fun isCjk(locale: String): Boolean =
        locale.lowercase().substringBefore('-').substringBefore('_') in setOf("zh", "ja", "ko")

    private fun ngrams(text: String, sizes: List<Int>): List<String> {
        val cjkOnly = Regex("^[\\p{Script=Han}\\p{Script=Kana}\\p{Script=Hangul}]+$").matches(text)
        val result = mutableListOf<String>()
        if (cjkOnly) {
            for (size in sizes) {
                for (index in 0..(text.length - size)) result += text.substring(index, index + size)
            }
            return result
        }
        val tokens = tokenize(text)
        for (size in sizes) {
            for (index in 0..(tokens.size - size)) result += tokens.subList(index, index + size).joinToString(" ")
        }
        return result
    }

    private fun containsTerm(text: String, term: String, cjk: Boolean): Boolean {
        if (cjk) return text.contains(term)
        if (term.contains(' ')) return text.contains(term)
        return " $text ".contains(" $term ")
    }

    private fun isStopword(ngram: String, cjk: Boolean): Boolean {
        val trimmed = ngram.trim()
        if (cjk) return trimmed.length < 2
        if (trimmed.length < 3) return true
        if (trimmed in stopwords) return true
        return trimmed.all { it.isDigit() }
    }

    private fun jsonString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
