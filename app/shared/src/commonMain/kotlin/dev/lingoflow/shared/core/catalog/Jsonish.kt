package dev.lingoflow.shared.core.catalog

/**
 * Tolerant JSON / JSON5 style parser.
 *
 * Real locale files contain comments, trailing commas and unquoted keys, so the
 * app cannot rely on a strict parser. This is a Kotlin port of the parser used by
 * the LingoFlow CLI, keeping both implementations behaviourally compatible.
 */
sealed class JsonValue {
    data class Obj(val entries: List<Pair<String, JsonValue>>, val comments: Map<String, String> = emptyMap()) : JsonValue() {
        operator fun get(key: String): JsonValue? = entries.firstOrNull { it.first == key }?.second
    }

    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data object Null : JsonValue()

    fun toPlain(): Any? = when (this) {
        is Obj -> entries.associate { it.first to it.second.toPlain() }
        is Arr -> items.map { it.toPlain() }
        is Str -> value
        is Num -> value
        is Bool -> value
        Null -> null
    }
}

class JsonishException(message: String) : Exception(message)

data class JsonishResult(
    val value: JsonValue,
    val tolerant: Boolean,
    val comments: Map<String, String>,
    val prefix: String,
    val suffix: String,
    /** Offset just after the parsed value. */
    val end: Int,
)

class JsonishParser(private val text: String, private val start: Int = 0) {
    private var index = start
    private var tolerant = false
    private val comments = LinkedHashMap<String, String>()

    fun parse(): JsonishResult {
        skipSpaceAndComments()
        val value = parseValue("")
        skipSpaceAndComments()
        return JsonishResult(
            value = value,
            tolerant = tolerant,
            comments = comments,
            prefix = text.substring(0, start.coerceAtMost(text.length)),
            suffix = text.substring(index.coerceAtMost(text.length)),
            end = index,
        )
    }

    private fun skipSpaceOnly() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun skipSpaceAndComments() {
        while (index < text.length) {
            val char = text[index]
            if (char.isWhitespace()) {
                index++
                continue
            }
            if (char == '/' && index + 1 < text.length && text[index + 1] == '/') {
                tolerant = true
                val end = text.indexOf('\n', index)
                index = if (end == -1) text.length else end + 1
                continue
            }
            if (char == '/' && index + 1 < text.length && text[index + 1] == '*') {
                tolerant = true
                val end = text.indexOf("*/", index + 2)
                index = if (end == -1) text.length else end + 2
                continue
            }
            return
        }
    }

    /** Reads a comment at the current position; returns null when there is none. */
    private fun readComment(): String? {
        if (index >= text.length || text[index] != '/') return null
        if (index + 1 >= text.length) return null
        return when (text[index + 1]) {
            '/' -> {
                val end = text.indexOf('\n', index)
                val body = text.substring(index + 2, if (end == -1) text.length else end).trim()
                index = if (end == -1) text.length else end
                tolerant = true
                body.ifBlank { null }
            }
            '*' -> {
                val end = text.indexOf("*/", index + 2)
                val body = text.substring(index + 2, if (end == -1) text.length else end)
                    .replace(Regex("\\s*\\*\\s*"), " ")
                    .trim()
                index = if (end == -1) text.length else end + 2
                tolerant = true
                body.ifBlank { null }
            }
            else -> null
        }
    }

    private fun parseValue(path: String): JsonValue {
        skipSpaceAndComments()
        if (index >= text.length) throw JsonishException("Unexpected end of input")
        return when (val char = text[index]) {
            '{' -> parseObject(path)
            '[' -> parseArray(path)
            '"', '\'' -> JsonValue.Str(readString(char))
            else -> {
                if (char.isDigit() || char == '-' || char == '+') parseNumber()
                else parseKeyword()
            }
        }
    }

    private fun parseObject(path: String): JsonValue {
        index++ // {
        val entries = mutableListOf<Pair<String, JsonValue>>()
        val localComments = LinkedHashMap<String, String>()
        while (true) {
            val pending = mutableListOf<String>()
            while (true) {
                skipSpaceOnly()
                val comment = readComment() ?: break
                pending += comment
            }
            skipSpaceOnly()
            if (index >= text.length) throw JsonishException("Unterminated object")
            if (text[index] == '}') {
                index++
                break
            }
            val key = if (text[index] == '"' || text[index] == '\'') {
                readString(text[index])
            } else {
                val match = IDENT_KEY.find(text, index) ?: throw JsonishException("Invalid key at $index")
                tolerant = true
                index += match.value.length
                match.value
            }
            skipSpaceAndComments()
            if (index >= text.length || text[index] != ':') throw JsonishException("Expected ':' after \"$key\"")
            index++
            val childPath = if (path.isEmpty()) key else "$path.$key"
            if (pending.isNotEmpty()) localComments[childPath] = pending.joinToString(" ")
            val value = parseValue(childPath)
            entries += key to value
            skipSpaceAndComments()
            when {
                index >= text.length -> throw JsonishException("Unterminated object")
                text[index] == ',' -> index++
                text[index] == '}' -> {
                    index++
                    break
                }
                else -> throw JsonishException("Expected ',' or '}' at $index")
            }
        }
        comments.putAll(localComments)
        return JsonValue.Obj(entries, localComments)
    }

    private fun parseArray(path: String): JsonValue {
        index++ // [
        val items = mutableListOf<JsonValue>()
        while (true) {
            skipSpaceAndComments()
            if (index >= text.length) throw JsonishException("Unterminated array")
            if (text[index] == ']') {
                index++
                break
            }
            items += parseValue("$path.${items.size}")
            skipSpaceAndComments()
            when {
                index >= text.length -> throw JsonishException("Unterminated array")
                text[index] == ',' -> index++
                text[index] == ']' -> {
                    index++
                    break
                }
                else -> throw JsonishException("Expected ',' or ']' at $index")
            }
        }
        return JsonValue.Arr(items)
    }

    private fun readString(quote: Char): String {
        if (quote != '"') tolerant = true
        index++
        val builder = StringBuilder()
        while (index < text.length) {
            val char = text[index]
            if (char == '\\') {
                index++
                if (index >= text.length) break
                when (val escaped = text[index]) {
                    'n' -> builder.append('\n')
                    't' -> builder.append('\t')
                    'r' -> builder.append('\r')
                    'b' -> builder.append('\b')
                    'f' -> builder.append('\u000C')
                    'u' -> {
                        val hex = text.substring(index + 1, (index + 5).coerceAtMost(text.length))
                        builder.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        index += 4
                    }
                    '\n' -> Unit
                    else -> builder.append(escaped)
                }
                index++
                continue
            }
            if (char == quote) {
                index++
                return builder.toString()
            }
            if (char == '\n' && quote != '`') throw JsonishException("Unterminated string")
            builder.append(char)
            index++
        }
        throw JsonishException("Unterminated string")
    }

    private fun parseNumber(): JsonValue {
        val match = NUMBER.find(text, index) ?: throw JsonishException("Invalid number at $index")
        index += match.value.length
        return JsonValue.Num(match.value.toDouble())
    }

    private fun parseKeyword(): JsonValue {
        val rest = text.substring(index)
        return when {
            rest.startsWith("true") -> {
                index += 4
                JsonValue.Bool(true)
            }
            rest.startsWith("false") -> {
                index += 5
                JsonValue.Bool(false)
            }
            rest.startsWith("null") -> {
                index += 4
                JsonValue.Null
            }
            else -> throw JsonishException("Unexpected token at $index")
        }
    }

    private companion object {
        val IDENT_KEY = Regex("[A-Za-z_$@][\\w$@.\\-]*")
        val NUMBER = Regex("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")
    }
}

/** Serialises plain Kotlin values (Map/List/String/Number/Boolean/null) as JSON. */
object JsonWriter {
    fun write(
        value: Any?,
        indent: Int = 2,
        quote: Char = '"',
        comments: Map<String, String> = emptyMap(),
        eol: String = "\n",
    ): String = writeValue(value, indent, quote, comments, eol, 0, "")

    private fun writeValue(
        value: Any?,
        indent: Int,
        quote: Char,
        comments: Map<String, String>,
        eol: String,
        depth: Int,
        path: String,
    ): String {
        val pad = " ".repeat(indent * depth)
        val padInner = " ".repeat(indent * (depth + 1))
        return when (value) {
            null -> "null"
            is String -> quoteString(value, quote)
            is Boolean -> value.toString()
            is Number -> numberToString(value)
            is List<*> -> {
                if (value.isEmpty()) "[]" else {
                    val inner = value.mapIndexed { i, item ->
                        padInner + writeValue(item, indent, quote, comments, eol, depth + 1, "$path.$i")
                    }
                    "[$eol${inner.joinToString(",$eol")}$eol$pad]"
                }
            }
            is Map<*, *> -> {
                if (value.isEmpty()) "{}" else {
                    val inner = value.entries.joinToString(",$eol") { (rawKey, item) ->
                        val key = rawKey.toString()
                        val childPath = if (path.isEmpty()) key else "$path.$key"
                        val comment = comments[childPath]
                        val prefix = if (comment != null) "$padInner// $comment$eol" else ""
                        prefix + padInner + keyString(key, quote) + ": " +
                            writeValue(item, indent, quote, comments, eol, depth + 1, childPath)
                    }
                    "{$eol$inner$eol$pad}"
                }
            }
            else -> "null"
        }
    }

    private fun keyString(key: String, quote: Char): String =
        if (key.matches(Regex("[A-Za-z_$][\\w$]*"))) quoteString(key, quote) else quoteString(key, quote)

    private fun numberToString(value: Number): String {
        val double = value.toDouble()
        return if (double == double.toLong().toDouble()) double.toLong().toString() else double.toString()
    }

    fun quoteString(input: String, quote: Char = '"'): String {
        val escaped = buildString {
            for (char in input) {
                when (char) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\u2028' -> append("\\u2028")
                    '\u2029' -> append("\\u2029")
                    '"' -> if (quote == '"') append("\\\"") else append('"')
                    '\'' -> if (quote == '\'') append("\\'") else append('\'')
                    else -> append(char)
                }
            }
        }
        return "$quote$escaped$quote"
    }
}
