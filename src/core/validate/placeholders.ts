/**
 * Placeholder extraction and comparison.
 *
 * Supports the syntaxes that show up in real projects:
 *   {name}            i18next / ICU simple argument
 *   {count, plural, …} ICU (the argument name is compared, syntax is checked in icu.ts)
 *   {{name}}          Vue i18n / Handlebars / Angular
 *   %s %d %1$s %(name)s  printf (Android, gettext, Rails)
 *   ${name}           template literal style
 *   $t(key)           nested translation reference
 */

export type PlaceholderKind = 'icu' | 'icu-double' | 'printf' | 'printf-named' | 'printf-indexed' | 'dollar-brace' | 'nested-translation';

export interface PlaceholderToken {
  raw: string;
  /** Canonical identity, e.g. `{name}` or `%s`. */
  key: string;
  kind: PlaceholderKind;
  start: number;
  end: number;
}

const PATTERNS: Array<{ kind: PlaceholderKind; regex: RegExp; canonical: (match: RegExpExecArray) => string }> = [
  {
    kind: 'dollar-brace',
    regex: /\$\{\s*[\p{Letter}\p{Number}._-]+\s*\}/gu,
    canonical: (match) => `\${${/[\p{Letter}\p{Number}._-]+/u.exec(match[0])?.[0] ?? ''}}`,
  },
  {
    kind: 'icu-double',
    regex: /\{\{\s*[\p{Letter}\p{Number}._-]+\s*\}\}/gu,
    canonical: (match) => `{{${/[\p{Letter}\p{Number}._-]+/u.exec(match[0])?.[0] ?? ''}}}`,
  },
  {
    kind: 'printf-indexed',
    regex: /%\d+\$[sdif]/gu,
    canonical: (match) => `%${match[0].slice(-1)}`,
  },
  {
    kind: 'printf-named',
    regex: /%\((\w+)\)[sdif]/gu,
    canonical: (match) => `%(${match[1] ?? ''})s`,
  },
  {
    kind: 'printf',
    regex: /%[sdif]/gu,
    canonical: (match) => match[0],
  },
  {
    kind: 'nested-translation',
    regex: /\$t\(\s*['"][^'"]+['"]\s*\)/gu,
    canonical: (match) => match[0].replace(/\s+/gu, ''),
  },
];

export function extractPlaceholders(text: string): PlaceholderToken[] {
  const tokens: PlaceholderToken[] = [];
  const claimed: Array<[number, number]> = [];
  const isClaimed = (start: number, end: number): boolean => claimed.some(([from, to]) => start < to && end > from);
  // Longest/most specific patterns first so `{{name}}` is not read as `{name}`.
  for (const pattern of PATTERNS) {
    pattern.regex.lastIndex = 0;
    let match: RegExpExecArray | null;
    while ((match = pattern.regex.exec(text)) !== null) {
      const start = match.index;
      const end = start + match[0].length;
      if (isClaimed(start, end)) continue;
      claimed.push([start, end]);
      tokens.push({ raw: match[0], key: pattern.canonical(match), kind: pattern.kind, start, end });
    }
  }
  // ICU arguments need balanced-brace handling, which regexes cannot express.
  for (let index = 0; index < text.length; index += 1) {
    if (text[index] !== '{') continue;
    if (text[index + 1] === '{') continue;
    if (text[index - 1] === '$') continue;
    const nameMatch = /^\{\s*([\p{Letter}\p{Number}._-]+)\s*/u.exec(text.slice(index));
    if (!nameMatch) continue;
    const body = scanBalanced(text, index);
    if (body < 0) continue;
    const end = body;
    if (isClaimed(index, end)) continue;
    claimed.push([index, end]);
    tokens.push({
      raw: text.slice(index, end),
      key: `{${nameMatch[1] as string}}`,
      kind: 'icu',
      start: index,
      end,
    });
  }
  return tokens.sort((a, b) => a.start - b.start);
}

/** Returns the index after the matching `}`, or -1 when unbalanced. */
function scanBalanced(text: string, start: number): number {
  let depth = 0;
  for (let index = start; index < text.length; index += 1) {
    const char = text[index] as string;
    if (char === "'") {
      const close = text.indexOf("'", index + 1);
      index = close === -1 ? text.length : close;
      continue;
    }
    if (char === '{') depth += 1;
    else if (char === '}') {
      depth -= 1;
      if (depth === 0) return index + 1;
    }
  }
  return -1;
}

/** Canonical multiset: `{count, plural, one {...} other {...}}` counts as `{count}`. */
export function placeholderKeys(text: string): string[] {
  const tokens = extractPlaceholders(text);
  const keys = new Set<string>();
  for (const token of tokens) {
    if (token.key === '{}') continue;
    keys.add(token.key);
  }
  return [...keys];
}

export interface PlaceholderComparison {
  missing: string[];
  extra: string[];
  matching: string[];
}

export function comparePlaceholders(source: string, target: string): PlaceholderComparison {
  const sourceKeys = placeholderKeys(source);
  const targetKeys = placeholderKeys(target);
  const sourceSet = new Set(sourceKeys);
  const targetSet = new Set(targetKeys);
  return {
    missing: sourceKeys.filter((key) => !targetSet.has(key)),
    extra: targetKeys.filter((key) => !sourceSet.has(key)),
    matching: sourceKeys.filter((key) => targetSet.has(key)),
  };
}

/** Protects placeholders with sentinel tokens before machine translation. */
export function maskPlaceholders(text: string): { text: string; map: Map<string, string> } {
  const tokens = extractPlaceholders(text);
  const map = new Map<string, string>();
  let output = text;
  // Replace from the end so offsets stay valid.
  for (const token of [...tokens].reverse()) {
    const sentinel = `\u2063${tokens.indexOf(token)}\u2063`;
    map.set(sentinel, token.raw);
    output = `${output.slice(0, token.start)}${sentinel}${output.slice(token.end)}`;
  }
  return { text: output, map };
}

export function unmaskPlaceholders(text: string, map: Map<string, string>): string {
  let output = text;
  for (const [sentinel, raw] of map) {
    output = output.split(sentinel).join(raw);
  }
  return output;
}
