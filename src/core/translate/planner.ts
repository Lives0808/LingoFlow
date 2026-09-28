import type { CatalogEntry, CatalogFile, Issue, MemoryRow, ScoredMemoryRow, SuggestionRow, TranslateItem } from '../types';
import type { ResolvedConfig } from '../config/schema';
import type { Logger } from '../logger';
import { toError } from '../errors';
import { mapPool, retry } from '../utils/pool';
import { sha256 } from '../utils/hash';
import { fuzzyScore } from '../utils/text';
import type { MemoryStore } from '../tm/store';
import { dntFor, glossaryFor, type RuleSet } from '../rules/model';
import { protectText, restoreText } from '../rules/apply';
import { fixEntry, type FixOutcome } from '../fix';
import { validateEntry, type ValidationContext } from '../validate';
import { resolveBudget } from '../length/budget';
import { measure } from '../length/metrics';
import type { EngineContext, TranslationEngine } from './engine';
import { engineForLocale, type ResolvedEngines } from './registry';

export type JobAction = 'translate' | 'memory' | 'fuzzy' | 'skip' | 'frozen';

export interface TranslationJob {
  locale: string;
  key: string;
  source: string;
  current: string | null;
  entry?: CatalogEntry;
  action: JobAction;
  reason: string;
  memory?: MemoryRow;
  memoryScore?: number;
  frozen: boolean;
}

export interface JobResult {
  job: TranslationJob;
  value: string | null;
  engine: string;
  confidence: number;
  fromMemory: boolean;
  issues: Issue[];
  fixes: string[];
  changed: boolean;
  notes: string[];
}

export interface PlannerDeps {
  config: ResolvedConfig;
  rootDir: string;
  logger: Logger;
  store: MemoryStore;
  ruleSet: RuleSet;
  engines: ResolvedEngines;
  validation: ValidationContext;
  /** Source catalog path -> locale -> catalog file. */
  bySource: Map<string, Map<string, CatalogFile>>;
  force?: boolean;
  onlyKeys?: string[];
  locales?: string[];
  dryRun?: boolean;
  onProgress?: (done: number, total: number, label: string) => void;
}

/** Machine-readable cache key for one engine call. */
export function translationCacheKey(
  engine: string,
  from: string,
  to: string,
  text: string,
  ruleVersion: string,
  maxChars?: number,
): string {
  return sha256([engine, from, to, text, ruleVersion, maxChars ?? ''].join('\u0000'));
}

/**
 * Decides what actually needs translating, cheapest decision first:
 * skip → frozen → translation memory → fuzzy match → engine.
 */
export async function planJobs(deps: PlannerDeps): Promise<TranslationJob[]> {
  const { config, store } = deps;
  const locales = deps.locales ?? config.locales;
  const jobs: TranslationJob[] = [];
  const pairCache = new Map<string, MemoryRow[]>();

  for (const [, variants] of deps.bySource) {
    const sourceFile = variants.get(config.sourceLocale);
    if (!sourceFile) continue;
    for (const locale of locales) {
      if (locale === config.sourceLocale) continue;
      const file = variants.get(locale);
      if (!file) continue;
      let pairs = pairCache.get(locale);
      if (!pairs) {
        pairs = await store.pairs(config.sourceLocale, locale);
        pairCache.set(locale, pairs);
      }
      jobs.push(...planForFile(deps, sourceFile, file, locale, pairs));
    }
  }
  return jobs;
}

function planForFile(
  deps: PlannerDeps,
  sourceFile: CatalogFile,
  file: CatalogFile,
  locale: string,
  pairs: MemoryRow[],
): TranslationJob[] {
  const { config } = deps;
  const keysFilter = deps.onlyKeys ? new Set(deps.onlyKeys) : null;
  const exactMap = new Map<string, MemoryRow>();
  for (const row of pairs) {
    const existing = exactMap.get(row.sourceHash);
    if (!existing || row.confidence > existing.confidence) exactMap.set(row.sourceHash, row);
  }
  const jobs: TranslationJob[] = [];

  for (const sourceEntry of sourceFile.entries) {
    if (keysFilter && !keysFilter.has(sourceEntry.key)) continue;
    const source = sourceEntry.value ?? '';
    if (source.trim().length === 0) continue;
    const currentEntry = file.index.get(sourceEntry.key);
    const current = currentEntry?.value ?? null;
    const maxLength = currentEntry?.maxLength ?? sourceEntry.maxLength;
    const entry: CatalogEntry = { ...(currentEntry ?? { key: sourceEntry.key, value: null }), maxLength };
    if (sourceEntry.refs) entry.refs = sourceEntry.refs;

    const memory = exactMap.get(sha256(`${config.sourceLocale}\u0000${source}`));
    const frozen = memory?.frozen ?? false;
    const decision = decideAction({ config, policy: config.translation.policy, current, source, memory, frozen, force: deps.force ?? false });

    const job: TranslationJob = {
      locale,
      key: sourceEntry.key,
      source,
      current,
      entry,
      action: decision.action,
      reason: decision.reason,
      frozen,
    };
    if (memory) job.memory = memory;

    if (decision.action === 'translate' && config.translation.memory.fuzzyAutofill) {
      const best = bestFuzzyMatch(source, pairs, config.translation.memory.fuzzyThreshold, locale);
      if (best) {
        job.action = 'fuzzy';
        job.reason = `fuzzy memory match (${(best.score * 100).toFixed(0)}%)`;
        job.memory = best;
        job.memoryScore = best.score;
      }
    }
    jobs.push(job);
  }
  return jobs;
}

export interface DecisionInput {
  config: ResolvedConfig;
  policy: ResolvedConfig['translation']['policy'];
  current: string | null;
  source: string;
  memory?: MemoryRow;
  frozen: boolean;
  force: boolean;
}

export function decideAction(input: DecisionInput): { action: JobAction; reason: string } {
  const hasValue = input.current !== null && input.current.trim().length > 0;
  if (input.frozen) {
    // Frozen entries are protected from every automatic write, including --force.
    if (!hasValue && input.memory) {
      return { action: 'memory', reason: 'frozen translation restored from memory' };
    }
    return { action: 'frozen', reason: 'translation is frozen (approved earlier)' };
  }
  if (input.force) return { action: 'translate', reason: 'forced' };
  switch (input.policy) {
    case 'all':
      return { action: 'translate', reason: 'policy=all' };
    case 'missing':
      return hasValue ? { action: 'skip', reason: 'already translated' } : { action: 'translate', reason: 'missing translation' };
    case 'empty':
      if (input.current === null) return { action: 'translate', reason: 'key missing' };
      if (input.current.trim().length === 0) return { action: 'translate', reason: 'empty value' };
      return { action: 'skip', reason: 'already translated' };
    case 'untranslated':
      if (!hasValue) return { action: 'translate', reason: 'missing translation' };
      if (input.current === input.source) return { action: 'translate', reason: 'identical to source' };
      return { action: 'skip', reason: 'already translated' };
    case 'stale': {
      if (!hasValue) return { action: 'translate', reason: 'missing translation' };
      if (!input.memory) return { action: 'translate', reason: 'no memory record for this source (source changed?)' };
      if (input.memory.target !== input.current) return { action: 'skip', reason: 'translation diverged from memory (human edit kept)' };
      return { action: 'skip', reason: 'memory is up to date' };
    }
    default:
      return { action: 'skip', reason: 'nothing to do' };
  }
}

export function bestFuzzyMatch(source: string, pairs: MemoryRow[], threshold: number, locale: string): ScoredMemoryRow | null {
  let best: ScoredMemoryRow | null = null;
  for (const row of pairs) {
    if (row.target.trim().length === 0) continue;
    if (row.target === source) continue;
    const score = fuzzyScore(source, row.source);
    if (score < threshold) continue;
    if (!best || score > best.score) best = { ...row, score: Number(score.toFixed(3)) };
  }
  void locale;
  return best;
}

/** Executes the plan: memory hits are instant, the rest go to the engine. */
export async function runJobs(deps: PlannerDeps, jobs: TranslationJob[]): Promise<JobResult[]> {
  const results: JobResult[] = [];
  const engineJobs: TranslationJob[] = [];
  const suggestions: SuggestionRow[] = [];
  const memoryRows: MemoryRow[] = [];

  for (const job of jobs) {
    switch (job.action) {
      case 'skip':
        results.push(skipResult(job, job.reason, []));
        break;
      case 'frozen': {
        const issues: Issue[] = [];
        if (job.memory && job.memory.target !== job.current) {
          issues.push({
            code: 'frozen.protected',
            severity: 'warn',
            locale: job.locale,
            key: job.key,
            message: `Frozen translation kept; approved value is "${truncate(job.memory.target)}"`,
            detail: 'unfreeze the entry (lingoflow memory approve --freeze/--unfreeze) to let automation update it',
            source: job.source,
            target: job.current ?? '',
            fixable: false,
          });
        }
        results.push(skipResult(job, job.reason, issues));
        break;
      }
      case 'memory':
      case 'fuzzy': {
        const memory = job.memory as MemoryRow;
        results.push(postProcess(deps, job, memory.target, job.action === 'fuzzy' ? 'memory-fuzzy' : 'memory', memory.confidence, [job.reason]));
        break;
      }
      case 'translate':
        engineJobs.push(job);
        break;
    }
  }

  if (engineJobs.length > 0 && !deps.dryRun) {
    const groups = new Map<string, TranslationJob[]>();
    for (const job of engineJobs) {
      const group = groups.get(job.locale) ?? [];
      group.push(job);
      groups.set(job.locale, group);
    }
    const entries = [...groups];
    const concurrency = Math.max(1, deps.config.translation.concurrency);
    let done = 0;
    const batchResults = await mapPool(entries, concurrency, async ([locale, localeJobs]) => {
      const engine = engineForLocale(deps.engines, locale, deps.config);
      const produced = await translateGroup(deps, engine, locale, localeJobs, suggestions);
      done += localeJobs.length;
      deps.onProgress?.(done, engineJobs.length, locale);
      return produced;
    });
    for (const list of batchResults) {
      for (const result of list) {
        results.push(result);
        if (result.value !== null) {
          memoryRows.push({
            sourceLocale: deps.config.sourceLocale,
            targetLocale: result.job.locale,
            source: result.job.source,
            target: result.value,
            engine: result.engine,
            confidence: result.confidence,
            refs: result.job.entry?.refs ?? [],
            approved: false,
            frozen: false,
            sourceHash: sha256(`${deps.config.sourceLocale}\u0000${result.job.source}`),
          });
        }
      }
    }
    if (memoryRows.length > 0) await deps.store.upsertMany(memoryRows);
    if (suggestions.length > 0) await deps.store.addSuggestions(suggestions);
  }

  return results;
}

function skipResult(job: TranslationJob, reason: string, issues: Issue[]): JobResult {
  return {
    job,
    value: job.current,
    engine: 'memory',
    confidence: 1,
    fromMemory: true,
    issues,
    fixes: [],
    changed: false,
    notes: [reason],
  };
}

async function translateGroup(
  deps: PlannerDeps,
  engine: TranslationEngine,
  locale: string,
  jobs: TranslationJob[],
  suggestions: SuggestionRow[],
): Promise<JobResult[]> {
  const { config, ruleSet } = deps;
  const glossaryMode = config.translation.glossary.mode;
  const shieldTerms = glossaryMode === 'protect' || (glossaryMode === 'auto' && engine.id !== 'openai');
  const glossary = glossaryFor(ruleSet, locale);
  const dnt = dntFor(ruleSet, locale);

  const items: TranslateItem[] = [];
  const protections = new Map<string, ReturnType<typeof protectText>>();
  const cached = new Map<string, { value: string; engine: string }>();

  for (const job of jobs) {
    const budget = resolveBudget(config, job.key, job.entry);
    const id = `${job.locale}\u0000${job.key}`;
    const protection = shieldTerms ? protectText(job.source, glossary, dnt) : { text: job.source, tokens: new Map() };
    protections.set(id, protection);
    const item: TranslateItem = {
      id,
      key: job.key,
      text: protection.text,
      from: config.sourceLocale,
      to: locale,
    };
    if (job.entry?.comment) item.comment = job.entry.comment;
    if (budget.max !== undefined && budget.unit === 'chars') item.maxChars = budget.max;
    if (budget.unit === 'px' && budget.max !== undefined) {
      item.maxPx = measure(job.source, { size: config.length.font.size, weight: config.length.font.weight }).px;
    }
    const cacheKey = translationCacheKey(engine.id, config.sourceLocale, locale, protection.text, ruleSet.version, item.maxChars);
    const hit = await deps.store.cacheGet(cacheKey);
    if (hit) {
      cached.set(id, hit);
      continue;
    }
    items.push(item);
  }

  let outputs: Array<{ id: string; text: string; engine: string; confidence: number; notes?: string[] }> = [];
  const engineContext: EngineContext = {
    config,
    logger: deps.logger,
    rootDir: deps.rootDir,
    glossaryByLocale: new Map([[locale, glossary]]),
    dntByLocale: new Map([[locale, dnt]]),
  };

  if (items.length > 0) {
    const candidates: TranslationEngine[] = [engine, ...deps.engines.fallbacks.filter((candidate) => candidate.id !== engine.id)];
    let lastError: Error | null = null;
    for (const candidate of candidates) {
      try {
        outputs = await retry(() => candidate.translate(items, engineContext), {
          attempts: Math.max(1, config.translation.retries + 1),
          onRetry: (error, attempt) => {
            deps.logger.warn(`engine ${candidate.id} failed (attempt ${attempt}): ${toError(error).message}`);
          },
        });
        if (candidate.id !== engine.id) {
          deps.logger.warn(`fell back to engine "${candidate.id}" for ${items.length} string(s) in ${locale}`);
        }
        lastError = null;
        break;
      } catch (error) {
        lastError = toError(error);
      }
    }
    if (lastError) throw lastError;
  }

  const byId = new Map(outputs.map((output) => [output.id, output]));
  const results: JobResult[] = [];
  for (const job of jobs) {
    const id = `${job.locale}\u0000${job.key}`;
    const hit = cached.get(id);
    if (hit) {
      results.push(postProcess(deps, job, hit.value, hit.engine, 0.9, ['cache hit']));
      continue;
    }
    const output = byId.get(id);
    if (!output) {
      results.push({
        job,
        value: job.current,
        engine: 'none',
        confidence: 0,
        fromMemory: false,
        issues: [],
        fixes: [],
        changed: false,
        notes: ['engine did not return a translation'],
      });
      continue;
    }
    const protection = protections.get(id) as ReturnType<typeof protectText>;
    const text = protection.tokens.size > 0 ? restoreText(output.text, protection) : output.text;
    const result = postProcess(deps, job, text, output.engine, output.confidence, output.notes ?? []);
    results.push(result);
    const cacheKey = translationCacheKey(
      output.engine,
      config.sourceLocale,
      job.locale,
      protection.text,
      ruleSet.version,
      undefined,
    );
    await deps.store.cacheSet(cacheKey, text, output.engine);
    suggestions.push({
      sourceHash: sha256(`${config.sourceLocale}\u0000${job.source}`),
      source: job.source,
      locale: job.locale,
      machine: text,
      engine: output.engine,
      refs: job.entry?.refs ?? [],
      createdAt: new Date().toISOString(),
    });
  }
  return results;
}

/** Deterministic fixes + validation for one produced value. */
function postProcess(
  deps: PlannerDeps,
  job: TranslationJob,
  rawText: string,
  engine: string,
  confidence: number,
  notes: string[] = [],
): JobResult {
  const { config, ruleSet } = deps;
  const fixes: string[] = [...notes];
  let text = rawText;
  const outcome: FixOutcome = { text, fixes: [], overBudget: false, corrections: [] };
  if (config.length.autofix?.enabled !== false) {
    const fixed = fixEntry(
      { config, ruleSet, sourceLocale: config.sourceLocale },
      { key: job.key, locale: job.locale, source: job.source, target: text, entry: job.entry },
    );
    text = fixed.text;
    fixes.push(...fixed.fixes);
    outcome.overBudget = fixed.overBudget;
  }
  const validation = validateEntry(deps.validation, {
    key: job.key,
    locale: job.locale,
    value: text,
    entry: job.entry,
    source: job.source,
  });
  return {
    job,
    value: text,
    engine,
    confidence,
    fromMemory: engine.startsWith('memory'),
    issues: validation.issues,
    fixes,
    changed: text !== job.current,
    notes,
  };
}

function truncate(text: string, max = 40): string {
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}

/** Turns job results into the per-locale value maps written back to catalogs. */
export function collectValues(results: JobResult[]): Map<string, Map<string, string>> {
  const byLocale = new Map<string, Map<string, string>>();
  for (const result of results) {
    if (result.value === null) continue;
    if (result.job.action === 'skip' || result.job.action === 'frozen') continue;
    const values = byLocale.get(result.job.locale) ?? new Map<string, string>();
    values.set(result.job.key, result.value);
    byLocale.set(result.job.locale, values);
  }
  return byLocale;
}

export function summarizeResults(results: JobResult[]): {
  translated: number;
  fromMemory: number;
  fixed: number;
  changed: number;
  issues: Issue[];
} {
  let translated = 0;
  let fromMemory = 0;
  let fixed = 0;
  let changed = 0;
  const issues: Issue[] = [];
  for (const result of results) {
    if (result.job.action === 'translate') translated += 1;
    if (result.fromMemory) fromMemory += 1;
    if (result.fixes.length > 0) fixed += 1;
    if (result.changed) changed += 1;
    issues.push(...result.issues);
  }
  return { translated, fromMemory, fixed, changed, issues };
}

