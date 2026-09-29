package dev.lingoflow.shared.core.config

import dev.lingoflow.shared.core.catalog.JsonValue
import dev.lingoflow.shared.core.catalog.JsonishParser
import dev.lingoflow.shared.core.catalog.Keys
import dev.lingoflow.shared.core.catalog.Formats
import dev.lingoflow.shared.core.model.CatalogSpec
import dev.lingoflow.shared.core.model.Format

/** A resolved length rule from the config. */
data class LengthRule(
    val match: String,
    val max: Int? = null,
    val min: Int? = null,
    val hard: Boolean? = null,
    val widget: String? = null,
)

data class LengthDefaults(
    val max: Int = 60,
    val min: Int = 0,
    val hard: Boolean = false,
    val widget: String = "label",
)

data class LengthConfig(
    val enabled: Boolean = true,
    val unit: String = "both",
    val fontSize: Float = 14f,
    val fontWeight: Int = 400,
    val defaults: LengthDefaults = LengthDefaults(),
    val rules: List<LengthRule> = emptyList(),
    val expansion: Map<String, Double> = emptyMap(),
    val autofix: Boolean = true,
)

data class TranslationConfig(
    val engine: String = "offline",
    val policy: String = "missing",
    val fuzzyThreshold: Double = 0.72,
    val fuzzyAutofill: Boolean = true,
    val freezeApproved: Boolean = true,
    val learnFromEdits: Boolean = true,
    val glossaryMode: String = "auto",
    val glossaryEnforce: Boolean = true,
    val concurrency: Int = 4,
)

data class ValidateConfig(
    val placeholders: Boolean = true,
    val icu: Boolean = true,
    val tags: Boolean = true,
    val whitespace: Boolean = true,
    val punctuation: Boolean = true,
    val ellipsis: Boolean = true,
    val cjk: Boolean = true,
    val script: Boolean = true,
    val dnt: Boolean = true,
    val glossary: Boolean = true,
    val consistency: Boolean = true,
    val forbidden: List<String> = emptyList(),
    val ignore: Map<String, List<String>> = emptyMap(),
)

data class ReportConfig(
    val dir: String = ".lingoflow/report",
    val title: String = "LingoFlow Report",
)

/** Everything the app needs from `lingoflow.config.json`. */
data class ProjectConfig(
    val sourceLocale: String = "en",
    val locales: List<String> = listOf("en"),
    val catalogs: List<CatalogSpec> = listOf(CatalogSpec("locales/{locale}.json")),
    val translation: TranslationConfig = TranslationConfig(),
    val length: LengthConfig = LengthConfig(),
    val validate: ValidateConfig = ValidateConfig(),
    val report: ReportConfig = ReportConfig(),
    val raw: Map<String, Any?> = emptyMap(),
) {
    fun catalogPathsFor(locale: String): List<Pair<String, Format>> = catalogs
        .filter { it.locale == null || it.locale == locale }
        .mapNotNull { spec ->
            val path = spec.path.replace("{locale}", locale)
            val format = spec.format ?: Formats.detect(path) ?: return@mapNotNull null
            path to format
        }

    fun catalogSpec(path: String): CatalogSpec? = catalogs.firstOrNull {
        it.path.replace("{locale}", "") == path.replace("{locale}", "")
    }

    companion object {
        val DEFAULT = ProjectConfig()

        /** Parses a config file; unknown fields are kept in [raw] and ignored. */
        fun parse(text: String): ProjectConfig {
            val parsed = try {
                JsonishParser(text).parse().value
            } catch (error: Exception) {
                return DEFAULT
            }
            val root = (parsed as? JsonValue.Obj)?.toPlain() as? Map<*, *> ?: return DEFAULT
            @Suppress("UNCHECKED_CAST")
            val map = root as Map<String, Any?>

            val sourceLocale = Keys.asString(map["sourceLocale"]) ?: "en"
            val locales = (map["locales"] as? List<*>)?.mapNotNull { Keys.asString(it) }.orEmpty()
                .ifEmpty { listOf(sourceLocale) }
                .let { if (sourceLocale in it) it else listOf(sourceLocale) + it }

            val catalogSpecs = (map["catalogs"] as? List<*>).orEmpty().mapNotNull { item ->
                val entry = item as? Map<*, *> ?: return@mapNotNull null
                val path = Keys.asString(entry["path"]) ?: return@mapNotNull null
                val formatName = Keys.asString(entry["format"])
                CatalogSpec(
                    path = path,
                    format = if (formatName.isNullOrBlank() || formatName == "auto") null else {
                        Format.entries.firstOrNull { it.name.equals(formatName, ignoreCase = true) }
                    },
                    locale = Keys.asString(entry["locale"]),
                    nesting = entry["nesting"] as? Boolean ?: true,
                    keySeparator = Keys.asString(entry["keySeparator"]) ?: ".",
                )
            }.ifEmpty { listOf(CatalogSpec("locales/{locale}.json")) }

            val translationMap = map["translation"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val memoryMap = translationMap["memory"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val glossaryMap = translationMap["glossary"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val translation = TranslationConfig(
                engine = Keys.asString(translationMap["engine"]) ?: "offline",
                policy = Keys.asString(translationMap["policy"]) ?: "missing",
                fuzzyThreshold = (memoryMap["fuzzyThreshold"] as? Number)?.toDouble() ?: 0.72,
                fuzzyAutofill = memoryMap["fuzzyAutofill"] as? Boolean ?: true,
                freezeApproved = memoryMap["freezeApproved"] as? Boolean ?: true,
                learnFromEdits = memoryMap["learnFromEdits"] as? Boolean ?: true,
                glossaryMode = Keys.asString(glossaryMap["mode"]) ?: "auto",
                glossaryEnforce = glossaryMap["enforce"] as? Boolean ?: true,
                concurrency = (translationMap["concurrency"] as? Number)?.toInt() ?: 4,
            )

            val lengthMap = map["length"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val fontMap = lengthMap["font"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val defaultMap = lengthMap["default"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val autofixMap = lengthMap["autofix"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val length = LengthConfig(
                enabled = lengthMap["enabled"] as? Boolean ?: true,
                unit = Keys.asString(lengthMap["unit"]) ?: "both",
                fontSize = (fontMap["size"] as? Number)?.toFloat() ?: 14f,
                fontWeight = (fontMap["weight"] as? Number)?.toInt() ?: 400,
                defaults = LengthDefaults(
                    max = (defaultMap["max"] as? Number)?.toInt() ?: 60,
                    min = (defaultMap["min"] as? Number)?.toInt() ?: 0,
                    hard = defaultMap["hard"] as? Boolean ?: false,
                    widget = Keys.asString(defaultMap["widget"]) ?: "label",
                ),
                rules = (lengthMap["rules"] as? List<*>).orEmpty().mapNotNull { item ->
                    val entry = item as? Map<*, *> ?: return@mapNotNull null
                    val match = Keys.asString(entry["match"]) ?: return@mapNotNull null
                    LengthRule(
                        match = match,
                        max = (entry["max"] as? Number)?.toInt(),
                        min = (entry["min"] as? Number)?.toInt(),
                        hard = entry["hard"] as? Boolean,
                        widget = Keys.asString(entry["widget"]),
                    )
                },
                expansion = (lengthMap["expansion"] as? Map<*, *>).orEmpty()
                    .mapNotNull { (key, value) ->
                        val locale = Keys.asString(key) ?: return@mapNotNull null
                        (value as? Number)?.let { locale to it.toDouble() }
                    }
                    .toMap(),
                autofix = autofixMap["enabled"] as? Boolean ?: true,
            )

            val validateMap = map["validate"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val validate = ValidateConfig(
                placeholders = validateMap["placeholders"] as? Boolean ?: true,
                icu = validateMap["icu"] as? Boolean ?: true,
                tags = validateMap["tags"] as? Boolean ?: true,
                whitespace = validateMap["whitespace"] as? Boolean ?: true,
                punctuation = validateMap["punctuation"] as? Boolean ?: true,
                ellipsis = validateMap["ellipsis"] as? Boolean ?: true,
                cjk = validateMap["cjk"] as? Boolean ?: true,
                script = validateMap["script"] as? Boolean ?: true,
                dnt = validateMap["dnt"] as? Boolean ?: true,
                glossary = validateMap["glossary"] as? Boolean ?: true,
                consistency = validateMap["consistency"] as? Boolean ?: true,
                forbidden = (validateMap["forbidden"] as? List<*>).orEmpty().mapNotNull { Keys.asString(it) },
                ignore = (validateMap["ignore"] as? Map<*, *>).orEmpty().mapNotNull { (locale, patterns) ->
                    val key = Keys.asString(locale) ?: return@mapNotNull null
                    key to (patterns as? List<*>).orEmpty().mapNotNull { Keys.asString(it) }
                }.toMap(),
            )

            val reportMap = map["report"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val report = ReportConfig(
                dir = Keys.asString(reportMap["dir"]) ?: ".lingoflow/report",
                title = Keys.asString(reportMap["title"]) ?: "LingoFlow Report",
            )

            return ProjectConfig(
                sourceLocale = sourceLocale,
                locales = locales,
                catalogs = catalogSpecs,
                translation = translation,
                length = length,
                validate = validate,
                report = report,
                raw = map,
            )
        }
    }
}
