import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseConfig } from '../src/core/config/schema';
import { createValidationContext, validateEntry } from '../src/core/validate/index';
import { parseIcu } from '../src/core/validate/icu';
import { comparePlaceholders, extractPlaceholders } from '../src/core/validate/placeholders';
import { compareTags } from '../src/core/validate/tags';
import { BUILD_RULESET } from './rules-fixture';

function makeContext(sourceEntries: Record<string, string>) {
  const config = parseConfig({
    sourceLocale: 'en',
    locales: ['en', 'zh-CN', 'de'],
    catalogs: [{ path: 'locales/{locale}.json', format: 'json' }],
    translation: { engine: 'offline' },
    length: { enabled: true, unit: 'chars', default: { max: 40, min: 0, hard: false, widget: 'label' } },
  });
  const sources = new Map(Object.entries(sourceEntries).map(([key, value]) => [key, { key, value }]));
  return createValidationContext({ config, ruleSet: BUILD_RULESET, sources });
}

test('placeholder extraction understands every supported syntax', () => {
  const text = 'Hi {name}, you have {{count}} of %d items (%1$s) at ${site} — {n, plural, one {# item} other {# items}}';
  const keys = extractPlaceholders(text).map((token) => token.key);
  assert.ok(keys.includes('{name}'));
  assert.ok(keys.includes('{{count}}'));
  assert.ok(keys.includes('%d'));
  assert.ok(keys.includes('%s'));
  assert.ok(keys.includes('${site}'));
  assert.ok(keys.includes('{n}'));
});

test('placeholder comparison reports missing and extra tokens', () => {
  const comparison = comparePlaceholders('Save {name} to {folder}', '保存 {name} 到 {dir}');
  assert.deepEqual(comparison.missing, ['{folder}']);
  assert.deepEqual(comparison.extra, ['{dir}']);
  assert.deepEqual(comparison.matching, ['{name}']);
});

test('ICU parser validates structure and plural categories per locale', () => {
  const ok = parseIcu('{count, plural, one {# item} other {# items}}', 'en');
  assert.equal(ok.ok, true, JSON.stringify(ok.issues));
  assert.deepEqual(ok.arguments[0]?.categories, ['one', 'other']);

  const zhOnly = parseIcu('{count, plural, other {# 项}}', 'zh-CN');
  assert.equal(zhOnly.ok, true, JSON.stringify(zhOnly.issues));

  const zhWithOne = parseIcu('{count, plural, one {# 项} other {# 项}}', 'zh-CN');
  assert.equal(zhWithOne.ok, false);
  assert.match(zhWithOne.issues.map((issue) => issue.message).join(' '), /not used by this locale/);

  const broken = parseIcu('{count, plural, one {# item}', 'en');
  assert.equal(broken.ok, false);

  const nested = parseIcu('{gender, select, male {He has {count, plural, one {# item} other {# items}}} other {They have items}}', 'en');
  assert.equal(nested.ok, true, JSON.stringify(nested.issues));
});

test('validateEntry catches missing placeholders, ICU drift and length overruns', () => {
  const ctx = makeContext({
    'cta.save': 'Save changes',
    'dialog.delete': 'Delete {name}?',
    'items.count': '{count, plural, one {# item} other {# items}}',
  });

  const missingPlaceholder = validateEntry(ctx, { key: 'dialog.delete', locale: 'zh-CN', value: '删除吗？' });
  assert.ok(missingPlaceholder.issues.some((issue) => issue.code === 'placeholder.missing'));

  const icuDrift = validateEntry(ctx, { key: 'items.count', locale: 'zh-CN', value: '{数量, plural, other {# 项}}' });
  assert.ok(icuDrift.issues.some((issue) => issue.code === 'icu.invalid'));

  const tooLong = validateEntry(ctx, {
    key: 'cta.save',
    locale: 'de',
    value: 'Änderungen jetzt dauerhaft speichern und schließen',
  });
  assert.ok(tooLong.issues.some((issue) => issue.code === 'length.tooLong'));

  const empty = validateEntry(ctx, { key: 'cta.save', locale: 'de', value: '' });
  assert.equal(empty.issues[0]?.code, 'empty');

  const good = validateEntry(ctx, { key: 'cta.save', locale: 'zh-CN', value: '保存' });
  assert.equal(good.issues.filter((issue) => issue.severity === 'error').length, 0);
});

test('CJK typography issues are detected', () => {
  const ctx = makeContext({ 'nav.settings': 'Settings' });
  const result = validateEntry(ctx, { key: 'nav.settings', locale: 'zh-CN', value: '设置 ,然后 保存' });
  const codes = result.issues.map((issue) => issue.code);
  assert.ok(codes.includes('cjk.punctuation') || codes.includes('cjk.space'), codes.join(','));
});

test('tag comparison catches removed markup', () => {
  const comparison = compareTags('Read the <a href="/docs">docs</a> now', '阅读文档');
  assert.deepEqual(comparison.missing, ['a']);
  assert.equal(comparison.unbalanced, false);

  const brokenHtml = compareTags('<b>bold</b>', '<b>粗体');
  assert.equal(brokenHtml.unbalanced, true);

  const lostAttribute = compareTags('<a href="/x">x</a>', '<a>链接</a>');
  assert.match(lostAttribute.attributeIssues.join(' '), /lost the "href" attribute/);
});

test('consistency flags the same source translated two ways', () => {
  const ctx = makeContext({ 'nav.settings': 'Settings', 'menu.settings': 'Settings' });
  const first = validateEntry(ctx, { key: 'nav.settings', locale: 'zh-CN', value: '设置' });
  assert.equal(first.issues.filter((issue) => issue.code === 'consistency.source').length, 0);
  const second = validateEntry(ctx, { key: 'menu.settings', locale: 'zh-CN', value: '设定' });
  assert.ok(second.issues.some((issue) => issue.code === 'consistency.source'));

  // Re-recording the dominant translation keeps the catalog clean.
  const third = validateEntry(ctx, { key: 'menu.settings', locale: 'zh-CN', value: '设置' });
  assert.equal(third.issues.filter((issue) => issue.code === 'consistency.source').length, 0);
});


test('severity overrides and ignore patterns are applied', () => {
  const config = parseConfig({
    sourceLocale: 'en',
    locales: ['en', 'zh-CN'],
    validate: { ignore: { 'zh-CN': ['debug.*'] }, severity: { 'length.tooLong': 'info' } },
    length: { enabled: true, unit: 'chars', default: { max: 5, min: 0, hard: false, widget: 'label' } },
  });
  const sources = new Map([['debug.panel', { key: 'debug.panel', value: 'Debug panel' }]]);
  const ctx = createValidationContext({ config, ruleSet: BUILD_RULESET, sources });
  const ignored = validateEntry(ctx, { key: 'debug.panel', locale: 'zh-CN', value: '调试面板非常长非常长非常长' });
  assert.equal(ignored.issues.length, 0);

  const sources2 = new Map([['form.email', { key: 'form.email', value: 'Email address' }]]);
  const ctx2 = createValidationContext({ config, ruleSet: BUILD_RULESET, sources: sources2 });
  const overridden = validateEntry(ctx2, { key: 'form.email', locale: 'zh-CN', value: '电子邮箱地址用于登录和通知' });
  const long = overridden.issues.find((issue) => issue.code === 'length.tooLong');
  assert.equal(long?.severity, 'info');
});
