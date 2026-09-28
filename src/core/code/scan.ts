import type { HardcodedCandidate, KeyUsage } from '../types';
import { looksLikeUiCopy, slugify } from '../utils/text';

import { positionAt, tokenize, type Token } from './tokenizer';

export interface ScanOptions {
  calls: string[];
  components: string[];
  attributes: string[];
  minWords: number;
  /** Regex sources; matching literals are never reported. */
  ignorePatterns: string[];
  jsx: boolean;
  /** When false, hardcoded strings are not collected. */
  collectHardcoded: boolean;
}

export interface FileScanResult {
  usages: KeyUsage[];
  dynamicKeys: string[];
  hardcoded: HardcodedCandidate[];
}

const KEY_ATTRIBUTES = new Set(['i18nkey', 'id', 'defaultmessage', 'message', 'msgid']);
const IGNORED_CALLERS = new Set([
  'console',
  'error',
  'describe',
  'it',
  'test',
  'expect',
  'import',
  'require',
  'throw',
  'keyof',
  'typeof',
  'assert',
  'expectTypeOf',
]);

export function scanSource(source: string, filePath: string, options: ScanOptions): FileScanResult {
  const tokens = tokenize(source, { jsx: options.jsx }).filter((token) => token.type !== 'comment');
  const calls = new Set(options.calls);
  const components = new Set(options.components);
  const usages: KeyUsage[] = [];
  const dynamicKeys: string[] = [];
  const hardcoded: HardcodedCandidate[] = [];
  const consumed = new Set<number>();
  const ignoreRegexes = options.ignorePatterns.map((pattern) => safeRegex(pattern)).filter((regex): regex is RegExp => regex !== null);

  let componentDepth = -1;
  let activeComponent: string | null = null;

  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i] as Token;

    if (token.type === 'jsx-tag' && token.tagName) {
      activeComponent = components.has(token.tagName) ? token.tagName : null;
      componentDepth = i;
      continue;
    }
    if (activeComponent && i - componentDepth > 40) {
      activeComponent = null;
    }

    // Translation component props: <Trans i18nKey="key" />
    if (activeComponent && token.type === 'string' && token.attribute) {
      if (KEY_ATTRIBUTES.has(token.attribute.toLowerCase())) {
        const pos = positionAt(source, token.start);
        usages.push({ key: token.value, file: filePath, line: pos.line, column: pos.column });
        consumed.add(token.start);
        continue;
      }
    }

    // Translation calls: t('key'), i18n.t('key'), $t('key')
    if (token.type === 'identifier') {
      const call = matchCallChain(tokens, i, calls);
      if (call) {
        const next = nextSignificant(tokens, call.next + 1);
        if (next && next.token.type === 'punctuation' && next.token.value === '(') {
          const argument = nextSignificant(tokens, next.index + 1);
          if (argument && (argument.token.type === 'string' || argument.token.type === 'template')) {
            consumed.add(argument.token.start);
            if (argument.token.type === 'template' && argument.token.interpolated) {
              dynamicKeys.push(argument.token.raw);
            } else {
              const pos = positionAt(source, argument.token.start);
              usages.push({
                key: argument.token.value,
                file: filePath,
                line: pos.line,
                column: pos.column,
              });
            }
          } else if (argument && argument.token.type === 'punctuation' && argument.token.value === '{') {
            // react-intl style: formatMessage({ id: 'key' })
            const idKey = findObjectKey(tokens, argument.index, KEY_ATTRIBUTES);
            if (idKey) {
              consumed.add(idKey.token.start);
              const pos = positionAt(source, idKey.token.start);
              usages.push({ key: idKey.token.value, file: filePath, line: pos.line, column: pos.column });
            }
          } else if (argument && argument.token.type === 'identifier') {
            dynamicKeys.push(argument.token.value);
          }
          // The chain was fully consumed; do not match the trailing `t` again.
          if (call.next > i) i = call.next;
        }
      }
    }

    if (!options.collectHardcoded) continue;
    if (token.type === 'jsx-text') {
      if (consumed.has(token.start)) continue;
      if (!looksLikeUiCopy(token.value, { minWords: 1 })) continue;
      if (matchesAny(ignoreRegexes, token.value)) continue;
      const pos = positionAt(source, token.start);
      hardcoded.push({
        file: filePath,
        line: pos.line,
        column: pos.column,
        text: token.value,
        kind: 'jsx-text',
        start: token.start,
        end: token.end,
        confidence: 0.9,
        key: slugify(token.value),
      });
      continue;
    }
    if (token.type === 'string' && token.attribute && options.attributes.some((attr) => attr.toLowerCase() === token.attribute?.toLowerCase())) {
      if (consumed.has(token.start)) continue;
      if (!looksLikeUiCopy(token.value, { minWords: 1 })) continue;
      if (matchesAny(ignoreRegexes, token.value)) continue;
      const pos = positionAt(source, token.start);
      hardcoded.push({
        file: filePath,
        line: pos.line,
        column: pos.column,
        text: token.value,
        kind: 'attribute',
        attribute: token.attribute,
        start: token.start,
        end: token.end,
        confidence: 0.85,
        key: slugify(token.value),
      });
      continue;
    }
    if (token.type === 'string' && !token.attribute && !token.jsx) {
      if (consumed.has(token.start)) continue;
      if (!looksLikeUiCopy(token.value, { minWords: options.minWords })) continue;
      if (matchesAny(ignoreRegexes, token.value)) continue;
      if (isCallerNoise(tokens, i)) continue;
      const pos = positionAt(source, token.start);
      hardcoded.push({
        file: filePath,
        line: pos.line,
        column: pos.column,
        text: token.value,
        kind: 'string',
        start: token.start,
        end: token.end,
        confidence: options.minWords <= 1 ? 0.65 : 0.7,
        key: slugify(token.value),
      });
    }
  }

  return { usages, dynamicKeys, hardcoded };
}

interface CallMatch {
  /** Index of the last token of the call chain. */
  next: number;
}

function matchCallChain(tokens: Token[], index: number, calls: Set<string>): CallMatch | null {
  const first = tokens[index] as Token;
  if (first.value === 'this') return null;
  let chain = first.value;
  let cursor = index;
  // Support `i18n.t(...)` / `intl.formatMessage(...)` chains.
  for (;;) {
    const dot = nextSignificant(tokens, cursor + 1);
    if (!dot || dot.token.type !== 'punctuation' || dot.token.value !== '.') break;
    const property = nextSignificant(tokens, dot.index + 1);
    if (!property || property.token.type !== 'identifier') break;
    chain = `${chain}.${property.token.value}`;
    cursor = property.index;
    if (calls.has(chain)) return { next: cursor };
  }
  if (calls.has(chain)) return { next: cursor };
  // Also accept the final property alone (`obj.t('key')` matches `t`).
  if (chain.includes('.')) {
    const last = chain.split('.').pop() as string;
    if (calls.has(last)) return { next: cursor };
  }
  return null;
}

const OBJECT_KEY_NAMES = new Set(['id', 'key', 'defaultmessage', 'i18nkey', 'message', 'msgid']);

/** Finds the string value of the first known key inside an object literal. */
function findObjectKey(tokens: Token[], from: number, extra: Set<string>): { token: Token; index: number } | null {
  const names = new Set([...OBJECT_KEY_NAMES, ...[...extra].map((name) => name.toLowerCase())]);
  let depth = 0;
  for (let i = from; i < tokens.length; i += 1) {
    const token = tokens[i] as Token;
    if (token.type === 'punctuation') {
      if (token.value === '{') depth += 1;
      else if (token.value === '}') {
        depth -= 1;
        if (depth <= 0) return null;
      }
      continue;
    }
    if (token.type !== 'identifier' && token.type !== 'string') continue;
    const next = nextSignificant(tokens, i + 1);
    if (!next || next.token.type !== 'punctuation' || next.token.value !== ':') continue;
    if (!names.has(token.value.toLowerCase())) continue;
    const value = nextSignificant(tokens, next.index + 1);
    if (value && value.token.type === 'string') return value;
  }
  return null;
}

function nextSignificant(tokens: Token[], from: number): { token: Token; index: number } | null {
  for (let i = from; i < tokens.length; i += 1) {
    const token = tokens[i] as Token;
    if (token.type === 'comment') continue;
    return { token, index: i };
  }
  return null;
}

function isCallerNoise(tokens: Token[], index: number): boolean {
  for (let i = index - 1; i >= 0 && i >= index - 8; i -= 1) {
    const token = tokens[i] as Token;
    if (token.type === 'punctuation') {
      if (token.value === ')' || token.value === ']') continue;
      if (token.value === '(' || token.value === ',' || token.value === '.') {
        const callee = tokens[i - 1];
        if (callee?.type === 'identifier' && IGNORED_CALLERS.has(callee.value)) return true;
        // member call like console.log(...): look one more back
        if (token.value === '(') {
          const dot = tokens[i - 2];
          const owner = tokens[i - 3];
          if (dot?.value === '.' && owner?.type === 'identifier' && IGNORED_CALLERS.has(owner.value)) return true;
        }
      }
      if (token.value === '=' || token.value === ':' || token.value === '{' || token.value === ';') return false;
      continue;
    }
    if (token.type === 'identifier' && IGNORED_CALLERS.has(token.value)) return true;
  }
  return false;
}

function safeRegex(pattern: string): RegExp | null {
  try {
    return new RegExp(pattern, 'u');
  } catch {
    return null;
  }
}

function matchesAny(regexes: RegExp[], value: string): boolean {
  return regexes.some((regex) => regex.test(value));
}
