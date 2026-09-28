import path from 'node:path';
import type { Command } from 'commander';
import { findPreset, PRESETS } from '../core/config/presets';
import { loadConfig, readRawConfig, writeConfig } from '../core/config/load';
import { parseConfig, type ResolvedConfig } from '../core/config/schema';
import { VERSION } from '../version';
import { logger } from '../core/logger';
import { ensureDir, exists, writeAtomic, writeJson } from '../core/utils/fsx';
import { LingoFlowError } from '../core/errors';
import { loadProject } from '../pipeline/context';
import { describeEngines } from '../core/translate/registry';
import { SqliteMemoryStore } from '../core/tm/sqlite';
import { applyGlobalOptions, globalOptions, parseList } from './shared';
import { alignCatalog } from '../core/catalog/catalog';
import { WORK_DIR } from '../version';

export function registerProjectCommands(program: Command): void {
  program
    .command('init')
    .description('Create lingoflow.config.json, glossary/style files and the working directory')
    .option('--preset <id>', `stack preset: ${PRESETS.map((preset) => preset.id).join(', ')}`)
    .option('--locales <list>', 'comma separated locale list, e.g. en,zh-CN,ja')
    .option('--source <locale>', 'source locale (default en)')
    .option('--force', 'overwrite an existing config', false)
    .option('--no-catalogs', 'do not create empty catalog files')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const rootDir = globals.cwd;
      const presetId = (options.preset as string | undefined) ?? 'react';
      const preset = findPreset(presetId);
      if (!preset) {
        throw new LingoFlowError(`Unknown preset "${presetId}"`, {
          code: 'UNKNOWN_PRESET',
          hint: `Available: ${PRESETS.map((item) => item.id).join(', ')}`,
        });
      }
      const config: Record<string, unknown> = structuredClone(preset.config) as Record<string, unknown>;
      const locales = parseList(options.locales as string | undefined);
      if (locales) {
        config.locales = locales.includes((options.source as string) ?? 'en') ? locales : [(options.source as string) ?? 'en', ...locales];
      }
      if (options.source) config.sourceLocale = options.source;
      const resolved = parseConfig(config);

      const configPath = await writeConfig(rootDir, config, { force: Boolean(options.force) });
      logger.success(`Created ${path.relative(rootDir, configPath)} (preset: ${preset.title})`);

      await ensureDir(path.join(rootDir, resolved.workDir || WORK_DIR));
      const glossaryPath = path.join(rootDir, resolved.translation.glossary.file);
      if (!(await exists(glossaryPath))) {
        await writeJson(glossaryPath, {
          terms: [
            { source: 'LingoFlow', target: 'LingoFlow', locale: null, note: 'brand name — never translate' },
            { source: 'Settings', target: '设置', locale: 'zh-CN' },
          ],
        });
        logger.success(`Created ${path.relative(rootDir, glossaryPath)} (starter terms — edit freely)`);
      }
      const stylePath = path.join(rootDir, resolved.translation.style.file);
      if (!(await exists(stylePath))) {
        await writeJson(stylePath, {
          rules: [
            { name: 'punctuation.trailing', value: 'never', locale: 'zh-CN', note: 'no trailing period on Chinese UI labels' },
            { name: 'cjk.fullwidth', value: 'true', locale: 'zh-CN' },
          ],
        });
        logger.success(`Created ${path.relative(rootDir, stylePath)}`);
      }

      if (options.catalogs !== false) {
        for (const catalog of resolved.catalogs) {
          for (const locale of resolved.locales) {
            if (catalog.locale && catalog.locale !== locale) continue;
            const file = path.join(rootDir, catalog.path.replace(/\{locale\}/gu, locale));
            if (await exists(file)) continue;
            await ensureDir(path.dirname(file));
            const empty = catalog.path.endsWith('.yaml') || catalog.path.endsWith('.yml') ? '' : '{}\n';
            await writeAtomic(file, empty);
            logger.info(`  created ${path.relative(rootDir, file)}`);
          }
        }
      }

      logger.info('');
      logger.info('Next steps:');
      logger.info('  1. lingoflow doctor          # verify engines, catalogs and environment');
      logger.info('  2. lingoflow scan            # find keys used in code + hardcoded copy');
      logger.info('  3. lingoflow sync --dry-run  # see exactly what would change');
      logger.info('  4. lingoflow sync            # translate, fix, validate and write back');
    });

  program
    .command('doctor')
    .description('Check environment, config, catalogs and engine availability')
    .action(async (_options: unknown, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const checks: Array<{ name: string; ok: boolean; detail: string }> = [];
      checks.push({ name: 'node', ok: true, detail: process.version });
      checks.push({
        name: 'sqlite driver',
        ok: await SqliteMemoryStore.isAvailable(),
        detail: (await SqliteMemoryStore.isAvailable()) ? 'node:sqlite available (fast private memory)' : 'falling back to JSONL memory',
      });
      checks.push({ name: 'config', ok: true, detail: loaded.path ? path.relative(loaded.rootDir, loaded.path) : '(defaults)' });
      checks.push({ name: 'source locale', ok: true, detail: loaded.config.sourceLocale });
      checks.push({ name: 'locales', ok: loaded.config.locales.length > 0, detail: loaded.config.locales.join(', ') });

      const project = await loadProject({ loaded, logger });
      const missingFiles: string[] = [];
      for (const target of project.targets) {
        if (target.locale !== loaded.config.sourceLocale) continue;
        if (!(await exists(target.path))) missingFiles.push(path.relative(loaded.rootDir, target.path).split(path.sep).join('/'));
      }
      checks.push({
        name: 'catalogs',
        ok: missingFiles.length === 0,
        detail:
          missingFiles.length === 0
            ? `${project.targets.length} file(s) across ${loaded.config.locales.length} locale(s)`
            : `missing: ${missingFiles.join(', ')}`,
      });

      try {
        const engines = await describeEngines({ config: loaded.config, rootDir: loaded.rootDir });
        for (const engine of engines) {
          checks.push({
            name: `engine:${engine.id}`,
            ok: engine.available || !engine.selected,
            detail: `${engine.available ? 'ready' : 'unavailable'} — ${engine.detail}${engine.selected ? ' (selected)' : ''}`,
          });
        }
      } catch (error) {
        checks.push({ name: 'engines', ok: false, detail: (error as Error).message });
      }

      const parity: Array<{ locale: string; missing: number; coverage: string }> = [];
      for (const [catalogPath, sourceFile] of project.sourceFiles) {
        for (const target of project.targets) {
          if (target.path !== catalogPath || target.locale === loaded.config.sourceLocale) continue;
          const file = project.files.get(`${target.locale}\u0000${target.path}`);
          if (!file) continue;
          const alignment = alignCatalog(sourceFile, file);
          parity.push({ locale: target.locale, missing: alignment.missing.length, coverage: `${(alignment.coverage * 100).toFixed(0)}%` });
        }
      }

      if (globals.json) {
        logger.payload({ checks, parity, config: loaded.config });
      } else {
        for (const check of checks) {
          const mark = check.ok ? '✓' : '✗';
          const line = `${mark} ${check.name.padEnd(16)} ${check.detail}`;
          if (check.ok) logger.info(line);
          else logger.warn(line);
        }
        if (parity.length > 0) {
          logger.info('');
          logger.table(parity.map((row) => [row.locale, row.coverage, String(row.missing)]), { head: ['Locale', 'Coverage', 'Missing'] });
        }
      }
      const failures = checks.filter((check) => !check.ok).length;
      if (failures > 0) logger.warn(`${failures} check(s) need attention`);
    });

  const configCommand = program.command('config').description('Inspect or change configuration');
  configCommand
    .command('path')
    .description('Print the config file path')
    .action(async (_options: unknown, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      process.stdout.write(`${loaded.path ?? ''}\n`);
    });
  configCommand
    .command('show')
    .description('Print the resolved configuration')
    .action(async (_options: unknown, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      if (globals.json) logger.payload(loaded.config);
      else process.stdout.write(`${JSON.stringify(loaded.config, null, 2)}\n`);
    });
  configCommand
    .command('set <path> <value>')
    .description('Set a config value, e.g. `lingoflow config set translation.engine openai`')
    .action(async (keyPath: string, value: string, _options: unknown, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { path: configPath, raw } = await readRawConfig(globals.cwd, globals.config);
      const segments = keyPath.split('.');
      const patch: Record<string, unknown> = {};
      let cursor = patch;
      segments.forEach((segment, index) => {
        if (index === segments.length - 1) {
          cursor[segment] = parseValue(value);
        } else {
          const next: Record<string, unknown> = {};
          cursor[segment] = next;
          cursor = next;
        }
      });
      const updated = deepMerge(raw, patch);
      parseConfig(updated); // validate before writing
      const target = configPath ?? path.join(globals.cwd, 'lingoflow.config.json');
      await writeAtomic(target, `${JSON.stringify(updated, null, 2)}\n`);
      logger.success(`Updated ${keyPath}`);
    });
}

function parseValue(value: string): unknown {
  if (value === 'true') return true;
  if (value === 'false') return false;
  if (value === 'null') return null;
  if (/^-?\d+(\.\d+)?$/u.test(value)) return Number(value);
  if ((value.startsWith('[') && value.endsWith(']')) || (value.startsWith('{') && value.endsWith('}'))) {
    try {
      return JSON.parse(value) as unknown;
    } catch {
      return value;
    }
  }
  return value;
}

function deepMerge(base: Record<string, unknown>, patch: Record<string, unknown>): Record<string, unknown> {
  const result: Record<string, unknown> = { ...base };
  for (const [key, value] of Object.entries(patch)) {
    const existing = result[key];
    if (value && typeof value === 'object' && !Array.isArray(value) && existing && typeof existing === 'object' && !Array.isArray(existing)) {
      result[key] = deepMerge(existing as Record<string, unknown>, value as Record<string, unknown>);
    } else {
      result[key] = value;
    }
  }
  return result;
}

export function registerEngineCommand(program: Command): void {
  program
    .command('engines')
    .description('List translation engines and whether they are ready')
    .option('--check', 'probe availability (may perform a network call)', false)
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent ?? command);
      applyGlobalOptions(globals);
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const engines = await describeEngines({ config: loaded.config, rootDir: loaded.rootDir });
      if (globals.json) {
        logger.payload(engines);
        return;
      }
      logger.table(
        engines.map((engine) => [
          [engine.selected ? '●' : ' ', engine.id].join(' '),
          engine.privacy,
          engine.available ? 'ready' : 'not ready',
          engine.detail,
        ]),
        { head: ['', 'Privacy', 'Status', 'Detail'] },
      );
      logger.info('');
      logger.info(`Selected: ${loaded.config.translation.engine} · fallback: ${loaded.config.translation.fallback.join(', ') || 'none'}`);
      logger.info(`Tip: \`lingoflow engines --json\` for machine readable output. Version ${VERSION}.`);
      void options;
    });
}

