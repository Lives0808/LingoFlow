package dev.lingoflow.shared.domain

import dev.lingoflow.shared.domain.doc.Document
import dev.lingoflow.shared.domain.doc.DocumentMeta
import dev.lingoflow.shared.domain.doc.ExportFile

/**
 * Persistence contract.
 *
 * Implemented on the JVM/Android side with plain files under a per-user data
 * directory: one folder per project holding `project.json`, `terms.json`,
 * `corpus.jsonl`, `vocabulary.json`, `documents/<docId>.json`. Nothing is uploaded and
 * the whole workspace can be copied to another machine by hand.
 */
interface WorkspaceStore {
    /** Human readable location shown in the settings screen. */
    val location: String

    suspend fun projects(): List<TranslationProject>
    suspend fun createProject(name: String, sourceLang: String, targetLang: String): TranslationProject
    suspend fun saveProject(project: TranslationProject)
    suspend fun deleteProject(projectId: String)

    suspend fun documents(projectId: String): List<DocumentMeta>
    suspend fun loadDocument(projectId: String, documentId: String): Document?
    suspend fun saveDocument(projectId: String, document: Document)
    suspend fun deleteDocument(projectId: String, documentId: String)

    suspend fun terms(projectId: String): List<Term>
    suspend fun saveTerms(projectId: String, terms: List<Term>)

    suspend fun corpus(projectId: String): List<CorpusEntry>
    suspend fun appendCorpus(projectId: String, entries: List<CorpusEntry>)
    /** Replaces the whole corpus (used for deletes and imports). */
    suspend fun saveCorpusSnapshot(projectId: String, entries: List<CorpusEntry>)

    suspend fun vocabulary(projectId: String): List<VocabEntry>
    suspend fun saveVocabulary(projectId: String, entries: List<VocabEntry>)

    suspend fun settings(): AppSettings
    suspend fun saveSettings(settings: AppSettings)

    suspend fun apiKey(): String
    suspend fun saveApiKey(key: String)
}

/** A file the user picked, already read into memory. */
data class ImportedFile(
    val name: String,
    val bytes: ByteArray,
    /** Text files arrive decoded; binary ones are parsed by the importer. */
    val text: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is ImportedFile && other.name == name && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
}

/** Platform capabilities the shared UI needs (files, camera, sharing, clipboard). */
interface PlatformServices {
    val name: String
    val store: WorkspaceStore
    val ocr: OcrEngine
    val binaryImporter: dev.lingoflow.shared.domain.doc.BinaryImporter

    /** True on desktop: hovering shows explanations, mobile uses tap. */
    val supportsHover: Boolean

    /** Opens the platform file picker for documents (txt/md/docx/pdf/srt). */
    suspend fun pickDocument(): ImportedFile?

    /** Opens the platform picker for images (camera roll / camera). */
    suspend fun pickImage(): ImportedFile?

    /** Writes an export next to the user's files or opens the share sheet. */
    suspend fun exportFile(file: ExportFile): String

    /** Copies text to the clipboard (used by "copy translation"). */
    fun copyToClipboard(text: String)

    /** Optional: text currently in the clipboard (clipboard watch, P2). */
    fun clipboardText(): String = ""

    /** Creates the configured translation backend (offline or an HTTP model). */
    suspend fun createEngine(settings: AppSettings): ChatEngine
}
