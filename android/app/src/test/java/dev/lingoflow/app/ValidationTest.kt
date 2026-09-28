package dev.lingoflow.app

import dev.lingoflow.app.core.config.ProjectConfig
import dev.lingoflow.app.core.model.ApproximateTextMeasurer
import dev.lingoflow.app.core.model.CatalogEntry
import dev.lingoflow.app.core.model.Severity
import dev.lingoflow.app.core.validate.Icu
import dev.lingoflow.app.core.validate.LengthCheck
import dev.lingoflow.app.core.validate.Placeholders
import dev.lingoflow.app.core.validate.Tags
import dev.lingoflow.app.core.validate.Validator
import dev.lingoflow.app.core.rules.RuleSet
import dev.lingoflow.app.core.rules.RuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {

    private val config = ProjectConfig.parse(
        """
        {
          "sourceLocale": "en",
          "locales": ["en", "zh-CN", "de"],
          "length": { "enabled": true, "unit": "chars", "default": { "max": 40, "widget": "label" } }
        }
        """.trimIndent(),
    )

    private val validator = Validator(config, RuleSet(), ApproximateTextMeasurer)

    @Test
    fun `placeholder extraction covers every syntax`() {
        val text = "Hi {name}, {{count}} items, %d of %1\$s at \${site}"
        val keys = Placeholders.keys(text)
        assertTrue(keys.contains("{name}"))
        assertTrue(keys.contains("{{count}}"))
        assertTrue(keys.contains("%d"))
        assertTrue(keys.contains("%s"))
        assertTrue(keys.contains("{site}".let { "\${site}" }))
    }

    @Test
    fun `icu argument inside a plural block is detected`() {
        val keys = Placeholders.keys("{count, plural, one {# item} other {# items}}")
        assertEquals(listOf("{count}"), keys)
    }

    @Test
    fun `placeholder comparison reports missing and extra`() {
        val comparison = Placeholders.compare("Save {name} to {folder}", "保存 {name} 到 {dir}")
        assertEquals(listOf("{folder}"), comparison.missing)
        assertEquals(listOf("{dir}"), comparison.extra)
    }

    @Test
    fun `icu parser validates plural categories per locale`() {
        assertTrue(Icu.parse("{count, plural, one {# item} other {# items}}", "en").ok)
        assertTrue(Icu.parse("{count, plural, other {# 项}}", "zh-CN").ok)
        val invalid = Icu.parse("{count, plural, one {# 项} other {# 项}}", "zh-CN")
        assertTrue(!invalid.ok)
        assertTrue(!Icu.parse("{count, plural, one {# item}", "en").ok)
    }

    @Test
    fun `icu parser handles nested select`() {
        val nested = "{gender, select, male {He has {count, plural, one {# item} other {# items}}} other {They have items}}"
        assertTrue(Icu.parse(nested, "en").ok)
    }

    @Test
    fun `tag comparison catches lost markup`() {
        val comparison = Tags.compare("Read the <a href=\"/docs\">docs</a>", "阅读文档")
        assertEquals(listOf("a"), comparison.missing)
        assertTrue(Tags.compare("<b>bold</b>", "<b>粗体").unbalanced)
        assertTrue(Tags.compare("<a href=\"/x\">x</a>", "<a>链接</a>").attributeIssues.isNotEmpty())
    }

    @Test
    fun `validator reports missing placeholder and empty target`() {
        val sources = mapOf(
            "dialog.delete" to CatalogEntry("dialog.delete", "Delete {name}?"),
            "cta.save" to CatalogEntry("cta.save", "Save"),
        )
        val validation = validator.validateLocale(
            locale = "zh-CN",
            sources = sources,
            targetValues = mapOf("dialog.delete" to "删除吗？", "cta.save" to ""),
        )
        assertTrue(validation.issues.any { it.code == "placeholder.missing" })
        assertTrue(validation.issues.any { it.code == "empty" })
        assertEquals(1, validation.empty)
    }

    @Test
    fun `validator flags identical source and length overruns`() {
        val sources = mapOf(
            "app.title" to CatalogEntry("app.title", "LingoFlow"),
            "cta.save" to CatalogEntry("cta.save", "Save changes"),
        )
        val validation = validator.validateLocale(
            locale = "de",
            sources = sources,
            targetValues = mapOf(
                "app.title" to "LingoFlow",
                "cta.save" to "Änderungen jetzt dauerhaft speichern und schließen",
            ),
        )
        assertTrue(validation.issues.any { it.code == "untranslated" })
        assertTrue(validation.issues.any { it.code == "length.tooLong" })
    }

    @Test
    fun `cjk typography issues are detected`() {
        val sources = mapOf("nav.settings" to CatalogEntry("nav.settings", "Settings"))
        val validation = validator.validateLocale(
            locale = "zh-CN",
            sources = sources,
            targetValues = mapOf("nav.settings" to "设置 ,然后 保存"),
        )
        assertTrue(validation.issues.any { it.code == "cjk.punctuation" || it.code == "cjk.space" })
    }

    @Test
    fun `length check uses widget budgets and expansion limits`() {
        val entry = CatalogEntry("cta.save", "Save changes", maxLength = 12)
        val budget = LengthCheck.resolveBudget(config.length, "cta.save", entry)
        assertEquals(12, budget.max)
        assertEquals("comment", budget.origin)
    }

    @Test
    fun `style fixes remove periods but keep question marks`() {
        val rules = RuleSet.fromRows(
            listOf(
                dev.lingoflow.app.core.model.RuleRow(
                    kind = dev.lingoflow.app.core.model.RuleKind.STYLE,
                    locale = "zh-CN",
                    pattern = RuleSet.STYLE_TRAILING,
                    value = "never",
                ),
            ),
        )
        val (cleaned, fixes) = RuleEngine.applyStyle("保存更改。", "zh-CN", rules)
        assertEquals("保存更改", cleaned)
        assertTrue(fixes.isNotEmpty())
        val (kept, _) = RuleEngine.applyStyle("删除 {name}？", "zh-CN", rules)
        assertEquals("删除 {name}？", kept)
    }

    @Test
    fun `severity ranking keeps errors first`() {
        val sources = mapOf("a" to CatalogEntry("a", "Save {x}"))
        val validation = validator.validateLocale("zh-CN", sources, mapOf("a" to null))
        assertTrue(validation.issues.isNotEmpty())
        assertEquals(Severity.ERROR, validation.issues.first().severity)
    }
}
