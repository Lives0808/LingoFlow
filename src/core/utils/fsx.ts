import { promises as fs } from 'node:fs';
import path from 'node:path';
import fg from 'fast-glob';
import { LingoFlowError } from '../errors';

export interface GlobOptions {
  cwd: string;
  ignore?: string[];
  dot?: boolean;
  absolute?: boolean;
}

export async function expandGlobs(patterns: string[], options: GlobOptions): Promise<string[]> {
  if (patterns.length === 0) return [];
  const matches = await fg(patterns, {
    cwd: options.cwd,
    ignore: options.ignore ?? ['**/node_modules/**', '**/.git/**'],
    dot: options.dot ?? false,
    absolute: options.absolute ?? false,
    onlyFiles: true,
    unique: true,
    followSymbolicLinks: false,
  });
  return matches.map((match) => normalizePath(match)).sort();
}

export function normalizePath(filePath: string): string {
  return filePath.split(path.sep).join('/');
}

export function resolveFrom(cwd: string, target: string): string {
  return path.isAbsolute(target) ? target : path.resolve(cwd, target);
}

export function relativeTo(cwd: string, target: string): string {
  return normalizePath(path.relative(cwd, target)) || '.';
}

export async function readText(filePath: string): Promise<string> {
  try {
    return await fs.readFile(filePath, 'utf8');
  } catch (error) {
    throw new LingoFlowError(`Cannot read file: ${filePath}`, {
      code: 'READ_FAILED',
      cause: error,
      hint: 'Check the path and file permissions.',
    });
  }
}

export async function readTextIfExists(filePath: string): Promise<string | null> {
  try {
    return await fs.readFile(filePath, 'utf8');
  } catch {
    return null;
  }
}

export async function exists(filePath: string): Promise<boolean> {
  try {
    await fs.access(filePath);
    return true;
  } catch {
    return false;
  }
}

export async function ensureDir(dir: string): Promise<void> {
  await fs.mkdir(dir, { recursive: true });
}

export interface WriteOptions {
  /** Copy the previous file to `<file>.bak` before writing. Default false. */
  backup?: boolean;
  /** Compare content first; skips the write when nothing changed. Returns whether it wrote. */
  skipIfSame?: boolean;
  /** Guard: only overwrite when the current content matches this value. */
  expect?: string | null;
}

export interface WriteResult {
  written: boolean;
  backup?: string;
}

/**
 * Atomic write: temp file + rename. Prevents half-written locale files when a
 * translation run is interrupted.
 */
export async function writeAtomic(filePath: string, content: string, options: WriteOptions = {}): Promise<WriteResult> {
  await ensureDir(path.dirname(filePath));
  const previous = await readTextIfExists(filePath);
  if (previous === content && options.skipIfSame !== false) return { written: false };
  if (options.expect !== undefined && options.expect !== null && previous !== options.expect) {
    throw new LingoFlowError(`File changed on disk since it was read: ${filePath}`, {
      code: 'WRITE_CONFLICT',
      hint: 'Re-run the command; LingoFlow refuses to overwrite concurrent edits.',
    });
  }
  let backup: string | undefined;
  if (options.backup && previous !== null) {
    backup = `${filePath}.bak`;
    await fs.writeFile(backup, previous, 'utf8');
  }
  const temporary = `${filePath}.${process.pid}.${Date.now()}.tmp`;
  await fs.writeFile(temporary, content, 'utf8');
  await fs.rename(temporary, filePath);
  return { written: true, backup };
}

export async function writeJson(filePath: string, value: unknown, options: WriteOptions = {}): Promise<WriteResult> {
  return writeAtomic(filePath, `${JSON.stringify(value, null, 2)}\n`, options);
}

export async function statSize(filePath: string): Promise<number> {
  try {
    const info = await fs.stat(filePath);
    return info.size;
  } catch {
    return 0;
  }
}
