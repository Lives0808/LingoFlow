package dev.lingoflow.shared.core.project

import dev.lingoflow.shared.core.project.ProjectSource

/** In-memory project source: used by tests and by dry-run previews. */
class MapFileSource(
    private val files: MutableMap<String, String> = mutableMapOf(),
    override val name: String = "in-memory",
) : ProjectSource {
    override val id: String = "memory://$name"

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun read(path: String): String? = files[path]

    override fun write(path: String, content: String): Boolean {
        files[path] = content
        return true
    }

    override fun delete(path: String): Boolean = files.remove(path) != null

    override fun listFiles(maxDepth: Int): List<String> = files.keys.toList()

    fun contents(): Map<String, String> = files.toMap()

    companion object {
        fun of(files: Map<String, String>, name: String = "in-memory"): MapFileSource =
            MapFileSource(files.toMutableMap(), name)
    }
}
