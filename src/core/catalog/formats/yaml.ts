import YAML from 'yaml';
import type { DecodedCatalog } from '../../types';
import { toCatalogEntries, unflattenEntries } from '../keys';
import { applyEol, type CatalogFormat, type FormatContext } from './format';

/**
 * YAML catalogs. The original YAML AST is kept in `meta.document`, so comments,
 * anchors and key order survive a round-trip.
 */
export const yamlFormat: CatalogFormat = {
  id: 'yaml',
  name: 'YAML',
  extensions: ['.yaml', '.yml'],

  decode(text: string, ctx: FormatContext): DecodedCatalog {
    const doc = YAML.parseDocument(text, { keepSourceTokens: true });
    const raw = (doc.toJS({ maxAliasCount: 1000 }) ?? {}) as unknown;
    const entries = toCatalogEntries(raw, { separator: ctx.keySeparator, nesting: ctx.nesting });
    return { entries, meta: { document: doc } };
  },

  encode(decoded: DecodedCatalog, ctx: FormatContext): string {
    const previous = (ctx.previous?.meta?.document ?? decoded.meta?.document) as YAML.Document | undefined;
    const options = { indent: Math.max(1, ctx.indent), lineWidth: 0 } as const;
    if (previous && typeof (previous as YAML.Document).setIn === 'function') {
      const doc = previous;
      const separator = ctx.keySeparator || '.';
      const seen = new Set<string>();
      for (const entry of decoded.entries) {
        const path = ctx.nesting ? entry.key.split(separator) : [entry.key];
        seen.add(entry.key);
        const current = doc.getIn(path, true);
        const nextValue = entry.value ?? '';
        if (current === undefined) {
          doc.setIn(path, nextValue);
        } else if (ctx.nesting ? YAML.isScalar(current) : true) {
          if (ctx.nesting && YAML.isScalar(current)) {
            current.value = nextValue;
          } else {
            doc.setIn(path, nextValue);
          }
        } else {
          doc.setIn(path, nextValue);
        }
      }
      return applyEol(String(doc.toString(options)), ctx.eol);
    }
    const value = unflattenEntries(
      decoded.entries.map((entry) => ({ key: entry.key, value: entry.value ?? '' })),
      { separator: ctx.keySeparator, nesting: ctx.nesting },
    );
    const doc = new YAML.Document(value);
    return applyEol(String(doc.toString(options)), ctx.eol);
  },
};
