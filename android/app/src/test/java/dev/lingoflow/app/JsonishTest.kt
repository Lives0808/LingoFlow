package dev.lingoflow.app

import dev.lingoflow.app.core.catalog.JsonValue
import dev.lingoflow.app.core.catalog.JsonWriter
import dev.lingoflow.app.core.catalog.JsonishParser
import dev.lingoflow.app.core.catalog.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonishTest {

    @Test
    fun `parses tolerant json with comments trailing commas and unquoted keys`() {
        val text = """
            {
              // translator comment
              app: {
                title: 'LingoFlow',
                count: 3,
              },
              /* block comment */
              enabled: true,
              missing: null,
            }
        """.trimIndent()
        val result = JsonishParser(text).parse()
        assertTrue("input needed tolerant parsing", result.tolerant)
        val root = result.value as JsonValue.Obj
        val app = root.get("app") as JsonValue.Obj
        assertEquals("LingoFlow", (app.get("title") as JsonValue.Str).value)
        assertEquals(3.0, (app.get("count") as JsonValue.Num).value, 0.0001)
        assertTrue((root.get("enabled") as JsonValue.Bool).value)
        assertEquals("translator comment", result.comments["app"])
    }

    @Test
    fun `flattens nested objects and arrays into dotted keys`() {
        val text = """{"app":{"title":"LingoFlow","items":["one","two"]}}"""
        val value = JsonishParser(text).parse().value
        val flat = Keys.flatten(value.toPlain()).map { it.first }
        assertEquals(listOf("app.title", "app.items.0", "app.items.1"), flat)
    }

    @Test
    fun `unflattens back into the original shape`() {
        val tree = Keys.unflatten(listOf("app.title" to "LingoFlow", "cta.save" to "Save"))
        assertEquals("LingoFlow", (tree["app"] as Map<*, *>)["title"])
        assertEquals("Save", (tree["cta"] as Map<*, *>)["save"])
    }

    @Test
    fun `writer escapes strings and keeps key order`() {
        val json = JsonWriter.write(linkedMapOf("a" to "line\nbreak", "b" to 2))
        assertTrue(json.contains("\"a\": \"line\\nbreak\""))
        assertTrue(json.indexOf("\"a\"") < json.indexOf("\"b\""))
    }

    @Test
    fun `glob matcher handles single and double wildcards`() {
        assertTrue(Keys.matches("cta.*", "cta.save"))
        assertTrue(!Keys.matches("cta.*", "cta.save.label"))
        assertTrue(Keys.matches("*.tooltip", "nav.tooltip"))
        assertTrue(Keys.matches("*", "anything"))
        assertTrue(Keys.matches("app.**", "app.a.b"))
    }
}
