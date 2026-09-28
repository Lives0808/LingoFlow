import path from 'node:path';
import type { ResolvedConfig } from '../config/schema';
import type { CatalogEntry, CatalogFile, CatalogTarget, DecodedCatalog, FormatId } from '../types';
import { LingoFlowError } from '../errors';
import { exists, normalizePath, readText, relativeTo, resolveFrom, writeAtomic, type WriteResult } from '../utils/fsx';
import { sha256 } from '../utils/hash';
import { detectEol, detectFormat, getFormat, type FormatContext } from './formats/index';
import { displayKey } from './keys';

export type { CatalogTarget };

export function resolveCatalogTargets(config: ResolvedConfig, rootDir: string): CatalogTarget[] {
  const targets: CatalogTarget[] = [];
  for (const [catalogIndex, catalog] of config.catalogs.entries()) {
    const locales = catalog.locale ? [catalog.locale] : config.locales;
    for (const locale of locales) {
      if (catalog.locale && catalog.locale !== locale) continue;
      const relative = catalog.path.replace(/\{locale\}/gu, locale);
      const absolute = resolveFrom(rootDir, relative);
      const format = catalog.format === 'auto' ? detectFormat(absolute) : catalog.format;
      targets.push({
        locale,
        catalogIndex,
        path: absolute,
        relativePath: normalizePath(relative),
        format,
        nesting: catalog.nesting,
        keySeparator: catalog.keySeparator,
      });
    }
  }
  return targets;
}

export function targetsForLocale(targets: CatalogTarget[], locale: string): CatalogTarget[] {
  return targets.filter((target) => target.locale === locale);
}

export function formatContextFor(target: CatalogTarget, config: ResolvedConfig, previous?: DecodedCatalog | null, raw?: string): FormatContext {
  return {
    locale: target.locale,
    path: target.path,
    keySeparator: target.keySeparator,
    nesting: target.nesting,
    indent: config.write.indent,
    eol: raw === undefined ? (config.write.eol === 'crlf' ? 'crlf' : 'lf') : detectEol(raw, config.write.eol),
    previous: previous ?? null,
  };
}

export async function loadCatalog(target: CatalogTarget, config: ResolvedConfig): Promise<CatalogFile> {
  const format = getFormat(target.format);
  const raw = (await exists(target.path)) ? await readText(target.path) : null;
  if (raw === null) {
    return {
      locale: target.locale,
      path: target.path,
      format: target.format,
      target,
      entries: [],
      index: new Map(),
      meta: {},
      contentHash: sha256(''),
      raw: '',
    };
  }
  let decoded: DecodedCatalog;
  try {
    decoded = format.decode(raw, formatContextFor(target, config, undefined, raw));
  } catch (error) {
    throw new LingoFlowError(`Cannot parse ${target.relativePath}: ${(error as Error).message}`, {
      code: 'CATALOG_PARSE_ERROR',
      hint: 'Fix the file syntax, or set a different format for this catalog in lingoflow.config.json.',
    });
  }
  const index = new Map<string, CatalogEntry>();
  const entries: CatalogEntry[] = [];
  for (const entry of decoded.entries) {
    if (index.has(entry.key)) {
      // Duplicate keys can only come from tolerant parsers; last one wins.
      const existing = index.get(entry.key) as CatalogEntry;
      existing.value = entry.value;
      continue;
    }
    index.set(entry.key, entry);
    entries.push(entry);
  }
  return {
    locale: target.locale,
    path: target.path,
    format: target.format,
    target,
    entries,
    index,
    meta: decoded.meta ?? {},
    contentHash: sha256(raw),
    raw,
  };
}

export async function loadAllCatalogs(
  targets: CatalogTarget[],
  config: ResolvedConfig,
): Promise<Map<string, CatalogFile>> {
  const files = new Map<string, CatalogFile>();
  for (const target of targets) {
    const key = `${target.locale}\u0000${target.path}`;
    const file = await loadCatalog(target, config);
    files.set(key, file);
  }
  return files;
}

export function catalogKey(target: CatalogTarget): string {
  return `${target.locale}\u0000${target.path}`;
}

/** Merges updated values back into the ordered entry list of a catalog file. */
export function mergeValues(file: CatalogFile, updates: Map<string, string | null>, options: { sortKeys?: boolean } = {}): CatalogEntry[] {
  const entries: CatalogEntry[] = [];
  const seen = new Set<string>();
  for (const entry of file.entries) {
    const update = updates.get(entry.key);
    if (update !== undefined) {
      entries.push({ ...entry, value: update });
      seen.add(entry.key);
    } else {
      entries.push(entry);
      seen.add(entry.key);
    }
  }
  for (const [key, value] of updates) {
    if (seen.has(key)) continue;
    entries.push({ key, value });
    seen.add(key);
  }
  if (options.sortKeys) entries.sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
  return entries;
}

export interface SaveOptions {
  /** Extra entries appended when missing (e.g. aligning a target to the source). */
  appendMissing?: CatalogEntry[];
  sortKeys?: boolean;
  backup?: boolean;
}

export async function saveCatalog(
  file: CatalogFile,
  config: ResolvedConfig,
  entries: CatalogEntry[],
  options: SaveOptions = {},
): Promise<WriteResult & { content: string }> {
  const format = getFormat(file.format);
  const ctx = formatContextFor(file.target, config, { entries: file.entries, meta: file.meta }, file.raw || undefined);
  const content = format.encode({ entries, meta: file.meta }, ctx);
  const wrote = await writeAtomic(file.path, content, {
    backup: options.backup ?? config.write.backup,
    expect: file.raw ? file.raw : undefined,
  });
  file.entries = entries;
  file.index = new Map(entries.map((entry) => [entry.key, entry]));
  file.contentHash = sha256(content);
  file.raw = content;
  return { ...wrote, content };
}

export interface AlignmentReport {
  locale: string;
  missing: string[];
  extra: string[];
  empty: string[];
  total: number;
  sourceTotal: number;
  coverage: number;
}

export function alignCatalog(source: CatalogFile, target: CatalogFile): AlignmentReport {
  const sourceKeys = source.entries.map((entry) => entry.key);
  const targetKeys = new Set(target.entries.map((entry) => entry.key));
  const missing = sourceKeys.filter((key) => !targetKeys.has(key));
  const sourceSet = new Set(sourceKeys);
  const extra = target.entries.filter((entry) => !sourceSet.has(entry.key)).map((entry) => entry.key);
  const empty = target.entries.filter((entry) => entry.value === null || entry.value === '').map((entry) => entry.key);
  const translated = sourceKeys.filter((key) => {
    const entry = target.index.get(key);
    return entry?.value !== undefined && entry.value !== null && entry.value !== '';
  }).length;
  return {
    locale: target.locale,
    missing,
    extra,
    empty,
    total: target.entries.length,
    sourceTotal: sourceKeys.length,
    coverage: sourceKeys.length === 0 ? 1 : translated / sourceKeys.length,
  };
}

export function formatKeyForDisplay(key: string): string {
  return displayKey(key);
}

export function keySetOf(file: CatalogFile | undefined): Set<string> {
  return new Set(file?.entries.map((entry) => entry.key) ?? []);
}

export function entryAt(file: CatalogFile | undefined, key: string): CatalogEntry | undefined {
  return file?.index.get(key);
}

export function catalogRelativePath(file: CatalogFile, rootDir: string): string {
  return relativeTo(rootDir, file.path);
}

export function catalogBasename(file: CatalogFile): string {
  return path.basename(file.path);
}
