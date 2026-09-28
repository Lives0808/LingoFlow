package dev.lingoflow.app

import dev.lingoflow.app.core.model.ApproximateTextMeasurer
import dev.lingoflow.app.data.LocalFileSource
import dev.lingoflow.app.core.project.Pipeline
import dev.lingoflow.app.core.project.PlannedJob
import dev.lingoflow.app.core.project.ProjectLoader
import dev.lingoflow.app.core.report.Report
import dev.lingoflow.app.core.rules.RuleSet
import dev.lingoflow.app.core.translate.OfflineEngine
import dev.lingoflow.app.core.translate.TranslationEngine
import dev.lingoflow.app.core.tm.InMemoryMemoryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PipelineTest {

    private lateinit var root: File
    private lateinit var source: LocalFileSource
    private val memory = InMemoryMemoryStore()
    private val engine: TranslationEngine = OfflineEngine()

    @Before
    fun setUp() {
        root = File.createTempFile("lingoflow", "project").apply {
            delete()
            mkdirs()
        }
        source = LocalFileSource(root, "Test project")
        write(
            "lingoflow.config.json",
            """
            {
              "sourceLocale": "en",
              "locales": ["en", "zh-CN"],
              "catalogs": [{ "path": "locales/{locale}.json", "format": "json" }],
              "translation": { "engine": "offline", "policy": "missing" },
              "length": { "unit": "chars", "default": { "max": 60, "widget": "label" } }
            }
            """.trimIndent(),
        )
        write(
            "locales/en.json",
            """
            {
              "app": { "title": "LingoFlow" },
              "cta": { "save": "Save changes", "cancel": "Cancel" },
              "form": { "email": "Email address" },
              "dialog": { "deleteTitle": "Delete {name}?" }
            }
            """.trimIndent(),
        )
        write(
            "locales/zh-CN.json",
            """
            {
              "app": { "title": "LingoFlow 演示" },
              "cta": { "save": "保存更改" },
              "form": { "email": "" },
              "dialog": { "deleteTitle": "Delete {name}?" }
            }
            """.trimIndent(),
        )
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(path: String, content: String) = source.write(path, content.trimIndent() + "\n")
    private fun read(path: String) = File(root, path).readText()

    private fun pipeline(session: dev.lingoflow.app.core.project.ProjectSession) =
        Pipeline(session, memory, RuleSet.fromRows(memory.listRules()), ApproximateTextMeasurer, engine)

    @Test
    fun `loads the project and reports coverage`() {
        val session = ProjectLoader.load(source)
        assertEquals("en", session.sourceLocale)
        assertEquals(5, session.sourceEntries.size)
        val stats = session.stats()
        val zh = stats.locales.first { it.locale == "zh-CN" }
        assertEquals(5, zh.keys)
        assertEquals(3, zh.translated)
        assertEquals(2, zh.missing)
    }

    @Test
    fun `analysis reports missing keys and empty values`() {
        val session = ProjectLoader.load(source)
        val snapshot = pipeline(session).analyze()
        val codes = snapshot.issues.map { it.code }.toSet()
        assertTrue(codes.contains("empty"))
        assertTrue(codes.contains("untranslated"))
        assertTrue(snapshot.errors >= 1)
    }

    @Test
    fun `plan classifies jobs and translation writes translations back`() {
        val session = ProjectLoader.load(source)
        val pipeline = pipeline(session)
        val plan = pipeline.plan(listOf("zh-CN"))
        assertTrue(plan.any { it.key == "cta.cancel" && it.action == PlannedJob.Action.TRANSLATE })
        assertTrue(plan.any { it.key == "app.title" && it.action == PlannedJob.Action.SKIP })

        val outcome = pipeline.run("zh-CN", plan)
        assertEquals(2, outcome.translated + outcome.fromMemory)
        assertTrue(outcome.filesWritten.contains("locales/zh-CN.json"))

        val written = read("locales/zh-CN.json")
        assertTrue("dictionary translation applied", written.contains("取消"))
        assertTrue("placeholder survived", written.contains("{name}"))
    }

    @Test
    fun `approved and frozen translations are never overwritten`() {
        val session = ProjectLoader.load(source)
        val pipeline = pipeline(session)
        pipeline.run("zh-CN", pipeline.plan(listOf("zh-CN")))
        var sessionAfter = ProjectLoader.load(source)
        pipeline(sessionAfter).approve("zh-CN", "cta.save", freeze = true)

        val frozen = memory.exact("en", "zh-CN", "Save changes")
        assertNotNull(frozen)
        assertTrue(frozen!!.frozen)

        val second = pipeline(ProjectLoader.load(source))
        val plan = second.plan(listOf("zh-CN"), force = true)
        val job = plan.first { it.key == "cta.save" }
        assertEquals(PlannedJob.Action.FROZEN, job.action)
        second.run("zh-CN", plan)
        assertTrue(read("locales/zh-CN.json").contains("保存更改"))
    }

    @Test
    fun `reviewer edits become reusable correction rules`() {
        val session = ProjectLoader.load(source)
        val pipeline = pipeline(session)
        pipeline.run("zh-CN", pipeline.plan(listOf("zh-CN")))

        // Reviewer edits two machine translations the same way.
        val file = File(root, "locales/zh-CN.json")
        val updated = file.readText()
            .replace("邮箱 address", "电子邮箱 address")
            .replace("取消", "撤销")
        file.writeText(updated)

        val reloaded = ProjectLoader.load(source)
        val outcome = pipeline(reloaded).learn(listOf("zh-CN"))
        assertTrue("rules were learned", outcome.added > 0)
        assertTrue(memory.listRules().isNotEmpty())
    }

    @Test
    fun `reports are generated in every format`() {
        val session = ProjectLoader.load(source)
        val analysis = pipeline(session).analyze()
        val snapshot = Report.Snapshot(session, analysis.validations, analysis.issues, engine = "offline")
        assertTrue(Report.html(snapshot, Report.placeholderSamples(session)).contains("LingoFlow"))
        assertTrue(Report.markdown(snapshot).contains("| Locale |"))
        assertTrue(Report.json(snapshot).contains("\"sourceLocale\": \"en\""))
    }
}
