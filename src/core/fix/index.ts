import type { ResolvedConfig } from '../config/schema';
import type { CatalogEntry } from '../types';
import type { RuleSet } from '../rules/model';
import { applyCorrections, type CorrectionResult } from '../rules/apply';
import { correctionsFor } from '../rules/model';
import { measure } from '../length/metrics';
import { resolveBudget } from '../length/budget';
import { comparePlaceholders } from '../validate/placeholders';
import { adaptToBudget } from './adapt';
import { applyCjkStyle, applyPunctuationStyle, reflow, repairPlaceholders } from './text';

export interface FixContext {
  config: ResolvedConfig;
  ruleSet: RuleSet;
  sourceLocale: string;
}

export interface FixInput {
  key: string;
  locale: string;
  source: string;
  target: string;
  entry?: CatalogEntry;
}

export interface FixOutcome {
  text: string;
  fixes: string[];
  overBudget: boolean;
  corrections: string[];
}

/**
 * Deterministic post-processing applied to every translated string:
 * line breaks → punctuation/style → CJK typography → placeholders → length adapt.
 * All steps are idempotent so `fix` can be re-run safely.
 */
export function fixEntry(ctx: FixContext, input: FixInput): FixOutcome {
  const fixes: string[] = [];
  let text = input.target;

  const reflowResult = reflow(text, {
    locale: input.locale,
    ruleSet: ctx.ruleSet,
    sourceHasNewline: /[\n\r]/u.test(input.source),
  });
  if (reflowResult.fixed) {
    text = reflowResult.text;
    fixes.push('normalised line breaks');
  }

  const punctuation = applyPunctuationStyle(text, {
    locale: input.locale,
    ruleSet: ctx.ruleSet,
    sourceHasNewline: false,
  });
  text = punctuation.text;
  fixes.push(...punctuation.fixed);

  const cjk = applyCjkStyle(text, { locale: input.locale, ruleSet: ctx.ruleSet, sourceHasNewline: false });
  text = cjk.text;
  fixes.push(...cjk.fixed);

  let corrections: CorrectionResult = { text, applied: [] };
  const rules = correctionsFor(ctx.ruleSet, input.locale);
  if (rules.length > 0) {
    corrections = applyCorrections(text, rules);
    if (corrections.applied.length > 0) {
      text = corrections.text;
      fixes.push(`applied ${corrections.applied.length} learned correction(s)`);
    }
  }

  if (ctx.config.validate.placeholders) {
    const comparison = comparePlaceholders(input.source, text);
    if (comparison.missing.length > 0) {
      const repaired = repairPlaceholders(input.source, text, comparison.missing);
      if (repaired.fixed) {
        text = repaired.text;
        fixes.push(`restored placeholder(s): ${comparison.missing.join(', ')}`);
      }
    }
  }

  const budget = resolveBudget(ctx.config, input.key, input.entry);
  const font = {
    family: ctx.config.length.font.family,
    size: ctx.config.length.font.size,
    weight: ctx.config.length.font.weight,
    letterSpacing: ctx.config.length.font.letterSpacing,
  };
  const measureValue = (value: string): number => {
    const metrics = measure(value, font);
    return budget.unit === 'px' ? metrics.px : metrics.chars;
  };
  const adapt = adaptToBudget(text, input.source, budget, {
    config: ctx.config,
    locale: input.locale,
    measure: measureValue,
  });
  if (adapt.fixes.length > 0) {
    text = adapt.text;
    fixes.push(...adapt.fixes);
  }

  return {
    text,
    fixes,
    overBudget: adapt.overBudget,
    corrections: corrections.applied,
  };
}

/**
 * When a translation is over budget and the engine supports hints, retranslate
 * with an explicit character limit ("shorten" strategy).
 */
export function shouldRetranslateForLength(config: ResolvedConfig, outcome: FixOutcome): boolean {
  if (!config.length.enabled) return false;
  if (!outcome.overBudget) return false;
  return (config.length.autofix?.strategies ?? []).includes('retranslate');
}
