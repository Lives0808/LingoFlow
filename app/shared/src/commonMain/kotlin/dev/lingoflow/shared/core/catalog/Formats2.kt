package dev.lingoflow.shared.core.catalog

import dev.lingoflow.shared.core.model.CatalogEntry
import dev.lingoflow.shared.core.model.Format

/**
 * Formats whose on-disk shape is line based: gettext PO, Apple .strings,
 * Flutter ARB, CSV and TS/JS modules.
 */

private data class PoEntry(
    val comments: MutableList<String> = mutableListOf(),
    val extracted: MutableList<String> = mutableListOf(),
    val refs: MutableList<String> = mutableListOf(),
    val flags: MutableList<String> = mutableListOf(),
    var msgctxt: String? = null,
    var msgid: String = "",
    var msgidPlural: String? = null,
    val msgstr: MutableList<String> = mutableListOf(),
)

object PoCatalogFormat : CatalogFormat {
    override val id: Format = Format.PO

    override fun decode(text: String, ctx: FormatContext): DecodedCatalog {
        val normalized = text.replace("\r\n", "\n")
        val entries = mutableListOf<CatalogEntry>()
        val template = mutableListOf<PoEntry>()
        val obsolete = mutableListOf<String>()
        var headerLines: List<String> = emptyList()
        var locale = ctx.locale

        for (block in normalized.split(Regex("\n\\s*\n"))) {
            val trimmed = block.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#~")) {
                obsolete += block
                continue
            }
            val parsed = parsePoBlock(block) ?: continue
            template += parsed
            if (parsed.msgid.isEmpty() && parsed.msgctxt == null) {
                headerLines = parsed.msgstr.firstOrNull().orEmpty().split("\n").filter { it.isNotBlank() }
                headerLines.firstOrNull { it.startsWith("Language:", ignoreCase = true) }
                    ?.let { locale = it.substringAfter(':').trim() }
                continue
            }
            val baseKey = if (parsed.msgctxt != null) "${parsed.msgctxt}${Keys.CONTEXT_SEPARATOR}${parsed.msgid}" else parsed.msgid
            val comment = (parsed.extracted + parsed.comments).joinToString(" ").trim().ifBlank { null }
            if (parsed.msgidPlural != null) {
                val categories = PluralCategories.forLocale(locale, parsed.msgstr.size)
                parsed.msgstr.forEachIndexed { index, value ->
                    val category = categories.getOrElse(index) { index.toString() }
                    entries += CatalogEntry("$baseKey[$category]", value, comment)
                }
            } else {
                entries += CatalogEntry(baseKey, parsed.msgstr.firstOrNull() ?: "", comment)
            }
        }
        return DecodedCatalog(
            entries,
            mutableMapOf("template" to template, "header" to headerLines, "obsolete" to obsolete, "locale" to locale),
        )
    }

    override fun encode(decoded: DecodedCatalog, ctx: FormatContext): String {
        @Suppress("UNCHECKED_CAST")
        val template = (decoded.meta["template"] as? List<PoEntry>).orEmpty()
        val header = (decoded.meta["header"] as? List<String>).orEmpty()
        @Suppress("UNCHECKED_CAST")
        val obsolete = (decoded.meta["obsolete"] as? List<String>).orEmpty()

        val byBase = LinkedHashMap<String, Pair<PoEntry, MutableMap<Int, String>>>()
        for (entry in decoded.entries) {
            val match = Regex("^(.*)\\[([a-z0-9]+)\\]$").find(entry.key)
            val baseKey = match?.groupValues?.get(1) ?: entry.key
            val category = match?.groupValues?.get(2)
            val existing = byBase[baseKey]
            val pair = existing ?: run {
                val fromTemplate = template.firstOrNull {
                    val key = if (it.msgctxt != null) "${it.msgctxt}${Keys.CONTEXT_SEPARATOR}${it.msgid}" else it.msgid
                    key == baseKey
                }
                val created = fromTemplate?.copy(
                    comments = fromTemplate.comments.toMutableList(),
                    extracted = fromTemplate.extracted.toMutableList(),
                    refs = fromTemplate.refs.toMutableList(),
                    flags = fromTemplate.flags.toMutableList(),
                    msgstr = fromTemplate.msgstr.toMutableList(),
                ) ?: run {
                    val separator = baseKey.indexOf(Keys.CONTEXT_SEPARATOR)
                    if (separator >= 0) {
                        PoEntry(msgctxt = baseKey.substring(0, separator), msgid = baseKey.substring(separator + 1))
                    } else {
                        PoEntry(msgid = baseKey)
                    }
                }
                created to mutableMapOf()
            }
            val values = pair.second
            if (category != null) {
                val index = PluralCategories.indexOf(category, decoded.meta["locale"] as? String ?: ctx.locale, values.size.coerceAtLeast(2))
                values[index] = entry.value ?: ""
            } else {
                values[0] = entry.value ?: ""
                if (entry.comment != null && pair.first.extracted.isEmpty()) pair.first.extracted += entry.comment
            }
            byBase[baseKey] = pair
        }

        val builder = StringBuilder()
        if (header.isNotEmpty()) {
            builder.append("msgid \"\"\nmsgstr \"\"\n")
            header.forEach { builder.append('"').append(escapePo(it)).append("\\n\"\n") }
        }
        for ((_, pair) in byBase) {
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(renderPoEntry(pair.first, pair.second))
        }
        for (block in obsolete) {
            builder.append('\n').append(block.trim()).append('\n')
        }
        return builder.toString()
    }

    private fun renderPoEntry(entry: PoEntry, values: Map<Int, String>): String = buildString {
        entry.extracted.forEach { append("#. ").append(it).append('\n') }
        if (entry.refs.isNotEmpty()) append("#: ").append(entry.refs.joinToString(" ")).append('\n')
        if (entry.flags.isNotEmpty()) append("#, ").append(entry.flags.joinToString(", ")).append('\n')
        if (entry.msgctxt != null) append("msgctxt \"").append(escapePo(entry.msgctxt!!)).append("\"\n")
        append(renderPoString("msgid", entry.msgid))
        if (values.size > 1 || entry.msgidPlural != null) {
            append(renderPoString("msgid_plural", entry.msgidPlural ?: entry.msgid))
            values.keys.sorted().forEach { index -> append(renderPoString("msgstr[$index]", values[index] ?: "")) }
        } else {
            append(renderPoString("msgstr", values[0] ?: ""))
        }
    }

    private fun renderPoString(field: String, value: String): String {
        val lines = value.split("\n")
        return if (lines.size == 1) {
            "$field \"${escapePo(value)}\"\n"
        } else {
            buildString {
                append(field).append(" \"\"\n")
                lines.forEachIndexed { index, line ->
                    append('"').append(escapePo(line))
                    if (index < lines.size - 1) append("\\n")
                    append("\"\n")
                }
            }
        }
    }

    private fun parsePoBlock(block: String): PoEntry? {
        val entry = PoEntry()
        var field = ""
        var pluralIndex = 0
        var sawField = false
        for (rawLine in block.split("\n")) {
            val line = rawLine.trim()
            when {
                line.startsWith("#.") -> entry.extracted += line.removePrefix("#.").trim()
                line.startsWith("#:") -> entry.refs += line.removePrefix("#:").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                line.startsWith("#,") -> entry.flags += line.removePrefix("#,").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                line.startsWith("#") -> entry.comments += line.removePrefix("#").trim()
                line.startsWith("msgctxt") -> {
                    entry.msgctxt = readPoValue(line.removePrefix("msgctxt"))
                    field = "msgctxt"
                    sawField = true
                }
                line.startsWith("msgid_plural") -> {
                    entry.msgidPlural = readPoValue(line.removePrefix("msgid_plural"))
                    field = "msgidPlural"
                    sawField = true
                }
                line.startsWith("msgid") -> {
                    entry.msgid = readPoValue(line.removePrefix("msgid"))
                    field = "msgid"
                    sawField = true
                }
                line.startsWith("msgstr[") -> {
                    pluralIndex = Regex("^msgstr\\[(\\d+)\\]").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    while (entry.msgstr.size <= pluralIndex) entry.msgstr += ""
                    entry.msgstr[pluralIndex] = readPoValue(line.substringAfter(']'))
                    field = "msgstr"
                    sawField = true
                }
                line.startsWith("msgstr") -> {
                    pluralIndex = 0
                    while (entry.msgstr.isEmpty()) entry.msgstr += ""
                    entry.msgstr[0] = readPoValue(line.removePrefix("msgstr"))
                    field = "msgstr"
                    sawField = true
                }
                line.startsWith("\"") -> {
                    val continuation = readPoValue(line).removeSuffix("\\n")
                    when (field) {
                        "msgctxt" -> entry.msgctxt = (entry.msgctxt ?: "") + continuation
                        "msgid" -> entry.msgid += continuation
                        "msgidPlural" -> entry.msgidPlural = (entry.msgidPlural ?: "") + continuation
                        "msgstr" -> {
                            while (entry.msgstr.size <= pluralIndex) entry.msgstr += ""
                            entry.msgstr[pluralIndex] = (entry.msgstr[pluralIndex] ?: "") + continuation
                        }
                    }
                }
            }
        }
        return if (sawField) entry else null
    }

    private fun readPoValue(rest: String): String {
        val match = Regex("^\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(rest) ?: return ""
        return unescapePo(match.groupValues[1])
    }

    private fun escapePo(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\t", "\\t")
        .replace("\r", "\\r")

    private fun unescapePo(value: String): String = Regex("\\\\(.)").replace(value) { match ->
        when (val char = match.groupValues[1]) {
            "n" -> "\n"
            "t" -> "\t"
            "r" -> "\r"
            "0" -> "\u0000"
            "\\" -> "\\"
            "\"" -> "\""
            else -> char
        }
    }
}

object ArbCatalogFormat : CatalogFormat {
    override val id: Format = Format.ARB

    override fun decode(text: String, ctx: FormatContext): DecodedCatalog {
        val parsed = JsonishParser(text).parse().value
        val entries = mutableListOf<CatalogEntry>()
        val template = LinkedHashMap<String, Any?>()
        if (parsed is JsonValue.Obj) {
            for ((key, value) in parsed.entries) {
                template[key] = value.toPlain()
                if (key.startsWith("@@") || key.startsWith("@")) continue
                val string = (value as? JsonValue.Str)?.value ?: continue
                val meta = parsed["@$key"] as? JsonValue.Obj
                val description = (meta?.get("description") as? JsonValue.Str)?.value
                entries += CatalogEntry(key, string, description)
            }
        }
        return DecodedCatalog(entries, mutableMapOf("template" to template))
    }

    override fun encode(decoded: DecodedCatalog, ctx: FormatContext): String {
        @Suppress("UNCHECKED_CAST")
        val template = LinkedHashMap(decoded.meta["template"] as? Map<String, Any?> ?: emptyMap())
        for (entry in decoded.entries) {
            template[entry.key] = entry.value ?: ""
            val metaKey = "@${entry.key}"
            val existing = template[metaKey]
            if (entry.comment != null && existing == null) {
                template[metaKey] = linkedMapOf("description" to entry.comment)
            }
        }
        // Keep @@metadata first, then keys with their @metadata.
        val ordered = LinkedHashMap<String, Any?>()
        template.filterKeys { it.startsWith("@@") }.forEach { (key, value) -> ordered[key] = value }
        template.forEach { (key, value) ->
            if (key.startsWith("@")) return@forEach
            ordered[key] = value
            template["@$key"]?.let { meta -> ordered["@$key"] = meta }
        }
        return JsonWriter.write(ordered, ctx.indent) + "\n"
    }
}

object StringsCatalogFormat : CatalogFormat {
    override val id: Format = Format.STRINGS

    override fun decode(text: String, ctx: FormatContext): DecodedCatalog {
        val entries = mutableListOf<CatalogEntry>()
        var index = 0
        var pendingComment: String? = null
        while (index < text.length) {
            val char = text[index]
            when {
                char == '/' && text.getOrNull(index + 1) == '*' -> {
                    val end = text.indexOf("*/", index + 2)
                    val body = text.substring(index + 2, if (end == -1) text.length else end)
                        .lines().joinToString(" ") { it.trim().removePrefix("*").trim() }.trim()
                    if (body.isNotEmpty()) pendingComment = body
                    index = if (end == -1) text.length else end + 2
                }
                char == '/' && text.getOrNull(index + 1) == '/' -> {
                    val end = text.indexOf('\n', index + 2)
                    val body = text.substring(index + 2, if (end == -1) text.length else end).trim()
                    if (body.isNotEmpty()) pendingComment = body
                    index = if (end == -1) text.length else end + 1
                }
                char == '"' -> {
                    val key = readStringsLiteral(text, index)
                    if (key == null) {
                        index++
                        continue
                    }
                    index = key.second
                    while (index < text.length && text[index].isWhitespace()) index++
                    if (text.getOrNull(index) != '=') continue
                    index++
                    while (index < text.length && text[index].isWhitespace()) index++
                    if (text.getOrNull(index) != '"') continue
                    val value = readStringsLiteral(text, index) ?: continue
                    index = value.second
                    while (index < text.length && text[index] != ';') index++
                    index++
                    entries += CatalogEntry(key.first, value.first, pendingComment)
                    pendingComment = null
                }
                else -> index++
            }
        }
        return DecodedCatalog(entries)
    }

    override fun encode(decoded: DecodedCatalog, ctx: FormatContext): String = buildString {
        for (entry in decoded.entries) {
            entry.comment?.let { append("/* ").append(it.replace("*/", "*\\/")).append(" */\n") }
            append('"').append(escapeStrings(entry.key)).append("\" = \"")
                .append(escapeStrings(entry.value ?: "")).append("\";\n")
        }
    }

    private fun readStringsLiteral(text: String, start: Int): Pair<String, Int>? {
        if (text.getOrNull(start) != '"') return null
        var index = start + 1
        val builder = StringBuilder()
        while (index < text.length) {
            val char = text[index]
            if (char == '\\') {
                val escaped = text.getOrNull(index + 1) ?: break
                index += 2
                when (escaped) {
                    'n' -> builder.append('\n')
                    't' -> builder.append('\t')
                    'r' -> builder.append('\r')
                    'u' -> {
                        val hex = text.substring(index, (index + 4).coerceAtMost(text.length))
                        builder.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        index += 4
                    }
                    else -> builder.append(escaped)
                }
                continue
            }
            if (char == '"') return builder.toString() to index + 1
            if (char == '\n') return null
            builder.append(char)
            index++
        }
        return null
    }

    private fun escapeStrings(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
}

object CsvCatalogFormat : CatalogFormat {
    override val id: Format = Format.CSV

    override fun decode(text: String, ctx: FormatContext): DecodedCatalog {
        val normalized = text.replace("\r\n", "\n").trimEnd('\n')
        val delimiter = detectDelimiter(normalized)
        val rows = parseCsv(normalized, delimiter)
        val header = rows.firstOrNull().orEmpty()
        val keyColumn = header.indexOfFirst { it.trim().lowercase() in setOf("key", "id", "msgid", "name") }.coerceAtLeast(0)
        val wanted = ctx.locale.lowercase().replace('_', '-')
        var valueColumn = header.indexOfFirst { it.trim().lowercase().replace('_', '-') == wanted }
        if (valueColumn == -1) valueColumn = header.indices.firstOrNull { it != keyColumn } ?: 1
        val entries = mutableListOf<CatalogEntry>()
        for (index in 1 until rows.size) {
            val row = rows[index]
            val key = row.getOrNull(keyColumn)?.takeIf { it.isNotBlank() } ?: continue
            entries += CatalogEntry(key, row.getOrNull(valueColumn))
        }
        return DecodedCatalog(
            entries,
            mutableMapOf("header" to header, "rows" to rows, "keyColumn" to keyColumn, "valueColumn" to valueColumn, "delimiter" to delimiter),
        )
    }

    override fun encode(decoded: DecodedCatalog, ctx: FormatContext): String {
        @Suppress("UNCHECKED_CAST")
        val header = (decoded.meta["header"] as? List<String>) ?: listOf("key", ctx.locale)
        @Suppress("UNCHECKED_CAST")
        val rows = (decoded.meta["rows"] as? List<List<String>>).orEmpty()
        val keyColumn = (decoded.meta["keyColumn"] as? Int) ?: 0
        val valueColumn = (decoded.meta["valueColumn"] as? Int) ?: 1
        val delimiter = (decoded.meta["delimiter"] as? String) ?: ","

        val output = mutableListOf(header)
        val used = mutableSetOf<String>()
        for (entry in decoded.entries) {
            val template = rows.firstOrNull { it.getOrNull(keyColumn) == entry.key } ?: List(header.size) { "" }
            val row = template.toMutableList()
            while (row.size < header.size) row += ""
            row[keyColumn] = entry.key
            row[valueColumn] = entry.value ?: ""
            output += row
            used += entry.key
        }
        for (row in rows) {
            val key = row.getOrNull(keyColumn) ?: continue
            if (key !in used) output += row
        }
        return output.joinToString("\n") { row -> row.joinToString(delimiter) { quoteCsv(it, delimiter) } } + "\n"
    }

    private fun detectDelimiter(text: String): String {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val candidates = listOf(",", "\t", ";", "|")
        return candidates.maxByOrNull { firstLine.count { char -> char.toString() == it } }?.takeIf {
            firstLine.count { char -> char.toString() == it } > 0
        } ?: ","
    }

    private fun parseCsv(text: String, delimiter: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                inQuotes && char == '"' && text.getOrNull(index + 1) == '"' -> {
                    cell.append('"')
                    index++
                }
                inQuotes && char == '"' -> inQuotes = false
                !inQuotes && char == '"' -> inQuotes = true
                !inQuotes && char == delimiter.first() -> {
                    row += cell.toString()
                    cell.clear()
                }
                !inQuotes && char == '\n' -> {
                    row += cell.toString()
                    cell.clear()
                    rows += row
                    row = mutableListOf()
                }
                else -> cell.append(char)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows
    }

    private fun quoteCsv(value: String, delimiter: String): String =
        if (value.contains(delimiter.first()) || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}

object TsJsCatalogFormat : CatalogFormat {
    override val id: Format = Format.TSJS

    override fun decode(text: String, ctx: FormatContext): DecodedCatalog {
        val anchor = Regex("(?:export\\s+default\\s+|module\\.exports\\s*=\\s*|export\\s+const\\s+\\w+\\s*(?::[^=]+)?=\\s*|const\\s+\\w+\\s*(?::[^=]+)?=\\s*)")
            .find(text)
        val objectStart = findObjectStart(text, anchor?.range?.last?.plus(1) ?: 0)
        if (objectStart < 0) return DecodedCatalog(emptyList())
        val parsed = try {
            JsonishParser(text, objectStart).parse()
        } catch (error: JsonishException) {
            return DecodedCatalog(emptyList())
        }
        val plain = parsed.value.toPlain()
        val entries = Keys.flatten(plain, ctx.keySeparator, true).map { (key, value) ->
            CatalogEntry(key, Keys.asString(value), parsed.comments[key])
        }
        return DecodedCatalog(
            entries,
            mutableMapOf("prefix" to text.substring(0, objectStart), "suffix" to text.substring(parsed.end)),
        )
    }

    override fun encode(decoded: DecodedCatalog, ctx: FormatContext): String {
        val prefix = (decoded.meta["prefix"] as? String) ?: "export default "
        val suffix = (decoded.meta["suffix"] as? String) ?: ";\n"
        val tree = Keys.unflatten(decoded.entries.map { it.key to (it.value ?: "") }, ctx.keySeparator, true)
        val comments = decoded.entries.mapNotNull { entry -> entry.comment?.let { entry.key to it } }.toMap()
        return prefix + JsonWriter.write(tree, ctx.indent, comments = comments) + suffix
    }

    private fun findObjectStart(text: String, from: Int): Int {
        var index = from
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' || char == '\'' || char == '`' -> {
                    index = skipString(text, index)
                }
                char == '/' && text.getOrNull(index + 1) == '/' -> {
                    val end = text.indexOf('\n', index)
                    index = if (end == -1) text.length else end + 1
                }
                char == '/' && text.getOrNull(index + 1) == '*' -> {
                    val end = text.indexOf("*/", index)
                    index = if (end == -1) text.length else end + 2
                }
                char == '{' -> return index
                else -> index++
            }
        }
        return -1
    }

    private fun skipString(text: String, start: Int): Int {
        val quote = text[start]
        var index = start + 1
        while (index < text.length) {
            when {
                text[index] == '\\' -> index += 2
                text[index] == quote -> return index + 1
                text[index] == '\n' && quote != '`' -> return index
                else -> index++
            }
        }
        return text.length
    }
}

/** CLDR plural categories, shared by the PO writer and the ICU validator. */
object PluralCategories {
    private val table = mapOf(
        "en" to listOf("one", "other"),
        "de" to listOf("one", "other"),
        "nl" to listOf("one", "other"),
        "sv" to listOf("one", "other"),
        "es" to listOf("one", "other"),
        "it" to listOf("one", "other"),
        "pt" to listOf("one", "other"),
        "fr" to listOf("one", "many", "other"),
        "ru" to listOf("one", "few", "many", "other"),
        "uk" to listOf("one", "few", "many", "other"),
        "pl" to listOf("one", "few", "many", "other"),
        "cs" to listOf("one", "few", "many", "other"),
        "ar" to listOf("zero", "one", "two", "few", "many", "other"),
        "he" to listOf("one", "two", "many", "other"),
        "tr" to listOf("one", "other"),
        "id" to listOf("other"),
        "vi" to listOf("other"),
        "th" to listOf("other"),
        "zh" to listOf("other"),
        "ja" to listOf("other"),
        "ko" to listOf("other"),
    )

    fun forLocale(locale: String): List<String> {
        val normalized = locale.lowercase().replace('_', '-')
        return table[normalized] ?: table[normalized.substringBefore('-')] ?: listOf("one", "other")
    }

    fun forLocale(locale: String, count: Int): List<String> {
        val categories = forLocale(locale)
        if (categories.size == count) return categories
        if (count == 1) return listOf("other")
        return listOf("zero", "one", "two", "few", "many", "other").take(count)
    }

    fun indexOf(category: String, locale: String, existing: Int): Int {
        val categories = forLocale(locale, existing.coerceAtLeast(2))
        val index = categories.indexOf(category)
        return if (index >= 0) index else if (category == "other") (existing - 1).coerceAtLeast(0) else category.toIntOrNull() ?: 0
    }
}
