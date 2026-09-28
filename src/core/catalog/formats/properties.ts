import type { CatalogEntry, DecodedCatalog } from '../../types';
import { applyEol, type CatalogFormat, type FormatContext } from './format';

/**
 * Java `.properties` catalogs.
 * Supports `#`/`!` comments, `=`/`:` separators, line continuations and
 * `\uXXXX` escapes (which stay escaped only when they were escaped before).
 */
export const propertiesFormat: CatalogFormat = {
  id: 'properties',
  name: 'Java properties',
  extensions: ['.properties'],

  decode(text: string, _ctx: FormatContext): DecodedCatalog {
    const entries: CatalogEntry[] = [];
    const lines = text.replace(/\r\n?/gu, '\n').split('\n');
    let pendingComment: string[] = [];
    let index = 0;
    while (index < lines.length) {
      const rawLine = lines[index] as string;
      const trimmed = rawLine.trim();
      if (trimmed.length === 0) {
        pendingComment = [];
        index += 1;
        continue;
      }
      if (trimmed.startsWith('#') || trimmed.startsWith('!')) {
        pendingComment.push(trimmed.replace(/^[#!]\s?/u, ''));
        index += 1;
        continue;
      }
      let logical = rawLine;
      let consumed = 1;
      while (/(?<!\\)(?:\\\\)*\\$/u.test(logical) && index + consumed < lines.length) {
        logical = `${logical.slice(0, -1)}${(lines[index + consumed] as string).trimStart()}`;
        consumed += 1;
      }
      index += consumed;
      const match = /^\s*([^=:\s]+)\s*[=:]?\s*/u.exec(logical);
      if (!match) continue;
      const key = unescapeProperty(match[1] as string);
      const valueRaw = logical.slice(match[0].length);
      entries.push({
        key,
        value: unescapeProperty(valueRaw.trim()),
        comment: pendingComment.length > 0 ? pendingComment.join(' ') : undefined,
        meta: { escaped: /\\u[0-9a-fA-F]{4}/u.test(valueRaw) },
      });
      pendingComment = [];
    }
    return { entries, meta: {} };
  },

  encode(decoded: DecodedCatalog, ctx: FormatContext): string {
    const lines: string[] = [];
    for (const entry of decoded.entries) {
      if (entry.comment) {
        for (const line of entry.comment.split('\n')) lines.push(`# ${line}`);
      }
      const escaped = entry.meta?.escaped === true ? escapePropertyNonAscii(entry.value ?? '') : escapeProperty(entry.value ?? '');
      lines.push(`${escapeKey(entry.key)}=${escaped}`);
    }
    return applyEol(`${lines.join('\n')}\n`, ctx.eol);
  },
};

function unescapeProperty(input: string): string {
  return input.replace(/\\u([0-9a-fA-F]{4})|\\n|\\r|\\t|\\\\|\\ /gu, (match, hex: string | undefined) => {
    if (hex) return String.fromCharCode(Number.parseInt(hex, 16));
    switch (match) {
      case '\\n':
        return '\n';
      case '\\r':
        return '\r';
      case '\\t':
        return '\t';
      case '\\\\':
        return '\\';
      case '\\ ':
        return ' ';
      default:
        return match;
    }
  });
}

function escapeProperty(input: string): string {
  return input
    .replace(/\\/gu, '\\\\')
    .replace(/\n/gu, '\\n')
    .replace(/\r/gu, '\\r')
    .replace(/\t/gu, '\\t');
}

function escapePropertyNonAscii(input: string): string {
  return escapeProperty(input).replace(/[^\x20-\x7e]/gu, (char) => {
    const code = char.codePointAt(0) ?? 0;
    if (code > 0xffff) {
      const high = Math.floor((code - 0x10000) / 0x400) + 0xd800;
      const low = ((code - 0x10000) % 0x400) + 0xdc00;
      return `\\u${high.toString(16).padStart(4, '0')}\\u${low.toString(16).padStart(4, '0')}`;
    }
    return `\\u${code.toString(16).padStart(4, '0')}`;
  });
}

function escapeKey(input: string): string {
  return input.replace(/([=:\s\\])/gu, '\\$1');
}
