/**
 * Text helpers shared by validation, fixing, length metrics and rules.
 * Everything here is pure and locale-agnostic; locale policy lives in `core/validate`.
 */

/** Languages written with Han characters (fullwidth punctuation, no word spacing). */
export const CJK_LANGS = new Set(['zh', 'ja', 'ko']);

/** RTL languages, used for bidi sanity notes in reports. */
export const RTL_LANGS = new Set(['ar', 'he', 'fa', 'ur']);

/** Script detection buckets, enough to catch "German locale contains Chinese". */
export type ScriptId = 'han' | 'hangul' | 'kana' | 'cyrillic' | 'arabic' | 'hebrew' | 'thai' | 'devanagari' | 'latin' | 'other';

const SCRIPT_RANGES: Array<[ScriptId, number, number]> = [
  ['han', 0x3400, 0x4dbf],
  ['han', 0x4e00, 0x9fff],
  ['han', 0xf900, 0xfaff],
  ['kana', 0x3040, 0x30ff],
  ['hangul', 0xac00, 0xd7af],
  ['hangul', 0x1100, 0x11ff],
  ['cyrillic', 0x0400, 0x04ff],
  ['arabic', 0x0600, 0x06ff],
  ['hebrew', 0x0590, 0x05ff],
  ['thai', 0x0e00, 0x0e7f],
  ['devanagari', 0x0900, 0x097f],
];

/** Language -> expected script(s). Used by the `script.mismatch` check. */
export const LANG_SCRIPT_EXPECTATION: Record<string, ScriptId[]> = {
  zh: ['han'],
  'zh-cn': ['han'],
  'zh-tw': ['han'],
  'zh-hans': ['han'],
  'zh-hant': ['han'],
  ja: ['kana', 'han'],
  ko: ['hangul', 'han'],
  ru: ['cyrillic'],
  uk: ['cyrillic'],
  bg: ['cyrillic'],
  ar: ['arabic'],
  fa: ['arabic'],
  ur: ['arabic'],
  he: ['hebrew'],
  th: ['thai'],
  hi: ['devanagari'],
};

export function baseLang(locale: string): string {
  return locale.toLowerCase().split(/[-_]/u)[0] ?? locale.toLowerCase();
}

export function isCJKLocale(locale: string): boolean {
  return CJK_LANGS.has(baseLang(locale));
}

export function isRTL(locale: string): boolean {
  return RTL_LANGS.has(baseLang(locale));
}

export function charScript(codePoint: number): ScriptId {
  for (const [script, from, to] of SCRIPT_RANGES) {
    if (codePoint >= from && codePoint <= to) return script;
  }
  if (
    (codePoint >= 0x41 && codePoint <= 0x5a) ||
    (codePoint >= 0x61 && codePoint <= 0x7a) ||
    (codePoint >= 0x00c0 && codePoint <= 0x024f)
  ) {
    return 'latin';
  }
  return 'other';
}

export function scriptsIn(text: string): ScriptId[] {
  const found = new Set<ScriptId>();
  for (const char of text) {
    const script = charScript(char.codePointAt(0) ?? 0);
    if (script !== 'other') found.add(script);
  }
  return [...found];
}

export function hasScript(text: string, script: ScriptId): boolean {
  return scriptsIn(text).includes(script);
}

export function isHan(char: string): boolean {
  const code = char.codePointAt(0) ?? 0;
  return charScript(code) === 'han';
}

export function countChars(text: string): number {
  return [...text].length;
}

/** Visible length: placeholders / tags / escapes count as a single unit. */
export function visibleLength(text: string): number {
  return countChars(text.replace(/\{[^{}]*\}/gu, '{x}').replace(/<[^<>]+>/gu, '<x>'));
}

export function normalizeNewlines(text: string): string {
  return text.replace(/\r\n?/gu, '\n');
}

export function toCRLF(text: string): string {
  return normalizeNewlines(text).replace(/\n/gu, '\r\n');
}

export function lineCount(text: string): number {
  if (text.length === 0) return 0;
  return normalizeNewlines(text).split('\n').length;
}

export function collapseWhitespace(text: string): string {
  return text.replace(/[ \t\u00a0]{2,}/gu, ' ').replace(/[ \t]+$/gmu, '');
}

export function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
}

export function slugify(text: string, maxWords = 6): string {
  const ascii = text
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/gu, '')
    .replace(/[^\p{Letter}\p{Number}\s_-]+/gu, ' ')
    .trim()
    .split(/\s+/u)
    .filter(Boolean)
    .slice(0, maxWords);
  const words = ascii.length > 0 ? ascii : ['text'];
  const slug = words
    .map((word, index) =>
      index === 0 ? word.toLowerCase() : `${word.charAt(0).toUpperCase()}${word.slice(1).toLowerCase()}`,
    )
    .join('');
  return slug.replace(/[^\p{Letter}\p{Number}_]/gu, '') || 'text';
}

/** Column/identifier/url/colour detection used to skip hardcoded-string noise. */
const CODE_LIKE_PATTERNS = [
  /^(https?|wss?|ftp|mailto|data):/iu,
  /^[a-z][a-z0-9+.-]*:\/\//iu,
  /^[.#][\w-]+(\s*[,>~+]\s*[.#][\w-]+)*$/u,
  /^[A-Z_][A-Z0-9_]*$/u,
  /^[a-z][a-zA-Z0-9]*$`?$/u,
  /^[a-z0-9]+(?:[._/-][a-z0-9]+)+$/iu,
  /^#[0-9a-f]{3,8}$/iu,
  /^(rgba?|hsla?)\(/iu,
  /^\d+(\.\d+)?(px|em|rem|%|vh|vw|s|ms|deg)?$/iu,
  /^[a-z-]+$/u,
  /^\{\{?[\w.\[\]-]+?\}\}?$/u,
  /^%[sdif]$/u,
  /^\\[ntr]$/u,
  /^@[\w-]+$/u,
  /^::?[\w-]+$/u,
  /^data-testid=/iu,
];

export function looksLikeCode(text: string): boolean {
  const value = text.trim();
  if (value.length === 0) return true;
  return CODE_LIKE_PATTERNS.some((pattern) => pattern.test(value));
}

const UI_ATTRIBUTES = new Set([
  'placeholder',
  'title',
  'label',
  'alt',
  'aria-label',
  'aria-description',
  'aria-placeholder',
  'aria-valuetext',
  'data-tooltip',
  'tooltip',
  'helpertext',
  'helptext',
  'description',
  'caption',
  'confirmtext',
  'canceltext',
  'emptytext',
  'errortext',
  'message',
]);

export function isUiAttribute(name: string): boolean {
  return UI_ATTRIBUTES.has(name.toLowerCase());
}

/** Heuristic: does this literal read like human UI copy? */
export function looksLikeUiCopy(text: string, options: { minWords?: number } = {}): boolean {
  const value = text.trim();
  if (value.length < 3) return false;
  if (looksLikeCode(value)) return false;
  if (/[\n\r\t]/u.test(value)) return false;
  const hasLetter = /\p{Letter}/u.test(value);
  if (!hasLetter) return false;
  const words = value.split(/\s+/u).filter(Boolean);
  const minWords = options.minWords ?? 1;
  if (words.length < minWords) {
    // Single words are still UI copy when they read like a label.
    return /^[A-Z\p{Lu}]/u.test(value) && words.length === 1 && value.length <= 24 ? true : false;
  }
  if (value.length > 240) return false;
  // Sentences / labels, not property paths.
  const letterRatio = [...value].filter((char) => /\p{Letter}/u.test(char)).length / value.length;
  return letterRatio > 0.5;
}

/** Title Case / sentence case detection for style unification. */
export function detectCaseStyle(text: string): 'title' | 'sentence' | 'lower' | 'upper' | 'unknown' {
  const letters = text.replace(/[^\p{Letter} ]/gu, '').trim();
  if (letters.length === 0) return 'unknown';
  if (letters === letters.toUpperCase()) return 'upper';
  if (letters === letters.toLowerCase()) return 'lower';
  const words = letters.split(/\s+/u).filter((word) => word.length > 2);
  if (words.length === 0) return 'sentence';
  const isTitle = words.every((word) => /^\p{Lu}/u.test(word));
  return isTitle ? 'title' : 'sentence';
}

export function titleCase(text: string): string {
  return text.replace(/\p{Letter}[\p{Letter}'’]*/gu, (word) => {
    if (word.length <= 3 && /^(a|an|and|or|of|to|in|on|at|by|for|the|is|as)$/iu.test(word)) {
      return word.toLowerCase();
    }
    return `${word.charAt(0).toLocaleUpperCase()}${word.slice(1).toLocaleLowerCase()}`;
  });
}

export function sentenceCase(text: string): string {
  const lowered = text.toLocaleLowerCase();
  return lowered.replace(/(^\s*[\p{Letter}]|(?<=[.!?。！？]\s+)[\p{Letter}])/gu, (char) => char.toLocaleUpperCase());
}

const WORD_RE = /[\p{Letter}\p{Number}]+/gu;

export function tokenizeWords(text: string): string[] {
  return text.match(WORD_RE) ?? [];
}

export function levenshtein(a: string, b: string): number {
  if (a === b) return 0;
  if (a.length === 0) return b.length;
  if (b.length === 0) return a.length;
  const previous: number[] = new Array(b.length + 1);
  const current: number[] = new Array(b.length + 1);
  for (let j = 0; j <= b.length; j += 1) previous[j] = j;
  for (let i = 1; i <= a.length; i += 1) {
    current[0] = i;
    for (let j = 1; j <= b.length; j += 1) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      current[j] = Math.min((current[j - 1] ?? 0) + 1, (previous[j] ?? 0) + 1, (previous[j - 1] ?? 0) + cost);
    }
    for (let j = 0; j <= b.length; j += 1) previous[j] = current[j] ?? 0;
  }
  return previous[b.length] ?? 0;
}

export function normalizedSimilarity(a: string, b: string): number {
  const left = a.toLowerCase().trim();
  const right = b.toLowerCase().trim();
  if (left.length === 0 && right.length === 0) return 1;
  const distance = levenshtein(left, right);
  return 1 - distance / Math.max(left.length, right.length, 1);
}

export function jaccard(a: Set<string>, b: Set<string>): number {
  if (a.size === 0 && b.size === 0) return 0;
  let intersection = 0;
  for (const item of a) if (b.has(item)) intersection += 1;
  return intersection / (a.size + b.size - intersection);
}

/** Blend of edit distance and token overlap; better for TM fuzzy matching. */
export function fuzzyScore(source: string, candidate: string): number {
  const normalized = normalizedSimilarity(source, candidate);
  const tokenScore = jaccard(new Set(tokenizeWords(source.toLowerCase())), new Set(tokenizeWords(candidate.toLowerCase())));
  return Math.max(normalized, normalized * 0.6 + tokenScore * 0.4);
}

export interface TextChange {
  from: string;
  to: string;
}

/**
 * Minimal diff of edited vs machine output, used to learn correction rules.
 * Word level, good enough to spot "登入" -> "登录" style post-edits.
 */
export function wordDiff(before: string, after: string): { changes: TextChange[]; similarity: number } {
  const a = tokenizeWords(before);
  const b = tokenizeWords(after);
  const changes: TextChange[] = [];
  let i = 0;
  while (i < a.length && i < b.length && a[i] === b[i]) i += 1;
  let endA = a.length - 1;
  let endB = b.length - 1;
  while (endA >= i && endB >= i && a[endA] === b[endB]) {
    endA -= 1;
    endB -= 1;
  }
  const removed = a.slice(i, endA + 1);
  const added = b.slice(i, endB + 1);
  if (removed.length > 0 && removed.length === added.length) {
    // Same length on both sides: pair the tokens up so reviewers' word level
    // substitutions ("登入" -> "登录") become individually reusable rules.
    for (let index = 0; index < removed.length; index += 1) {
      const from = removed[index] as string;
      const to = added[index] as string;
      if (from !== to) changes.push({ from, to });
    }
  } else if (removed.length > 0 || added.length > 0) {
    changes.push({ from: removed.join(' '), to: added.join(' ') });
  }
  return { changes, similarity: normalizedSimilarity(before, after) };
}

/** Replaces the first `\n` runs with a space (used by reflow). */
export function unwrapLines(text: string): string {
  return normalizeNewlines(text).replace(/\s*\n\s*/gu, ' ');
}

/** Wrap a single line to a maximum width at word boundaries (never inside placeholders). */
export function wrapText(text: string, maxWidth: number): string {
  const normalized = unwrapLines(text);
  if (maxWidth <= 0 || countChars(normalized) <= maxWidth) return normalized;
  const tokens = normalized.split(/(\s+)/u).filter((token) => token.length > 0);
  const lines: string[] = [];
  let current = '';
  for (const token of tokens) {
    const candidate = current.length === 0 ? token : `${current}${token}`;
    if (countChars(candidate) > maxWidth && current.trim().length > 0) {
      lines.push(current.trimEnd());
      current = token.trimStart();
    } else {
      current = candidate;
    }
  }
  if (current.trim().length > 0) lines.push(current.trimEnd());
  return lines.join('\n');
}

/** True when the string carries no letters (numbers, emoji, placeholders only). */
export function isPunctuationOnly(text: string): boolean {
  return !/\p{Letter}/u.test(text);
}
