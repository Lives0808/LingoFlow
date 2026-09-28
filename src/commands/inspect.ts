import path from 'node:path';
import type { Command } from 'commander';
import { logger } from '../core/logger';
import { loadProject } from '../pipeline/context';
import { scanProject, compareKeyCoverage } from '../pipeline/scan';
import { runCheck } from '../pipeline/check';
import { extractHardcoded } from '../pipeline/extract';
import { startPreviewServer } from '../preview/server';
import { writeReports } from '../report/write';
import { applyGlobalOptions, finishRun, globalOptions, openPath, parseList, relative } from './shared';

export function registerInspectCommands(program: Command): void {
  program
    .command('scan')
    .description('Scan source code for translation keys, missing keys and hardcoded UI copy')
    .option('--json', 'machine readable output')
    .option('--hardcoded', 'also list hardcoded strings', true)
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const scan = await scanProject(loaded.config, loaded.rootDir);
      const defined = new Set<string>();
      for (const file of project.sourceFiles.values()) for (const entry of file.entries) defined.add(entry.key);
      const coverage = compareKeyCoverage(scan, defined);

      if (globals.json) {
        logger.payload({
          filesScanned: scan.filesScanned,
          usages: scan.usages,
          usedKeys: scan.usedKeys,
          undefinedKeys: coverage.undefinedKeys,
          unusedKeys: coverage.unusedKeys,
          dynamicKeys: scan.dynamicKeys,
          hardcoded: scan.hardcoded,
        });
        return;
      }
      logger.info(`${scan.filesScanned} file(s) scanned · ${scan.usages.length} key usage(s) · ${scan.usedKeys.length} distinct key(s)`);
      if (coverage.undefinedKeys.length > 0) {
        logger.warn(`${coverage.undefinedKeys.length} key(s) used in code but missing from ${loaded.config.sourceLocale}:`);
        for (const key of coverage.undefinedKeys.slice(0, 40)) {
          const usage = coverage.usageByKey.get(key)?.[0];
          logger.info(`  ${key}  ${usage ? `(${usage.file}:${usage.line})` : ''}`);
        }
      }
      if (coverage.unusedKeys.length > 0) {
        logger.info(`${coverage.unusedKeys.length} unused key(s): ${coverage.unusedKeys.slice(0, 20).join(', ')}${coverage.unusedKeys.length > 20 ? ' …' : ''}`);
      }
      if (scan.dynamicKeys.length > 0) {
        logger.info(`${scan.dynamicKeys.length} dynamic key(s) could not be resolved: ${scan.dynamicKeys.slice(0, 10).join(', ')}`);
      }
      if (options.hardcoded !== false && scan.hardcoded.length > 0) {
        logger.info('');
        logger.info(`${scan.hardcoded.length} hardcoded string(s) found:`);
        logger.table(
          scan.hardcoded
            .slice(0, 40)
            .map((candidate) => [`${candidate.file}:${candidate.line}`, candidate.kind, candidate.confidence.toFixed(2), candidate.text.slice(0, 48)]),
          { head: ['Where', 'Kind', 'Conf', 'Text'] },
        );
        logger.info('');
        logger.info('Run `lingoflow extract --write` to turn them into keys.');
      }
      await project.store.close();
    });

  program
    .command('extract')
    .description('Turn hardcoded UI strings into catalog keys and t() calls')
    .option('--write', 'apply the rewrite (default is a dry run)', false)
    .option('--min-confidence <n>', 'minimum confidence 0..1', '0.75')
    .option('--json', 'machine readable output')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const result = await extractHardcoded(project, {
        dryRun: options.write !== true,
        minConfidence: Number(options.minConfidence ?? 0.75),
        writeSourceCatalog: options.write === true,
      });
      if (globals.json) {
        logger.payload(result);
      } else {
        logger.info(`${result.keys.length} string(s) mapped to keys${result.dryRun ? ' (dry run)' : ''}`);
        for (const key of result.keys.slice(0, 40)) {
          logger.info(`  ${relative(loaded.rootDir, key.file)}:${key.line}  ${key.text.slice(0, 40)} → ${key.key}`);
        }
        if (result.files.length > 0) {
          logger.info('');
          logger.info(`${result.files.reduce((sum, file) => sum + file.rewrites, 0)} rewrite(s) across ${result.files.length} file(s)`);
        }
        if (result.dryRun && result.keys.length > 0) logger.info('Re-run with --write to apply.');
      }
      await project.store.close();
    });

  program
    .command('preview')
    .description('Build the UI/length preview, optionally serving it with live reload')
    .option('--serve', 'serve the report locally with live reload', false)
    .option('--port <n>', 'port for --serve', '4173')
    .option('--open', 'open in the default browser', false)
    .option('--locales <list>', 'comma separated locales')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const build = async () => {
        const { loadProject: reload } = await import('../pipeline/context');
        const project = await reload({ loaded, logger, skipEngines: true });
        const { report } = await runCheck({
          project,
          locales: parseList(options.locales as string | undefined),
          fix: false,
          write: false,
        });
        const artifacts = await writeReports(report, loaded.config, loaded.rootDir);
        await project.store.close();
        return artifacts;
      };
      const artifacts = await build();
      const htmlPath = path.join(artifacts.dir, 'index.html');
      if (options.serve) {
        const server = await startPreviewServer({
          dir: artifacts.dir,
          port: Number(options.port ?? 4173),
          rebuild: async () => {
            await build();
          },
          watchDirs: [loaded.rootDir],
        });
        logger.success(`Preview running at ${server.url}`);
        logger.info('Press Ctrl+C to stop. Editing catalogs or code refreshes the page.');
        if (options.open) await openPath(server.url);
        await new Promise<void>(() => {
          // Keep serving until the process is interrupted.
        });
      } else {
        logger.success(`Preview written to ${relative(loaded.rootDir, htmlPath)}`);
        if (options.open) await openPath(htmlPath);
      }
    });

  program
    .command('report')
    .description('Write report.json / report.md / report.sarif / index.html for the current state')
    .option('--locales <list>', 'comma separated locales')
    .option('--open', 'open the HTML report', false)
    .option('--json', 'machine readable output')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const { report } = await runCheck({
        project,
        locales: parseList(options.locales as string | undefined),
        fix: false,
        write: false,
      });
      const code = await finishRun({
        report,
        config: loaded.config,
        rootDir: loaded.rootDir,
        json: globals.json,
        open: Boolean(options.open),
        failOn: 'none',
      });
      await project.store.close();
      process.exitCode = code;
    });
}
