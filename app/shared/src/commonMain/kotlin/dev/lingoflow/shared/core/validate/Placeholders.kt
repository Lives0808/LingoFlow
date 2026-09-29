package dev.lingoflow.shared.core.validate

/**
 * Placeholder extraction and comparison.
 *
 * Supported syntaxes: `{name}`, `{count, plural, …}`, `{{count}}`, `%s`, `%1$s`,
 * `%(name)s`, `${name}` and nested `$t(key)` references — the same set the CLI
 * understands, so a project can be checked on desktop and on the phone.
 */
object Placeholders {

    data class Token(val raw: String, val key: String, val start: Int, val end: Int)

    private val doubleBrace = Regex("\\{\\{\\s*[\\p{L}\\p{N}._-]+\\s*\\}\\}")
    private val dollarBrace = Regex("\\$\\{\\s*[\\p{L}\\p{N}._-]+\\s*\\}")
    private val printfIndexed = Regex("%\\d+\\$[sdif]")
    private val printfNamed = Regex("%\\((\\w+)\\)[sdif]")
    private val printf = Regex("%[sdif]")
    /** `$t(key)` nested references; built without string templates for clarity. */
    private val nested = Regex("\\" + "$" + "t\\(\\s*['\"][^'\"]+['\"]\\s*\\)")
    private val nameStart = Regex("^\\{\\s*([\\p{L}\\p{N}._-]+)\\s*")

    fun extract(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val claimed = mutableListOf<IntRange>()

        fun isClaimed(range: IntRange) = claimed.any { it.first < range.last + 1 && range.first < it.last + 1 }

        fun add(regex: Regex, canonical: (MatchResult) -> String) {
            regex.findAll(text).forEach { match ->
                val range = match.range
                if (isClaimed(range)) return@forEach
                claimed += range
                tokens += Token(match.value, canonical(match), range.first, range.last + 1)
            }
        }

        add(dollarBrace) { "${'$'}{${Regex("[\\p{L}\\p{N}._-]+").find(it.value)?.value ?: ""}}" }
        add(doubleBrace) { "{{${Regex("[\\p{L}\\p{N}._-]+").find(it.value)?.value ?: ""}}}" }
        add(printfIndexed) { "%${it.value.last()}" }
        add(printfNamed) { "%(${it.groupValues[1]})s" }
        add(printf) { it.value }
        add(nested) { it.value.replace(Regex("\\s+"), "") }

        // Balanced-brace scan for single-brace arguments (the only way to handle
        // `{count, plural, one {# item} other {# items}}` correctly).
        var index = 0
        while (index < text.length) {
            if (text[index] != '{' || text.getOrNull(index + 1) == '{' || text.getOrNull(index - 1) == '$') {
                index++
                continue
            }
            val match = nameStart.find(text.substring(index))
            if (match == null) {
                index++
                continue
            }
            val end = scanBalanced(text, index)
            if (end < 0) {
                index++
                continue
            }
            val range = index until end
            if (!isClaimed(range)) {
                claimed += range
                tokens += Token(text.substring(index, end), "{${match.groupValues[1]}}", index, end)
            }
            index = end
        }
        return tokens.sortedBy { it.start }
    }

    /** Returns the index just after the matching `}`, or -1 when unbalanced. */
    private fun scanBalanced(text: String, start: Int): Int {
        var depth = 0
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '\'' -> {
                    val close = text.indexOf('\'', index + 1)
                    index = if (close == -1) text.length else close
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
            index++
        }
        return -1
    }

    fun keys(text: String): List<String> = extract(text).map { it.key }.distinct()

    data class Comparison(val missing: List<String>, val extra: List<String>, val matching: List<String>)

    fun compare(source: String, target: String): Comparison {
        val sourceKeys = keys(source)
        val targetKeys = keys(target)
        val sourceSet = sourceKeys.toSet()
        val targetSet = targetKeys.toSet()
        return Comparison(
            missing = sourceKeys.filterNot { it in targetSet },
            extra = targetKeys.filterNot { it in sourceSet },
            matching = sourceKeys.filter { it in targetSet },
        )
    }

    /**
     * Shields placeholders with sentinels so engines cannot translate them.
     * Sentinels use private-use code points, so they are never mistaken for words
     * (which would dilute the offline engine's confidence).
     */
    fun mask(text: String): Pair<String, Map<String, String>> {
        val tokens = extract(text)
        val map = LinkedHashMap<String, String>()
        var output = text
        tokens.sortedByDescending { it.start }.forEach { token ->
            val sentinel = sentinelFor(tokens.indexOf(token))
            map[sentinel] = token.raw
            output = output.substring(0, token.start) + sentinel + output.substring(token.end)
        }
        return output to map
    }

    fun sentinelFor(index: Int): String =
        "\u2063\u2062" + String(Character.toChars(0xE000 + (index % 0x1900))) + "\u2062\u2063"

    fun unmask(text: String, map: Map<String, String>): String {
        var output = text
        map.forEach { (sentinel, raw) -> output = output.replace(sentinel, raw) }
        return output
    }
}
