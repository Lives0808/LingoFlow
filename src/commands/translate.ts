import type { Command } from 'commander';
import { logger } from '../core/logger';
import { loadProject } from '../pipeline/context';
import { runSync } from '../pipeline/sync';
import { runCheck, runAlign } from '../pipeline/check';
import { applyGlobalOptions, finishRun, globalOptions, parseKeyList, parseList } from './shared';
import { loadConfig } from '../core/config/load';
import type { EngineId } from '../core/types';
import { ENGINE_IDS } from '../core/translate/registry';

export function registerTranslateCommands(program: Command): void {
  program
    .command('sync')
    .description('One-click pipeline: scan → extract → translate → fix → validate → write back → learn')
    .option('--locales <list>', 'comma separated locales to process')
    .option('--key <list>', 'only process these keys (comma separated)')
    .option('--force', 'retranslate even when a translation exists', false)
    .option('--dry-run', 'plan and report without writing anything', false)
    .option('--extract', 'rewrite hardcoded strings into translation calls')
    .option('--no-scan', 'skip scanning the source code')
    .option('--no-fix', 'do not apply automatic fixes')
    .option('--learn', 'also learn glossary terms from history')
    .option('--export-rules', 'write learned rules back to glossary/style files', false)
    .option('--engine <id>', `override engine (${ENGINE_IDS.join(', ')})`)
    .option('--open', 'open the HTML report when the run finishes', false)
    .option('--fail-on <level>', 'error | warn | none')
    .option('--samples <n>', 'preview samples per locale (default 40)')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({
        loaded,
        logger,
        engineOverride: options.engine as EngineId | undefined,
      });
      const result = await runSync({
        project,
        locales: parseList(options.locales as string | undefined),
        onlyKeys: parseKeyList(options.key as string | undefined),
        force: Boolean(options.force),
        dryRun: Boolean(options.dryRun),
        fix: options.fix !== false,
        scan: options.scan !== false,
        extract: Boolean(options.extract),
        learn: Boolean(options.learn),
        exportRules: Boolean(options.exportRules),
        sampleLimit: options.samples ? Number(options.samples) : undefined,
      });
      const code = await finishRun({
        report: result.report,
        config: loaded.config,
        rootDir: loaded.rootDir,
        json: globals.json,
        open: Boolean(options.open),
        failOn: (options.failOn as 'error' | 'warn' | 'none' | undefined) ?? undefined,
        notice: options.dryRun ? 'Dry run: nothing was written. Re-run without --dry-run to apply.' : undefined,
      });
      await project.store.close();
      process.exitCode = code;

      async function loadProjectConfig() {
          return loadConfig({ cwd: globals.cwd, configPath: globals.config });
      }
    });

  program
    .command('translate')
    .description('Translate missing or stale strings and write them back to the catalogs')
    .option('--locales <list>', 'comma separated locales to process')
    .option('--key <list>', 'only process these keys (comma separated)')
    .option('--policy <policy>', 'missing | empty | untranslated | stale | all')
    .option('--force', 'retranslate everything (except frozen entries)', false)
    .option('--dry-run', 'show what would be translated without calling the engine', false)
    .option('--engine <id>', `override engine (${ENGINE_IDS.join(', ')})`)
    .option('--open', 'open the HTML report when the run finishes', false)
    .option('--fail-on <level>', 'error | warn | none')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const overrides = options.policy ? { translation: { policy: options.policy as 'missing' } } : undefined;
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config, overrides });
      const project = await loadProject({ loaded, logger, engineOverride: options.engine as EngineId | undefined });
      const result = await runSync({
        project,
        locales: parseList(options.locales as string | undefined),
        onlyKeys: parseKeyList(options.key as string | undefined),
        force: Boolean(options.force),
        dryRun: Boolean(options.dryRun),
        scan: false,
        fix: true,
        learn: loaded.config.translation.memory.learnFromEdits,
      });
      result.report.command = options.dryRun ? 'translate --dry-run' : 'translate';
      const code = await finishRun({
        report: result.report,
        config: loaded.config,
        rootDir: loaded.rootDir,
        json: globals.json,
        open: Boolean(options.open),
        failOn: (options.failOn as 'error' | 'warn' | 'none' | undefined) ?? undefined,
      });
      await project.store.close();
      process.exitCode = code;
    });

  program
    .command('check')
    .description('Validate translations: placeholders, ICU, tags, length, style, consistency')
    .option('--locales <list>', 'comma separated locales to check')
    .option('--open', 'open the HTML report when finished', false)
    .option('--fail-on <level>', 'error | warn | none')
    .option('--no-report', 'do not write report files')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
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
        writeReports: options.report !== false,
        failOn: (options.failOn as 'error' | 'warn' | 'none' | undefined) ?? undefined,
      });
      await project.store.close();
      process.exitCode = code;
    });

  program
    .command('fix')
    .description('Apply deterministic fixes (line breaks, punctuation, spacing, placeholders, length)')
    .option('--locales <list>', 'comma separated locales to fix')
    .option('--dry-run', 'report fixes without writing', false)
    .option('--open', 'open the HTML report when finished', false)
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const { report, written } = await runCheck({
        project,
        locales: parseList(options.locales as string | undefined),
        fix: true,
        write: options.dryRun !== true,
      });
      for (const file of written) logger.success(`fixed ${file}`);
      const code = await finishRun({
        report,
        config: loaded.config,
        rootDir: loaded.rootDir,
        json: globals.json,
        open: Boolean(options.open),
        failOn: (options.failOn as 'error' | 'warn' | 'none' | undefined) ?? undefined,
      });
      await project.store.close();
      process.exitCode = code;
    });

  program
    .command('align')
    .description('Align locales with the source catalog: report, fill and prune keys')
    .option('--locales <list>', 'comma separated locales to align')
    .option('--fill', 'add missing keys using the source text', false)
    .option('--prune', 'remove keys that no longer exist in the source', false)
    .option('--write', 'apply the changes to disk (default: report only)', false)
    .option('--open', 'open the HTML report when finished', false)
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const { report, written } = await runAlign({
        project,
        locales: parseList(options.locales as string | undefined),
        fill: Boolean(options.fill),
        prune: Boolean(options.prune),
        write: Boolean(options.write),
      });
      if (written.length > 0) logger.success(`${written.length} file(s) aligned`);
      const code = await finishRun({
        report,
        config: loaded.config,
        rootDir: loaded.rootDir,
        json: globals.json,
        open: Boolean(options.open),
        failOn: options.write ? 'none' : 'error',
      });
      await project.store.close();
      process.exitCode = code;
    });
}
