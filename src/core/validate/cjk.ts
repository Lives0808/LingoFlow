import type { Issue } from '../types';
import { isCJKLocale } from '../utils/text';
import { withMaskedSyntax } from '../utils/syntax';

const HAN_OR_KANA = '\\p{Script=Han}\\p{Script=Kana}';
const HALFWIDTH_PUNCT = /[,.!?;:]/u;
const FULLWIDTH_PUNCT = /[，。！？；：]/u;

export interface CjkCheckOptions {
  locale: string;
  sourceLocale: string;
  /** Target text should use fullwidth punctuation. */
  fullwidth: boolean;
  /** Whether a space between Han and Latin is desired. */
  latinSpace: boolean | null;
}

/**
 * CJK typography checks: fullwidth punctuation, forbidden spaces, and
 * Han↔Latin spacing (a detail that makes Chinese/Japanese UI look native).
 */
export function checkCjk(key: string, source: string, target: string, options: CjkCheckOptions): Issue[] {
  const issues: Issue[] = [];
  if (!isCJKLocale(options.locale) || target.trim().length === 0) return issues;
  const push = (issue: Omit<Issue, 'key' | 'locale'>): void => {
    issues.push({ key, locale: options.locale, ...issue });
  };
  // Punctuation inside `{count, plural, …}` or `<a href="…">` is not typography.
  withMaskedSyntax(target, (maskedTarget) => {
    checkCjkInner(maskedTarget, target, source, options, push);
    return maskedTarget;
  });
  return issues;
}

function checkCjkInner(
  target: string,
  displayTarget: string,
  source: string,
  options: CjkCheckOptions,
  push: (issue: Omit<Issue, 'key' | 'locale'>) => void,
): void {
  if (options.fullwidth) {
    // A halfwidth comma/period surrounded by CJK is a typography bug.
    const halfwidthBetweenCjk = new RegExp(`(?<=[${HAN_OR_KANA}])[,.!?;:](?=[${HAN_OR_KANA}\\s]|$)`, 'u');
    const match = halfwidthBetweenCjk.exec(target);
    if (match) {
      push({
        code: 'cjk.punctuation',
        severity: 'info',
        message: `Halfwidth "${match[0]}" between CJK characters — use fullwidth punctuation`,
        target: displayTarget,
        fixable: true,
      });
    }
  }

  // Spaces directly before CJK punctuation are always wrong.
  if (new RegExp(`\\s+[，。！？；：、）】》」』]`, 'u').test(target)) {
    push({
      code: 'cjk.space',
      severity: 'warn',
      message: 'Space before CJK punctuation',
      target: displayTarget,
      fixable: true,
    });
  }
  if (new RegExp(`[（【《「『]\\s+`, 'u').test(target)) {
    push({ code: 'cjk.space', severity: 'warn', message: 'Space after an opening CJK bracket', target: displayTarget, fixable: true });
  }

  // Space between Han characters (a common MT artefact).
  const hanSpace = new RegExp(`(?<=[${HAN_OR_KANA}])\\s+(?=[${HAN_OR_KANA}])`, 'u').exec(target);
  if (hanSpace) {
    push({
      code: 'cjk.space',
      severity: 'warn',
      message: 'Space between CJK characters',
      target: displayTarget,
      fixable: true,
    });
  }

  if (options.latinSpace === true) {
    const missing = new RegExp(`(?<=[${HAN_OR_KANA}])(?=[A-Za-z0-9])|(?<=[A-Za-z0-9])(?=[${HAN_OR_KANA}])`, 'u').test(target);
    if (missing) {
      push({ code: 'cjk.space', severity: 'info', message: 'Missing space between CJK and Latin text', target: displayTarget, fixable: true });
    }
  } else if (options.latinSpace === false) {
    const extra = new RegExp(`(?<=[${HAN_OR_KANA}])\\s+(?=[A-Za-z0-9])|(?<=[A-Za-z0-9])\\s+(?=[${HAN_OR_KANA}])`, 'u').test(target);
    if (extra) {
      push({ code: 'cjk.space', severity: 'info', message: 'Unexpected space between CJK and Latin text', target: displayTarget, fixable: true });
    }
  }

  if (HALFWIDTH_PUNCT.test(target) && FULLWIDTH_PUNCT.test(target)) {
    push({
      code: 'cjk.punctuation',
      severity: 'info',
      message: 'Mixed halfwidth and fullwidth punctuation',
      target: displayTarget,
      fixable: true,
    });
  }

  void source;
}
