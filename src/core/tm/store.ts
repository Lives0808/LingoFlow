import type { MemoryRow, RuleKind, RuleRow, RunSummary, SuggestionRow } from '../types';

export interface MemoryQuery {
  sourceLocale?: string;
  targetLocale?: string;
  engine?: string;
  approved?: boolean;
  frozen?: boolean;
  search?: string;
  limit?: number;
  offset?: number;
}

export interface MemoryStats {
  driver: 'sqlite' | 'jsonl';
  location: string;
  entries: number;
  approved: number;
  frozen: number;
  rules: number;
  suggestions: number;
  runs: number;
  cache: number;
  byLocale: Array<{ locale: string; entries: number; approved: number; frozen: number }>;
  byEngine: Array<{ engine: string; entries: number }>;
}

export interface RuleQuery {
  kind?: RuleKind;
  locale?: string | null;
  enabled?: boolean;
  search?: string;
  limit?: number;
  offset?: number;
}

/**
 * Translation memory + rule base + engine cache.
 * Two drivers share this interface: SQLite (`node:sqlite`) and JSONL files.
 * Everything stays inside the project directory; nothing is uploaded.
 */
export interface MemoryStore {
  readonly driver: 'sqlite' | 'jsonl';
  readonly location: string;

  init(): Promise<void>;
  close(): Promise<void>;

  upsertMany(rows: MemoryRow[]): Promise<number>;
  exact(sourceLocale: string, targetLocale: string, source: string): Promise<MemoryRow | null>;
  /** All rows for a locale pair, used for fuzzy matching. */
  pairs(sourceLocale: string, targetLocale: string): Promise<MemoryRow[]>;
  list(query?: MemoryQuery): Promise<MemoryRow[]>;
  update(id: number, patch: Partial<MemoryRow>): Promise<void>;
  remove(ids: number[]): Promise<number>;
  all(): Promise<MemoryRow[]>;
  stats(): Promise<MemoryStats>;

  addRules(rules: RuleRow[]): Promise<number>;
  listRules(query?: RuleQuery): Promise<RuleRow[]>;
  updateRule(id: number, patch: Partial<RuleRow>): Promise<void>;
  removeRules(ids: number[]): Promise<number>;

  cacheGet(key: string): Promise<{ value: string; engine: string } | null>;
  cacheSet(key: string, value: string, engine: string): Promise<void>;
  cacheClear(): Promise<number>;

  addSuggestions(rows: SuggestionRow[]): Promise<void>;
  listSuggestions(query?: { locale?: string; limit?: number }): Promise<SuggestionRow[]>;

  addRun(run: RunSummary): Promise<void>;
  listRuns(limit?: number): Promise<RunSummary[]>;
}
