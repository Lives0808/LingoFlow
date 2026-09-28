package dev.lingoflow.app.core.model

/** Severity of a validation issue, matching the CLI's issue severities. */
enum class Severity { ERROR, WARN, INFO }

/** One validation finding for a single key in a single locale. */
data class Issue(
    val code: String,
    val severity: Severity,
    val locale: String,
    val key: String,
    val message: String,
    val detail: String? = null,
    val target: String? = null,
    val fixable: Boolean = false,
    val suggestion: String? = null,
)

/** A key/value pair inside one locale file. `value == null` means "key missing". */
data class CatalogEntry(
    val key: String,
    val value: String?,
    val comment: String? = null,
    val maxLength: Int? = null,
)

enum class Format { JSON, YAML, PROPERTIES, PO, ARB, STRINGS, CSV, TSJS }

data class CatalogFile(
    val locale: String,
    val path: String,
    val format: Format,
    /** Mutable so the editor can patch values in place before saving. */
    val entries: MutableList<CatalogEntry>,
    val raw: String,
) {
    private val index: Map<String, CatalogEntry> by lazy { entries.associateBy { it.key } }
    fun entry(key: String): CatalogEntry? = index[key]
    fun value(key: String): String? = entry(key)?.value
    fun keys(): Set<String> = index.keys
}

data class CatalogSpec(
    val path: String,
    val format: Format? = null,
    val locale: String? = null,
    val nesting: Boolean = true,
    val keySeparator: String = ".",
)

enum class RuleKind(val wire: String) {
    GLOSSARY("glossary"),
    DNT("dnt"),
    CORRECTION("correction"),
    STYLE("style");

    companion object {
        fun fromWire(value: String): RuleKind? = entries.firstOrNull { it.wire == value.lowercase() }
    }
}

data class RuleRow(
    val id: Long = 0,
    val kind: RuleKind,
    val locale: String?,
    val pattern: String,
    val value: String,
    val priority: Int = 60,
    val enabled: Boolean = true,
    val origin: String = "manual",
    val confidence: Double = 1.0,
    val note: String? = null,
)

data class MemoryRow(
    val id: Long = 0,
    val sourceLocale: String,
    val targetLocale: String,
    val source: String,
    val target: String,
    val engine: String,
    val confidence: Double = 1.0,
    val refs: List<String> = emptyList(),
    val approved: Boolean = false,
    val frozen: Boolean = false,
    val updatedAt: Long = 0,
)

data class SuggestionRow(
    val sourceHash: String,
    val source: String,
    val locale: String,
    val machine: String,
    val engine: String,
    val createdAt: Long = 0,
)

/** Resolved length budget for one key. */
data class LengthBudget(
    val min: Int = 0,
    val max: Int? = null,
    val hard: Boolean = false,
    val unit: Unit3 = Unit3.CHARS,
    val widget: String = "label",
    val containerPx: Float = 260f,
    val origin: String = "default",
)

enum class Unit3 { PX, CHARS }

/** Widget presets used by the simulated UI. */
data class WidgetPreset(val name: String, val px: Float, val chars: Int, val label: String)

object Widgets {
    val presets: Map<String, WidgetPreset> = listOf(
        WidgetPreset("button", 96f, 18, "Button"),
        WidgetPreset("label", 260f, 60, "Form label"),
        WidgetPreset("input", 180f, 40, "Input placeholder"),
        WidgetPreset("heading", 220f, 32, "Heading"),
        WidgetPreset("title", 220f, 32, "Title"),
        WidgetPreset("nav", 128f, 24, "Navigation item"),
        WidgetPreset("menu", 160f, 28, "Menu item"),
        WidgetPreset("list", 280f, 48, "List row"),
        WidgetPreset("tooltip", 640f, 120, "Tooltip"),
        WidgetPreset("toast", 420f, 80, "Toast"),
        WidgetPreset("description", 640f, 120, "Description"),
        WidgetPreset("text", 640f, 160, "Body text"),
        WidgetPreset("badge", 64f, 12, "Badge"),
        WidgetPreset("tab", 110f, 20, "Tab"),
    ).associateBy { it.name }

    fun of(name: String?): WidgetPreset = presets[name] ?: presets.getValue("label")
}

/** Measures rendered text width; the Android layer supplies a Paint-based one. */
fun interface TextMeasurer {
    fun widthPx(text: String, sizeSp: Float, weight: Int): Float
}

/** Deterministic fallback usable from unit tests and headless code. */
object ApproximateTextMeasurer : TextMeasurer {
    private val widths: Map<Char, Float> = buildMap {
        fun put(chars: String, width: Float) = chars.forEach { put(it, width) }
        put(" ", 0.278f); put("!", 0.278f); put("\"", 0.355f); put("#", 0.556f); put("$", 0.556f)
        put("%", 0.889f); put("&", 0.667f); put("'", 0.191f); put("(", 0.333f); put(")", 0.333f)
        put("*", 0.389f); put("+", 0.584f); put(",", 0.278f); put("-", 0.333f); put(".", 0.278f)
        put("/", 0.278f); put("0123456789", 0.556f); put(":", 0.278f); put(";", 0.278f); put("<", 0.584f)
        put("=", 0.584f); put(">", 0.584f); put("?", 0.556f); put("@", 1.015f)
        put("A", 0.667f); put("B", 0.667f); put("C", 0.722f); put("D", 0.722f); put("E", 0.667f)
        put("F", 0.611f); put("G", 0.778f); put("H", 0.722f); put("I", 0.278f); put("J", 0.5f)
        put("K", 0.667f); put("L", 0.556f); put("M", 0.833f); put("N", 0.722f); put("O", 0.778f)
        put("P", 0.667f); put("Q", 0.778f); put("R", 0.722f); put("S", 0.667f); put("T", 0.611f)
        put("U", 0.722f); put("V", 0.667f); put("W", 0.944f); put("X", 0.667f); put("Y", 0.667f); put("Z", 0.611f)
        put("[", 0.278f); put("\\", 0.278f); put("]", 0.278f); put("^", 0.469f); put("_", 0.556f); put("`", 0.333f)
        put("a", 0.556f); put("b", 0.556f); put("c", 0.5f); put("d", 0.556f); put("e", 0.556f); put("f", 0.278f)
        put("g", 0.556f); put("h", 0.556f); put("i", 0.222f); put("j", 0.222f); put("k", 0.5f); put("l", 0.222f)
        put("m", 0.833f); put("n", 0.556f); put("o", 0.556f); put("p", 0.556f); put("q", 0.556f); put("r", 0.333f)
        put("s", 0.5f); put("t", 0.278f); put("u", 0.556f); put("v", 0.5f); put("w", 0.722f); put("x", 0.5f)
        put("y", 0.5f); put("z", 0.5f); put("{", 0.334f); put("|", 0.26f); put("}", 0.334f); put("~", 0.584f)
    }

    fun charWidthEm(char: Char): Float {
        widths[char]?.let { return it }
        val code = char.code
        return when {
            code in 0x1100..0x115F || code in 0x2E80..0xA4CF || code in 0xAC00..0xD7A3 ||
                code in 0xF900..0xFAFF || code in 0xFE10..0xFE6F || code in 0xFF00..0xFF60 ||
                code in 0xFFE0..0xFFE6 || code in 0x20000..0x3FFFD -> 1f
            code in 0x1F300..0x1F9FF -> 1.15f
            code in 0x0E00..0x0E7F -> 0.5f
            code in 0x0900..0x097F -> 0.55f
            code in 0x0590..0x06FF -> 0.55f
            code in 0x0400..0x04FF -> 0.58f
            else -> 0.56f
        }
    }

    override fun widthPx(text: String, sizeSp: Float, weight: Int): Float {
        val weightFactor = when {
            weight >= 700 -> 1.07f
            weight >= 600 -> 1.04f
            else -> 1f
        }
        var em = 0f
        for (char in text) em += charWidthEm(char)
        return em * sizeSp * weightFactor
    }
}
