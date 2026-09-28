import type { TranslateItem, TranslateOutput } from '../../types';
import { maskPlaceholders, unmaskPlaceholders } from '../../validate/placeholders';
import type { EngineAvailability, EngineContext, TranslationEngine } from '../engine';

const ACCENTS: Record<string, string> = {
  a: 'á', b: 'ƀ', c: 'ç', d: 'ð', e: 'é', f: 'ƒ', g: 'ĝ', h: 'ĥ', i: 'í', j: 'ĵ', k: 'ķ', l: 'ļ',
  m: 'ɱ', n: 'ñ', o: 'ó', p: 'þ', q: 'ǫ', r: 'ŕ', s: 'š', t: 'ţ', u: 'ú', v: 'ṽ', w: 'ŵ', x: 'ẋ',
  y: 'ý', z: 'ž', A: 'Á', B: 'Ɓ', C: 'Ç', D: 'Ð', E: 'É', F: 'Ƒ', G: 'Ĝ', H: 'Ĥ', I: 'Í', J: 'Ĵ',
  K: 'Ķ', L: 'Ļ', M: 'Ṁ', N: 'Ñ', O: 'Ó', P: 'Þ', Q: 'Ǫ', R: 'Ŕ', S: 'Š', T: 'Ţ', U: 'Ú', V: 'Ṽ',
  W: 'Ŵ', X: 'Ẋ', Y: 'Ý', Z: 'Ž',
};

/** Locales that request pseudo-localisation by convention. */
export const PSEUDO_LOCALE_PATTERN = /^(en[-_]?xa|ar[-_]?xb|qps[-_]?ploc|xx|pseudo|pseudo[-_]?\w*)$/iu;

export function isPseudoLocale(locale: string): boolean {
  return PSEUDO_LOCALE_PATTERN.test(locale);
}

/**
 * Pseudo-localisation engine: accents every letter and pads the string by
 * `expansion` (default +40%). This is how you catch truncation and layout
 * breakage *before* paying for real translations.
 */
export class PseudoEngine implements TranslationEngine {
  id = 'pseudo' as const;
  label = 'Pseudo-localisation (en-XA style)';
  privacy = 'local' as const;
  network = false;
  private expansion: number;

  constructor(expansion = 0.4) {
    this.expansion = expansion;
  }

  async availability(): Promise<EngineAvailability> {
    return { ok: true, detail: `always available · +${Math.round(this.expansion * 100)}% expansion` };
  }

  async translate(items: TranslateItem[], _ctx: EngineContext): Promise<TranslateOutput[]> {
    return items.map((item) => ({
      id: item.id,
      text: pseudoLocalize(item.text, this.expansion, item.maxChars),
      engine: this.id,
      confidence: 1,
      notes: ['pseudo-locale for layout testing'],
    }));
  }
}

export function pseudoLocalize(text: string, expansion = 0.4, maxChars?: number): string {
  // Placeholders and tags survive pseudo-localisation untouched, exactly like a
  // real engine is expected to keep them.
  const masked = maskPlaceholders(text);
  const source = masked.text;
  const target = Math.max(source.length, Math.round(source.length * (1 + expansion)));
  const accented = [...source].map((char) => ACCENTS[char] ?? char).join('');
  const padded =
    accented.length >= target
      ? `⟦${accented}⟧`
      : `⟦${accented}${'·'.repeat(Math.max(0, target - accented.length))}⟧`;
  const bounded =
    maxChars && [...padded].length > maxChars ? `⟦${accented}⟧`.slice(0, Math.max(3, maxChars - 1)) : padded;
  return unmaskPlaceholders(bounded, masked.map);
}
