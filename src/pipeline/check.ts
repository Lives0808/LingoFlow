import type { Issue } from '../core/types';
import { VERSION, PRODUCT } from '../version';
import { summarizeIssues } from '../core/validate';
import { fixEntry } from '../core/fix';
import { alignCatalog, mergeValues, saveCatalog } from '../core/catalog/catalog';
import { fileFor, type ProjectContext } from './context';
import { analyzeLocales } from './analyze';
import type { RunReport } from '../report/model';

export interface CheckOptions {
  project: ProjectContext;
  locales?: string[];
  /** Apply deterministic fixes. */
  fix?: boolean;
  /** Persist fixed values to disk. Implies nothing when false. */
  write?: boolean;
  sampleLimit?: number;
}

/** `check` / `fix` / `report`: validate the current catalogs (optionally repairing them). */
export async function runCheck(options: CheckOptions): Promise<{ report: RunReport; written: string[] }> {
  const { project } = options;
  const { config } = project;
  const locales = options.locales ?? config.locales.filter((locale) => locale !== config.sourceLocale);
  const written: string[] = [];
  const fixer = options.fix
    ? (input: { key: string; locale: string; source: string; target: string; entry: import('../core/types').CatalogEntry }) => {
        const outcome = fixEntry({ config, ruleSet: project.ruleSet, sourceLocale: config.sourceLocale }, input);
        return { text: outcome.text, fixes: outcome.fixes };
      }
    : undefined;

  const startedAt = new Date();
  const analysis = await analyzeLocales(project, { locales, sampleLimit: options.sampleLimit, fixer });

  if (options.fix && options.write) {
    for (const locale of locales) {
      for (const catalogPath of project.sourceFiles.keys()) {
        const file = fileFor(project, catalogPath, locale);
        if (!file) continue;
        const result = await saveCatalog(file, config, file.entries, { backup: config.write.backup });
        if (result.written) written.push(file.target.relativePath);
      }
    }
  }

  const issues: Issue[] = [...analysis.issues];
  const finishedAt = new Date();
  const summary = summarizeIssues(issues);
  const memory = await project.store.stats();
  const report: RunReport = {
    product: PRODUCT,
    version: VERSION,
    command: options.fix ? 'fix' : 'check',
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
      translated: 0,
      fromMemory: 0,
      engineCalls: 0,
      fixed: analysis.fixesApplied,
      written,
      errors: summary.errors,
      warnings: summary.warnings,
      infos: summary.infos,
      empty: analysis.totals.empty,
      missing: analysis.totals.missing,
    },
    memory,
    notes: options.fix ? [`${analysis.fixesApplied} value(s) adjusted by automatic fixes`] : [],
    dryRun: !options.write,
  };

  if (options.write) {
    await project.store.addRun({
      command: report.command,
      startedAt: report.startedAt,
      finishedAt: report.finishedAt,
      engine: report.engine,
      locales,
      translated: 0,
      fixed: report.summary.fixed,
      issues: summary.total,
      errors: summary.errors,
      warnings: summary.warnings,
      filesWritten: written,
      durationMs: report.durationMs,
    });
  }

  return { report, written };
}

/** Key parity / alignment report across locales, optionally filled and pruned. */
export async function runAlign(options: {
  project: ProjectContext;
  locales?: string[];
  fill?: boolean;
  prune?: boolean;
  write?: boolean;
}): Promise<{ report: RunReport; written: string[] }> {
  const { project } = options;
  const { config } = project;
  const locales = options.locales ?? config.locales.filter((locale) => locale !== config.sourceLocale);
  const written: string[] = [];
  const issues: Issue[] = [];
  const startedAt = new Date();

  for (const locale of locales) {
    for (const [catalogPath, sourceFile] of project.sourceFiles) {
      const file = fileFor(project, catalogPath, locale);
      if (!file) continue;
      const alignment = alignCatalog(sourceFile, file);
      for (const key of alignment.missing) {
        issues.push({
          code: 'missing',
          severity: 'error',
          locale,
          key,
          message: `Missing in ${locale}`,
          fixable: Boolean(options.fill),
        });
      }
      for (const key of alignment.extra) {
        issues.push({
          code: 'key.unused',
          severity: 'info',
          locale,
          key,
          message: `Key exists in ${locale} but not in the source catalog`,
          fixable: Boolean(options.prune),
        });
      }
      if (!options.write || (!options.fill && !options.prune && !config.write.keepOrder)) continue;

      const updates = new Map<string, string>();
      if (options.fill) {
        for (const key of alignment.missing) {
          const sourceEntry = sourceFile.index.get(key);
          if (sourceEntry?.value) updates.set(key, sourceEntry.value);
        }
      }
      let entries = mergeValues(file, updates, { sortKeys: config.write.sortKeys });
      if (options.prune) {
        const sourceKeys = new Set(sourceFile.entries.map((entry) => entry.key));
        entries = entries.filter((entry) => sourceKeys.has(entry.key));
      }
      if (config.write.keepOrder) {
        const order = new Map(sourceFile.entries.map((entry, index) => [entry.key, index]));
        const rank = (key: string): number => order.get(key) ?? Number.MAX_SAFE_INTEGER;
        entries = [...entries].sort((a, b) => rank(a.key) - rank(b.key));
      }
      file.entries = entries;
      file.index = new Map(entries.map((entry) => [entry.key, entry]));
      const result = await saveCatalog(file, config, entries, { backup: config.write.backup });
      if (result.written) written.push(file.target.relativePath);
    }
  }

  const finishedAt = new Date();
  const summary = summarizeIssues(issues);
  const analysis = await analyzeLocales(project, { locales, sampleLimit: 5 });
  const report: RunReport = {
    product: PRODUCT,
    version: VERSION,
    command: 'align',
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
      translated: 0,
      fromMemory: 0,
      engineCalls: 0,
      fixed: 0,
      written,
      errors: summary.errors,
      warnings: summary.warnings,
      infos: summary.infos,
      empty: analysis.totals.empty,
      missing: analysis.totals.missing,
    },
    memory: await project.store.stats(),
    notes: options.fill ? ['missing keys were filled with the source text (flagged as untranslated)'] : [],
    dryRun: !options.write,
  };
  return { report, written };
}
