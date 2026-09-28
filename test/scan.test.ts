import { test } from 'node:test';
import assert from 'node:assert/strict';
import { tokenize } from '../src/core/code/tokenizer';
import { scanSource } from '../src/core/code/scan';
import { applyRewrites, renderReplacement } from '../src/core/code/rewrite';

const OPTIONS = {
  calls: ['t', 'i18n.t', '$t', 'formatMessage', 'translate'],
  components: ['Trans', 'FormattedMessage'],
  attributes: ['placeholder', 'title', 'aria-label'],
  minWords: 2,
  ignorePatterns: [],
  jsx: true,
  collectHardcoded: true,
};

test('tokenizer handles strings, templates, regex and comments', () => {
  const tokens = tokenize(
    `const a = 'text'; // comment
     const b = \`hello \${name}\`;
     const re = /ab\\/c/g;
     const c = "quote\\"d";`,
    { jsx: true },
  );
  const strings = tokens.filter((token) => token.type === 'string' || token.type === 'template');
  assert.equal(strings.length, 3);
  assert.equal(strings[0]?.value, 'text');
  assert.equal(strings[1]?.interpolated, true);
  assert.equal(tokens.some((token) => token.type === 'regex'), true);
  assert.equal(strings[2]?.value, 'quote"d');
});

test('scanner finds t() calls, dotted chains, components and dynamic keys', () => {
  const source = `
    import { Trans } from 'react-i18next';
    export function App({ t, i18n, translate, key }) {
      return (
        <div>
          {t('app.title')}
          {i18n.t('nav.settings')}
          {formatMessage({ id: 'cta.save' })}
          {translate(key)}
          {t(\`dynamic.\${key}\`)}
          <Trans i18nKey="legal.terms" />
          <FormattedMessage id="form.email" />
        </div>
      );
    }
  `;
  const result = scanSource(source, 'src/App.tsx', OPTIONS);
  const keys = result.usages.map((usage) => usage.key).sort();
  assert.deepEqual(keys, ['app.title', 'cta.save', 'form.email', 'legal.terms', 'nav.settings']);
  assert.ok(result.dynamicKeys.includes('key'));
  const usage = result.usages.find((item) => item.key === 'nav.settings');
  assert.equal(usage?.file, 'src/App.tsx');
  assert.equal(usage?.line, 7);
});

test('scanner reports hardcoded UI copy with confidence levels', () => {
  const source = `
    export function Card() {
      return (
        <div>
          <button>Get started for free</button>
          <input placeholder="Email address" />
          <span title="Settings" />
          <p>{'Saved'}</p>
          <code className="text-red-500">{value}</code>
        </div>
      );
    }
  `;
  const result = scanSource(source, 'src/Card.tsx', OPTIONS);
  const texts = result.hardcoded.map((candidate) => candidate.text);
  assert.ok(texts.includes('Get started for free'));
  assert.ok(texts.includes('Email address'));
  assert.ok(texts.includes('Settings'));
  assert.equal(texts.includes('text-red-500'), false);
  const button = result.hardcoded.find((candidate) => candidate.text === 'Get started for free');
  assert.equal(button?.kind, 'jsx-text');
  assert.equal(button?.confidence, 0.9);
  const placeholder = result.hardcoded.find((candidate) => candidate.text === 'Email address');
  assert.equal(placeholder?.attribute, 'placeholder');
});

test('scanner ignores console logs, imports and urls', () => {
  const source = `
    import thing from './some-module';
    console.log('Something went wrong here');
    throw new Error('Unexpected failure happened');
    const url = 'https://example.com/some/page';
  `;
  const result = scanSource(source, 'src/util.ts', { ...OPTIONS, jsx: false });
  assert.deepEqual(result.hardcoded.map((candidate) => candidate.text), []);
});

test('rewrites replace literals from the end and keep offsets valid', () => {
  const source = `<div>Hello world<span title="Settings" /></div>`;
  const helloStart = source.indexOf('Hello world');
  const settingsStart = source.indexOf('"Settings"');
  const result = applyRewrites(source, [
    { start: helloStart, end: helloStart + 'Hello world'.length, replacement: `{t('helloWorld')}` },
    { start: settingsStart, end: settingsStart + '"Settings"'.length, replacement: `{t('settings')}` },
  ]);
  assert.equal(result.applied, 2);
  assert.equal(result.content, `<div>{t('helloWorld')}<span title={t('settings')} /></div>`);
});

test('renderReplacement respects the call template and JSX braces', () => {
  assert.equal(renderReplacement({ kind: 'jsx-text', key: 'a.b' }, { callTemplate: "t('{key}')", jsxBraces: true }), "{t('a.b')}");
  assert.equal(renderReplacement({ kind: 'string', key: 'a.b' }, { callTemplate: "i18n.t('{key}')", jsxBraces: true }), "i18n.t('a.b')");
  assert.equal(
    renderReplacement({ kind: 'attribute', key: 'x' }, { callTemplate: 'intl.formatMessage({ id: "{key}" })', jsxBraces: true }),
    '{intl.formatMessage({ id: "x" })}',
  );
});
