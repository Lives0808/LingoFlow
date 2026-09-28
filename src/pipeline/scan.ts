import path from 'node:path';
import type { HardcodedCandidate, KeyUsage, ScanResult } from '../core/types';
import type { ResolvedConfig } from '../core/config/schema';
import { expandGlobs, normalizePath, relativeTo } from '../core/utils/fsx';
import { readText } from '../core/utils/fsx';
import { scanSource } from '../core/code/scan';

export interface ScanOptions {
  /** Restrict to specific files (watch mode). */
  files?: string[];
}

/** Scans the project's source files for translation keys and hardcoded copy. */
export async function scanProject(config: ResolvedConfig, rootDir: string, options: ScanOptions = {}): Promise<ScanResult> {
  const files =
    options.files && options.files.length > 0
      ? options.files.map((file) => normalizePath(file))
      : await expandGlobs(config.code.include, { cwd: rootDir, ignore: config.code.exclude });
  const usages: KeyUsage[] = [];
  const dynamicKeys: string[] = [];
  const hardcoded: HardcodedCandidate[] = [];
  let scanned = 0;

  for (const relative of files) {
    const absolute = path.isAbsolute(relative) ? relative : path.resolve(rootDir, relative);
    let text: string;
    try {
      text = await readText(absolute);
    } catch {
      continue;
    }
    const displayPath = normalizePath(path.isAbsolute(relative) ? relativeTo(rootDir, relative) : relative);
    const jsx = /\.[jt]sx$/u.test(displayPath) || /\.(vue|svelte|astro)$/u.test(displayPath);
    const result = scanSource(text, displayPath, {
      calls: config.code.calls,
      components: config.code.components,
      attributes: config.code.hardcoded.attributes,
      minWords: config.code.hardcoded.minWords,
      ignorePatterns: config.code.hardcoded.ignore,
      jsx,
      collectHardcoded: config.code.hardcoded.mode !== 'off',
    });
    usages.push(...result.usages);
    dynamicKeys.push(...result.dynamicKeys);
    hardcoded.push(...result.hardcoded);
    scanned += 1;
  }

  return {
    filesScanned: scanned,
    usages,
    usedKeys: [...new Set(usages.map((usage) => usage.key))].sort(),
    hardcoded,
    dynamicKeys: [...new Set(dynamicKeys)].sort(),
  };
}

export interface KeyCoverage {
  undefinedKeys: string[];
  unusedKeys: string[];
  usageByKey: Map<string, KeyUsage[]>;
}

/** Compares keys used in code with keys defined in the source catalog. */
export function compareKeyCoverage(scan: ScanResult, definedKeys: Iterable<string>): KeyCoverage {
  const defined = new Set(definedKeys);
  const usageByKey = new Map<string, KeyUsage[]>();
  for (const usage of scan.usages) {
    const list = usageByKey.get(usage.key) ?? [];
    list.push(usage);
    usageByKey.set(usage.key, list);
  }
  const undefinedKeys = [...usageByKey.keys()].filter((key) => !defined.has(key) && !key.startsWith('*')).sort();
  const unusedKeys = [...defined].filter((key) => !usageByKey.has(key)).sort();
  return { undefinedKeys, unusedKeys, usageByKey };
}
