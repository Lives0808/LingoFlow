package dev.lingoflow.shared.core.catalog

/** Key flattening / unflattening and glob matching, shared by every format. */
object Keys {

    /** Gettext context separator, kept identical to the CLI. */
    const val CONTEXT_SEPARATOR: Char = '\u0004'

    fun display(key: String): String = key.replace(CONTEXT_SEPARATOR, '|')

    fun flatten(
        value: Any?,
        separator: String = ".",
        nesting: Boolean = true,
        prefix: String = "",
        sink: MutableList<Pair<String, Any?>> = mutableListOf(),
    ): List<Pair<String, Any?>> {
        if (!nesting) {
            if (value is Map<*, *>) {
                value.forEach { (key, item) -> sink += key.toString() to item }
            }
            return sink
        }
        when (value) {
            is Map<*, *> -> value.forEach { (rawKey, item) ->
                val key = rawKey.toString()
                val childKey = if (prefix.isEmpty()) key else "$prefix$separator$key"
                when (item) {
                    is Map<*, *>, is List<*> -> flatten(item, separator, nesting, childKey, sink)
                    else -> sink += childKey to item
                }
            }
            is List<*> -> value.forEachIndexed { index, item ->
                val childKey = if (prefix.isEmpty()) index.toString() else "$prefix$separator$index"
                when (item) {
                    is Map<*, *>, is List<*> -> flatten(item, separator, nesting, childKey, sink)
                    else -> sink += childKey to item
                }
            }
            else -> if (prefix.isNotEmpty()) sink += prefix to value
        }
        return sink
    }

    fun unflatten(
        entries: List<Pair<String, Any?>>,
        separator: String = ".",
        nesting: Boolean = true,
    ): LinkedHashMap<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        if (!nesting) {
            entries.forEach { (key, value) -> result[key] = value }
            return result
        }
        for ((key, value) in entries) {
            val segments = key.split(separator)
            var cursor: MutableMap<String, Any?> = result
            segments.forEachIndexed { index, segment ->
                val isLast = index == segments.lastIndex
                val nextIsIndex = !isLast && segments[index + 1].toIntOrNull() != null
                if (isLast) {
                    cursor[segment] = value
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val existing = cursor[segment]
                    val child: MutableMap<String, Any?> = when (existing) {
                        is MutableMap<*, *> -> existing as MutableMap<String, Any?>
                        else -> LinkedHashMap<String, Any?>().also { cursor[segment] = it }
                    }
                    cursor = child
                    if (nextIsIndex) {
                        // Numeric segments stay as map keys; writers render them as objects
                        // which every supported consumer accepts.
                        cursor = child
                    }
                }
            }
        }
        return result
    }

    /** Glob matcher for length rules / ignore lists: `cta.*`, `*.tooltip`, `*`. */
    fun matches(pattern: String, key: String): Boolean {
        if (pattern == "*" || pattern == "**") return true
        val regex = buildString {
            append('^')
            var index = 0
            while (index < pattern.length) {
                val char = pattern[index]
                when {
                    char == '*' && index + 1 < pattern.length && pattern[index + 1] == '*' -> {
                        append(".*")
                        index += 2
                        continue
                    }
                    char == '*' -> append("[^.]*")
                    char == '?' -> append('.')
                    char in ".+^\${}()|[]\\" -> {
                        append('\\')
                        append(char)
                    }
                    else -> append(char)
                }
                index++
            }
            append('$')
        }
        return Regex(regex).matches(key)
    }

    /** Value lookup that also accepts numbers/booleans from tolerant JSON. */
    fun asString(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Number -> if (value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Boolean -> value.toString()
        else -> null
    }
}
