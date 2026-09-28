import path from 'node:path';
import type { DatabaseSync as DatabaseSyncType } from 'node:sqlite';
import type { MemoryRow, RuleKind, RuleRow, RunSummary, SuggestionRow } from '../types';
import { ensureDir } from '../utils/fsx';
import { sha256 } from '../utils/hash';
import type { MemoryQuery, MemoryStats, MemoryStore, RuleQuery } from './store';

type SqlValue = string | number | null;

/**
 * SQLite driver (`node:sqlite`, Node >= 22.5). Private by design: the database
 * lives inside the project (`.lingoflow/memory/lingoflow.db`) and is never synced.
 */
export class SqliteMemoryStore implements MemoryStore {
  readonly driver = 'sqlite' as const;
  readonly location: string;
  private db: DatabaseSyncType;
  private cacheSize = 0;

  private constructor(db: DatabaseSyncType, file: string) {
    this.db = db;
    this.location = file;
  }

  static async open(file: string): Promise<SqliteMemoryStore> {
    await ensureDir(path.dirname(file));
    const { DatabaseSync } = await import('node:sqlite');
    const db = new DatabaseSync(file);
    const store = new SqliteMemoryStore(db, file);
    store.migrate();
    return store;
  }

  /** True when this process can use the SQLite driver at all. */
  static async isAvailable(): Promise<boolean> {
    try {
      await import('node:sqlite');
      return true;
    } catch {
      return false;
    }
  }

  private migrate(): void {
    this.db.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA synchronous = NORMAL;
      CREATE TABLE IF NOT EXISTS memory (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        source_locale TEXT NOT NULL,
        target_locale TEXT NOT NULL,
        source_hash TEXT NOT NULL,
        source TEXT NOT NULL,
        target TEXT NOT NULL,
        engine TEXT NOT NULL,
        confidence REAL NOT NULL DEFAULT 0,
        refs TEXT NOT NULL DEFAULT '[]',
        approved INTEGER NOT NULL DEFAULT 0,
        frozen INTEGER NOT NULL DEFAULT 0,
        notes TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        UNIQUE (source_locale, target_locale, source_hash)
      );
      CREATE INDEX IF NOT EXISTS idx_memory_pair ON memory (source_locale, target_locale);
      CREATE INDEX IF NOT EXISTS idx_memory_engine ON memory (engine);
      CREATE TABLE IF NOT EXISTS rules (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        kind TEXT NOT NULL,
        locale TEXT,
        pattern TEXT NOT NULL,
        value TEXT NOT NULL,
        priority INTEGER NOT NULL DEFAULT 50,
        enabled INTEGER NOT NULL DEFAULT 1,
        origin TEXT NOT NULL DEFAULT 'manual',
        confidence REAL NOT NULL DEFAULT 1,
        note TEXT,
        created_at TEXT NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_rules_kind ON rules (kind, locale);
      CREATE TABLE IF NOT EXISTS cache (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL,
        engine TEXT NOT NULL,
        created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS suggestions (
        source_hash TEXT NOT NULL,
        source TEXT NOT NULL,
        locale TEXT NOT NULL,
        machine TEXT NOT NULL,
        engine TEXT NOT NULL,
        refs TEXT NOT NULL DEFAULT '[]',
        created_at TEXT NOT NULL,
        PRIMARY KEY (source_hash, locale)
      );
      CREATE TABLE IF NOT EXISTS runs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        command TEXT NOT NULL,
        started_at TEXT NOT NULL,
        finished_at TEXT NOT NULL,
        engine TEXT NOT NULL,
        locales TEXT NOT NULL,
        translated INTEGER NOT NULL DEFAULT 0,
        fixed INTEGER NOT NULL DEFAULT 0,
        issues INTEGER NOT NULL DEFAULT 0,
        errors INTEGER NOT NULL DEFAULT 0,
        warnings INTEGER NOT NULL DEFAULT 0,
        files TEXT NOT NULL DEFAULT '[]',
        duration_ms INTEGER NOT NULL DEFAULT 0,
        meta TEXT
      );
    `);
    const cached = this.db.prepare('SELECT COUNT(*) AS count FROM cache').get() as { count: number } | undefined;
    this.cacheSize = Number(cached?.count ?? 0);
  }

  async init(): Promise<void> {
    // Migrations run in the constructor/open path.
  }

  async close(): Promise<void> {
    this.db.close();
  }

  async upsertMany(rows: MemoryRow[]): Promise<number> {
    const now = new Date().toISOString();
    const statement = this.db.prepare(`
      INSERT INTO memory (source_locale, target_locale, source_hash, source, target, engine, confidence, refs, approved, frozen, notes, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (source_locale, target_locale, source_hash) DO UPDATE SET
        target = CASE WHEN memory.frozen = 1 THEN memory.target ELSE excluded.target END,
        engine = CASE WHEN memory.frozen = 1 THEN memory.engine ELSE excluded.engine END,
        confidence = CASE WHEN memory.frozen = 1 THEN memory.confidence ELSE excluded.confidence END,
        refs = excluded.refs,
        approved = CASE WHEN excluded.approved = 1 THEN 1 ELSE memory.approved END,
        notes = COALESCE(excluded.notes, memory.notes),
        updated_at = excluded.updated_at
    `);
    let written = 0;
    for (const row of rows) {
      const sourceHash = row.sourceHash || sha256(`${row.sourceLocale}\u0000${row.source}`);
      statement.run(
        row.sourceLocale,
        row.targetLocale,
        sourceHash,
        row.source,
        row.target,
        row.engine,
        row.confidence,
        JSON.stringify(row.refs ?? []),
        row.approved ? 1 : 0,
        row.frozen ? 1 : 0,
        row.notes ?? null,
        row.createdAt ?? now,
        now,
      );
      written += 1;
    }
    return written;
  }

  async exact(sourceLocale: string, targetLocale: string, source: string): Promise<MemoryRow | null> {
    const hash = sha256(`${sourceLocale}\u0000${source}`);
    const row = this.db
      .prepare('SELECT * FROM memory WHERE source_locale = ? AND target_locale = ? AND source_hash = ?')
      .get(sourceLocale, targetLocale, hash) as Record<string, SqlValue> | undefined;
    return row ? sqliteRowToMemory(row) : null;
  }

  async pairs(sourceLocale: string, targetLocale: string): Promise<MemoryRow[]> {
    const rows = this.db
      .prepare('SELECT * FROM memory WHERE source_locale = ? AND target_locale = ?')
      .all(sourceLocale, targetLocale) as Array<Record<string, SqlValue>>;
    return rows.map(sqliteRowToMemory);
  }

  async list(query: MemoryQuery = {}): Promise<MemoryRow[]> {
    const clauses: string[] = [];
    const params: SqlValue[] = [];
    if (query.sourceLocale) {
      clauses.push('source_locale = ?');
      params.push(query.sourceLocale);
    }
    if (query.targetLocale) {
      clauses.push('target_locale = ?');
      params.push(query.targetLocale);
    }
    if (query.engine) {
      clauses.push('engine = ?');
      params.push(query.engine);
    }
    if (query.approved !== undefined) {
      clauses.push('approved = ?');
      params.push(query.approved ? 1 : 0);
    }
    if (query.frozen !== undefined) {
      clauses.push('frozen = ?');
      params.push(query.frozen ? 1 : 0);
    }
    if (query.search) {
      clauses.push('(source LIKE ? OR target LIKE ?)');
      params.push(`%${query.search}%`, `%${query.search}%`);
    }
    const where = clauses.length > 0 ? `WHERE ${clauses.join(' AND ')}` : '';
    const limit = query.limit ? 'LIMIT ? OFFSET ?' : '';
    if (query.limit) params.push(query.limit, query.offset ?? 0);
    const rows = this.db.prepare(`SELECT * FROM memory ${where} ORDER BY updated_at DESC ${limit}`).all(...params) as Array<
      Record<string, SqlValue>
    >;
    return rows.map(sqliteRowToMemory);
  }

  async update(id: number, patch: Partial<MemoryRow>): Promise<void> {
    const sets: string[] = [];
    const params: SqlValue[] = [];
    const push = (column: string, value: SqlValue): void => {
      sets.push(`${column} = ?`);
      params.push(value);
    };
    if (patch.target !== undefined) push('target', patch.target);
    if (patch.engine !== undefined) push('engine', patch.engine);
    if (patch.confidence !== undefined) push('confidence', patch.confidence);
    if (patch.approved !== undefined) push('approved', patch.approved ? 1 : 0);
    if (patch.frozen !== undefined) push('frozen', patch.frozen ? 1 : 0);
    if (patch.notes !== undefined) push('notes', patch.notes);
    if (patch.refs !== undefined) push('refs', JSON.stringify(patch.refs));
    if (sets.length === 0) return;
    push('updated_at', new Date().toISOString());
    params.push(id);
    this.db.prepare(`UPDATE memory SET ${sets.join(', ')} WHERE id = ?`).run(...params);
  }

  async remove(ids: number[]): Promise<number> {
    if (ids.length === 0) return 0;
    const placeholders = ids.map(() => '?').join(', ');
    const statement = this.db.prepare(`DELETE FROM memory WHERE id IN (${placeholders})`);
    const result = statement.run(...(ids as number[]));
    return Number(result.changes ?? 0);
  }

  async all(): Promise<MemoryRow[]> {
    const rows = this.db.prepare('SELECT * FROM memory').all() as Array<Record<string, SqlValue>>;
    return rows.map(sqliteRowToMemory);
  }

  async stats(): Promise<MemoryStats> {
    const total = Number((this.db.prepare('SELECT COUNT(*) AS c FROM memory').get() as { c: number }).c);
    const approved = Number((this.db.prepare('SELECT COUNT(*) AS c FROM memory WHERE approved = 1').get() as { c: number }).c);
    const frozen = Number((this.db.prepare('SELECT COUNT(*) AS c FROM memory WHERE frozen = 1').get() as { c: number }).c);
    const rules = Number((this.db.prepare('SELECT COUNT(*) AS c FROM rules').get() as { c: number }).c);
    const suggestions = Number((this.db.prepare('SELECT COUNT(*) AS c FROM suggestions').get() as { c: number }).c);
    const runs = Number((this.db.prepare('SELECT COUNT(*) AS c FROM runs').get() as { c: number }).c);
    const byLocaleRows = this.db
      .prepare(
        `SELECT target_locale AS locale, COUNT(*) AS entries,
                SUM(approved) AS approved, SUM(frozen) AS frozen
         FROM memory GROUP BY target_locale ORDER BY entries DESC`,
      )
      .all() as Array<{ locale: string; entries: number; approved: number | null; frozen: number | null }>;
    const byEngineRows = this.db
      .prepare('SELECT engine, COUNT(*) AS entries FROM memory GROUP BY engine ORDER BY entries DESC')
      .all() as Array<{ engine: string; entries: number }>;
    return {
      driver: this.driver,
      location: this.location,
      entries: total,
      approved,
      frozen,
      rules,
      suggestions,
      runs,
      cache: this.cacheSize,
      byLocale: byLocaleRows.map((row) => ({
        locale: row.locale,
        entries: Number(row.entries),
        approved: Number(row.approved ?? 0),
        frozen: Number(row.frozen ?? 0),
      })),
      byEngine: byEngineRows.map((row) => ({ engine: row.engine, entries: Number(row.entries) })),
    };
  }

  async addRules(rules: RuleRow[]): Promise<number> {
    const statement = this.db.prepare(`
      INSERT INTO rules (kind, locale, pattern, value, priority, enabled, origin, confidence, note, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT DO NOTHING
    `);
    let added = 0;
    for (const rule of rules) {
      const existing = this.db
        .prepare(
          "SELECT id, confidence FROM rules WHERE kind = ? AND IFNULL(locale, '') = IFNULL(?, '') AND pattern = ? AND value = ?",
        )
        .get(rule.kind, rule.locale ?? null, rule.pattern, rule.value) as { id: number; confidence: number } | undefined;
      if (existing) {
        if (rule.confidence > Number(existing.confidence)) {
          this.db.prepare('UPDATE rules SET confidence = ? WHERE id = ?').run(rule.confidence, existing.id);
        }
        continue;
      }
      statement.run(
        rule.kind,
        rule.locale ?? null,
        rule.pattern,
        rule.value,
        rule.priority,
        rule.enabled ? 1 : 0,
        rule.origin,
        rule.confidence,
        rule.note ?? null,
        rule.createdAt ?? new Date().toISOString(),
      );
      added += 1;
    }
    return added;
  }

  async listRules(query: RuleQuery = {}): Promise<RuleRow[]> {
    const clauses: string[] = [];
    const params: SqlValue[] = [];
    if (query.kind) {
      clauses.push('kind = ?');
      params.push(query.kind);
    }
    if (query.locale !== undefined) {
      clauses.push("IFNULL(locale, '') = IFNULL(?, '')");
      params.push(query.locale ?? null);
    }
    if (query.enabled !== undefined) {
      clauses.push('enabled = ?');
      params.push(query.enabled ? 1 : 0);
    }
    if (query.search) {
      clauses.push('(pattern LIKE ? OR value LIKE ? OR note LIKE ?)');
      params.push(`%${query.search}%`, `%${query.search}%`, `%${query.search}%`);
    }
    const where = clauses.length > 0 ? `WHERE ${clauses.join(' AND ')}` : '';
    const limit = query.limit ? 'LIMIT ? OFFSET ?' : '';
    if (query.limit) params.push(query.limit, query.offset ?? 0);
    const rows = this.db
      .prepare(`SELECT * FROM rules ${where} ORDER BY priority DESC, id ASC ${limit}`)
      .all(...params) as Array<Record<string, SqlValue>>;
    return rows.map((row) => ({
      id: Number(row.id),
      kind: String(row.kind) as RuleKind,
      locale: row.locale === null ? null : String(row.locale),
      pattern: String(row.pattern),
      value: String(row.value),
      priority: Number(row.priority),
      enabled: Number(row.enabled) === 1,
      origin: String(row.origin),
      confidence: Number(row.confidence),
      note: row.note === null ? undefined : String(row.note),
      createdAt: String(row.created_at),
    }));
  }

  async updateRule(id: number, patch: Partial<RuleRow>): Promise<void> {
    const sets: string[] = [];
    const params: SqlValue[] = [];
    if (patch.pattern !== undefined) {
      sets.push('pattern = ?');
      params.push(patch.pattern);
    }
    if (patch.value !== undefined) {
      sets.push('value = ?');
      params.push(patch.value);
    }
    if (patch.priority !== undefined) {
      sets.push('priority = ?');
      params.push(patch.priority);
    }
    if (patch.enabled !== undefined) {
      sets.push('enabled = ?');
      params.push(patch.enabled ? 1 : 0);
    }
    if (patch.locale !== undefined) {
      sets.push('locale = ?');
      params.push(patch.locale ?? null);
    }
    if (patch.confidence !== undefined) {
      sets.push('confidence = ?');
      params.push(patch.confidence);
    }
    if (patch.note !== undefined) {
      sets.push('note = ?');
      params.push(patch.note);
    }
    if (sets.length === 0) return;
    params.push(id);
    this.db.prepare(`UPDATE rules SET ${sets.join(', ')} WHERE id = ?`).run(...params);
  }

  async removeRules(ids: number[]): Promise<number> {
    if (ids.length === 0) return 0;
    const placeholders = ids.map(() => '?').join(', ');
    const result = this.db.prepare(`DELETE FROM rules WHERE id IN (${placeholders})`).run(...(ids as number[]));
    return Number(result.changes ?? 0);
  }

  async cacheGet(key: string): Promise<{ value: string; engine: string } | null> {
    const row = this.db.prepare('SELECT value, engine FROM cache WHERE key = ?').get(key) as
      | { value: string; engine: string }
      | undefined;
    return row ?? null;
  }

  async cacheSet(key: string, value: string, engine: string): Promise<void> {
    this.db
      .prepare(
        `INSERT INTO cache (key, value, engine, created_at) VALUES (?, ?, ?, ?)
         ON CONFLICT (key) DO UPDATE SET value = excluded.value, engine = excluded.engine, created_at = excluded.created_at`,
      )
      .run(key, value, engine, new Date().toISOString());
    this.cacheSize += 1;
  }

  async cacheClear(): Promise<number> {
    const result = this.db.prepare('DELETE FROM cache').run();
    const removed = Number(result.changes ?? 0);
    this.cacheSize = 0;
    return removed;
  }

  async addSuggestions(rows: SuggestionRow[]): Promise<void> {
    const statement = this.db.prepare(`
      INSERT INTO suggestions (source_hash, source, locale, machine, engine, refs, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (source_hash, locale) DO UPDATE SET
        machine = excluded.machine, engine = excluded.engine, refs = excluded.refs, created_at = excluded.created_at
    `);
    for (const row of rows) {
      statement.run(
        row.sourceHash,
        row.source,
        row.locale,
        row.machine,
        row.engine,
        JSON.stringify(row.refs ?? []),
        row.createdAt,
      );
    }
  }

  async listSuggestions(query: { locale?: string; limit?: number } = {}): Promise<SuggestionRow[]> {
    const where = query.locale ? 'WHERE locale = ?' : '';
    const params: SqlValue[] = query.locale ? [query.locale] : [];
    const limit = query.limit ? 'LIMIT ?' : '';
    if (query.limit) params.push(query.limit);
    const rows = this.db
      .prepare(`SELECT * FROM suggestions ${where} ORDER BY created_at DESC ${limit}`)
      .all(...params) as Array<Record<string, SqlValue>>;
    return rows.map((row) => ({
      sourceHash: String(row.source_hash),
      source: String(row.source),
      locale: String(row.locale),
      machine: String(row.machine),
      engine: String(row.engine),
      refs: JSON.parse(String(row.refs ?? '[]')) as string[],
      createdAt: String(row.created_at),
    }));
  }

  async addRun(run: RunSummary): Promise<void> {
    this.db
      .prepare(
        `INSERT INTO runs (command, started_at, finished_at, engine, locales, translated, fixed, issues, errors, warnings, files, duration_ms, meta)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        run.command,
        run.startedAt,
        run.finishedAt,
        run.engine,
        JSON.stringify(run.locales),
        run.translated,
        run.fixed,
        run.issues,
        run.errors,
        run.warnings,
        JSON.stringify(run.filesWritten),
        run.durationMs,
        run.meta ? JSON.stringify(run.meta) : null,
      );
  }

  async listRuns(limit = 20): Promise<RunSummary[]> {
    const rows = this.db.prepare('SELECT * FROM runs ORDER BY id DESC LIMIT ?').all(limit) as Array<Record<string, SqlValue>>;
    return rows.map((row) => ({
      id: Number(row.id),
      command: String(row.command),
      startedAt: String(row.started_at),
      finishedAt: String(row.finished_at),
      engine: String(row.engine),
      locales: JSON.parse(String(row.locales ?? '[]')) as string[],
      translated: Number(row.translated),
      fixed: Number(row.fixed),
      issues: Number(row.issues),
      errors: Number(row.errors),
      warnings: Number(row.warnings),
      filesWritten: JSON.parse(String(row.files ?? '[]')) as string[],
      durationMs: Number(row.duration_ms),
      meta: row.meta ? (JSON.parse(String(row.meta)) as Record<string, unknown>) : undefined,
    }));
  }
}

function sqliteRowToMemory(row: Record<string, SqlValue>): MemoryRow {
  return {
    id: Number(row.id),
    sourceLocale: String(row.source_locale),
    targetLocale: String(row.target_locale),
    sourceHash: String(row.source_hash),
    source: String(row.source),
    target: String(row.target),
    engine: String(row.engine),
    confidence: Number(row.confidence),
    refs: JSON.parse(String(row.refs ?? '[]')) as string[],
    approved: Number(row.approved) === 1,
    frozen: Number(row.frozen) === 1,
    notes: row.notes === null ? undefined : String(row.notes),
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
  };
}
