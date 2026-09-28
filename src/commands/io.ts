import path from 'node:path';
import type { Command } from 'commander';
import { logger } from '../core/logger';
import { fileFor, loadProject } from '../pipeline/context';
import { runSync } from '../pipeline/sync';
import { parseXliff, renderXliff } from '../core/io/xliff';
import { mergeValues, saveCatalog } from '../core/catalog/catalog';
import { ensureDir, readText, writeAtomic, writeJson } from '../core/utils/fsx';
import { applyGlobalOptions, globalOptions, parseList } from './shared';

export function registerIoCommands(program: Command): void {
  program
    .command('export')
    .description('Export catalogs for translators (xliff | csv | json)')
    .option('--format <format>', 'xliff | csv | json', 'xliff')
    .requiredOption('--out <dir>', 'output directory')
    .option('--locales <list>', 'comma separated locales')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const locales = parseList(options.locales as string | undefined) ?? loaded.config.locales.filter((locale) => locale !== loaded.config.sourceLocale);
      const outDir = path.resolve(loaded.rootDir, options.out as string);
      await ensureDir(outDir);
      const format = String(options.format ?? 'xliff');
      const written: string[] = [];

      for (const locale of locales) {
        const units = collectUnits(project, locale);
        const file = path.join(outDir, `messages.${locale}.${format === 'xliff' ? 'xlf' : format}`);
        if (format === 'xliff') {
          await writeAtomic(
            file,
            renderXliff({ sourceLanguage: loaded.config.sourceLocale, targetLanguage: locale, units }),
          );
        } else if (format === 'csv') {
          const header = 'key,source,target,comment,maxLength,status';
          const rows = units.map((unit) =>
            [unit.id, unit.source, unit.target, unit.comment ?? '', String(maxLengthOf(project, unit.id) ?? ''), unit.target.trim().length > 0 ? 'translated' : 'missing']
              .map((cell) => quoteCsv(cell))
              .join(','),
          );
          await writeAtomic(file, `${[header, ...rows].join('\n')}\n`);
        } else {
          await writeJson(file, { locale, sourceLocale: loaded.config.sourceLocale, units });
        }
        written.push(path.relative(loaded.rootDir, file));
      }
      logger.success(`Exported ${written.length} file(s): ${written.join(', ')}`);
      await project.store.close();
    });

  program
    .command('import')
    .description('Import translations from an xliff / csv / json file')
    .requiredOption('--in <file>', 'input file')
    .option('--locale <locale>', 'target locale (required for csv/xliff without target-language)')
    .option('--write', 'apply to the catalogs (default: dry run)', false)
    .option('--json', 'machine readable output')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const filePath = path.resolve(loaded.rootDir, options.in as string);
      const text = await readText(filePath);
      const extension = path.extname(filePath).toLowerCase();
      let locale = (options.locale as string | undefined) ?? '';
      const updates = new Map<string, string>();

      if (extension === '.xlf' || extension === '.xliff') {
        const parsed = parseXliff(text);
        locale = locale || parsed.targetLanguage;
        for (const unit of parsed.units) {
          if (unit.target.trim().length > 0) updates.set(unit.id, unit.target);
        }
      } else if (extension === '.csv') {
        const lines = text.replace(/\r\n?/gu, '\n').split('\n').filter((line) => line.trim().length > 0);
        const header = parseCsvLine(lines[0] ?? '');
        const keyIndex = header.findIndex((cell) => /^(key|id|msgid)$/iu.test(cell.trim()));
        const targetIndex = locale ? header.findIndex((cell) => cell.trim().toLowerCase().replace(/_/gu, '-') === locale.toLowerCase()) : -1;
        const fallbackTarget = targetIndex >= 0 ? targetIndex : header.length - 1;
        for (const line of lines.slice(1)) {
          const cells = parseCsvLine(line);
          const key = cells[keyIndex >= 0 ? keyIndex : 0];
          const target = cells[fallbackTarget];
          if (key && target) updates.set(key, target);
        }
      } else {
        const parsed = JSON.parse(text) as { locale?: string; units?: Array<{ id: string; target: string }> };
        locale = locale || parsed.locale || '';
        for (const unit of parsed.units ?? []) {
          if (unit.target && unit.target.trim().length > 0) updates.set(unit.id, unit.target);
        }
      }

      if (!locale) {
        logger.error('Cannot determine the target locale; pass --locale <locale>');
        process.exitCode = 1;
        await project.store.close();
        return;
      }
      if (updates.size === 0) {
        logger.warn('No translations found in the file');
        await project.store.close();
        return;
      }

      if (options.write) {
        const written: string[] = [];
        for (const catalogPath of project.sourceFiles.keys()) {
          const file = fileFor(project, catalogPath, locale);
          if (!file) continue;
          const merged = mergeValues(file, updates, { sortKeys: loaded.config.write.sortKeys });
          file.entries = merged;
          file.index = new Map(merged.map((entry) => [entry.key, entry]));
          const result = await saveCatalog(file, loaded.config, merged, { backup: loaded.config.write.backup });
          if (result.written) written.push(file.target.relativePath);
        }
        logger.success(`Imported ${updates.size} string(s) into ${locale} · ${written.length} file(s) written`);
      } else {
        logger.info(`${updates.size} string(s) would be imported into ${locale} (dry run — pass --write to apply)`);
      }
      if (globals.json) logger.payload({ locale, count: updates.size, write: Boolean(options.write) });
      await project.store.close();
    });

  program
    .command('watch')
    .description('Watch code and catalogs, re-running sync on change')
    .option('--locales <list>', 'comma separated locales')
    .option('--extract', 'also extract hardcoded strings', false)
    .option('--interval <ms>', 'debounce interval', '500')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const { watch } = await import('node:fs');
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const run = async (): Promise<void> => {
        const project = await loadProject({ loaded, logger });
        const result = await runSync({
          project,
          locales: parseList(options.locales as string | undefined),
          extract: Boolean(options.extract),
          scan: true,
          fix: true,
        });
        logger.info(
          `sync: ${result.report.summary.translated} translated · ${result.report.summary.fixed} fixed · ${result.report.summary.written.length} file(s) written · ${result.report.summary.errors}E ${result.report.summary.warnings}W`,
        );
        await project.store.close();
      };
      await run();
      logger.success('Watching for changes… (Ctrl+C to stop)');
      let timer: NodeJS.Timeout | null = null;
      const interval = Number(options.interval ?? 500);
      const watcher = watch(loaded.rootDir, { recursive: true }, (_event: string, filename: string | null) => {
        if (filename && (String(filename).includes('node_modules') || String(filename).includes('.lingoflow') || String(filename).startsWith('.'))) return;
        if (timer) clearTimeout(timer);
        timer = setTimeout(() => {
          void run().catch((error) => logger.error((error as Error).message));
        }, interval);
      });
      process.on('SIGINT', () => {
        watcher.close();
        process.exit(0);
      });
    });
}

function collectUnits(
  project: Awaited<ReturnType<typeof loadProject>>,
  locale: string,
): Array<{ id: string; source: string; target: string; comment?: string }> {
  const units: Array<{ id: string; source: string; target: string; comment?: string }> = [];
  for (const [catalogPath, sourceFile] of project.sourceFiles) {
    const target = fileFor(project, catalogPath, locale);
    if (!target) continue;
    for (const entry of sourceFile.entries) {
      const translated = target.index.get(entry.key);
      units.push({
        id: entry.key,
        source: entry.value ?? '',
        target: translated?.value ?? '',
        comment: entry.comment,
      });
    }
  }
  return units;
}

function maxLengthOf(project: Awaited<ReturnType<typeof loadProject>>, key: string): number | undefined {
  for (const file of project.sourceFiles.values()) {
    const entry = file.index.get(key);
    if (entry?.maxLength) return entry.maxLength;
  }
  return undefined;
}

function quoteCsv(value: string): string {
  if (value.includes(',') || value.includes('"') || value.includes('\n')) return `"${value.replace(/"/gu, '""')}"`;
  return value;
}

function parseCsvLine(line: string): string[] {
  const cells: string[] = [];
  let cell = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i += 1) {
    const char = line[i] as string;
    if (inQuotes) {
      if (char === '"') {
        if (line[i + 1] === '"') {
          cell += '"';
          i += 1;
        } else inQuotes = false;
      } else cell += char;
      continue;
    }
    if (char === '"') {
      inQuotes = true;
      continue;
    }
    if (char === ',') {
      cells.push(cell);
      cell = '';
      continue;
    }
    cell += char;
  }
  cells.push(cell);
  return cells;
}
