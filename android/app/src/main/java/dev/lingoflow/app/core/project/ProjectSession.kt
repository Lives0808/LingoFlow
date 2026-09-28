package dev.lingoflow.app.core.project

import dev.lingoflow.app.core.catalog.Keys
import dev.lingoflow.app.core.config.ProjectConfig
import dev.lingoflow.app.core.model.CatalogEntry
import dev.lingoflow.app.core.model.CatalogFile

/** A file system the project lives in: a local directory or a SAF tree. */
interface ProjectSource {
    /** Stable identifier (SAF tree uri or absolute path). */
    val id: String

    /** Human readable name shown in the UI. */
    val name: String

    fun exists(path: String): Boolean
    fun read(path: String): String?
    fun write(path: String, content: String): Boolean
    fun delete(path: String): Boolean

    /** All files under the project, relative paths, used for discovery. */
    fun listFiles(maxDepth: Int = 3): List<String>
}

/** One locale file inside the project. */
data class LocaleFile(
    val locale: String,
    val path: String,
    val file: CatalogFile,
)

/**
 * A loaded project: configuration, the source locale catalog(s) and every locale
 * variant, with helpers for alignment and lookup.
 */
class ProjectSession(
    val source: ProjectSource,
    val config: ProjectConfig,
    /** source catalog path -> locale -> file */
    val bySource: Map<String, Map<String, LocaleFile>>,
    val warnings: List<String> = emptyList(),
) {
    val sourceLocale: String get() = config.sourceLocale

    val locales: List<String> get() = config.locales

    /** Merged source keys (first catalog wins) used for validation. */
    val sourceEntries: Map<String, CatalogEntry> by lazy {
        val merged = LinkedHashMap<String, CatalogEntry>()
        for ((_, variants) in bySource) {
            val sourceFile = variants[config.sourceLocale] ?: continue
            for (entry in sourceFile.file.entries) {
                if (!merged.containsKey(entry.key)) merged[entry.key] = entry
            }
        }
        merged
    }

    fun filesFor(locale: String): List<LocaleFile> = bySource.values.mapNotNull { it[locale] }

    fun sourcePathOf(path: String): String? = bySource.keys.firstOrNull { it == path }

    fun valuesFor(locale: String): Map<String, String?> {
        val values = LinkedHashMap<String, String?>()
        for (file in filesFor(locale)) {
            for (entry in file.file.entries) values[entry.key] = entry.value
        }
        return values
    }

    fun missingKeys(locale: String): List<String> = sourceEntries.keys.filter { key ->
        val value = valuesFor(locale)[key]
        value == null || value.isBlank()
    }

    fun updateEntry(locale: String, key: String, value: String?): ProjectSession {
        val updated = bySource.mapValues { (_, variants) ->
            variants.mapValues { (fileLocale, localeFile) ->
                if (fileLocale != locale) {
                    localeFile
                } else {
                    val entries = localeFile.file.entries.toMutableList()
                    val index = entries.indexOfFirst { it.key == key }
                    if (index >= 0) {
                        entries[index] = entries[index].copy(value = value)
                    } else {
                        entries += CatalogEntry(key, value)
                    }
                    localeFile.copy(file = localeFile.file.copy(entries = entries))
                }
            }
        }
        return ProjectSession(source, config, updated, warnings)
    }

    fun stats(): ProjectStats {
        val localesWithCoverage = locales.map { locale ->
            val values = valuesFor(locale)
            val translated = sourceEntries.keys.count { key -> !values[key].isNullOrBlank() }
            LocaleCoverage(locale, sourceEntries.size, translated, sourceEntries.size - translated)
        }
        return ProjectStats(sourceEntries.size, localesWithCoverage, bySource.keys.size)
    }
}

data class LocaleCoverage(val locale: String, val keys: Int, val translated: Int, val missing: Int) {
    val coverage: Double get() = if (keys == 0) 1.0 else translated.toDouble() / keys
}

data class ProjectStats(val keys: Int, val locales: List<LocaleCoverage>, val catalogCount: Int)

/** Reads configuration and every locale file from a [ProjectSource]. */
object ProjectLoader {

    private val configNames = listOf("lingoflow.config.json", "lingoflow.config.jsonc", "lingoflow.config.yaml", "lingoflow.config.yml")

    fun load(source: ProjectSource): ProjectSession {
        val warnings = mutableListOf<String>()
        val configText = configNames.firstNotNullOfOrNull { name -> source.read(name) }
        val config = configText?.let { ProjectConfig.parse(it) } ?: ProjectConfig.DEFAULT
        if (configText == null) warnings += "No lingoflow.config.json found — using defaults"

        if (configText != null && configNames.firstOrNull { source.exists(it) }?.endsWith(".yml") == true ||
            configNames.firstOrNull { source.exists(it) }?.endsWith(".yaml") == true
        ) {
            warnings += "YAML config is read with a simplified parser; JSON is recommended"
        }

        val bySource = LinkedHashMap<String, Map<String, LocaleFile>>()
        for (spec in config.catalogs) {
            val sourcePath = spec.path.replace("{locale}", config.sourceLocale)
            val variants = LinkedHashMap<String, LocaleFile>()
            for (locale in config.locales) {
                if (spec.locale != null && spec.locale != locale) continue
                val path = spec.path.replace("{locale}", locale)
                val raw = source.read(path) ?: continue
                val format = spec.format ?: dev.lingoflow.app.core.catalog.Formats.detect(path)
                if (format == null) {
                    warnings += "Unsupported format for $path"
                    continue
                }
                val impl = dev.lingoflow.app.core.catalog.Formats.of(format)
                val ctx = dev.lingoflow.app.core.catalog.FormatContext(
                    locale = locale,
                    path = path,
                    keySeparator = spec.keySeparator,
                    nesting = spec.nesting,
                )
                val decoded = try {
                    impl.decode(raw, ctx)
                } catch (error: Exception) {
                    warnings += "Cannot parse $path: ${error.message}"
                    continue
                }
                variants[locale] = LocaleFile(locale, path, CatalogFile(locale, path, format, decoded.entries.toMutableList(), raw))
            }
            if (variants.isNotEmpty()) bySource[sourcePath] = variants
        }

        if (bySource.isEmpty()) warnings += "No catalog files found for the configured paths"
        return ProjectSession(source, config, bySource, warnings)
    }

    /** Writes one locale file back to disk using the format that produced it. */
    fun save(session: ProjectSession, localeFile: LocaleFile): Boolean {
        val spec = session.config.catalogs.firstOrNull { it.path.replace("{locale}", "") == localeFile.path.replace("{locale}", "") }
        val ctx = dev.lingoflow.app.core.catalog.FormatContext(
            locale = localeFile.locale,
            path = localeFile.path,
            keySeparator = spec?.keySeparator ?: ".",
            nesting = spec?.nesting ?: true,
        )
        if (spec?.format != null) {
            // The file came from a configured format; reuse it.
            val impl = dev.lingoflow.app.core.catalog.Formats.of(spec.format)
            val decoded = dev.lingoflow.app.core.catalog.DecodedCatalog(localeFile.file.entries.toList())
            val content = impl.encode(decoded, ctx)
            return session.source.write(localeFile.path, content)
        }
        val impl = dev.lingoflow.app.core.catalog.Formats.of(localeFile.file.format)
        val decoded = dev.lingoflow.app.core.catalog.DecodedCatalog(localeFile.file.entries.toList())
        return session.source.write(localeFile.path, impl.encode(decoded, ctx))
    }

    /** Saves every changed locale file for one locale. */
    fun saveLocale(session: ProjectSession, locale: String): List<String> {
        val written = mutableListOf<String>()
        for (file in session.filesFor(locale)) {
            if (save(session, file)) written += file.path
        }
        return written
    }

    fun keyPatterns(source: ProjectSource, config: ProjectConfig): List<String> =
        config.catalogs.map { it.path.replace("{locale}", "*") }.map { it.replace("**", "*") }

    @Suppress("UNUSED_PARAMETER")
    private fun unused(entry: CatalogEntry): String = Keys.display(entry.key)
}
