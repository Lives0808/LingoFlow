import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConfig } from '../src/core/config/load';
import { loadProject, fileFor } from '../src/pipeline/context';
import { runSync } from '../src/pipeline/sync';
import { runCheck, runAlign } from '../src/pipeline/check';
import { scanProject, compareKeyCoverage } from '../src/pipeline/scan';
import { writeReports } from '../src/report/write';
import { logger } from '../src/core/logger';
import { createTempProject, BASIC_CONFIG } from './helpers';

logger.configure({ level: 'error' });

const SOURCE_CATALOG = {
  app: { title: 'LingoFlow', subtitle: 'Ship 12 languages without breaking the layout' },
  cta: { save: 'Save changes', cancel: 'Cancel' },
  form: { email: 'Email address' },
  items: { count: '{count, plural, one {# item} other {# items}}' },
  dialog: { deleteTitle: 'Delete {name}?' },
  message: { welcome: 'Welcome back, {name}!' },
};

const ZH_CATALOG = {
  app: { title: 'LingoFlow 演示', subtitle: '一次发布 12 种语言，布局不错位' },
  cta: { save: '保存更改' },
  form: { email: '' },
  items: { count: '{数量, plural, other {# 项}}' },
  message: { welcome: 'Welcome back, {name}!' },
};

async function setupProject() {
  const project = await createTempProject({
    'lingoflow.config.json': BASIC_CONFIG,
    'locales/en.json': SOURCE_CATALOG,
    'locales/zh-CN.json': ZH_CATALOG,
    'src/App.tsx': `
      export function App({ t }) {
        return (<div>
          {t('app.title')}
          {t('cta.save')}
          {t('form.email')}
          {t('items.count')}
          {t('message.welcome')}
          {t('nav.settings')}
          <button>Get started for free</button>
        </div>);
      }
    `,
  });
  const loaded = await loadConfig({ cwd: project.dir });
  const context = await loadProject({ loaded, logger });
  return { project, loaded, context };
}

test('scan finds usages, undefined keys and hardcoded copy', async () => {
  const { project, loaded, context } = await setupProject();
  const scan = await scanProject(loaded.config, loaded.rootDir);
  const defined = new Set<string>();
  for (const file of context.sourceFiles.values()) for (const entry of file.entries) defined.add(entry.key);
  const coverage = compareKeyCoverage(scan, defined);
  assert.ok(scan.usedKeys.includes('app.title'));
  assert.deepEqual(coverage.undefinedKeys, ['nav.settings']);
  assert.ok(coverage.unusedKeys.includes('cta.cancel'));
  assert.ok(scan.hardcoded.some((candidate) => candidate.text === 'Get started for free'));
  await context.store.close();
  await project.cleanup();
});

test('check reports missing keys, ICU drift and untranslated values', async () => {
  const { project, loaded, context } = await setupProject();
  const { report } = await runCheck({ project: context, fix: false, write: false });
  const codes = new Set(report.issues.map((issue) => issue.code));
  assert.ok(codes.has('missing'), 'missing keys');
  assert.ok(codes.has('empty'), 'empty value');
  assert.ok(codes.has('icu.invalid'), 'ICU drift');
  assert.ok(codes.has('untranslated'), 'identical to source');
  const zh = report.locales.find((locale) => locale.locale === 'zh-CN');
  assert.equal(zh?.coverage, 0.625);
  await context.store.close();
  await project.cleanup();
});

test('sync translates missing keys, applies fixes and writes back', async () => {
  const { project, loaded, context } = await setupProject();
  const { report, written } = await runSync({ project: context, fix: true, scan: true, sampleLimit: 5 });
  assert.ok(written.some((file) => file.includes('zh-CN.json')), written.join(','));
  const zh = await project.json<{ app: Record<string, string>; form: Record<string, string>; dialog: Record<string, string>; cta: Record<string, string> }>(
    'locales/zh-CN.json',
  );
  assert.match(zh.form.email, /^邮箱 ?address$/u, 'dictionary drives the first word, the rest is kept');
  assert.equal(zh.dialog.deleteTitle.includes('{name}'), true);
  assert.equal(zh.cta.cancel.length, 2);
  assert.equal(report.summary.translated >= 2, true);
  // The intentionally broken fixture still surfaces its ICU drift…
  assert.ok(
    report.issues.some(
      (issue) => issue.key === 'items.count' && (issue.code === 'placeholder.extra' || issue.code === 'auto.fixed'),
    ),
    'ICU drift in the fixture is reported',
  );
  // …but the freshly translated keys are clean.
  for (const key of ['cta.cancel', 'dialog.deleteTitle', 'form.email']) {
    const keyIssues = report.issues.filter((issue) => issue.key === key && issue.severity === 'error');
    assert.deepEqual(keyIssues, [], `${key} must be error free: ${JSON.stringify(keyIssues)}`);
  }
  // The translation memory now knows these strings.
  const memory = await context.store.stats();
  assert.ok(memory.entries >= 2, String(memory.entries));
  await context.store.close();
  await project.cleanup();
});

test('sync reuses translation memory and never re-translates frozen entries', async () => {
  const { project, context } = await setupProject();
  const row = await context.store.exact('en', 'zh-CN', 'Save changes');
  assert.equal(row, null);
  await context.store.upsertMany([
    {
      sourceLocale: 'en',
      targetLocale: 'zh-CN',
      source: 'Save changes',
      target: '保存',
      engine: 'human',
      confidence: 1,
      refs: [],
      approved: true,
      frozen: true,
      sourceHash: (
        await import('../src/core/utils/hash')
      ).sha256('en\u0000Save changes'),
    },
  ]);
  const { report } = await runSync({ project: context, force: true, scan: false, sampleLimit: 3 });
  const zh = await project.json<{ cta: Record<string, string> }>('locales/zh-CN.json');
  assert.equal(zh.cta.save, '保存更改', 'frozen entries are never overwritten, even with --force');
  assert.ok(
    report.issues.some((issue) => issue.code === 'frozen.protected'),
    'the difference from the approved value is reported',
  );
  await context.store.close();
  await project.cleanup();
});

test('align fills missing keys and prunes stale ones', async () => {
  const { project, loaded, context } = await setupProject();
  const zhFile = fileFor(context, [...context.sourceFiles.keys()][0] as string, 'zh-CN');
  assert.ok(zhFile);
  await project.write('locales/zh-CN.json', JSON.stringify({ ...ZH_CATALOG, obsolete: { key: '旧' } }, null, 2));
  const reloaded = await loadConfig({ cwd: project.dir });
  const context2 = await loadProject({ loaded: reloaded, logger });
  const { report, written } = await runAlign({ project: context2, fill: true, prune: true, write: true });
  assert.ok(written.some((file) => file.includes('zh-CN.json')));
  const zh = await project.json<{ dialog: Record<string, string>; obsolete?: Record<string, string> }>('locales/zh-CN.json');
  assert.equal(zh.obsolete, undefined);
  assert.equal(zh.dialog.deleteTitle, 'Delete {name}?', 'filled with source text');
  assert.ok(report.issues.some((issue) => issue.code === 'missing'));
  await context.store.close();
  await context2.store.close();
  void loaded;
  await project.cleanup();
});

test('learns correction rules from reviewer edits (防返工 loop)', async () => {
  // A dedicated project with two similar strings, so a reviewer's terminology
  // fix repeats and becomes a reusable rule.
  const project = await createTempProject({
    'lingoflow.config.json': BASIC_CONFIG,
    'locales/en.json': {
      form: { email: 'Email address', emailHint: 'Email address for invoices' },
      cta: { save: 'Save changes' },
    },
    'locales/zh-CN.json': { form: { email: '', emailHint: '' }, cta: { save: '' } },
  });
  const loaded = await loadConfig({ cwd: project.dir });
  const context = await loadProject({ loaded, logger });
  await runSync({ project: context, scan: false, sampleLimit: 2 });

  const zh = await project.json<{ form: Record<string, string>; cta: Record<string, string> }>('locales/zh-CN.json');
  assert.ok(zh.form.email.includes('邮箱'), `machine output: ${zh.form.email}`);
  // The reviewer prefers 电子邮箱 over 邮箱, twice.
  zh.form.email = zh.form.email.replace('邮箱', '电子邮箱');
  zh.form.emailHint = zh.form.emailHint.replace('邮箱', '电子邮箱');
  await project.write('locales/zh-CN.json', JSON.stringify(zh, null, 2));
  await context.store.close();

  const reloaded = await loadConfig({ cwd: project.dir });
  const context2 = await loadProject({ loaded: reloaded, logger });
  const { report } = await runSync({ project: context2, scan: false, sampleLimit: 2 });

  const rules = await context2.store.listRules({ kind: 'correction' });
  assert.ok(rules.length > 0, 'correction rules were learned');
  const rule = rules.find((candidate) => candidate.pattern.includes('邮箱'));
  assert.ok(rule, JSON.stringify(rules));
  assert.equal(rule?.enabled, true);
  assert.ok((rule?.confidence ?? 0) >= 0.9);
  assert.ok(report.learned && report.learned.corrections > 0);

  // A third key with the same wording is fixed automatically on the next run.
  await project.write('locales/en.json', JSON.stringify({ form: { email: 'Email address', emailHint: 'Email address for invoices', emailCopy: 'Email address for support' }, cta: { save: 'Save changes' } }, null, 2));
  const reloaded2 = await loadConfig({ cwd: project.dir });
  const context3 = await loadProject({ loaded: reloaded2, logger });
  await runSync({ project: context3, scan: false, sampleLimit: 2 });
  const after = await project.json<{ form: Record<string, string> }>('locales/zh-CN.json');
  assert.ok(after.form.emailCopy.includes('电子邮箱'), `learned rule applied: ${after.form.emailCopy}`);
  await context2.store.close();
  await context3.store.close();
  await project.cleanup();
});

test('html, markdown and sarif reports are generated', async () => {
  const { project, loaded, context } = await setupProject();
  const { report } = await runCheck({ project: context, fix: false, write: false });
  const artifacts = await writeReports(report, loaded.config, loaded.rootDir);
  assert.equal(artifacts.files.length, 4);
  const html = await project.read('.lingoflow/report/index.html');
  assert.ok(html.includes('UI fit'));
  assert.ok(html.includes('zh-CN'));
  const sarif = await project.json<{ version: string; runs: unknown[] }>('.lingoflow/report/report.sarif');
  assert.equal(sarif.version, '2.1.0');
  assert.ok(sarif.runs.length === 1);
  const markdown = await project.read('.lingoflow/report/report.md');
  assert.ok(markdown.includes('| Locale |'));
  await context.store.close();
  await project.cleanup();
});
