import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JsonlMemoryStore } from '../src/core/tm/jsonl';
import { SqliteMemoryStore } from '../src/core/tm/sqlite';
import { bestFuzzyMatch } from '../src/core/translate/planner';
import { createTempProject } from './helpers';
import { sha256 } from '../src/core/utils/hash';
import type { MemoryStore } from '../src/core/tm/store';

async function exercise(store: MemoryStore, label: string): Promise<void> {
  const row = {
    sourceLocale: 'en',
    targetLocale: 'zh-CN',
    source: 'Settings',
    target: '设置',
    engine: 'human',
    confidence: 1,
    refs: ['src/App.tsx:10'],
    approved: false,
    frozen: false,
    sourceHash: sha256('en\u0000Settings'),
  };
  await store.upsertMany([row]);
  const exact = await store.exact('en', 'zh-CN', 'Settings');
  assert.equal(exact?.target, '设置', label);

  // Machine output must not overwrite a frozen entry.
  await store.update(exact?.id as number, { frozen: true });
  await store.upsertMany([{ ...row, target: '参数', engine: 'offline', confidence: 0.5 }]);
  const frozenRow = await store.exact('en', 'zh-CN', 'Settings');
  assert.equal(frozenRow?.target, '设置', `${label}: frozen entry was protected`);
  assert.equal(frozenRow?.frozen, true);

  // But it can overwrite when approved/frozen are off.
  await store.update(frozenRow?.id as number, { frozen: false });
  await store.upsertMany([{ ...row, target: '参数', engine: 'offline', confidence: 0.5 }]);
  const updated = await store.exact('en', 'zh-CN', 'Settings');
  assert.equal(updated?.target, '参数', label);

  const stats = await store.stats();
  assert.equal(stats.entries, 1, label);
  assert.equal(stats.byLocale[0]?.locale, 'zh-CN');

  // Rules
  const added = await store.addRules([
    { kind: 'glossary', locale: 'zh-CN', pattern: 'Settings', value: '{"target":"设置"}', priority: 60, enabled: true, origin: 'import', confidence: 1 },
  ]);
  assert.equal(added, 1, label);
  const duplicated = await store.addRules([
    { kind: 'glossary', locale: 'zh-CN', pattern: 'Settings', value: '{"target":"设置"}', priority: 60, enabled: true, origin: 'import', confidence: 1 },
  ]);
  assert.equal(duplicated, 0, `${label}: duplicate rules are ignored`);
  assert.equal((await store.listRules({ kind: 'glossary' })).length, 1);

  // Cache
  await store.cacheSet('k1', '翻译', 'offline');
  assert.equal((await store.cacheGet('k1'))?.value, '翻译', label);
  await store.cacheClear();
  assert.equal(await store.cacheGet('k1'), null);

  // Suggestions + runs
  await store.addSuggestions([{ sourceHash: row.sourceHash, source: 'Settings', locale: 'zh-CN', machine: '设定', engine: 'offline', createdAt: new Date().toISOString() }]);
  assert.equal((await store.listSuggestions({ locale: 'zh-CN' })).length, 1, label);
  await store.addRun({
    command: 'sync',
    startedAt: new Date().toISOString(),
    finishedAt: new Date().toISOString(),
    engine: 'offline',
    locales: ['zh-CN'],
    translated: 1,
    fixed: 0,
    issues: 0,
    errors: 0,
    warnings: 0,
    filesWritten: ['locales/zh-CN.json'],
    durationMs: 12,
  });
  assert.equal((await store.listRuns()).length, 1, label);

  await store.remove([updated?.id as number]);
  assert.equal((await store.all()).length, 0, label);
}

test('JSONL memory store implements the full contract', async () => {
  const project = await createTempProject();
  const store = new JsonlMemoryStore(`${project.dir}/.lingoflow/memory`);
  await store.init();
  await exercise(store, 'jsonl');
  await project.cleanup();
});

test('SQLite memory store implements the full contract', async (t) => {
  if (!(await SqliteMemoryStore.isAvailable())) {
    t.skip('node:sqlite is not available on this runtime');
    return;
  }
  const project = await createTempProject();
  const store = await SqliteMemoryStore.open(`${project.dir}/.lingoflow/memory/lingoflow.db`);
  await exercise(store, 'sqlite');
  await store.close();
  await project.cleanup();
});

test('fuzzy memory matching finds close source strings', () => {
  const pairs = [
    {
      sourceLocale: 'en',
      targetLocale: 'zh-CN',
      source: 'Save changes',
      target: '保存更改',
      engine: 'human',
      confidence: 1,
      refs: [],
      approved: true,
      frozen: false,
      sourceHash: sha256('en\u0000Save changes'),
    },
  ];
  const match = bestFuzzyMatch('Save change', pairs, 0.7, 'zh-CN');
  assert.ok(match);
  assert.ok((match?.score ?? 0) >= 0.7);
  const miss = bestFuzzyMatch('Completely different sentence', pairs, 0.7, 'zh-CN');
  assert.equal(miss, null);
});
