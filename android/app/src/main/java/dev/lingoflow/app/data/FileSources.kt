package dev.lingoflow.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.lingoflow.app.core.project.ProjectSource
import java.io.File

/** A project living in a normal directory (app storage, demo project, tests). */
class LocalFileSource(private val root: File, override val name: String = root.name) : ProjectSource {
    override val id: String = root.absolutePath

    override fun exists(path: String): Boolean = resolve(path)?.exists() == true

    override fun read(path: String): String? = runCatching {
        resolve(path)?.takeIf { it.isFile }?.readText()
    }.getOrNull()

    override fun write(path: String, content: String): Boolean = runCatching {
        val target = resolve(path) ?: return false
        target.parentFile?.mkdirs()
        target.writeText(content)
        true
    }.getOrDefault(false)

    override fun delete(path: String): Boolean = resolve(path)?.delete() == true

    override fun listFiles(maxDepth: Int): List<String> {
        val results = mutableListOf<String>()
        fun walk(directory: File, depth: Int, prefix: String) {
            if (depth > maxDepth) return
            directory.listFiles()?.forEach { child ->
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                when {
                    child.isDirectory && child.name !in IGNORED_DIRS -> walk(child, depth + 1, relative)
                    child.isFile -> results += relative
                }
            }
        }
        walk(root, 1, "")
        return results
    }

    private fun resolve(path: String): File? {
        val normalized = path.trim('/')
        if (normalized.contains("..")) return null
        return File(root, normalized)
    }

    companion object {
        val IGNORED_DIRS = setOf("node_modules", ".git", "build", "dist", ".gradle", ".lingoflow")
    }
}

/** A project chosen through the system folder picker (SAF). */
class SafFileSource(
    private val context: Context,
    private val treeUri: Uri,
    override val name: String,
) : ProjectSource {
    override val id: String = treeUri.toString()

    private val root: DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    override fun exists(path: String): Boolean = find(path)?.exists() == true

    override fun read(path: String): String? = runCatching {
        val file = find(path) ?: return null
        context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().decodeToString() }
    }.getOrNull()

    override fun write(path: String, content: String): Boolean = runCatching {
        val segments = path.trim('/').split('/')
        var directory = root ?: return false
        for (segment in segments.dropLast(1)) {
            directory = directory.findFile(segment)?.takeIf { it.isDirectory }
                ?: directory.createDirectory(segment) ?: return false
        }
        val fileName = segments.last()
        val document = directory.findFile(fileName) ?: directory.createFile(mimeOf(fileName), fileName) ?: return false
        context.contentResolver.openOutputStream(document.uri, "wt")?.use { stream ->
            stream.write(content.toByteArray())
        }
        true
    }.getOrDefault(false)

    override fun delete(path: String): Boolean = runCatching { find(path)?.delete() == true }.getOrDefault(false)

    override fun listFiles(maxDepth: Int): List<String> {
        val results = mutableListOf<String>()
        fun walk(directory: DocumentFile, depth: Int, prefix: String) {
            if (depth > maxDepth) return
            directory.listFiles().forEach { child ->
                val relative = if (prefix.isEmpty()) child.name.orEmpty() else "$prefix/${child.name}"
                when {
                    child.isDirectory && child.name !in LocalFileSource.IGNORED_DIRS -> walk(child, depth + 1, relative)
                    child.isFile -> results += relative
                }
            }
        }
        root?.let { walk(it, 1, "") }
        return results
    }

    private fun find(path: String): DocumentFile? {
        var directory = root ?: return null
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        for ((index, segment) in segments.withIndex()) {
            val child = directory.findFile(segment) ?: return null
            if (index == segments.lastIndex) return child
            directory = child
        }
        return null
    }

    private fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "json", "json5", "jsonc", "arb" -> "application/json"
        "yaml", "yml" -> "text/yaml"
        "csv" -> "text/csv"
        "ts", "js", "mjs", "cjs" -> "text/javascript"
        "po", "pot", "properties", "strings" -> "text/plain"
        else -> "application/octet-stream"
    }
}
