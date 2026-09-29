package dev.lingoflow.shared.core.validate

import dev.lingoflow.shared.core.catalog.Keys
import dev.lingoflow.shared.core.config.ProjectConfig
import dev.lingoflow.shared.core.model.CatalogEntry
import dev.lingoflow.shared.core.model.Issue
import dev.lingoflow.shared.core.model.Severity
import dev.lingoflow.shared.core.model.TextMeasurer
import dev.lingoflow.shared.core.rules.RuleEngine
import dev.lingoflow.shared.core.rules.RuleSet

/** Everything the UI needs to describe one locale. */
data class LocaleValidation(
    val locale: String,
    val keyCount: Int,
    val translated: Int,
    val missing: Int,
    val empty: Int,
    val frozen: Int,
    val issues: List<Issue>,
) {
    val coverage: Double get() = if (keyCount == 0) 1.0 else translated.toDouble() / keyCount
    val errors: Int get() = issues.count { it.severity == Severity.ERROR }
    val warnings: Int get() = issues.count { it.severity == Severity.WARN }
    val infos: Int get() = issues.count { it.severity == Severity.INFO }
}

/**
 * Runs the whole validation matrix for one locale, mirroring the CLI so a
 * project reviewed on the phone matches a CI run on the desktop.
 */
class Validator(
    private val config: ProjectConfig,
    private val rules: RuleSet,
    private val measurer: TextMeasurer,
) {
    fun validateLocale(
        locale: String,
        sources: Map<String, CatalogEntry>,
        targetValues: Map<String, String?>,
        frozenKeys: Set<String> = emptySet(),
    ): LocaleValidation {
        val issues = mutableListOf<Issue>()
        var translated = 0
        var missing = 0
        var empty = 0
        var frozen = 0
        val consistency = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
        val isSource = locale == config.sourceLocale

        for ((key, sourceEntry) in sources) {
            val source = sourceEntry.value ?: continue
            if (source.isBlank()) continue
            if (key in frozenKeys) frozen++
            val target = targetValues[key]
            when {
                target == null -> {
                    missing++
                    issues += Issue("missing", Severity.ERROR, locale, key, "Key is missing in this locale", detail = null)
                    continue
                }
                target.isBlank() -> {
                    empty++
                    issues += Issue("empty", Severity.ERROR, locale, key, "Translation is empty")
                    continue
                }
                else -> translated++
            }

            issues += validateEntry(locale, key, source, target, sourceEntry, consistency, isSource)
        }
        val filtered = finalize(issues, locale)
        return LocaleValidation(locale, sources.size, translated, missing, empty, frozen, filtered)
    }

    fun validateEntry(
        locale: String,
        key: String,
        source: String,
        target: String,
        sourceEntry: CatalogEntry? = null,
        consistencyIndex: MutableMap<String, MutableMap<String, MutableList<String>>> = mutableMapOf(),
        isSource: Boolean = locale == config.sourceLocale,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        val validate = config.validate

        if (validate.placeholders) {
            val comparison = Placeholders.compare(source, target)
            comparison.missing.forEach {
                issues += Issue("placeholder.missing", Severity.ERROR, locale, key, "Missing placeholder $it", target = target, fixable = true)
            }
            comparison.extra.forEach {
                issues += Issue("placeholder.extra", Severity.WARN, locale, key, "Unexpected placeholder $it", target = target, fixable = true)
            }
        }

        if (validate.icu) {
            val sourceIcu = Icu.hasSyntax(source)
            val targetIcu = Icu.hasSyntax(target)
            if (targetIcu) {
                val parsed = Icu.parse(target, locale)
                parsed.problems.forEach { problem ->
                    issues += Issue("icu.invalid", Severity.ERROR, locale, key, "ICU: ${problem.message}", detail = problem.argument, target = target)
                }
                if (sourceIcu) {
                    val sourceParsed = Icu.parse(source, config.sourceLocale)
                    val sourceArgs = sourceParsed.arguments.associateBy { it.name }
                    for (argument in parsed.arguments) {
                        val sourceArgument = sourceArgs[argument.name] ?: continue
                        val allowed = dev.lingoflow.shared.core.catalog.PluralCategories.forLocale(locale)
                        if (argument.type == "plural" && sourceArgument.categories.isNotEmpty()) {
                            val expected = sourceArgument.categories.filterNot { it.startsWith("=") }
                            for (category in expected) {
                                val hasCategory = argument.categories.contains(category) || argument.categories.contains("other")
                                if (category in allowed && !hasCategory) {
                                    issues += Issue("icu.category", Severity.WARN, locale, key, "Plural form \"$category\" missing for {${argument.name}}", target = target)
                                }
                            }
                        }
                    }
                }
            } else if (sourceIcu) {
                issues += Issue("icu.invalid", Severity.ERROR, locale, key, "Source uses ICU syntax but the translation does not", target = target, fixable = true)
            }
        }

        if (validate.tags) {
            val comparison = Tags.compare(source, target)
            if (comparison.unbalanced) issues += Issue("tag.unbalanced", Severity.ERROR, locale, key, "HTML/XML tags are unbalanced", target = target)
            comparison.missing.forEach { issues += Issue("tag.mismatch", Severity.WARN, locale, key, "Tag <$it> is missing in the translation", target = target) }
            comparison.extra.forEach { issues += Issue("tag.mismatch", Severity.WARN, locale, key, "Tag <$it> was added by the translation", target = target) }
            comparison.attributeIssues.forEach { issues += Issue("tag.mismatch", Severity.WARN, locale, key, it, target = target) }
        }

        issues += TextChecks.run(key, locale, source, target, config.sourceLocale, validate)

        if (validate.cjk) {
            issues += CjkChecks.run(
                key = key,
                locale = locale,
                target = target,
                fullwidth = rules.style(locale, RuleSet.STYLE_CJK_FULLWIDTH) != "false",
                latinSpace = when (rules.style(locale, RuleSet.STYLE_CJK_LATIN_SPACE)) {
                    "true" -> true
                    "false" -> false
                    else -> null
                },
            )
        }

        val (lengthIssues, _) = LengthCheck.run(key, locale, source, target, config.length, measurer, sourceEntry, config.sourceLocale)
        issues += lengthIssues

        if (validate.dnt || validate.glossary) {
            issues += RuleEngine.glossaryIssues(
                key = key,
                locale = locale,
                source = source,
                target = target,
                rules = rules,
                enabled = config.translation.glossaryEnforce && (validate.dnt || validate.glossary),
                mode = config.translation.glossaryMode,
            )
        }

        if (validate.consistency && !isSource) {
            val variants = consistencyIndex.getOrPut(source.lowercase().trim()) { mutableMapOf() }
            val dominant = variants.entries.maxByOrNull { it.value.size }
            if (dominant != null && dominant.key != target && dominant.value.none { it == key }) {
                issues += Issue(
                    code = "consistency.source",
                    severity = Severity.WARN,
                    locale = locale,
                    key = key,
                    message = "Inconsistent with ${dominant.value.size} other key(s): \"${dominant.key}\"",
                    detail = "also used by ${dominant.value.take(3).joinToString(", ")}",
                    target = target,
                    fixable = true,
                    suggestion = dominant.key,
                )
            }
            val keys = variants.getOrPut(target) { mutableListOf() }
            keys += key
        }

        return finalize(issues, locale)
    }

    /** Applies per-locale ignore patterns and severity overrides, then sorts. */
    fun finalize(issues: List<Issue>, locale: String): List<Issue> {
        val ignore = config.validate.ignore[locale] ?: config.validate.ignore["*"] ?: emptyList()
        return issues
            .filterNot { issue -> ignore.any { Keys.matches(it, issue.key) } }
            .distinctBy { "${it.code}|${it.locale}|${it.key}|${it.message}" }
            .sortedBy { when (it.severity) { Severity.ERROR -> 0; Severity.WARN -> 1; Severity.INFO -> 2 } }
    }
}

/** Deterministic fixes (line breaks, punctuation, placeholders) used by the editor. */
object Fixer {

    fun fix(
        config: ProjectConfig,
        rules: RuleSet,
        key: String,
        locale: String,
        source: String,
        target: String,
        entry: CatalogEntry?,
    ): Pair<String, List<String>> {
        var text = target
        val fixes = mutableListOf<String>()

        // Line breaks follow the source.
        if (!source.contains('\n') && text.contains('\n')) {
            text = text.replace(Regex("\\s*\\n\\s*"), " ")
            fixes += "normalised line breaks"
        }

        val (styled, styleFixes) = RuleEngine.applyStyle(text, locale, rules)
        text = styled
        fixes += styleFixes

        val corrections = RuleEngine.applyCorrections(text, rules.correctionsFor(locale))
        if (corrections.applied.isNotEmpty()) {
            text = corrections.text
            fixes += "applied ${corrections.applied.size} learned correction(s)"
        }

        val comparison = Placeholders.compare(source, text)
        if (comparison.missing.isNotEmpty()) {
            val suffix = comparison.missing.joinToString(" ")
            val trailing = Regex("[.!?…。！？；;:：]+\\s*$").find(text)
            text = if (trailing != null && trailing.range.first > 0) {
                val head = text.substring(0, trailing.range.first).trimEnd()
                "$head $suffix${trailing.value}"
            } else {
                "${text.trimEnd()} $suffix"
            }
            fixes += "restored placeholder(s): ${comparison.missing.joinToString(", ")}"
        }
        @Suppress("UNUSED_EXPRESSION") key
        @Suppress("UNUSED_EXPRESSION") entry
        return text to fixes
    }
}
