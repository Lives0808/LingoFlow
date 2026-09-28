import type { TranslateItem, TranslateOutput } from '../../types';
import { baseLang, isCJKLocale } from '../../utils/text';
import { maskPlaceholders, unmaskPlaceholders } from '../../validate/placeholders';
import type { EngineAvailability, EngineContext, TranslationEngine } from '../engine';
import { SEED_DICTIONARY } from './dictionary';

interface OfflineDictionary {
  [sourceTerm: string]: Record<string, string>;
}

/**
 * Built-in offline engine.
 *
 * Privacy by default: nothing leaves the machine. Quality comes from the
 * project's own glossary + translation memory (which run before this engine),
 * a small seed dictionary for common UI vocabulary, and deterministic
 * placeholder/typography handling.
 */
export class OfflineEngine implements TranslationEngine {
  id = 'offline' as const;
  label = 'Built-in offline';
  privacy = 'local' as const;
  network = false;
  private dictionary: OfflineDictionary;

  constructor(userDictionary?: OfflineDictionary) {
    this.dictionary = { ...SEED_DICTIONARY, ...(userDictionary ?? {}) };
  }

  async availability(): Promise<EngineAvailability> {
    const termCount = Object.keys(this.dictionary).length;
    return {
      ok: true,
      detail: `always available · ${termCount} seeded terms (+ your glossary & memory)`,
    };
  }

  async translate(items: TranslateItem[], ctx: EngineContext): Promise<TranslateOutput[]> {
    return items.map((item) => {
      const { text, confidence, covered, total } = composeTranslation(item.text, item.from, item.to, this.dictionary);
      const notes: string[] = [];
      if (covered === 0) notes.push('no dictionary coverage — add glossary terms or use a local model');
      else if (covered < total) notes.push(`${covered}/${total} words matched the dictionary`);
      if (ctx.config.length.enabled && item.maxChars && [...text].length > item.maxChars) {
        notes.push(`over budget: ${[...text].length} > ${item.maxChars} chars`);
      }
      return {
        id: item.id,
        text,
        engine: this.id,
        confidence,
        notes,
      };
    });
  }
}

export function composeTranslation(
  text: string,
  from: string,
  to: string,
  dictionary: OfflineDictionary,
): { text: string; confidence: number; covered: number; total: number } {
  const targetLang = normalizeTargetLocale(to);
  // Placeholders are opaque: masking keeps `{name}` from becoming `{名称}`.
  const masked = maskPlaceholders(text);
  const parts = masked.text.split(/([\p{Letter}\p{Number}'’-]+)/u);
  const output: Array<{ text: string; translated: boolean }> = [];
  let covered = 0;
  let total = 0;

  let i = 0;
  while (i < parts.length) {
    const part = parts[i] as string;
    if (part.length === 0) {
      i += 1;
      continue;
    }
    if (!/[\p{Letter}\p{Number}]/u.test(part)) {
      output.push({ text: part, translated: false });
      i += 1;
      continue;
    }
    total += 1;
    const phrase = matchPhrase(parts, i, dictionary, targetLang);
    if (phrase) {
      output.push({ text: applyCase(phrase.value, part), translated: true });
      covered += 1;
      total += phrase.words - 1;
      i += phrase.consumed;
      continue;
    }
    const direct = lookup(part, dictionary, targetLang);
    if (direct) {
      output.push({ text: applyCase(direct, part), translated: true });
      covered += 1;
    } else {
      output.push({ text: part, translated: false });
    }
    i += 1;
  }

  const cjk = isCJKLocale(to);
  let result = '';
  for (let index = 0; index < output.length; index += 1) {
    const current = output[index] as { text: string; translated: boolean };
    const next = output[index + 1];
    result += current.text;
    if (!cjk) continue;
    // Drop spaces between two translated tokens in CJK output.
    if (current.text.trim().length === 0 && current.translated === false && current.text === ' ' && next?.translated) {
      const previous = output[index - 1];
      if (previous?.translated) result = result.slice(0, -1);
    }
  }

  result = unmaskPlaceholders(result, masked.map);
  result = normalizeSpacing(result, cjk);
  const confidence = total === 0 ? 0.3 : covered / total;
  return { text: result, confidence: Number(confidence.toFixed(2)), covered, total };
}

function normalizeTargetLocale(locale: string): string {
  const normalized = locale.replace(/_/gu, '-');
  if (hasLocale(normalized)) return normalized;
  const base = baseLang(locale);
  if (hasLocale(base)) return base;
  if (base === 'zh' && hasLocale('zh-CN')) return 'zh-CN';
  return normalized;
}

/** True when the seed dictionary has at least one entry for this locale. */
function hasLocale(locale: string): boolean {
  for (const entry of Object.values(SEED_DICTIONARY)) {
    if (entry[locale] !== undefined) return true;
  }
  return false;
}

function matchPhrase(
  parts: string[],
  index: number,
  dictionary: OfflineDictionary,
  targetLang: string,
): { value: string; consumed: number; words: number } | null {
  // Try 4, 3 and 2 word phrases.
  const words: Array<{ text: string; partIndex: number }> = [];
  for (let i = index; i < parts.length && words.length < 4; i += 2) {
    const part = parts[i] as string;
    if (!/[\p{Letter}\p{Number}]/u.test(part)) break;
    words.push({ text: part, partIndex: i });
  }
  for (let size = Math.min(4, words.length); size >= 2; size -= 1) {
    const slice = words.slice(0, size);
    const lastPart = slice[slice.length - 1] as { text: string; partIndex: number };
    // Require single-space gaps between phrase words.
    let contiguous = true;
    for (let i = 1; i < slice.length; i += 1) {
      const previous = slice[i - 1] as { partIndex: number };
      const current = slice[i] as { partIndex: number };
      if (current.partIndex !== previous.partIndex + 2) contiguous = false;
      const gap = parts[previous.partIndex + 1];
      if (gap !== ' ') contiguous = false;
    }
    if (!contiguous) continue;
    const phrase = slice.map((word) => word.text.toLowerCase()).join(' ');
    const value = lookup(phrase, dictionary, targetLang);
    if (value) {
      return { value, consumed: lastPart.partIndex - index + 1, words: size };
    }
  }
  return null;
}

function lookup(term: string, dictionary: OfflineDictionary, targetLang: string): string | null {
  const lower = term.toLowerCase();
  const entry = dictionary[lower];
  if (entry) {
    const value = entry[targetLang] ?? entry[baseLang(targetLang)] ?? null;
    if (value) return value;
  }
  if (lower.endsWith('s') && lower.length > 3) {
    const singular = dictionary[lower.slice(0, -1)];
    const value = singular?.[targetLang] ?? singular?.[baseLang(targetLang)] ?? null;
    if (value) return value;
  }
  return null;
}

function applyCase(value: string, source: string): string {
  if (!/^[A-Z\u00c0-\u00dc]/u.test(source)) return value;
  if (source === source.toUpperCase() && source.length > 1) return value.toLocaleUpperCase();
  return value.charAt(0).toLocaleUpperCase() + value.slice(1);
}

function normalizeSpacing(text: string, cjk: boolean): string {
  let output = text.replace(/[ \t]{2,}/gu, ' ');
  if (cjk) {
    // Spaces between CJK characters are always wrong; Han<->Latin spacing is a
    // house-style decision, applied later by the style fixer.
    output = output
      .replace(/([\p{Script=Han}\p{Script=Kana}\p{Script=Hangul}]) +(?=[\p{Script=Han}\p{Script=Kana}\p{Script=Hangul}])/gu, '$1')
      .replace(/\s+([，。！？：；、）】》」』])/gu, '$1')
      .replace(/([（【《「『])\s+/gu, '$1');
  }
  return output.trim();
}
