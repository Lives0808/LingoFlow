package dev.lingoflow.app

import dev.lingoflow.app.core.catalog.ArbCatalogFormat
import dev.lingoflow.app.core.catalog.CsvCatalogFormat
import dev.lingoflow.app.core.catalog.DecodedCatalog
import dev.lingoflow.app.core.catalog.FormatContext
import dev.lingoflow.app.core.catalog.FormatContext as Ctx
import dev.lingoflow.app.core.catalog.JsonCatalogFormat
import dev.lingoflow.app.core.catalog.PoCatalogFormat
import dev.lingoflow.app.core.catalog.PropertiesCatalogFormat
import dev.lingoflow.app.core.catalog.StringsCatalogFormat
import dev.lingoflow.app.core.catalog.TsJsCatalogFormat
import dev.lingoflow.app.core.catalog.YamlCatalogFormat
import dev.lingoflow.app.core.model.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {

    private val ctx = FormatContext(locale = "zh-CN", path = "locales/zh-CN.json")

    @Test
    fun `json keeps comments on parent keys`() {
        val text = """
            {
              // header comment
              "app": {
                "title": "LingoFlow"
              }
            }
        """.trimIndent()
        val decoded = JsonCatalogFormat.decode(text, ctx)
        assertEquals(1, decoded.entries.size)
        assertEquals("app.title", decoded.entries[0].key)
        assertEquals("header comment", decoded.entries[0].comment)
    }

    @Test
    fun `json round trip preserves values`() {
        val decoded = JsonCatalogFormat.decode("""{"a":{"b":"one"},"c":"two"}""", ctx)
        val encoded = JsonCatalogFormat.encode(decoded, ctx)
        val reparsed = JsonCatalogFormat.decode(encoded, ctx)
        assertEquals(decoded.entries.map { it.key to it.value }, reparsed.entries.map { it.key to it.value })
    }

    @Test
    fun `properties handles escapes and comments`() {
        val decoded = PropertiesCatalogFormat.decode("# greeting\napp.title=LingoFlow\napp.hint=Line1\\nLine2\n", ctx)
        assertEquals("greeting", decoded.entries[0].comment)
        assertEquals("Line1\nLine2", decoded.entries[1].value)
    }

    @Test
    fun `po keeps header comments and plural forms`() {
        val text = """
            msgid ""
            msgstr ""
            "Language: zh-CN\n"

            #. Item counter
            #: src/App.tsx:12
            msgid "item"
            msgid_plural "items"
            msgstr[0] "# 项"
        """.trimIndent()
        val decoded = PoCatalogFormat.decode(text, ctx)
        assertEquals(1, decoded.entries.size)
        assertEquals("item[other]", decoded.entries[0].key)
        assertEquals("Item counter", decoded.entries[0].comment)
        val encoded = PoCatalogFormat.encode(decoded, ctx)
        assertTrue(encoded.contains("Language: zh-CN"))
        assertTrue(encoded.contains("#. Item counter"))
        assertTrue(encoded.contains("# 项"))
    }

    @Test
    fun `arb preserves metadata`() {
        val text = """{"@@locale":"zh-CN","appTitle":"LingoFlow","@appTitle":{"description":"Header"}}"""
        val decoded = ArbCatalogFormat.decode(text, ctx)
        assertEquals("Header", decoded.entries[0].comment)
        val entries = decoded.entries.map { it.copy(value = "LingoFlow 演示") }
        val encoded = ArbCatalogFormat.encode(DecodedCatalog(entries, decoded.meta), ctx)
        assertTrue(encoded.contains("\"@@locale\""))
        assertTrue(encoded.contains("LingoFlow 演示"))
    }

    @Test
    fun `apple strings round trips quotes and comments`() {
        val text = "/* Greeting */\n\"app.title\" = \"He said \\\"hi\\\"\";\n"
        val decoded = StringsCatalogFormat.decode(text, ctx)
        assertEquals("Greeting", decoded.entries[0].comment)
        assertEquals("He said \"hi\"", decoded.entries[0].value)
        val encoded = StringsCatalogFormat.encode(decoded, ctx)
        assertEquals("He said \"hi\"", StringsCatalogFormat.decode(encoded, ctx).entries[0].value)
    }

    @Test
    fun `csv updates only its own locale column`() {
        val text = "key,en,zh-CN\napp.title,LingoFlow,LingoFlow 演示\n"
        val decoded = CsvCatalogFormat.decode(text, FormatContext(locale = "zh-CN", path = "messages.csv"))
        val entries = decoded.entries.map { if (it.key == "app.title") it.copy(value = "演示") else it }
        val encoded = CsvCatalogFormat.encode(DecodedCatalog(entries, decoded.meta), ctx)
        assertTrue(encoded.contains("app.title,LingoFlow,演示"))
    }

    @Test
    fun `ts module keeps the surrounding code`() {
        val text = """
            import type { Messages } from './types';

            export const messages: Messages = {
              'app.title': 'LingoFlow',
            } as const;
        """.trimIndent()
        val decoded = TsJsCatalogFormat.decode(text, ctx)
        assertEquals("app.title", decoded.entries[0].key)
        val encoded = TsJsCatalogFormat.encode(decoded, ctx)
        assertTrue(encoded.startsWith("import type { Messages }"))
        assertTrue(encoded.contains("as const;"))
    }

    @Test
    fun `yaml subset parses nested keys and comments`() {
        val text = """
            # product strings
            app:
              title: LingoFlow
              subtitle: "Ship fast"
        """.trimIndent()
        val decoded = YamlCatalogFormat.decode(text, Ctx(locale = "en", path = "locales/en.yaml"))
        assertEquals(listOf("app.title", "app.subtitle"), decoded.entries.map { it.key })
        assertEquals("Ship fast", decoded.entries[1].value)
    }

    @Test
    fun `entry value null is preserved`() {
        val decoded = JsonCatalogFormat.decode("""{"missing":null}""", ctx)
        assertEquals(null, decoded.entries[0].value)
        assertEquals(listOf(CatalogEntry("missing", null)), decoded.entries)
    }
}
