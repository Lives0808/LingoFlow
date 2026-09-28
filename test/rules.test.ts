import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildRuleSet } from '../src/core/rules/model';
import { applyCorrections, protectText, restoreText } from '../src/core/rules/apply';
import { learnCorrectionsFromEdits, learnGlossaryFromPairs, learnStylesFromEntries } from '../src/core/rules/learn';
import { fixEntry } from '../src/core/fix/index';
import { adaptToBudget } from '../src/core/fix/adapt';
import { parseConfig } from '../src/core/config/schema';
import { BUILD_RULESET } from './rules-fixture';
import type { RuleRow } from '../src/core/types';

test('glossary and do-not-translate terms are shielded before translation', () => {
  const ruleSet = buildRuleSet([
    { kind: 'glossary', locale: 'zh-CN', pattern: 'Settings', value: '{"target":"设置"}', priority: 60, enabled: true, origin: 'import', confidence: 1 },
    { kind: 'dnt', locale: null, pattern: 'LingoFlow', value: '{"replacement":"LingoFlow"}', priority: 90, enabled: true, origin: 'manual', confidence: 1 },
  ] satisfies RuleRow[]);
  const protection = protectText('Open LingoFlow Settings', ruleSet.glossary, ruleSet.dnt);
  assert.equal(protection.tokens.size, 2);
  assert.equal(protection.text.includes('Settings'), false);
  assert.equal(protection.text.includes('LingoFlow'), false);
  const [firstToken, secondToken] = [...protection.tokens.keys()] as [string, string];
  const restored = restoreText(`打开 ${firstToken} ${secondToken}`, protection);
  assert.equal(restored.includes('设置'), true);
  assert.equal(restored.includes('LingoFlow'), true);
  // Engines that strip zero-width markers still resolve via the LF<n> anchor.
  const stripped = restoreText('打开 LF0 LF1', protection);
  assert.equal(stripped.includes('设置'), true);
  assert.equal(stripped.includes('LingoFlow'), true);
});

test('correction rules fix repeated reviewer edits', () => {
  const rules = applyCorrections('登入页面', [
    { locale: 'zh-CN', pattern: '登入', replacement: '登录', isRegex: false, priority: 70, origin: 'learned', confidence: 0.9 },
  ]);
  assert.equal(rules.text, '登录页面');
  assert.equal(rules.applied.length, 1);
});

test('correction learning turns reviewer edits into rules', () => {
  const edits = [
    { machine: '登入 到 你的 账户', human: '登录 到 您的 账户', source: 'Sign in to your account', locale: 'zh-CN' },
    { machine: '请 登入', human: '请 登录', source: 'Please sign in', locale: 'zh-CN' },
  ];
  const rules = learnCorrectionsFromEdits(edits, { minOccurrences: 2 });
  const rule = rules.find((candidate) => candidate.pattern.includes('登入'));
  assert.ok(rule, JSON.stringify(rules));
  assert.equal(rule?.enabled, true);
  assert.ok((rule?.confidence ?? 0) >= 0.9);
});

test('glossary learning mines consistent term pairs', () => {
  const pairs = [
    { source: 'Save changes now', target: '立即保存更改' },
    { source: 'Please save changes', target: '请保存更改' },
    { source: 'Save changes before leaving', target: '离开前保存更改' },
    { source: 'Nothing to see here', target: '这里什么都没有' },
  ];
  const rules = learnGlossaryFromPairs(pairs, 'zh-CN', { minOccurrences: 3, minConsistency: 0.7, sourceLocale: 'en' });
  const term = rules.find((rule) => rule.pattern === 'save changes');
  assert.ok(term, JSON.stringify(rules.map((rule) => rule.pattern)));
  assert.equal(term?.value, '保存更改');
  assert.ok((term?.confidence ?? 0) > 0.8);
});

test('style learning derives house rules from approved copy', () => {
  const entries = Array.from({ length: 12 }, (_, index) => ({
    value: `项目${index}，设置完成`,
    source: `Project ${index} settings`,
    locale: 'zh-CN',
  }));
  const rules = learnStylesFromEntries(entries);
  const fullwidth = rules.find((rule) => rule.pattern === 'cjk.fullwidth');
  assert.ok(fullwidth, JSON.stringify(rules));
  assert.equal(fullwidth?.value, 'true');
});

test('fixEntry normalises line breaks, punctuation and restores placeholders', () => {
  const config = parseConfig({
    sourceLocale: 'en',
    locales: ['en', 'zh-CN'],
    translation: { engine: 'offline' },
    length: { enabled: true, unit: 'chars', default: { max: 120, min: 0, hard: false, widget: 'label' } },
  });
  const outcome = fixEntry(
    { config, ruleSet: BUILD_RULESET, sourceLocale: 'en' },
    { key: 'dialog.body', locale: 'zh-CN', source: 'Delete {name} now.', target: '删除 {name} 吧。\n多余的换行' },
  );
  assert.equal(outcome.text.includes('\n'), false);
  assert.equal(outcome.text.includes('{name}'), true);
  assert.ok(outcome.fixes.length > 0);

  const dropped = fixEntry(
    { config, ruleSet: BUILD_RULESET, sourceLocale: 'en' },
    { key: 'dialog.body', locale: 'zh-CN', source: 'Delete {name} now', target: '删除吧' },
  );
  assert.equal(dropped.text.includes('{name}'), true);
});

test('length adaptation abbreviates before giving up', () => {
  const config = parseConfig({
    sourceLocale: 'en',
    locales: ['en', 'de'],
    length: {
      enabled: true,
      unit: 'chars',
      default: { max: 40, min: 0, hard: true, widget: 'button' },
      autofix: { enabled: true, strategies: ['abbreviate', 'shorten'], maxRounds: 2 },
    },
  });
  const result = adaptToBudget('Please save the configuration information', 'Save the configuration information', {
    unit: 'chars',
    max: 30,
    hard: true,
  }, { config, locale: 'en', measure: (value) => [...value].length });
  assert.ok(result.text.length <= 30, result.text);
  assert.ok(result.fixes.length > 0);
  assert.equal(result.overBudget, false);
});
