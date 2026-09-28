import path from 'node:path';
import type { DecodedCatalog, FormatId } from '../../types';

export interface FormatContext {
  locale: string;
  path: string;
  keySeparator: string;
  nesting: boolean;
  indent: number;
  eol: 'lf' | 'crlf';
  /** Decoded previous content, used for comment/order preservation. */
  previous?: DecodedCatalog | null;
}

export interface CatalogFormat {
  id: FormatId;
  name: string;
  extensions: string[];
  decode(text: string, ctx: FormatContext): DecodedCatalog;
  encode(decoded: DecodedCatalog, ctx: FormatContext): string;
}

const EXTENSION_MAP: Record<string, FormatId> = {
  '.json': 'json',
  '.json5': 'json',
  '.jsonc': 'json',
  '.yaml': 'yaml',
  '.yml': 'yaml',
  '.properties': 'properties',
  '.po': 'po',
  '.pot': 'po',
  '.arb': 'arb',
  '.strings': 'strings',
  '.stringsdict': 'strings',
  '.csv': 'csv',
  '.ts': 'tsjs',
  '.js': 'tsjs',
  '.mjs': 'tsjs',
  '.cjs': 'tsjs',
};

export function detectFormat(filePath: string): FormatId {
  const ext = path.extname(filePath).toLowerCase();
  const detected = EXTENSION_MAP[ext];
  if (!detected) {
    throw new Error(
      `Cannot detect catalog format for "${filePath}". Supported extensions: ${Object.keys(EXTENSION_MAP).join(', ')}`,
    );
  }
  return detected;
}

export function applyEol(text: string, eol: 'lf' | 'crlf'): string {
  const normalized = text.replace(/\r\n?/gu, '\n');
  return eol === 'crlf' ? normalized.replace(/\n/gu, '\r\n') : normalized;
}

export function detectEol(text: string, configured: 'lf' | 'crlf' | 'auto'): 'lf' | 'crlf' {
  if (configured !== 'auto') return configured;
  return /\r\n/u.test(text) ? 'crlf' : 'lf';
}
