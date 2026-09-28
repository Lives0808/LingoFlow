import { isCJKLocale, normalizeNewlines, unwrapLines } from '../utils/text';
import { withMaskedSyntax } from '../utils/syntax';
import { STYLE_NAMES, styleValue, type RuleSet } from '../rules/model';

export interface TextStyleOptions {
  locale: string;
  ruleSet: RuleSet;
  sourceHasNewline: boolean;
}

/** Normalises line breaks to match the source, without touching meaningful wraps. */
export function reflow(text: string, options: TextStyleOptions): { text: string; fixed: boolean } {
  const normalized = normalizeNewlines(text);
  const targetLines = normalized.split('\n');
  if (targetLines.length === 1) return { text: normalized, fixed: false };
  const sourceLines = options.sourceHasNewline ? Number.POSITIVE_INFINITY : 1;
  if (Number.isFinite(sourceLines) && sourceLines === 1) {
    // Source is single-line: the engine inserted breaks, remove them.
    return { text: unwrapLines(normalized), fixed: true };
  }
  // Trim trailing spaces per line and collapse empty lines.
  const cleaned = targetLines
    .map((line) => line.replace(/[ \t]+$/u, ''))
    .filter((line, index, all) => !(line.length === 0 && index > 0 && all[index - 1]?.length === 0))
    .join('\n');
  return { text: cleaned, fixed: cleaned !== normalized };
}

/** Applies house-style punctuation and casing rules. */
export function applyPunctuationStyle(text: string, options: TextStyleOptions): { text: string; fixed: string[] } {
  const fixes: string[] = [];
  const output = withMaskedSyntax(text, (value) => applyPunctuationStyleInner(value, options, fixes));
  return { text: output, fixed: fixes };
}

function applyPunctuationStyleInner(text: string, options: TextStyleOptions, fixes: string[]): string {
  let output = text;
  const locale = options.locale;

  const trailing = styleValue(options.ruleSet, locale, STYLE_NAMES.trailingPunctuation);
  // Question and exclamation marks are content, not style: never strip them.
  if (trailing === 'never' && /[.。]\s*$/u.test(output) && output.trim().length > 3) {
    const stripped = output.replace(/[.]\s*$/u, '').replace(/[。]\s*$/u, '');
    if (stripped.trim().length > 0) {
      output = stripped;
      fixes.push('removed trailing period');
    }
  } else if (trailing === 'always' && !/[.!?…。！？]\s*$/u.test(output.trim()) && output.trim().length > 0) {
    output = `${output.trimEnd()}${isCJKLocale(locale) ? '。' : '.'}`;
    fixes.push('added trailing punctuation');
  }

  const ellipsis = styleValue(options.ruleSet, locale, STYLE_NAMES.ellipsis);
  if (ellipsis === 'unicode' && output.includes('...')) {
    output = output.replace(/\.{3,}/gu, '…');
    fixes.push('converted ... to …');
  } else if (ellipsis === 'ascii' && output.includes('…')) {
    output = output.replace(/…/gu, '...');
    fixes.push('converted … to ...');
  }

  const capitalization = styleValue(options.ruleSet, locale, STYLE_NAMES.capitalization);
  if (capitalization === 'lower' && /^[\p{Lu}]/u.test(output)) {
    output = output.charAt(0).toLocaleLowerCase() + output.slice(1);
    fixes.push('lowercased first letter');
  }

  const formality = styleValue(options.ruleSet, locale, STYLE_NAMES.formality);
  if (formality === 'formal' && locale.toLowerCase().startsWith('zh') && output.includes('你')) {
    output = output.replace(/你们的/gu, '您的').replace(/你/gu, '您');
    fixes.push('switched to formal address (您)');
  } else if (formality === 'informal' && locale.toLowerCase().startsWith('zh') && output.includes('您')) {
    output = output.replace(/您/gu, '你');
    fixes.push('switched to informal address (你)');
  }

  return output;
}

/** CJK typography fixes: fullwidth punctuation and Han/Latin spacing. */
export function applyCjkStyle(text: string, options: TextStyleOptions): { text: string; fixed: string[] } {
  const fixes: string[] = [];
  if (!isCJKLocale(options.locale)) return { text, fixed: fixes };
  const output = withMaskedSyntax(text, (value) => applyCjkStyleInner(value, options, fixes));
  return { text: output, fixed: fixes };
}

function applyCjkStyleInner(text: string, options: TextStyleOptions, fixes: string[]): string {
  let output = text;

  const fullwidth = styleValue(options.ruleSet, options.locale, STYLE_NAMES.cjkFullwidth);
  if (fullwidth !== 'false') {
    const map: Record<string, string> = { ',': '，', '.': '。', '!': '！', '?': '？', ';': '；', ':': '：', '(': '（', ')': '）' };
    const replaced = output.replace(/(?<=[\p{Script=Han}\p{Script=Kana}])[,.!?;:](?=[\p{Script=Han}\p{Script=Kana}\s]|$)/gu, (match) => map[match] ?? match);
    if (replaced !== output) {
      output = replaced;
      fixes.push('converted to fullwidth punctuation');
    }
  }
  // Remove spaces that CJK typography forbids.
  const tightened = output
    .replace(/(?<=[\p{Script=Han}\p{Script=Kana}])\s+(?=[\p{Script=Han}\p{Script=Kana}])/gu, '')
    .replace(/\s+(?=[，。！？；：、）】》」』])/gu, '')
    .replace(/(?<=[（【《「『])\s+/gu, '');
  if (tightened !== output) {
    output = tightened;
    fixes.push('removed invalid CJK spacing');
  }
  return output;
}

/**
 * Restores placeholders that an engine dropped.
 * The placeholder is inserted before trailing punctuation so the sentence keeps
 * its shape ("删除？" + {name} -> "删除 {name}？").
 */
export function repairPlaceholders(source: string, target: string, missing: string[]): { text: string; fixed: boolean } {
  if (missing.length === 0) return { text: target, fixed: false };
  const insertion = missing.join(' ');
  const trailing = /[.!?…。！？；;:：]+\s*$/u.exec(target);
  if (trailing && trailing.index > 0) {
    const head = target.slice(0, trailing.index).trimEnd();
    const joiner = /[\p{Script=Han}\p{Script=Kana}\p{Script=Hangul}]$/u.test(head) ? ' ' : ' ';
    return { text: `${head}${joiner}${insertion}${trailing[0]}`, fixed: true };
  }
  const trimmed = target.trimEnd();
  return { text: `${trimmed}${trimmed.length > 0 ? ' ' : ''}${insertion}`, fixed: true };
}
