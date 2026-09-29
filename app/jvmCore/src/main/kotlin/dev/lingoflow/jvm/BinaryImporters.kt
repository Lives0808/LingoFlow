package dev.lingoflow.jvm

import dev.lingoflow.shared.domain.doc.BinaryImporter
import dev.lingoflow.shared.domain.doc.DocFormat
import dev.lingoflow.shared.domain.doc.ExtractedDocument
import java.io.ByteArrayInputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * DOCX and PDF text extraction.
 *
 * Both are best-effort by design and stay dependency free:
 *  - DOCX: reads `word/document.xml` from the OOXML zip and keeps one paragraph
 *    per `<w:p>`, so headings and lists survive as blocks.
 *  - PDF: inflates FlateDecode content streams, reads the text operators and
 *    applies any `ToUnicode` CMaps found in the file. Layout, columns and
 *    scanned pages are out of scope — the UI says so instead of pretending.
 */
class JvmBinaryImporter : BinaryImporter {
    override val supported: Set<DocFormat> = setOf(DocFormat.DOCX, DocFormat.PDF)

    override fun extract(fileName: String, bytes: ByteArray): ExtractedDocument? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "docx" -> extractDocx(bytes)?.let { ExtractedDocument(text = it, paragraphs = it.split("\n\n"), format = DocFormat.DOCX) }
        "pdf" -> extractPdf(bytes)?.let { ExtractedDocument(text = it, paragraphs = it.split("\n\n"), format = DocFormat.PDF) }
        else -> null
    }

    // ------------------------------------------------------------------- DOCX

    fun extractDocx(bytes: ByteArray): String? {
        val xml = readZipEntry(bytes, "word/document.xml") ?: return null
        val paragraphs = mutableListOf<String>()
        val paragraphRegex = Regex("<w:p[ >][\\s\\S]*?</w:p>|<w:p/>", RegexOption.IGNORE_CASE)
        val styleRegex = Regex("<w:pStyle[^>]*w:val=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val textRegex = Regex("<w:t[^>]*>([\\s\\S]*?)</w:t>", RegexOption.IGNORE_CASE)
        val breakRegex = Regex("<w:(br|tab)\\s*/?>", RegexOption.IGNORE_CASE)

        for (match in paragraphRegex.findAll(xml)) {
            val paragraph = match.value
            val style = styleRegex.find(paragraph)?.groupValues?.get(1).orEmpty()
            val builder = StringBuilder()
            var cursor = 0
            // Keep inline order of text runs and breaks.
            Regex("<w:t[^>]*>[\\s\\S]*?</w:t>|<w:(?:br|tab)\\s*/?>", RegexOption.IGNORE_CASE).findAll(paragraph).forEach { token ->
                if (token.range.first > cursor) {
                    cursor = token.range.first
                }
                val piece = token.value
                when {
                    piece.startsWith("<w:t") -> {
                        val value = textRegex.find(piece)?.groupValues?.get(1) ?: ""
                        builder.append(unescapeXml(value))
                    }
                    piece.contains("w:tab") -> builder.append('\t')
                    else -> builder.append(' ')
                }
                cursor = token.range.last + 1
            }
            val text = builder.toString().trim()
            if (text.isEmpty()) continue
            val headingLevel = Regex("heading\\s*(\\d)", RegexOption.IGNORE_CASE).find(style)?.groupValues?.get(1)?.toIntOrNull()
            paragraphs += if (headingLevel != null) "${"#".repeat(headingLevel.coerceIn(1, 6))} $text" else text
        }
        if (paragraphs.isEmpty()) return null
        return paragraphs.joinToString("\n\n")
    }

    private fun readZipEntry(bytes: ByteArray, entryName: String): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.equals(entryName, ignoreCase = true)) {
                    return zip.readBytes().decodeToString()
                }
                zip.closeEntry()
            }
        }
        return null
    }

    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    // -------------------------------------------------------------------- PDF

    fun extractPdf(bytes: ByteArray): String? {
        val content = StringBuilder()
        var index = 0
        var sawText = false
        val cmap = HashMap<Int, String>()

        // Pass 1: collect ToUnicode CMaps (they can appear before or after the page).
        forEachStream(bytes) { stream, dictionary ->
            val text = stream.decodeToString()
            if (dictionary.contains("/ToUnicode") || text.contains("beginbfchar") || text.contains("beginbfrange")) {
                parseToUnicode(text, cmap)
            }
        }

        // Pass 2: text operators.
        forEachStream(bytes) { stream, _ ->
            val text = stream.decodeToString()
            if (!text.contains("BT")) return@forEachStream
            sawText = true
            content.append(extractTextOperators(text, cmap)).append('\n')
        }

        if (!sawText) return null
        return normalizePdfText(content.toString())
    }

    private fun forEachStream(bytes: ByteArray, action: (ByteArray, String) -> Unit) {
        val text = bytes.decodeToString()
        val streamRegex = Regex("<<([\\s\\S]{0,4000}?)>>\\s*stream\\r?\\n", RegexOption.IGNORE_CASE)
        for (match in streamRegex.findAll(text)) {
            val dictionary = match.value
            val start = match.range.last + 1
            val end = text.indexOf("endstream", start)
            if (end < 0 || end <= start) continue
            var payload = bytes.copyOfRange(start, end)
            if (dictionary.contains("FlateDecode")) {
                payload = inflate(payload) ?: continue
            }
            action(payload, dictionary)
        }
    }

    private fun inflate(data: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater()
        inflater.setInput(data)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && inflater.needsInput()) break
            output.write(buffer, 0, count)
        }
        inflater.end()
        output.toByteArray()
    }.getOrNull()

    /** Extracts text from a decoded content stream, honouring BT/ET, Tj/TJ/'/" and Td/TD/T*. */
    private fun extractTextOperators(content: String, cmap: Map<Int, String>): String {
        val out = StringBuilder()
        val tokenRegex = Regex(
            "(\\((?:\\\\.|[^\\\\()])*\\))|(<[0-9A-Fa-f\\s]*>)|(\\[)|(\\])|(TJ|Tj|Tf|Td|TD|T\\*|BT|ET|'|\")",
        )
        val stack = ArrayDeque<String>()
        var pendingArray: MutableList<String>? = null
        for (match in tokenRegex.findAll(content)) {
            val token = match.value
            when {
                token.startsWith("(") -> {
                    val value = decodeLiteralString(token)
                    if (pendingArray != null) pendingArray!!.add(value) else stack.addLast(value)
                }
                token.startsWith("<") -> {
                    val value = decodeHexString(token, cmap)
                    if (pendingArray != null) pendingArray!!.add(value) else stack.addLast(value)
                }
                token == "[" -> pendingArray = mutableListOf()
                token == "]" -> {
                    pendingArray?.let { stack.addLast(it.joinToString("")) }
                    pendingArray = null
                }
                token == "TJ" -> {
                    val text = stack.joinToString("")
                    out.append(text)
                    stack.clear()
                }
                token == "Tj" || token == "'" || token == "\"" -> {
                    val text = stack.lastOrNull() ?: ""
                    if (!out.endsWith("\n")) out.append(text)
                    stack.clear()
                }
                token == "Td" || token == "TD" || token == "T*" -> {
                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                }
                token == "ET" -> if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                else -> Unit
            }
        }
        return out.toString()
    }

    private fun decodeLiteralString(token: String): String {
        val body = token.removePrefix("(").removeSuffix(")")
        val builder = StringBuilder()
        var index = 0
        while (index < body.length) {
            val char = body[index]
            if (char == '\\' && index + 1 < body.length) {
                val next = body[index + 1]
                index += 2
                when (next) {
                    'n' -> builder.append('\n')
                    'r' -> builder.append('\r')
                    't' -> builder.append('\t')
                    'b' -> builder.append('\b')
                    'f' -> builder.append('\u000C')
                    '(' -> builder.append('(')
                    ')' -> builder.append(')')
                    '\\' -> builder.append('\\')
                    in '0'..'7' -> {
                        var octal = next.toString()
                        while (octal.length < 3 && index < body.length && body[index] in '0'..'7') {
                            octal += body[index]
                            index++
                        }
                        builder.append((octal.toIntOrNull(8) ?: 0).toChar())
                    }
                    else -> builder.append(next)
                }
                continue
            }
            builder.append(char)
            index++
        }
        return builder.toString()
    }

    private fun decodeHexString(token: String, cmap: Map<Int, String>): String {
        val hex = token.removePrefix("<").removeSuffix(">").replace(Regex("\\s"), "")
        if (hex.isEmpty()) return ""
        val builder = StringBuilder()
        var index = 0
        while (index + 1 < hex.length) {
            val code = hex.substring(index, index + 2).toIntOrNull(16) ?: 0
            index += 2
            val mapped = cmap[code]
            if (mapped != null) builder.append(mapped) else if (code in 32..126 || code > 159) builder.append(code.toChar())
        }
        return builder.toString()
    }

    /** Parses bfchar/bfrange mappings from a ToUnicode CMap. */
    private fun parseToUnicode(cmapText: String, into: MutableMap<Int, String>) {
        Regex("beginbfchar([\\s\\S]*?)endbfchar").findAll(cmapText).forEach { block ->
            Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(block.groupValues[1]).forEach { pair ->
                val source = pair.groupValues[1].toIntOrNull(16) ?: return@forEach
                into[source] = hexToText(pair.groupValues[2])
            }
        }
        Regex("beginbfrange([\\s\\S]*?)endbfrange").findAll(cmapText).forEach { block ->
            Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(block.groupValues[1]).forEach { triple ->
                val from = triple.groupValues[1].toIntOrNull(16) ?: return@forEach
                val to = triple.groupValues[2].toIntOrNull(16) ?: return@forEach
                val targetStart = triple.groupValues[3].toIntOrNull(16) ?: return@forEach
                for (code in from..to) {
                    val target = targetStart + (code - from)
                    into[code] = codePointToText(target)
                }
            }
        }
    }

    private fun hexToText(hex: String): String {
        val builder = StringBuilder()
        var index = 0
        while (index + 1 < hex.length) {
            val code = hex.substring(index, minOf(index + 4, hex.length)).toIntOrNull(16) ?: 0
            builder.append(codePointToText(code))
            index += if (hex.length - index >= 4) 4 else 2
        }
        return builder.toString()
    }

    private fun codePointToText(code: Int): String = runCatching {
        String(Character.toChars(code))
    }.getOrDefault("")

    private fun normalizePdfText(raw: String): String {
        val lines = raw.replace("\r\n", "\n")
            .split('\n')
            .map { it.replace(Regex("[ \\t]{2,}"), " ").trim() }
            .filter { it.isNotEmpty() }
        // Merge very short fragments (typical for column layouts) into paragraphs.
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        for (line in lines) {
            if (current.isNotEmpty()) current.append(' ')
            current.append(line)
            if (current.length > 120 || line.endsWith('.') || line.endsWith('。')) {
                paragraphs += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) paragraphs += current.toString()
        return paragraphs.joinToString("\n\n")
    }
}
