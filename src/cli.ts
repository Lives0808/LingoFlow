import { Command, CommanderError } from 'commander';
import { HOMEPAGE, PRODUCT, VERSION } from './version';
import { logger } from './core/logger';
import { isLingoFlowError, toError } from './core/errors';
import { registerProjectCommands, registerEngineCommand } from './commands/project';
import { registerTranslateCommands } from './commands/translate';
import { registerInspectCommands } from './commands/inspect';
import { registerKnowledgeCommands, registerGlossaryAlias } from './commands/knowledge';
import { registerIoCommands } from './commands/io';

export function buildProgram(): Command {
  const program = new Command();
  program
    .name('lingoflow')
    .alias('lf')
    .description(`${PRODUCT} — code-aware i18n pipeline: translate, align, validate length, write back, learn rules.`)
    .version(VERSION, '-v, --version')
    .option('--config <file>', 'path to lingoflow.config.json')
    .option('--cwd <dir>', 'run as if started in this directory')
    .option('--json', 'machine readable output on stdout', false)
    .option('--quiet', 'only warnings and errors', false)
    .option('--verbose', 'debug output', false)
    .option('--no-color', 'disable colors')
    .showHelpAfterError(true)
    .showSuggestionAfterError(true)
    .configureHelp({ sortSubcommands: true });

  registerProjectCommands(program);
  registerEngineCommand(program);
  registerTranslateCommands(program);
  registerInspectCommands(program);
  registerKnowledgeCommands(program);
  registerGlossaryAlias(program);
  registerIoCommands(program);

  program.addHelpText(
    'after',
    `
Examples
  $ lingoflow init --preset react            # scaffold config, glossary and style files
  $ lingoflow sync                         # one-click: translate, fix, validate, write back
  $ lingoflow sync --dry-run               # preview every change without touching files
  $ lingoflow check --fail-on warn         # CI gate for placeholders, length and style
  $ lingoflow preview --serve              # local UI-fit preview with live reload
  $ lingoflow rules learn --glossary       # mine glossary terms from your own history

Docs: ${HOMEPAGE}
`,
  );
  return program;
}

export async function main(argv: string[]): Promise<number> {
  const program = buildProgram();
  try {
    await program.parseAsync(argv);
    return typeof process.exitCode === 'number' ? process.exitCode : 0;
  } catch (error) {
    if (error instanceof CommanderError) {
      if (error.code === 'commander.helpDisplayed' || error.code === 'commander.version') return 0;
      return Number(error.exitCode ?? 1) || 1;
    }
    const err = toError(error);
    if (isLingoFlowError(err)) {
      logger.error(err.message);
      if (err.hint) logger.info(`hint: ${err.hint}`);
      return err.exitCode;
    }
    logger.error(err.message);
    if (process.env.LINGOFLOW_DEBUG || process.argv.includes('--verbose')) {
      logger.error(err.stack ?? '');
    }
    return 1;
  }
}
