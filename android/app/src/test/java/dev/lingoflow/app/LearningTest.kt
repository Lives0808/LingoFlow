package dev.lingoflow.app

import dev.lingoflow.app.core.model.MemoryRow
import dev.lingoflow.app.core.rules.Learn
import dev.lingoflow.app.core.rules.RuleEngine
import dev.lingoflow.app.core.rules.RuleSet
import dev.lingoflow.app.core.tm.Fuzzy
import dev.lingoflow.app.core.tm.InMemoryMemoryStore
import dev.lingoflow.app.core.validate.Placeholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningTest {

    @Test
    fun `word diff splits equal length edits into reusable changes`() {
        val changes = Learn.wordDiff("登入 到 你的 账户", "登录 到 您的 账户")
        assertTrue(changes.any { it.from == "登入" && it.to == "登录" })
        assertTrue(changes.any { it.from == "你的" && it.to == "您的" })
    }

    @Test
    fun `correction rules are learned after two occurrences`() {
        val edits = listOf(
            Learn.Edit("登入 到 你的 账户", "登录 到 您的 账户", "Sign in to your account", "zh-CN"),
            Learn.Edit("请 登入", "请 登录", "Please sign in", "zh-CN"),
        )
        val rules = Learn.learnCorrectionsFromEdits(edits, minOccurrences = 2)
        val rule = rules.firstOrNull { it.pattern.contains("登入") }
        assertNotNull(rule)
        assertTrue(rule!!.enabled)
        assertTrue(rule.confidence >= 0.9)

        // The rule is applied to a new string automatically.
        val ruleSet = RuleSet.fromRows(rules)
        val applied = RuleEngine.applyCorrections("请先登入后继续", ruleSet.correctionsFor("zh-CN"))
        assertTrue(applied.text.contains("登录"))
    }

    @Test
    fun `style learning derives house rules from approved copy`() {
        val samples = (0..11).map { Triple("项目$it，设置完成", "Project $it settings", "zh-CN") }
        val rules = Learn.learnStyles(samples)
        assertTrue(rules.any { it.pattern == RuleSet.STYLE_CJK_FULLWIDTH && it.value == "true" })
        assertTrue(rules.any { it.pattern == RuleSet.STYLE_TRAILING && it.value == "never" })
    }

    @Test
    fun `glossary mining finds consistent term pairs`() {
        val pairs = listOf(
            "Save changes now" to "立即保存更改",
            "Please save changes" to "请保存更改",
            "Save changes before leaving" to "离开前保存更改",
            "Nothing to see" to "这里什么都没有",
        )
        val rules = Learn.learnGlossary(pairs, "zh-CN", "en", minOccurrences = 3, minConsistency = 0.7)
        val term = rules.firstOrNull { it.pattern == "save changes" }
        assertNotNull(term)
        assertTrue(term!!.value.contains("保存更改"))
        assertTrue(term.confidence > 0.8)
    }

    @Test
    fun `offline engine keeps placeholders and uses the dictionary`() {
        val engine = dev.lingoflow.app.core.translate.OfflineEngine()
        val output = engine.translate(
            listOf(
                dev.lingoflow.app.core.translate.TranslateItem(
                    id = "1",
                    key = "cta.save",
                    text = "Save changes for {name}",
                    from = "en",
                    to = "zh-CN",
                ),
            ),
            dev.lingoflow.app.core.translate.EngineContext(),
        ).first()
        assertTrue(output.text.contains("{name}"))
        assertTrue(output.text.contains("保存更改"))
        // Two of three tokens came from the dictionary ("for" is unknown).
        assertTrue(output.confidence >= 0.3)
    }

    @Test
    fun `memory store protects frozen entries`() {
        val store = InMemoryMemoryStore()
        store.upsertMany(
            listOf(
                MemoryRow(
                    sourceLocale = "en",
                    targetLocale = "zh-CN",
                    source = "Settings",
                    target = "设置",
                    engine = "human",
                    approved = true,
                    frozen = true,
                ),
            ),
        )
        val row = store.exact("en", "zh-CN", "Settings")
        assertNotNull(row)
        store.update(row!!.id, mapOf("frozen" to true))
        store.upsertMany(
            listOf(
                MemoryRow(
                    sourceLocale = "en",
                    targetLocale = "zh-CN",
                    source = "Settings",
                    target = "参数",
                    engine = "offline",
                ),
            ),
        )
        assertEquals("设置", store.exact("en", "zh-CN", "Settings")!!.target)
    }

    @Test
    fun `fuzzy matching finds close sources and ignores distant ones`() {
        val candidates = listOf(
            MemoryRow(
                sourceLocale = "en",
                targetLocale = "zh-CN",
                source = "Save changes",
                target = "保存更改",
                engine = "human",
            ),
        )
        assertNotNull(Fuzzy.best("Save change", candidates, 0.7))
        assertEquals(null, Fuzzy.best("Completely different sentence", candidates, 0.7))
    }

    @Test
    fun `pseudo localisation keeps placeholders and expands text`() {
        val pseudo = dev.lingoflow.app.core.translate.PseudoEngine.localize("Save {name} now")
        assertTrue(pseudo.contains("{name}"))
        assertTrue(pseudo.length > "Save {name} now".length)
    }

    @Test
    fun `protection shields glossary terms and do-not-translate words`() {
        val glossary = listOf(
            dev.lingoflow.app.core.rules.GlossaryTerm(locale = "zh-CN", source = "Settings", target = "设置"),
        )
        val dnt = listOf(dev.lingoflow.app.core.rules.DntTerm(locale = null, pattern = "LingoFlow", replacement = "LingoFlow"))
        val protection = RuleEngine.protect("Open LingoFlow Settings", glossary, dnt)
        assertTrue(!protection.text.contains("Settings"))
        assertTrue(!protection.text.contains("LingoFlow"))
        val restored = RuleEngine.restore(protection.text, protection)
        assertTrue(restored.contains("设置"))
        assertTrue(restored.contains("LingoFlow"))
    }

    @Test
    fun `placeholder masking survives an untranslated passthrough`() {
        val masked = Placeholders.mask("Delete {name}?")
        assertEquals("Delete {name}?", Placeholders.unmask(masked.first, masked.second))
    }
}
