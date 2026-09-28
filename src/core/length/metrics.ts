/**
 * Font metrics for length checks.
 *
 * Real font files are big and version dependent, so LingoFlow ships a compact
 * advance-width table for UI sans-serif faces (Helvetica/Inter/system-ui class).
 * Accuracy is ~±5% for Latin text, which is what you need to catch "this label
 * will never fit" problems before design review.
 */

export interface FontSpec {
  family?: string;
  size: number;
  weight?: number;
  letterSpacing?: number;
}

const ASCII_WIDTHS: Record<string, number> = {
  ' ': 0.278,
  '!': 0.278,
  '"': 0.355,
  '#': 0.556,
  $: 0.556,
  '%': 0.889,
  '&': 0.667,
  "'": 0.191,
  '(': 0.333,
  ')': 0.333,
  '*': 0.389,
  '+': 0.584,
  ',': 0.278,
  '-': 0.333,
  '.': 0.278,
  '/': 0.278,
  ':': 0.278,
  ';': 0.278,
  '<': 0.584,
  '=': 0.584,
  '>': 0.584,
  '?': 0.556,
  '@': 1.015,
  A: 0.667,
  B: 0.667,
  C: 0.722,
  D: 0.722,
  E: 0.667,
  F: 0.611,
  G: 0.778,
  H: 0.722,
  I: 0.278,
  J: 0.5,
  K: 0.667,
  L: 0.556,
  M: 0.833,
  N: 0.722,
  O: 0.778,
  P: 0.667,
  Q: 0.778,
  R: 0.722,
  S: 0.667,
  T: 0.611,
  U: 0.722,
  V: 0.667,
  W: 0.944,
  X: 0.667,
  Y: 0.667,
  Z: 0.611,
  '[': 0.278,
  '\\': 0.278,
  ']': 0.278,
  '^': 0.469,
  _: 0.556,
  '`': 0.333,
  a: 0.556,
  b: 0.556,
  c: 0.5,
  d: 0.556,
  e: 0.556,
  f: 0.278,
  g: 0.556,
  h: 0.556,
  i: 0.222,
  j: 0.222,
  k: 0.5,
  l: 0.222,
  m: 0.833,
  n: 0.556,
  o: 0.556,
  p: 0.556,
  q: 0.556,
  r: 0.333,
  s: 0.5,
  t: 0.278,
  u: 0.556,
  v: 0.5,
  w: 0.722,
  x: 0.5,
  y: 0.5,
  z: 0.5,
  '{': 0.334,
  '|': 0.26,
  '}': 0.334,
  '~': 0.584,
};

for (let digit = 0; digit <= 9; digit += 1) {
  ASCII_WIDTHS[String(digit)] = 0.556;
}

const NARROW = new Set(['i', 'j', 'l', 'I', "'", '.', ',', ':', ';', '!', '|', '(', ')', '[', ']', 'f', 't', 'r']);

export function charWidthEm(char: string): number {
  const ascii = ASCII_WIDTHS[char];
  if (ascii !== undefined) return ascii;
  const code = char.codePointAt(0) ?? 0;
  // CJK ideographs, kana, hangul and fullwidth forms are square.
  if (
    (code >= 0x1100 && code <= 0x115f) ||
    (code >= 0x2e80 && code <= 0xa4cf) ||
    (code >= 0xac00 && code <= 0xd7a3) ||
    (code >= 0xf900 && code <= 0xfaff) ||
    (code >= 0xfe10 && code <= 0xfe6f) ||
    (code >= 0xff00 && code <= 0xff60) ||
    (code >= 0xffe0 && code <= 0xffe6) ||
    (code >= 0x20000 && code <= 0x3fffd)
  ) {
    return 1;
  }
  if (code >= 0x1f300 && code <= 0x1f9ff) return 1.15; // emoji
  if (code >= 0x0e00 && code <= 0x0e7f) return 0.5; // Thai
  if (code >= 0x0900 && code <= 0x097f) return 0.55; // Devanagari
  if (code >= 0x0590 && code <= 0x06ff) return 0.55; // Hebrew / Arabic
  if (code >= 0x0400 && code <= 0x04ff) return 0.58; // Cyrillic
  if (code < 0x0250) return 0.56; // accented Latin
  return 0.6;
}

/** Estimated rendered width in pixels. */
export function estimateWidth(text: string, font: FontSpec): number {
  const weight = font.weight ?? 400;
  const weightFactor = weight >= 700 ? 1.07 : weight >= 600 ? 1.04 : 1;
  let em = 0;
  for (const char of text) em += charWidthEm(char);
  const spacing = (font.letterSpacing ?? 0) * Math.max(0, [...text].length - 1);
  return em * font.size * weightFactor + spacing;
}

/** Rough number of lines when the text is placed in a container of `containerPx`. */
export function estimateLines(text: string, font: FontSpec, containerPx: number): number {
  if (containerPx <= 0) return 1;
  return text
    .split('\n')
    .reduce((lines, segment) => lines + Math.max(1, Math.ceil(estimateWidth(segment, font) / containerPx)), 0);
}

export function charCount(text: string): number {
  return [...text].length;
}

/** Width metrics for a batch of strings; handy for reports. */
export interface WidthMetrics {
  chars: number;
  px: number;
}

export function measure(text: string, font: FontSpec): WidthMetrics {
  return { chars: charCount(text), px: Math.round(estimateWidth(text, font)) };
}
