/**
 * CLDR plural categories per language, used by ICU validation and PO writers.
 * Only the categories that appear in real UI catalogs are listed.
 */
const CATEGORIES: Record<string, string[]> = {
  other: ['other'],
  en: ['one', 'other'],
  de: ['one', 'other'],
  nl: ['one', 'other'],
  sv: ['one', 'other'],
  da: ['one', 'other'],
  nb: ['one', 'other'],
  nn: ['one', 'other'],
  es: ['one', 'other'],
  it: ['one', 'other'],
  pt: ['one', 'other'],
  el: ['one', 'other'],
  fi: ['one', 'other'],
  et: ['one', 'other'],
  hu: ['one', 'other'],
  tr: ['one', 'other'],
  id: ['other'],
  ms: ['other'],
  vi: ['other'],
  th: ['other'],
  zh: ['other'],
  ja: ['other'],
  ko: ['other'],
  fil: ['one', 'other'],
  fr: ['one', 'many', 'other'],
  ru: ['one', 'few', 'many', 'other'],
  uk: ['one', 'few', 'many', 'other'],
  be: ['one', 'few', 'many', 'other'],
  sr: ['one', 'few', 'other'],
  hr: ['one', 'few', 'other'],
  bs: ['one', 'few', 'other'],
  pl: ['one', 'few', 'many', 'other'],
  cs: ['one', 'few', 'many', 'other'],
  sk: ['one', 'few', 'many', 'other'],
  lt: ['one', 'few', 'many', 'other'],
  lv: ['zero', 'one', 'other'],
  sl: ['one', 'two', 'few', 'other'],
  ro: ['one', 'few', 'other'],
  ar: ['zero', 'one', 'two', 'few', 'many', 'other'],
  he: ['one', 'two', 'many', 'other'],
  ga: ['one', 'two', 'few', 'many', 'other'],
  cy: ['zero', 'one', 'two', 'few', 'many', 'other'],
  is: ['one', 'other'],
  mk: ['one', 'other'],
  sq: ['one', 'other'],
  hy: ['one', 'other'],
  ka: ['one', 'other'],
  sw: ['one', 'other'],
  ta: ['one', 'other'],
  te: ['one', 'other'],
  ml: ['one', 'other'],
  hi: ['one', 'other'],
  bn: ['one', 'other'],
  ur: ['one', 'other'],
  fa: ['one', 'other'],
};

export function pluralCategoriesFor(locale: string): string[] {
  const normalized = locale.replace(/_/gu, '-').toLowerCase();
  if (CATEGORIES[normalized]) return CATEGORIES[normalized] as string[];
  const base = normalized.split('-')[0] as string;
  return CATEGORIES[base] ?? ['one', 'other'];
}

export const ALL_CATEGORIES = ['zero', 'one', 'two', 'few', 'many', 'other'];

export function isValidCategory(category: string): boolean {
  return ALL_CATEGORIES.includes(category);
}
