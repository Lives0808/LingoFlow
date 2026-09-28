import path from 'node:path';
import type { RunReport } from './model';
import { renderHtmlReport } from './html';
import { renderMarkdownReport } from './markdown';
import { renderSarifReport } from './sarif';
import { ensureDir, resolveFrom, writeAtomic, writeJson } from '../core/utils/fsx';
import type { ResolvedConfig } from '../core/config/schema';

export interface ReportArtifacts {
  dir: string;
  files: string[];
}

/** Writes every report flavour next to each other so CI can pick and choose. */
export async function writeReports(
  report: RunReport,
  config: ResolvedConfig,
  rootDir: string,
  options: { notice?: string } = {},
): Promise<ReportArtifacts> {
  const dir = resolveFrom(rootDir, config.report.dir);
  await ensureDir(dir);
  const files: string[] = [];

  const html = renderHtmlReport(report, {
    theme: config.report.theme,
    title: `${config.report.title} · ${report.command}`,
    includePreview: config.report.includePreview,
    notice: options.notice,
  });
  const htmlPath = path.join(dir, 'index.html');
  await writeAtomic(htmlPath, html);
  files.push(htmlPath);

  const jsonPath = path.join(dir, 'report.json');
  await writeJson(jsonPath, report);
  files.push(jsonPath);

  const markdownPath = path.join(dir, 'report.md');
  await writeAtomic(markdownPath, renderMarkdownReport(report));
  files.push(markdownPath);

  const sarifPath = path.join(dir, 'report.sarif');
  await writeAtomic(sarifPath, renderSarifReport(report));
  files.push(sarifPath);

  return { dir, files };
}
