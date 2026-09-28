import type { RuleRow } from '../types';
import { escapeRegExp } from '../utils/text';
import { hashOf } from '../utils/hash';

export interface GlossaryTerm {
  id?: number;
  /** null = every locale. */
  locale: string | null;
  source: string;
  target: string;
  priority: number;
  matchCase: boolean;
  wholeWord: boolean;
  origin: string;
  confidence: number;
  note?: string;
  /** Internal: pattern is already a regular expression (imported rules). */
  isRegexHint?: boolean;
}

export interface DntTerm {
  id?: number;
  locale: string | null;
  pattern: string;
  /** Literal text that must appear in the target (usually the pattern itself). */
  replacement: string;
  isRegex: boolean;
  priority: number;
  origin: string;
}

export interface CorrectionRule {
  id?: number;
  locale: string | null;
  pattern: string;
  replacement: string;
  isRegex: boolean;
  priority: number;
  origin: string;
  confidence: number;
  note?: string;
}

export interface StyleRule {
  id?: number;
  locale: string | null;
  name: string;
  value: string;
  priority: number;
  origin: string;
}

export interface RuleSet {
  glossary: GlossaryTerm[];
  dnt: DntTerm[];
  corrections: CorrectionRule[];
  styles: StyleRule[];
  /** Stable hash used in translation cache keys. */
  version: string;
}

export function buildRuleSet(rules: RuleRow[]): RuleSet {
  const glossary: GlossaryTerm[] = [];
  const dnt: DntTerm[] = [];
  const corrections: CorrectionRule[] = [];
  const styles: StyleRule[] = [];

  for (const rule of rules) {
    if (!rule.enabled) continue;
    switch (rule.kind) {
      case 'glossary': {
        const json = parseJsonValue(rule.value);
        glossary.push({
          id: rule.id,
          locale: rule.locale ?? null,
          source: rule.pattern,
          target: asString(json?.target) ?? rule.value,
          priority: rule.priority,
          matchCase: asBoolean(json?.matchCase) ?? false,
          wholeWord: asBoolean(json?.wholeWord) ?? true,
          origin: rule.origin,
          confidence: rule.confidence,
          note: rule.note,
        });
        break;
      }
      case 'dnt': {
        const json = parseJsonValue(rule.value);
        dnt.push({
          id: rule.id,
          locale: rule.locale ?? null,
          pattern: rule.pattern,
          replacement: asString(json?.replacement) ?? rule.value ?? rule.pattern,
          isRegex: asBoolean(json?.regex) ?? false,
          priority: rule.priority,
          origin: rule.origin,
        });
        break;
      }
      case 'correction': {
        const json = parseJsonValue(rule.value);
        corrections.push({
          id: rule.id,
          locale: rule.locale ?? null,
          pattern: rule.pattern,
          replacement: asString(json?.to) ?? rule.value,
          isRegex: asBoolean(json?.regex) ?? false,
          priority: rule.priority,
          origin: rule.origin,
          confidence: rule.confidence,
          note: rule.note,
        });
        break;
      }
      case 'style': {
        styles.push({
          id: rule.id,
          locale: rule.locale ?? null,
          name: rule.pattern,
          value: rule.value,
          priority: rule.priority,
          origin: rule.origin,
        });
        break;
      }
      case 'length':
        // Length rules live in the config (they are project structure, not language).
        break;
    }
  }

  glossary.sort((a, b) => b.priority - a.priority || b.source.length - a.source.length);
  dnt.sort((a, b) => b.priority - a.priority || b.pattern.length - a.pattern.length);
  corrections.sort((a, b) => b.priority - a.priority);
  return {
    glossary,
    dnt,
    corrections,
    styles,
    version: hashOf(rules.map((rule) => [rule.kind, rule.locale ?? '', rule.pattern, rule.value, rule.enabled]).sort()),
  };
}

export function emptyRuleSet(): RuleSet {
  return { glossary: [], dnt: [], corrections: [], styles: [], version: hashOf([]) };
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function asBoolean(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined;
}

function parseJsonValue(value: string): Record<string, unknown> | null {
  if (!value.trim().startsWith('{')) return null;
  try {
    return JSON.parse(value) as Record<string, unknown>;
  } catch {
    return null;
  }
}

export function glossaryFor(ruleSet: RuleSet, locale: string): GlossaryTerm[] {
  return ruleSet.glossary.filter((term) => term.locale === null || localeMatches(term.locale, locale));
}

export function dntFor(ruleSet: RuleSet, locale: string): DntTerm[] {
  return ruleSet.dnt.filter((term) => term.locale === null || localeMatches(term.locale, locale));
}

export function correctionsFor(ruleSet: RuleSet, locale: string): CorrectionRule[] {
  return ruleSet.corrections.filter((rule) => rule.locale === null || localeMatches(rule.locale, locale));
}

export function styleValue(ruleSet: RuleSet, locale: string, name: string): string | null {
  const matches = ruleSet.styles.filter(
    (rule) => rule.name === name && (rule.locale === null || localeMatches(rule.locale, locale)),
  );
  if (matches.length === 0) return null;
  matches.sort((a, b) => {
    const aSpecific = a.locale !== null ? 1 : 0;
    const bSpecific = b.locale !== null ? 1 : 0;
    return bSpecific - aSpecific || b.priority - a.priority;
  });
  return (matches[0] as StyleRule).value;
}

export const STYLE_NAMES = {
  trailingPunctuation: 'punctuation.trailing',
  capitalization: 'capitalization',
  ellipsis: 'punctuation.ellipsis',
  cjkFullwidth: 'cjk.fullwidth',
  cjkLatinSpace: 'cjk.latin-space',
  formality: 'formality',
  quotes: 'quotes',
  interpolation: 'interpolation.style',
  spacesAroundPlaceholders: 'placeholder.spacing',
} as const;

export function localeMatches(ruleLocale: string, locale: string): boolean {
  const left = normalize(ruleLocale);
  const right = normalize(locale);
  return left === right;
}

function normalize(locale: string): string {
  return locale.toLowerCase().replace(/_/gu, '-');
}

/** Builds a source-term matcher honouring case/word-boundary options. */
export function termMatcher(term: GlossaryTerm): RegExp {
  const escaped = term.isRegexHint ? term.source : escapeRegExp(term.source);
  const flags = term.matchCase ? 'gu' : 'giu';
  const body = term.wholeWord ? `(?<![\\p{Letter}\\p{Number}])${escaped}(?![\\p{Letter}\\p{Number}])` : escaped;
  return new RegExp(body, flags);
}
