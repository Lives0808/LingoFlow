import type { HardcodedCandidate, Issue, KeyUsage, ScanResult } from '../core/types';
import type { LengthMetrics } from '../core/validate/length';
import type { MemoryStats } from '../core/tm/store';

export interface LocaleReport {
  locale: string;
  files: string[];
  total: number;
  translated: number;
  missing: number;
  empty: number;
  coverage: number;
  frozen: number;
  issues: Issue[];
  /** Entries used by the UI preview / length table. */
  samples: PreviewSample[];
}

export interface PreviewSample {
  key: string;
  source: string;
  target: string | null;
  widget: string;
  containerPx: number;
  chars: number;
  px: number;
  sourcePx: number;
  ratio: number;
  lines: number;
  overBudget: boolean;
  severity: 'error' | 'warn' | 'info' | 'ok';
  issues: Issue[];
  metrics?: LengthMetrics;
  /** Pseudo-localised version, used to test layout before real translation. */
  pseudo: string;
}

export interface ScanSummary {
  filesScanned: number;
  usages: KeyUsage[];
  undefinedKeys: string[];
  unusedKeys: string[];
  dynamicKeys: string[];
  hardcoded: HardcodedCandidate[];
}

export interface LearnedSummary {
  glossary: number;
  corrections: number;
  styles: number;
  details: Array<{ kind: string; pattern: string; value: string; locale: string | null; confidence: number }>;
}

export interface RunReport {
  product: string;
  version: string;
  command: string;
  startedAt: string;
  finishedAt: string;
  durationMs: number;
  rootDir: string;
  configPath: string | null;
  engine: string;
  sourceLocale: string;
  locales: LocaleReport[];
  issues: Issue[];
  summary: {
    keys: number;
    translated: number;
    fromMemory: number;
    engineCalls: number;
    fixed: number;
    written: string[];
    errors: number;
    warnings: number;
    infos: number;
    empty: number;
    missing: number;
  };
  scan?: ScanSummary;
  learned?: LearnedSummary;
  memory: MemoryStats;
  notes: string[];
  /** Present when the run was a dry run (nothing written). */
  dryRun: boolean;
}

export function severityRank(severity: Issue['severity']): number {
  return severity === 'error' ? 0 : severity === 'warn' ? 1 : 2;
}

export function issueSeverityOf(sample: PreviewSample): 'error' | 'warn' | 'info' | 'ok' {
  if (sample.overBudget) return sample.severity === 'info' ? 'warn' : sample.severity;
  return sample.issues.length > 0 ? sample.issues[0]?.severity ?? 'ok' : 'ok';
}

export function emptyScanSummary(): ScanSummary {
  return { filesScanned: 0, usages: [], undefinedKeys: [], unusedKeys: [], dynamicKeys: [], hardcoded: [] };
}

export function scanSummaryFrom(result: ScanResult): ScanSummary {
  return {
    filesScanned: result.filesScanned,
    usages: result.usages,
    undefinedKeys: [],
    unusedKeys: [],
    dynamicKeys: result.dynamicKeys,
    hardcoded: result.hardcoded,
  };
}
