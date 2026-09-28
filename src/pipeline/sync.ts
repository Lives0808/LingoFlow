import path from 'node:path';
import type { Issue } from '../core/types';
import { VERSION, PRODUCT } from '../version';
import { summarizeIssues } from '../core/validate';
import { fixEntry } from '../core/fix';
import { fileFor, exportRulesToFiles } from './context';
import { mergeValues, saveCatalog } from '../core/catalog/catalog';
import type { ProjectContext } from './context';
import { analyzeLocales, type Analysis } from './analyze';
import { compareKeyCoverage, scanProject } from './scan';
import { collectValues, planJobs, runJobs, summarizeResults } from '../core/translate/planner';

import { learnCorrectionsFromEdits, learnGlossaryFromPairs, learnStylesFromEntries } from '../core/rules/learn';
import { extractHardcoded, type ExtractResult } from './extract';
import { sha256 } from '../core/utils/hash';
import type { LearnedSummary, RunReport, ScanSummary } from '../report/model';
import type { RuleRow } from '../core/types';

export interface SyncOptions {
  project: ProjectContext;
  locales?: string[];
  onlyKeys?: string[];
  force?: boolean;
  dryRun?: boolean;
  /** Apply deterministic fixes to existing translations too. Default true. */
  fix?: boolean;
  /** Scan source code for keys and hardcoded copy. Default true. */
  scan?: boolean;
  /** Rewrite hardcoded strings into t() calls. Default from config. */
  extract?: boolean;
  /** Derive glossary/style/correction rules from history. Default false. */
  learn?: boolean;
  sampleLimit?: number;
  /** Update the rule files (glossary/style/corrections) after learning. */
  exportRules?: boolean;
}

export interface SyncResult {
  report: RunReport;
  analysis: Analysis;
  extracted?: ExtractResult;
  written: string[];
}

/**
 * The one-click pipeline:
 *   scan code → extract (optional) → align → translate → fix → validate → write back
 *   → grow the memory/rule base → report.
 */
export async function runSync(options: SyncOptions): Promise<SyncResult> {
  const { project } = options;
  const { config, logger } = project;
  const startedAt = new Date();
  const notes: string[] = [];
  const issues: Issue[] = [];
  const locales = options.locales ?? config.locales.filter((locale) => locale !== config.sourceLocale);
  const dryRun = options.dryRun ?? false;
  const written: string[] = [];
  const rulesBefore = await project.store.stats();

  // 1) Scan the code base -----------------------------------------------------
  let scanSummary: ScanSummary | undefined;
  if (options.scan !== false && config.code.include.length > 0) {
    logger.step('Scanning source code for translation keys');
    const scan = await scanProject(config, project.rootDir);
    const defined = new Set<string>();
    for (const file of project.sourceFiles.values()) for (const entry of file.entries) defined.add(entry.key);
    const coverage = compareKeyCoverage(scan, defined);
    scanSummary = {
      filesScanned: scan.filesScanned,
      usages: scan.usages,
      undefinedKeys: coverage.undefinedKeys,
      unusedKeys: coverage.unusedKeys,
      dynamicKeys: scan.dynamicKeys,
      hardcoded: scan.hardcoded,
    };
    for (const key of coverage.undefinedKeys.slice(0, 200)) {
      const usage = coverage.usageByKey.get(key)?.[0];
      issues.push({
        code: 'key.undefined',
        severity: 'error',
        locale: config.sourceLocale,
        key,
        message: `Key "${key}" is used in code but missing from ${config.sourceLocale}`,
        detail: usage ? `${usage.file}:${usage.line}` : undefined,
      });
    }
    for (const key of coverage.unusedKeys.slice(0, 500)) {
      issues.push({
        code: 'key.unused',
        severity: 'info',
        locale: config.sourceLocale,
        key,
        message: 'Key is defined but never used in code',
      });
    }
  }

  // 2) Extract hardcoded copy (optional) --------------------------------------
  let extracted: ExtractResult | undefined;
  const shouldExtract = options.extract ?? config.code.hardcoded.mode === 'extract';
  if (shouldExtract && scanSummary && scanSummary.hardcoded.length > 0) {
    logger.step('Extracting hardcoded UI strings');
    extracted = await extractHardcoded(project, {
      dryRun,
      minConfidence: config.code.hardcoded.minConfidence,
      writeSourceCatalog: !dryRun,
    });
    notes.push(`extracted ${extracted.keys.length} hardcoded string(s) from ${extracted.files.length} file(s)`);
    if (extracted.keys.length > 0 && !dryRun) {
      const sourcePaths = [...project.sourceFiles.keys()];
      for (const sourcePath of sourcePaths) written.push(path.relative(project.rootDir, sourcePath));
    }
    for (const candidate of extracted.skipped.slice(0, 100)) {
      issues.push({
        code: 'key.undefined',
        severity: 'info',
        locale: config.sourceLocale,
        key: candidate.key,
        message: `Hardcoded string left in place: "${candidate.text.slice(0, 60)}"`,
        detail: `${candidate.file}:${candidate.line}`,
      });
    }
  }

  // 3) Plan + translate ------------------------------------------------------
  logger.step(`Planning translations for ${locales.length} locale(s)`);
  const jobs = await planJobs({
    config,
    rootDir: project.rootDir,
    logger,
    store: project.store,
    ruleSet: project.ruleSet,
    engines: project.engines,
    validation: project.validation,
    bySource: project.bySource,
    force: options.force,
    onlyKeys: options.onlyKeys,
    locales,
    dryRun,
  });
  const actionable = jobs.filter((job) => job.action !== 'skip' && job.action !== 'frozen');
  logger.step(
    dryRun
      ? `Dry run: ${actionable.length} string(s) would be processed`
      : `Translating ${actionable.filter((job) => job.action === 'translate').length} new string(s) with engine "${config.translation.engine}"`,
  );

  let results: Awaited<ReturnType<typeof runJobs>> = [];
  if (!dryRun) {
    results = await runJobs(
      {
        config,
        rootDir: project.rootDir,
        logger,
        store: project.store,
        ruleSet: project.ruleSet,
        engines: project.engines,
        validation: project.validation,
        bySource: project.bySource,
        locales,
        onProgress: (done, total, label) => logger.progress(done, total, label),
      },
      jobs,
    );
  } else {
    results = jobs
      .filter((job) => job.action !== 'skip' && job.action !== 'frozen')
      .map((job) => ({
        job,
        value: job.action === 'translate' ? null : (job.memory?.target ?? job.current),
        engine: job.action === 'translate' ? 'dry-run' : 'memory',
        confidence: 0,
        fromMemory: job.action !== 'translate',
        issues: [],
        fixes: [],
        changed: false,
        notes: ['dry run'],
      }));
  }
  const stats = summarizeResults(results);
  issues.push(...stats.issues);

  // 4) Merge values into catalogs + apply fixes ------------------------------
  const valuesByLocale = collectValues(results);
  const writeMode = !dryRun;
  const fixer = options.fix === false
    ? undefined
    : (input: { key: string; locale: string; source: string; target: string; entry: import('../core/types').CatalogEntry }) => {
        const outcome = fixEntry(
          { config, ruleSet: project.ruleSet, sourceLocale: config.sourceLocale },
          input,
        );
        return { text: outcome.text, fixes: outcome.fixes };
      };

  if (writeMode) {
    for (const locale of locales) {
      const values = valuesByLocale.get(locale);
      for (const catalogPath of project.sourceFiles.keys()) {
        const file = fileFor(project, catalogPath, locale);
        if (!file) continue;
        if (values && values.size > 0) {
          const merged = mergeValues(file, values, { sortKeys: config.write.sortKeys });
          file.entries = merged;
          file.index = new Map(merged.map((entry) => [entry.key, entry]));
        }
      }
    }
  }

  // 5) Validate everything (applies fixes in memory when enabled) ------------
  logger.step('Validating translations, length budgets and UI fit');
  const analysis = await analyzeLocales(project, { locales, sampleLimit: options.sampleLimit, fixer });
  issues.push(...analysis.issues);

  // 6) Write catalogs --------------------------------------------------------
  if (writeMode) {
    for (const locale of locales) {
      for (const catalogPath of project.sourceFiles.keys()) {
        const file = fileFor(project, catalogPath, locale);
        if (!file) continue;
        const result = await saveCatalog(file, config, file.entries, { backup: config.write.backup });
        if (result.written) written.push(path.relative(project.rootDir, file.path));
      }
    }
  }

  // 7) Grow the private rule base -------------------------------------------
  let learned: LearnedSummary | undefined;
  if (!dryRun && (options.learn || config.translation.memory.learnFromEdits)) {
    learned = await learnFromHistory(project, {
      includeGlossary: options.learn ?? false,
      locales,
    });
    if (options.exportRules) {
      const exported = await exportRulesToFiles(project);
      written.push(...exported.map((file) => path.relative(project.rootDir, file)));
    }
  }

  // 8) Report ---------------------------------------------------------------
  const finishedAt = new Date();
  const summary = summarizeIssues(issues);
  const memory = await project.store.stats();
  const report: RunReport = {
    product: PRODUCT,
    version: VERSION,
    command: options.dryRun ? 'sync --dry-run' : 'sync',
    startedAt: startedAt.toISOString(),
    finishedAt: finishedAt.toISOString(),
    durationMs: finishedAt.getTime() - startedAt.getTime(),
    rootDir: project.rootDir,
    configPath: project.configPath,
    engine: config.translation.engine,
    sourceLocale: config.sourceLocale,
    locales: analysis.locales,
    issues,
    summary: {
      keys: analysis.totals.keys,
      translated: stats.translated,
      fromMemory: stats.fromMemory,
      engineCalls: results.filter((result) => result.job.action === 'translate' && result.engine !== 'cache').length,
      fixed: stats.fixed + analysis.fixesApplied,
      written,
      errors: summary.errors,
      warnings: summary.warnings,
      infos: summary.infos,
      empty: analysis.totals.empty,
      missing: analysis.totals.missing,
    },
    scan: scanSummary,
    learned,
    memory,
    notes,
    dryRun,
  };
  if (!dryRun) {
    await project.store.addRun({
      command: report.command,
      startedAt: report.startedAt,
      finishedAt: report.finishedAt,
      engine: report.engine,
      locales,
      translated: report.summary.translated,
      fixed: report.summary.fixed,
      issues: summary.total,
      errors: summary.errors,
      warnings: summary.warnings,
      filesWritten: written,
      durationMs: report.durationMs,
      meta: learned ? { learned: { glossary: learned.glossary, corrections: learned.corrections, styles: learned.styles } } : undefined,
    });
  }
  void rulesBefore;
  return { report, analysis, extracted, written };
}

export interface LearnFromHistoryOptions {
  includeGlossary: boolean;
  locales: string[];
  /** Minimum repetitions before a glossary term is proposed (default 3). */
  minOccurrences?: number;
}

/**
 * "防返工": records what reviewers changed after machine translation and turns
 * those edits into reusable correction/style/glossary rules.
 */
export async function learnFromHistory(project: ProjectContext, options: LearnFromHistoryOptions): Promise<LearnedSummary> {
  const { config } = project;
  const details: LearnedSummary['details'] = [];
  const rules: RuleRow[] = [];

  // Correction rules from machine output vs current catalog values.
  for (const locale of options.locales) {
    const suggestions = await project.store.listSuggestions({ locale, limit: 5000 });
    if (suggestions.length === 0) continue;
    // Suggestions are keyed by the *source text* hash, so rebuild the same hash
    // from the source catalog and compare it with what the reviewer kept.
    const currentValues = new Map<string, string>();
    const sourceTextByHash = new Map<string, string>();
    for (const [sourcePath, sourceFile] of project.sourceFiles) {
      const target = fileFor(project, sourcePath, locale);
      for (const sourceEntry of sourceFile.entries) {
        const sourceText = sourceEntry.value ?? '';
        if (sourceText.length === 0) continue;
        const hash = sha256(`${config.sourceLocale}\u0000${sourceText}`);
        sourceTextByHash.set(hash, sourceText);
        const translated = target?.index.get(sourceEntry.key);
        if (translated?.value) currentValues.set(hash, translated.value);
      }
    }
    const edits: Array<{ machine: string; human: string; source: string; locale: string }> = [];
    for (const suggestion of suggestions) {
      const current = currentValues.get(suggestion.sourceHash);
      if (!current) continue;
      if (current === suggestion.machine) continue;
      const source = sourceTextByHash.get(suggestion.sourceHash) ?? suggestion.source;
      edits.push({ machine: suggestion.machine, human: current, source, locale });
    }
    if (edits.length > 0) {
      const learned = learnCorrectionsFromEdits(edits, { minOccurrences: 2 });
      rules.push(...learned);
      for (const rule of learned) {
        details.push({ kind: rule.kind, pattern: rule.pattern, value: safeString(rule.value), locale: rule.locale ?? null, confidence: rule.confidence });
      }
    }
  }

  // Style rules from the current, already human-approved catalogs.
  const styleSamples: Array<{ value: string; source: string; locale: string }> = [];
  for (const locale of options.locales) {
    for (const [, file] of project.files) {
      if (file.locale !== locale) continue;
      for (const entry of file.entries) {
        if (!entry.value) continue;
        const source = sourceByKey(project, entry.key);
        styleSamples.push({ value: entry.value, source, locale });
      }
    }
  }
  const styleRules = learnStylesFromEntries(styleSamples);
  rules.push(...styleRules);
  for (const rule of styleRules) {
    details.push({ kind: rule.kind, pattern: rule.pattern, value: rule.value, locale: rule.locale ?? null, confidence: rule.confidence });
  }

  // Glossary candidates from trusted pairs (explicitly requested).
  const glossaryRules: RuleRow[] = [];
  if (options.includeGlossary) {
    for (const locale of options.locales) {
      const pairs = await project.store.pairs(config.sourceLocale, locale);
      const usable = pairs.filter((row) => row.target.trim().length > 0 && row.confidence >= 0.5);
      if (usable.length < 5) continue;
      const learned = learnGlossaryFromPairs(usable, locale, {
        minOccurrences: options.minOccurrences ?? 3,
        minConsistency: 0.7,
        sourceLocale: config.sourceLocale,
      });
      glossaryRules.push(...learned);
      for (const rule of learned) {
        details.push({ kind: rule.kind, pattern: rule.pattern, value: safeString(rule.value), locale: rule.locale ?? null, confidence: rule.confidence });
      }
    }
  }
  rules.push(...glossaryRules);

  const added = rules.length > 0 ? await project.store.addRules(rules) : 0;
  return {
    glossary: glossaryRules.length,
    corrections: rules.filter((rule) => rule.kind === 'correction').length,
    styles: rules.filter((rule) => rule.kind === 'style').length,
    details: details.slice(0, 200),
  };
}

function sourceByKey(project: ProjectContext, key: string): string {
  for (const file of project.sourceFiles.values()) {
    const entry = file.index.get(key);
    if (entry?.value) return entry.value;
  }
  return '';
}

function safeString(value: string): string {
  if (value.trim().startsWith('{')) {
    try {
      const parsed = JSON.parse(value) as { target?: string; to?: string };
      return parsed.target ?? parsed.to ?? value;
    } catch {
      return value;
    }
  }
  return value;
}

