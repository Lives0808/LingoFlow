import path from 'node:path';
import YAML from 'yaml';
import { CONFIG_FILENAMES, WORK_DIR } from '../../version';
import { LingoFlowError } from '../errors';
import { exists, readText, readTextIfExists, relativeTo, resolveFrom, writeAtomic } from '../utils/fsx';
import { parseConfig, type DeepPartial, type LoadedConfig, type ResolvedConfig } from './schema';

export interface LoadOptions {
  cwd: string;
  configPath?: string;
  overrides?: DeepPartial<ResolvedConfig>;
  /** Do not fail when no config exists (used by `init`). */
  optional?: boolean;
}

export async function findConfigFile(cwd: string, explicit?: string): Promise<string | null> {
  if (explicit) {
    const resolved = resolveFrom(cwd, explicit);
    if (!(await exists(resolved))) {
      throw new LingoFlowError(`Config file not found: ${explicit}`, {
        code: 'CONFIG_NOT_FOUND',
        hint: 'Run `lingoflow init` to create one.',
      });
    }
    return resolved;
  }
  let current = path.resolve(cwd);
  for (let depth = 0; depth < 6; depth += 1) {
    for (const name of CONFIG_FILENAMES) {
      const candidate = path.join(current, name);
      if (await exists(candidate)) return candidate;
    }
    const parent = path.dirname(current);
    if (parent === current) break;
    current = parent;
  }
  return null;
}

export function parseConfigText(text: string, filePath: string): unknown {
  const ext = path.extname(filePath).toLowerCase();
  if (ext === '.yaml' || ext === '.yml') {
    return YAML.parse(text, { prettyErrors: true }) ?? {};
  }
  try {
    return JSON.parse(stripJsonComments(text)) as unknown;
  } catch (error) {
    throw new LingoFlowError(`Cannot parse config file ${filePath}: ${(error as Error).message}`, {
      code: 'CONFIG_PARSE_ERROR',
    });
  }
}

export async function loadConfig(options: LoadOptions): Promise<LoadedConfig> {
  const filePath = await findConfigFile(options.cwd, options.configPath);
  if (!filePath) {
    if (options.optional) {
      const config = parseConfig({});
      applyOverrides(config, options.overrides);
      return { config, path: null, rootDir: path.resolve(options.cwd) };
    }
    throw new LingoFlowError('No lingoflow config found in this project.', {
      code: 'CONFIG_NOT_FOUND',
      hint: 'Run `lingoflow init` (or pass --config <file>) first.',
    });
  }
  const text = await readText(filePath);
  let config: ResolvedConfig;
  try {
    config = parseConfig(parseConfigText(text, filePath));
  } catch (error) {
    throw new LingoFlowError(`Invalid config in ${filePath}\n${(error as Error).message}`, {
      code: 'CONFIG_INVALID',
    });
  }
  const rootDir = path.dirname(filePath);
  applyOverrides(config, options.overrides);
  validateLocales(config, rootDir);
  return { config, path: filePath, rootDir };
}

function applyOverrides(config: ResolvedConfig, overrides?: DeepPartial<ResolvedConfig>): void {
  if (!overrides) return;
  if (overrides.sourceLocale) config.sourceLocale = overrides.sourceLocale;
  if (overrides.locales && overrides.locales.length > 0) config.locales = overrides.locales;
  if (overrides.translation) {
    Object.assign(config.translation, overrides.translation);
  }
  if (overrides.length) Object.assign(config.length, overrides.length);
  if (overrides.validate) Object.assign(config.validate, overrides.validate);
  if (overrides.write) Object.assign(config.write, overrides.write);
  if (overrides.report) Object.assign(config.report, overrides.report);
  if (overrides.privacy) Object.assign(config.privacy, overrides.privacy);
  if (overrides.workDir) config.workDir = overrides.workDir;
}

function validateLocales(config: ResolvedConfig, rootDir: string): void {
  if (config.locales.length === 0) {
    throw new LingoFlowError(`No locales configured in ${relativeTo(rootDir, path.join(rootDir, 'lingoflow.config.json'))}`, {
      code: 'CONFIG_NO_LOCALES',
      hint: 'Add e.g. "locales": ["en", "zh-CN", "ja"] to the config.',
    });
  }
  if (!config.locales.includes(config.sourceLocale)) {
    config.locales = [config.sourceLocale, ...config.locales];
  }
}

export function workDirOf(loaded: LoadedConfig): string {
  return resolveFrom(loaded.rootDir, loaded.config.workDir || WORK_DIR);
}

export async function writeConfig(rootDir: string, config: RawConfigLike, options: { force?: boolean } = {}): Promise<string> {
  const filePath = path.join(rootDir, 'lingoflow.config.json');
  if (!options.force && (await exists(filePath))) {
    throw new LingoFlowError(`Config already exists: ${filePath}`, {
      code: 'CONFIG_EXISTS',
      hint: 'Pass --force to overwrite it.',
    });
  }
  const body = JSON.stringify(config, null, 2);
  await writeAtomic(filePath, `${body}\n`);
  return filePath;
}

type RawConfigLike = Record<string, unknown>;

export async function readRawConfig(cwd: string, explicit?: string): Promise<{ path: string | null; raw: RawConfigLike }> {
  const filePath = await findConfigFile(cwd, explicit);
  if (!filePath) return { path: null, raw: {} };
  const text = (await readTextIfExists(filePath)) ?? '{}';
  const parsed = parseConfigText(text, filePath);
  return { path: filePath, raw: (parsed ?? {}) as RawConfigLike };
}

export function updateRawConfig(raw: RawConfigLike, patch: Record<string, unknown>): RawConfigLike {
  const next: RawConfigLike = { ...raw };
  for (const [key, value] of Object.entries(patch)) {
    if (value && typeof value === 'object' && !Array.isArray(value) && typeof next[key] === 'object' && next[key] !== null) {
      next[key] = { ...(next[key] as Record<string, unknown>), ...(value as Record<string, unknown>) };
    } else if (value === undefined) {
      delete next[key];
    } else {
      next[key] = value;
    }
  }
  return next;
}

/** Very small JSONC stripper: comments + trailing commas. */
export function stripJsonComments(text: string): string {
  let output = '';
  let inString = false;
  let inLineComment = false;
  let inBlockComment = false;
  for (let i = 0; i < text.length; i += 1) {
    const char = text[i] as string;
    const next = text[i + 1];
    if (inLineComment) {
      if (char === '\n') {
        inLineComment = false;
        output += char;
      }
      continue;
    }
    if (inBlockComment) {
      if (char === '*' && next === '/') {
        inBlockComment = false;
        i += 1;
      }
      continue;
    }
    if (inString) {
      output += char;
      if (char === '\\') {
        output += next ?? '';
        i += 1;
      } else if (char === '"') {
        inString = false;
      }
      continue;
    }
    if (char === '"') {
      inString = true;
      output += char;
      continue;
    }
    if (char === '/' && next === '/') {
      inLineComment = true;
      i += 1;
      continue;
    }
    if (char === '/' && next === '*') {
      inBlockComment = true;
      i += 1;
      continue;
    }
    output += char;
  }
  return output.replace(/,(\s*[}\]])/gu, '$1');
}
