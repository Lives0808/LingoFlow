package dev.lingoflow.shared.domain.doc

import dev.lingoflow.shared.core.util.SimpleDate

/**
 * Exporters. Everything the user produced stays convertible: bilingual Markdown
 * (developers), plain target text, JSON/CSV for tooling, SRT for subtitles and a
 * printable HTML that doubles as the PDF path.
 */
data class ExportFile(val fileName: String, val mimeType: String, val content: String)


object Exporters {

    fun bilingualMarkdown(document: Document, includeNotes: Boolean = false): ExportFile {
        val builder = StringBuilder()
        builder.append("# ${document.title} (bilingual)\n\n")
        builder.append("> ${document.sourceLang} → ${document.targetLang} · ${SimpleDate.format(System.currentTimeMillis())}\n\n")
        for (segment in document.segments) {
            when (segment.kind) {
                BlockKind.CODE -> builder.append(segment.source).append('\n')
                BlockKind.HEADING -> {
                    builder.append("#".repeat((segment.level.coerceIn(1, 6)))).append(' ').append(segment.source).append('\n')
                    if (segment.target.isNotBlank()) builder.append("\n> ").append(segment.target).append('\n')
                    builder.append('\n')
                }
                BlockKind.LIST_ITEM -> {
                    val indent = "  ".repeat(segment.level)
                    builder.append('*').append(' ').append(indent).append(segment.source).append('\n')
                    if (segment.target.isNotBlank()) builder.append(' ').append(' ').append(indent).append("→ ").append(segment.target).append('\n')
                }
                BlockKind.QUOTE -> {
                    builder.append("> ").append(segment.source).append('\n')
                    if (segment.target.isNotBlank()) builder.append("> _").append(segment.target).append("_\n")
                    builder.append('\n')
                }
                BlockKind.SUBTITLE -> {
                    builder.append(segment.note ?: "").append('\n')
                    builder.append(segment.source).append('\n')
                    if (segment.target.isNotBlank()) builder.append(segment.target).append('\n')
                    builder.append('\n')
                }
                else -> {
                    builder.append(segment.source).append("\n\n")
                    if (segment.target.isNotBlank()) builder.append(segment.target).append("\n\n")
                    if (includeNotes && segment.note != null) builder.append("> _").append(segment.note).append("_\n\n")
                }
            }
        }
        return ExportFile("${slug(document.title)}-bilingual.md", "text/markdown", builder.toString())
    }

    fun targetMarkdown(document: Document): ExportFile {
        val builder = StringBuilder()
        for (segment in document.segments) {
            val text = segment.target.ifBlank { segment.source }
            when (segment.kind) {
                BlockKind.CODE -> builder.append(segment.source).append('\n')
                BlockKind.HEADING -> builder.append("#".repeat(segment.level.coerceIn(1, 6))).append(' ').append(text).append("\n\n")
                BlockKind.LIST_ITEM -> builder.append("  ".repeat(segment.level)).append("- ").append(text).append('\n')
                BlockKind.QUOTE -> builder.append("> ").append(text).append("\n\n")
                BlockKind.TABLE -> builder.append(text).append('\n')
                else -> builder.append(text).append("\n\n")
            }
        }
        return ExportFile("${slug(document.title)}-${document.targetLang}.md", "text/markdown", builder.toString())
    }

    fun plainText(document: Document): ExportFile {
        val content = document.segments.joinToString("\n\n") { it.target.ifBlank { it.source } }
        return ExportFile("${slug(document.title)}-${document.targetLang}.txt", "text/plain", content)
    }

    fun csv(document: Document): ExportFile {
        val builder = StringBuilder("id,kind,source,target,origin\n")
        for (segment in document.segments) {
            builder.append(segment.id).append(',')
                .append(segment.kind.name.lowercase()).append(',')
                .append(quoteCsv(segment.source)).append(',')
                .append(quoteCsv(segment.target)).append(',')
                .append(segment.origin).append('\n')
        }
        return ExportFile("${slug(document.title)}.csv", "text/csv", builder.toString())
    }

    fun json(document: Document): ExportFile {
        val builder = StringBuilder()
        builder.append("{\n  \"title\": ").append(quoteJson(document.title))
        builder.append(",\n  \"sourceLang\": ").append(quoteJson(document.sourceLang))
        builder.append(",\n  \"targetLang\": ").append(quoteJson(document.targetLang))
        builder.append(",\n  \"format\": ").append(quoteJson(document.format.name.lowercase()))
        builder.append(",\n  \"segments\": [\n")
        builder.append(
            document.segments.joinToString(",\n") { segment ->
                """    {"id":${segment.id},"kind":"${segment.kind.name.lowercase()}","source":${quoteJson(segment.source)},"target":${quoteJson(segment.target)},"origin":"${segment.origin}"}"""
            },
        )
        builder.append("\n  ]\n}\n")
        return ExportFile("${slug(document.title)}.json", "application/json", builder.toString())
    }

    fun srt(document: Document): ExportFile {
        val builder = StringBuilder()
        var index = 1
        for (segment in document.segments.filter { it.kind == BlockKind.SUBTITLE }) {
            builder.append(index++).append('\n')
            builder.append(segment.note ?: "00:00:00,000 --> 00:00:02,000").append('\n')
            builder.append(segment.target.ifBlank { segment.source }).append("\n\n")
        }
        return ExportFile("${slug(document.title)}-${document.targetLang}.srt", "application/x-subrip", builder.toString())
    }

    /** Self-contained HTML; "Print to PDF" gives a paginated, CJK-safe PDF. */
    fun html(document: Document, bilingual: Boolean = true): ExportFile {
        val rows = document.segments.joinToString("\n") { segment ->
            when (segment.kind) {
                BlockKind.CODE -> "<pre>${escape(segment.source)}</pre>"
                BlockKind.HEADING -> "<h${segment.level.coerceIn(1, 6)}>${escape(segment.source)}</h${segment.level.coerceIn(1, 6)}>" +
                    if (bilingual && segment.target.isNotBlank()) "<p class=\"target\">${escape(segment.target)}</p>" else ""
                BlockKind.LIST_ITEM -> "<li>${escape(segment.source)}" +
                    if (bilingual && segment.target.isNotBlank()) " <span class=\"target\">— ${escape(segment.target)}</span>" else "" + "</li>"
                BlockKind.QUOTE -> "<blockquote>${escape(segment.source)}" +
                    if (bilingual && segment.target.isNotBlank()) "<div class=\"target\">${escape(segment.target)}</div>" else "" + "</blockquote>"
                else -> "<p>${escape(segment.source)}</p>" +
                    if (bilingual && segment.target.isNotBlank()) "<p class=\"target\">${escape(segment.target)}</p>" else ""
            }
        }
        val html = """<!doctype html>
<html lang="${document.targetLang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(document.title)}</title>
<style>
  :root { color-scheme: light dark; }
  body { font: 16px/1.7 system-ui, -apple-system, "Noto Sans SC", sans-serif; max-width: 46rem; margin: 3rem auto; padding: 0 1.25rem; }
  h1,h2,h3,h4,h5,h6 { line-height: 1.3; }
  .target { color: #4f46e5; }
  pre { background: color-mix(in srgb, currentColor 8%, transparent); padding: .75rem 1rem; border-radius: 10px; overflow-x: auto; }
  blockquote { border-left: 3px solid color-mix(in srgb, currentColor 25%, transparent); margin: 1rem 0; padding: .25rem 1rem; }
  .meta { opacity: .65; font-size: .85rem; margin-bottom: 2rem; }
  @media print { body { margin: 0; max-width: none; } }
</style>
</head>
<body>
<h1>${escape(document.title)}</h1>
<p class="meta">${document.sourceLang} → ${document.targetLang} · bilingual export from LingoFlow</p>
$rows
</body>
</html>
"""
        return ExportFile("${slug(document.title)}-bilingual.html", "text/html", html)
    }

    fun slug(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
        .trim('-')
        .ifBlank { "document" }
        .take(48)

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun quoteJson(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""

    private fun quoteCsv(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
