/**
 * Programmatic API.
 *
 * ```ts
 * import { loadConfig, loadProject, runSync } from 'lingoflow';
 *
 * const loaded = await loadConfig({ cwd: process.cwd() });
 * const project = await loadProject({ loaded });
 * const { report } = await runSync({ project });
 * console.log(report.summary);
 * ```
 */

export { VERSION, PRODUCT, HOMEPAGE } from './version';
export { Logger, logger } from './core/logger';
export { LingoFlowError } from './core/errors';
export { loadConfig, findConfigFile, workDirOf } from './core/config/load';
export { configSchema, parseConfig } from './core/config/schema';
export type { LoadedConfig, ResolvedConfig } from './core/config/schema';
export { PRESETS, findPreset } from './core/config/presets';

export {
  resolveCatalogTargets,
  loadCatalog,
  loadAllCatalogs,
  saveCatalog,
  alignCatalog,
  mergeValues,
} from './core/catalog/catalog';
export { getFormat, detectFormat, FORMATS } from './core/catalog/formats/index';

export { tokenize, positionAt } from './core/code/tokenizer';
export { scanSource } from './core/code/scan';
export { applyRewrites, renderReplacement, ensureImport } from './core/code/rewrite';

export { openMemory } from './core/tm/index';
export type { MemoryStore } from './core/tm/store';

export { buildRuleSet, glossaryFor, dntFor, styleValue, STYLE_NAMES } from './core/rules/model';
export { protectText, restoreText, applyCorrections } from './core/rules/apply';
export { learnGlossaryFromPairs, learnCorrectionsFromEdits, learnStylesFromEntries } from './core/rules/learn';

export { createEngine, resolveEngines, describeEngines, ENGINE_IDS } from './core/translate/registry';
export type { TranslationEngine } from './core/translate/engine';
export { buildSystemPrompt } from './core/translate/prompt';

export { validateEntry, createValidationContext, summarizeIssues } from './core/validate/index';
export { comparePlaceholders, extractPlaceholders } from './core/validate/placeholders';
export { parseIcu } from './core/validate/icu';
export { checkLength } from './core/validate/length';

export { fixEntry } from './core/fix/index';
export { estimateWidth, measure } from './core/length/metrics';
export { resolveBudget, WIDGET_PRESETS } from './core/length/budget';

export { loadProject } from './pipeline/context';
export { runSync } from './pipeline/sync';
export { runCheck, runAlign } from './pipeline/check';
export { analyzeLocales } from './pipeline/analyze';
export { scanProject } from './pipeline/scan';
export { extractHardcoded } from './pipeline/extract';

export { renderHtmlReport } from './report/html';
export { renderMarkdownReport } from './report/markdown';
export { renderSarifReport } from './report/sarif';
export { writeReports } from './report/write';
export type { RunReport, LocaleReport, PreviewSample } from './report/model';
