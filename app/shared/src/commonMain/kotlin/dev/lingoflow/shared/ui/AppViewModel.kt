package dev.lingoflow.shared.ui

import dev.lingoflow.shared.domain.AppSettings
import dev.lingoflow.shared.domain.ChatEngine
import dev.lingoflow.shared.domain.CorpusEntry
import dev.lingoflow.shared.domain.DocumentTranslator
import dev.lingoflow.shared.domain.EngineKind
import dev.lingoflow.shared.domain.PlatformServices
import dev.lingoflow.shared.domain.PolishPresets
import dev.lingoflow.shared.domain.StylePresets
import dev.lingoflow.shared.domain.Term
import dev.lingoflow.shared.domain.TermExtractor
import dev.lingoflow.shared.domain.TranslationProject
import dev.lingoflow.shared.domain.VocabEntry
import dev.lingoflow.shared.domain.doc.BlockKind
import dev.lingoflow.shared.domain.doc.DocFormat
import dev.lingoflow.shared.domain.doc.Document
import dev.lingoflow.shared.domain.doc.DocumentMeta
import dev.lingoflow.shared.domain.doc.ExportFile
import dev.lingoflow.shared.domain.doc.Exporters
import dev.lingoflow.shared.domain.doc.MarkdownImporter
import dev.lingoflow.shared.domain.doc.SubtitleImporter
import dev.lingoflow.shared.domain.doc.TextImporter
import dev.lingoflow.shared.domain.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { PROJECTS, DOCUMENTS, TRANSLATE, TERMS, CORPUS, VOCABULARY, SETTINGS }

data class UiState(
    val loading: Boolean = false,
    val busyLabel: String = "",
    val projects: List<TranslationProject> = emptyList(),
    val project: TranslationProject? = null,
    val documents: List<DocumentMeta> = emptyList(),
    val document: Document? = null,
    val terms: List<Term> = emptyList(),
    val corpus: List<CorpusEntry> = emptyList(),
    val vocabulary: List<VocabEntry> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val apiKeySet: Boolean = false,
    val screen: Screen = Screen.PROJECTS,
    val message: String? = null,
    val error: String? = null,
    val progress: Pair<Int, Int>? = null,
    val candidates: List<TermExtractor.Candidate> = emptyList(),
    val explanation: String? = null,
    val explanationFor: Int? = null,
    val ocrText: String? = null,
    val showImportDialog: Boolean = false,
)

/**
 * Single state holder for every screen (no Android dependencies), so the exact
 * same UI logic runs on the phone and on the desktop.
 */
class AppViewModel(private val services: PlatformServices) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        scope.launch { reloadAll() }
    }

    fun close() = scope.cancel()

    fun workspaceLocation(): String = services.store.location

    private fun update(block: (UiState) -> UiState) {
        _state.value = block(_state.value)
    }

    private suspend fun reloadAll() {
        update { it.copy(loading = true, busyLabel = "Loading workspace…") }
        withContext(Dispatchers.Default) {
            val projects = services.store.projects()
            val settings = services.store.settings()
            val keySet = services.store.apiKey().isNotBlank()
            update {
                it.copy(
                    loading = false,
                    projects = projects,
                    settings = settings,
                    apiKeySet = keySet,
                    project = it.project?.let { current -> projects.firstOrNull { p -> p.id == current.id } ?: current },
                )
            }
        }
    }

    fun message(text: String?) = update { it.copy(message = text) }
    fun error(text: String?) = update { it.copy(error = text) }

    // ---------------------------------------------------------------- projects

    fun createProject(name: String, sourceLang: String, targetLang: String) {
        scope.launch {
            update { it.copy(loading = true, busyLabel = "Creating project…") }
            val project = services.store.createProject(name, sourceLang, targetLang)
            withContext(Dispatchers.Default) { services.store.saveSettings(_state.value.settings) }
            val projects = services.store.projects()
            update { it.copy(loading = false, projects = projects, project = project, screen = Screen.DOCUMENTS) }
        }
    }

    fun openProject(project: TranslationProject) {
        scope.launch {
            update { it.copy(loading = true, busyLabel = "Opening ${project.name}…", project = project) }
            val documents = services.store.documents(project.id)
            val terms = services.store.terms(project.id)
            val corpus = services.store.corpus(project.id)
            val vocabulary = services.store.vocabulary(project.id)
            update {
                it.copy(
                    loading = false,
                    project = project,
                    documents = documents,
                    terms = terms,
                    corpus = corpus,
                    vocabulary = vocabulary,
                    screen = Screen.DOCUMENTS,
                    candidates = emptyList(),
                )
            }
        }
    }

    fun deleteProject(project: TranslationProject) {
        scope.launch {
            services.store.deleteProject(project.id)
            val projects = services.store.projects()
            update { it.copy(project = null, projects = projects, screen = Screen.PROJECTS) }
        }
    }

    fun go(screen: Screen) = update { it.copy(screen = screen) }

    fun backToProjects() = update { it.copy(screen = Screen.PROJECTS) }

    fun backToDocuments() = update { it.copy(screen = Screen.DOCUMENTS, document = null) }

    // --------------------------------------------------------------- documents

    fun importDocument(file: dev.lingoflow.shared.domain.ImportedFile) {
        val project = _state.value.project ?: return
        scope.launch {
            update { it.copy(loading = true, busyLabel = "Importing ${file.name}…") }
            val importer = services.binaryImporter
            val extension = file.name.substringAfterLast('.', "").lowercase()
            val document = withContext(Dispatchers.Default) {
                when {
                    extension == "md" || extension == "markdown" || extension == "mdx" ->
                        MarkdownImporter.import(file.text.orEmpty(), newId("doc", file.name), file.name, project.sourceLang, project.targetLang)
                    extension == "srt" || extension == "vtt" ->
                        SubtitleImporter.import(file.text.orEmpty(), newId("doc", file.name), file.name, project.sourceLang, project.targetLang)
                    extension == "txt" || file.text != null ->
                        TextImporter.import(file.text.orEmpty(), newId("doc", file.name), file.name, project.sourceLang, project.targetLang)
                    extension == "docx" || extension == "pdf" -> {
                        val extracted = importer.extract(file.name, file.bytes)
                        if (extracted == null) {
                            null
                        } else {
                            val format = if (extension == "pdf") DocFormat.PDF else DocFormat.DOCX
                            val text = TextImporter.import(extracted.text, newId("doc", file.name), file.name, project.sourceLang, project.targetLang)
                            text.copy(format = format)
                        }
                    }
                    else -> null
                }
            }
            if (document == null) {
                update { it.copy(loading = false, error = "Cannot read ${file.name}: unsupported or scanned document") }
                return@launch
            }
            withContext(Dispatchers.Default) {
                services.store.saveDocument(project.id, document)
            }
            val documents = services.store.documents(project.id)
            update {
                it.copy(
                    loading = false,
                    document = document,
                    documents = documents,
                    screen = Screen.TRANSLATE,
                    message = "Imported ${document.segments.size} segments",
                )
            }
        }
    }

    fun importText(title: String, text: String, format: DocFormat = DocFormat.TEXT) {
        val project = _state.value.project ?: return
        val document = when (format) {
            DocFormat.MARKDOWN -> MarkdownImporter.import(text, newId("doc", title), title, project.sourceLang, project.targetLang)
            DocFormat.SUBTITLE -> SubtitleImporter.import(text, newId("doc", title), title, project.sourceLang, project.targetLang)
            else -> TextImporter.import(text, newId("doc", title), title, project.sourceLang, project.targetLang)
        }
        scope.launch {
            withContext(Dispatchers.Default) { services.store.saveDocument(project.id, document) }
            val documents = services.store.documents(project.id)
            update {
                it.copy(
                    document = document,
                    documents = documents,
                    screen = Screen.TRANSLATE,
                    showImportDialog = false,
                )
            }
        }
    }

    fun openDocument(meta: DocumentMeta) {
        val project = _state.value.project ?: return
        scope.launch {
            update { it.copy(loading = true, busyLabel = "Opening document…") }
            val document = services.store.loadDocument(project.id, meta.id)
            update { it.copy(loading = false, document = document, screen = Screen.TRANSLATE) }
        }
    }

    fun deleteDocument(meta: DocumentMeta) {
        val project = _state.value.project ?: return
        scope.launch {
            services.store.deleteDocument(project.id, meta.id)
            val documents = services.store.documents(project.id)
            update { it.copy(documents = documents) }
        }
    }

    fun setShowImportDialog(show: Boolean) = update { it.copy(showImportDialog = show) }

    // ------------------------------------------------------------------ editor

    /** Human edit: stores the pair into the parallel corpus automatically. */
    fun editSegment(segmentId: Int, text: String) {
        val document = _state.value.document ?: return
        val project = _state.value.project ?: return
        val segment = document.segments.firstOrNull { it.id == segmentId } ?: return
        val updated = document.updateSegment(segmentId, { it.copy(target = text, locked = true, origin = "human") })
        update { it.copy(document = updated) }
        scope.launch {
            withContext(Dispatchers.Default) {
                services.store.saveDocument(project.id, updated)
                if (segment.source.isNotBlank() && text.isNotBlank() && text != segment.target) {
                    services.store.appendCorpus(
                        project.id,
                        listOf(
                            CorpusEntry(
                                id = newId("corpus", segment.source + text),
                                source = segment.source,
                                target = text,
                                sourceLang = document.sourceLang,
                                targetLang = document.targetLang,
                                projectId = project.id,
                                documentId = document.id,
                                confirmed = true,
                                createdAt = System.currentTimeMillis(),
                            ),
                        ),
                    )
                }
            }
            val corpus = services.store.corpus(project.id)
            update { it.copy(corpus = corpus) }
        }
    }

    fun lockSegment(segmentId: Int, locked: Boolean) {
        val document = _state.value.document ?: return
        val updated = document.updateSegment(segmentId, { it.copy(locked = locked) })
        update { it.copy(document = updated) }
    }

    fun translateDocument(polish: Boolean = false) {
        val document = _state.value.document ?: return
        val project = _state.value.project ?: return
        val settings = _state.value.settings
        scope.launch {
            val engine = services.createEngine(settings)
            val options = dev.lingoflow.shared.domain.TranslateOptions(
                stylePreset = StylePresets.byId(settings.stylePresetId),
                contextWindow = settings.contextWindow,
                batchSize = settings.maxSegmentsPerRequest,
                explain = settings.explainSegments,
            )
            val translator = DocumentTranslator(engine, _state.value.terms, _state.value.corpus, settings, options)
            update { it.copy(progress = 0 to 1, busyLabel = if (polish) "Polishing…" else "Translating…") }
            val (result, report) = try {
                if (polish) {
                    translator.polish(
                        document,
                        PresetHolder.polishPreset,
                        onProgress = { progress -> update { it.copy(progress = progress.done to progress.total) } },
                        onSegment = { segment -> update { it.copy(document = (_state.value.document ?: document).updateSegment(segment.id, { segment })) } },
                    )
                } else {
                    translator.translate(
                        document,
                        onProgress = { progress -> update { it.copy(progress = progress.done to progress.total) } },
                        onSegment = { segment -> update { it.copy(document = (_state.value.document ?: document).updateSegment(segment.id, { segment })) } },
                    )
                }
            } catch (error: Exception) {
                update { it.copy(progress = null, busyLabel = "", error = error.message ?: "Translation failed") }
                return@launch
            }
            withContext(Dispatchers.Default) {
                services.store.saveDocument(project.id, result)
                services.store.appendCorpus(
                    project.id,
                    result.segments.filter { it.origin == engine.id && it.target.isNotBlank() }.map { segment ->
                        CorpusEntry(
                            id = newId("corpus", segment.source + segment.target),
                            source = segment.source,
                            target = segment.target,
                            sourceLang = document.sourceLang,
                            targetLang = document.targetLang,
                            projectId = project.id,
                            documentId = document.id,
                            confirmed = false,
                            createdAt = System.currentTimeMillis(),
                        )
                    },
                )
            }
            val corpus = services.store.corpus(project.id)
            val documents = services.store.documents(project.id)
            update {
                it.copy(
                    document = result,
                    progress = null,
                    busyLabel = "",
                    corpus = corpus,
                    documents = documents,
                    message = buildString {
                        append(if (polish) "Polished " else "Translated ").append(report.translated).append(" segment(s)")
                        if (report.fromCorpus > 0) append(" · ${report.fromCorpus} reused from corpus")
                        if (report.errors.isNotEmpty()) append(" · ${report.errors.size} error(s)")
                    },
                )
            }
            if (report.errors.isNotEmpty()) {
                update { it.copy(error = report.errors.first()) }
            }
        }
    }

    fun explainSegment(segmentId: Int) {
        val document = _state.value.document ?: return
        val segment = document.segments.firstOrNull { it.id == segmentId } ?: return
        scope.launch {
            update { it.copy(explanationFor = segmentId, explanation = null) }
            val engine = services.createEngine(_state.value.settings)
            val translator = DocumentTranslator(engine, _state.value.terms, _state.value.corpus, _state.value.settings)
            val text = translator.explain(document, segment)
            update { it.copy(explanation = text ?: "No explanation available (configure a model in Settings)") }
        }
    }

    fun clearExplanation() = update { it.copy(explanation = null, explanationFor = null) }

    // ------------------------------------------------------------------- terms

    fun addTerm(source: String, target: String, note: String = "", origin: String = "manual") {
        val project = _state.value.project ?: return
        if (source.isBlank() || target.isBlank()) return
        val term = Term(
            id = newId("term", source + target),
            source = source.trim(),
            target = target.trim(),
            note = note,
            origin = origin,
        )
        scope.launch {
            val terms = (_state.value.terms + term).distinctBy { it.key }
            withContext(Dispatchers.Default) { services.store.saveTerms(project.id, terms) }
            update { it.copy(terms = terms, message = "Term saved: $source → $target") }
        }
    }

    fun updateTerm(term: Term) {
        val project = _state.value.project ?: return
        scope.launch {
            val terms = _state.value.terms.map { if (it.id == term.id) term else it }
            withContext(Dispatchers.Default) { services.store.saveTerms(project.id, terms) }
            update { it.copy(terms = terms) }
        }
    }

    fun deleteTerm(term: Term) {
        val project = _state.value.project ?: return
        scope.launch {
            val terms = _state.value.terms.filterNot { it.id == term.id }
            withContext(Dispatchers.Default) { services.store.saveTerms(project.id, terms) }
            update { it.copy(terms = terms) }
        }
    }

    fun extractCandidates() {
        val document = _state.value.document ?: _state.value.project?.let { null }
        val target = document ?: run {
            val project = _state.value.project ?: return
            scope.launch {
                update { it.copy(loading = true, busyLabel = "Reading documents…") }
                val all = services.store.documents(project.id).mapNotNull { services.store.loadDocument(project.id, it.id) }
                val merged = all.fold(Document.empty("all", "all", project.sourceLang, project.targetLang, DocFormat.TEXT)) { acc, doc -> acc.copy(segments = acc.segments + doc.segments) }
                val candidates = TermExtractor.extract(merged, existing = _state.value.terms.map { it.source }.toSet())
                update {
                    it.copy(
                        loading = false,
                        busyLabel = "",
                        candidates = candidates,
                        message = if (candidates.isEmpty()) "No new term candidates" else "${candidates.size} candidate(s)",
                    )
                }
            }
            return
        }
        scope.launch {
            update { it.copy(loading = true, busyLabel = "Mining terms…") }
            val candidates = withContext(Dispatchers.Default) {
                TermExtractor.extract(target, existing = _state.value.terms.map { it.source }.toSet())
            }
            update {
                it.copy(
                    loading = false,
                    busyLabel = "",
                    candidates = candidates,
                    screen = Screen.TERMS,
                    message = if (candidates.isEmpty()) "No new term candidates" else "${candidates.size} candidate(s)",
                )
            }
        }
    }

    fun acceptCandidate(candidate: TermExtractor.Candidate, target: String) {
        addTerm(candidate.text, target, note = "auto-extracted ×${candidate.count}", origin = "extracted")
        update { it.copy(candidates = it.candidates.filterNot { c -> c.text == candidate.text }) }
    }

    fun dismissCandidate(candidate: TermExtractor.Candidate) =
        update { it.copy(candidates = it.candidates.filterNot { c -> c.text == candidate.text }) }

    // ------------------------------------------------------------------ corpus

    fun deleteCorpusEntry(entry: CorpusEntry) {
        val project = _state.value.project ?: return
        scope.launch {
            val remaining = _state.value.corpus.filterNot { it.id == entry.id }
            withContext(Dispatchers.Default) {
                services.store.saveCorpusSnapshot(project.id, remaining)
            }
            update { it.copy(corpus = remaining) }
        }
    }

    fun addCorpusEntry(source: String, target: String) {
        val project = _state.value.project ?: return
        if (source.isBlank() || target.isBlank()) return
        scope.launch {
            withContext(Dispatchers.Default) {
                services.store.appendCorpus(
                    project.id,
                    listOf(
                        CorpusEntry(
                            id = newId("corpus", source + target),
                            source = source,
                            target = target,
                            sourceLang = project.sourceLang,
                            targetLang = project.targetLang,
                            projectId = project.id,
                            confirmed = true,
                            createdAt = System.currentTimeMillis(),
                        ),
                    ),
                )
            }
            val corpus = services.store.corpus(project.id)
            update { it.copy(corpus = corpus, message = "Saved to corpus") }
        }
    }

    // -------------------------------------------------------------- vocabulary

    fun addWord(word: String, phonetic: String, meaning: String, example: String) {
        val project = _state.value.project ?: return
        if (word.isBlank()) return
        val entry = VocabEntry(
            id = newId("word", word),
            word = word.trim(),
            phonetic = phonetic.trim(),
            meaning = meaning.trim(),
            example = example.trim(),
            projectId = project.id,
            createdAt = System.currentTimeMillis(),
        )
        scope.launch {
            val entries = (_state.value.vocabulary + entry).distinctBy { it.word.lowercase() }
            withContext(Dispatchers.Default) { services.store.saveVocabulary(project.id, entries) }
            update { it.copy(vocabulary = entries, message = "Added to vocabulary: $word") }
        }
    }

    fun saveWord(entry: VocabEntry) {
        val project = _state.value.project ?: return
        scope.launch {
            val entries = _state.value.vocabulary.map { if (it.id == entry.id) entry else it }
            withContext(Dispatchers.Default) { services.store.saveVocabulary(project.id, entries) }
            update { it.copy(vocabulary = entries) }
        }
    }

    fun deleteWord(entry: VocabEntry) {
        val project = _state.value.project ?: return
        scope.launch {
            val entries = _state.value.vocabulary.filterNot { it.id == entry.id }
            withContext(Dispatchers.Default) { services.store.saveVocabulary(project.id, entries) }
            update { it.copy(vocabulary = entries) }
        }
    }

    // ---------------------------------------------------------------- settings

    fun saveSettings(settings: AppSettings) {
        scope.launch {
            withContext(Dispatchers.Default) { services.store.saveSettings(settings) }
            update { it.copy(settings = settings, message = "Settings saved") }
        }
    }

    fun saveApiKey(key: String) {
        scope.launch {
            withContext(Dispatchers.Default) { services.store.saveApiKey(key) }
            update { it.copy(apiKeySet = key.isNotBlank(), message = if (key.isBlank()) "API key cleared" else "API key stored on this device") }
        }
    }

    // ------------------------------------------------------------------- files

    fun export(export: ExportFile) {
        scope.launch {
            update { it.copy(loading = true, busyLabel = "Exporting…") }
            val result = try {
                services.exportFile(export)
            } catch (error: Exception) {
                update { it.copy(loading = false, busyLabel = "", error = error.message ?: "Export failed") }
                return@launch
            }
            update { it.copy(loading = false, busyLabel = "", message = result) }
        }
    }

    fun pickImport() {
        scope.launch {
            val file = try {
                services.pickDocument()
            } catch (error: Exception) {
                update { it.copy(error = error.message ?: "Cannot open picker") }
                null
            }
            if (file != null) importDocument(file)
        }
    }

    /** Camera / gallery OCR: recognise then hand the text to the importer. */
    fun runOcr() {
        scope.launch {
            if (!services.ocr.available) {
                update { it.copy(error = services.ocr.description) }
                return@launch
            }
            update { it.copy(loading = true, busyLabel = "Recognising text…") }
            val image = try {
                services.pickImage()
            } catch (error: Exception) {
                update { it.copy(loading = false, error = error.message ?: "Cannot open camera") }
                null
            }
            if (image == null) {
                update { it.copy(loading = false) }
                return@launch
            }
            val result = try {
                services.ocr.recognize(image.bytes)
            } catch (error: Exception) {
                update { it.copy(loading = false, error = error.message ?: "OCR failed") }
                return@launch
            }
            update {
                it.copy(
                    loading = false,
                    ocrText = result.text,
                    showImportDialog = true,
                    message = if (result.isEmpty) "No text found in the image" else "Recognised ${result.lines.size} line(s)",
                )
            }
        }
    }

    fun clearOcrText() = update { it.copy(ocrText = null) }

    fun copy(text: String) {
        services.copyToClipboard(text)
        update { it.copy(message = "Copied") }
    }
}

/** Holds the currently selected polish preset (UI-level state). */
object PresetHolder {
    var polishPreset = PolishPresets.all.first()
}

/** Builds the engine for the configured provider (platform supplies the HTTP client). */
suspend fun engineFor(settings: AppSettings, services: PlatformServices): ChatEngine = services.createEngine(settings)
