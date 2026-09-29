package dev.lingoflow.shared.domain.doc

/**
 * Importers that keep the paragraph structure of the original document.
 *
 * Plain text, Markdown and subtitles are parsed here (pure Kotlin, unit tested);
 * DOCX and PDF need byte-level access to archives and are provided by the
 * platform modules through [BinaryImporter].
 */

object TextImporter {
    /** Splits on blank lines, keeping each paragraph as one segment. */
    fun import(text: String, id: String, title: String, sourceLang: String, targetLang: String): Document {
        val paragraphs = text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val segments = paragraphs.mapIndexed { index, paragraph ->
            Segment(
                id = index,
                kind = if (paragraph.length <= 80 && paragraph.endsWith(":")) BlockKind.HEADING else BlockKind.PARAGRAPH,
                source = paragraph,
            )
        }
        return Document.empty(id, title, sourceLang, targetLang, DocFormat.TEXT).withSegments(segments)
    }
}

object MarkdownImporter {

    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val listItem = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val quote = Regex("^>\\s?(.*)$")
    private val tableRow = Regex("^\\|.*\\|$")
    private val fence = Regex("^```(.*)$")

    /**
     * Block level Markdown parser: fenced code stays verbatim, everything else is
     * one segment per paragraph/list item so the bilingual export can rebuild the
     * same document shape.
     */
    fun import(text: String, id: String, title: String, sourceLang: String, targetLang: String): Document {
        val lines = text.replace("\r\n", "\n").split("\n")
        val segments = mutableListOf<Segment>()
        var inFence = false
        var buffer = StringBuilder()
        var counter = 0

        fun flush() {
            val block = buffer.toString().trim()
            buffer = StringBuilder()
            if (block.isEmpty()) return
            segments += Segment(id = counter++, kind = BlockKind.PARAGRAPH, source = block)
        }

        for (line in lines) {
            when {
                fence.matches(line) -> {
                    inFence = !inFence
                    // Keep the fence as a structural marker so the export stays valid.
                    segments += Segment(id = counter++, kind = BlockKind.CODE, source = line, target = line, locked = true)
                }
                inFence -> segments += Segment(id = counter++, kind = BlockKind.CODE, source = line, target = line, locked = true)
                line.isBlank() -> flush()
                heading.matches(line) -> {
                    flush()
                    val match = heading.matchEntire(line)!!
                    val level = match.groupValues[1].length
                    segments += Segment(
                        id = counter++,
                        kind = BlockKind.HEADING,
                        source = match.groupValues[2],
                        level = level,
                    )
                }
                listItem.matches(line) -> {
                    flush()
                    val match = listItem.matchEntire(line)!!
                    val indent = match.groupValues[1].length / 2
                    segments += Segment(
                        id = counter++,
                        kind = BlockKind.LIST_ITEM,
                        source = match.groupValues[3],
                        level = indent,
                    )
                }
                quote.matches(line) -> {
                    flush()
                    segments += Segment(
                        id = counter++,
                        kind = BlockKind.QUOTE,
                        source = quote.matchEntire(line)!!.groupValues[1],
                    )
                }
                tableRow.matches(line) -> {
                    flush()
                    segments += Segment(id = counter++, kind = BlockKind.TABLE, source = line)
                }
                else -> {
                    if (buffer.isNotEmpty()) buffer.append(' ')
                    buffer.append(line.trim())
                }
            }
        }
        flush()
        return Document.empty(id, title, sourceLang, targetLang, DocFormat.MARKDOWN).withSegments(segments)
    }
}

object SubtitleImporter {
    /** Minimal SRT parser: index, timecode, text lines. */
    fun import(text: String, id: String, title: String, sourceLang: String, targetLang: String): Document {
        val blocks = text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        var counter = 0
        val segments = mutableListOf<Segment>()
        for (block in blocks) {
            val lines = block.trim().split("\n")
            if (lines.size < 2) continue
            val timecode = lines.firstOrNull { it.contains("-->") } ?: continue
            val textLines = lines.dropWhile { it != timecode }.drop(1)
                .filter { it.isNotBlank() }
            if (textLines.isEmpty()) continue
            segments += Segment(
                id = counter++,
                kind = BlockKind.SUBTITLE,
                source = textLines.joinToString("\n"),
                note = timecode,
            )
        }
        return Document.empty(id, title, sourceLang, targetLang, DocFormat.SUBTITLE).withSegments(segments)
    }
}

/**
 * `expect`-style hook for binary formats. Android and desktop supply their own
 * extractor; when none is available the UI reports it honestly.
 */
interface BinaryImporter {
    val supported: Set<DocFormat>

    /** Returns the extracted text or null when the platform cannot parse it. */
    fun extract(fileName: String, bytes: ByteArray): ExtractedDocument?
}

/** Parsed document plus the structure the extractor could recover. */
data class ExtractedDocument(
    val text: String,
    val paragraphs: List<String> = emptyList(),
    val format: DocFormat,
)
