package dev.lingoflow.app

import dev.lingoflow.app.core.model.ApproximateTextMeasurer
import dev.lingoflow.app.core.model.Severity
import dev.lingoflow.app.core.project.DemoProjectData
import dev.lingoflow.app.core.project.MapFileSource
import dev.lingoflow.app.core.project.Pipeline
import dev.lingoflow.app.core.project.PlannedJob
import dev.lingoflow.app.core.project.ProjectLoader
import dev.lingoflow.app.core.report.Report
import dev.lingoflow.app.core.rules.RuleSet
import dev.lingoflow.app.core.translate.OfflineEngine
import dev.lingoflow.app.core.tm.InMemoryMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end guard for the bundled demo project: it must load, validate, plan,
 * translate and report — the exact flow a first-time user triggers.
 */
class DemoProjectTest {

    private val source = MapFileSource.of(DemoProjectData.files, DemoProjectData.NAME)

    @Test
    fun `demo project loads with every configured locale`() {
        val session = ProjectLoader.load(source)
        assertEquals("en", session.sourceLocale)
        assertTrue(session.warnings.isEmpty())
        val stats = session.stats()
        assertEquals(5, stats.locales.size)
        assertTrue(stats.keys >= 10)
        // The demo intentionally ships partially translated catalogs.
        val zh = stats.locales.first { it.locale == "zh-CN" }
        assertTrue(zh.translated in 1 until zh.keys)
    }

    @Test
    fun `demo project validates and reports real issues`() {
        val session = ProjectLoader.load(source)
        val memory = InMemoryMemoryStore()
        val pipeline = Pipeline(session, memory, RuleSet.fromRows(memory.listRules()), ApproximateTextMeasurer, OfflineEngine())
        val snapshot = pipeline.analyze()
        val codes = snapshot.issues.map { it.code }.toSet()
        assertTrue("empty value detected", codes.contains("empty"))
        assertTrue("untranslated value detected", codes.contains("untranslated"))
        assertTrue("ICU drift detected", codes.contains("icu.invalid") || codes.contains("placeholder.extra"))
        assertTrue("missing keys detected", codes.contains("missing"))
        assertTrue(snapshot.errors > 0)
    }

    @Test
    fun `demo project translates missing keys and writes them back`() {
        val session = ProjectLoader.load(source)
        val memory = InMemoryMemoryStore()
        val pipeline = Pipeline(session, memory, RuleSet.fromRows(memory.listRules()), ApproximateTextMeasurer, OfflineEngine())
        val plan = pipeline.plan(listOf("zh-CN"))
        assertTrue(plan.any { it.action == PlannedJob.Action.TRANSLATE })

        val outcome = pipeline.run("zh-CN", plan)
        assertTrue(outcome.filesWritten.contains("locales/zh-CN.json"))
        val written = source.read("locales/zh-CN.json").orEmpty()
        assertTrue("dictionary translation present", written.contains("取消"))
        assertTrue("placeholder survived", written.contains("{name}"))
        assertTrue("nothing was lost", written.contains("保存更改"))
    }

    @Test
    fun `demo project produces every report format`() {
        val session = ProjectLoader.load(source)
        val memory = InMemoryMemoryStore()
        val snapshot = Pipeline(session, memory, RuleSet(), ApproximateTextMeasurer, OfflineEngine()).analyze()
        val report = Report.Snapshot(session, snapshot.validations, snapshot.issues, engine = "offline")
        assertTrue(Report.html(report, Report.placeholderSamples(session)).contains("UI"))
        assertTrue(Report.markdown(report).contains("Locales".let { "| Locale |" }))
        assertTrue(Report.json(report).contains("\"platform\": \"android\""))
        assertTrue(snapshot.issues.none { it.severity == Severity.INFO && it.message.isBlank() })
    }

    @Test
    fun `pseudo locale can be previewed for every source string`() {
        val session = ProjectLoader.load(source)
        val localised = session.sourceEntries.values.map { entry ->
            dev.lingoflow.app.core.translate.PseudoEngine.localize(entry.value.orEmpty())
        }
        assertTrue(localised.all { it.isNotBlank() })
        assertTrue(localised.any { it.length > session.sourceEntries.values.first().value.orEmpty().length })
    }
}
