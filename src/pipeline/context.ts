import path from 'node:path';
import type { ResolvedConfig } from '../core/config/schema';
import type { Logger } from '../core/logger';
import type { CatalogEntry, CatalogFile, CatalogTarget, RuleKind, RuleRow } from '../core/types';
import type { LoadedConfig } from '../core/config/schema';
import { loadCatalog, resolveCatalogTargets, catalogKey } from '../core/catalog/catalog';
import { buildRuleSet, emptyRuleSet, type RuleSet } from '../core/rules/model';
import { openMemory } from '../core/tm';
import type { MemoryStore } from '../core/tm/store';
import { resolveEngines, type ResolvedEngines } from '../core/translate/registry';
import { createValidationContext, type ValidationContext } from '../core/validate';
import { readTextIfExists, resolveFrom, writeJson } from '../core/utils/fsx';
import { hashOf } from '../core/utils/hash';
import { logger as defaultLogger } from '../core/logger';

export interface ProjectOptions {
  loaded: LoadedConfig;
  logger?: Logger;
  engineOverride?: Parameters<typeof resolveEngines>[1];
  skipEngines?: boolean;
}

export interface ProjectContext {
  config: ResolvedConfig;
  rootDir: string;
  configPath: string | null;
  logger: Logger;
  targets: CatalogTarget[];
  /** Source-locale catalog per catalog path. */
  sourceFiles: Map<string, CatalogFile>;
  /** Every loaded catalog keyed by `${locale}\0${path}`. */
  files: Map<string, CatalogFile>;
  /** Source catalog path -> locale -> catalog file (including the source locale). */
  bySource: Map<string, Map<string, CatalogFile>>;
  ruleSet: RuleSet;
  store: MemoryStore;
  engines: ResolvedEngines;
  validation: ValidationContext;
}

export async function loadProject(options: ProjectOptions): Promise<ProjectContext> {
  const logger = options.logger ?? defaultLogger;
  const { config, rootDir, path: configPath } = options.loaded;
  const targets = resolveCatalogTargets(config, rootDir);
  const files = new Map<string, CatalogFile>();
  const sourceFiles = new Map<string, CatalogFile>();
  const bySource = new Map<string, Map<string, CatalogFile>>();
  const groups = new Map<number, Map<string, CatalogFile>>();

  for (const target of targets) {
    const file = await loadCatalog(target, config);
    files.set(catalogKey(target), file);
    const group = groups.get(target.catalogIndex) ?? new Map<string, CatalogFile>();
    group.set(target.locale, file);
    groups.set(target.catalogIndex, group);
    if (target.locale === config.sourceLocale && !sourceFiles.has(target.path)) {
      sourceFiles.set(target.path, file);
    }
  }

  // `catalogs[]` entries share one path template, so every source catalog knows
  // its sibling file for each locale (locales/en.json -> locales/zh-CN.json).
  for (const target of targets) {
    if (target.locale !== config.sourceLocale) continue;
    const group = groups.get(target.catalogIndex);
    if (group) bySource.set(target.path, group);
  }

  const store = await openMemory(config, rootDir, logger);
  const ruleSet = await loadRuleSet(store, config, rootDir, logger);
  const engines = options.skipEngines
    ? ({ primary: null as never, fallbacks: [], pseudo: null as never } as ResolvedEngines)
    : await resolveEngines({ config, rootDir }, options.engineOverride);
  const sources = new Map<string, CatalogFile>();
  for (const file of sourceFiles.values()) sources.set(file.path, file);
  const sourceEntries = new Map<string, CatalogEntry>();
  for (const file of sourceFiles.values()) {
    for (const entry of file.entries) {
      if (!sourceEntries.has(entry.key)) sourceEntries.set(entry.key, entry);
    }
  }
  const validation = createValidationContext({ config, ruleSet, sources: sourceEntries });
  return {
    config,
    rootDir,
    configPath,
    logger,
    targets,
    sourceFiles,
    files,
    bySource,
    ruleSet,
    store,
    engines,
    validation,
  };
}

/**
 * Resolves the catalog file for a locale given the *source* catalog path
 * (e.g. `locales/en.json` + `zh-CN` -> `locales/zh-CN.json`).
 */
export function fileFor(project: ProjectContext, sourcePath: string, locale: string): CatalogFile | undefined {
  return project.bySource.get(sourcePath)?.get(locale);
}


export function sourceFileFor(project: ProjectContext, catalogPath: string): CatalogFile | undefined {
  return project.sourceFiles.get(catalogPath);
}

export interface RuleFileImportResult {
  imported: number;
  sources: string[];
}

/**
 * Imports `lingoflow.glossary.json` / `lingoflow.style.json` into the private
 * database when they change, so the files can live in git while the runtime
 * works from fast, indexed storage.
 */
export async function loadRuleSet(store: MemoryStore, config: ResolvedConfig, rootDir: string, logger: Logger): Promise<RuleSet> {
  const markerPath = path.join(resolveFrom(rootDir, config.workDir), 'rules-import.json');
  const markerText = await readTextIfExists(markerPath);
  let marker: Record<string, string> = {};
  if (markerText) {
    try {
      marker = JSON.parse(markerText) as Record<string, string>;
    } catch {
      marker = {};
    }
  }
  const sources: string[] = [];
  const newRules: RuleRow[] = [];
  let changed = false;

  const glossaryPath = resolveFrom(rootDir, config.translation.glossary.file);
  const glossaryText = await readTextIfExists(glossaryPath);
  if (glossaryText) {
    const hash = hashOf(glossaryText);
    if (marker[glossaryPath] !== hash) {
      newRules.push(...parseGlossaryFile(glossaryText, glossaryPath, logger));
      marker[glossaryPath] = hash;
      changed = true;
      sources.push(config.translation.glossary.file);
    }
  }

  const stylePath = resolveFrom(rootDir, config.translation.style.file);
  const styleText = await readTextIfExists(stylePath);
  if (styleText) {
    const hash = hashOf(styleText);
    if (marker[stylePath] !== hash) {
      newRules.push(...parseStyleFile(styleText, stylePath, logger));
      marker[stylePath] = hash;
      changed = true;
      sources.push(config.translation.style.file);
    }
  }

  if (newRules.length > 0) {
    const added = await store.addRules(newRules);
    logger.debug(`imported ${added} rule(s) from ${sources.join(', ')}`);
  }
  if (changed) {
    await writeJson(markerPath, marker);
  }
  const stored = await store.listRules({ enabled: true });
  if (stored.length === 0) return emptyRuleSet();
  return buildRuleSet(stored);
}

export function parseGlossaryFile(text: string, filePath: string, logger: Logger): RuleRow[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text) as unknown;
  } catch (error) {
    logger.warn(`Cannot parse glossary file ${filePath}: ${(error as Error).message}`);
    return [];
  }
  const rules: RuleRow[] = [];
  const now = new Date().toISOString();
  const record = (source: unknown, target: unknown, locale: string | null, options: Record<string, unknown> = {}): void => {
    if (typeof source !== 'string' || typeof target !== 'string') return;
    if (source.trim().length === 0 || target.trim().length === 0) return;
    const value = JSON.stringify({
      target,
      matchCase: options.matchCase === true,
      wholeWord: options.wholeWord !== false,
    });
    rules.push({
      kind: 'glossary',
      locale,
      pattern: source,
      value,
      priority: typeof options.priority === 'number' ? options.priority : 60,
      enabled: options.enabled !== false,
      origin: 'import',
      confidence: 1,
      note: typeof options.note === 'string' ? options.note : undefined,
      createdAt: now,
    });
  };

  if (Array.isArray(parsed)) {
    for (const item of parsed) {
      const entry = item as Record<string, unknown>;
      record(entry.source, entry.target, (entry.locale as string | null | undefined) ?? null, entry);
    }
  } else if (parsed && typeof parsed === 'object') {
    const object = parsed as Record<string, unknown>;
    if (Array.isArray(object.terms)) {
      for (const item of object.terms) {
        const entry = item as Record<string, unknown>;
        record(entry.source, entry.target, (entry.locale as string | null | undefined) ?? null, entry);
      }
    } else {
      // Simple form: { "zh-CN": { "Settings": "设置" }, "*": { "LingoFlow": "LingoFlow" } }
      for (const [locale, terms] of Object.entries(object)) {
        if (locale.startsWith('$')) continue;
        if (!terms || typeof terms !== 'object') continue;
        for (const [source, target] of Object.entries(terms as Record<string, unknown>)) {
          record(source, target, locale === '*' ? null : locale);
        }
      }
    }
  }
  return rules;
}

export function parseStyleFile(text: string, filePath: string, logger: Logger): RuleRow[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text) as unknown;
  } catch (error) {
    logger.warn(`Cannot parse style file ${filePath}: ${(error as Error).message}`);
    return [];
  }
  const rules: RuleRow[] = [];
  const now = new Date().toISOString();
  const push = (entry: Record<string, unknown>, localeHint?: string | null): void => {
    const name = typeof entry.name === 'string' ? entry.name : typeof entry.rule === 'string' ? entry.rule : null;
    const value = entry.value === undefined ? entry.set : entry.value;
    if (!name || value === undefined) return;
    rules.push({
      kind: 'style',
      locale: (entry.locale as string | undefined) ?? localeHint ?? null,
      pattern: name,
      value: typeof value === 'string' ? value : JSON.stringify(value),
      priority: typeof entry.priority === 'number' ? entry.priority : 80,
      enabled: entry.enabled !== false,
      origin: 'import',
      confidence: 1,
      note: typeof entry.note === 'string' ? entry.note : undefined,
      createdAt: now,
    });
  };
  if (Array.isArray(parsed)) {
    for (const item of parsed) push(item as Record<string, unknown>);
  } else if (parsed && typeof parsed === 'object') {
    const object = parsed as Record<string, unknown>;
    if (Array.isArray(object.rules)) {
      for (const item of object.rules) push(item as Record<string, unknown>);
    } else {
      for (const [locale, entries] of Object.entries(object)) {
        if (!entries || typeof entries !== 'object') continue;
        for (const [name, value] of Object.entries(entries as Record<string, unknown>)) {
          push({ name, value }, locale === '*' ? null : locale);
        }
      }
    }
  }
  return rules;
}

/** Exports the private rule base back to the git-friendly files. */
export async function exportRulesToFiles(project: ProjectContext): Promise<string[]> {
  const written: string[] = [];
  const glossaryRules = await project.store.listRules({ kind: 'glossary' });
  const styleRules = await project.store.listRules({ kind: 'style' });
  const dntRules = await project.store.listRules({ kind: 'dnt' });
  const correctionRules = await project.store.listRules({ kind: 'correction' });

  const glossaryPath = resolveFrom(project.rootDir, project.config.translation.glossary.file);
  const terms = glossaryRules.map((rule) => {
    const parsed = safeJson(rule.value);
    return {
      source: rule.pattern,
      target: (parsed?.target as string | undefined) ?? rule.value,
      locale: rule.locale,
      ...(parsed?.matchCase === true ? { matchCase: true } : {}),
      ...(parsed?.wholeWord === false ? { wholeWord: false } : {}),
      ...(rule.origin !== 'manual' ? { note: `${rule.origin}: ${rule.note ?? ''}`.trim() } : {}),
    };
  });
  await writeJson(glossaryPath, { terms }, { skipIfSame: true });
  written.push(glossaryPath);

  const stylePath = resolveFrom(project.rootDir, project.config.translation.style.file);
  await writeJson(
    stylePath,
    {
      rules: styleRules.map((rule) => ({ name: rule.pattern, value: rule.value, locale: rule.locale, enabled: rule.enabled })),
    },
    { skipIfSame: true },
  );
  written.push(stylePath);

  const correctionsPath = path.join(resolveFrom(project.rootDir, project.config.workDir), 'corrections.json');
  await writeJson(
    correctionsPath,
    {
      rules: correctionRules.map((rule) => {
        const parsed = safeJson(rule.value);
        return {
          locale: rule.locale,
          pattern: rule.pattern,
          replacement: (parsed?.to as string | undefined) ?? rule.value,
          regex: parsed?.regex === true,
          enabled: rule.enabled,
          confidence: rule.confidence,
          note: rule.note,
        };
      }),
      doNotTranslate: dntRules.map((rule) => ({ locale: rule.locale, pattern: rule.pattern, replacement: rule.value })),
    },
    { skipIfSame: true },
  );
  written.push(correctionsPath);
  return written;
}

function safeJson(value: string): Record<string, unknown> | null {
  if (!value.trim().startsWith('{')) return null;
  try {
    return JSON.parse(value) as Record<string, unknown>;
  } catch {
    return null;
  }
}
