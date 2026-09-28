import type { CatalogEntry, Issue, IssueCode, Severity } from '../types';
import type { ResolvedConfig } from '../config/schema';
import type { RuleSet } from '../rules/model';
import { STYLE_NAMES, styleValue } from '../rules/model';
import { dntIssues, glossaryIssues } from '../rules/apply';
import { keyMatches } from '../catalog/keys';
import { normalizeNewlines } from '../utils/text';
import { checkCjk } from './cjk';
import { checkConsistency, createConsistencyIndex, recordTranslation, type ConsistencyIndex } from './consistency';
import { parseIcu, hasIcuSyntax } from './icu';
import { checkLength, type LengthMetrics } from './length';
import { comparePlaceholders } from './placeholders';
import { pluralCategoriesFor } from './plural';
import { compareTags } from './tags';
import { checkText } from './text';

const DEFAULT_SEVERITY: Record<string, Severity> = {
  missing: 'error',
  empty: 'error',
  untranslated: 'warn',
  'source.changed': 'warn',
  stale: 'warn',
  'placeholder.missing': 'error',
  'placeholder.extra': 'warn',
  'placeholder.mismatch': 'warn',
  'icu.invalid': 'error',
  'icu.category': 'warn',
  'tag.unbalanced': 'error',
  'tag.mismatch': 'warn',
  'length.tooLong': 'warn',
  'length.tooShort': 'info',
  'length.expansion': 'info',
  'length.wrap': 'info',
  'text.leadingSpace': 'warn',
  'text.trailingSpace': 'warn',
  'text.doubleSpace': 'info',
  'text.punctuation': 'info',
  'text.ellipsis': 'info',
  'text.newline': 'warn',
  'cjk.punctuation': 'info',
  'cjk.space': 'info',
  'script.mismatch': 'warn',
  'dnt.violated': 'warn',
  'glossary.missed': 'warn',
  'glossary.drift': 'warn',
  'consistency.source': 'warn',
  'forbidden.term': 'warn',
  'style.case': 'info',
  'frozen.protected': 'warn',
  'auto.fixed': 'info',
  'key.unused': 'info',
  'key.undefined': 'error',
};

export interface ValidationContext {
  config: ResolvedConfig;
  sourceLocale: string;
  ruleSet: RuleSet;
  sources: Map<string, CatalogEntry>;
  consistency: Map<string, ConsistencyIndex>;
}

export function createValidationContext(input: {
  config: ResolvedConfig;
  ruleSet: RuleSet;
  sources: Map<string, CatalogEntry>;
}): ValidationContext {
  return {
    config: input.config,
    sourceLocale: input.config.sourceLocale,
    ruleSet: input.ruleSet,
    sources: input.sources,
    consistency: new Map(),
  };
}

export interface EntryInput {
  key: string;
  locale: string;
  value: string | null;
  entry?: CatalogEntry;
  /** Source text; defaults to the source catalog value. */
  source?: string;
}

export interface EntryValidation {
  issues: Issue[];
  metrics?: LengthMetrics;
}

/** Full validation for one translated entry. */
export function validateEntry(ctx: ValidationContext, input: EntryInput): EntryValidation {
  const config = ctx.config;
  const sourceEntry = ctx.sources.get(input.key);
  const source = input.source ?? sourceEntry?.value ?? '';
  const target = input.value ?? '';
  const issues: Issue[] = [];

  if (input.value === null) {
    return {
      issues: [
        {
          code: 'missing',
          severity: 'error',
          locale: input.locale,
          key: input.key,
          message: 'Key is missing in this locale',
          source,
          fixable: false,
        },
      ],
    };
  }
  if (target.trim().length === 0) {
    return {
      issues: [
        { code: 'empty', severity: 'error', locale: input.locale, key: input.key, message: 'Translation is empty', source },
      ],
    };
  }

  if (config.validate.placeholders) {
    const comparison = comparePlaceholders(source, target);
    for (const placeholder of comparison.missing) {
      issues.push({
        code: 'placeholder.missing',
        severity: 'error',
        locale: input.locale,
        key: input.key,
        message: `Missing placeholder ${placeholder}`,
        source,
        target,
        fixable: true,
      });
    }
    for (const placeholder of comparison.extra) {
      issues.push({
        code: 'placeholder.extra',
        severity: 'warn',
        locale: input.locale,
        key: input.key,
        message: `Unexpected placeholder ${placeholder}`,
        source,
        target,
        fixable: true,
      });
    }
  }

  if (config.validate.icu) {
    const sourceIcu = hasIcuSyntax(source);
    const targetIcu = hasIcuSyntax(target);
    if (targetIcu) {
      const parsed = parseIcu(target, input.locale);
      for (const issue of parsed.issues) {
        issues.push({
          code: 'icu.invalid',
          severity: 'error',
          locale: input.locale,
          key: input.key,
          message: `ICU: ${issue.message}`,
          detail: issue.detail,
          target,
        });
      }
      if (sourceIcu) {
        const sourceParsed = parseIcu(source, ctx.sourceLocale);
        const sourceArgs = new Map(sourceParsed.arguments.map((argument) => [argument.name, argument]));
        for (const argument of parsed.arguments) {
          const sourceArgument = sourceArgs.get(argument.name);
          if (!sourceArgument) {
            issues.push({
              code: 'placeholder.extra',
              severity: 'warn',
              locale: input.locale,
              key: input.key,
              message: `ICU argument {${argument.name}} is not in the source`,
              target,
            });
            continue;
          }
          if ((argument.type === 'plural' || argument.type === 'selectordinal') && sourceArgument.categories) {
            const allowed = pluralCategoriesFor(input.locale);
            const expected = sourceArgument.categories.filter((category) => !category.startsWith('='));
            for (const category of expected) {
              if (allowed.includes(category) && !(argument.categories ?? []).includes(category) && !(argument.categories ?? []).includes('other')) {
                issues.push({
                  code: 'icu.category',
                  severity: 'warn',
                  locale: input.locale,
                  key: input.key,
                  message: `Plural form "${category}" missing for {${argument.name}}`,
                  target,
                });
              }
            }
          }
        }
      }
    } else if (sourceIcu) {
      issues.push({
        code: 'icu.invalid',
        severity: 'error',
        locale: input.locale,
        key: input.key,
        message: 'Source uses ICU syntax but the translation does not',
        source,
        target,
        fixable: true,
      });
    }
  }

  if (config.validate.tags) {
    const comparison = compareTags(source, target);
    if (comparison.unbalanced) {
      issues.push({
        code: 'tag.unbalanced',
        severity: 'error',
        locale: input.locale,
        key: input.key,
        message: 'HTML/XML tags are unbalanced',
        target,
      });
    }
    for (const name of comparison.missing) {
      issues.push({
        code: 'tag.mismatch',
        severity: 'warn',
        locale: input.locale,
        key: input.key,
        message: `Tag <${name}> is missing in the translation`,
        target,
      });
    }
    for (const name of comparison.extra) {
      issues.push({
        code: 'tag.mismatch',
        severity: 'warn',
        locale: input.locale,
        key: input.key,
        message: `Tag <${name}> was added by the translation`,
        target,
      });
    }
    for (const message of comparison.attributeIssues) {
      issues.push({ code: 'tag.mismatch', severity: 'warn', locale: input.locale, key: input.key, message, target });
    }
  }

  issues.push(
    ...checkText(input.key, input.locale, source, target, {
      locale: input.locale,
      sourceLocale: ctx.sourceLocale,
      whitespace: config.validate.whitespace,
      punctuation: config.validate.punctuation,
      ellipsis: config.validate.ellipsis,
      script: config.validate.script,
      forbidden: config.validate.forbidden,
    }),
  );

  if (config.validate.cjk) {
    const fullwidthSetting = styleValue(ctx.ruleSet, input.locale, STYLE_NAMES.cjkFullwidth);
    const latinSpaceSetting = styleValue(ctx.ruleSet, input.locale, STYLE_NAMES.cjkLatinSpace);
    issues.push(
      ...checkCjk(input.key, source, target, {
        locale: input.locale,
        sourceLocale: ctx.sourceLocale,
        fullwidth: fullwidthSetting === null ? true : fullwidthSetting === 'true',
        latinSpace: latinSpaceSetting === null ? null : latinSpaceSetting === 'true',
      }),
    );
  }

  const lengthResult = checkLength(input.key, input.locale, source, target, config, input.entry ?? sourceEntry);
  issues.push(...lengthResult.issues);

  if (config.validate.dnt) {
    issues.push(...dntIssues(input.key, input.locale, source, target, ctx.ruleSet, true));
  }
  if (config.validate.glossary) {
    issues.push(
      ...glossaryIssues(input.key, input.locale, source, target, ctx.ruleSet, {
        enabled: config.translation.glossary.enforce,
        mode: config.translation.glossary.mode,
      }),
    );
  }

  if (config.validate.consistency && config.validate.sameSourceSameTarget && input.locale !== ctx.sourceLocale) {
    const index = ctx.consistency.get(input.locale) ?? createConsistencyIndex();
    ctx.consistency.set(input.locale, index);
    const consistencyIssues = checkConsistency(index, input.key, source, target).map((issue) => ({ ...issue, locale: input.locale }));
    issues.push(...consistencyIssues);
    recordTranslation(index, input.key, source, normalizeNewlines(target));
  }

  return { issues: finalizeIssues(ctx, issues), metrics: lengthResult.metrics };
}

/** Applies configured severity overrides, ignore patterns and ordering. */
export function finalizeIssues(ctx: ValidationContext, issues: Issue[]): Issue[] {
  const overrides = ctx.config.validate.severity as Record<string, Severity>;
  const ignore = ctx.config.validate.ignore;
  const filtered: Issue[] = [];
  const seen = new Set<string>();
  for (const issue of issues) {
    const localeIgnore = ignore[issue.locale] ?? ignore['*'] ?? [];
    if (localeIgnore.some((pattern) => keyMatches(pattern, issue.key))) continue;
    const severity = overrides[issue.code] ?? issue.severity ?? DEFAULT_SEVERITY[issue.code] ?? 'warn';
    const enriched: Issue = { ...issue, severity };
    const dedupeKey = `${enriched.code}\u0000${enriched.locale}\u0000${enriched.key}\u0000${enriched.message}`;
    if (seen.has(dedupeKey)) continue;
    seen.add(dedupeKey);
    filtered.push(enriched);
  }
  const rank: Record<Severity, number> = { error: 0, warn: 1, info: 2 };
  return filtered.sort((a, b) => rank[a.severity] - rank[b.severity]);
}

export function summarizeIssues(issues: Issue[]): { errors: number; warnings: number; infos: number; total: number } {
  let errors = 0;
  let warnings = 0;
  let infos = 0;
  for (const issue of issues) {
    if (issue.severity === 'error') errors += 1;
    else if (issue.severity === 'warn') warnings += 1;
    else infos += 1;
  }
  return { errors, warnings, infos, total: issues.length };
}

export function issuesByCode(issues: Issue[]): Array<{ code: IssueCode; count: number }> {
  const counts = new Map<IssueCode, number>();
  for (const issue of issues) counts.set(issue.code, (counts.get(issue.code) ?? 0) + 1);
  return [...counts].map(([code, count]) => ({ code, count })).sort((a, b) => b.count - a.count);
}

export { pluralCategoriesFor };
