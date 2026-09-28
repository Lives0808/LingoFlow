/**
 * Tolerant JSON / JSON5 style parser used by the JSON, ARB and TS-object formats.
 *
 * Why not JSON.parse? Real locale files contain comments, trailing commas and
 * unquoted keys. We need to read them without losing translator comments, and
 * we must never evaluate code.
 */

export interface JsonishNode {
  type: 'object' | 'array' | 'string' | 'number' | 'boolean' | 'null';
  /** For objects: ordered entries. */
  entries?: Array<{ key: string; keyPos: number; value: JsonishNode; comment?: string }>;
  items?: JsonishNode[];
  value?: string | number | boolean | null;
  start: number;
  end: number;
}

export interface JsonishResult {
  node: JsonishNode;
  /** True when the text needed tolerant parsing (comments, trailing commas, unquoted keys, single quotes). */
  tolerant: boolean;
  /** Top level path -> comment text. */
  comments: Map<string, string>;
  /** Text before/after the parsed value, kept for lossless re-serialisation. */
  prefix: string;
  suffix: string;
}

export class JsonishError extends Error {}

interface Cursor {
  text: string;
  index: number;
  tolerant: boolean;
}

export function parseJsonish(text: string, options: { start?: number } = {}): JsonishResult {
  const cursor: Cursor = { text, index: options.start ?? 0, tolerant: false };
  skipWhitespaceAndComments(cursor);
  const comments = new Map<string, string>();
  const prefix = text.slice(0, cursor.index);
  const node = parseValue(cursor, comments, '');
  skipWhitespaceAndComments(cursor);
  const suffix = text.slice(cursor.index);
  return { node, tolerant: cursor.tolerant, comments, prefix, suffix };
}

/** Finds the first `{` that starts the object literal, skipping strings/comments. */
export function findObjectStart(text: string, searchFrom = 0): number {
  let index = searchFrom;
  while (index < text.length) {
    const char = text[index];
    if (char === '"' || char === "'" || char === '`') {
      index = skipString(text, index);
      continue;
    }
    if (char === '/' && text[index + 1] === '/') {
      const next = text.indexOf('\n', index);
      index = next === -1 ? text.length : next + 1;
      continue;
    }
    if (char === '/' && text[index + 1] === '*') {
      const next = text.indexOf('*/', index);
      index = next === -1 ? text.length : next + 2;
      continue;
    }
    if (char === '{') return index;
    index += 1;
  }
  return -1;
}

function skipString(text: string, start: number): number {
  const quote = text[start] as string;
  let index = start + 1;
  while (index < text.length) {
    const char = text[index];
    if (char === '\\') {
      index += 2;
      continue;
    }
    if (char === quote) return index + 1;
    if (char === '\n' && quote !== '`') return index;
    index += 1;
  }
  return text.length;
}

function skipWhitespaceOnly(cursor: Cursor): void {
  for (;;) {
    const char = cursor.text[cursor.index];
    if (char === ' ' || char === '\t' || char === '\n' || char === '\r') {
      cursor.index += 1;
      continue;
    }
    return;
  }
}

function skipWhitespaceAndComments(cursor: Cursor): void {
  for (;;) {
    const char = cursor.text[cursor.index];
    if (char === undefined) return;
    if (char === ' ' || char === '\t' || char === '\n' || char === '\r') {
      cursor.index += 1;
      continue;
    }
    if (char === '/' && cursor.text[cursor.index + 1] === '/') {
      cursor.tolerant = true;
      const next = cursor.text.indexOf('\n', cursor.index);
      cursor.index = next === -1 ? cursor.text.length : next + 1;
      continue;
    }
    if (char === '/' && cursor.text[cursor.index + 1] === '*') {
      cursor.tolerant = true;
      const next = cursor.text.indexOf('*/', cursor.index);
      cursor.index = next === -1 ? cursor.text.length : next + 2;
      continue;
    }
    return;
  }
}

/** Reads a `//` or `/* *\/` comment block; returns the cleaned comment text. */
function readComment(cursor: Cursor): string | null {
  const text = cursor.text;
  if (text[cursor.index] !== '/') return null;
  const next = text[cursor.index + 1];
  if (next === '/') {
    const end = text.indexOf('\n', cursor.index);
    const body = text.slice(cursor.index + 2, end === -1 ? text.length : end).trim();
    cursor.index = end === -1 ? text.length : end;
    cursor.tolerant = true;
    return body.length > 0 ? body : null;
  }
  if (next === '*') {
    const end = text.indexOf('*/', cursor.index + 2);
    const body = text.slice(cursor.index + 2, end === -1 ? text.length : end).trim();
    cursor.index = end === -1 ? text.length : end + 2;
    cursor.tolerant = true;
    const cleaned = body.replace(/\s*\*\s*/gu, ' ').trim();
    return cleaned.length > 0 ? cleaned : null;
  }
  return null;
}

function parseValue(cursor: Cursor, comments: Map<string, string>, path: string): JsonishNode {
  skipWhitespaceAndComments(cursor);
  const start = cursor.index;
  const char = cursor.text[cursor.index];
  if (char === '{') return parseObject(cursor, comments, path);
  if (char === '[') return parseArray(cursor, comments, path);
  if (char === '"' || char === "'") {
    const { value, end } = parseStringLiteral(cursor);
    cursor.index = end;
    return { type: 'string', value, start, end };
  }
  if (char !== undefined && /[-0-9]/u.test(char)) {
    const match = /^-?\d+(\.\d+)?([eE][+-]?\d+)?/u.exec(cursor.text.slice(cursor.index));
    if (!match) throw new JsonishError(`Invalid number at offset ${cursor.index}`);
    cursor.index += match[0].length;
    return { type: 'number', value: Number(match[0]), start, end: cursor.index };
  }
  const keyword = /^(true|false|null)/u.exec(cursor.text.slice(cursor.index));
  if (keyword) {
    cursor.index += keyword[0].length;
    const value = keyword[0] === 'null' ? null : keyword[0] === 'true';
    return { type: keyword[0] === 'null' ? 'null' : 'boolean', value, start, end: cursor.index };
  }
  throw new JsonishError(`Unexpected token at offset ${cursor.index}`);
}

function parseStringLiteral(cursor: Cursor): { value: string; end: number } {
  const text = cursor.text;
  const quote = text[cursor.index] as string;
  if (quote !== '"') cursor.tolerant = true;
  let index = cursor.index + 1;
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
        case 'b':
          value += '\b';
          break;
        case 'f':
          value += '\f';
          break;
        case 'u': {
          const hex = text.slice(index, index + 4);
          value += String.fromCharCode(Number.parseInt(hex, 16) || 0);
          index += 4;
          break;
        }
        case '\n':
          break;
        default:
          value += escaped ?? '';
      }
      continue;
    }
    if (char === quote) return { value, end: index + 1 };
    value += char;
    index += 1;
  }
  throw new JsonishError('Unterminated string literal');
}

function parseObject(cursor: Cursor, comments: Map<string, string>, path: string): JsonishNode {
  const start = cursor.index;
  cursor.index += 1;
  const entries: NonNullable<JsonishNode['entries']> = [];
  for (;;) {
    // Comments directly above a key belong to that key.
    const pending: string[] = [];
    for (;;) {
      skipWhitespaceOnly(cursor);
      const comment = readComment(cursor);
      if (comment === null) break;
      pending.push(comment);
    }
    skipWhitespaceOnly(cursor);
    const char = cursor.text[cursor.index];
    if (char === '}') {
      cursor.index += 1;
      break;
    }
    if (char === undefined) throw new JsonishError('Unterminated object literal');
    const keyPos = cursor.index;
    let key: string;
    if (char === '"' || char === "'") {
      const parsed = parseStringLiteral(cursor);
      key = parsed.value;
      cursor.index = parsed.end;
    } else {
      const match = /^[A-Za-z_$@][\w$@.-]*/u.exec(cursor.text.slice(cursor.index));
      if (!match) throw new JsonishError(`Invalid object key at offset ${cursor.index}`);
      key = match[0];
      cursor.index += match[0].length;
      cursor.tolerant = true;
    }
    skipWhitespaceAndComments(cursor);
    if (cursor.text[cursor.index] !== ':') throw new JsonishError(`Expected ":" after key "${key}"`);
    cursor.index += 1;
    const childPath = path ? `${path}.${key}` : key;
    if (pending.length > 0) comments.set(childPath, pending.join(' '));
    const value = parseValue(cursor, comments, childPath);
    entries.push({ key, keyPos, value, comment: comments.get(childPath) });
    skipWhitespaceAndComments(cursor);
    const nextChar = cursor.text[cursor.index];
    if (nextChar === ',') {
      cursor.index += 1;
      continue;
    }
    if (nextChar === '}') {
      cursor.index += 1;
      break;
    }
    if (nextChar === undefined) throw new JsonishError('Unterminated object literal');
    throw new JsonishError(`Expected "," or "}" at offset ${cursor.index}`);
  }
  return { type: 'object', entries, start, end: cursor.index };
}

function parseArray(cursor: Cursor, comments: Map<string, string>, path: string): JsonishNode {
  const start = cursor.index;
  cursor.index += 1;
  const items: JsonishNode[] = [];
  for (;;) {
    skipWhitespaceAndComments(cursor);
    const char = cursor.text[cursor.index];
    if (char === ']') {
      cursor.index += 1;
      break;
    }
    if (char === undefined) throw new JsonishError('Unterminated array literal');
    items.push(parseValue(cursor, comments, `${path}.${items.length}`));
    skipWhitespaceAndComments(cursor);
    const nextChar = cursor.text[cursor.index];
    if (nextChar === ',') {
      cursor.index += 1;
      continue;
    }
    if (nextChar === ']') {
      cursor.index += 1;
      break;
    }
    if (nextChar === undefined) throw new JsonishError('Unterminated array literal');
    throw new JsonishError(`Expected "," or "]" at offset ${cursor.index}`);
  }
  return { type: 'array', items, start, end: cursor.index };
}

/** Converts a JsonishNode into a plain JS value (no code execution). */
export function nodeToValue(node: JsonishNode): unknown {
  switch (node.type) {
    case 'object': {
      const result: Record<string, unknown> = {};
      for (const entry of node.entries ?? []) result[entry.key] = nodeToValue(entry.value);
      return result;
    }
    case 'array':
      return (node.items ?? []).map((item) => nodeToValue(item));
    case 'null':
      return null;
    default:
      return node.value;
  }
}

export interface JsonishSerializeOptions {
  indent: number;
  /** Quote style for strings. */
  quote?: '"' | "'";
  /** Emit keys without quotes (JSON5 style). */
  unquoted?: boolean;
  /** Comments keyed by dotted path. */
  comments?: Map<string, string> | undefined;
  eol?: string;
}

export function serializeJsonish(value: unknown, options: JsonishSerializeOptions): string {
  const indent = options.indent;
  const eol = options.eol ?? '\n';
  const quote = options.quote ?? '"';
  const pad = (depth: number): string => ' '.repeat(indent * depth);
  const quoteString = (input: string): string => {
    const escaped = input
      .replace(/\\/gu, '\\\\')
      .replace(/\n/gu, '\\n')
      .replace(/\r/gu, '\\r')
      .replace(/\t/gu, '\\t')
      .replace(/\u2028/gu, '\\u2028')
      .replace(/\u2029/gu, '\\u2029')
      .replace(/"/gu, '\\"');
    if (quote === '"') return `"${escaped}"`;
    return `'${escaped.replace(/'/gu, "\\'")}'`;
  };
  const keyString = (key: string): string => {
    if (options.unquoted && /^[A-Za-z_$][\w$]*$/u.test(key)) return key;
    return quoteString(key);
  };
  const walk = (node: unknown, depth: number, path: string): string => {
    if (node === null) return 'null';
    if (typeof node === 'string') return quoteString(node);
    if (typeof node === 'number' || typeof node === 'boolean') return JSON.stringify(node);
    if (Array.isArray(node)) {
      if (node.length === 0) return '[]';
      const inner = node.map((item, index) => `${pad(depth + 1)}${walk(item, depth + 1, `${path}.${index}`)}`);
      return `[${eol}${inner.join(`,${eol}`)}${eol}${pad(depth)}]`;
    }
    if (typeof node === 'object') {
      const entries = Object.entries(node as Record<string, unknown>);
      if (entries.length === 0) return '{}';
      const inner = entries.map(([key, item]) => {
        const childPath = path ? `${path}.${key}` : key;
        const comment = options.comments?.get(childPath);
        const prefix = comment ? `${pad(depth + 1)}// ${comment}${eol}` : '';
        return `${prefix}${pad(depth + 1)}${keyString(key)}: ${walk(item, depth + 1, childPath)}`;
      });
      return `{${eol}${inner.join(`,${eol}`)}${eol}${pad(depth)}}`;
    }
    return 'null';
  };
  return walk(value, 0, '');
}
