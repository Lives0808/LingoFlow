import type { CatalogEntry, DecodedCatalog } from '../../types';
import { nodeToValue, parseJsonish, serializeJsonish } from './jsonish';
import { toCatalogEntries, unflattenEntries } from '../keys';
import { applyEol, type CatalogFormat, type FormatContext } from './format';

interface JsonMeta {
  [key: string]: unknown;
  tolerant?: boolean;
  comments?: Record<string, string>;
  flat?: boolean;
}

/**
 * JSON / JSON5 / JSONC catalogs.
 * Reads tolerantly (comments, trailing commas, single quotes) and writes strict
 * JSON unless the original file already contained comments.
 */
export const jsonFormat: CatalogFormat = {
  id: 'json',
  name: 'JSON',
  extensions: ['.json', '.json5', '.jsonc'],

  decode(text: string, ctx: FormatContext): DecodedCatalog {
    const flat = !ctx.nesting;
    let raw: unknown;
    let tolerant = false;
    const comments = new Map<string, string>();
    try {
      raw = JSON.parse(text) as unknown;
    } catch {
      const parsed = parseJsonish(text);
      raw = nodeToValue(parsed.node);
      tolerant = parsed.tolerant;
      for (const [key, value] of parsed.comments) comments.set(key, value);
    }
    const entries = toCatalogEntries(raw, { separator: ctx.keySeparator, nesting: ctx.nesting }, comments);
    const meta: JsonMeta = { tolerant, flat };
    if (comments.size > 0) meta.comments = Object.fromEntries(comments);
    return { entries, meta };
  },

  encode(decoded: DecodedCatalog, ctx: FormatContext): string {
    const meta = (decoded.meta ?? {}) as JsonMeta;
    const value = unflattenEntries(
      decoded.entries.map((entry) => ({ key: entry.key, value: entry.value ?? '' })),
      { separator: ctx.keySeparator, nesting: ctx.nesting },
    );
    const commentMap = new Map<string, string>();
    if (meta.tolerant) {
      for (const entry of decoded.entries) {
        if (entry.comment) commentMap.set(entry.key, entry.comment);
      }
    }
    const body = serializeJsonish(value, {
      indent: ctx.indent,
      comments: commentMap.size > 0 ? commentMap : undefined,
      eol: '\n',
    });
    return applyEol(`${body}\n`, ctx.eol);
  },
};

/** Helper used by ARB/TS writers that need to merge into an existing tree. */
export function mergeEntriesIntoTree(
  tree: Record<string, unknown>,
  entries: CatalogEntry[],
  ctx: Pick<FormatContext, 'keySeparator' | 'nesting'>,
): Record<string, unknown> {
  if (!ctx.nesting) {
    for (const entry of entries) tree[entry.key] = entry.value ?? '';
    return tree;
  }
  const separator = ctx.keySeparator || '.';
  for (const entry of entries) {
    const segments = entry.key.split(separator);
    let cursor: Record<string, unknown> = tree;
    for (let i = 0; i < segments.length - 1; i += 1) {
      const segment = segments[i] as string;
      const existing = cursor[segment];
      if (existing === null || typeof existing !== 'object' || Array.isArray(existing)) {
        cursor[segment] = {};
      }
      cursor = cursor[segment] as Record<string, unknown>;
    }
    const last = segments[segments.length - 1] as string;
    cursor[last] = entry.value ?? '';
  }
  return tree;
}
