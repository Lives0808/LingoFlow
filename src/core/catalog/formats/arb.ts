import type { CatalogEntry, DecodedCatalog } from '../../types';
import { applyEol, type CatalogFormat, type FormatContext } from './format';

interface ArbMeta {
  [key: string]: unknown;
  /** Original parsed ARB document, kept so @@metadata and ordering survive writes. */
  template?: Record<string, unknown>;
  locale?: string;
}

/**
 * Flutter ARB catalogs.
 * `@@locale` / `@@last_modified` and `@key` placeholder metadata are preserved;
 * translation comments are written back as `@key.description`.
 */
export const arbFormat: CatalogFormat = {
  id: 'arb',
  name: 'Flutter ARB',
  extensions: ['.arb'],

  decode(text: string, ctx: FormatContext): DecodedCatalog {
    const parsed = JSON.parse(text) as Record<string, unknown>;
    const entries: CatalogEntry[] = [];
    for (const [key, value] of Object.entries(parsed)) {
      if (key.startsWith('@@')) continue;
      if (key.startsWith('@')) continue;
      if (value === null || typeof value === 'object') continue;
      if (typeof value !== 'string') continue;
      const meta = parsed[`@${key}`] as Record<string, unknown> | undefined;
      const comment = typeof meta?.description === 'string' ? meta.description : undefined;
      entries.push({ key, value, comment, meta: meta ? { arb: meta } : undefined });
    }
    const meta: ArbMeta = { template: parsed, locale: typeof parsed['@@locale'] === 'string' ? (parsed['@@locale'] as string) : ctx.locale };
    return { entries, meta };
  },

  encode(decoded: DecodedCatalog, ctx: FormatContext): string {
    const meta = (decoded.meta ?? {}) as ArbMeta;
    const template: Record<string, unknown> = meta.template ? { ...meta.template } : {};
    if (meta.locale) template['@@locale'] = meta.locale;

    const seen = new Set<string>();
    for (const entry of decoded.entries) {
      seen.add(entry.key);
      template[entry.key] = entry.value ?? '';
      const existingMeta = template[`@${entry.key}`];
      if (entry.comment && (!existingMeta || typeof existingMeta !== 'object')) {
        template[`@${entry.key}`] = { description: entry.comment };
      } else if (entry.comment && typeof existingMeta === 'object') {
        const record = existingMeta as Record<string, unknown>;
        if (typeof record.description !== 'string') record.description = entry.comment;
      }
    }
    // Keep the historical order (metadata follows its key), then append new keys.
    const ordered: Record<string, unknown> = {};
    const push = (key: string): void => {
      if (key in ordered) return;
      ordered[key] = template[key];
      const metaKey = `@${key}`;
      if (key in template && metaKey in template && !key.startsWith('@@')) ordered[metaKey] = template[metaKey];
    };
    for (const key of Object.keys(template)) {
      if (key.startsWith('@@') || key.startsWith('@')) continue;
      push(key);
    }
    for (const entry of decoded.entries) push(entry.key);
    for (const key of Object.keys(template)) {
      if (key.startsWith('@@') && !(key in ordered)) ordered[key] = template[key];
    }

    const body = JSON.stringify(ordered, null, ctx.indent);
    return applyEol(`${body}\n`, ctx.eol);
  },
};
