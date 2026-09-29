package dev.lingoflow.shared.core.catalog

import dev.lingoflow.shared.core.model.CatalogEntry
import dev.lingoflow.shared.core.model.Format

data class FormatContext(
    val locale: String,
    val path: String,
    val keySeparator: String = ".",
    val nesting: Boolean = true,
    val indent: Int = 2,
    val eol: String = "\n",
    val previous: DecodedCatalog? = null,
)

data class DecodedCatalog(
    val entries: List<CatalogEntry>,
    val meta: MutableMap<String, Any?> = mutableMapOf(),
)

interface CatalogFormat {
    val id: Format

    fun decode(text: String, ctx: FormatContext): DecodedCatalog

    fun encode(decoded: DecodedCatalog, ctx: FormatContext): String
}

object Formats {

    val all: Map<Format, CatalogFormat> = listOf(
        JsonCatalogFormat,
        YamlCatalogFormat,
        PropertiesCatalogFormat,
        PoCatalogFormat,
        ArbCatalogFormat,
        StringsCatalogFormat,
        CsvCatalogFormat,
        TsJsCatalogFormat,
    ).associateBy { it.id }

    private val extensionMap = mapOf(
        "json" to Format.JSON,
        "json5" to Format.JSON,
        "jsonc" to Format.JSON,
        "yaml" to Format.YAML,
        "yml" to Format.YAML,
        "properties" to Format.PROPERTIES,
        "po" to Format.PO,
        "pot" to Format.PO,
        "arb" to Format.ARB,
        "strings" to Format.STRINGS,
        "stringsdict" to Format.STRINGS,
        "csv" to Format.CSV,
        "ts" to Format.TSJS,
        "js" to Format.TSJS,
        "mjs" to Format.TSJS,
        "cjs" to Format.TSJS,
    )

    fun detect(path: String): Format? {
        val extension = path.substringAfterLast('.', "").lowercase()
        return extensionMap[extension]
    }

    fun of(format: Format): CatalogFormat = all.getValue(format)

    fun eolOf(text: String, configured: String): String = when (configured.lowercase()) {
        "crlf" -> "\r\n"
        "lf" -> "\n"
        else -> if (text.contains("\r\n")) "\r\n" else "\n"
    }
}

private fun entriesFromJson(
    value: JsonValue,
    ctx: FormatContext,
    comments: Map<String, String> = emptyMap(),
): List<CatalogEntry> {
    val plain = value.toPlain()
    val lookup = { key: String ->
        if (comments.isEmpty()) null else run {
            var cursor = key
            var found: String? = null
            while (found == null) {
                found = comments[cursor]
                val dot = cursor.lastIndexOf(ctx.keySeparator.ifEmpty { "." })
                if (found != null || dot <= 0) break
                cursor = cursor.substring(0, dot)
            }
            found
        }
    }
    return Keys.flatten(plain, ctx.keySeparator, ctx.nesting).map { (key, raw) ->
        CatalogEntry(key = key, value = Keys.asString(raw), comment = lookup(key))
    }
}

object JsonCatalogFormat : CatalogFormat {
    override val id: Format = Format.JSON

    override fun decode(text: String, ctx: FormatContext): DecodedCatalog {
        var tolerant = false
        val comments = LinkedHashMap<String, String>()
        val value: JsonValue = try {
            JsonishParser(text).parse().let {
                tolerant = it.tolerant
                comments.putAll(it.comments)
                it.value
            }
        } catch (error: JsonishException) {
            // Fall back to a lenient second pass that strips comments first.
            val stripped = stripComments(text)
            JsonishParser(stripped).parse().let {
                tolerant = true
                it.value
            }
        }
        return DecodedCatalog(
            entries = entriesFromJson(value, ctx, comments),
            meta = mutableMapOf("tolerant" to tolerant, "comments" to comments),
        )
    }

    override fun encode(decoded: DecodedCatalog, ctx: FormatContext): String {
        @Suppress("UNCHECKED_CAST")
        val comments = (decoded.meta["comments"] as? Map<String, String>).orEmpty()
        val tolerant = decoded.meta["tolerant"] == true
        val tree = Keys.unflatten(
            decoded.entries.map { it.key to (it.value ?: "") },
            ctx.keySeparator,
            ctx.nesting,
        )
        val body = JsonWriter.write(
            value = tree,
            indent = ctx.indent,
            comments = if (tolerant) comments else emptyMap(),
            eol = "\n",
        )
        return body + "\n"
    }

    private fun stripComments(text: String): String {
        val builder = StringBuilder()
        var inString = false
        var quote = ' '
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val next = text.getOrNull(index + 1)
            if (inString) {
                builder.append(char)
                if (char == '\\') {
                    next?.let { builder.append(it) }
                    index += 2
                    continue
                }
                if (char == quote) inString = false
                index++
                continue
            }
            when {
                char == '"' || char == '\'' -> {
                    inString = true
                    quote = char
                    builder.append(char)
                }
                char == '/' && next == '/' -> {
                    val end = text.indexOf('\n', index)
                    index = if (end == -1) text.length else end
                    continue
                }
                char == '/' && next == '*' -> {
                    val end = text.indexOf("*/", index + 2)
                    index = if (end == -1) text.length else end + 2
                    continue
                }
                else -> builder.append(char)
            }
            index++
        }
        return builder.toString().replace(Regex(",(\\s*[}\\]])"), "$1")
    }
}

/**
 * YAML subset: nested maps by indentation, `#` comments, quoted and plain
 * scalars, literal blocks (`|`, `|-`) and inline lists. Enough for locale files;
 * anything exotic falls back to a clear parse error.
 */
object YamlCatalogFormat : CatalogFormat {
    override val id: Format = Format.YAML

    override fun decode(text: String, ctx: FormatContext): DecodedCatalog {
        val lines = text.replace("\r\n", "\n").split("\n")
        val root = LinkedHashMap<String, Any?>()
        val comments = LinkedHashMap<String, String>()
        val stack = ArrayDeque<Pair<Int, MutableMap<String, Any?>>>()
        stack.addLast(0 to root)
        var pendingComment: String? = null
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                index++
                continue
            }
            if (trimmed.startsWith("#")) {
                pendingComment = trimmed.removePrefix("#").trim()
                index++
                continue
            }
            val indent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val colon = findKeyColon(trimmed)
            if (colon == -1) {
                // List item under the current path: keep as a numbered key.
                if (trimmed.startsWith("-")) {
                    val current = stack.last().second
                    val itemIndex = current.keys.count { it.toIntOrNull() != null }
                    current[itemIndex.toString()] = unquote(trimmed.removePrefix("-").trim())
                    index++
                    pendingComment = null
                    continue
                }
                index++
                continue
            }
            while (stack.size > 1 && indent <= stack.last().first) stack.removeLast()
            val parent = stack.last().second
            val key = unquote(trimmed.substring(0, colon).trim())
            val valueText = trimmed.substring(colon + 1).trim()
            val path = buildPath(stack, key)

            if (valueText.isEmpty()) {
                val child = LinkedHashMap<String, Any?>()
                parent[key] = child
                if (pendingComment != null) comments[path] = pendingComment!!
                stack.addLast(indent to child)
                pendingComment = null
                index++
                continue
            }
            if (valueText == "|" || valueText == "|-" || valueText == ">" || valueText == ">-") {
                val blockIndent = nextBlockIndent(lines, index + 1)
                val blockLines = mutableListOf<String>()
                var cursor = index + 1
                while (cursor < lines.size) {
                    val raw = lines[cursor]
                    if (raw.isBlank()) {
                        blockLines += ""
                        cursor++
                        continue
                    }
                    val rawIndent = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                    if (rawIndent < blockIndent) break
                    blockLines += raw.substring(blockIndent.coerceAtMost(raw.length))
                    cursor++
                }
                val joined = blockLines.joinToString("\n").trimEnd('\n')
                parent[key] = if (valueText.startsWith(">")) joined.replace("\n", " ") else joined
                if (pendingComment != null) comments[path] = pendingComment!!
                pendingComment = null
                index = cursor
                continue
            }
            parent[key] = parseScalar(valueText)
            if (pendingComment != null) comments[path] = pendingComment!!
            pendingComment = null
            index++
        }
        val entries = Keys.flatten(root, ctx.keySeparator, ctx.nesting).map { (key, raw) ->
            CatalogEntry(key, Keys.asString(raw), comments[key])
        }
        return DecodedCatalog(entries, mutableMapOf("comments" to comments))
    }

    override fun encode(decoded: DecodedCatalog, ctx: FormatContext): String {
        @Suppress("UNCHECKED_CAST")
        val comments = (decoded.meta["comments"] as? Map<String, String>).orEmpty()
        val builder = StringBuilder()
        for (entry in decoded.entries) {
            comments[entry.key]?.let { builder.append("# ").append(it).append('\n') }
            val indent = entry.key.count { it == '.' } // best-effort visual nesting
            builder.append("  ".repeat(indent))
            builder.append(quoteIfNeeded(entry.key))
            builder.append(": ")
            val value = entry.value ?: ""
            builder.append(
                when {
                    value.contains('\n') -> "|-\n" + value.lines().joinToString("\n") { "  ".repeat(indent + 1) + it }
                    needsQuotes(value) -> JsonWriter.quoteString(value)
                    else -> value
                },
            )
            builder.append('\n')
        }
        return builder.toString()
    }

    private fun buildPath(stack: ArrayDeque<Pair<Int, MutableMap<String, Any?>>>, key: String): String {
        val segments = stack.drop(1).mapNotNull { frame ->
            frame.second.entries.lastOrNull()?.key
        }
        return if (segments.isEmpty()) key else segments.joinToString(".") + "." + key
    }

    private fun findKeyColon(line: String): Int {
        var inQuote = false
        var quote = ' '
        line.forEachIndexed { index, char ->
            if (inQuote) {
                if (char == quote) inQuote = false
                return@forEachIndexed
            }
            when (char) {
                '"', '\'' -> {
                    inQuote = true
                    quote = char
                }
                ':' -> if (index + 1 >= line.length || line[index + 1] == ' ') return index
            }
        }
        return -1
    }

    private fun nextBlockIndent(lines: List<String>, from: Int): Int {
        for (index in from until lines.size) {
            val line = lines[index]
            if (line.isBlank()) continue
            return line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        }
        return 0
    }

    private fun parseScalar(text: String): Any? = when {
        text == "null" || text == "~" -> null
        text == "true" -> true
        text == "false" -> false
        text.toDoubleOrNull() != null -> text.toDouble()
        text.startsWith("[") && text.endsWith("]") -> text
            .removePrefix("[").removeSuffix("]")
            .split(",")
            .map { unquote(it.trim()) }
            .filter { it.isNotEmpty() }
        else -> unquote(text)
    }

    private fun unquote(text: String): String {
        if (text.length >= 2 && (text.first() == '"' || text.first() == '\'') && text.last() == text.first()) {
            val body = text.substring(1, text.length - 1)
            return body
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
        }
        return text
    }

    private fun needsQuotes(value: String): Boolean =
        value.isEmpty() || value.contains(':') || value.contains('#') || value.trim() != value ||
            value.startsWith("-") || value.startsWith("*") || value.startsWith("[") || value.startsWith("{") ||
            value == "true" || value == "false" || value == "null" || value.toDoubleOrNull() != null

    private fun quoteIfNeeded(key: String): String =
        if (key.contains(':') || key.contains('#') || key.startsWith(" ") || key.endsWith(" ")) JsonWriter.quoteString(key) else key
}

object PropertiesCatalogFormat : CatalogFormat {
    override val id: Format = Format.PROPERTIES

    override fun decode(text: String, ctx: FormatContext): DecodedCatalog {
        val entries = mutableListOf<CatalogEntry>()
        var pending = mutableListOf<String>()
        for (rawLine in text.replace("\r\n", "\n").split("\n")) {
            val line = rawLine.trim()
            when {
                line.isEmpty() -> pending = mutableListOf()
                line.startsWith("#") || line.startsWith("!") -> pending += line.drop(1).trim()
                else -> {
                    val separator = line.indexOfFirst { it == '=' || it == ':' }.let { if (it == -1) line.length else it }
                    val key = unescape(line.substring(0, separator).trim())
                    val value = unescape(line.substring((separator + 1).coerceAtMost(line.length)).trim())
                    if (key.isNotEmpty()) {
                        entries += CatalogEntry(
                            key = key,
                            value = value,
                            comment = pending.takeIf { it.isNotEmpty() }?.joinToString(" "),
                        )
                    }
                    pending = mutableListOf()
                }
            }
        }
        return DecodedCatalog(entries)
    }

    override fun encode(decoded: DecodedCatalog, ctx: FormatContext): String = buildString {
        for (entry in decoded.entries) {
            entry.comment?.let { append("# ").append(it).append('\n') }
            append(escape(entry.key)).append('=').append(escape(entry.value ?: "")).append('\n')
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun unescape(value: String): String = Regex("\\\\u([0-9a-fA-F]{4})|\\\\n|\\\\r|\\\\t|\\\\\\\\").replace(value) { match ->
        val hex = match.groupValues[1]
        when {
            hex.isNotEmpty() -> hex.toInt(16).toChar().toString()
            match.value == "\\n" -> "\n"
            match.value == "\\r" -> "\r"
            match.value == "\\t" -> "\t"
            else -> "\\"
        }
    }
}
