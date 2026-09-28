import type { ResolvedConfig } from '../config/schema';
import type { LengthBudget } from '../types';
import { baseLang, isCJKLocale } from '../utils/text';

/**
 * Abbreviation + shortening strategies used when a translation exceeds a hard
 * UI budget. Only well-known, safe rewrites are applied; LingoFlow never
 * silently truncates copy.
 */
const ABBREVIATIONS: Record<string, Array<[RegExp, string]>> = {
  en: [
    [/\binformation\b/giu, 'info'],
    [/\bconfiguration\b/giu, 'config'],
    [/\bpreferences\b/giu, 'prefs'],
    [/\bnotification(s)?\b/giu, 'notif$1'],
    [/\bmessage(s)?\b/giu, 'msg$1'],
    [/\bnumber\b/giu, 'no.'],
    [/\bapproximately\b/giu, '~'],
    [/\bwith\b/giu, 'w/'],
    [/\band\b/giu, '&'],
    [/\bdelete\b/giu, 'remove'],
    [/\bcancel\b/giu, 'discard'],
    [/\bsave changes\b/giu, 'save'],
    [/\blearn more\b/giu, 'details'],
    [/\bget started\b/giu, 'start'],
  ],
  de: [
    [/\bKonfiguration\b/gu, 'Konfig.'],
    [/\bVerwaltung\b/gu, 'Verw.'],
    [/\bBenachrichtigungen\b/gu, 'Benachr.'],
    [/\bEinstellungen\b/gu, 'Optionen'],
    [/\bInformationen\b/gu, 'Infos'],
    // `\b` is ASCII-only, so umlauts need explicit lookarounds.
    [/(?<![\p{Letter}])Änderungen speichern(?![\p{Letter}])/gu, 'Speichern'],
    [/(?<![\p{Letter}])Änderungen verwerfen(?![\p{Letter}])/gu, 'Verwerfen'],
    [/(?<![\p{Letter}])Benachrichtigung(en)?(?![\p{Letter}])/gu, 'Meldung$1'],
  ],
  fr: [
    [/\bconfiguration\b/giu, 'config.'],
    [/\bparamètres\b/giu, 'réglages'],
    [/\bnotification(s)?\b/giu, 'notif$1'],
    [/\binformation(s)?\b/giu, 'info$1'],
  ],
  es: [
    [/\bconfiguración\b/giu, 'config.'],
    [/\bnotificación(es)?\b/giu, 'notif$1'],
    [/\binformación\b/giu, 'info'],
  ],
  'pt-BR': [
    [/\bconfiguração\b/giu, 'config.'],
    [/\binformação\b/giu, 'info'],
  ],
  ru: [
    [/\bНастройки\b/gu, 'Опции'],
    [/\bИнформация\b/gu, 'Инфо'],
  ],
};

const FILLER_PATTERNS: Array<[RegExp, string]> = [
  [/^\s*Please\s+/iu, ''],
  [/\bPlease\b\s*/giu, ''],
  [/\bkindly\b\s*/giu, ''],
  [/^\s*请\s*/u, ''],
  [/^\s*请您\s*/u, ''],
  [/\s*了$/u, ''],
];

export interface AdaptResult {
  text: string;
  fixes: string[];
  /** True when the text still exceeds the budget after adaptation. */
  overBudget: boolean;
}

/**
 * Tries to bring a translation inside its length budget.
 * `strategies` is ordered: cheap/safe rewrites first, retranslation last.
 */
export function adaptToBudget(
  text: string,
  source: string,
  budget: LengthBudget & { unit: 'px' | 'chars'; widget?: string },
  options: { config: ResolvedConfig; locale: string; measure: (value: string) => number },
): AdaptResult {
  const fixes: string[] = [];
  let output = text;
  const limit = budget.max;
  if (limit === undefined) return { text, fixes, overBudget: false };

  const isOver = (): boolean => options.measure(output) > limit;
  if (!isOver()) return { text, fixes, overBudget: false };

  const strategies = options.config.length.autofix?.strategies ?? ['reflow', 'punctuation', 'abbreviate', 'shorten'];
  const localeKey = isCJKLocale(options.locale) || baseLang(options.locale) === 'en' ? options.locale : baseLang(options.locale);

  if (strategies.includes('abbreviate')) {
    const table = ABBREVIATIONS[localeKey] ?? ABBREVIATIONS[baseLang(options.locale)] ?? ABBREVIATIONS.en ?? [];
    for (const [pattern, replacement] of table) {
      if (!isOver()) break;
      const next = output.replace(pattern, replacement);
      if (next !== output) {
        output = next;
        fixes.push(`abbreviated "${pattern.source}"`);
      }
    }
  }

  if (strategies.includes('shorten') && isOver()) {
    for (const [pattern, replacement] of FILLER_PATTERNS) {
      if (!isOver()) break;
      const next = output.replace(pattern, replacement);
      if (next !== output && next.trim().length > 0) {
        output = next;
        fixes.push('removed filler words');
      }
    }
  }

  // Never shrink a placeholder-only or single-character string away.
  if (output.trim().length === 0) return { text, fixes: [], overBudget: true };

  return { text: output, fixes, overBudget: isOver() };
}
