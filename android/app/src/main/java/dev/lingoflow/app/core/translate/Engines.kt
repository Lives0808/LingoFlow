package dev.lingoflow.app.core.translate

import dev.lingoflow.app.core.rules.DntTerm
import dev.lingoflow.app.core.rules.GlossaryTerm
import dev.lingoflow.app.core.validate.Placeholders

data class TranslateItem(
    val id: String,
    val key: String,
    val text: String,
    val from: String,
    val to: String,
    val comment: String? = null,
    val maxChars: Int? = null,
)

data class TranslateOutput(
    val id: String,
    val text: String,
    val engine: String,
    val confidence: Double = 0.5,
)

data class EngineContext(
    val glossary: List<GlossaryTerm> = emptyList(),
    val dnt: List<DntTerm> = emptyList(),
    val styleHints: List<String> = emptyList(),
)

/** Every translation backend implements this. */
interface TranslationEngine {
    val id: String
    val label: String
    /** True when the engine sends text over the network. */
    val network: Boolean

    fun translate(items: List<TranslateItem>, context: EngineContext): List<TranslateOutput>
}

/**
 * Built-in offline engine: glossary + a compact seed dictionary + typography.
 * It never leaves the device, which is why it is the default on mobile.
 */
class OfflineEngine(private val dictionary: Map<String, Map<String, String>> = SeedDictionary.entries) : TranslationEngine {

    private companion object {
        val WORD = Regex("[\\p{L}\\p{N}'’-]+")
    }

    override val id: String = "offline"
    override val label: String = "Built-in offline"
    override val network: Boolean = false

    override fun translate(items: List<TranslateItem>, context: EngineContext): List<TranslateOutput> =
        items.map { item -> compose(item) }

    private fun compose(item: TranslateItem): TranslateOutput {
        val target = item.to.replace('_', '-')
        val masked = Placeholders.mask(item.text)
        val source = masked.first
        // Kotlin's Regex.split drops the delimiters, so tokenise by iterating matches.
        val matches = WORD.findAll(source).toList()
        val builder = StringBuilder()
        var cursor = 0
        var covered = 0
        var total = 0
        var index = 0
        while (index < matches.size) {
            val match = matches[index]
            builder.append(source, cursor, match.range.first)
            cursor = match.range.last + 1
            total++
            val phrase = matchPhrase(matches, index, source, target)
            if (phrase != null) {
                builder.append(applyCase(phrase.value, match.value))
                covered++
                total += phrase.words - 1
                cursor = matches[index + phrase.consumed - 1].range.last + 1
                index += phrase.consumed
                continue
            }
            val direct = lookup(match.value, target)
            if (direct != null) {
                builder.append(applyCase(direct, match.value))
                covered++
            } else {
                builder.append(match.value)
            }
            index++
        }
        builder.append(source, cursor, source.length)
        var text = Placeholders.unmask(builder.toString(), masked.second)
        text = normalizeSpacing(text, isCjk(item.to))
        val confidence = if (total == 0) 0.3 else covered.toDouble() / total
        return TranslateOutput(item.id, text, id, (confidence * 100).toInt() / 100.0)
    }

    private data class Phrase(val value: String, val consumed: Int, val words: Int)

    /** Longest dictionary phrase (up to 4 words) starting at [index]. */
    private fun matchPhrase(matches: List<MatchResult>, index: Int, source: String, target: String): Phrase? {
        for (size in minOf(4, matches.size - index) downTo 2) {
            // Words must be separated by a single space in the source.
            var contiguous = true
            for (offset in 1 until size) {
                val previous = matches[index + offset - 1]
                val current = matches[index + offset]
                if (source.substring(previous.range.last + 1, current.range.first) != " ") {
                    contiguous = false
                    break
                }
            }
            if (!contiguous) continue
            val phrase = (0 until size).joinToString(" ") { matches[index + it].value.lowercase() }
            val value = lookup(phrase, target) ?: continue
            return Phrase(value, size, size)
        }
        return null
    }

    private fun lookup(term: String, target: String): String? {
        val lower = term.lowercase()
        dictionary[lower]?.let { entry ->
            entry[normalizeTarget(target)]?.let { return it }
            entry[target.lowercase().substringBefore('-')]?.let { return it }
        }
        if (lower.endsWith("s") && lower.length > 3) {
            dictionary[lower.dropLast(1)]?.let { entry ->
                entry[normalizeTarget(target)]?.let { return it }
                entry[target.lowercase().substringBefore('-')]?.let { return it }
            }
        }
        return null
    }

    private fun normalizeTarget(locale: String): String {
        val normalized = locale.lowercase().replace('_', '-')
        if (dictionary.values.any { it.containsKey(normalized) }) return normalized
        val base = normalized.substringBefore('-')
        if (dictionary.values.any { it.containsKey(base) }) return base
        if (base == "zh") return "zh-cn"
        return normalized
    }

    private fun applyCase(value: String, source: String): String = when {
        source.firstOrNull()?.isUpperCase() != true -> value
        source.all { !it.isLetter() || it.isUpperCase() } && source.length > 1 -> value.uppercase()
        else -> value.replaceFirstChar { it.uppercase() }
    }

    private fun isCjk(locale: String): Boolean =
        locale.lowercase().substringBefore('-').substringBefore('_') in setOf("zh", "ja", "ko")

    private fun normalizeSpacing(text: String, cjk: Boolean): String {
        var output = text.replace(Regex("[ \\t]{2,}"), " ")
        if (cjk) {
            output = output
                .replace(Regex("([\\p{Script=Han}\\p{Script=Kana}\\p{Script=Hangul}]) +(?=[\\p{Script=Han}\\p{Script=Kana}\\p{Script=Hangul}])"), "$1")
                .replace(Regex("\\s+([，。！？：；、）】》」』])"), "$1")
                .replace(Regex("([（【《「『])\\s+"), "$1")
        }
        return output.trim()
    }
}

/** Pseudo-localisation: accents + 40% expansion, for layout testing before translating. */
object PseudoEngine {
    private val accents = mapOf(
        'a' to 'á', 'b' to 'ƀ', 'c' to 'ç', 'd' to 'ð', 'e' to 'é', 'f' to 'ƒ', 'g' to 'ĝ', 'h' to 'ĥ',
        'i' to 'í', 'j' to 'ĵ', 'k' to 'ķ', 'l' to 'ļ', 'm' to 'ɱ', 'n' to 'ñ', 'o' to 'ó', 'p' to 'þ',
        'q' to 'ǫ', 'r' to 'ŕ', 's' to 'š', 't' to 'ţ', 'u' to 'ú', 'v' to 'ṽ', 'w' to 'ŵ', 'x' to 'ẋ',
        'y' to 'ý', 'z' to 'ž',
        'A' to 'Á', 'B' to 'Ɓ', 'C' to 'Ç', 'D' to 'Ð', 'E' to 'É', 'F' to 'Ƒ', 'G' to 'Ĝ', 'H' to 'Ĥ',
        'I' to 'Í', 'J' to 'Ĵ', 'K' to 'Ķ', 'L' to 'Ļ', 'M' to 'Ṁ', 'N' to 'Ñ', 'O' to 'Ó', 'P' to 'Þ',
        'Q' to 'Ǫ', 'R' to 'Ŕ', 'S' to 'Š', 'T' to 'Ţ', 'U' to 'Ú', 'V' to 'Ṽ', 'W' to 'Ŵ', 'X' to 'Ẋ',
        'Y' to 'Ý', 'Z' to 'Ž',
    )

    val localePattern = Regex("^(en[-_]?xa|ar[-_]?xb|qps[-_]?ploc|xx|pseudo|pseudo[-_]?\\w*)$", RegexOption.IGNORE_CASE)

    fun isPseudo(locale: String): Boolean = localePattern.matches(locale)

    fun localize(text: String, expansion: Double = 0.4, maxChars: Int? = null): String {
        val masked = Placeholders.mask(text)
        val target = maxOf(masked.first.length, (masked.first.length * (1 + expansion)).toInt())
        val accented = masked.first.map { accents[it] ?: it }.joinToString("")
        val padded = if (accented.length >= target) "⟦$accented⟧" else "⟦$accented${"·".repeat(target - accented.length)}⟧"
        val bounded = if (maxChars != null && padded.length > maxChars) {
            "⟦$accented⟧".take(maxOf(3, maxChars - 1))
        } else {
            padded
        }
        return Placeholders.unmask(bounded, masked.second)
    }
}
