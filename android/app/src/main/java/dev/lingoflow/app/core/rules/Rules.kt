package dev.lingoflow.app.core.rules

import dev.lingoflow.app.core.catalog.Keys
import dev.lingoflow.app.core.model.RuleKind
import dev.lingoflow.app.core.model.RuleRow
import dev.lingoflow.app.core.validate.Scripts

/** A glossary term ready for matching. */
data class GlossaryTerm(
    val locale: String?,
    val source: String,
    val target: String,
    val priority: Int = 60,
    val matchCase: Boolean = false,
    val wholeWord: Boolean = true,
    val origin: String = "manual",
    val confidence: Double = 1.0,
)

data class DntTerm(val locale: String?, val pattern: String, val replacement: String, val isRegex: Boolean = false, val priority: Int = 90)

data class CorrectionRule(
    val locale: String?,
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = false,
    val priority: Int = 70,
    val confidence: Double = 1.0,
    val enabled: Boolean = true,
    val note: String? = null,
)

data class StyleRule(val locale: String?, val name: String, val value: String, val priority: Int = 80)

/** Typed view over the raw rule rows stored in the database. */
data class RuleSet(
    val glossary: List<GlossaryTerm> = emptyList(),
    val dnt: List<DntTerm> = emptyList(),
    val corrections: List<CorrectionRule> = emptyList(),
    val styles: List<StyleRule> = emptyList(),
    val version: String = "0",
) {
    fun glossaryFor(locale: String): List<GlossaryTerm> = glossary.filter { it.locale == null || it.locale.equals(locale, ignoreCase = true) }
    fun dntFor(locale: String): List<DntTerm> = dnt.filter { it.locale == null || it.locale.equals(locale, ignoreCase = true) }
    fun correctionsFor(locale: String): List<CorrectionRule> =
        corrections.filter { it.enabled && (it.locale == null || it.locale.equals(locale, ignoreCase = true)) }

    fun style(locale: String, name: String): String? = styles
        .filter { it.name == name && (it.locale == null || it.locale.equals(locale, ignoreCase = true)) }
        .maxByOrNull { (if (it.locale != null) 1 else 0) * 1000 + it.priority }
        ?.value

    companion object {
        val STYLE_TRAILING = "punctuation.trailing"
        val STYLE_ELLIPSIS = "punctuation.ellipsis"
        val STYLE_CAPITALIZATION = "capitalization"
        val STYLE_CJK_FULLWIDTH = "cjk.fullwidth"
        val STYLE_CJK_LATIN_SPACE = "cjk.latin-space"
        val STYLE_FORMALITY = "formality"

        fun fromRows(rows: List<RuleRow>): RuleSet {
            val glossary = mutableListOf<GlossaryTerm>()
            val dnt = mutableListOf<DntTerm>()
            val corrections = mutableListOf<CorrectionRule>()
            val styles = mutableListOf<StyleRule>()
            for (row in rows) {
                if (!row.enabled) continue
                when (row.kind) {
                    RuleKind.GLOSSARY -> {
                        val json = parseJson(row.value)
                        glossary += GlossaryTerm(
                            locale = row.locale,
                            source = row.pattern,
                            target = (json?.get("target") as? String) ?: row.value,
                            priority = row.priority,
                            matchCase = json?.get("matchCase") as? Boolean ?: false,
                            wholeWord = json?.get("wholeWord") as? Boolean ?: true,
                            origin = row.origin,
                            confidence = row.confidence,
                        )
                    }
                    RuleKind.DNT -> {
                        val json = parseJson(row.value)
                        dnt += DntTerm(
                            locale = row.locale,
                            pattern = row.pattern,
                            replacement = (json?.get("replacement") as? String) ?: row.value.ifBlank { row.pattern },
                            isRegex = json?.get("regex") as? Boolean ?: false,
                            priority = row.priority,
                        )
                    }
                    RuleKind.CORRECTION -> {
                        val json = parseJson(row.value)
                        corrections += CorrectionRule(
                            locale = row.locale,
                            pattern = row.pattern,
                            replacement = (json?.get("to") as? String) ?: row.value,
                            isRegex = json?.get("regex") as? Boolean ?: false,
                            priority = row.priority,
                            confidence = row.confidence,
                            enabled = row.enabled,
                            note = row.note,
                        )
                    }
                    RuleKind.STYLE -> styles += StyleRule(row.locale, row.pattern, row.value, row.priority)
                }
            }
            glossary.sortWith(compareByDescending<GlossaryTerm> { it.priority }.thenByDescending { it.source.length })
            dnt.sortWith(compareByDescending<DntTerm> { it.priority }.thenByDescending { it.pattern.length })
            corrections.sortByDescending { it.priority }
            val version = (rows.map { "${it.kind}|${it.locale}|${it.pattern}|${it.value}" }.sorted().joinToString(",")).hashCode().toString()
            return RuleSet(glossary, dnt, corrections, styles, version)
        }

        private fun parseJson(value: String): Map<*, *>? {
            if (!value.trim().startsWith("{")) return null
            return runCatching {
                (dev.lingoflow.app.core.catalog.JsonishParser(value).parse().value as? dev.lingoflow.app.core.catalog.JsonValue.Obj)
                    ?.toPlain() as? Map<*, *>
            }.getOrNull()
        }
    }
}

/** Term protection, corrections and glossary verification. */
object RuleEngine {

    /** A shielded term: the original text, its replacement and the token index. */
    data class Shielded(val source: String, val target: String, val index: Int)

    data class Protection(val text: String, val tokens: Map<String, Shielded>)

    fun protect(text: String, glossary: List<GlossaryTerm>, dnt: List<DntTerm>): Protection {
        var output = text
        val tokens = LinkedHashMap<String, Shielded>()
        var counter = 0

        // Private-use markers keep the token from being read as a word by engines.
        fun shield(match: String, target: String): String {
            val index = counter++
            val token = "\u2063\u2062" + String(Character.toChars(0xE000 + (index % 0x1900))) + "\u2062\u2063"
            tokens[token] = Shielded(match, target, index)
            return token
        }

        for (term in dnt) {
            output = if (term.isRegex) {
                runCatching { Regex(term.pattern).replace(output) { shield(it.value, term.replacement) } }.getOrDefault(output)
            } else {
                output.replace(Regex(Regex.escape(term.pattern))) { shield(it.value, term.replacement) }
            }
        }
        for (term in glossary) {
            val pattern = matcher(term) ?: continue
            output = pattern.replace(output) { shield(it.value, term.target) }
        }
        return Protection(output, tokens)
    }

    fun restore(text: String, protection: Protection): String {
        var output = text
        for ((token, shielded) in protection.tokens) {
            val variants = listOf(token, token.replace("\u2063", ""), token.replace("\u2062", ""))
            var replaced = false
            for (variant in variants) {
                if (output.contains(variant)) {
                    output = output.replace(variant, shielded.target)
                    replaced = true
                    break
                }
            }
            if (!replaced) {
                // Engines sometimes drop invisible markers entirely; the visible
                // LF<n> marker is the next best anchor.
                val marker = "LF${shielded.index}"
                if (output.contains(marker)) {
                    output = output.replace(marker, shielded.target)
                } else {
                    output = output.replace(Regex("\\b${shielded.index}\\b"), shielded.target)
                }
            }
        }
        return output
    }

    fun matcher(term: GlossaryTerm): Regex? {
        val escaped = Regex.escape(term.source)
        val flags = if (term.matchCase) setOf(RegexOption.MULTILINE) else setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        val body = if (term.wholeWord) "(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])" else escaped
        return runCatching { Regex(body, flags) }.getOrNull()
    }

    data class CorrectionResult(val text: String, val applied: List<String>)

    fun applyCorrections(text: String, rules: List<CorrectionRule>): CorrectionResult {
        var output = text
        val applied = mutableListOf<String>()
        for (rule in rules) {
            if (rule.pattern.isBlank()) continue
            if (rule.isRegex) {
                val regex = runCatching { Regex(rule.pattern) }.getOrNull() ?: continue
                if (regex.containsMatchIn(output)) {
                    output = regex.replace(output, rule.replacement)
                    applied += "${rule.pattern} → ${rule.replacement}"
                }
            } else if (output.contains(rule.pattern)) {
                output = output.replace(rule.pattern, rule.replacement)
                applied += "${rule.pattern} → ${rule.replacement}"
            }
        }
        return CorrectionResult(output, applied)
    }

    /** Checks that glossary terms and do-not-translate strings survived. */
    fun glossaryIssues(
        key: String,
        locale: String,
        source: String,
        target: String,
        rules: RuleSet,
        enabled: Boolean,
        mode: String,
    ): List<dev.lingoflow.app.core.model.Issue> {
        if (!enabled || mode == "protect") return emptyList()
        val issues = mutableListOf<dev.lingoflow.app.core.model.Issue>()
        for (term in rules.glossaryFor(locale)) {
            if (term.source.isBlank()) continue
            val matcher = matcher(term) ?: continue
            if (!matcher.containsMatchIn(source)) continue
            if (!runCatching { Regex(Regex.escape(term.target)).containsMatchIn(target) }.getOrDefault(true)) {
                issues += dev.lingoflow.app.core.model.Issue(
                    code = "glossary.missed",
                    severity = dev.lingoflow.app.core.model.Severity.WARN,
                    locale = locale,
                    key = key,
                    message = "Glossary term not applied: \"${term.source}\" should be \"${term.target}\"",
                    target = target,
                    fixable = true,
                    suggestion = term.target,
                )
            }
        }
        for (term in rules.dntFor(locale)) {
            val inSource = if (term.isRegex) {
                runCatching { Regex(term.pattern).containsMatchIn(source) }.getOrDefault(false)
            } else {
                source.contains(term.pattern)
            }
            if (!inSource) continue
            val expected = term.replacement.ifBlank { term.pattern }
            if (!target.contains(expected)) {
                issues += dev.lingoflow.app.core.model.Issue(
                    code = "dnt.violated",
                    severity = dev.lingoflow.app.core.model.Severity.WARN,
                    locale = locale,
                    key = key,
                    message = "Do-not-translate term was modified: \"$expected\"",
                    target = target,
                    fixable = true,
                    suggestion = expected,
                )
            }
        }
        return issues
    }

    /** Deterministic style fixes (punctuation, CJK spacing, formality). */
    fun applyStyle(text: String, locale: String, rules: RuleSet): Pair<String, List<String>> {
        var output = text
        val fixes = mutableListOf<String>()

        when (rules.style(locale, RuleSet.STYLE_TRAILING)) {
            "never" -> {
                // `?` and `!` carry meaning; only periods are stylistic.
                if (output.trim().length > 3 && (output.trimEnd().endsWith(".") || output.trimEnd().endsWith("。"))) {
                    output = output.trimEnd().removeSuffix("。").removeSuffix(".")
                    fixes += "removed trailing period"
                }
            }
            "always" -> {
                if (output.isNotBlank() && output.trimEnd().lastOrNull() !in listOf('.', '!', '?', '…', '。', '！', '？')) {
                    output = output.trimEnd() + if (Scripts.isCjk(locale)) "。" else "."
                    fixes += "added trailing punctuation"
                }
            }
        }
        when (rules.style(locale, RuleSet.STYLE_ELLIPSIS)) {
            "unicode" -> if (output.contains("...")) {
                output = output.replace("...", "…")
                fixes += "converted ... to …"
            }
            "ascii" -> if (output.contains("…")) {
                output = output.replace("…", "...")
                fixes += "converted … to ..."
            }
        }
        when (rules.style(locale, RuleSet.STYLE_FORMALITY)) {
            "formal" -> if (locale.lowercase().startsWith("zh") && output.contains("你")) {
                output = output.replace("你们的", "您的").replace("你", "您")
                fixes += "switched to formal address (您)"
            }
            "informal" -> if (locale.lowercase().startsWith("zh") && output.contains("您")) {
                output = output.replace("您", "你")
                fixes += "switched to informal address (你)"
            }
        }
        if (Scripts.isCjk(locale)) {
            if (rules.style(locale, RuleSet.STYLE_CJK_FULLWIDTH) != "false") {
                val map = mapOf(',' to '，', '.' to '。', '!' to '！', '?' to '？', ';' to '；', ':' to '：')
                val replaced = Regex("(?<=[\\p{Script=Han}\\p{Script=Kana}])[,.!?;:](?=[\\p{Script=Han}\\p{Script=Kana}\\s]|$)")
                    .replace(output) { map[it.value.first()]?.toString() ?: it.value }
                if (replaced != output) {
                    output = replaced
                    fixes += "converted to fullwidth punctuation"
                }
            }
            val tightened = output
                .replace(Regex("(?<=[\\p{Script=Han}\\p{Script=Kana}])\\s+(?=[\\p{Script=Han}\\p{Script=Kana}])"), "")
                .replace(Regex("\\s+(?=[，。！？；：、）】》」』])"), "")
                .replace(Regex("(?<=[（【《「『])\\s+"), "")
            if (tightened != output) {
                output = tightened
                fixes += "removed invalid CJK spacing"
            }
        }
        return output to fixes
    }
}
