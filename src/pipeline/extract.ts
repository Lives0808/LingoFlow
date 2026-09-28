import path from 'node:path';
import type { HardcodedCandidate } from '../core/types';
import { applyRewrites, ensureImport, renderReplacement, type Rewrite } from '../core/code/rewrite';
import { slugify } from '../core/utils/text';
import { readText, writeAtomic } from '../core/utils/fsx';
import { mergeValues, saveCatalog } from '../core/catalog/catalog';
import type { ProjectContext } from './context';
import { scanProject } from './scan';

export interface ExtractOptions {
  dryRun: boolean;
  minConfidence: number;
  writeSourceCatalog: boolean;
  /** Candidates from a previous scan; otherwise the project is scanned again. */
  candidates?: HardcodedCandidate[];
  files?: string[];
}

export interface ExtractedKey {
  key: string;
  text: string;
  file: string;
  line: number;
  kind: HardcodedCandidate['kind'];
}

export interface ExtractResult {
  keys: ExtractedKey[];
  files: Array<{ path: string; rewrites: number }>;
  skipped: HardcodedCandidate[];
  dryRun: boolean;
}

/**
 * Turns hardcoded UI strings into catalog keys and writes `t('key')` calls back
 * into the source files. Dry-run by default from the CLI.
 */
export async function extractHardcoded(project: ProjectContext, options: ExtractOptions): Promise<ExtractResult> {
  const { config } = project;
  const candidates =
    options.candidates ??
    (await scanProject(config, project.rootDir, { files: options.files })).hardcoded;
  const accepted = candidates.filter((candidate) => candidate.confidence >= options.minConfidence);
  const skipped = candidates.filter((candidate) => candidate.confidence < options.minConfidence);

  const sourceFile = [...project.sourceFiles.values()][0];
  const existingByValue = new Map<string, string>();
  const usedKeys = new Set<string>();
  if (sourceFile) {
    for (const entry of sourceFile.entries) {
      if (entry.value !== null) existingByValue.set(entry.value, entry.key);
      usedKeys.add(entry.key);
    }
  }

  const keyByText = new Map<string, string>();
  const extracted: ExtractedKey[] = [];
  const newEntries: Array<{ key: string; value: string }> = [];

  for (const candidate of accepted) {
    let key = existingByValue.get(candidate.text) ?? keyByText.get(candidate.text);
    if (!key) {
      const base = candidate.key || slugify(candidate.text);
      key = base;
      let suffix = 2;
      while (usedKeys.has(key)) {
        key = `${base}${suffix}`;
        suffix += 1;
      }
      usedKeys.add(key);
      keyByText.set(candidate.text, key);
      existingByValue.set(candidate.text, key);
      newEntries.push({ key, value: candidate.text });
      extracted.push({ key, text: candidate.text, file: candidate.file, line: candidate.line, kind: candidate.kind });
    } else {
      extracted.push({ key, text: candidate.text, file: candidate.file, line: candidate.line, kind: candidate.kind });
    }
  }

  // Apply rewrites per file (from the end so offsets stay valid).
  const byFile = new Map<string, HardcodedCandidate[]>();
  for (const candidate of accepted) {
    const list = byFile.get(candidate.file) ?? [];
    list.push(candidate);
    byFile.set(candidate.file, list);
  }

  const writtenFiles: ExtractResult['files'] = [];
  for (const [file, fileCandidates] of byFile) {
    const absolute = path.resolve(project.rootDir, file);
    let content: string;
    try {
      content = await readText(absolute);
    } catch {
      continue;
    }
    const rewrites: Rewrite[] = [];
    for (const candidate of fileCandidates) {
      const key = existingByValue.get(candidate.text) as string;
      rewrites.push({
        start: candidate.start,
        end: candidate.end,
        replacement: renderReplacement({ kind: candidate.kind, key }, {
          callTemplate: config.code.hardcoded.rewrite.callTemplate,
          jsxBraces: config.code.hardcoded.rewrite.jsxBraces,
        }),
      });
    }
    const result = applyRewrites(content, rewrites);
    let next = result.content;
    if (config.code.hardcoded.rewrite.importStatement) {
      next = ensureImport(next, config.code.hardcoded.rewrite.importStatement);
    }
    if (!options.dryRun && result.applied > 0) {
      await writeAtomic(absolute, next, { backup: config.write.backup });
    }
    writtenFiles.push({ path: file, rewrites: result.applied });
  }

  // Grow the source catalog so the extracted strings become translatable keys.
  if (options.writeSourceCatalog && !options.dryRun && newEntries.length > 0 && sourceFile) {
    const merged = mergeValues(
      sourceFile,
      new Map(newEntries.map((entry) => [entry.key, entry.value])),
      { sortKeys: config.write.sortKeys },
    );
    sourceFile.entries = merged;
    sourceFile.index = new Map(merged.map((entry) => [entry.key, entry]));
    await saveCatalog(sourceFile, config, merged, { backup: config.write.backup });
  }

  return { keys: extracted, files: writtenFiles, skipped, dryRun: options.dryRun };
}
