package dev.lingoflow.shared.core.validate

import dev.lingoflow.shared.core.model.Issue
import dev.lingoflow.shared.core.model.Severity

/** Script helpers used by typography and "looks untranslated" checks. */
object Scripts {

    enum class Script { HAN, KANA, HANGUL, CYRILLIC, ARABIC, HEBREW, THAI, DEVANAGARI, LATIN, OTHER }

    fun of(codePoint: Int): Script = when {
        codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF || codePoint in 0xF900..0xFAFF -> Script.HAN
        codePoint in 0x3040..0x30FF -> Script.KANA
        codePoint in 0xAC00..0xD7AF || codePoint in 0x1100..0x11FF -> Script.HANGUL
        codePoint in 0x0400..0x04FF -> Script.CYRILLIC
        codePoint in 0x0600..0x06FF -> Script.ARABIC
        codePoint in 0x0590..0x05FF -> Script.HEBREW
        codePoint in 0x0E00..0x0E7F -> Script.THAI
        codePoint in 0x0900..0x097F -> Script.DEVANAGARI
        codePoint in 0x41..0x5A || codePoint in 0x61..0x7A || codePoint in 0xC0..0x24F -> Script.LATIN
        else -> Script.OTHER
    }

    fun inText(text: String): Set<Script> = text.map { of(it.code) }.filter { it != Script.OTHER }.toSet()

    fun expected(locale: String): List<Script> {
        val base = locale.lowercase().substringBefore('-').substringBefore('_')
        return when (base) {
            "zh" -> listOf(Script.HAN)
            "ja" -> listOf(Script.KANA, Script.HAN)
            "ko" -> listOf(Script.HANGUL, Script.HAN)
            "ru", "uk", "bg", "sr" -> listOf(Script.CYRILLIC)
            "ar", "fa", "ur" -> listOf(Script.ARABIC)
            "he" -> listOf(Script.HEBREW)
            "th" -> listOf(Script.THAI)
            "hi", "bn" -> listOf(Script.DEVANAGARI)
            else -> emptyList()
        }
    }

    fun isCjk(locale: String): Boolean {
        val base = locale.lowercase().substringBefore('-').substringBefore('_')
        return base in setOf("zh", "ja", "ko")
    }
}

/** Text-level checks: whitespace, punctuation, script mismatch, forbidden terms. */
object TextChecks {

    fun run(
        key: String,
        locale: String,
        source: String,
        target: String,
        sourceLocale: String,
        config: dev.lingoflow.shared.core.config.ValidateConfig,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        val isSource = locale == sourceLocale

        fun add(
            code: String,
            severity: Severity,
            message: String,
            fixable: Boolean = false,
            detail: String? = null,
        ) {
            issues += Issue(code, severity, locale, key, message, detail = detail, target = target, fixable = fixable)
        }

        if (target.isEmpty()) {
            add("empty", Severity.ERROR, "Translation is empty")
            return issues
        }

        if (config.whitespace) {
            if (target.first().isWhitespace() != source.firstOrNull()?.isWhitespace()) {
                add(
                    "text.leadingSpace",
                    Severity.WARN,
                    if (target.first().isWhitespace()) "Unexpected leading whitespace" else "Missing leading whitespace",
                    fixable = true,
                )
            }
            if (target.last().isWhitespace() != source.lastOrNull()?.isWhitespace()) {
                add(
                    "text.trailingSpace",
                    Severity.WARN,
                    if (target.last().isWhitespace()) "Unexpected trailing whitespace" else "Missing trailing whitespace",
                    fixable = true,
                )
            }
            if (Regex("[ \\t]{2,}").containsMatchIn(target)) {
                add("text.doubleSpace", Severity.INFO, "Double space inside the string", fixable = true)
            }
            val sourceLines = source.count { it == '\n' } + 1
            val targetLines = target.count { it == '\n' } + 1
            if (sourceLines > 1 && targetLines != sourceLines) {
                add("text.newline", Severity.WARN, "Line breaks changed ($sourceLines → $targetLines)", fixable = true)
            }
        }

        if (config.punctuation && !isSource) {
            val sourceEnds = source.trimEnd().lastOrNull() in listOf('.', '!', '?', '…', '。', '！', '？')
            val targetEnds = target.trimEnd().lastOrNull() in listOf('.', '!', '?', '…', '。', '！', '？')
            if (sourceEnds != targetEnds) {
                add(
                    "text.punctuation",
                    Severity.INFO,
                    if (sourceEnds) "Source ends with punctuation, target does not" else "Target added trailing punctuation",
                    fixable = true,
                )
            }
        }

        if (config.ellipsis && !isSource && source.contains("...") && !target.contains("...") && !target.contains("…")) {
            add("text.ellipsis", Severity.INFO, "Source ellipsis (...) not represented in target", fixable = true)
        }

        if (config.script && !isSource) {
            val targetScripts = Scripts.inText(target).filter { it != Scripts.Script.LATIN }
            val expected = Scripts.expected(locale)
            if (expected.isNotEmpty() && targetScripts.isNotEmpty() && targetScripts.none { it in expected }) {
                add(
                    "script.mismatch",
                    Severity.WARN,
                    "Target contains ${targetScripts.joinToString(", ") { it.name.lowercase() }} but the locale expects ${
                        expected.joinToString(", ") { it.name.lowercase() }
                    }",
                )
            }
        }

        for (term in config.forbidden) {
            if (term.isEmpty()) continue
            val matched = runCatching { Regex(term).containsMatchIn(target) }.getOrElse { target.contains(term) }
            if (matched) add("forbidden.term", Severity.WARN, "Forbidden term matched: $term")
        }

        if (!isSource && target == source && source.length > 2) {
            add("untranslated", Severity.WARN, "Target is identical to the source")
        }

        return issues
    }
}

/** CJK typography: fullwidth punctuation, Han/Latin spacing, invalid spaces. */
object CjkChecks {

    private const val HAN_KANA = "\\p{Script=Han}\\p{Script=Kana}"

    fun run(
        key: String,
        locale: String,
        target: String,
        fullwidth: Boolean,
        latinSpace: Boolean?,
    ): List<Issue> {
        if (!Scripts.isCjk(locale) || target.isBlank()) return emptyList()
        val issues = mutableListOf<Issue>()

        fun add(code: String, severity: Severity, message: String) {
            issues += Issue(code, severity, locale, key, message, target = target, fixable = true)
        }

        if (fullwidth) {
            Regex("(?<=[$HAN_KANA])[,.!?;:](?=[$HAN_KANA\\s]|$)").find(target)?.let { match ->
                add("cjk.punctuation", Severity.INFO, "Halfwidth \"${match.value}\" between CJK characters — use fullwidth punctuation")
            }
        }
        if (Regex("\\s+[，。！？；：、）】》」』]").containsMatchIn(target)) {
            add("cjk.space", Severity.WARN, "Space before CJK punctuation")
        }
        if (Regex("[（【《「『]\\s+").containsMatchIn(target)) {
            add("cjk.space", Severity.WARN, "Space after an opening CJK bracket")
        }
        if (Regex("(?<=[$HAN_KANA])\\s+(?=[$HAN_KANA])").containsMatchIn(target)) {
            add("cjk.space", Severity.WARN, "Space between CJK characters")
        }
        when (latinSpace) {
            true -> if (Regex("(?<=[$HAN_KANA])(?=[A-Za-z0-9])|(?<=[A-Za-z0-9])(?=[$HAN_KANA])").containsMatchIn(target)) {
                add("cjk.space", Severity.INFO, "Missing space between CJK and Latin text")
            }
            false -> if (Regex("(?<=[$HAN_KANA])\\s+(?=[A-Za-z0-9])|(?<=[A-Za-z0-9])\\s+(?=[$HAN_KANA])").containsMatchIn(target)) {
                add("cjk.space", Severity.INFO, "Unexpected space between CJK and Latin text")
            }
            null -> Unit
        }
        return issues
    }
}
