import type { CatalogEntry } from '../types';

export const CONTEXT_SEPARATOR = '\u0004';

export function displayKey(key: string): string {
  return key.split(CONTEXT_SEPARATOR).join(' | ');
}

export interface FlattenOptions {
  separator: string;
  nesting: boolean;
}

/**
 * Flattens a nested catalog object into dotted keys, keeping source order.
 * Arrays become numeric segments (`items.0.title`).
 */
export function flattenObject(
  value: unknown,
  options: FlattenOptions,
  prefix = '',
  sink: Array<{ key: string; value: unknown }> = [],
): Array<{ key: string; value: unknown }> {
  const separator = options.separator || '.';
  if (Array.isArray(value)) {
    value.forEach((item, index) => {
      const key = prefix ? `${prefix}${separator}${index}` : String(index);
      if (item !== null && typeof item === 'object') flattenObject(item, options, key, sink);
      else sink.push({ key, value: item });
    });
    return sink;
  }
  if (value !== null && typeof value === 'object') {
    for (const [childKey, child] of Object.entries(value as Record<string, unknown>)) {
      const key = prefix ? `${prefix}${separator}${childKey}` : childKey;
      if (child !== null && typeof child === 'object') flattenObject(child, options, key, sink);
      else sink.push({ key, value: child });
    }
    return sink;
  }
  if (prefix) sink.push({ key: prefix, value });
  return sink;
}

export function toCatalogEntries(
  value: unknown,
  options: FlattenOptions,
  comments?: Map<string, string>,
): CatalogEntry[] {
  const lookup = (key: string): string | undefined => {
    if (!comments || comments.size === 0) return undefined;
    // A comment above a parent object documents the whole subtree.
    let cursor = key;
    for (;;) {
      const found = comments.get(cursor);
      if (found) return found;
      const dot = cursor.lastIndexOf(options.separator || '.');
      if (dot <= 0) break;
      cursor = cursor.slice(0, dot);
    }
    return comments.get('');
  };
  return flattenObject(value, options).map(({ key, value: raw }) => ({
    key,
    value: raw === null || raw === undefined ? null : String(raw),
    comment: lookup(key),
  }));
}

export function entryValueAsString(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return null;
}

/** Rebuilds a nested object from flat entries, preserving entry order. */
export function unflattenEntries(entries: Array<{ key: string; value: unknown }>, options: FlattenOptions): Record<string, unknown> {
  const separator = options.separator || '.';
  if (!options.nesting) {
    return Object.fromEntries(entries.map((entry) => [entry.key, entry.value]));
  }
  const root: Record<string, unknown> = {};
  for (const entry of entries) {
    const segments = entry.key.split(separator);
    let cursor: Record<string, unknown> | unknown[] = root;
    for (let i = 0; i < segments.length; i += 1) {
      const segment = segments[i] as string;
      const isLast = i === segments.length - 1;
      const nextIsIndex = !isLast && /^\d+$/u.test(segments[i + 1] ?? '');
      if (isLast) {
        if (Array.isArray(cursor)) cursor[Number(segment)] = entry.value;
        else cursor[segment] = entry.value;
        break;
      }
      const existing = Array.isArray(cursor) ? cursor[Number(segment)] : cursor[segment];
      if (existing !== null && typeof existing === 'object') {
        cursor = existing as Record<string, unknown> | unknown[];
        continue;
      }
      const created: Record<string, unknown> | unknown[] = nextIsIndex ? [] : {};
      if (Array.isArray(cursor)) cursor[Number(segment)] = created;
      else cursor[segment] = created;
      cursor = created;
    }
  }
  return root;
}

/** Glob-ish matcher used by length rules and ignore lists (`cta.*`, `*.tooltip`, `*`). */
export function keyMatches(pattern: string, key: string): boolean {
  if (pattern === '*' || pattern === '**') return true;
  const escaped = pattern
    .replace(/[.+^${}()|[\]\\]/gu, '\\$&')
    .replace(/\*\*/gu, '\u0000')
    .replace(/\*/gu, '[^.]*')
    .replace(/\u0000/gu, '.*')
    .replace(/\?/gu, '.');
  return new RegExp(`^${escaped}$`, 'u').test(key);
}

export function sortEntries<T extends { key: string }>(entries: T[]): T[] {
  return [...entries].sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
}
