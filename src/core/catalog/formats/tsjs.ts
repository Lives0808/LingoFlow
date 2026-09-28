import type { CatalogEntry, DecodedCatalog } from '../../types';
import { findObjectStart, nodeToValue, parseJsonish, serializeJsonish, type JsonishNode } from './jsonish';
import { applyEol, type CatalogFormat, type FormatContext } from './format';

interface TsMeta {
  [key: string]: unknown;
  prefix?: string;
  suffix?: string;
  quote?: '"' | "'";
  comments?: Record<string, string>;
  /** Anchor patterns accepted when locating the message object. */
  matched?: string;
}

export const tsjsFormat: CatalogFormat = {
  id: 'tsjs',
  name: 'TS/JS module',
  extensions: ['.ts', '.js', '.mjs', '.cjs'],

  decode(text: string, ctx: FormatContext): DecodedCatalog {
    const anchor = /(?:export\s+default\s+|module\.exports\s*=\s*|export\s+const\s+\w+\s*(?::[^=]+)?=\s*|const\s+\w+\s*(?::[^=]+)?=\s*)/u.exec(text);
    const start = anchor ? findObjectStart(text, anchor.index + anchor[0].length - 1) : findObjectStart(text, 0);
    if (start < 0) {
      return { entries: [], meta: { prefix: text, suffix: '' } satisfies TsMeta };
    }
    const parsed = parseJsonish(text, { start });
    const value = nodeToValue(parsed.node) as Record<string, unknown>;
    const entries: CatalogEntry[] = [];
    for (const [key, raw] of Object.entries(value)) {
      if (raw === null || typeof raw !== 'object') {
        entries.push({ key, value: raw === null || raw === undefined ? null : String(raw), comment: parsed.comments.get(key) });
      } else {
        collectNested(raw, key, entries, parsed.comments, ctx);
      }
    }
    const quote = detectQuote(text, start);
    const meta: TsMeta = {
      prefix: text.slice(0, start),
      suffix: text.slice(parsed.node.end),
      quote,
      matched: anchor?.[0],
    };
    if (parsed.comments.size > 0) meta.comments = Object.fromEntries(parsed.comments);
    return { entries, meta };
  },

  encode(decoded: DecodedCatalog, ctx: FormatContext): string {
    const meta = (decoded.meta ?? {}) as TsMeta;
    const prefix = meta.prefix ?? 'export default ';
    const suffix = meta.suffix ?? ';\n';
    const tree: Record<string, unknown> = {};
    const separator = ctx.keySeparator || '.';
    for (const entry of decoded.entries) {
      const segments = ctx.nesting ? entry.key.split(separator) : [entry.key];
      let cursor: Record<string, unknown> = tree;
      for (let i = 0; i < segments.length - 1; i += 1) {
        const segment = segments[i] as string;
        if (cursor[segment] === null || typeof cursor[segment] !== 'object') cursor[segment] = {};
        cursor = cursor[segment] as Record<string, unknown>;
      }
      cursor[segments[segments.length - 1] as string] = entry.value ?? '';
    }
    const comments = new Map<string, string>();
    for (const entry of decoded.entries) if (entry.comment) comments.set(entry.key, entry.comment);
    const body = serializeJsonish(tree, {
      indent: ctx.indent,
      quote: meta.quote ?? '"',
      comments: comments.size > 0 ? comments : undefined,
      eol: '\n',
    });
    return applyEol(`${prefix}${body}${suffix}`, ctx.eol);
  },
};

function collectNested(
  value: unknown,
  prefix: string,
  sink: CatalogEntry[],
  comments: Map<string, string>,
  ctx: FormatContext,
): void {
  const separator = ctx.keySeparator || '.';
  if (Array.isArray(value)) {
    value.forEach((item, index) => {
      const key = `${prefix}${separator}${index}`;
      if (item !== null && typeof item === 'object') collectNested(item, key, sink, comments, ctx);
      else sink.push({ key, value: item === null || item === undefined ? null : String(item), comment: comments.get(key) });
    });
    return;
  }
  for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
    const childKey = `${prefix}${separator}${key}`;
    if (item !== null && typeof item === 'object') collectNested(item, childKey, sink, comments, ctx);
    else sink.push({ key: childKey, value: item === null || item === undefined ? null : String(item), comment: comments.get(childKey) });
  }
}

function detectQuote(text: string, objectStart: number): '"' | "'" {
  const segment = text.slice(objectStart, objectStart + 2000);
  const double = (segment.match(/"/gu) ?? []).length;
  const single = (segment.match(/'/gu) ?? []).length;
  return single > double * 2 ? "'" : '"';
}

export function encodeObjectLiteral(value: unknown, options: { indent: number; quote?: '"' | "'" }): string {
  return serializeJsonish(value, { indent: options.indent, quote: options.quote ?? '"', eol: '\n' });
}

export type { JsonishNode };
