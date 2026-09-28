import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

export interface TempProject {
  dir: string;
  write: (relative: string, content: string) => Promise<string>;
  read: (relative: string) => Promise<string>;
  json: <T = unknown>(relative: string) => Promise<T>;
  cleanup: () => Promise<void>;
}

export async function createTempProject(files: Record<string, string | Record<string, unknown>> = {}): Promise<TempProject> {
  const dir = await mkdtemp(path.join(tmpdir(), 'lingoflow-test-'));
  const write = async (relative: string, content: string): Promise<string> => {
    const target = path.join(dir, relative);
    await mkdir(path.dirname(target), { recursive: true });
    await writeFile(target, content, 'utf8');
    return target;
  };
  for (const [relative, content] of Object.entries(files)) {
    await write(relative, typeof content === 'string' ? content : `${JSON.stringify(content, null, 2)}\n`);
  }
  return {
    dir,
    write,
    read: (relative: string) => readFile(path.join(dir, relative), 'utf8'),
    json: async <T = unknown>(relative: string): Promise<T> => JSON.parse(await readFile(path.join(dir, relative), 'utf8')) as T,
    cleanup: () => rm(dir, { recursive: true, force: true }),
  };
}

export const BASIC_CONFIG = {
  sourceLocale: 'en',
  locales: ['en', 'zh-CN'],
  catalogs: [{ path: 'locales/{locale}.json', format: 'json' }],
  code: { include: ['src/**/*.tsx'], exclude: [] },
  translation: { engine: 'offline', policy: 'missing' },
  length: { enabled: true, unit: 'chars', default: { max: 60, min: 0, hard: false, widget: 'label' } },
  write: { atomic: true, backup: false, indent: 2, eol: 'lf' },
  report: { dir: '.lingoflow/report' },
};
