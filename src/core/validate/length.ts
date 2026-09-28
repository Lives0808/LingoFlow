import type { CatalogEntry, Issue } from '../types';
import type { ResolvedConfig } from '../config/schema';
import { estimateLines, measure, type FontSpec } from '../length/metrics';
import { expansionLimit, resolveBudget, type ResolvedBudget } from '../length/budget';

export interface LengthMetrics {
  chars: number;
  px: number;
  sourceChars: number;
  sourcePx: number;
  ratio: number;
  containerPx: number;
  lines: number;
  budget: ResolvedBudget;
  widget: string;
}

export interface LengthCheckResult {
  issues: Issue[];
  metrics: LengthMetrics;
}

export function fontSpecOf(config: ResolvedConfig): FontSpec {
  return {
    family: config.length.font.family,
    size: config.length.font.size,
    weight: config.length.font.weight,
    letterSpacing: config.length.font.letterSpacing,
  };
}

/**
 * Length validation + UI-fit estimation.
 * `length.tooLong` is an error when the budget is marked hard (e.g. CTA buttons),
 * otherwise a warning you can triage in the report.
 */
export function checkLength(
  key: string,
  locale: string,
  source: string,
  target: string,
  config: ResolvedConfig,
  entry?: CatalogEntry,
): LengthCheckResult {
  const font = fontSpecOf(config);
  const budget = resolveBudget(config, key, entry);
  const targetMetrics = measure(target, font);
  const sourceMetrics = measure(source, font);
  const ratio = sourceMetrics.px === 0 ? 1 : targetMetrics.px / sourceMetrics.px;
  const metrics: LengthMetrics = {
    chars: targetMetrics.chars,
    px: targetMetrics.px,
    sourceChars: sourceMetrics.chars,
    sourcePx: sourceMetrics.px,
    ratio: Number(ratio.toFixed(2)),
    containerPx: budget.containerPx,
    lines: estimateLines(target, font, budget.containerPx),
    budget,
    widget: budget.widget,
  };
  const issues: Issue[] = [];
  if (!config.length.enabled) return { issues, metrics };

  const limit = expansionLimit(config, locale);
  const severity = budget.hard ? 'error' : 'warn';

  const measureValue = budget.unit === 'px' ? metrics.px : metrics.chars;
  if (budget.max !== undefined && measureValue > budget.max) {
    issues.push({
      code: 'length.tooLong',
      severity,
      locale,
      key,
      message: `Too long for ${budget.widget}: ${Math.round(measureValue)} ${budget.unit} > ${budget.max} ${budget.unit}`,
      target,
      fixable: true,
    });
  }
  if (budget.min !== undefined && budget.min > 0 && measureValue < budget.min) {
    issues.push({
      code: 'length.tooShort',
      severity: 'info',
      locale,
      key,
      message: `Shorter than expected: ${Math.round(measureValue)} ${budget.unit} < ${budget.min} ${budget.unit}`,
      target,
      fixable: false,
    });
  }
  if (locale !== config.sourceLocale && metrics.sourcePx > 0 && ratio > limit) {
    issues.push({
      code: 'length.expansion',
      severity: 'info',
      locale,
      key,
      message: `Expands ${Math.round((ratio - 1) * 100)}% vs source (budget ${Math.round((limit - 1) * 100)}%)`,
      target,
      fixable: false,
    });
  }
  if (metrics.lines > 1 && metrics.sourceChars > 0 && metrics.chars > metrics.sourceChars) {
    issues.push({
      code: 'length.wrap',
      severity: 'info',
      locale,
      key,
      message: `Likely wraps to ${metrics.lines} lines in a ${budget.widget} (${budget.containerPx}px)`,
      target,
      fixable: false,
    });
  }
  return { issues, metrics };
}
