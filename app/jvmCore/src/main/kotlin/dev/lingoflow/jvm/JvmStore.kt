package dev.lingoflow.jvm

import dev.lingoflow.shared.core.catalog.JsonValue
import dev.lingoflow.shared.core.catalog.JsonWriter
import dev.lingoflow.shared.core.catalog.JsonishParser
import dev.lingoflow.shared.domain.AppSettings
import dev.lingoflow.shared.domain.CorpusEntry
import dev.lingoflow.shared.domain.Term
import dev.lingoflow.shared.domain.TranslationProject
import dev.lingoflow.shared.domain.VocabEntry
import dev.lingoflow.shared.domain.WorkspaceStore
import dev.lingoflow.shared.domain.doc.DocFormat
import dev.lingoflow.shared.domain.doc.Document
import dev.lingoflow.shared.domain.doc.DocumentMeta
import dev.lingoflow.shared.domain.doc.Segment
import dev.lingoflow.shared.domain.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** Where an API key is kept: Keystore on Android, an owner-only file elsewhere. */
interface SecretVault {
    fun read(): String
    fun write(value: String)
}

/** Owner-only file vault (0600). Used on desktop; Android injects a keystore vault. */
class FileSecretVault(private val file: File) : SecretVault {
    override fun read(): String = runCatching { file.readText().trim() }.getOrDefault("")

    override fun write(value: String) {
        file.parentFile?.mkdirs()
        file.writeText(value)
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}

/**
 * File-based workspace.
 *
 * ```
 * ~/.lingoflow/
 *   settings.json
 *   secrets.json
 *   projects/<id>/project.json
 *               terms.json
 *               corpus.jsonl
 *               vocabulary.json
 *               documents/<docId>.json
 * ```
 *
 * Plain files on purpose: a project can be zipped, diffed in git or copied to
 * another machine by hand. Nothing is uploaded anywhere.
 */
class JvmWorkspaceStore(
    private val root: File,
    private val vault: SecretVault = FileSecretVault(File(root, "api-key")),
) : WorkspaceStore {

    override val location: String get() = root.absolutePath

    override suspend fun projects(): List<TranslationProject> = io {
        projectsDir().listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { dir -> readProject(File(dir, "project.json")) }
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun createProject(name: String, sourceLang: String, targetLang: String): TranslationProject = io {
        val id = newId("prj", name)
        val project = TranslationProject(
            id = id,
            name = name,
            sourceLang = sourceLang,
            targetLang = targetLang,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        writeProject(project)
        projectDir(id).mkdirs()
        project
    }

    override suspend fun saveProject(project: TranslationProject): Unit = io {
        writeProject(project.copy(updatedAt = System.currentTimeMillis()))
    }

    override suspend fun deleteProject(projectId: String): Unit = io {
        projectDir(projectId).deleteRecursively()
    }

    override suspend fun documents(projectId: String): List<DocumentMeta> = io {
        docsDir(projectId).listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> readDocument(file) }
            .map { document ->
                DocumentMeta(
                    id = document.id,
                    title = document.title,
                    format = document.format,
                    sourceLang = document.sourceLang,
                    targetLang = document.targetLang,
                    segments = document.translatableSegments.size,
                    translated = document.translatedCount,
                    updatedAt = document.updatedAt,
                )
            }
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun loadDocument(projectId: String, documentId: String): Document? = io {
        readDocument(File(docsDir(projectId), "$documentId.json"))
    }

    override suspend fun saveDocument(projectId: String, document: Document): Unit = io {
        val target = File(docsDir(projectId), "${document.id}.json")
        writeJson(target, documentToJson(document.copy(updatedAt = System.currentTimeMillis())))
    }

    override suspend fun deleteDocument(projectId: String, documentId: String): Unit = io {
        File(docsDir(projectId), "$documentId.json").delete()
    }

    override suspend fun terms(projectId: String): List<Term> = io {
        val value = readJson(File(projectDir(projectId), "terms.json")) ?: return@io emptyList()
        val list = (value as? JsonValue.Arr)?.items.orEmpty()
        list.mapNotNull { node ->
            val obj = node as? JsonValue.Obj ?: return@mapNotNull null
            val source = obj.string("source") ?: return@mapNotNull null
            val target = obj.string("target") ?: return@mapNotNull null
            Term(
                id = obj.string("id") ?: newId("term", source),
                source = source,
                target = target,
                note = obj.string("note").orEmpty(),
                locale = obj.string("locale"),
                caseSensitive = obj.boolean("caseSensitive") ?: false,
                enabled = obj.boolean("enabled") ?: true,
                origin = obj.string("origin") ?: "manual",
                hits = obj.number("hits")?.toInt() ?: 0,
            )
        }
    }

    override suspend fun saveTerms(projectId: String, terms: List<Term>): Unit = io {
        val array = terms.map { term ->
            linkedMapOf(
                "id" to term.id,
                "source" to term.source,
                "target" to term.target,
                "note" to term.note,
                "locale" to term.locale,
                "caseSensitive" to term.caseSensitive,
                "enabled" to term.enabled,
                "origin" to term.origin,
                "hits" to term.hits,
            )
        }
        writeJson(File(projectDir(projectId), "terms.json"), array)
    }

    override suspend fun corpus(projectId: String): List<CorpusEntry> = io {
        val file = File(projectDir(projectId), "corpus.jsonl")
        if (!file.exists()) return@io emptyList()
        file.readLines()
            .mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let { parseCorpusLine(it) } }
    }

    override suspend fun appendCorpus(projectId: String, entries: List<CorpusEntry>): Unit = io {
        if (entries.isEmpty()) return@io
        val file = File(projectDir(projectId), "corpus.jsonl")
        file.parentFile?.mkdirs()
        val existing = file.takeIf { it.exists() }?.readLines().orEmpty().toMutableList()
        val bySource = entries.associateBy { it.source.lowercase() + "\u0000" + it.target.lowercase() }
        val known = existing.mapNotNull { parseCorpusLine(it) }
            .associateBy { it.source.lowercase() + "\u0000" + it.target.lowercase() }
        for ((key, entry) in bySource) {
            val previous = known[key]
            if (previous != null && previous.confirmed) continue
            existing += toJsonLine(entry)
        }
        file.writeText(existing.joinToString("\n") + "\n")
    }

    override suspend fun saveCorpusSnapshot(projectId: String, entries: List<CorpusEntry>): Unit = io {
        val file = File(projectDir(projectId), "corpus.jsonl")
        file.writeText(entries.joinToString("\n") { toJsonLine(it) } + if (entries.isEmpty()) "" else "\n")
    }

    override suspend fun vocabulary(projectId: String): List<VocabEntry> = io {
        val value = readJson(File(projectDir(projectId), "vocabulary.json")) ?: return@io emptyList()
        (value as? JsonValue.Arr)?.items.orEmpty().mapNotNull { node ->
            val obj = node as? JsonValue.Obj ?: return@mapNotNull null
            val word = obj.string("word") ?: return@mapNotNull null
            VocabEntry(
                id = obj.string("id") ?: newId("word", word),
                word = word,
                phonetic = obj.string("phonetic").orEmpty(),
                meaning = obj.string("meaning").orEmpty(),
                example = obj.string("example").orEmpty(),
                sourceLang = obj.string("sourceLang") ?: "en",
                projectId = projectId,
                createdAt = obj.number("createdAt")?.toLong() ?: System.currentTimeMillis(),
            )
        }
    }

    override suspend fun saveVocabulary(projectId: String, entries: List<VocabEntry>): Unit = io {
        writeJson(
            File(projectDir(projectId), "vocabulary.json"),
            entries.map { entry ->
                linkedMapOf(
                    "id" to entry.id,
                    "word" to entry.word,
                    "phonetic" to entry.phonetic,
                    "meaning" to entry.meaning,
                    "example" to entry.example,
                    "sourceLang" to entry.sourceLang,
                    "createdAt" to entry.createdAt,
                )
            },
        )
    }

    override suspend fun settings(): AppSettings = io {
        val obj = readJson(File(root, "settings.json")) as? JsonValue.Obj
        if (obj == null) {
            AppSettings()
        } else {
            val defaults = AppSettings()
            AppSettings(
                engine = obj.string("engine")?.let { name ->
                    dev.lingoflow.shared.domain.EngineKind.entries.firstOrNull { it.name.equals(name, true) }
                } ?: defaults.engine,
                baseUrl = obj.string("baseUrl") ?: defaults.baseUrl,
                model = obj.string("model") ?: defaults.model,
                stylePresetId = obj.string("stylePresetId") ?: defaults.stylePresetId,
                contextWindow = obj.number("contextWindow")?.toInt() ?: defaults.contextWindow,
                maxSegmentsPerRequest = obj.number("maxSegmentsPerRequest")?.toInt() ?: defaults.maxSegmentsPerRequest,
                temperature = obj.number("temperature") ?: defaults.temperature,
                explainSegments = obj.boolean("explainSegments") ?: defaults.explainSegments,
                offlineOnly = obj.boolean("offlineOnly") ?: defaults.offlineOnly,
                darkTheme = obj.boolean("darkTheme") ?: defaults.darkTheme,
                ocrLanguage = obj.string("ocrLanguage") ?: defaults.ocrLanguage,
            )
        }
    }

    override suspend fun saveSettings(settings: AppSettings): Unit = io {
        writeJson(
            File(root, "settings.json"),
            linkedMapOf(
                "engine" to settings.engine.name.lowercase(),
                "baseUrl" to settings.baseUrl,
                "model" to settings.model,
                "stylePresetId" to settings.stylePresetId,
                "contextWindow" to settings.contextWindow,
                "maxSegmentsPerRequest" to settings.maxSegmentsPerRequest,
                "temperature" to settings.temperature,
                "explainSegments" to settings.explainSegments,
                "offlineOnly" to settings.offlineOnly,
                "darkTheme" to settings.darkTheme,
                "ocrLanguage" to settings.ocrLanguage,
            ),
        )
    }

    override suspend fun apiKey(): String = io { vault.read() }

    override suspend fun saveApiKey(key: String): Unit = io { vault.write(key) }

    // ------------------------------------------------------------------ helpers

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun projectsDir(): File = File(root, "projects").also { it.mkdirs() }

    private fun projectDir(projectId: String): File = File(projectsDir(), projectId).also { it.mkdirs() }

    private fun docsDir(projectId: String): File = File(projectDir(projectId), "documents").also { it.mkdirs() }

    private fun readProject(file: File): TranslationProject? {
        val obj = readJson(file) as? JsonValue.Obj ?: return null
        return TranslationProject(
            id = obj.string("id") ?: return null,
            name = obj.string("name") ?: "Untitled",
            sourceLang = obj.string("sourceLang") ?: "en",
            targetLang = obj.string("targetLang") ?: "zh-CN",
            createdAt = obj.number("createdAt")?.toLong() ?: 0,
            updatedAt = obj.number("updatedAt")?.toLong() ?: 0,
            note = obj.string("note").orEmpty(),
        )
    }

    private fun writeProject(project: TranslationProject) {
        val dir = projectDir(project.id)
        writeJson(
            File(dir, "project.json"),
            linkedMapOf(
                "id" to project.id,
                "name" to project.name,
                "sourceLang" to project.sourceLang,
                "targetLang" to project.targetLang,
                "createdAt" to project.createdAt,
                "updatedAt" to project.updatedAt,
                "note" to project.note,
            ),
        )
    }

    private fun readDocument(file: File): Document? {
        val obj = readJson(file) as? JsonValue.Obj ?: return null
        val segments = (obj.get("segments") as? JsonValue.Arr)?.items.orEmpty().mapNotNull { node ->
            val segment = node as? JsonValue.Obj ?: return@mapNotNull null
            Segment(
                id = segment.number("id")?.toInt() ?: return@mapNotNull null,
                kind = runCatching {
                    dev.lingoflow.shared.domain.doc.BlockKind.valueOf(
                        (segment.string("kind") ?: "PARAGRAPH").uppercase(),
                    )
                }.getOrDefault(dev.lingoflow.shared.domain.doc.BlockKind.PARAGRAPH),
                source = segment.string("source").orEmpty(),
                target = segment.string("target").orEmpty(),
                note = segment.string("note"),
                level = segment.number("level")?.toInt() ?: 0,
                locked = segment.boolean("locked") ?: false,
                origin = segment.string("origin").orEmpty(),
            )
        }
        return Document(
            id = obj.string("id") ?: return null,
            title = obj.string("title") ?: "Untitled",
            sourceLang = obj.string("sourceLang") ?: "en",
            targetLang = obj.string("targetLang") ?: "zh-CN",
            format = runCatching {
                DocFormat.valueOf((obj.string("format") ?: "TEXT").uppercase())
            }.getOrDefault(DocFormat.TEXT),
            segments = segments,
            createdAt = obj.number("createdAt")?.toLong() ?: 0,
            updatedAt = obj.number("updatedAt")?.toLong() ?: 0,
            fileName = obj.string("fileName").orEmpty(),
        )
    }

    private fun documentToJson(document: Document): Map<String, Any?> = linkedMapOf(
        "id" to document.id,
        "title" to document.title,
        "sourceLang" to document.sourceLang,
        "targetLang" to document.targetLang,
        "format" to document.format.name.lowercase(),
        "createdAt" to document.createdAt,
        "updatedAt" to document.updatedAt,
        "fileName" to document.fileName,
        "segments" to document.segments.map { segment ->
            linkedMapOf(
                "id" to segment.id,
                "kind" to segment.kind.name.lowercase(),
                "source" to segment.source,
                "target" to segment.target,
                "note" to segment.note,
                "level" to segment.level,
                "locked" to segment.locked,
                "origin" to segment.origin,
            )
        },
    )

    private fun parseCorpusLine(line: String): CorpusEntry? {
        val obj = runCatching { JsonishParser(line).parse().value }.getOrNull() as? JsonValue.Obj ?: return null
        return CorpusEntry(
            id = obj.string("id") ?: newId("corpus", line.take(32)),
            source = obj.string("source") ?: return null,
            target = obj.string("target") ?: return null,
            sourceLang = obj.string("sourceLang") ?: "en",
            targetLang = obj.string("targetLang") ?: "zh-CN",
            projectId = obj.string("projectId").orEmpty(),
            documentId = obj.string("documentId").orEmpty(),
            confirmed = obj.boolean("confirmed") ?: true,
            createdAt = obj.number("createdAt")?.toLong() ?: 0,
            hits = obj.number("hits")?.toInt() ?: 0,
        )
    }

    private fun toJsonLine(entry: CorpusEntry): String = JsonWriter.write(
        linkedMapOf(
            "id" to entry.id,
            "source" to entry.source,
            "target" to entry.target,
            "sourceLang" to entry.sourceLang,
            "targetLang" to entry.targetLang,
            "projectId" to entry.projectId,
            "documentId" to entry.documentId,
            "confirmed" to entry.confirmed,
            "createdAt" to entry.createdAt,
            "hits" to entry.hits,
        ),
        indent = 0,
    ).replace("\n", "")

    private fun readJson(file: File): JsonValue? {
        if (!file.exists()) return null
        return runCatching { JsonishParser(file.readText()).parse().value }.getOrNull()
    }

    /** Atomic write so an interrupted save never truncates a project file. */
    private fun writeJson(file: File, value: Any?) {
        file.parentFile?.mkdirs()
        val text = JsonWriter.write(value, indent = 2) + "\n"
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun JsonValue.Obj.string(key: String): String? = (get(key) as? JsonValue.Str)?.value
    private fun JsonValue.Obj.number(key: String): Double? = (get(key) as? JsonValue.Num)?.value
    private fun JsonValue.Obj.boolean(key: String): Boolean? = (get(key) as? JsonValue.Bool)?.value
}
