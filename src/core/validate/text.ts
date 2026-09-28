import type { Issue } from '../types';
import { baseLang, charScript, countChars, lineCount, normalizeNewlines, scriptsIn } from '../utils/text';

export interface TextCheckOptions {
  locale: string;
  sourceLocale: string;
  whitespace: boolean;
  punctuation: boolean;
  ellipsis: boolean;
  script: boolean;
  forbidden: string[];
}

export function checkText(key: string, locale: string, source: string, target: string, options: TextCheckOptions): Issue[] {
  const issues: Issue[] = [];
  const push = (issue: Omit<Issue, 'key' | 'locale'>): void => {
    issues.push({ key, locale, ...issue });
  };

  if (target.length === 0) {
    push({ code: 'empty', severity: 'error', message: 'Translation is empty', fixable: false });
    return issues;
  }

  const isSource = locale === options.sourceLocale;

  if (options.whitespace) {
    if (/^\s/u.test(target) !== /^\s/u.test(source)) {
      push({
        code: 'text.leadingSpace',
        severity: 'warn',
        message: /^\s/u.test(target) ? 'Unexpected leading whitespace' : 'Missing leading whitespace',
        target,
        fixable: true,
      });
    }
    if (/\s$/u.test(target) !== /\s$/u.test(source)) {
      push({
        code: 'text.trailingSpace',
        severity: 'warn',
        message: /\s$/u.test(target) ? 'Unexpected trailing whitespace' : 'Missing trailing whitespace',
        target,
        fixable: true,
      });
    }
    if (/[ \t]{2,}/u.test(normalizeNewlines(target))) {
      push({ code: 'text.doubleSpace', severity: 'info', message: 'Double space inside the string', target, fixable: true });
    }
    const sourceLines = lineCount(source);
    const targetLines = lineCount(target);
    if (sourceLines > 1 && targetLines !== sourceLines) {
      push({
        code: 'text.newline',
        severity: 'warn',
        message: `Line breaks changed (${sourceLines} → ${targetLines})`,
        target,
        fixable: true,
      });
    }
  }

  if (options.punctuation && !isSource) {
    const sourceEnds = /[.!?…。！？]$/u.test(source.trim());
    const targetEnds = /[.!?…。！？]$/u.test(target.trim());
    if (sourceEnds !== targetEnds) {
      push({
        code: 'text.punctuation',
        severity: 'info',
        message: sourceEnds ? 'Source ends with punctuation, target does not' : 'Target added trailing punctuation',
        target,
        fixable: true,
      });
    }
  }

  if (options.ellipsis && !isSource) {
    if (source.includes('...') && !target.includes('...') && !target.includes('…')) {
      push({ code: 'text.ellipsis', severity: 'info', message: 'Source ellipsis (...) not represented in target', target, fixable: true });
    }
  }

  if (options.script && !isSource) {
    const targetScripts = scriptsIn(target).filter((script) => script !== 'latin');
    const expected = expectedScripts(options.locale);
    if (expected.length > 0 && targetScripts.length > 0) {
      const matches = targetScripts.some((script) => expected.includes(script));
      if (!matches) {
        push({
          code: 'script.mismatch',
          severity: 'warn',
          message: `Target contains ${targetScripts.join(', ')} but the locale expects ${expected.join(', ')}`,
          target,
        });
      }
    }
    if (expected.length === 0 && targetScripts.length > 0) {
      const sourceScripts = scriptsIn(source);
      const onlySourceScripts = targetScripts.every((script) => sourceScripts.includes(script));
      if (onlySourceScripts) {
        push({
          code: 'script.mismatch',
          severity: 'warn',
          message: `Text looks untranslated (${targetScripts.join(', ')} characters kept)`,
          target,
        });
      }
    }
  }

  for (const term of options.forbidden) {
    if (term.length === 0) continue;
    try {
      if (new RegExp(term, 'u').test(target)) {
        push({ code: 'forbidden.term', severity: 'warn', message: `Forbidden term matched: ${term}`, target });
      }
    } catch {
      if (target.includes(term)) {
        push({ code: 'forbidden.term', severity: 'warn', message: `Forbidden term present: ${term}`, target });
      }
    }
  }

  if (!isSource && target === source && countChars(source) > 2) {
    push({
      code: 'untranslated',
      severity: 'warn',
      message: 'Target is identical to the source',
      target,
      fixable: false,
    });
  }

  return issues;
}

function expectedScripts(locale: string): ReturnType<typeof scriptsIn> {
  const base = baseLang(locale);
  switch (base) {
    case 'zh':
      return ['han'];
    case 'ja':
      return ['kana', 'han'];
    case 'ko':
      return ['hangul', 'han'];
    case 'ru':
    case 'uk':
    case 'bg':
    case 'sr':
      return ['cyrillic'];
    case 'ar':
    case 'fa':
    case 'ur':
      return ['arabic'];
    case 'he':
      return ['hebrew'];
    case 'th':
      return ['thai'];
    case 'hi':
    case 'bn':
      return ['devanagari'];
    default:
      return [];
  }
}

export function isHanChar(char: string): boolean {
  return charScript(char.codePointAt(0) ?? 0) === 'han';
}
