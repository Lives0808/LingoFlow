import { spawn } from 'node:child_process';
import path from 'node:path';
import type { Command } from 'commander';
import { loadConfig } from '../core/config/load';
import type { LoadedConfig, ResolvedConfig } from '../core/config/schema';
import { logger } from '../core/logger';
import type { Issue, Severity } from '../core/types';
import { LingoFlowError } from '../core/errors';
import { summarizeIssues } from '../core/validate';
import type { RunReport } from '../report/model';
import { writeReports } from '../report/write';

export interface GlobalOptions {
  config?: string;
  cwd: string;
  json: boolean;
  quiet: boolean;
  verbose: boolean;
  color: boolean;
}

export function globalOptions(program: Command): GlobalOptions {
  const options = program.opts();
  return {
    config: options.config as string | undefined,
    cwd: (options.cwd as string | undefined) ?? process.cwd(),
    json: Boolean(options.json),
    quiet: Boolean(options.quiet),
    verbose: Boolean(options.verbose),
    color: options.color !== false,
  };
}

export function applyGlobalOptions(options: GlobalOptions): void {
  logger.configure({
    json: options.json,
    level: options.quiet ? 'warn' : options.verbose ? 'debug' : 'info',
    color: options.color,
  });
}

export async function loadProjectConfig(options: GlobalOptions, overrides?: Partial<ResolvedConfig>): Promise<LoadedConfig> {
  const loaded = await loadConfig({ cwd: options.cwd, configPath: options.config, overrides });
  return loaded;
}

export function relative(rootDir: string, filePath: string): string {
  const value = path.relative(rootDir, filePath) || '.';
  return value.split(path.sep).join('/');
}

export function colorFor(severity: Severity): (text: string) => string {
  return (text: string) => text;
}

export function printIssueSummary(issues: Issue[], rootDir: string): void {
  const summary = summarizeIssues(issues);
  if (issues.length === 0) {
    logger.success('No issues found');
    return;
  }
  const grouped = new Map<string, Issue[]>();
  for (const issue of issues) {
    const list = grouped.get(issue.code) ?? [];
    list.push(issue);
    grouped.set(issue.code, list);
  }
  const rows = [...grouped]
    .sort((a, b) => b[1].length - a[1].length)
    .map(([code, list]) => [code, String(list.length), list[0]?.severity ?? '']);
  logger.table(rows, { head: ['Code', 'Count', 'Severity'] });
  logger.info('');
  logger.info(`${summary.errors} error(s), ${summary.warnings} warning(s), ${summary.infos} info(s)`);
  void rootDir;
}

export function exitCodeForIssues(issues: Issue[], failOn: 'error' | 'warn' | 'none'): number {
  if (failOn === 'none') return 0;
  const summary = summarizeIssues(issues);
  if (failOn === 'error') return summary.errors > 0 ? 1 : 0;
  return summary.errors > 0 || summary.warnings > 0 ? 1 : 0;
}

export function reportToJson(report: RunReport): unknown {
  return {
    product: report.product,
    version: report.version,
    command: report.command,
    startedAt: report.startedAt,
    finishedAt: report.finishedAt,
    durationMs: report.durationMs,
    engine: report.engine,
    sourceLocale: report.sourceLocale,
    summary: report.summary,
    locales: report.locales.map((locale) => ({
      locale: locale.locale,
      coverage: Number(locale.coverage.toFixed(4)),
      total: locale.total,
      translated: locale.translated,
      missing: locale.missing,
      empty: locale.empty,
      frozen: locale.frozen,
      issues: locale.issues.length,
    })),
    issues: report.issues,
    memory: report.memory,
    learned: report.learned,
    scan: report.scan
      ? {
          filesScanned: report.scan.filesScanned,
          undefinedKeys: report.scan.undefinedKeys,
          unusedKeys: report.scan.unusedKeys,
          dynamicKeys: report.scan.dynamicKeys,
          hardcoded: report.scan.hardcoded.length,
        }
      : undefined,
    written: report.summary.written,
    notes: report.notes,
    dryRun: report.dryRun,
  };
}

/** Opens a file with the OS default handler. */
export async function openPath(target: string): Promise<void> {
  const platform = process.platform;
  const command = platform === 'darwin' ? 'open' : platform === 'win32' ? 'cmd' : 'xdg-open';
  const args = platform === 'win32' ? ['/c', 'start', '', target] : [target];
  await new Promise<void>((resolve) => {
    const child = spawn(command, args, { stdio: 'ignore', detached: true });
    child.on('error', () => resolve());
    child.on('close', () => resolve());
    child.unref();
    setTimeout(resolve, 300);
  });
}

export function parseList(value: string | undefined): string[] | undefined {
  if (!value) return undefined;
  const list = value
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
  return list.length > 0 ? list : undefined;
}

export function parseKeyList(value: string | undefined): string[] | undefined {
  if (!value) return undefined;
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

export function requireNonEmpty(value: string | undefined, what: string, hint?: string): string {
  if (!value || value.trim().length === 0) {
    throw new LingoFlowError(`${what} is required`, { code: 'MISSING_ARGUMENT', hint });
  }
  return value;
}

export function severityColor(severity: Severity): 'red' | 'yellow' | 'cyan' {
  return severity === 'error' ? 'red' : severity === 'warn' ? 'yellow' : 'cyan';
}

export interface FinishOptions {
  report: RunReport;
  config: ResolvedConfig;
  rootDir: string;
  json: boolean;
  open?: boolean;
  writeReports?: boolean;
  failOn?: 'error' | 'warn' | 'none';
  notice?: string;
}

/** Writes report artifacts, prints the summary and computes the exit code. */
export async function finishRun(options: FinishOptions): Promise<number> {
  const { report } = options;
  const failOn = options.failOn ?? options.config.report.failOn;
  const artifacts = options.writeReports === false
    ? null
    : await writeReports(report, options.config, options.rootDir, { notice: options.notice });

  if (options.json) {
    logger.payload({ ...(reportToJson(report) as Record<string, unknown>), reports: artifacts?.files ?? [] });
    return options.failOn === undefined && failOn === 'none' ? 0 : exitCodeForIssues(report.issues, failOn);
  }

  logger.info('');
  for (const locale of report.locales) {
    const errors = locale.issues.filter((issue) => issue.severity === 'error').length;
    const warnings = locale.issues.filter((issue) => issue.severity === 'warn').length;
    logger.info(
      `${locale.locale.padEnd(8)} ${(locale.coverage * 100).toFixed(1).padStart(5)}% coverage · ${String(locale.missing).padStart(4)} missing · ${errors}E ${warnings}W`,
    );
  }
  const summary = summarizeIssues(report.issues);
  logger.info('');
  logger.info(
    `translated ${report.summary.translated} · from memory ${report.summary.fromMemory} · fixed ${report.summary.fixed} · ${report.summary.written.length} file(s) written`,
  );
  logger.info(`${summary.errors} error(s), ${summary.warnings} warning(s), ${summary.infos} info(s)`);
  if (artifacts) {
    logger.info(`report: ${relative(options.rootDir, path.join(artifacts.dir, 'index.html'))}`);
  }
  if (report.learned) {
    logger.info(
      `learned: ${report.learned.glossary} glossary · ${report.learned.corrections} correction · ${report.learned.styles} style rule(s)`,
    );
  }
  if (options.open && artifacts) {
    await openPath(path.join(artifacts.dir, 'index.html'));
  }
  return exitCodeForIssues(report.issues, failOn);
}
