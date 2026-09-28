package dev.lingoflow.app.core.project

import dev.lingoflow.app.core.model.CatalogEntry
import dev.lingoflow.app.core.model.Issue
import dev.lingoflow.app.core.model.MemoryRow
import dev.lingoflow.app.core.model.Severity
import dev.lingoflow.app.core.model.SuggestionRow
import dev.lingoflow.app.core.rules.Learn
import dev.lingoflow.app.core.rules.RuleEngine
import dev.lingoflow.app.core.rules.RuleSet
import dev.lingoflow.app.core.tm.Fuzzy
import dev.lingoflow.app.core.tm.MemoryStore
import dev.lingoflow.app.core.translate.EngineContext
import dev.lingoflow.app.core.translate.OfflineEngine
import dev.lingoflow.app.core.translate.TranslateItem
import dev.lingoflow.app.core.translate.TranslationEngine
import dev.lingoflow.app.core.validate.Fixer
import dev.lingoflow.app.core.validate.LocaleValidation
import dev.lingoflow.app.core.validate.Placeholders
import dev.lingoflow.app.core.validate.Validator
import dev.lingoflow.app.core.model.TextMeasurer

/** What the pipeline decided to do with one key. */
data class PlannedJob(
    val locale: String,
    val key: String,
    val source: String,
    val current: String?,
    val action: Action,
    val reason: String,
    val memory: MemoryRow? = null,
    val memoryScore: Double = 0.0,
    val frozen: Boolean = false,
) {
    enum class Action { TRANSLATE, MEMORY, FUZZY, SKIP, FROZEN }
}

data class SyncOutcome(
    val locale: String,
    val translated: Int,
    val fromMemory: Int,
    val fixed: Int,
    val filesWritten: List<String>,
    val issues: List<Issue>,
    val notes: List<String>,
)

data class AnalysisSnapshot(
    val validations: List<LocaleValidation>,
    val issues: List<Issue>,
    val keys: Int,
) {
    val errors: Int get() = issues.count { it.severity == Severity.ERROR }
    val warnings: Int get() = issues.count { it.severity == Severity.WARN }
    val infos: Int get() = issues.count { it.severity == Severity.INFO }
}

/**
 * Plan → translate → fix → validate → write back → learn.
 * The same order as `lingoflow sync`, minus the code scanning (no source tree on
 * a phone) and with the translation memory stored in the app's database.
 */
class Pipeline(
    private val session: ProjectSession,
    private val memory: MemoryStore,
    private val rules: RuleSet,
    private val measurer: TextMeasurer,
    private val engine: TranslationEngine = OfflineEngine(),
) {
    private val validator = Validator(session.config, rules, measurer)

    fun analyze(locales: List<String> = session.locales, frozenKeys: Set<String> = emptySet()): AnalysisSnapshot {
        val validations = locales.map { locale ->
            validator.validateLocale(locale, session.sourceEntries, session.valuesFor(locale), frozenKeys)
        }
        return AnalysisSnapshot(validations, validations.flatMap { it.issues }, session.sourceEntries.size)
    }

    /** Decides what needs translating, cheapest option first. */
    fun plan(locales: List<String>, force: Boolean = false): List<PlannedJob> {
        val policy = session.config.translation.policy
        val jobs = mutableListOf<PlannedJob>()
        for (locale in locales) {
            if (locale == session.sourceLocale) continue
            val pairs = memory.pairs(session.sourceLocale, locale)
            for ((key, entry) in session.sourceEntries) {
                val source = entry.value ?: continue
                if (source.isBlank()) continue
                val current = session.valuesFor(locale)[key]
                val hash = Learn.sourceHash(session.sourceLocale, source)
                val memoryRow = pairs.firstOrNull { it.sourceHashOf() == hash }
                val frozen = memoryRow?.frozen == true
                val hasValue = !current.isNullOrBlank()

                val job = when {
                    frozen && !hasValue && memoryRow != null -> PlannedJob(
                        locale, key, source, current, PlannedJob.Action.MEMORY, "frozen translation restored from memory", memoryRow,
                    )
                    frozen -> PlannedJob(locale, key, source, current, PlannedJob.Action.FROZEN, "translation is frozen (approved earlier)", memoryRow)
                    force -> PlannedJob(locale, key, source, current, PlannedJob.Action.TRANSLATE, "forced")
                    policy == "all" -> PlannedJob(locale, key, source, current, PlannedJob.Action.TRANSLATE, "policy=all")
                    policy == "untranslated" && hasValue && current != source ->
                        PlannedJob(locale, key, source, current, PlannedJob.Action.SKIP, "already translated")
                    policy == "untranslated" -> PlannedJob(
                        locale, key, source, current, PlannedJob.Action.TRANSLATE,
                        if (hasValue) "identical to source" else "missing translation",
                    )
                    hasValue -> PlannedJob(locale, key, source, current, PlannedJob.Action.SKIP, "already translated")
                    else -> PlannedJob(
                        locale, key, source, current, PlannedJob.Action.TRANSLATE,
                        if (current == null) "missing translation" else "empty value",
                    )
                }
                val fuzzy = if (job.action == PlannedJob.Action.TRANSLATE && session.config.translation.fuzzyAutofill) {
                    Fuzzy.best(source, pairs, session.config.translation.fuzzyThreshold)
                } else {
                    null
                }
                jobs += if (fuzzy != null) {
                    job.copy(
                        action = PlannedJob.Action.FUZZY,
                        reason = "fuzzy memory match (${(fuzzy.score * 100).toInt()}%)",
                        memory = fuzzy.row,
                        memoryScore = fuzzy.score,
                    )
                } else {
                    job
                }
            }
        }
        return jobs
    }

    /** Executes the plan for one locale and writes the result back. */
    fun run(locale: String, jobs: List<PlannedJob>): SyncOutcome {
        val localeJobs = jobs.filter { it.locale == locale }
        val notes = mutableListOf<String>()
        val issues = mutableListOf<Issue>()
        var translated = 0
        var fromMemory = 0
        var fixed = 0

        val engineItems = mutableListOf<TranslateItem>()
        val itemByKey = LinkedHashMap<String, PlannedJob>()

        for (job in localeJobs) {
            when (job.action) {
                PlannedJob.Action.SKIP -> Unit
                PlannedJob.Action.FROZEN -> {
                    if (job.memory != null && job.memory.target != job.current) {
                        issues += Issue(
                            code = "frozen.protected",
                            severity = Severity.WARN,
                            locale = locale,
                            key = job.key,
                            message = "Frozen translation kept; approved value is \"${job.memory.target.take(40)}\"",
                            detail = "unfreeze the entry to let automation update it",
                            target = job.current ?: "",
                        )
                    }
                }
                PlannedJob.Action.MEMORY, PlannedJob.Action.FUZZY -> {
                    val target = job.memory?.target ?: continue
                    applyValue(locale, job.key, target)
                    fromMemory++
                }
                PlannedJob.Action.TRANSLATE -> {
                    val id = "$locale\u0000${job.key}"
                    itemByKey[id] = job
                    engineItems += TranslateItem(
                        id = id,
                        key = job.key,
                        text = job.source,
                        from = session.sourceLocale,
                        to = locale,
                        comment = session.sourceEntries[job.key]?.comment,
                        maxChars = session.sourceEntries[job.key]?.maxLength,
                    )
                }
            }
        }

        if (engineItems.isNotEmpty()) {
            val context = EngineContext(
                glossary = rules.glossaryFor(locale),
                dnt = rules.dntFor(locale),
                styleHints = buildList {
                    if (rules.glossaryFor(locale).isNotEmpty()) add("${rules.glossaryFor(locale).size} glossary terms active")
                },
            )
            val shield = session.config.translation.glossaryMode == "protect" ||
                (session.config.translation.glossaryMode == "auto" && engine.id != "openai")
            val protections = LinkedHashMap<String, RuleEngine.Protection>()
            val outputs = try {
                engine.translate(
                    engineItems.map { item ->
                        if (!shield) item else {
                            val protection = RuleEngine.protect(item.text, context.glossary, context.dnt)
                            protections[item.id] = protection
                            item.copy(text = protection.text)
                        }
                    },
                    context,
                )
            } catch (error: Exception) {
                notes += "Engine ${engine.id} failed: ${error.message}"
                emptyList()
            }
            for (output in outputs) {
                val job = itemByKey[output.id] ?: continue
                var text = output.text
                protections[output.id]?.let { text = RuleEngine.restore(text, it) }
                val (fixedText, fixes) = Fixer.fix(
                    session.config,
                    rules,
                    job.key,
                    locale,
                    job.source,
                    text,
                    session.sourceEntries[job.key],
                )
                if (fixes.isNotEmpty()) fixed++
                applyValue(locale, job.key, fixedText)
                translated++
                memory.upsertMany(
                    listOf(
                        MemoryRow(
                            sourceLocale = session.sourceLocale,
                            targetLocale = locale,
                            source = job.source,
                            target = fixedText,
                            engine = output.engine,
                            confidence = output.confidence,
                            refs = emptyList(),
                            approved = false,
                            frozen = false,
                        ),
                    ),
                )
                memory.addSuggestions(
                    listOf(
                        SuggestionRow(
                            sourceHash = Learn.sourceHash(session.sourceLocale, job.source),
                            source = job.source,
                            locale = locale,
                            machine = fixedText,
                            engine = output.engine,
                            createdAt = System.currentTimeMillis(),
                        ),
                    ),
                )
            }
        }

        val filesWritten = ProjectLoader.saveLocale(session, locale)
        val validation = validator.validateLocale(locale, session.sourceEntries, session.valuesFor(locale))
        return SyncOutcome(locale, translated, fromMemory, fixed, filesWritten, validation.issues, notes)
    }

    private fun applyValue(locale: String, key: String, value: String) {
        for (variants in session.bySource.values) {
            val localeFile = variants[locale] ?: continue
            val index = localeFile.file.entries.indexOfFirst { it.key == key }
            if (index >= 0) {
                localeFile.file.entries[index] = localeFile.file.entries[index].copy(value = value)
            } else {
                localeFile.file.entries += CatalogEntry(key, value)
            }
        }
    }

    /** Approves (and optionally freezes) a translation so nothing overwrites it. */
    fun approve(locale: String, key: String, freeze: Boolean = true) {
        val source = session.sourceEntries[key]?.value ?: return
        val target = session.valuesFor(locale)[key] ?: return
        val existing = memory.exact(session.sourceLocale, locale, source)
        if (existing != null) {
            memory.update(existing.id, mapOf("approved" to true, "frozen" to freeze))
        } else {
            memory.upsertMany(
                listOf(
                    MemoryRow(
                        sourceLocale = session.sourceLocale,
                        targetLocale = locale,
                        source = source,
                        target = target,
                        engine = "human",
                        confidence = 1.0,
                        approved = true,
                        frozen = freeze,
                    ),
                ),
            )
        }
        if (!freeze) {
            val row = memory.exact(session.sourceLocale, locale, source)
            if (row != null) memory.update(row.id, mapOf("frozen" to false))
        }
    }

    /** 防返工: derives correction and style rules from reviewer edits. */
    fun learn(locales: List<String>, includeGlossary: Boolean = false): LearnOutcome {
        val newRules = mutableListOf<dev.lingoflow.app.core.model.RuleRow>()
        var editsAnalyzed = 0
        for (locale in locales) {
            if (locale == session.sourceLocale) continue
            val suggestions = memory.listSuggestions(locale)
            if (suggestions.isEmpty()) continue
            val values = session.valuesFor(locale)
            val edits = suggestions.mapNotNull { suggestion ->
                val entry = session.sourceEntries.values.firstOrNull {
                    Learn.sourceHash(session.sourceLocale, it.value ?: "") == suggestion.sourceHash
                } ?: return@mapNotNull null
                val current = values[entry.key] ?: return@mapNotNull null
                if (current == suggestion.machine) return@mapNotNull null
                Learn.Edit(suggestion.machine, current, entry.value.orEmpty(), locale)
            }
            editsAnalyzed += edits.size
            newRules += Learn.learnCorrectionsFromEdits(edits)
        }

        val styleSamples = mutableListOf<Triple<String, String, String>>()
        for (locale in locales) {
            val values = session.valuesFor(locale)
            for ((key, value) in values) {
                if (value.isNullOrBlank()) continue
                styleSamples += Triple(value, session.sourceEntries[key]?.value.orEmpty(), locale)
            }
        }
        newRules += Learn.learnStyles(styleSamples)

        if (includeGlossary) {
            for (locale in locales) {
                if (locale == session.sourceLocale) continue
                val pairs = memory.pairs(session.sourceLocale, locale)
                    .filter { it.confidence >= 0.5 && it.target.isNotBlank() }
                    .map { it.source to it.target }
                if (pairs.size < 5) continue
                newRules += Learn.learnGlossary(pairs, locale, session.sourceLocale)
            }
        }

        val added = memory.addRules(newRules)
        return LearnOutcome(added, newRules, editsAnalyzed)
    }

    data class LearnOutcome(val added: Int, val rules: List<dev.lingoflow.app.core.model.RuleRow>, val editsAnalyzed: Int)

    /** Placeholder check used by the editor while typing. */
    fun quickIssues(locale: String, key: String, value: String): List<Issue> {
        val source = session.sourceEntries[key]?.value ?: return emptyList()
        return validator.validateEntry(locale, key, source, value, session.sourceEntries[key])
    }

    fun placeholderHint(source: String): List<String> = Placeholders.keys(source)
}

private fun MemoryRow.sourceHashOf(): String = Learn.sourceHash(sourceLocale, source)
