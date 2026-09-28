import { promises as fs } from 'node:fs';
import path from 'node:path';
import type { MemoryRow, RuleRow, RunSummary, SuggestionRow } from '../types';
import { ensureDir, readTextIfExists } from '../utils/fsx';
import { sha256 } from '../utils/hash';
import type { MemoryQuery, MemoryStats, MemoryStore, RuleQuery } from './store';

/**
 * JSONL driver: one append-mostly file per table. Used when `node:sqlite` is
 * unavailable (Node < 22.5) or when the user prefers plain text diffs.
 */
export class JsonlMemoryStore implements MemoryStore {
  readonly driver = 'jsonl' as const;
  readonly location: string;
  private dir: string;
  private memory: MemoryRow[] = [];
  private rules: RuleRow[] = [];
  private cache = new Map<string, { value: string; engine: string }>();
  private suggestions: SuggestionRow[] = [];
  private runs: RunSummary[] = [];
  private nextId = 1;
  private loaded = false;

  constructor(dir: string) {
    this.dir = dir;
    this.location = path.join(dir, 'memory.jsonl');
  }

  async init(): Promise<void> {
    if (this.loaded) return;
    await ensureDir(this.dir);
    this.memory = await this.readTable<MemoryRow>('memory.jsonl');
    this.rules = await this.readTable<RuleRow>('rules.jsonl');
    this.suggestions = await this.readTable<SuggestionRow>('suggestions.jsonl');
    this.runs = await this.readTable<RunSummary>('runs.jsonl');
    const cacheRows = await this.readTable<{ key: string; value: string; engine: string }>('cache.jsonl');
    for (const row of cacheRows) this.cache.set(row.key, { value: row.value, engine: row.engine });
    this.nextId = this.memory.reduce((max, row) => Math.max(max, row.id ?? 0), 0) + 1;
    this.loaded = true;
  }

  async close(): Promise<void> {
    // Nothing to flush: every mutation writes through.
  }

  private async readTable<T>(file: string): Promise<T[]> {
    const text = await readTextIfExists(path.join(this.dir, file));
    if (!text) return [];
    const rows: T[] = [];
    for (const line of text.split('\n')) {
      const trimmed = line.trim();
      if (trimmed.length === 0) continue;
      try {
        rows.push(JSON.parse(trimmed) as T);
      } catch {
        // Skip corrupt lines rather than failing the whole run.
      }
    }
    return rows;
  }

  private async writeTable(file: string, rows: unknown[]): Promise<void> {
    const body = rows.map((row) => JSON.stringify(row)).join('\n');
    const target = path.join(this.dir, file);
    const temporary = `${target}.tmp`;
    await fs.writeFile(temporary, body.length > 0 ? `${body}\n` : '', 'utf8');
    await fs.rename(temporary, target);
  }

  async upsertMany(rows: MemoryRow[]): Promise<number> {
    await this.init();
    let written = 0;
    for (const row of rows) {
      const sourceHash = row.sourceHash || sha256(`${row.sourceLocale}\u0000${row.source}`);
      const existing = this.memory.find(
        (candidate) =>
          candidate.sourceLocale === row.sourceLocale &&
          candidate.targetLocale === row.targetLocale &&
          candidate.sourceHash === sourceHash,
      );
      const now = new Date().toISOString();
      if (existing) {
        if (existing.frozen) continue;
        existing.target = row.target;
        existing.engine = row.engine;
        existing.confidence = row.confidence;
        existing.refs = mergeRefs(existing.refs, row.refs);
        existing.updatedAt = now;
        if (row.approved) existing.approved = true;
        if (row.notes) existing.notes = row.notes;
      } else {
        this.memory.push({
          ...row,
          id: this.nextId++,
          sourceHash,
          refs: row.refs ?? [],
          createdAt: now,
          updatedAt: now,
        });
      }
      written += 1;
    }
    await this.writeTable('memory.jsonl', this.memory);
    return written;
  }

  async exact(sourceLocale: string, targetLocale: string, source: string): Promise<MemoryRow | null> {
    await this.init();
    const hash = sha256(`${sourceLocale}\u0000${source}`);
    return (
      this.memory.find(
        (row) => row.sourceLocale === sourceLocale && row.targetLocale === targetLocale && row.sourceHash === hash,
      ) ?? null
    );
  }

  async pairs(sourceLocale: string, targetLocale: string): Promise<MemoryRow[]> {
    await this.init();
    return this.memory.filter((row) => row.sourceLocale === sourceLocale && row.targetLocale === targetLocale);
  }

  async list(query: MemoryQuery = {}): Promise<MemoryRow[]> {
    await this.init();
    let rows = this.memory;
    if (query.sourceLocale) rows = rows.filter((row) => row.sourceLocale === query.sourceLocale);
    if (query.targetLocale) rows = rows.filter((row) => row.targetLocale === query.targetLocale);
    if (query.engine) rows = rows.filter((row) => row.engine === query.engine);
    if (query.approved !== undefined) rows = rows.filter((row) => row.approved === query.approved);
    if (query.frozen !== undefined) rows = rows.filter((row) => row.frozen === query.frozen);
    if (query.search) {
      const needle = query.search.toLowerCase();
      rows = rows.filter((row) => row.source.toLowerCase().includes(needle) || row.target.toLowerCase().includes(needle));
    }
    const offset = query.offset ?? 0;
    return rows.slice(offset, query.limit ? offset + query.limit : undefined);
  }

  async update(id: number, patch: Partial<MemoryRow>): Promise<void> {
    await this.init();
    const row = this.memory.find((candidate) => candidate.id === id);
    if (!row) return;
    Object.assign(row, patch, { updatedAt: new Date().toISOString() });
    await this.writeTable('memory.jsonl', this.memory);
  }

  async remove(ids: number[]): Promise<number> {
    await this.init();
    const before = this.memory.length;
    const removeSet = new Set(ids);
    this.memory = this.memory.filter((row) => !removeSet.has(row.id ?? -1));
    await this.writeTable('memory.jsonl', this.memory);
    return before - this.memory.length;
  }

  async all(): Promise<MemoryRow[]> {
    await this.init();
    return this.memory;
  }

  async stats(): Promise<MemoryStats> {
    await this.init();
    const byLocaleMap = new Map<string, { entries: number; approved: number; frozen: number }>();
    const byEngineMap = new Map<string, number>();
    for (const row of this.memory) {
      const locale = byLocaleMap.get(row.targetLocale) ?? { entries: 0, approved: 0, frozen: 0 };
      locale.entries += 1;
      if (row.approved) locale.approved += 1;
      if (row.frozen) locale.frozen += 1;
      byLocaleMap.set(row.targetLocale, locale);
      byEngineMap.set(row.engine, (byEngineMap.get(row.engine) ?? 0) + 1);
    }
    return {
      driver: this.driver,
      location: this.location,
      entries: this.memory.length,
      approved: this.memory.filter((row) => row.approved).length,
      frozen: this.memory.filter((row) => row.frozen).length,
      rules: this.rules.length,
      suggestions: this.suggestions.length,
      runs: this.runs.length,
      cache: this.cache.size,
      byLocale: [...byLocaleMap].map(([locale, value]) => ({ locale, ...value })).sort((a, b) => b.entries - a.entries),
      byEngine: [...byEngineMap].map(([engine, entries]) => ({ engine, entries })).sort((a, b) => b.entries - a.entries),
    };
  }

  async addRules(rules: RuleRow[]): Promise<number> {
    await this.init();
    let added = 0;
    for (const rule of rules) {
      const duplicate = this.rules.find(
        (existing) =>
          existing.kind === rule.kind &&
          (existing.locale ?? null) === (rule.locale ?? null) &&
          existing.pattern === rule.pattern &&
          existing.value === rule.value,
      );
      if (duplicate) {
        if (rule.confidence > duplicate.confidence) duplicate.confidence = rule.confidence;
        continue;
      }
      this.rules.push({ ...rule, id: this.nextId++, createdAt: rule.createdAt ?? new Date().toISOString() });
      added += 1;
    }
    await this.writeTable('rules.jsonl', this.rules);
    return added;
  }

  async listRules(query: RuleQuery = {}): Promise<RuleRow[]> {
    await this.init();
    let rows = this.rules;
    if (query.kind) rows = rows.filter((row) => row.kind === query.kind);
    if (query.locale !== undefined) rows = rows.filter((row) => (row.locale ?? null) === (query.locale ?? null));
    if (query.enabled !== undefined) rows = rows.filter((row) => row.enabled === query.enabled);
    if (query.search) {
      const needle = query.search.toLowerCase();
      rows = rows.filter((row) => row.pattern.toLowerCase().includes(needle) || row.value.toLowerCase().includes(needle));
    }
    rows = [...rows].sort((a, b) => b.priority - a.priority);
    const offset = query.offset ?? 0;
    return rows.slice(offset, query.limit ? offset + query.limit : undefined);
  }

  async updateRule(id: number, patch: Partial<RuleRow>): Promise<void> {
    await this.init();
    const rule = this.rules.find((candidate) => candidate.id === id);
    if (!rule) return;
    Object.assign(rule, patch);
    await this.writeTable('rules.jsonl', this.rules);
  }

  async removeRules(ids: number[]): Promise<number> {
    await this.init();
    const before = this.rules.length;
    const removeSet = new Set(ids);
    this.rules = this.rules.filter((rule) => !removeSet.has(rule.id ?? -1));
    await this.writeTable('rules.jsonl', this.rules);
    return before - this.rules.length;
  }

  async cacheGet(key: string): Promise<{ value: string; engine: string } | null> {
    await this.init();
    return this.cache.get(key) ?? null;
  }

  async cacheSet(key: string, value: string, engine: string): Promise<void> {
    await this.init();
    this.cache.set(key, { value, engine });
    await this.writeTable(
      'cache.jsonl',
      [...this.cache].map(([cacheKey, entry]) => ({ key: cacheKey, ...entry })),
    );
  }

  async cacheClear(): Promise<number> {
    await this.init();
    const size = this.cache.size;
    this.cache.clear();
    await this.writeTable('cache.jsonl', []);
    return size;
  }

  async addSuggestions(rows: SuggestionRow[]): Promise<void> {
    await this.init();
    for (const row of rows) {
      this.suggestions.push(row);
    }
    await this.writeTable('suggestions.jsonl', this.suggestions);
  }

  async listSuggestions(query: { locale?: string; limit?: number } = {}): Promise<SuggestionRow[]> {
    await this.init();
    let rows = this.suggestions;
    if (query.locale) rows = rows.filter((row) => row.locale === query.locale);
    return query.limit ? rows.slice(-query.limit) : rows;
  }

  async addRun(run: RunSummary): Promise<void> {
    await this.init();
    this.runs.push(run);
    await this.writeTable('runs.jsonl', this.runs);
  }

  async listRuns(limit = 20): Promise<RunSummary[]> {
    await this.init();
    return this.runs.slice(-limit).reverse();
  }
}

function mergeRefs(a: string[] | undefined, b: string[] | undefined): string[] {
  return [...new Set([...(a ?? []), ...(b ?? [])])];
}
