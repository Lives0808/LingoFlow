package dev.lingoflow.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lingoflow.app.core.model.Issue
import dev.lingoflow.app.core.model.RuleKind
import dev.lingoflow.app.core.model.RuleRow
import dev.lingoflow.app.core.model.Severity
import dev.lingoflow.app.core.project.AnalysisSnapshot
import dev.lingoflow.app.core.project.Pipeline
import dev.lingoflow.app.core.project.PlannedJob
import dev.lingoflow.app.core.project.ProjectLoader
import dev.lingoflow.app.core.project.ProjectSession
import dev.lingoflow.app.core.project.ProjectSource
import dev.lingoflow.app.core.project.SyncOutcome
import dev.lingoflow.app.core.report.Report
import dev.lingoflow.app.core.rules.RuleSet
import dev.lingoflow.app.core.tm.MemoryStore
import dev.lingoflow.app.core.translate.OfflineEngine
import dev.lingoflow.app.core.translate.TranslationEngine
import dev.lingoflow.app.core.translate.PseudoEngine
import dev.lingoflow.app.data.CustomHttpEngine
import dev.lingoflow.app.data.DemoProject
import dev.lingoflow.app.data.LibreTranslateEngine
import dev.lingoflow.app.data.LocalFileSource
import dev.lingoflow.app.data.OpenAiEngine
import dev.lingoflow.app.data.PaintTextMeasurer
import dev.lingoflow.app.data.Prefs
import dev.lingoflow.app.data.SqliteMemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val projectName: String? = null,
    val projectId: String? = null,
    val session: ProjectSession? = null,
    val analysis: AnalysisSnapshot? = null,
    val rules: List<RuleRow> = emptyList(),
    val memoryStats: MemoryStore.Stats? = null,
    val plan: List<PlannedJob> = emptyList(),
    val busy: Boolean = false,
    val busyLabel: String = "",
    val message: String? = null,
    val error: String? = null,
    val selectedLocale: String? = null,
    val severityFilter: Severity? = null,
    val search: String = "",
    val lastOutcome: SyncOutcome? = null,
    val pseudoPreview: Boolean = false,
    val projectSource: ProjectSource? = null,
)

enum class ReportFormat { HTML, MARKDOWN, JSON }

/**
 * Single state holder for the whole app: loads a project, runs the pipeline and
 * exposes the resulting catalogs, issues and learning results to Compose.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = Prefs(application)
    private val memory = SqliteMemoryStore(application)
    private val measurer = PaintTextMeasurer(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val lastId = prefs.recentProjects().firstOrNull()?.first
        if (lastId != null) {
            val file = File(lastId)
            if (file.exists()) openSource(LocalFileSource(file, file.name))
        }
    }

    fun openSource(source: ProjectSource) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyLabel = "Loading project…", error = null, message = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { ProjectLoader.load(source) }
            }
            result.onSuccess { session ->
                prefs.rememberProject(source.id, source.name)
                val pipeline = pipelineFor(session)
                val analysis = withContext(Dispatchers.IO) { pipeline.analyze() }
                _state.value = _state.value.copy(
                    busy = false,
                    projectName = source.name,
                    projectId = source.id,
                    projectSource = source,
                    session = session,
                    analysis = analysis,
                    rules = memory.listRules(),
                    memoryStats = memory.stats(),
                    selectedLocale = session.locales.firstOrNull { it != session.sourceLocale },
                    message = if (session.warnings.isEmpty()) null else session.warnings.first(),
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(busy = false, error = error.message ?: "Cannot open project")
            }
        }
    }

    fun openDemo() {
        val source = DemoProject.ensure(getApplication())
        openSource(source)
    }

    fun closeProject() {
        _state.value = UiState()
    }

    fun refresh() {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyLabel = "Re-reading catalogs…")
            val reloaded = withContext(Dispatchers.IO) { ProjectLoader.load(session.source) }
            val analysis = withContext(Dispatchers.IO) { pipelineFor(reloaded).analyze() }
            _state.value = _state.value.copy(busy = false, session = reloaded, analysis = analysis, rules = memory.listRules())
        }
    }

    fun selectLocale(locale: String) {
        _state.value = _state.value.copy(selectedLocale = locale)
    }

    fun setSeverityFilter(severity: Severity?) {
        _state.value = _state.value.copy(severityFilter = severity)
    }

    fun setSearch(text: String) {
        _state.value = _state.value.copy(search = text)
    }

    fun togglePseudoPreview() {
        _state.value = _state.value.copy(pseudoPreview = !_state.value.pseudoPreview)
    }

    fun runChecks() {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyLabel = "Validating…")
            val analysis = withContext(Dispatchers.IO) { pipelineFor(session).analyze() }
            _state.value = _state.value.copy(busy = false, analysis = analysis)
        }
    }

    fun planTranslations(force: Boolean, locale: String?) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            val locales = locale?.let { listOf(it) } ?: session.locales.filter { it != session.sourceLocale }
            val plan = withContext(Dispatchers.IO) { pipelineFor(session).plan(locales, force) }
            _state.value = _state.value.copy(plan = plan)
        }
    }

    fun dismissPlan() {
        _state.value = _state.value.copy(plan = emptyList())
    }

    fun runPlan() {
        val session = _state.value.session ?: return
        val jobs = _state.value.plan
        if (jobs.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyLabel = "Translating…")
            val outcomes = mutableListOf<SyncOutcome>()
            withContext(Dispatchers.IO) {
                val pipeline = pipelineFor(session)
                jobs.map { it.locale }.distinct().forEach { locale ->
                    outcomes += pipeline.run(locale, jobs)
                }
            }
            val analysis = withContext(Dispatchers.IO) { pipelineFor(session).analyze() }
            _state.value = _state.value.copy(
                busy = false,
                plan = emptyList(),
                analysis = analysis,
                memoryStats = memory.stats(),
                lastOutcome = outcomes.lastOrNull(),
                message = outcomes.sumOf { it.translated + it.fromMemory }.let { "$it string(s) updated" },
            )
        }
    }

    fun updateValue(locale: String, key: String, value: String) {
        val session = _state.value.session ?: return
        val updated = session.updateEntry(locale, key, value)
        _state.value = _state.value.copy(session = updated)
    }

    fun saveLocale(locale: String) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyLabel = "Saving $locale…")
            val written = withContext(Dispatchers.IO) { ProjectLoader.saveLocale(session, locale) }
            val analysis = withContext(Dispatchers.IO) { pipelineFor(session).analyze() }
            _state.value = _state.value.copy(
                busy = false,
                analysis = analysis,
                message = if (written.isEmpty()) "Nothing to write" else "${written.size} file(s) written",
            )
        }
    }

    fun approve(locale: String, key: String, freeze: Boolean) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val pipeline = pipelineFor(session)
                ProjectLoader.saveLocale(session, locale)
                pipeline.approve(locale, key, freeze)
            }
            _state.value = _state.value.copy(
                memoryStats = memory.stats(),
                message = if (freeze) "Approved and frozen — automation will not overwrite it" else "Approved",
            )
        }
    }

    fun learn(includeGlossary: Boolean) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyLabel = "Learning from edits…")
            val outcome = withContext(Dispatchers.IO) {
                pipelineFor(session).learn(session.locales.filter { it != session.sourceLocale }, includeGlossary)
            }
            _state.value = _state.value.copy(
                busy = false,
                rules = memory.listRules(),
                memoryStats = memory.stats(),
                message = "Learned ${outcome.added} rule(s) from ${outcome.editsAnalyzed} edit(s)",
            )
        }
    }

    fun toggleRule(rule: RuleRow) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { memory.updateRule(rule.id, mapOf("enabled" to !rule.enabled)) }
            _state.value = _state.value.copy(rules = memory.listRules())
        }
    }

    fun deleteRule(rule: RuleRow) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { memory.removeRules(listOf(rule.id)) }
            _state.value = _state.value.copy(rules = memory.listRules(), memoryStats = memory.stats())
        }
    }

    fun addGlossaryTerm(source: String, target: String, locale: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                memory.addRules(
                    listOf(
                        RuleRow(
                            kind = RuleKind.GLOSSARY,
                            locale = locale,
                            pattern = source,
                            value = """{"target":${quoted(target)}}""",
                            priority = 70,
                            enabled = true,
                            origin = "manual",
                            confidence = 1.0,
                        ),
                    ),
                )
            }
            _state.value = _state.value.copy(rules = memory.listRules(), memoryStats = memory.stats(), message = "Term saved")
        }
    }

    fun toggleFreeze(rowId: Long, frozen: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { memory.update(rowId, mapOf("frozen" to frozen, "approved" to true)) }
            _state.value = _state.value.copy(memoryStats = memory.stats(), message = if (frozen) "Entry frozen" else "Entry unfrozen")
        }
    }

    fun memoryRows(locale: String?): List<dev.lingoflow.app.core.model.MemoryRow> = memory.list(locale)

    /** Writes a report next to the project (or to app storage) and returns its path. */
    fun exportReport(format: ReportFormat): File? {
        val session = _state.value.session ?: return null
        val analysis = _state.value.analysis ?: return null
        val snapshot = Report.Snapshot(
            session = session,
            validations = analysis.validations,
            issues = analysis.issues,
            engine = prefs.engine,
        )
        val content = when (format) {
            ReportFormat.HTML -> Report.html(snapshot, Report.placeholderSamples(session))
            ReportFormat.MARKDOWN -> Report.markdown(snapshot)
            ReportFormat.JSON -> Report.json(snapshot)
        }
        val name = when (format) {
            ReportFormat.HTML -> "lingoflow-report.html"
            ReportFormat.MARKDOWN -> "lingoflow-report.md"
            ReportFormat.JSON -> "lingoflow-report.json"
        }
        val written = session.source.write(name, content)
        val target = if (written) {
            File(getApplication<Application>().cacheDir, name).also { it.writeText(content) }
        } else {
            File(getApplication<Application>().cacheDir, name).also { it.writeText(content) }
        }
        _state.value = _state.value.copy(message = if (written) "Report saved as $name" else "Report saved to app storage")
        return target
    }

    fun engineLabel(): String = when (prefs.engine) {
        "openai" -> "OpenAI-compatible"
        "libretranslate" -> "LibreTranslate"
        "custom" -> "Custom HTTP"
        "pseudo" -> "Pseudo-locale"
        else -> "Built-in offline"
    }

    fun prefs(): Prefs = prefs

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    private fun pipelineFor(session: ProjectSession): Pipeline = Pipeline(
        session = session,
        memory = memory,
        rules = RuleSet.fromRows(memory.listRules()),
        measurer = measurer,
        engine = buildEngine(),
    )

    private fun buildEngine(): TranslationEngine {
        if (prefs.offlineOnly) return OfflineEngine()
        return when (prefs.engine) {
            "openai" -> OpenAiEngine(prefs.endpointUrl, prefs.model, prefs.apiKey)
            "libretranslate" -> LibreTranslateEngine(prefs.endpointUrl, prefs.apiKey)
            "custom" -> CustomHttpEngine(prefs.endpointUrl, prefs.customBodyTemplate, prefs.customResponsePath, prefs.apiKey)
            "pseudo" -> PseudoEngineAdapter
            else -> OfflineEngine()
        }
    }

    private fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private object PseudoEngineAdapter : TranslationEngine {
        override val id: String = "pseudo"
        override val label: String = "Pseudo-locale"
        override val network: Boolean = false

        override fun translate(items: List<dev.lingoflow.app.core.translate.TranslateItem>, context: dev.lingoflow.app.core.translate.EngineContext) =
            items.map { item ->
                dev.lingoflow.app.core.translate.TranslateOutput(item.id, PseudoEngine.localize(item.text, maxChars = item.maxChars), id, 1.0)
            }
    }

    fun quickIssues(locale: String, key: String, value: String): List<Issue> {
        val session = _state.value.session ?: return emptyList()
        return pipelineFor(session).quickIssues(locale, key, value)
    }
}
