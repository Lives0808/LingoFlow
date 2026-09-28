package dev.lingoflow.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.lingoflow.app.core.model.MemoryRow
import dev.lingoflow.app.core.model.RuleKind
import dev.lingoflow.app.core.model.RuleRow
import dev.lingoflow.app.core.model.SuggestionRow
import dev.lingoflow.app.core.rules.Learn
import dev.lingoflow.app.core.tm.MemoryStore

/**
 * SQLite backed private translation memory.
 *
 * The database lives in the app's private storage: no sync, no cloud, no
 * telemetry. Approving and freezing an entry is what protects reviewed wording
 * from later automatic runs.
 */
class SqliteMemoryStore(context: Context) : SQLiteOpenHelper(context, "lingoflow-memory.db", null, 1), MemoryStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE memory (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              source_locale TEXT NOT NULL,
              target_locale TEXT NOT NULL,
              source_hash TEXT NOT NULL,
              source TEXT NOT NULL,
              target TEXT NOT NULL,
              engine TEXT NOT NULL,
              confidence REAL NOT NULL DEFAULT 0,
              refs TEXT NOT NULL DEFAULT '',
              approved INTEGER NOT NULL DEFAULT 0,
              frozen INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL DEFAULT 0,
              UNIQUE (source_locale, target_locale, source_hash)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_memory_pair ON memory (source_locale, target_locale)")
        db.execSQL(
            """
            CREATE TABLE rules (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              kind TEXT NOT NULL,
              locale TEXT,
              pattern TEXT NOT NULL,
              value TEXT NOT NULL,
              priority INTEGER NOT NULL DEFAULT 60,
              enabled INTEGER NOT NULL DEFAULT 1,
              origin TEXT NOT NULL DEFAULT 'manual',
              confidence REAL NOT NULL DEFAULT 1,
              note TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE suggestions (
              source_hash TEXT NOT NULL,
              locale TEXT NOT NULL,
              source TEXT NOT NULL,
              machine TEXT NOT NULL,
              engine TEXT NOT NULL,
              created_at INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (source_hash, locale)
            )
            """.trimIndent(),
        )
        seedDefaults(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Local cache only: recreate rather than migrate.
        db.execSQL("DROP TABLE IF EXISTS memory")
        db.execSQL("DROP TABLE IF EXISTS rules")
        db.execSQL("DROP TABLE IF EXISTS suggestions")
        onCreate(db)
    }

    private fun seedDefaults(db: SQLiteDatabase) {
        val starter = listOf(
            Triple("LingoFlow", "LingoFlow", null),
            Triple("Settings", "设置", "zh-CN"),
        )
        starter.forEach { (source, target, locale) ->
            db.insert("rules", null, ContentValues().apply {
                put("kind", RuleKind.GLOSSARY.wire)
                put("locale", locale)
                put("pattern", source)
                put("value", """{"target":${quote(target)}}""")
                put("priority", 60)
                put("enabled", 1)
                put("origin", "seed")
                put("confidence", 1.0)
                put("note", "starter term — review it")
            })
        }
    }

    override fun upsertMany(rows: List<MemoryRow>): Int {
        val db = writableDatabase
        var written = 0
        rows.forEach { row ->
            val values = ContentValues().apply {
                put("source_locale", row.sourceLocale)
                put("target_locale", row.targetLocale)
                put("source_hash", Learn.sourceHash(row.sourceLocale, row.source))
                put("source", row.source)
                put("target", row.target)
                put("engine", row.engine)
                put("confidence", row.confidence)
                put("refs", row.refs.joinToString(","))
                put("approved", if (row.approved) 1 else 0)
                put("frozen", if (row.frozen) 1 else 0)
                put("updated_at", System.currentTimeMillis())
            }
            // Frozen entries are never overwritten.
            val conflict = "source_locale = ? AND target_locale = ? AND source_hash = ?"
            val args = arrayOf(row.sourceLocale, row.targetLocale, Learn.sourceHash(row.sourceLocale, row.source))
            val existing = db.query("memory", arrayOf("id", "frozen", "approved"), conflict, args, null, null, null)
            if (existing.use { it.moveToFirst() }) {
                val frozen = existing.use { if (it.isFirst) it.getInt(1) == 1 else false }
                if (frozen) return@forEach
                db.update("memory", values, conflict, args)
            } else {
                db.insert("memory", null, values)
            }
            written++
        }
        return written
    }

    override fun exact(sourceLocale: String, targetLocale: String, source: String): MemoryRow? {
        val args = arrayOf(sourceLocale, targetLocale, Learn.sourceHash(sourceLocale, source))
        return readableDatabase
            .query("memory", null, "source_locale = ? AND target_locale = ? AND source_hash = ?", args, null, null, null)
            .use { cursor ->
                if (cursor.moveToFirst()) cursor.toMemoryRow() else null
            }
    }

    override fun pairs(sourceLocale: String, targetLocale: String): List<MemoryRow> =
        readableDatabase.query(
            "memory", null, "source_locale = ? AND target_locale = ?",
            arrayOf(sourceLocale, targetLocale), null, null, null,
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toMemoryRow()) }
        }

    override fun list(targetLocale: String?, search: String?): List<MemoryRow> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        targetLocale?.let {
            clauses += "target_locale = ?"
            args += it
        }
        search?.takeIf { it.isNotBlank() }?.let {
            clauses += "(source LIKE ? OR target LIKE ?)"
            args += "%$it%"
            args += "%$it%"
        }
        val where = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        return readableDatabase.query("memory", null, where, args.toTypedArray(), null, null, "updated_at DESC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toMemoryRow()) }
        }
    }

    override fun update(id: Long, patch: Map<String, Any?>) {
        val values = ContentValues().apply {
            (patch["target"] as? String)?.let { put("target", it) }
            (patch["approved"] as? Boolean)?.let { put("approved", if (it) 1 else 0) }
            (patch["frozen"] as? Boolean)?.let { put("frozen", if (it) 1 else 0) }
            put("updated_at", System.currentTimeMillis())
        }
        if (values.size() == 0) return
        writableDatabase.update("memory", values, "id = ?", arrayOf(id.toString()))
    }

    override fun remove(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        return writableDatabase.delete("memory", "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
    }

    override fun stats(): MemoryStore.Stats {
        val db = readableDatabase
        fun count(table: String, where: String? = null): Int =
            db.query(table, arrayOf("COUNT(*)"), where, null, null, null, null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        val byLocale = mutableListOf<MemoryStore.LocaleCount>()
        db.rawQuery(
            "SELECT target_locale, COUNT(*), SUM(approved), SUM(frozen) FROM memory GROUP BY target_locale ORDER BY COUNT(*) DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                byLocale += MemoryStore.LocaleCount(cursor.getString(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3))
            }
        }
        val byEngine = mutableListOf<Pair<String, Int>>()
        db.rawQuery("SELECT engine, COUNT(*) FROM memory GROUP BY engine ORDER BY COUNT(*) DESC", null).use { cursor ->
            while (cursor.moveToNext()) byEngine += cursor.getString(0) to cursor.getInt(1)
        }
        return MemoryStore.Stats(
            entries = count("memory"),
            approved = count("memory", "approved = 1"),
            frozen = count("memory", "frozen = 1"),
            rules = count("rules"),
            suggestions = count("suggestions"),
            byLocale = byLocale,
            byEngine = byEngine,
        )
    }

    override fun addRules(rules: List<RuleRow>): Int {
        val db = writableDatabase
        var added = 0
        rules.forEach { rule ->
            val existing = db.query(
                "rules", arrayOf("id"),
                "kind = ? AND IFNULL(locale, '') = IFNULL(?, '') AND pattern = ? AND value = ?",
                arrayOf(rule.kind.wire, rule.locale ?: "", rule.pattern, rule.value),
                null, null, null,
            ).use { it.count > 0 }
            if (existing) return@forEach
            db.insert("rules", null, ContentValues().apply {
                put("kind", rule.kind.wire)
                put("locale", rule.locale)
                put("pattern", rule.pattern)
                put("value", rule.value)
                put("priority", rule.priority)
                put("enabled", if (rule.enabled) 1 else 0)
                put("origin", rule.origin)
                put("confidence", rule.confidence)
                put("note", rule.note)
            })
            added++
        }
        return added
    }

    override fun listRules(kind: RuleKind?, locale: String?): List<RuleRow> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        kind?.let {
            clauses += "kind = ?"
            args += it.wire
        }
        locale?.let {
            clauses += "(locale IS NULL OR locale = ?)"
            args += it
        }
        val where = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        return readableDatabase.query("rules", null, where, args.toTypedArray(), null, null, "priority DESC, id ASC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RuleRow(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            kind = RuleKind.fromWire(cursor.getString(cursor.getColumnIndexOrThrow("kind"))) ?: RuleKind.GLOSSARY,
                            locale = cursor.getString(cursor.getColumnIndexOrThrow("locale")),
                            pattern = cursor.getString(cursor.getColumnIndexOrThrow("pattern")),
                            value = cursor.getString(cursor.getColumnIndexOrThrow("value")),
                            priority = cursor.getInt(cursor.getColumnIndexOrThrow("priority")),
                            enabled = cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 1,
                            origin = cursor.getString(cursor.getColumnIndexOrThrow("origin")),
                            confidence = cursor.getDouble(cursor.getColumnIndexOrThrow("confidence")),
                            note = cursor.getString(cursor.getColumnIndexOrThrow("note")),
                        ),
                    )
                }
            }
        }
    }

    override fun updateRule(id: Long, patch: Map<String, Any?>) {
        val values = ContentValues().apply {
            (patch["enabled"] as? Boolean)?.let { put("enabled", if (it) 1 else 0) }
            (patch["value"] as? String)?.let { put("value", it) }
            (patch["note"] as? String)?.let { put("note", it) }
        }
        if (values.size() == 0) return
        writableDatabase.update("rules", values, "id = ?", arrayOf(id.toString()))
    }

    override fun removeRules(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        return writableDatabase.delete("rules", "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
    }

    override fun addSuggestions(rows: List<SuggestionRow>) {
        val db = writableDatabase
        rows.forEach { row ->
            db.insertWithOnConflict(
                "suggestions",
                null,
                ContentValues().apply {
                    put("source_hash", row.sourceHash)
                    put("locale", row.locale)
                    put("source", row.source)
                    put("machine", row.machine)
                    put("engine", row.engine)
                    put("created_at", row.createdAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    override fun listSuggestions(locale: String?): List<SuggestionRow> {
        val where = locale?.let { "locale = ?" }
        val args = locale?.let { arrayOf(it) }
        return readableDatabase.query("suggestions", null, where, args, null, null, "created_at DESC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SuggestionRow(
                            sourceHash = cursor.getString(cursor.getColumnIndexOrThrow("source_hash")),
                            source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
                            locale = cursor.getString(cursor.getColumnIndexOrThrow("locale")),
                            machine = cursor.getString(cursor.getColumnIndexOrThrow("machine")),
                            engine = cursor.getString(cursor.getColumnIndexOrThrow("engine")),
                            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                        ),
                    )
                }
            }
        }
    }

    private fun android.database.Cursor.toMemoryRow(): MemoryRow = MemoryRow(
        id = getLong(getColumnIndexOrThrow("id")),
        sourceLocale = getString(getColumnIndexOrThrow("source_locale")),
        targetLocale = getString(getColumnIndexOrThrow("target_locale")),
        source = getString(getColumnIndexOrThrow("source")),
        target = getString(getColumnIndexOrThrow("target")),
        engine = getString(getColumnIndexOrThrow("engine")),
        confidence = getDouble(getColumnIndexOrThrow("confidence")),
        refs = getString(getColumnIndexOrThrow("refs"))?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
        approved = getInt(getColumnIndexOrThrow("approved")) == 1,
        frozen = getInt(getColumnIndexOrThrow("frozen")) == 1,
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
