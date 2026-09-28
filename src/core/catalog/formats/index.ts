import type { FormatId } from '../../types';
import type { CatalogFormat } from './format';
import { jsonFormat } from './json';
import { yamlFormat } from './yaml';
import { propertiesFormat } from './properties';
import { poFormat } from './po';
import { arbFormat } from './arb';
import { stringsFormat } from './strings';
import { csvFormat } from './csv';
import { tsjsFormat } from './tsjs';

export * from './format';

export const FORMATS: Record<FormatId, CatalogFormat> = {
  json: jsonFormat,
  yaml: yamlFormat,
  properties: propertiesFormat,
  po: poFormat,
  arb: arbFormat,
  strings: stringsFormat,
  csv: csvFormat,
  tsjs: tsjsFormat,
};

export function getFormat(id: FormatId): CatalogFormat {
  const format = FORMATS[id];
  if (!format) throw new Error(`Unknown catalog format: ${id}`);
  return format;
}
