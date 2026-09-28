import type { CatalogEntry, DecodedCatalog } from '../../types';
import { CONTEXT_SEPARATOR } from '../keys';
import { applyEol, type CatalogFormat, type FormatContext } from './format';

interface PoEntry {
  comments: string[];
  extracted: string[];
  refs: string[];
  flags: string[];
  previous: string[];
  msgctxt?: string;
  msgid: string;
  msgidPlural?: string;
  msgstr: string[];
  obsolete?: boolean;
}

interface PoMeta {
  [key: string]: unknown;
  headerLines?: string[];
  entries?: PoEntry[];
  obsolete?: string[];
  locale?: string;
  pluralCategories?: string[];
}

const PLURAL_CATEGORY_ORDER = ['zero', 'one', 'two', 'few', 'many', 'other'];

/**
 * Gettext `.po` catalogs.
 * Plural entries are exposed per CLDR category (`key[one]`, `key[other]`, ...)
 * so validators can check each form and the writer can map them back to `msgstr[N]`.
 */
export const poFormat: CatalogFormat = {
  id: 'po',
  name: 'Gettext PO',
  extensions: ['.po', '.pot'],

  decode(text: string, ctx: FormatContext): DecodedCatalog {
    const normalized = text.replace(/\r\n?/gu, '\n');
    const blocks = splitBlocks(normalized);
    const entries: CatalogEntry[] = [];
    const template: PoEntry[] = [];
    const obsolete: string[] = [];
    let headerLines: string[] = [];
    let locale = ctx.locale;
    const pluralCategories = PLURAL_CATEGORY_ORDER;

    for (const block of blocks) {
      if (block.trim().length === 0) continue;
      if (block.trimStart().startsWith('#~')) {
        obsolete.push(block);
        continue;
      }
      const parsed = parseBlock(block);
      if (!parsed) continue;
      template.push(parsed);
      const baseKey = parsed.msgctxt ? `${parsed.msgctxt}${CONTEXT_SEPARATOR}${parsed.msgid}` : parsed.msgid;
      if (parsed.msgid === '' && parsed.msgstr.length === 1 && parsed.msgstr[0] === '') continue;
      if (parsed.msgid === '' && !parsed.msgctxt) {
        headerLines = (parsed.msgstr[0] ?? '').split('\n').filter((line) => line.trim().length > 0);
        for (const line of headerLines) {
          const match = /^Language:\s*(.+)$/iu.exec(line.trim());
          if (match) locale = (match[1] as string).trim();
        }
        continue;
      }
      const comment = [...parsed.extracted, ...parsed.comments].join(' ').trim() || undefined;
      if (parsed.msgidPlural) {
        const categories = pickCategories(locale, parsed.msgstr.length);
        parsed.msgstr.forEach((value, index) => {
          const category = categories[index] ?? String(index);
          entries.push({
            key: `${baseKey}[${category}]`,
            value,
            comment,
            refs: parsed.refs,
            meta: { poIndex: index, plural: true },
          });
        });
      } else {
        entries.push({ key: baseKey, value: parsed.msgstr[0] ?? '', comment, refs: parsed.refs });
      }
    }

    const meta: PoMeta = { headerLines, entries: template, obsolete, locale, pluralCategories };
    return { entries, meta };
  },

  encode(decoded: DecodedCatalog, ctx: FormatContext): string {
    const meta = (decoded.meta ?? {}) as PoMeta;
    const locale = meta.locale ?? ctx.locale;
    const template = meta.entries ?? [];
    const byKey = new Map<string, PoEntry>();
    for (const entry of template) {
      const baseKey = entry.msgctxt ? `${entry.msgctxt}${CONTEXT_SEPARATOR}${entry.msgid}` : entry.msgid;
      byKey.set(baseKey, entry);
    }

    const grouped = new Map<string, { entry: PoEntry; values: Map<number, string>; order: string[] }>();
    for (const entry of decoded.entries) {
      const pluralMatch = /^(.*)\[([a-z0-9]+)\]$/u.exec(entry.key);
      const baseKey = pluralMatch ? (pluralMatch[1] as string) : entry.key;
      const category = pluralMatch ? (pluralMatch[2] as string) : null;
      let group = grouped.get(baseKey);
      if (!group) {
        const found = byKey.get(baseKey);
        const parsed: PoEntry =
          found ??
          (() => {
            const [ctxt, id] = baseKey.includes(CONTEXT_SEPARATOR) ? baseKey.split(CONTEXT_SEPARATOR) : [undefined, baseKey];
            return {
              comments: [],
              extracted: [],
              refs: [],
              flags: [],
              previous: [],
              msgctxt: ctxt,
              msgid: id as string,
              msgstr: [],
            };
          })();
        group = { entry: parsed, values: new Map(), order: [] };
        grouped.set(baseKey, group);
      }
      if (category) {
        const index = pluralIndex(category, locale, group.entry.msgstr.length);
        group.values.set(index, entry.value ?? '');
      } else {
        group.values.set(0, entry.value ?? '');
      }
      if (entry.refs && entry.refs.length > 0 && group.entry.refs.length === 0) group.entry.refs = entry.refs;
      if (entry.comment && group.entry.extracted.length === 0) group.entry.extracted = [entry.comment];
    }

    const header = (meta.headerLines ?? []).filter((line) => line.trim().length > 0);
    const headerBlock = ['msgid ""', 'msgstr ""', ...header.map((line) => `"${escapePo(line)}\\n"`)].join('\n');
    const chunks: string[] = [headerBlock];
    for (const [, group] of grouped) {
      chunks.push(renderEntry(group.entry, group.values));
    }
    if (meta.obsolete && meta.obsolete.length > 0) chunks.push(...meta.obsolete);
    return applyEol(`${chunks.join('\n\n')}\n`, ctx.eol);
  },
};

function splitBlocks(text: string): string[] {
  const lines = text.split('\n');
  const blocks: string[] = [];
  let current: string[] = [];
  for (const line of lines) {
    if (line.trim().length === 0 && current.length > 0) {
      blocks.push(current.join('\n'));
      current = [];
      continue;
    }
    if (line.trim().length === 0) continue;
    current.push(line);
  }
  if (current.length > 0) blocks.push(current.join('\n'));
  return blocks;
}

function parseBlock(block: string): PoEntry | null {
  const entry: PoEntry = { comments: [], extracted: [], refs: [], flags: [], previous: [], msgid: '', msgstr: [] };
  const lines = block.split('\n');
  let field: 'msgctxt' | 'msgid' | 'msgidPlural' | 'msgstr' | 'previous' | null = null;
  let pluralIndexCurrent = 0;
  let sawField = false;

  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (line.startsWith('#.')) {
      entry.extracted.push(line.slice(2).trim());
      continue;
    }
    if (line.startsWith('#:')) {
      entry.refs.push(...line.slice(2).trim().split(/\s+/u).filter(Boolean));
      continue;
    }
    if (line.startsWith('#,')) {
      entry.flags.push(...line.slice(2).trim().split(/\s+/u).filter(Boolean));
      continue;
    }
    if (line.startsWith('#|') || line.startsWith('#~|')) {
      entry.previous.push(line.replace(/^#~?\|\s*/u, ''));
      field = 'previous';
      continue;
    }
    if (line.startsWith('#')) {
      entry.comments.push(line.replace(/^#\s?/u, ''));
      continue;
    }
    if (line.startsWith('msgctxt')) {
      entry.msgctxt = readValue(line.slice('msgctxt'.length));
      field = 'msgctxt';
      sawField = true;
      continue;
    }
    if (line.startsWith('msgid_plural')) {
      entry.msgidPlural = readValue(line.slice('msgid_plural'.length));
      field = 'msgidPlural';
      sawField = true;
      continue;
    }
    if (line.startsWith('msgid')) {
      entry.msgid = readValue(line.slice('msgid'.length));
      field = 'msgid';
      sawField = true;
      continue;
    }
    const pluralMatch = /^msgstr\[(\d+)\]/u.exec(line);
    if (pluralMatch) {
      pluralIndexCurrent = Number(pluralMatch[1]);
      entry.msgstr[pluralIndexCurrent] = readValue(line.slice(pluralMatch[0].length));
      field = 'msgstr';
      sawField = true;
      continue;
    }
    if (line.startsWith('msgstr')) {
      entry.msgstr[0] = readValue(line.slice('msgstr'.length));
      field = 'msgstr';
      sawField = true;
      continue;
    }
    if (line.startsWith('"')) {
      const value = readValue(line).replace(/\\n$/u, '');
      if (field === 'msgctxt') entry.msgctxt = `${entry.msgctxt ?? ''}${value}`;
      else if (field === 'msgid') entry.msgid += value;
      else if (field === 'msgidPlural') entry.msgidPlural = `${entry.msgidPlural ?? ''}${value}`;
      else if (field === 'msgstr') entry.msgstr[pluralIndexCurrent] = `${entry.msgstr[pluralIndexCurrent] ?? ''}${value}`;
      else if (field === 'previous') entry.previous.push(value);
      continue;
    }
  }
  return sawField ? entry : null;
}

function readValue(rest: string): string {
  const trimmed = rest.trim().replace(/;$/, '');
  if (trimmed.length === 0) return '';
  const match = /^"((?:[^"\\]|\\.)*)"/u.exec(trimmed);
  if (!match) return '';
  return unescapePo(match[1] as string);
}

function pickCategories(locale: string, count: number): string[] {
  const lang = locale.toLowerCase().split(/[-_@.]/u)[0] ?? '';
  const table: Record<string, string[]> = {
    zh: ['other'],
    ja: ['other'],
    ko: ['other'],
    th: ['other'],
    vi: ['other'],
    id: ['other'],
    tr: ['other'],
    en: ['one', 'other'],
    de: ['one', 'other'],
    es: ['one', 'other'],
    it: ['one', 'other'],
    nl: ['one', 'other'],
    sv: ['one', 'other'],
    da: ['one', 'other'],
    fr: ['one', 'other'],
    pt: ['one', 'other'],
    ru: ['one', 'few', 'many', 'other'],
    uk: ['one', 'few', 'many', 'other'],
    pl: ['one', 'few', 'many', 'other'],
    cs: ['one', 'few', 'many', 'other'],
    sk: ['one', 'few', 'many', 'other'],
    ar: ['zero', 'one', 'two', 'few', 'many', 'other'],
    he: ['one', 'two', 'many', 'other'],
    ga: ['one', 'two', 'few', 'many', 'other'],
    lt: ['one', 'few', 'many', 'other'],
    lv: ['zero', 'one', 'other'],
    sl: ['one', 'two', 'few', 'other'],
    ro: ['one', 'few', 'other'],
    cy: ['zero', 'one', 'two', 'few', 'many', 'other'],
    ja_JP: ['other'],
  };
  const categories = table[lang] ?? ['one', 'other'];
  if (categories.length === count) return categories;
  if (count === 1) return ['other'];
  return PLURAL_CATEGORY_ORDER.slice(0, count);
}

function pluralIndex(category: string, locale: string, existing: number): number {
  const categories = pickCategories(locale, Math.max(existing, 2));
  const index = categories.indexOf(category);
  if (index >= 0) return index;
  if (category === 'other') return Math.max(existing - 1, 0);
  return Number.parseInt(category, 10) || 0;
}

function renderEntry(entry: PoEntry, values: Map<number, string>): string {
  const lines: string[] = [];
  for (const comment of entry.extracted) lines.push(`#. ${comment}`);
  if (entry.refs.length > 0) lines.push(`#: ${entry.refs.join(' ')}`);
  if (entry.flags.length > 0) lines.push(`#, ${entry.flags.join(', ')}`);
  for (const previous of entry.previous) lines.push(`#| ${previous}`);
  if (entry.msgctxt !== undefined) lines.push(`msgctxt "${escapePo(entry.msgctxt)}"`);
  lines.push(...renderString('msgid', entry.msgid));
  const pluralCount = values.size > 1 || entry.msgidPlural !== undefined;
  if (pluralCount) {
    lines.push(...renderString('msgid_plural', entry.msgidPlural ?? entry.msgid));
    const indices = [...values.keys()].sort((a, b) => a - b);
    for (const index of indices) {
      lines.push(...renderString(`msgstr[${index}]`, values.get(index) ?? ''));
    }
    if (indices.length === 0) lines.push('msgstr[0] ""');
  } else {
    lines.push(...renderString('msgstr', values.get(0) ?? ''));
  }
  return lines.join('\n');
}

function renderString(field: string, value: string): string[] {
  const parts = value.split('\n');
  if (parts.length === 1) return [`${field} "${escapePo(value)}"`];
  return [`${field} ""`, ...parts.map((part, index) => `"${escapePo(part)}${index < parts.length - 1 ? '\\n' : ''}"`)];
}

function escapePo(input: string): string {
  return input.replace(/\\/gu, '\\\\').replace(/"/gu, '\\"').replace(/\t/gu, '\\t').replace(/\r/gu, '\\r');
}

function unescapePo(input: string): string {
  return input.replace(/\\(.)/gu, (_match, char: string) => {
    switch (char) {
      case 'n':
        return '\n';
      case 't':
        return '\t';
      case 'r':
        return '\r';
      case '0':
        return '\0';
      case '\\':
        return '\\';
      case '"':
        return '"';
      default:
        return char;
    }
  });
}
