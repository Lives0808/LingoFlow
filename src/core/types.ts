/** Shared domain types for LingoFlow. */

export type FormatId = 'json' | 'yaml' | 'properties' | 'po' | 'arb' | 'strings' | 'csv' | 'tsjs';

export type EngineId =
  | 'offline'
  | 'pseudo'
  | 'argos'
  | 'libretranslate'
  | 'deepl'
  | 'openai'
  | 'custom';

export type Severity = 'error' | 'warn' | 'info';

export type IssueCode =
  | 'missing'
  | 'empty'
  | 'untranslated'
  | 'source.changed'
  | 'stale'
  | 'placeholder.missing'
  | 'placeholder.extra'
  | 'placeholder.mismatch'
  | 'icu.invalid'
  | 'icu.category'
  | 'tag.unbalanced'
  | 'tag.mismatch'
  | 'length.tooLong'
  | 'length.tooShort'
  | 'length.expansion'
  | 'length.wrap'
  | 'text.leadingSpace'
  | 'text.trailingSpace'
  | 'text.doubleSpace'
  | 'text.punctuation'
  | 'text.ellipsis'
  | 'text.newline'
  | 'cjk.punctuation'
  | 'cjk.space'
  | 'script.mismatch'
  | 'dnt.violated'
  | 'dnt.missing'
  | 'glossary.missed'
  | 'glossary.drift'
  | 'consistency.source'
  | 'consistency.term'
  | 'forbidden.term'
  | 'style.case'
  | 'style.formality'
  | 'frozen.protected'
  | 'auto.fixed'
  | 'key.unused'
  | 'key.undefined'
  | 'key.duplicate';

export interface Issue {
  code: IssueCode;
  severity: Severity;
  locale: string;
  key: string;
  message: string;
  detail?: string;
  source?: string;
  target?: string;
  fixable?: boolean;
  suggestion?: string;
}

export interface CatalogEntry {
  key: string;
  /** null = key exists but has no value (empty/placeholder slot). */
  value: string | null;
  comment?: string;
  refs?: string[];
  /** `{maxLength: 12}` style hint parsed from source comments. */
  maxLength?: number;
  frozen?: boolean;
  meta?: Record<string, unknown>;
}

export interface CatalogMeta {
  locale?: string;
  /** Opaque artifact kept for lossless re-serialisation (YAML AST, PO header, ...). */
  document?: unknown;
  /** Arbitrary format-level metadata (ARB @@locale, PO header, ...). */
  [key: string]: unknown;
}

export interface DecodedCatalog {
  entries: CatalogEntry[];
  meta: CatalogMeta;
}

export interface CatalogTarget {
  locale: string;
  /** Index of the `catalogs[]` entry this target came from. */
  catalogIndex: number;
  /** Absolute path on disk. */
  path: string;
  /** Path relative to the project root (used in reports). */
  relativePath: string;
  format: FormatId;
  nesting: boolean;
  keySeparator: string;
}

export interface CatalogFile {
  locale: string;
  path: string;
  format: FormatId;
  target: CatalogTarget;
  entries: CatalogEntry[];
  index: Map<string, CatalogEntry>;
  meta: CatalogMeta;
  /** Content hash of the file when it was read (write-conflict guard). */
  contentHash: string;
  raw: string;
}

export interface MemoryRow {
  id?: number;
  sourceLocale: string;
  targetLocale: string;
  source: string;
  target: string;
  engine: string;
  confidence: number;
  refs: string[];
  approved: boolean;
  frozen: boolean;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
  /** Source text hash, used for exact lookup. */
  sourceHash: string;
}

export interface ScoredMemoryRow extends MemoryRow {
  score: number;
}

export type RuleKind = 'glossary' | 'dnt' | 'correction' | 'style' | 'length';

export interface RuleRow {
  id?: number;
  kind: RuleKind;
  /** null = applies to every locale. */
  locale?: string | null;
  pattern: string;
  value: string;
  priority: number;
  enabled: boolean;
  /** Where the rule came from: `seed`, `import`, `manual`, `learned`, `review`. */
  origin: string;
  confidence: number;
  note?: string;
  createdAt?: string;
}

export interface SuggestionRow {
  sourceHash: string;
  source: string;
  locale: string;
  machine: string;
  engine: string;
  createdAt: string;
  refs?: string[];
}

export interface RunSummary {
  id?: number;
  command: string;
  startedAt: string;
  finishedAt: string;
  engine: string;
  locales: string[];
  translated: number;
  fixed: number;
  issues: number;
  errors: number;
  warnings: number;
  filesWritten: string[];
  durationMs: number;
  meta?: Record<string, unknown>;
}

export interface TranslateItem {
  id: string;
  key: string;
  text: string;
  from: string;
  to: string;
  comment?: string;
  maxChars?: number;
  maxPx?: number;
  /** Extra instruction for LLM style engines. */
  hint?: string;
}

export interface TranslateOutput {
  id: string;
  text: string;
  engine: string;
  confidence: number;
  notes?: string[];
}

export interface TranslatedEntry {
  key: string;
  locale: string;
  text: string;
  engine: string;
  confidence: number;
  fromMemory: boolean;
  memoryScore?: number;
  issues: Issue[];
  fixed: string[];
  skippedReason?: string;
}

export interface LengthBudget {
  min?: number;
  max?: number;
  hard?: boolean;
  unit: 'px' | 'chars';
  widget?: string;
}

export interface HardcodedCandidate {
  file: string;
  line: number;
  column: number;
  text: string;
  kind: 'jsx-text' | 'attribute' | 'string';
  attribute?: string;
  start: number;
  end: number;
  confidence: number;
  /** Suggested catalog key. */
  key: string;
  callTemplate?: string;
}

export interface KeyUsage {
  key: string;
  file: string;
  line: number;
  column: number;
}

export interface ScanResult {
  filesScanned: number;
  usages: KeyUsage[];
  usedKeys: string[];
  hardcoded: HardcodedCandidate[];
  dynamicKeys: string[];
}
