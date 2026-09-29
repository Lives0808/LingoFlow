package dev.lingoflow.shared

import dev.lingoflow.shared.core.util.Sha256
import dev.lingoflow.shared.core.util.SimpleDate
import dev.lingoflow.shared.domain.ChatEngine
import dev.lingoflow.shared.domain.CorpusEntry
import dev.lingoflow.shared.domain.CorpusMatcher
import dev.lingoflow.shared.domain.DocumentTranslator
import dev.lingoflow.shared.domain.SegmentOps
import dev.lingoflow.shared.domain.Term
import dev.lingoflow.shared.domain.TermExtractor
import dev.lingoflow.shared.domain.TermMatcher
import dev.lingoflow.shared.domain.TranslateOptions
import dev.lingoflow.shared.domain.doc.BlockKind
import dev.lingoflow.shared.domain.doc.DocFormat
import dev.lingoflow.shared.domain.doc.Document
import dev.lingoflow.shared.domain.doc.Exporters
import dev.lingoflow.shared.domain.doc.MarkdownImporter
import dev.lingoflow.shared.domain.doc.Segment
import dev.lingoflow.shared.domain.doc.SubtitleImporter
import dev.lingoflow.shared.domain.doc.TextImporter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A scripted engine so the translator can be tested without a network or a key. */
private class FakeChat(private val respond: (String) -> String) : ChatEngine {
    override val id: String = "fake"
    override val label: String = "fake model"
    override val requiresKey: Boolean = false
    var lastSystem: String = ""
    var lastUser: String = ""

    override suspend fun complete(system: String, user: String, temperature: Double): String {
        lastSystem = system
        lastUser = user
        return respond(user)
    }
}

class DocumentImportTest {

    @Test
    fun `markdown keeps headings lists and code fences`() {
        val markdown = """
            # Title

            Intro paragraph with **bold** text.

            - first item
            - second item

            ```kotlin
            val x = 1
            ```

            > a quote
        """.trimIndent()
        val document = MarkdownImporter.import(markdown, "d1", "README", "en", "zh-CN")
        assertEquals(BlockKind.HEADING, document.segments.first().kind)
        assertEquals(1, document.segments.first().level)
        assertTrue(document.segments.any { it.kind == BlockKind.LIST_ITEM })
        assertTrue(document.segments.any { it.kind == BlockKind.CODE })
        assertTrue(document.segments.any { it.kind == BlockKind.QUOTE })
        // Code segments are never offered for translation.
        assertTrue(document.translatableSegments.none { it.kind == BlockKind.CODE })
    }

    @Test
    fun `plain text splits paragraphs and srt keeps timecodes`() {
        val text = TextImporter.import("One paragraph.\n\nAnother one.", "d2", "notes", "en", "zh-CN")
        assertEquals(2, text.segments.size)

        val srt = """
            1
            00:00:01,000 --> 00:00:03,000
            Hello world

            2
            00:00:03,500 --> 00:00:05,000
            Second line
        """.trimIndent()
        val subtitle = SubtitleImporter.import(srt, "d3", "clip", "en", "zh-CN")
        assertEquals(2, subtitle.segments.size)
        assertEquals("00:00:01,000 --> 00:00:03,000", subtitle.segments.first().note)
    }

    @Test
    fun `bilingual export rebuilds markdown and csv`() {
        val document = MarkdownImporter.import("# Hello\n\nBody text.", "d4", "doc", "en", "zh-CN")
            .let { doc ->
                doc.withSegments(
                    doc.segments.map { segment ->
                        when (segment.kind) {
                            BlockKind.HEADING -> segment.copy(target = "你好")
                            else -> segment.copy(target = "正文")
                        }
                    },
                )
            }
        val markdown = Exporters.bilingualMarkdown(document).content
        assertTrue(markdown.contains("# Hello"))
        assertTrue(markdown.contains("你好"))
        assertTrue(markdown.contains("正文"))
        val csv = Exporters.csv(document).content
        assertTrue(csv.startsWith("id,kind,source,target,origin"))
        val json = Exporters.json(document).content
        assertTrue(json.contains("\"targetLang\": \"zh-CN\""))
    }
}

class TermTest {

    @Test
    fun `extractor finds repeated proper nouns and skips sentence noise`() {
        val text = """
            LingoFlow keeps a glossary. The LingoFlow corpus grows with every edit.
            Kotlin Multiplatform powers LingoFlow on Android and Windows.
            Install the CLI and run LingoFlow sync.
        """.trimIndent()
        val document = TextImporter.import(text, "d", "t", "en", "zh-CN")
        val candidates = TermExtractor.extract(document, minCount = 2)
        assertTrue(candidates.any { it.text.contains("LingoFlow") }, candidates.joinToString { it.text })
        assertTrue(candidates.none { it.text == "The" })
        assertTrue(candidates.none { it.text == "Install" })
    }

    @Test
    fun `matcher honours case sensitivity and highlight ranges`() {
        val terms = listOf(
            Term(id = "1", source = "LingoFlow", target = "LingoFlow"),
            Term(id = "2", source = "sync", target = "同步", caseSensitive = true),
        )
        assertTrue(TermMatcher.matches("Run LingoFlow sync now", terms).isNotEmpty())
        val segment = Segment(id = 1, kind = BlockKind.PARAGRAPH, source = "Run LingoFlow sync now")
        val ranges = SegmentOps.highlightTerms(segment, terms)
        assertEquals(2, ranges.size)
        assertEquals("LingoFlow", segment.source.substring(ranges[0].first, ranges[0].second))
    }

    @Test
    fun `corpus matcher reuses the closest confirmed pair`() {
        val entries = listOf(
            CorpusEntry(
                id = "c1",
                source = "Save changes before leaving",
                target = "离开前保存更改",
                sourceLang = "en",
                targetLang = "zh-CN",
                projectId = "p",
                confirmed = true,
            ),
        )
        val hit = CorpusMatcher.best("Save changes before leaving the page", entries, threshold = 0.7)
        assertTrue(hit != null)
        assertTrue(CorpusMatcher.memoryBlock("Save changes now", entries).contains("离开前保存更改"))
    }
}

class TranslatorTest {

    private fun document(): Document {
        val document = TextImporter.import(
            "Alpha beta.\n\nGamma delta.\n\nEpsilon zeta.",
            "doc",
            "test",
            "en",
            "zh-CN",
        )
        return document.withSegments(document.segments.mapIndexed { index, segment -> segment.copy(id = index) })
    }

    @Test
    fun `translate asks for context and stores results`() = runBlocking {
        val engine = FakeChat { user ->
            user.lineSequence().filter { it.startsWith("ID:") }.joinToString("\n") { line ->
                val id = line.removePrefix("ID:").trim()
                "$id: 译文-$id"
            }
        }
        val translator = DocumentTranslator(
            chat = engine,
            terms = listOf(Term(id = "t", source = "Alpha", target = "阿尔法")),
            corpus = emptyList(),
            settings = dev.lingoflow.shared.domain.AppSettings(contextWindow = 2, maxSegmentsPerRequest = 2),
            options = TranslateOptions(contextWindow = 2, batchSize = 2),
        )
        val (result, report) = translator.translate(document())
        assertEquals(3, report.translated)
        assertEquals(0, report.fromCorpus)
        assertTrue(result.translatedCount == 3)
        // The glossary travelled into the prompt.
        assertTrue(engine.lastSystem.contains("阿尔法"))
    }

    @Test
    fun `context memory is sent for later batches`() = runBlocking {
        val engine = FakeChat { user ->
            user.lineSequence().filter { it.startsWith("ID:") }.joinToString("\n") { line ->
                val id = line.removePrefix("ID:").trim()
                "$id: 译文-$id"
            }
        }
        val translator = DocumentTranslator(
            chat = engine,
            terms = emptyList(),
            corpus = emptyList(),
            settings = dev.lingoflow.shared.domain.AppSettings(),
            options = TranslateOptions(contextWindow = 4, batchSize = 1),
        )
        translator.translate(document())
        // After the first batch the prompt must contain the CONTEXT block.
        assertTrue(engine.lastSystem.contains("CONTEXT"), engine.lastSystem)
    }

    @Test
    fun `corpus hits win over the engine`() = runBlocking {
        var engineCalls = 0
        val engine = FakeChat { user ->
            engineCalls++
            user.lineSequence().filter { it.startsWith("ID:") }.joinToString("\n") { line ->
                val id = line.removePrefix("ID:").trim()
                "$id: 模型译文"
            }
        }
        val corpus = listOf(
            CorpusEntry(
                id = "c",
                source = "Alpha beta.",
                target = "阿尔法贝塔",
                sourceLang = "en",
                targetLang = "zh-CN",
                projectId = "p",
                confirmed = true,
            ),
        )
        val translator = DocumentTranslator(
            chat = engine,
            terms = emptyList(),
            corpus = corpus,
            settings = dev.lingoflow.shared.domain.AppSettings(),
            options = TranslateOptions(reuseCorpus = true, corpusThreshold = 0.9, batchSize = 3),
        )
        val (result, report) = translator.translate(document())
        assertEquals(1, report.fromCorpus)
        assertEquals("阿尔法贝塔", result.segments.first().target)
        assertTrue(engineCalls == 1)
    }

    @Test
    fun `locked segments are skipped`() = runBlocking {
        val engine = FakeChat { "0: 模型" }
        val source = document()
        val locked = source.withSegments(
            source.segments.mapIndexed { index, segment -> if (index == 1) segment.copy(target = "人工译文", locked = true) else segment },
        )
        val translator = DocumentTranslator(
            chat = engine,
            terms = emptyList(),
            corpus = emptyList(),
            settings = dev.lingoflow.shared.domain.AppSettings(),
            options = TranslateOptions(skipLocked = true, batchSize = 4),
        )
        val (result, _) = translator.translate(locked)
        assertEquals("人工译文", result.segments[1].target)
        assertTrue(result.segments[0].target.isNotBlank())
    }
}

class PlatformUtilityTest {

    @Test
    fun `sha256 matches the known digest`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc"),
        )
        assertEquals(64, Sha256.hex("LingoFlow").length)
    }

    @Test
    fun `simple date formats epoch millis in utc`() {
        // 2026-09-29T00:00:00Z
        assertEquals("2026-09-29 00:00", SimpleDate.format(1_790_640_000_000))
    }

    @Test
    fun `document progress is computed from translatable segments`() {
        val document = Document.empty("d", "t", "en", "zh-CN", DocFormat.TEXT)
            .withSegments(
                listOf(
                    Segment(0, BlockKind.PARAGRAPH, "one", target = "一"),
                    Segment(1, BlockKind.PARAGRAPH, "two"),
                    Segment(2, BlockKind.CODE, "val x = 1", target = "val x = 1"),
                ),
            )
        assertEquals(2, document.translatableSegments.size)
        assertEquals(0.5, document.progress)
    }
}
