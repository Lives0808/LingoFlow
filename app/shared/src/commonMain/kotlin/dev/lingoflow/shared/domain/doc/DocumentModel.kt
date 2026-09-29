package dev.lingoflow.shared.domain.doc

/**
 * Document model.
 *
 * A document is a flat list of segments that keeps the original block structure
 * (headings, paragraphs, list items, code, quotes). Translation only ever
 * touches [Segment.source]; the structure survives round-trips and can be
 * exported as a bilingual document.
 */
enum class BlockKind { HEADING, PARAGRAPH, LIST_ITEM, CODE, QUOTE, TABLE, SUBTITLE, CAPTION }

enum class DocFormat { TEXT, MARKDOWN, DOCX, PDF, SUBTITLE, IMAGE }

data class Segment(
    val id: Int,
    val kind: BlockKind,
    /** Original text. Empty for structural blocks that carry no prose. */
    val source: String,
    /** Translation (machine or human). */
    val target: String = "",
    /** AI explanation shown on hover / tap. */
    val note: String? = null,
    /** Heading level or list depth, used by exporters. */
    val level: Int = 0,
    /** True once a human edited the target: protected from automation. */
    val locked: Boolean = false,
    /** Glossary terms matched inside [source]. */
    val matchedTerms: List<String> = emptyList(),
    /** Where the translation came from: "corpus", "offline", "openai", "human". */
    val origin: String = "",
) {
    val translatable: Boolean get() = source.isNotBlank() && kind != BlockKind.CODE
}

data class Document(
    val id: String,
    val title: String,
    val sourceLang: String,
    val targetLang: String,
    val format: DocFormat,
    val segments: List<Segment> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Original file name, kept for exports. */
    val fileName: String = "",
) {
    val translatableSegments: List<Segment> get() = segments.filter { it.translatable }
    val translatedCount: Int get() = translatableSegments.count { it.target.isNotBlank() }
    val progress: Double
        get() = translatableSegments.size.let { if (it == 0) 1.0 else translatedCount.toDouble() / it }

    fun updateSegment(id: Int, transform: (Segment) -> Segment): Document =
        copy(segments = segments.map { if (it.id == id) transform(it) else it }, updatedAt = System.currentTimeMillis())

    fun withSegments(segments: List<Segment>): Document = copy(segments = segments, updatedAt = System.currentTimeMillis())

    companion object {
        fun empty(id: String, title: String, sourceLang: String, targetLang: String, format: DocFormat): Document =
            Document(
                id = id,
                title = title,
                sourceLang = sourceLang,
                targetLang = targetLang,
                format = format,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
    }
}

/** Lightweight metadata used by the document list. */
data class DocumentMeta(
    val id: String,
    val title: String,
    val format: DocFormat,
    val sourceLang: String,
    val targetLang: String,
    val segments: Int,
    val translated: Int,
    val updatedAt: Long,
)
