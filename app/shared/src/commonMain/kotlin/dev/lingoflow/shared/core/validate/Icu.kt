package dev.lingoflow.shared.core.validate

import dev.lingoflow.shared.core.catalog.PluralCategories

/** Structural validation of ICU MessageFormat strings. */
object Icu {

    data class Problem(val message: String, val argument: String? = null)

    data class Argument(val name: String, val type: String, val categories: List<String> = emptyList())

    data class Result(val ok: Boolean, val problems: List<Problem>, val arguments: List<Argument>)

    private val knownTypes = setOf("plural", "select", "selectordinal", "number", "date", "time")
    private val knownCategories = setOf("zero", "one", "two", "few", "many", "other")

    fun hasSyntax(text: String): Boolean =
        Regex("\\{\\s*[\\p{L}\\p{N}._-]+\\s*,\\s*(plural|select|selectordinal|number|date|time)").containsMatchIn(text)

    fun parse(message: String, locale: String): Result = Parser(message, locale).run()

    /** Instance based so `readNested` and `parseArgument` can recurse into each other. */
    private class Parser(private val message: String, private val locale: String) {
        private var index = 0
        private val problems = mutableListOf<Problem>()
        private val arguments = LinkedHashMap<String, Argument>()

        fun run(): Result {
            readNested(insidePlural = false)
            if (message.getOrNull(index) == '}') problems += Problem("Unbalanced closing brace")
            if (countBraces(message) != 0) problems += Problem("Unbalanced braces in ICU message")
            return Result(problems.isEmpty(), problems.toList(), arguments.values.toList())
        }

        private fun skipQuoted() {
            if (message.getOrNull(index + 1) == '\'') {
                index += 2
                return
            }
            val close = message.indexOf('\'', index + 1)
            if (close == -1) {
                problems += Problem("Unterminated quoted literal")
                index = message.length
                return
            }
            index = close + 1
        }

        private fun readNested(insidePlural: Boolean) {
            while (index < message.length) {
                when (message[index]) {
                    '}' -> return
                    '#' -> {
                        if (!insidePlural) problems += Problem("The # symbol is only valid inside a plural block")
                        index++
                    }
                    '\'' -> skipQuoted()
                    '{' -> {
                        index++
                        parseArgument()
                    }
                    else -> index++
                }
            }
        }

        private fun parseArgument() {
            val nameMatch = Regex("^\\s*([\\p{L}\\p{N}._-]+)\\s*").find(message.substring(index))
            if (nameMatch == null) {
                problems += Problem("Empty ICU argument")
                index++
                return
            }
            val name = nameMatch.groupValues[1]
            index += nameMatch.value.length
            val typeMatch = Regex("^,\\s*(\\w+)\\s*").find(message.substring(index))
            if (typeMatch == null) {
                arguments[name] = Argument(name, "argument")
                if (message.getOrNull(index) == '}') index++
                return
            }
            val type = typeMatch.groupValues[1]
            index += typeMatch.value.length
            if (type !in knownTypes) {
                problems += Problem("Unknown ICU type \"$type\"", name)
                val close = message.indexOf('}', index)
                index = if (close == -1) message.length else close + 1
                return
            }
            if (type == "plural" || type == "select" || type == "selectordinal") {
                Regex("^,?\\s*offset:\\s*\\d+\\s*").find(message.substring(index))?.let { index += it.value.length }
                val categories = mutableListOf<String>()
                while (true) {
                    val branch = Regex("^[,\\s]*(=?(\\w+))\\s*\\{").find(message.substring(index)) ?: break
                    categories += branch.groupValues[1]
                    index += branch.value.length
                    readNested(insidePlural = type != "select")
                    if (message.getOrNull(index) == '}') index++
                }
                arguments[name] = Argument(name, type, categories)
                if (type != "select") validateCategories(name, categories)
                if (message.getOrNull(index) == '}') index++ else problems += Problem("Plural block {$name} is not closed", name)
                return
            }
            // number / date / time with an optional style
            val close = findClosingBrace(message, index)
            arguments[name] = Argument(name, type)
            if (close == -1) {
                problems += Problem("Argument {$name} is not closed", name)
                index = message.length
                return
            }
            index = close + 1
        }

        private fun validateCategories(name: String, categories: List<String>) {
            val allowed = PluralCategories.forLocale(locale)
            val named = categories.filterNot { it.startsWith("=") }
            named.filterNot { it in knownCategories }.forEach {
                problems += Problem("\"$it\" is not a CLDR plural category", name)
            }
            allowed.filterNot { it in named }.forEach {
                problems += Problem("Plural form \"$it\" is missing", name)
            }
            named.filter { it in knownCategories && it !in allowed }.forEach {
                problems += Problem("Plural form \"$it\" is not used by this locale", name)
            }
        }

        private fun countBraces(text: String): Int {
            var depth = 0
            var cursor = 0
            while (cursor < text.length) {
                when (text[cursor]) {
                    '\'' -> {
                        if (text.getOrNull(cursor + 1) == '\'') {
                            cursor++
                        } else {
                            val close = text.indexOf('\'', cursor + 1)
                            cursor = if (close == -1) text.length else close
                        }
                    }
                    '{' -> depth++
                    '}' -> depth--
                }
                cursor++
            }
            return depth
        }

        private fun findClosingBrace(text: String, from: Int): Int {
            var depth = 0
            var cursor = from
            while (cursor < text.length) {
                when (text[cursor]) {
                    '\'' -> {
                        val close = text.indexOf('\'', cursor + 1)
                        cursor = if (close == -1) text.length else close
                    }
                    '{' -> depth++
                    '}' -> {
                        if (depth == 0) return cursor
                        depth--
                    }
                }
                cursor++
            }
            return -1
        }
    }
}

/** HTML / XML tag comparison, used to catch markup lost in translation. */
object Tags {

    data class Token(val raw: String, val name: String, val closing: Boolean, val selfClosing: Boolean, val attributes: String)

    private val regex = Regex("<(/?)([A-Za-z][\\w:.\\-]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*?)(/?)>")

    fun extract(text: String): List<Token> = regex.findAll(text).map { match ->
        Token(
            raw = match.value,
            closing = match.groupValues[1] == "/",
            name = match.groupValues[2].lowercase(),
            attributes = match.groupValues[3].trim(),
            selfClosing = match.groupValues[4] == "/",
        )
    }.toList()

    data class Comparison(val missing: List<String>, val extra: List<String>, val unbalanced: Boolean, val attributeIssues: List<String>)

    fun compare(source: String, target: String): Comparison {
        val sourceTags = extract(source)
        val targetTags = extract(target)
        val sourceOpen = sourceTags.filterNot { it.closing || it.selfClosing }.map { it.name }
        val targetOpen = targetTags.filterNot { it.closing || it.selfClosing }.map { it.name }
        val missing = sourceOpen.filterNot { it in targetOpen }
        val extra = targetOpen.filterNot { it in sourceOpen }

        val stack = ArrayDeque<String>()
        var unbalanced = false
        for (tag in targetTags) {
            if (tag.selfClosing) continue
            if (!tag.closing) stack.addLast(tag.name)
            else if (stack.removeLastOrNull() != tag.name) unbalanced = true
        }
        if (stack.isNotEmpty()) unbalanced = true

        val attributeIssues = mutableListOf<String>()
        for (sourceTag in sourceTags) {
            if (sourceTag.closing || sourceTag.selfClosing || sourceTag.attributes.isEmpty()) continue
            val targetTag = targetTags.firstOrNull { it.name == sourceTag.name && !it.closing } ?: continue
            val attributes = Regex("([A-Za-z_:][\\w:.\\-]*)\\s*=").findAll(sourceTag.attributes)
                .map { it.groupValues[1].lowercase() }
                .filterNot { it.startsWith("aria-") || it == "class" || it == "style" }
            for (attribute in attributes) {
                if (!targetTag.attributes.contains(attribute)) {
                    attributeIssues += "<${sourceTag.name}> lost the \"$attribute\" attribute"
                }
            }
        }
        return Comparison(missing, extra, unbalanced, attributeIssues)
    }
}
