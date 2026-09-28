import type { Issue, RuleRow } from '../types';
import { escapeRegExp, isCJKLocale, normalizeNewlines } from '../utils/text';
import { glossaryFor, type CorrectionRule, type GlossaryTerm, type RuleSet, termMatcher, type DntTerm } from './model';

/** Placeholder tokens used to shield brand names and glossary terms from engines. */
const TOKEN_PREFIX = '\u2063\u2062LF';
const TOKEN_SUFFIX = '\u2062\u2063';

export interface ProtectionResult {
  text: string;
  tokens: Map<string, { source: string; target: string; kind: 'dnt' | 'glossary' }>;
}

/**
 * Replaces glossary terms and do-not-translate strings with invisible tokens
 * before the text reaches an engine, so the engine cannot mistranslate them.
 */
export function protectText(text: string, glossaryTerms: GlossaryTerm[], dnt: DntTerm[]): ProtectionResult {
  const tokens = new Map<string, { source: string; target: string; kind: 'dnt' | 'glossary' }>();
  let output = text;
  let counter = 0;

  const shield = (match: string, target: string, kind: 'dnt' | 'glossary'): string => {
    const token = `${TOKEN_PREFIX}${counter++}${TOKEN_SUFFIX}`;
    tokens.set(token, { source: match, target, kind });
    return token;
  };

  for (const term of dnt) {
    if (term.isRegex) {
      output = output.replace(new RegExp(term.pattern, 'gu'), (match) => shield(match, term.replacement, 'dnt'));
    } else {
      output = output.replace(new RegExp(escapeRegExp(term.pattern), 'gu'), (match) =>
        shield(match, term.replacement || term.pattern, 'dnt'),
      );
    }
  }

  for (const term of glossaryTerms) {
    const matcher = termMatcher(term);
    output = output.replace(matcher, (match) => shield(match, term.target, 'glossary'));
  }

  return { text: output, tokens };
}

/** Restores shielded tokens using the rule target text. */
export function restoreText(text: string, protection: ProtectionResult): string {
  let output = text;
  for (const [token, info] of protection.tokens) {
    const variants = [token, token.replace(/\u2063/gu, ''), token.replace(/\s/gu, '')];
    let replaced = false;
    for (const variant of variants) {
      if (output.includes(variant)) {
        output = output.split(variant).join(info.target);
        replaced = true;
        break;
      }
    }
    if (!replaced) {
      // Engines often strip zero-width markers; the visible `LF<n>` marker is the
      // next best anchor before falling back to the bare index.
      const index = token.replace(/[^\d]/gu, '');
      const marker = `LF${index}`;
      if (output.includes(marker)) {
        output = output.split(marker).join(info.target);
        continue;
      }
      output = output.replace(new RegExp(`\\b${index}\\b`, 'u'), info.target);
    }
  }
  return output;
}

export interface CorrectionResult {
  text: string;
  applied: string[];
}

/** Applies learned/手工 correction rules to a finished translation. */
export function applyCorrections(text: string, rules: CorrectionRule[]): CorrectionResult {
  let output = text;
  const applied: string[] = [];
  for (const rule of rules) {
    if (rule.pattern.trim().length === 0) continue;
    try {
      if (rule.isRegex) {
        const regex = new RegExp(rule.pattern, 'gu');
        if (regex.test(output)) {
          output = output.replace(regex, rule.replacement);
          applied.push(`${rule.pattern} → ${rule.replacement}`);
        }
      } else if (output.includes(rule.pattern)) {
        output = output.split(rule.pattern).join(rule.replacement);
        applied.push(`${rule.pattern} → ${rule.replacement}`);
      }
    } catch {
      // Invalid regex from a hand-edited rule: skip instead of failing the run.
    }
  }
  return { text: output, applied };
}

/** Checks that glossary terms made it into the translation. */
export function glossaryIssues(
  key: string,
  locale: string,
  source: string,
  target: string,
  ruleSet: RuleSet,
  options: { enabled: boolean; mode: 'auto' | 'protect' | 'prompt' | 'enforce' },
): Issue[] {
  if (!options.enabled || options.mode === 'protect') return [];
  const issues: Issue[] = [];
  for (const term of glossaryFor(ruleSet, locale)) {
    if (term.source.trim().length === 0) continue;
    if (!termMatcher(term).test(source)) continue;
    const targetMatcher = new RegExp(escapeRegExp(term.target), 'u');
    if (!targetMatcher.test(target)) {
      issues.push({
        code: 'glossary.missed',
        severity: 'warn',
        locale,
        key,
        message: `Glossary term not applied: "${term.source}" should be "${term.target}"`,
        source,
        target,
        fixable: true,
        suggestion: term.target,
      });
    }
  }
  return issues;
}

/** Checks that do-not-translate strings stayed untouched. */
export function dntIssues(
  key: string,
  locale: string,
  source: string,
  target: string,
  ruleSet: RuleSet,
  enabled: boolean,
): Issue[] {
  if (!enabled) return [];
  const issues: Issue[] = [];
  for (const term of ruleSet.dnt) {
    if (term.locale !== null && term.locale !== locale) continue;
    const inSource = term.isRegex
      ? new RegExp(term.pattern, 'u').test(source)
      : new RegExp(escapeRegExp(term.pattern), 'u').test(source);
    if (!inSource) continue;
    const expected = term.replacement || term.pattern;
    if (!target.includes(expected)) {
      issues.push({
        code: 'dnt.violated',
        severity: 'warn',
        locale,
        key,
        message: `Do-not-translate term was modified: "${expected}"`,
        target,
        fixable: true,
        suggestion: expected,
      });
    }
  }
  return issues;
}

/**
 * Re-adds placeholders that an engine dropped, in a deterministic order.
 * Returns null when nothing was lost.
 */
export function repairPlaceholders(source: string, target: string, placeholders: string[]): string | null {
  const missing = placeholders.filter((placeholder) => !target.includes(placeholder));
  if (missing.length === 0) return null;
  const insertAt = target.search(/[.!?。！？]/u);
  const head = insertAt >= 0 ? target.slice(0, insertAt).trimEnd() : target.trimEnd();
  const tail = insertAt >= 0 ? target.slice(insertAt) : '';
  const joiner = isCJKLocale('zh') ? '' : ' ';
  return `${head}${joiner}${missing.join(' ')}${tail}`;
}

export function normalizeTargetText(text: string): string {
  return normalizeNewlines(text).replace(/[\u200b\u2060]/gu, '');
}

export function ruleRowToGlossary(rule: RuleRow): GlossaryTerm {
  return {
    id: rule.id,
    locale: rule.locale ?? null,
    source: rule.pattern,
    target: rule.value,
    priority: rule.priority,
    matchCase: false,
    wholeWord: true,
    origin: rule.origin,
    confidence: rule.confidence,
  };
}
