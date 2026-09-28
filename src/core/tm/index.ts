import path from 'node:path';
import type { ResolvedConfig } from '../config/schema';
import type { Logger } from '../logger';
import { resolveFrom } from '../utils/fsx';
import { JsonlMemoryStore } from './jsonl';
import { SqliteMemoryStore } from './sqlite';
import type { MemoryStore } from './store';

export * from './store';
export { JsonlMemoryStore } from './jsonl';
export { SqliteMemoryStore } from './sqlite';

/**
 * Opens the private translation memory.
 * `auto` prefers SQLite and degrades to JSONL when `node:sqlite` is unavailable.
 */
export async function openMemory(config: ResolvedConfig, rootDir: string, logger?: Logger): Promise<MemoryStore> {
  const base = resolveFrom(rootDir, config.translation.memory.path || '.lingoflow/memory');
  const driver = config.translation.memory.driver;
  if (driver === 'jsonl') {
    const store = new JsonlMemoryStore(base);
    await store.init();
    return store;
  }
  if (driver === 'sqlite' || driver === 'auto') {
    try {
      const store = await SqliteMemoryStore.open(path.join(base, 'lingoflow.db'));
      return store;
    } catch (error) {
      if (driver === 'sqlite') throw error;
      logger?.debug(`sqlite driver unavailable (${(error as Error).message}); using JSONL memory instead`);
    }
  }
  const fallback = new JsonlMemoryStore(base);
  await fallback.init();
  return fallback;
}
