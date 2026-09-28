import { test } from 'node:test';
import assert from 'node:assert/strict';
import { getFormat } from '../src/core/catalog/formats/index';
import type { FormatContext } from '../src/core/catalog/formats/index';

const ctx: FormatContext = {
  locale: 'zh-CN',
  path: 'locales/zh-CN.json',
  keySeparator: '.',
  nesting: true,
  indent: 2,
  eol: 'lf',
};

test('JSON round-trip keeps nesting, order and comments', () => {
  const format = getFormat('json');
  const text = `{
  // app title shown in the header
  "app": {
    "title": "LingoFlow",
    "items": ["one", "two"]
  },
  "cta": { "save": "Save" },
}`;
  const decoded = format.decode(text, ctx);
  assert.deepEqual(
    decoded.entries.map((entry) => entry.key),
    ['app.title', 'app.items.0', 'app.items.1', 'cta.save'],
  );
  assert.equal(decoded.entries[0]?.comment, 'app title shown in the header');
  const encoded = format.encode({ entries: decoded.entries, meta: decoded.meta }, ctx);
  assert.match(encoded, /LingoFlow/);
  const reparsed = format.decode(encoded, { ...ctx, previous: decoded });
  assert.equal(reparsed.entries.length, 4);
});

test('YAML keeps comments and quotes when values change', () => {
  const format = getFormat('yaml');
  const text = `# product strings\napp:\n  title: LingoFlow # keep short\n  subtitle: Ship fast\n`;
  const decoded = format.decode(text, ctx);
  assert.equal(decoded.entries.length, 2);
  const entries = decoded.entries.map((entry) => (entry.key === 'app.title' ? { ...entry, value: 'LingoFlow 演示' } : entry));
  const encoded = format.encode({ entries, meta: decoded.meta }, { ...ctx, locale: 'zh-CN', previous: decoded });
  assert.match(encoded, /# product strings/);
  assert.match(encoded, /keep short/);
  assert.match(encoded, /LingoFlow 演示/);
});

test('properties handles escapes and comments', () => {
  const format = getFormat('properties');
  const decoded = format.decode('# greeting\napp.title=LingoFlow\napp.hint=Line1\\nLine2\n', ctx);
  assert.equal(decoded.entries[0]?.comment, 'greeting');
  assert.equal(decoded.entries[1]?.value, 'Line1\nLine2');
  const encoded = format.encode({ entries: decoded.entries, meta: {} }, ctx);
  assert.match(encoded, /app\.hint=Line1\\nLine2/);
});

test('gettext PO maps plural categories and keeps the header', () => {
  const format = getFormat('po');
  const text = `msgid ""
msgstr ""
"Language: zh-CN\\n"
"Plural-Forms: nplurals=1; plural=0;\\n"

#. Item counter
#: src/App.tsx:28
msgid "{count, plural, one {# item} other {# items}}"
msgstr "{count, plural, other {# 项}}"
`;
  const decoded = format.decode(text, ctx);
  assert.equal(decoded.entries.length, 1);
  assert.equal(decoded.entries[0]?.key, '{count, plural, one {# item} other {# items}}');
  assert.equal(decoded.entries[0]?.comment, 'Item counter');
  assert.deepEqual(decoded.entries[0]?.refs, ['src/App.tsx:28']);
  const entries = decoded.entries.map((entry) => ({ ...entry, value: '{count, plural, other {# 个项目}}' }));
  const encoded = format.encode({ entries, meta: decoded.meta }, ctx);
  assert.match(encoded, /Language: zh-CN/);
  assert.match(encoded, /#\. Item counter/);
  assert.match(encoded, /#: src\/App\.tsx:28/);
  assert.match(encoded, /个项目/);
});

test('ARB preserves @metadata and adds descriptions for new keys', () => {
  const format = getFormat('arb');
  const text = `{
  "@@locale": "zh-CN",
  "appTitle": "LingoFlow",
  "@appTitle": { "description": "Header title", "placeholders": {} }
}`;
  const decoded = format.decode(text, ctx);
  assert.equal(decoded.entries.length, 1);
  assert.equal(decoded.entries[0]?.comment, 'Header title');
  const entries = [...decoded.entries.map((entry) => ({ ...entry, value: 'LingoFlow 演示' })), { key: 'newKey', value: '新增', comment: 'translator note' }];
  const encoded = format.encode({ entries, meta: decoded.meta }, ctx);
  assert.match(encoded, /"@@locale": "zh-CN"/);
  assert.match(encoded, /LingoFlow 演示/);
  assert.match(encoded, /"newKey": "新增"/);
  assert.match(encoded, /"description": "translator note"/);
});

test('.strings round-trips comments and escapes', () => {
  const format = getFormat('strings');
  const text = `/* Greeting shown on launch */
"app.title" = "LingoFlow";
"app.quote" = "He said \\"hi\\"";
`;
  const decoded = format.decode(text, ctx);
  assert.equal(decoded.entries.length, 2);
  assert.equal(decoded.entries[0]?.comment, 'Greeting shown on launch');
  assert.equal(decoded.entries[1]?.value, 'He said "hi"');
  const encoded = format.encode({ entries: decoded.entries, meta: {} }, ctx);
  const reparsed = format.decode(encoded, ctx);
  assert.equal(reparsed.entries[1]?.value, 'He said "hi"');
});

test('CSV updates only the column that belongs to the locale', () => {
  const format = getFormat('csv');
  const text = 'key,en,zh-CN\napp.title,LingoFlow,LingoFlow 演示\ncta.save,Save changes,保存更改\n';
  const decoded = format.decode(text, { ...ctx, locale: 'zh-CN' });
  assert.equal(decoded.entries[1]?.value, '保存更改');
  const entries = decoded.entries.map((entry) => (entry.key === 'cta.save' ? { ...entry, value: '保存' } : entry));
  const encoded = format.encode({ entries, meta: decoded.meta }, ctx);
  assert.match(encoded, /cta\.save,Save changes,保存/);
  assert.match(encoded, /app\.title,LingoFlow,LingoFlow 演示/);
});

test('TS/JS catalogs keep the surrounding code', () => {
  const format = getFormat('tsjs');
  const text = `import type { Messages } from './types';

export const messages: Messages = {
  // header
  'app.title': 'LingoFlow',
  cta: { save: 'Save' },
} as const;
`;
  const decoded = format.decode(text, ctx);
  assert.equal(decoded.entries.length, 2);
  assert.equal(decoded.entries[0]?.comment, 'header');
  const entries = decoded.entries.map((entry) => (entry.key === 'cta.save' ? { ...entry, value: '保存' } : entry));
  const encoded = format.encode({ entries, meta: decoded.meta }, ctx);
  assert.match(encoded, /import type \{ Messages \}/);
  assert.match(encoded, /as const;/);
  assert.match(encoded, /保存/);
});
