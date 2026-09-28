import { pluralCategoriesFor } from './plural';

export interface IcuIssue {
  message: string;
  detail?: string;
  argument?: string;
}

export interface IcuArgument {
  name: string;
  type: 'argument' | 'plural' | 'select' | 'selectordinal' | 'number' | 'date' | 'time';
  categories?: string[];
}

export interface IcuParseResult {
  ok: boolean;
  issues: IcuIssue[];
  arguments: IcuArgument[];
}

const CLDR_TYPES = new Set(['plural', 'select', 'selectordinal', 'number', 'date', 'time']);
const CATEGORIES = new Set(['zero', 'one', 'two', 'few', 'many', 'other']);

/**
 * Small recursive ICU MessageFormat parser.
 * Validates structure, argument names and CLDR plural categories without the
 * heavyweight ICU runtime.
 */
export function parseIcu(message: string, locale: string): IcuParseResult {
  const issues: IcuIssue[] = [];
  const args = new Map<string, IcuArgument>();
  let index = 0;

  const skipQuoted = (): void => {
    if (message[index + 1] === "'") {
      index += 2;
      return;
    }
    const close = message.indexOf("'", index + 1);
    if (close === -1) {
      issues.push({ message: 'Unterminated quoted literal', detail: `at offset ${index}` });
      index = message.length;
      return;
    }
    index = close + 1;
  };

  const readNested = (insidePlural: boolean): void => {
    while (index < message.length) {
      const char = message[index] as string;
      if (char === '}') return;
      if (char === '#') {
        if (!insidePlural) issues.push({ message: 'The # symbol is only valid inside a plural block', detail: `offset ${index}` });
        index += 1;
        continue;
      }
      if (char === "'") {
        skipQuoted();
        continue;
      }
      if (char === '{') {
        index += 1;
        parseArgument();
        continue;
      }
      index += 1;
    }
  };

  const parseArgument = (): void => {
    const nameMatch = /^\s*([\w.-]+)\s*/u.exec(message.slice(index));
    if (!nameMatch) {
      issues.push({ message: 'Empty ICU argument', detail: `offset ${index}` });
      index += 1;
      return;
    }
    const name = nameMatch[1] as string;
    index += nameMatch[0].length;
    const typeMatch = /^,\s*(\w+)\s*/u.exec(message.slice(index));
    if (!typeMatch) {
      args.set(name, { name, type: 'argument' });
      if (message[index] === '}') index += 1;
      return;
    }
    const type = typeMatch[1] as string;
    index += typeMatch[0].length;
    if (!CLDR_TYPES.has(type)) {
      issues.push({ message: `Unknown ICU type "${type}"`, argument: name });
      const close = message.indexOf('}', index);
      index = close === -1 ? message.length : close + 1;
      return;
    }
    if (type === 'plural' || type === 'select' || type === 'selectordinal') {
      const offset = /^,?\s*offset:\s*\d+\s*/u.exec(message.slice(index));
      if (offset) index += offset[0].length;
      const categories: string[] = [];
      for (;;) {
        const branch = /^[,\s]*(=?\w+)\s*\{/u.exec(message.slice(index));
        if (!branch) break;
        categories.push(branch[1] as string);
        index += branch[0].length;
        readNested(type !== 'select');
        if (message[index] === '}') index += 1;
      }
      args.set(name, { name, type: type as IcuArgument['type'], categories });
      if (type !== 'select') validateCategories(name, categories, locale, issues);
      if (message[index] === '}') index += 1;
      else issues.push({ message: `Plural block {${name}} is not closed`, argument: name });
      return;
    }
    // number / date / time (with optional skeleton or style)
    const close = findClosingBrace(message, index);
    args.set(name, { name, type: type as IcuArgument['type'] });
    if (close === -1) {
      issues.push({ message: `Argument {${name}} is not closed`, argument: name });
      index = message.length;
      return;
    }
    index = close + 1;
  };

  readNested(false);
  if (index < message.length && message[index] === '}') {
    issues.push({ message: 'Unbalanced closing brace', detail: `offset ${index}` });
  }
  if (countBraces(message) !== 0) {
    issues.push({ message: 'Unbalanced braces in ICU message' });
  }
  return { ok: issues.length === 0, issues, arguments: [...args.values()] };
}

function countBraces(message: string): number {
  let depth = 0;
  for (let i = 0; i < message.length; i += 1) {
    const char = message[i] as string;
    if (char === "'") {
      if (message[i + 1] === "'") {
        i += 1;
        continue;
      }
      const close = message.indexOf("'", i + 1);
      i = close === -1 ? message.length : close;
      continue;
    }
    if (char === '{') depth += 1;
    else if (char === '}') depth -= 1;
  }
  return depth;
}

function findClosingBrace(message: string, from: number): number {
  let depth = 0;
  for (let i = from; i < message.length; i += 1) {
    const char = message[i] as string;
    if (char === "'") {
      const close = message.indexOf("'", i + 1);
      i = close === -1 ? message.length : close;
      continue;
    }
    if (char === '{') depth += 1;
    else if (char === '}') {
      if (depth === 0) return i;
      depth -= 1;
    }
  }
  return -1;
}

function validateCategories(name: string, categories: string[], locale: string, issues: IcuIssue[]): void {
  const allowed = pluralCategoriesFor(locale);
  const named = categories.filter((category) => !category.startsWith('='));
  for (const category of named) {
    if (!CATEGORIES.has(category)) {
      issues.push({ message: `"${category}" is not a CLDR plural category`, argument: name });
    }
  }
  for (const required of allowed) {
    if (!named.includes(required)) {
      issues.push({
        message: `Plural form "${required}" is missing`,
        argument: name,
        detail: `locale requires ${allowed.join(', ')}`,
      });
    }
  }
  for (const category of named) {
    if (CATEGORIES.has(category) && !allowed.includes(category)) {
      issues.push({
        message: `Plural form "${category}" is not used by this locale`,
        argument: name,
        detail: `expected one of ${allowed.join(', ')}`,
      });
    }
  }
}

export function hasIcuSyntax(text: string): boolean {
  return /\{\s*[\w.-]+\s*,\s*(plural|select|selectordinal|number|date|time)/u.test(text);
}
