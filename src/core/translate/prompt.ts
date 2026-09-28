import type { TranslateItem } from '../types';
import { baseLang, isCJKLocale, isRTL } from '../utils/text';
import type { GlossaryTerm } from '../rules/model';

export interface PromptContext {
  sourceLocale: string;
  targetLocale: string;
  glossary: GlossaryTerm[];
  styleHints: string[];
  dnt: string[];
  maxChars?: number;
}

const LOCALE_NOTES: Record<string, string> = {
  'zh-CN': 'Simplified Chinese: use fullwidth punctuation (，。！？：；), no space between Han characters, keep a space between Han and Latin words, use 「您」 for formal product tone when the source is polite.',
  'zh-TW': 'Traditional Chinese: same typography rules as Simplified, use Taiwan vocabulary (設定, 檔案, 儲存).',
  ja: 'Japanese: use fullwidth punctuation, no spaces between characters, prefer です・ます for polite UI when the source is polite, keep katakana for loanwords.',
  ko: 'Korean: use Hangul, no spaces around placeholders, standard polite style (-습니다/하세요) for UI copy.',
  de: 'German: capitalise nouns, prefer the formal address (Sie) for UI, keep sentences compact because German expands ~35%.',
  fr: 'French: use French quotes « », non-breaking space before : ; ! ?, formal address (vous).',
  es: 'Spanish: use inverted question/exclamation marks (¿ ¡), sentence case.',
  'pt-BR': 'Brazilian Portuguese: sentence case, compact UI copy, formal address (você).',
  it: 'Italian: sentence case, formal address (Lei) for product UI.',
  ru: 'Russian: keep placeholders unchanged, formal address (Вы), watch the 1.35× expansion.',
  ar: 'Arabic: right-to-left text, keep Latin placeholders/technical terms LTR, use Modern Standard Arabic.',
  he: 'Hebrew: right-to-left text, keep technical terms in Latin script.',
  th: 'Thai: no spaces between words inside a phrase, use Thai digits only if the source uses them.',
  vi: 'Vietnamese: keep diacritics correct, sentence case.',
  id: 'Indonesian: short direct UI copy, sentence case.',
  tr: 'Turkish: watch the dotted/dotless i when lowercasing, keep placeholders unchanged.',
  pl: 'Polish: use formal address, watch plural forms (one/few/many).',
  nl: 'Dutch: sentence case, compact phrasing, formal address (u) for product UI.',
  hi: 'Hindi: Devanagari script, keep technical terms in Latin when commonly used.',
};

/**
 * Language + product tone guidance handed to LLM engines.
 * This is what keeps several locales consistent with each other.
 */
export function localeGuidance(ctx: PromptContext): string {
  const lines: string[] = [];
  const target = ctx.targetLocale.replace(/_/gu, '-');
  const note = LOCALE_NOTES[target] ?? LOCALE_NOTES[baseLang(target)];
  if (note) lines.push(note);
  if (isCJKLocale(target) && !note) lines.push('CJK typography: fullwidth punctuation, no extra spaces between characters.');
  if (isRTL(target)) lines.push('RTL language: keep placeholders and Latin brand names embedded left-to-right.');
  if (ctx.styleHints.length > 0) lines.push(`House style: ${ctx.styleHints.join('; ')}.`);
  return lines.join('\n');
}

export function buildSystemPrompt(ctx: PromptContext): string {
  const glossary = ctx.glossary
    .filter((term) => term.source.trim().length > 0)
    .slice(0, 120)
    .map((term) => `- ${term.source} → ${term.target}`)
    .join('\n');
  const dnt = ctx.dnt.length > 0 ? ctx.dnt.map((term) => `- ${term}`).join('\n') : '';
  return [
    'You are a senior software localisation engineer.',
    `Translate UI strings from ${ctx.sourceLocale} to ${ctx.targetLocale}.`,
    'Rules:',
    '- Translate only the string values. Never add comments or explanations.',
    '- Preserve placeholders exactly as they appear: {name}, {{count}}, %s, %1$s, {count, plural, ...}, $t(key).',
    '- Preserve HTML/XML tags and their nesting exactly (<b>, </b>, <a href="...">).',
    '- Preserve escape sequences such as \\n and \\t and keep the same number of line breaks.',
    '- Keep the same length budget as the source where possible; UI copy must stay short and natural.',
    '- Do not translate product names, brand names, code identifiers, URLs or e-mail addresses.',
    localeGuidance(ctx),
    glossary ? `Glossary (must be used):\n${glossary}` : '',
    dnt ? `Do not translate:\n${dnt}` : '',
    'Respond with JSON only, shaped like: {"translations":[{"id":"<id>","text":"<translation>"}]}',
  ]
    .filter((line) => line.trim().length > 0)
    .join('\n');
}

export function buildUserPrompt(items: TranslateItem[], ctx: PromptContext): string {
  const payload = items.map((item) => {
    const entry: Record<string, unknown> = { id: item.id, text: item.text };
    if (item.comment) entry.context = item.comment;
    if (item.maxChars) entry.maxChars = item.maxChars;
    if (item.hint) entry.hint = item.hint;
    return entry;
  });
  return [
    `Locale: ${ctx.targetLocale}`,
    'Translate each item. Keep the "id" untouched and return the same number of items.',
    JSON.stringify(payload, null, 2),
  ].join('\n');
}

/** Extracts `{translations:[...]}` from a model answer, tolerating code fences. */
export function parseModelTranslations(content: string): Map<string, string> {
  const result = new Map<string, string>();
  const cleaned = content
    .replace(/^\s*```(?:json)?/u, '')
    .replace(/```\s*$/u, '')
    .trim();
  let parsed: unknown;
  try {
    parsed = JSON.parse(cleaned) as unknown;
  } catch {
    const start = cleaned.indexOf('{');
    const end = cleaned.lastIndexOf('}');
    if (start === -1 || end === -1) return result;
    try {
      parsed = JSON.parse(cleaned.slice(start, end + 1)) as unknown;
    } catch {
      return result;
    }
  }
  const list = (parsed as { translations?: unknown }).translations ?? parsed;
  if (!Array.isArray(list)) return result;
  for (const item of list) {
    if (!item || typeof item !== 'object') continue;
    const record = item as Record<string, unknown>;
    const id = record.id;
    const text = record.text ?? record.translation ?? record.target;
    if (typeof id === 'string' && typeof text === 'string') result.set(id, text);
  }
  return result;
}
