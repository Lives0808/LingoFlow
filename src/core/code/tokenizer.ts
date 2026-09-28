/**
 * Small, dependency-free JS/TS/Vue lexer.
 *
 * It intentionally does not build a full AST: LingoFlow needs string literal
 * positions (for extraction), translation call detection and JSX text nodes,
 * all without executing or type-checking user code.
 */

export type TokenType =
  | 'string'
  | 'template'
  | 'comment'
  | 'identifier'
  | 'punctuation'
  | 'number'
  | 'regex'
  | 'jsx-text'
  | 'jsx-tag';

export interface Token {
  type: TokenType;
  /** Raw source slice. */
  raw: string;
  /** Decoded value for strings/templates/jsx text. */
  value: string;
  start: number;
  end: number;
  quote?: string;
  /** JSX attribute name when the string is an attribute value. */
  attribute?: string;
  /** true when the string is part of a JSX expression. */
  jsx?: boolean;
  /** Templates containing `${}` are dynamic. */
  interpolated?: boolean;
  /** For jsx-tag tokens: the tag name. */
  tagName?: string;
}

const IDENT_START = /[A-Za-z_$]/u;
const IDENT_PART = /[\w$]/u;
const REGEX_ALLOWED_BEFORE = new Set([
  '(',
  ',',
  '=',
  ':',
  '[',
  '!',
  '&',
  '|',
  '?',
  '{',
  '}',
  ';',
  '+',
  '-',
  '*',
  '%',
  '<',
  '>',
  '~',
  '^',
  'return',
  'typeof',
  'case',
  'in',
  'of',
  'new',
  'delete',
  'void',
  'instanceof',
  'do',
  'else',
  'yield',
  'await',
]);

const REGEX_ALLOWED_PREV_TYPES = new Set(['character', 'literal', 'property']);

export interface TokenizeOptions {
  /** Enable JSX scanning (tsx/jsx/vue/svelte). */
  jsx?: boolean;
}

export function tokenize(source: string, options: TokenizeOptions = {}): Token[] {
  const lexer = new Lexer(source, options.jsx ?? false);
  return lexer.run();
}

class Lexer {
  private readonly source: string;
  private readonly jsx: boolean;
  private index = 0;
  private tokens: Token[] = [];

  constructor(source: string, jsx: boolean) {
    this.source = source;
    this.jsx = jsx;
  }

  run(): Token[] {
    while (this.index < this.source.length) {
      const char = this.source[this.index] as string;
      if (char === ' ' || char === '\t' || char === '\n' || char === '\r') {
        this.index += 1;
        continue;
      }
      if (char === '/' && this.source[this.index + 1] === '/') {
        this.readLineComment();
        continue;
      }
      if (char === '/' && this.source[this.index + 1] === '*') {
        this.readBlockComment();
        continue;
      }
      if (char === '"' || char === "'") {
        this.push(this.readString(char, this.index, {}));
        continue;
      }
      if (char === '`') {
        this.push(this.readTemplate(this.index));
        continue;
      }
      if (IDENT_START.test(char)) {
        this.push(this.readIdentifier(this.index));
        continue;
      }
      if (/[0-9]/u.test(char) || (char === '.' && /[0-9]/u.test(this.source[this.index + 1] ?? ''))) {
        this.push(this.readNumber(this.index));
        continue;
      }
      if (char === '<' && this.jsx && this.canStartJsx()) {
        this.readJsxElement();
        continue;
      }
      if (char === '/' && this.canStartRegex()) {
        const regex = this.readRegex(this.index);
        if (regex) {
          this.push(regex);
          continue;
        }
      }
      this.push({ type: 'punctuation', raw: char, value: char, start: this.index, end: this.index + 1 });
      this.index += 1;
    }
    return this.tokens;
  }

  private push(token: Token): void {
    this.tokens.push(token);
  }

  private lastSignificant(): Token | undefined {
    for (let i = this.tokens.length - 1; i >= 0; i -= 1) {
      const token = this.tokens[i] as Token;
      if (token.type !== 'comment') return token;
    }
    return undefined;
  }

  private readLineComment(): void {
    const start = this.index;
    const end = this.source.indexOf('\n', start);
    this.index = end === -1 ? this.source.length : end;
    this.push({ type: 'comment', raw: this.source.slice(start, this.index), value: '', start, end: this.index });
  }

  private readBlockComment(): void {
    const start = this.index;
    const end = this.source.indexOf('*/', start + 2);
    this.index = end === -1 ? this.source.length : end + 2;
    this.push({ type: 'comment', raw: this.source.slice(start, this.index), value: '', start, end: this.index });
  }

  private readIdentifier(start: number): Token {
    let index = start + 1;
    while (index < this.source.length && IDENT_PART.test(this.source[index] as string)) index += 1;
    const raw = this.source.slice(start, index);
    this.index = index;
    return { type: 'identifier', raw, value: raw, start, end: index };
  }

  private readNumber(start: number): Token {
    let index = start;
    while (index < this.source.length && /[\w.+-]/u.test(this.source[index] as string)) {
      const char = this.source[index] as string;
      // `1-2` or `1+2` are not numbers; only allow +- right after an exponent.
      if ((char === '+' || char === '-') && !/[eE]$/u.test(this.source.slice(start, index))) break;
      index += 1;
    }
    const raw = this.source.slice(start, index);
    this.index = index;
    return { type: 'number', raw, value: raw, start, end: index };
  }

  private readString(quote: string, start: number, extra: Partial<Token>): Token {
    let index = start + 1;
    let value = '';
    while (index < this.source.length) {
      const char = this.source[index] as string;
      if (char === '\\') {
        const escaped = this.source[index + 1] ?? '';
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
          case 'u': {
            const hex = this.source.slice(index, index + 4);
            value += String.fromCharCode(Number.parseInt(hex, 16) || 0);
            index += 4;
            break;
          }
          case 'x': {
            const hex = this.source.slice(index, index + 2);
            value += String.fromCharCode(Number.parseInt(hex, 16) || 0);
            index += 2;
            break;
          }
          default:
            value += escaped;
        }
        continue;
      }
      if (char === quote) {
        index += 1;
        break;
      }
      if (char === '\n') break;
      value += char;
      index += 1;
    }
    this.index = index;
    return { type: 'string', raw: this.source.slice(start, index), value, start, end: index, quote, ...extra };
  }

  private readTemplate(start: number): Token {
    let index = start + 1;
    let value = '';
    let interpolated = false;
    while (index < this.source.length) {
      const char = this.source[index] as string;
      if (char === '\\') {
        value += this.source[index + 1] ?? '';
        index += 2;
        continue;
      }
      if (char === '$' && this.source[index + 1] === '{') {
        interpolated = true;
        index = this.skipBalanced(index + 1);
        continue;
      }
      if (char === '`') {
        index += 1;
        break;
      }
      value += char;
      index += 1;
    }
    this.index = index;
    return { type: 'template', raw: this.source.slice(start, index), value, start, end: index, quote: '`', interpolated };
  }

  private readRegex(start: number): Token | null {
    let index = start + 1;
    let inClass = false;
    while (index < this.source.length) {
      const char = this.source[index] as string;
      if (char === '\\') {
        index += 2;
        continue;
      }
      if (char === '\n') return null;
      if (char === '[') inClass = true;
      else if (char === ']') inClass = false;
      else if (char === '/' && !inClass) {
        index += 1;
        while (index < this.source.length && /[a-z]/iu.test(this.source[index] as string)) index += 1;
        this.index = index;
        return { type: 'regex', raw: this.source.slice(start, index), value: this.source.slice(start, index), start, end: index };
      }
      index += 1;
    }
    return null;
  }

  private canStartRegex(): boolean {
    const previous = this.lastSignificant();
    if (!previous) return true;
    if (REGEX_ALLOWED_PREV_TYPES.has('character')) {
      /* unreachable, kept for clarity */
    }
    if (previous.type === 'punctuation') return REGEX_ALLOWED_BEFORE.has(previous.value);
    if (previous.type === 'identifier') return REGEX_ALLOWED_BEFORE.has(previous.value);
    return false;
  }

  private canStartJsx(): boolean {
    const next = this.source[this.index + 1] ?? '';
    const code = next.codePointAt(0) ?? 0;
    const isIdentifierStart =
      (code >= 65 && code <= 90) || (code >= 97 && code <= 122) || code === 95 || code === 36;
    if (!isIdentifierStart && next !== '>' && next !== '!') return false;
    const previous = this.lastSignificant();
    if (!previous) return true;
    if (previous.type === 'punctuation') {
      return ['(', ',', '=', ':', '?', '{', '}', '[', ';', '&&', '||', '=>', 'return', '>'].includes(previous.value);
    }
    if (previous.type === 'identifier') return ['return', 'default', 'yield', 'await'].includes(previous.value);
    return false;
  }

  /**
   * Skips a `{ ... }` region (index points at `{`) and returns the index after `}`.
   * Restores `this.index` so callers can keep tokenising around the region.
   */
  private skipBalanced(start: number): number {
    const saved = this.index;
    const end = this.skipBalancedInner(start);
    this.index = saved;
    return end;
  }

  private skipBalancedInner(start: number): number {
    let index = start;
    let depth = 0;
    while (index < this.source.length) {
      const char = this.source[index] as string;
      if (char === '"' || char === "'") {
        index = this.readString(char, index, {}).end;
        continue;
      }
      if (char === '`') {
        index = this.readTemplate(index).end;
        continue;
      }
      if (char === '/' && this.source[index + 1] === '/') {
        const end = this.source.indexOf('\n', index);
        index = end === -1 ? this.source.length : end;
        continue;
      }
      if (char === '/' && this.source[index + 1] === '*') {
        const end = this.source.indexOf('*/', index + 2);
        index = end === -1 ? this.source.length : end + 2;
        continue;
      }
      if (char === '{') depth += 1;
      else if (char === '}') {
        depth -= 1;
        if (depth <= 0) return index + 1;
      }
      index += 1;
    }
    return index;
  }

  // ---- JSX -----------------------------------------------------------------

  private readJsxElement(): void {
    const tagStart = this.index;
    this.index += 1;
    let name = '';
    if (this.source[this.index] === '>') {
      // Fragment <>
      this.index += 1;
    } else {
      const match = /^[A-Za-z_$][\w$.:-]*/u.exec(this.source.slice(this.index));
      if (!match) {
        this.push({ type: 'punctuation', raw: '<', value: '<', start: tagStart, end: tagStart + 1 });
        this.index = tagStart + 1;
        return;
      }
      name = match[0];
      this.index += match[0].length;
      this.push({ type: 'jsx-tag', raw: `<${name}`, value: name, start: tagStart, end: this.index, tagName: name });
      if (!this.readJsxAttributes(name)) return;
    }
    this.readJsxChildren();
  }

  /** Reads attributes; returns false when the element is self closing. */
  private readJsxAttributes(tagName: string): boolean {
    for (;;) {
      while (this.index < this.source.length && /\s/u.test(this.source[this.index] as string)) this.index += 1;
      const char = this.source[this.index];
      if (char === undefined) return false;
      if (char === '{') {
        this.index = this.skipBalanced(this.index);
        continue;
      }
      if (char === '/' && this.source[this.index + 1] === '>') {
        this.index += 2;
        return false;
      }
      if (char === '>') {
        this.index += 1;
        return true;
      }
      const attrMatch = /^[A-Za-z_$][\w$:-]*/u.exec(this.source.slice(this.index));
      if (!attrMatch) {
        this.index += 1;
        continue;
      }
      const attribute = attrMatch[0];
      this.index += attribute.length;
      while (this.index < this.source.length && /\s/u.test(this.source[this.index] as string)) this.index += 1;
      if (this.source[this.index] !== '=') {
        // Boolean attribute (e.g. `disabled`).
        continue;
      }
      this.index += 1;
      while (this.index < this.source.length && /\s/u.test(this.source[this.index] as string)) this.index += 1;
      const valueChar = this.source[this.index] as string;
      if (valueChar === '"' || valueChar === "'") {
        const token = this.readString(valueChar, this.index, { attribute, jsx: true });
        this.push(token);
        continue;
      }
      if (valueChar === '{') {
        const end = this.skipBalanced(this.index);
        // Expression container: capture string literals inside so
        // placeholder={t('key')} / label={'Text'} stay visible to the scanner.
        this.pushNested(this.index + 1, end - 1);
        this.index = end;
        continue;
      }
      if (valueChar === '`') {
        this.push(this.readTemplate(this.index));
        continue;
      }
      // Unquoted value, skip to whitespace.
      const start = this.index;
      while (this.index < this.source.length && !/[\s>]/u.test(this.source[this.index] as string)) this.index += 1;
      if (this.index > start) {
        this.push({
          type: 'identifier',
          raw: this.source.slice(start, this.index),
          value: this.source.slice(start, this.index),
          start,
          end: this.index,
          attribute,
          jsx: true,
        });
      }
    }
  }

  /** Tokenises an inner region (e.g. a JSX expression container) with correct offsets. */
  private pushNested(from: number, to: number): void {
    if (to <= from) return;
    const inner = this.source.slice(from, to);
    const nested = new Lexer(inner, this.jsx).run();
    for (const token of nested) {
      this.push({ ...token, start: token.start + from, end: token.end + from });
    }
  }

  private readJsxChildren(): void {
    let depth = 1;
    while (this.index < this.source.length && depth > 0) {
      const char = this.source[this.index] as string;
      if (char === '<') {
        if (this.source[this.index + 1] === '/') {
          const end = this.source.indexOf('>', this.index);
          this.index = end === -1 ? this.source.length : end + 1;
          depth -= 1;
          continue;
        }
        const savedTokens = this.tokens;
        this.tokens = [];
        this.readJsxElement();
        const inner = this.tokens;
        this.tokens = savedTokens;
        for (const token of inner) this.push(token);
        continue;
      }
      if (char === '{') {
        const end = this.skipBalanced(this.index);
        this.pushNested(this.index + 1, end - 1);
        this.index = end;
        continue;
      }
      if (char === '&') {
        const entity = /^&(?:#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);/u.exec(this.source.slice(this.index));
        if (entity) this.index += entity[0].length;
        else this.index += 1;
        continue;
      }
      const start = this.index;
      while (
        this.index < this.source.length &&
        this.source[this.index] !== '<' &&
        this.source[this.index] !== '{' &&
        this.source[this.index] !== '&'
      ) {
        this.index += 1;
      }
      const raw = this.source.slice(start, this.index);
      if (raw.trim().length > 0) {
        const leading = raw.length - raw.trimStart().length;
        const trailing = raw.length - raw.trimEnd().length;
        this.push({
          type: 'jsx-text',
          raw,
          value: raw.replace(/\s+/gu, ' ').trim(),
          start: start + leading,
          end: this.index - trailing,
          jsx: true,
        });
      }
    }
  }
}

/** 1-based line/column from a character offset. */
export function positionAt(source: string, offset: number): { line: number; column: number } {
  let line = 1;
  let lineStart = 0;
  for (let i = 0; i < offset && i < source.length; i += 1) {
    if (source[i] === '\n') {
      line += 1;
      lineStart = i + 1;
    }
  }
  return { line, column: offset - lineStart + 1 };
}
