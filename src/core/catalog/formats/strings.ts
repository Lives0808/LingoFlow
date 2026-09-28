import type { CatalogEntry, DecodedCatalog } from '../../types';
import { applyEol, type CatalogFormat, type FormatContext } from './format';

/**
 * Apple `.strings` catalogs (`"key" = "value";`).
 * Comment blocks directly above an entry are attached as translator comments.
 */
export const stringsFormat: CatalogFormat = {
  id: 'strings',
  name: 'Apple .strings',
  extensions: ['.strings'],

  decode(text: string, _ctx: FormatContext): DecodedCatalog {
    const entries: CatalogEntry[] = [];
    let index = 0;
    let pendingComment: string | undefined;
    while (index < text.length) {
      const char = text[index];
      if (char === '/' && text[index + 1] === '*') {
        const end = text.indexOf('*/', index + 2);
        const body = text.slice(index + 2, end === -1 ? text.length : end);
        const cleaned = body
          .split('\n')
          .map((line) => line.replace(/^\s*\*\s?/u, '').trim())
          .filter(Boolean)
          .join(' ');
        if (cleaned) pendingComment = cleaned;
        index = end === -1 ? text.length : end + 2;
        continue;
      }
      if (char === '/' && text[index + 1] === '/') {
        const end = text.indexOf('\n', index + 2);
        const body = text.slice(index + 2, end === -1 ? text.length : end).trim();
        if (body) pendingComment = body;
        index = end === -1 ? text.length : end + 1;
        continue;
      }
      if (char === ' ' || char === '\t' || char === '\n' || char === '\r') {
        index += 1;
        continue;
      }
      if (char === '"') {
        const key = readString(text, index);
        if (!key) {
          index += 1;
          continue;
        }
        index = key.end;
        while (index < text.length && /\s/u.test(text[index] as string)) index += 1;
        if (text[index] !== '=') continue;
        index += 1;
        while (index < text.length && /\s/u.test(text[index] as string)) index += 1;
        if (text[index] !== '"') continue;
        const value = readString(text, index);
        if (!value) continue;
        index = value.end;
        while (index < text.length && text[index] !== ';') index += 1;
        index += 1;
        entries.push({ key: key.value, value: value.value, comment: pendingComment });
        pendingComment = undefined;
        continue;
      }
      index += 1;
    }
    return { entries, meta: {} };
  },

  encode(decoded: DecodedCatalog, ctx: FormatContext): string {
    const lines: string[] = [];
    for (const entry of decoded.entries) {
      if (entry.comment) lines.push(`/* ${entry.comment.replace(/\*\//gu, '*\\/')} */`);
      lines.push(`"${escapeStrings(entry.key)}" = "${escapeStrings(entry.value ?? '')}";`);
    }
    return applyEol(`${lines.join('\n')}\n`, ctx.eol);
  },
};

function readString(text: string, start: number): { value: string; end: number } | null {
  if (text[start] !== '"') return null;
  let index = start + 1;
  let value = '';
  while (index < text.length) {
    const char = text[index] as string;
    if (char === '\\') {
      const escaped = text[index + 1];
      index += 2;
      switch (escaped) {
        case 'n':
          value += '\n';
          break;
        case 't':
          value += '\t';
          break;
        case 'r':
          value += '\r';
          break;
        case '"':
          value += '"';
          break;
        case '\\':
          value += '\\';
          break;
        case 'U': {
          const hex = text.slice(index, index + 8);
          value += String.fromCodePoint(Number.parseInt(hex, 16) || 0);
          index += 8;
          break;
        }
        case 'u': {
          const hex = text.slice(index, index + 4);
          value += String.fromCharCode(Number.parseInt(hex, 16) || 0);
          index += 4;
          break;
        }
        default:
          value += escaped ?? '';
      }
      continue;
    }
    if (char === '"') return { value, end: index + 1 };
    if (char === '\n') return null;
    value += char;
    index += 1;
  }
  return null;
}

function escapeStrings(input: string): string {
  return input.replace(/\\/gu, '\\\\').replace(/"/gu, '\\"').replace(/\n/gu, '\\n').replace(/\t/gu, '\\t');
}
