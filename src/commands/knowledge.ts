import path from 'node:path';
import type { Command } from 'commander';
import { logger } from '../core/logger';
import type { RuleKind, RuleRow } from '../core/types';
import { loadProject, exportRulesToFiles } from '../pipeline/context';
import { learnFromHistory } from '../pipeline/sync';
import { sha256 } from '../core/utils/hash';
import { readText, writeAtomic, writeJson } from '../core/utils/fsx';
import { applyGlobalOptions, globalOptions, parseList, requireNonEmpty } from './shared';

export function registerKnowledgeCommands(program: Command): void {
  const memory = program.command('memory').description('Inspect and edit the private translation memory');

  memory
    .command('list')
    .description('List translation memory entries')
    .option('--locale <locale>', 'target locale')
    .option('--source <locale>', 'source locale')
    .option('--engine <engine>', 'filter by engine')
    .option('--search <text>', 'search source or target text')
    .option('--limit <n>', 'maximum rows', '20')
    .option('--offset <n>', 'pagination offset', '0')
    .option('--json', 'machine readable output')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const rows = await project.store.list({
        targetLocale: options.locale as string | undefined,
        sourceLocale: options.source as string | undefined,
        engine: options.engine as string | undefined,
        search: options.search as string | undefined,
        limit: Number(options.limit ?? 20),
        offset: Number(options.offset ?? 0),
      });
      if (globals.json) logger.payload(rows);
      else {
        logger.table(
          rows.map((row) => [String(row.id ?? ''), row.targetLocale, row.source.slice(0, 32), row.target.slice(0, 32), row.engine, row.approved ? '✓' : '', row.frozen ? '❄' : '']),
          { head: ['ID', 'Locale', 'Source', 'Target', 'Engine', 'Ap', 'Fr'] },
        );
        logger.info(`${rows.length} row(s) · driver ${project.store.driver}`);
      }
      await project.store.close();
    });

  memory
    .command('add')
    .description('Add a trusted translation (human review or an approved import)')
    .requiredOption('--src <text>', 'source text')
    .requiredOption('--tgt <text>', 'target text')
    .requiredOption('--locale <locale>', 'target locale')
    .option('--source <locale>', 'source locale (defaults to config)')
    .option('--approved', 'mark as approved', false)
    .option('--frozen', 'protect from future overwrites', false)
    .option('--note <text>', 'note')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const sourceLocale = (options.source as string | undefined) ?? loaded.config.sourceLocale;
      const locale = requireNonEmpty(options.locale as string, '--locale');
      const source = requireNonEmpty(options.src as string, '--src');
      const target = requireNonEmpty(options.tgt as string, '--tgt');
      const added = await project.store.upsertMany([
        {
          sourceLocale,
          targetLocale: locale,
          source,
          target,
          engine: 'human',
          confidence: 1,
          refs: [],
          approved: Boolean(options.approved),
          frozen: Boolean(options.frozen),
          notes: options.note as string | undefined,
          sourceHash: sha256(`${sourceLocale}\u0000${source}`),
        },
      ]);
      logger.success(`Stored ${added} entr(y|ies) for ${locale}${options.frozen ? ' (frozen)' : ''}`);
      await project.store.close();
    });

  memory
    .command('rm')
    .description('Remove memory entries by id')
    .option('--id <list>', 'comma separated ids')
    .option('--json', 'machine readable output')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const ids = parseList(options.id as string | undefined)?.map((value) => Number(value)).filter((value) => Number.isFinite(value)) ?? [];
      if (ids.length === 0) {
        logger.warn('Nothing to remove: pass --id 12,13');
      } else {
        const removed = await project.store.remove(ids);
        logger.success(`Removed ${removed} entr(y|ies)`);
      }
      await project.store.close();
    });

  memory
    .command('approve')
    .description('Mark entries as approved (or frozen) to protect them from rework')
    .option('--id <list>', 'comma separated ids')
    .option('--src <text>', 'match by source text instead of id')
    .option('--locale <locale>', 'target locale for --src')
    .option('--freeze', 'also freeze the entries', false)
    .option('--unfreeze', 'unfreeze instead', false)
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      let ids = parseList(options.id as string | undefined)?.map((value) => Number(value)) ?? [];
      if (ids.length === 0 && options.src) {
        const rows = await project.store.list({
          targetLocale: options.locale as string | undefined,
          search: options.src as string,
          limit: 50,
        });
        ids = rows.map((row) => row.id as number).filter((id) => Number.isFinite(id));
      }
      if (ids.length === 0) {
        logger.warn('No entries matched — pass --id or --src with --locale');
        await project.store.close();
        return;
      }
      for (const id of ids) {
        await project.store.update(id, {
          approved: true,
          ...(options.unfreeze ? { frozen: false } : options.freeze ? { frozen: true } : {}),
        });
      }
      logger.success(`${ids.length} entr(y|ies) ${options.freeze ? 'approved and frozen' : options.unfreeze ? 'unfrozen' : 'approved'}`);
      await project.store.close();
    });

  memory
    .command('stats')
    .description('Show translation memory statistics')
    .option('--json', 'machine readable output')
    .action(async (_options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const stats = await project.store.stats();
      if (globals.json) logger.payload(stats);
      else {
        logger.info(`driver      ${stats.driver} (${stats.location})`);
        logger.info(`entries     ${stats.entries} (${stats.approved} approved, ${stats.frozen} frozen)`);
        logger.info(`rules       ${stats.rules}`);
        logger.info(`suggestions ${stats.suggestions}`);
        logger.info(`runs        ${stats.runs}`);
        logger.info(`cache       ${stats.cache}`);
        if (stats.byLocale.length > 0) {
          logger.info('');
          logger.table(stats.byLocale.map((row) => [row.locale, String(row.entries), String(row.approved), String(row.frozen)]), {
            head: ['Locale', 'Entries', 'Approved', 'Frozen'],
          });
        }
      }
      await project.store.close();
    });

  memory
    .command('export')
    .description('Export the translation memory to a JSON file (share it deliberately)')
    .requiredOption('--out <file>', 'output file')
    .option('--locale <locale>', 'only this target locale')
    .option('--approved-only', 'export approved entries only', false)
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const rows = await project.store.list({
        targetLocale: options.locale as string | undefined,
        approved: options.approvedOnly ? true : undefined,
      });
      await writeJson(path.resolve(loaded.rootDir, options.out as string), { exportedAt: new Date().toISOString(), rows });
      logger.success(`Exported ${rows.length} entr(y|ies) to ${options.out}`);
      await project.store.close();
    });

  memory
    .command('import')
    .description('Import translation memory entries from a JSON export')
    .requiredOption('--in <file>', 'input file')
    .option('--approved', 'mark imported entries as approved', true)
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const text = await readText(path.resolve(loaded.rootDir, options.in as string));
      const parsed = JSON.parse(text) as { rows?: unknown[] } | unknown[];
      const rows = Array.isArray(parsed) ? parsed : (parsed.rows ?? []);
      const added = await project.store.upsertMany(
        rows
          .map((row) => row as Record<string, unknown>)
          .filter((row) => typeof row.source === 'string' && typeof row.target === 'string')
          .map((row) => ({
            sourceLocale: (row.sourceLocale as string | undefined) ?? loaded.config.sourceLocale,
            targetLocale: (row.targetLocale as string | undefined) ?? 'unknown',
            source: row.source as string,
            target: row.target as string,
            engine: (row.engine as string | undefined) ?? 'import',
            confidence: typeof row.confidence === 'number' ? row.confidence : 1,
            refs: Array.isArray(row.refs) ? (row.refs as string[]) : [],
            approved: options.approved !== false,
            frozen: row.frozen === true,
            sourceHash: sha256(`${(row.sourceLocale as string | undefined) ?? loaded.config.sourceLocale}\u0000${row.source as string}`),
          })),
      );
      logger.success(`Imported ${added} entr(y|ies)`);
      await project.store.close();
    });

  const rules = program.command('rules').description('Manage glossary, correction and style rules');

  rules
    .command('list')
    .description('List rules')
    .option('--kind <kind>', 'glossary | correction | style | dnt | length')
    .option('--locale <locale>', 'filter by locale')
    .option('--search <text>', 'search pattern/value')
    .option('--limit <n>', 'maximum rows', '40')
    .option('--json', 'machine readable output')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const rows = await project.store.listRules({
        kind: options.kind as RuleKind | undefined,
        locale: options.locale as string | undefined,
        search: options.search as string | undefined,
        limit: Number(options.limit ?? 40),
      });
      if (globals.json) logger.payload(rows);
      else {
        logger.table(
          rows.map((row) => [
            String(row.id ?? ''),
            row.kind,
            row.locale ?? '*',
            row.enabled ? '✓' : '—',
            row.pattern.slice(0, 28),
            row.value.slice(0, 28),
            row.origin,
            row.confidence.toFixed(2),
          ]),
          { head: ['ID', 'Kind', 'Locale', 'On', 'Pattern', 'Value', 'Origin', 'Conf'] },
        );
        logger.info(`${rows.length} rule(s) · driver ${project.store.driver}`);
      }
      await project.store.close();
    });

  rules
    .command('add')
    .description('Add a rule manually')
    .requiredOption('--kind <kind>', 'glossary | correction | style | dnt')
    .requiredOption('--pattern <text>', 'source term, regex or rule name')
    .requiredOption('--value <text>', 'target term, replacement or rule value')
    .option('--locale <locale>', 'locale (omit for all locales)')
    .option('--regex', 'treat the pattern as a regular expression', false)
    .option('--priority <n>', 'higher wins', '70')
    .option('--note <text>', 'note')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const rule: RuleRow = {
        kind: options.kind as RuleKind,
        locale: (options.locale as string | undefined) ?? null,
        pattern: requireNonEmpty(options.pattern as string, '--pattern'),
        value: options.regex ? JSON.stringify({ to: options.value, regex: true }) : (options.value as string),
        priority: Number(options.priority ?? 70),
        enabled: true,
        origin: 'manual',
        confidence: 1,
        note: options.note as string | undefined,
      };
      const added = await project.store.addRules([rule]);
      logger.success(`${added} rule(s) added`);
      await project.store.close();
    });

  rules
    .command('rm')
    .description('Remove rules by id')
    .requiredOption('--id <list>', 'comma separated ids')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const ids = parseList(options.id as string)?.map((value) => Number(value)).filter(Number.isFinite) ?? [];
      const removed = await project.store.removeRules(ids);
      logger.success(`Removed ${removed} rule(s)`);
      await project.store.close();
    });

  rules
    .command('learn')
    .description('Learn rules from history: reviewer edits → corrections, approved copy → style, TM → glossary')
    .option('--glossary', 'also mine glossary candidates (slower, more proposals)', false)
    .option('--dry-run', 'show proposals without storing them', false)
    .option('--locales <list>', 'comma separated locales')
    .option('--json', 'machine readable output')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const locales = parseList(options.locales as string | undefined) ?? loaded.config.locales.filter((locale) => locale !== loaded.config.sourceLocale);
      const learned = await learnFromHistory(project, { includeGlossary: Boolean(options.glossary), locales });
      if (globals.json) {
        logger.payload(learned);
      } else {
        logger.success(
          `learned ${learned.glossary} glossary · ${learned.corrections} correction · ${learned.styles} style rule(s)` +
            (options.dryRun ? ' (dry run — pass without --dry-run to store; this run stored them)' : ''),
        );
        logger.table(
          learned.details.slice(0, 30).map((rule) => [rule.kind, rule.locale ?? '*', rule.pattern.slice(0, 30), rule.value.slice(0, 30), rule.confidence.toFixed(2)]),
          { head: ['Kind', 'Locale', 'Pattern', 'Value', 'Conf'] },
        );
      }
      await project.store.close();
    });

  rules
    .command('export')
    .description('Write the rule base back to lingoflow.glossary.json / style / corrections files')
    .action(async (_options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const files = await exportRulesToFiles(project);
      for (const file of files) logger.success(`wrote ${path.relative(loaded.rootDir, file)}`);
      await project.store.close();
    });
}

export function registerGlossaryAlias(program: Command): void {
  const glossary = program.command('glossary').description('Glossary shortcuts (same as `rules --kind glossary`)');
  glossary
    .command('add <source> <target>')
    .description('Add a glossary term')
    .option('--locale <locale>', 'locale (omit for every locale)')
    .option('--note <text>', 'note')
    .action(async (source: string, target: string, options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const added = await project.store.addRules([
        {
          kind: 'glossary',
          locale: (options.locale as string | undefined) ?? null,
          pattern: source,
          value: JSON.stringify({ target }),
          priority: 70,
          enabled: true,
          origin: 'manual',
          confidence: 1,
          note: options.note as string | undefined,
        },
      ]);
      logger.success(`${added} term(s) added: ${source} → ${target}`);
      await project.store.close();
    });
  glossary
    .command('list')
    .description('List glossary terms')
    .option('--locale <locale>', 'filter by locale')
    .option('--json', 'machine readable output')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const rows = await project.store.listRules({ kind: 'glossary', locale: options.locale as string | undefined, limit: 500 });
      if (globals.json) logger.payload(rows);
      else
        logger.table(
          rows.map((row) => [String(row.id ?? ''), row.locale ?? '*', row.pattern, row.value.replace(/^\{.*"target":"(.*?)".*\}$/u, '$1'), row.origin, row.enabled ? '✓' : '—']),
          { head: ['ID', 'Locale', 'Source', 'Target', 'Origin', 'On'] },
        );
      await project.store.close();
    });
  glossary
    .command('learn')
    .description('Mine glossary candidates from the translation memory')
    .option('--locales <list>', 'comma separated locales')
    .option('--json', 'machine readable output')
    .action(async (options: Record<string, unknown>, command: Command) => {
      const globals = globalOptions(command.parent?.parent ?? command);
      applyGlobalOptions(globals);
      const { loadConfig } = await import('../core/config/load');
      const loaded = await loadConfig({ cwd: globals.cwd, configPath: globals.config });
      const project = await loadProject({ loaded, logger, skipEngines: true });
      const locales = parseList(options.locales as string | undefined) ?? loaded.config.locales.filter((locale) => locale !== loaded.config.sourceLocale);
      const learned = await learnFromHistory(project, { includeGlossary: true, locales });
      if (globals.json) logger.payload(learned);
      else {
        logger.success(`${learned.glossary} glossary candidate(s) proposed`);
        logger.table(
          learned.details.filter((rule) => rule.kind === 'glossary').slice(0, 40).map((rule) => [rule.locale ?? '*', rule.pattern, rule.value, rule.confidence.toFixed(2)]),
          { head: ['Locale', 'Source', 'Target', 'Conf'] },
        );
      }
      await project.store.close();
    });
}

export { writeAtomic };
