import type { CatalogEntry, DecodedCatalog } from '../../types';
import { applyEol, type CatalogFormat, type FormatContext } from './format';

interface CsvMeta {
  [key: string]: unknown;
  header: string[];
  rows: string[][];
  keyColumn: number;
  valueColumn: number;
  delimiter: string;
}

/**
 * CSV catalogs. Works with per-locale files (`key,value`) and with a single
 * spreadsheet holding every locale (`key,en,zh-CN,ja`): LingoFlow updates only
 * the column that belongs to the locale being written.
 */
export const csvFormat: CatalogFormat = {
  id: 'csv',
  name: 'CSV',
  extensions: ['.csv'],

  decode(text: string, ctx: FormatContext): DecodedCatalog {
    const normalized = text.replace(/\r\n?/gu, '\n').replace(/\n+$/u, '');
    const delimiter = detectDelimiter(normalized);
    const rows = parseCsv(normalized, delimiter);
    const header = rows[0] ?? [];
    const keyColumn = findKeyColumn(header);
    const valueColumn = findLocaleColumn(header, ctx.locale, keyColumn);
    const entries: CatalogEntry[] = [];
    for (let i = 1; i < rows.length; i += 1) {
      const row = rows[i] as string[];
      const key = row[keyColumn];
      if (!key) continue;
      entries.push({ key, value: row[valueColumn] ?? null });
    }
    const meta: CsvMeta = { header, rows, keyColumn, valueColumn, delimiter };
    return { entries, meta };
  },

  encode(decoded: DecodedCatalog, ctx: FormatContext): string {
    const meta = (decoded.meta ?? {}) as Partial<CsvMeta>;
    const delimiter = meta.delimiter ?? ',';
    const header = meta.header ?? ['key', ctx.locale];
    const keyColumn = meta.keyColumn ?? 0;
    const valueColumn = meta.valueColumn ?? 1;
    const rows: string[][] = [header];
    const used = new Set<string>();
    for (const entry of decoded.entries) {
      const template = (meta.rows ?? []).find((row) => row[keyColumn] === entry.key);
      const row = template ? [...template] : new Array<string>(header.length).fill('');
      row[keyColumn] = entry.key;
      row[valueColumn] = entry.value ?? '';
      rows.push(row);
      used.add(entry.key);
    }
    for (const template of meta.rows ?? []) {
      const key = template[keyColumn];
      if (key && !used.has(key)) rows.push([...template]);
    }
    const body = rows.map((row) => row.map((cell) => quoteCell(cell ?? '', delimiter)).join(delimiter)).join('\n');
    return applyEol(`${body}\n`, ctx.eol);
  },
};

function detectDelimiter(text: string): string {
  const firstLine = text.split('\n')[0] ?? '';
  const counts: Array<[string, number]> = [
    [',', (firstLine.match(/,/gu) ?? []).length],
    ['\t', (firstLine.match(/\t/gu) ?? []).length],
    [';', (firstLine.match(/;/gu) ?? []).length],
    ['|', (firstLine.match(/\|/gu) ?? []).length],
  ];
  counts.sort((a, b) => b[1] - a[1]);
  return counts[0] && counts[0][1] > 0 ? counts[0][0] : ',';
}

function parseCsv(text: string, delimiter: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let inQuotes = false;
  for (let i = 0; i < text.length; i += 1) {
    const char = text[i] as string;
    if (inQuotes) {
      if (char === '"') {
        if (text[i + 1] === '"') {
          cell += '"';
          i += 1;
        } else {
          inQuotes = false;
        }
      } else {
        cell += char;
      }
      continue;
    }
    if (char === '"') {
      inQuotes = true;
      continue;
    }
    if (char === delimiter) {
      row.push(cell);
      cell = '';
      continue;
    }
    if (char === '\n') {
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
      continue;
    }
    cell += char;
  }
  if (cell.length > 0 || row.length > 0) {
    row.push(cell);
    rows.push(row);
  }
  return rows;
}

function findKeyColumn(header: string[]): number {
  const index = header.findIndex((cell) => /^(key|id|msgid|name)$/iu.test(cell.trim()));
  return index >= 0 ? index : 0;
}

function findLocaleColumn(header: string[], locale: string, keyColumn: number): number {
  const wanted = normalizeLocale(locale);
  const index = header.findIndex((cell) => normalizeLocale(cell) === wanted);
  if (index >= 0) return index;
  const fallback = header.findIndex((_cell, i) => i !== keyColumn);
  return fallback >= 0 ? fallback : 1;
}

function normalizeLocale(value: string): string {
  return value.trim().toLowerCase().replace(/_/gu, '-');
}

function quoteCell(value: string, delimiter: string): string {
  if (value.includes('"') || value.includes('\n') || value.includes(delimiter)) {
    return `"${value.replace(/"/gu, '""')}"`;
  }
  return value;
}
