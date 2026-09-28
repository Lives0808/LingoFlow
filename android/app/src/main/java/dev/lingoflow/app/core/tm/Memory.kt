package dev.lingoflow.app.core.tm

import dev.lingoflow.app.core.model.MemoryRow
import dev.lingoflow.app.core.model.RuleKind
import dev.lingoflow.app.core.model.RuleRow
import dev.lingoflow.app.core.model.SuggestionRow
import dev.lingoflow.app.core.rules.Learn
import kotlin.math.max
import kotlin.math.min

/** Storage contract for the private translation memory and rule base. */
interface MemoryStore {
    fun upsertMany(rows: List<MemoryRow>): Int
    fun exact(sourceLocale: String, targetLocale: String, source: String): MemoryRow?
    fun pairs(sourceLocale: String, targetLocale: String): List<MemoryRow>
    fun list(targetLocale: String? = null, search: String? = null): List<MemoryRow>
    fun update(id: Long, patch: Map<String, Any?>)
    fun remove(ids: List<Long>): Int
    fun stats(): Stats

    fun addRules(rules: List<RuleRow>): Int
    fun listRules(kind: RuleKind? = null, locale: String? = null): List<RuleRow>
    fun updateRule(id: Long, patch: Map<String, Any?>)
    fun removeRules(ids: List<Long>): Int

    fun addSuggestions(rows: List<SuggestionRow>)
    fun listSuggestions(locale: String? = null): List<SuggestionRow>

    data class LocaleCount(val locale: String, val entries: Int, val approved: Int, val frozen: Int)

    data class Stats(
        val entries: Int = 0,
        val approved: Int = 0,
        val frozen: Int = 0,
        val rules: Int = 0,
        val suggestions: Int = 0,
        val byLocale: List<LocaleCount> = emptyList(),
        val byEngine: List<Pair<String, Int>> = emptyList(),
    )
}

/** In-memory implementation: used by unit tests and as the app's scratch store. */
class InMemoryMemoryStore : MemoryStore {
    private val entries = mutableListOf<MemoryRow>()
    private val rules = mutableListOf<RuleRow>()
    private val suggestions = mutableListOf<SuggestionRow>()
    private var nextId = 1L

    override fun upsertMany(rows: List<MemoryRow>): Int {
        var written = 0
        for (row in rows) {
            val hash = row.sourceHash()
            val existing = entries.firstOrNull {
                it.sourceLocale == row.sourceLocale && it.targetLocale == row.targetLocale && it.sourceHash() == hash
            }
            if (existing != null) {
                if (existing.frozen) continue
                val index = entries.indexOf(existing)
                entries[index] = existing.copy(
                    target = row.target,
                    engine = row.engine,
                    confidence = row.confidence,
                    approved = existing.approved || row.approved,
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                entries += row.copy(id = nextId++, updatedAt = System.currentTimeMillis())
            }
            written++
        }
        return written
    }

    override fun exact(sourceLocale: String, targetLocale: String, source: String): MemoryRow? {
        val hash = Learn.sourceHash(sourceLocale, source)
        return entries.firstOrNull {
            it.sourceLocale == sourceLocale && it.targetLocale == targetLocale && it.sourceHash() == hash
        }
    }

    override fun pairs(sourceLocale: String, targetLocale: String): List<MemoryRow> =
        entries.filter { it.sourceLocale == sourceLocale && it.targetLocale == targetLocale }

    override fun list(targetLocale: String?, search: String?): List<MemoryRow> = entries.filter { row ->
        (targetLocale == null || row.targetLocale == targetLocale) &&
            (search.isNullOrBlank() || row.source.contains(search, true) || row.target.contains(search, true))
    }

    override fun update(id: Long, patch: Map<String, Any?>) {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return
        val row = entries[index]
        entries[index] = row.copy(
            target = patch["target"] as? String ?: row.target,
            approved = patch["approved"] as? Boolean ?: row.approved,
            frozen = patch["frozen"] as? Boolean ?: row.frozen,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override fun remove(ids: List<Long>): Int {
        val before = entries.size
        entries.removeAll { it.id in ids }
        return before - entries.size
    }

    override fun stats(): MemoryStore.Stats = MemoryStore.Stats(
        entries = entries.size,
        approved = entries.count { it.approved },
        frozen = entries.count { it.frozen },
        rules = rules.size,
        suggestions = suggestions.size,
        byLocale = entries.groupBy { it.targetLocale }.map { (locale, rows) ->
            MemoryStore.LocaleCount(locale, rows.size, rows.count { it.approved }, rows.count { it.frozen })
        }.sortedByDescending { it.entries },
        byEngine = entries.groupBy { it.engine }.map { it.key to it.value.size }.sortedByDescending { it.second },
    )

    override fun addRules(rules: List<RuleRow>): Int {
        var added = 0
        for (rule in rules) {
            val duplicate = this.rules.firstOrNull {
                it.kind == rule.kind && it.locale == rule.locale && it.pattern == rule.pattern && it.value == rule.value
            }
            if (duplicate != null) continue
            this.rules += rule.copy(id = nextId++)
            added++
        }
        return added
    }

    override fun listRules(kind: RuleKind?, locale: String?): List<RuleRow> = rules
        .filter { (kind == null || it.kind == kind) && (locale == null || it.locale == null || it.locale == locale) }
        .sortedByDescending { it.priority }

    override fun updateRule(id: Long, patch: Map<String, Any?>) {
        val index = rules.indexOfFirst { it.id == id }
        if (index < 0) return
        val rule = rules[index]
        rules[index] = rule.copy(
            enabled = patch["enabled"] as? Boolean ?: rule.enabled,
            value = patch["value"] as? String ?: rule.value,
            note = patch["note"] as? String ?: rule.note,
        )
    }

    override fun removeRules(ids: List<Long>): Int {
        val before = rules.size
        rules.removeAll { it.id in ids }
        return before - rules.size
    }

    override fun addSuggestions(rows: List<SuggestionRow>) {
        for (row in rows) {
            suggestions.removeAll { it.sourceHash == row.sourceHash && it.locale == row.locale }
            suggestions += row
        }
    }

    override fun listSuggestions(locale: String?): List<SuggestionRow> =
        suggestions.filter { locale == null || it.locale == locale }
}

/** Fuzzy matching used before calling a translation engine. */
object Fuzzy {

    data class Match(val row: MemoryRow, val score: Double)

    fun best(source: String, candidates: List<MemoryRow>, threshold: Double): Match? {
        var best: Match? = null
        for (row in candidates) {
            if (row.target.isBlank() || row.target == source) continue
            val score = score(source, row.source)
            if (score < threshold) continue
            if (best == null || score > best.score) best = Match(row, score)
        }
        return best
    }

    fun score(a: String, b: String): Double {
        val left = a.lowercase().trim()
        val right = b.lowercase().trim()
        if (left.isEmpty() && right.isEmpty()) return 1.0
        val distance = levenshtein(left, right)
        val edit = 1.0 - distance.toDouble() / max(left.length, right.length).coerceAtLeast(1)
        val jaccard = jaccard(tokens(left), tokens(right))
        return max(edit, edit * 0.6 + jaccard * 0.4)
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun tokens(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        return intersection.toDouble() / (a.size + b.size - intersection)
    }
}

private fun MemoryRow.sourceHash(): String = Learn.sourceHash(sourceLocale, source)
