import type { Issue, CatalogEntry } from '../core/types';
import type { ProjectContext } from './context';
import { fileFor } from './context';
import { alignCatalog, mergeValues } from '../core/catalog/catalog';
import { validateEntry } from '../core/validate';
import { resolveBudget, widgetForBudget } from '../core/length/budget';
import { fontSpecOf } from '../core/validate/length';
import { measure } from '../core/length/metrics';
import { pseudoLocalize } from '../core/translate/engines/pseudo';
import type { LocaleReport, PreviewSample } from '../report/model';

export interface FixerInput {
  key: string;
  locale: string;
  source: string;
  target: string;
  entry: CatalogEntry;
}

export interface AnalysisOptions {
  locales?: string[];
  /** Cap on preview samples per locale. */
  sampleLimit?: number;
  /** Applies deterministic fixes to the in-memory catalogs before measuring. */
  fixer?: (input: FixerInput) => { text: string; fixes: string[] };
}

export interface Analysis {
  locales: LocaleReport[];
  issues: Issue[];
  samples: Map<string, PreviewSample[]>;
  totals: { translated: number; missing: number; empty: number; keys: number };
  fixesApplied: number;
}

/** Validates every locale file, computes length metrics and builds UI samples. */
export async function analyzeLocales(project: ProjectContext, options: AnalysisOptions = {}): Promise<Analysis> {
  const { config } = project;
  const locales = options.locales ?? config.locales.filter((locale) => locale !== config.sourceLocale);
  const sampleLimit = options.sampleLimit ?? 40;
  const font = fontSpecOf(config);
  const reports: LocaleReport[] = [];
  const allIssues: Issue[] = [];
  const samples = new Map<string, PreviewSample[]>();
  let totalTranslated = 0;
  let totalMissing = 0;
  let totalEmpty = 0;
  let totalKeys = 0;
  let fixesApplied = 0;

  for (const locale of locales) {
    const localeIssues: Issue[] = [];
    const localeSamples: PreviewSample[] = [];
    let translated = 0;
    let missing = 0;
    let empty = 0;
    let frozen = 0;
    let keys = 0;
    const files: string[] = [];

    for (const [catalogPath, sourceFile] of project.sourceFiles) {
      const target = fileFor(project, catalogPath, locale);
      if (!target) continue;
      files.push(target.target.relativePath);
      const alignment = alignCatalog(sourceFile, target);
      keys += sourceFile.entries.length;
      missing += alignment.missing.length;
      empty += alignment.empty.length;

      const fixesByKey = new Map<string, string[]>();
      if (options.fixer) {
        const updates = new Map<string, string>();
        for (const entry of target.entries) {
          const sourceEntry = sourceFile.index.get(entry.key);
          if (!sourceEntry || entry.value === null) continue;
          const outcome = options.fixer({
            key: entry.key,
            locale,
            source: sourceEntry.value ?? '',
            target: entry.value,
            entry,
          });
          if (outcome.text !== entry.value) {
            updates.set(entry.key, outcome.text);
            fixesByKey.set(entry.key, outcome.fixes);
            fixesApplied += 1;
          }
        }
        if (updates.size > 0) {
          const merged = mergeValues(target, updates);
          target.entries = merged;
          target.index = new Map(merged.map((entry) => [entry.key, entry]));
        }
      }

      for (const sourceEntry of sourceFile.entries) {
        const source = sourceEntry.value ?? '';
        if (source.trim().length === 0) continue;
        const entry = target.index.get(sourceEntry.key);
        const value = entry?.value ?? null;
        if (value !== null && value.trim().length > 0) translated += 1;
        if (entry?.meta?.frozen === true) frozen += 1;
        const effectiveEntry: CatalogEntry = { ...(entry ?? { key: sourceEntry.key, value: null }) };
        effectiveEntry.maxLength = entry?.maxLength ?? sourceEntry.maxLength;
        const validation = validateEntry(project.validation, {
          key: sourceEntry.key,
          locale,
          value,
          entry: effectiveEntry,
          source,
        });
        const appliedFixes = fixesByKey.get(sourceEntry.key);
        if (appliedFixes && appliedFixes.length > 0 && validation.issues.every((issue) => issue.severity !== 'error')) {
          validation.issues.push({
            code: 'auto.fixed',
            severity: 'info',
            locale,
            key: sourceEntry.key,
            message: `auto-fixed: ${appliedFixes.join('; ')}`,
            fixable: false,
          });
        }
        localeIssues.push(...validation.issues);

        const budget = resolveBudget(config, sourceEntry.key, effectiveEntry);
        const widget = widgetForBudget(budget);
        const targetMetrics = measure(value ?? '', font);
        const sourceMetrics = measure(source, font);
        const overBudget =
          value !== null &&
          budget.max !== undefined &&
          (budget.unit === 'px' ? targetMetrics.px : targetMetrics.chars) > budget.max;
        localeSamples.push({
          key: sourceEntry.key,
          source,
          target: value,
          widget: widget.name,
          containerPx: widget.px,
          chars: targetMetrics.chars,
          px: targetMetrics.px,
          sourcePx: sourceMetrics.px,
          ratio: sourceMetrics.px === 0 ? 1 : Number((targetMetrics.px / sourceMetrics.px).toFixed(2)),
          lines: validation.metrics?.lines ?? 1,
          overBudget,
          severity: severityOf(overBudget, budget.hard ?? false, validation.issues),
          issues: validation.issues,
          metrics: validation.metrics,
          pseudo: pseudoLocalize(source, 0.4, budget.max),
        });
      }
    }

    const rank = { error: 0, warn: 1, info: 2, ok: 3 } as const;
    localeSamples.sort((a, b) => rank[a.severity] - rank[b.severity] || b.ratio - a.ratio);
    const report: LocaleReport = {
      locale,
      files,
      total: keys,
      translated,
      missing,
      empty,
      coverage: keys === 0 ? 1 : translated / keys,
      frozen,
      issues: localeIssues,
      samples: localeSamples.slice(0, sampleLimit),
    };
    reports.push(report);
    samples.set(locale, report.samples);
    allIssues.push(...localeIssues);
    totalTranslated += translated;
    totalMissing += missing;
    totalEmpty += empty;
    totalKeys += keys;
  }

  return {
    locales: reports,
    issues: allIssues,
    samples,
    totals: { translated: totalTranslated, missing: totalMissing, empty: totalEmpty, keys: totalKeys },
    fixesApplied,
  };
}

function severityOf(overBudget: boolean, hard: boolean, issues: Issue[]): PreviewSample['severity'] {
  if (overBudget) return hard ? 'error' : 'warn';
  if (issues.some((issue) => issue.severity === 'error')) return 'error';
  if (issues.some((issue) => issue.severity === 'warn')) return 'warn';
  if (issues.length > 0) return 'info';
  return 'ok';
}
