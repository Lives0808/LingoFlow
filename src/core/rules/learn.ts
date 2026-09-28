import type { RuleRow } from '../types';
import { baseLang, detectCaseStyle, escapeRegExp, isCJKLocale, tokenizeWords, wordDiff } from '../utils/text';
import { STYLE_NAMES } from './model';

export interface LearnOptions {
  minOccurrences: number;
  minConsistency: number;
  sourceLocale: string;
  maxTerms?: number;
}

export interface LearnCounters {
  pairs: number;
  edits: number;
  entries: number;
}

export interface LearnResult {
  glossary: RuleRow[];
  corrections: RuleRow[];
  styles: RuleRow[];
  counters: LearnCounters;
}

const STOPWORDS = new Set([
  'the','a','an','and','or','of','to','in','on','at','by','for','with','from','is','are','was','were','be','been',
  'you','your','we','our','it','its','this','that','these','those','as','if','then','than','so','not','no','yes',
  'new','old','more','less','all','any','some','can','will','would','should','could','do','does','did','has','have',
  'de','la','el','los','las','un','una','y','o','en','con','por','para','der','die','das','und','oder','ein','eine',
  'le','les','des','du','et','ou','dans','pour','avec','sur','il','elle','zu','von','mit','nach','bei','aus','ich',
]);

const MIN_LATIN_TERM_LENGTH = 3;

/**
 * Learns glossary candidates from trusted translation pairs.
 * A term is proposed when the same source n-gram consistently maps to the same
 * target n-gram across several already-translated entries.
 */
export function learnGlossaryFromPairs(
  pairs: Array<{ source: string; target: string }>,
  targetLocale: string,
  options: LearnOptions,
): RuleRow[] {
  const candidates = new Set<string>();
  const sourceIsCJK = isCJKLocale(options.sourceLocale);
  const targetIsCJK = isCJKLocale(targetLocale);
  const useful = pairs.filter((pair) => pair.source.trim().length > 0 && pair.target.trim().length > 0);
  if (useful.length < options.minOccurrences) return [];

  for (const pair of useful) {
    const normalizedSource = sourceIsCJK ? pair.source : pair.source.toLowerCase();
    for (const ngram of ngramsOf(normalizedSource, sourceIsCJK ? [2, 3, 4] : [1, 2, 3])) {
      if (isStopwordCandidate(ngram, sourceIsCJK)) continue;
      candidates.add(ngram);
    }
  }

  const results: RuleRow[] = [];
  const maxPairsForTerm = Math.max(3, Math.floor(useful.length * 0.6));

  for (const candidate of candidates) {
    const containing = useful.filter((pair) => containsTerm(sourceIsCJK ? pair.source : pair.source.toLowerCase(), candidate, sourceIsCJK));
    if (containing.length < options.minOccurrences || containing.length > maxPairsForTerm) continue;
    const targetCounts = new Map<string, number>();
    for (const pair of containing) {
      const normalizedTarget = targetIsCJK ? pair.target : pair.target.toLowerCase();
      const seen = new Set<string>();
      for (const ngram of ngramsOf(normalizedTarget, targetIsCJK ? [2, 3, 4] : [1, 2, 3])) {
        if (seen.has(ngram)) continue;
        seen.add(ngram);
        targetCounts.set(ngram, (targetCounts.get(ngram) ?? 0) + 1);
      }
    }
    const ranked = [...targetCounts].sort((a, b) => b[1] - a[1] || b[0].length - a[0].length);
    const best = ranked[0];
    if (!best) continue;
    const [targetTerm, support] = best;
    const consistency = support / containing.length;
    if (consistency < options.minConsistency) continue;
    if (targetTerm.toLowerCase() === candidate.toLowerCase()) continue;
    if (targetTerm.trim().length === 0) continue;
    const confidence = Math.min(0.98, consistency * Math.min(1, 0.7 + support * 0.1));
    results.push({
      kind: 'glossary',
      locale: targetLocale,
      pattern: candidate,
      value: targetTerm,
      priority: 60,
      enabled: consistency >= 0.85 && support >= options.minOccurrences,
      origin: 'learned',
      confidence: Number(confidence.toFixed(3)),
      note: `observed ${support}/${containing.length} times in history`,
    });
  }

  return dedupeGlossary(results, options.maxTerms ?? 200);
}

/** Keeps the longest/highest confidence terms and drops overlapping shorter ones. */
function dedupeGlossary(rules: RuleRow[], maxTerms: number): RuleRow[] {
  const sorted = [...rules].sort((a, b) => b.confidence - a.confidence || b.pattern.length - a.pattern.length);
  const kept: RuleRow[] = [];
  for (const rule of sorted) {
    const conflict = kept.find(
      (other) =>
        other.locale === rule.locale &&
        (other.pattern.toLowerCase().includes(rule.pattern.toLowerCase()) || rule.pattern.toLowerCase().includes(other.pattern.toLowerCase())),
    );
    if (conflict) {
      if (conflict.value === rule.value) continue;
      // Same span, different target: keep the more confident one only.
      if (conflict.pattern.length >= rule.pattern.length) continue;
    }
    kept.push(rule);
    if (kept.length >= maxTerms) break;
  }
  return kept;
}

/**
 * Learns correction rules from human edits. This is the "防返工" core: whenever a
 * reviewer fixes a machine translation, the fix becomes a reusable rule.
 */
export function learnCorrectionsFromEdits(
  edits: Array<{ machine: string; human: string; source: string; locale: string }>,
  options: { minOccurrences: number },
): RuleRow[] {
  const counts = new Map<string, { from: string; to: string; locale: string; count: number; similarity: number }>();
  for (const edit of edits) {
    const { changes, similarity } = wordDiff(edit.machine, edit.human);
    for (const change of changes) {
      const from = change.from.trim();
      const to = change.to.trim();
      if (from.length === 0 || to.length === 0) continue;
      if (from.length > 60 || to.length > 60) continue;
      if (/\{[^}]*\}/u.test(from) || /\{[^}]*\}/u.test(to)) continue;
      if (!/\p{Letter}/u.test(from) || !/\p{Letter}/u.test(to)) continue;
      const id = `${edit.locale}\u0000${from}\u0000${to}`;
      const entry = counts.get(id) ?? { from, to, locale: edit.locale, count: 0, similarity };
      entry.count += 1;
      counts.set(id, entry);
    }
  }

  const rules: RuleRow[] = [];
  for (const entry of counts.values()) {
    const cjk = isCJKLocale(entry.locale);
    const pattern = cjk
      ? escapeRegExp(entry.from)
      : `(?<![\\p{Letter}\\p{Number}])${escapeRegExp(entry.from)}(?![\\p{Letter}\\p{Number}])`;
    const enabled = entry.count >= options.minOccurrences;
    rules.push({
      kind: 'correction',
      locale: entry.locale,
      pattern,
      value: JSON.stringify({ to: entry.to, regex: true }),
      priority: 70,
      enabled,
      origin: 'learned',
      confidence: enabled ? 0.9 : 0.5,
      note: enabled
        ? `reviewer applied this ${entry.count} times`
        : `seen once — enable after a second review (${entry.count})`,
    });
  }
  return rules.sort((a, b) => b.confidence - a.confidence);
}

/**
 * Detects the house style of already-approved translations, so new strings can
 * be normalised to match without human review.
 */
export function learnStylesFromEntries(entries: Array<{ value: string; source: string; locale: string }>): RuleRow[] {
  const rules: RuleRow[] = [];
  const locales = [...new Set(entries.map((entry) => entry.locale))];
  for (const locale of locales) {
    const samples = entries.filter((entry) => entry.locale === locale && entry.value.trim().length > 0);
    if (samples.length < 8) continue;

    // Capitalisation
    const caseCounts = new Map<string, number>();
    for (const sample of samples) {
      if (tokenizeWords(sample.value).length < 2) continue;
      const style = detectCaseStyle(sample.value);
      caseCounts.set(style, (caseCounts.get(style) ?? 0) + 1);
    }
    const dominantCase = [...caseCounts].sort((a, b) => b[1] - a[1])[0];
    const caseTotal = [...caseCounts.values()].reduce((sum, value) => sum + value, 0);
    if (dominantCase && caseTotal >= 8 && dominantCase[1] / caseTotal >= 0.85 && dominantCase[0] !== 'unknown') {
      rules.push(styleRule(locale, STYLE_NAMES.capitalization, dominantCase[0], dominantCase[1] / caseTotal));
    }

    // Trailing punctuation on short UI strings
    const short = samples.filter((sample) => sample.value.length <= 40 && tokenizeWords(sample.value).length <= 6);
    if (short.length >= 8) {
      const withPeriod = short.filter((sample) => /[.!?。！？]$/u.test(sample.value.trim())).length;
      if (withPeriod / short.length <= 0.1) {
        rules.push(styleRule(locale, STYLE_NAMES.trailingPunctuation, 'never', 1 - withPeriod / short.length));
      } else if (withPeriod / short.length >= 0.8) {
        rules.push(styleRule(locale, STYLE_NAMES.trailingPunctuation, 'always', withPeriod / short.length));
      }
    }

    // Ellipsis convention
    const unicodeEllipsis = samples.filter((sample) => sample.value.includes('…')).length;
    const asciiEllipsis = samples.filter((sample) => sample.value.includes('...')).length;
    if (unicodeEllipsis + asciiEllipsis >= 3) {
      if (unicodeEllipsis >= asciiEllipsis * 2) rules.push(styleRule(locale, STYLE_NAMES.ellipsis, 'unicode', 0.9));
      else if (asciiEllipsis > unicodeEllipsis) rules.push(styleRule(locale, STYLE_NAMES.ellipsis, 'ascii', 0.9));
    }

    // CJK typography
    if (isCJKLocale(locale)) {
      const fullwidth = samples.filter((sample) => /[，。！？：；]/u.test(sample.value)).length;
      const halfwidth = samples.filter((sample) => /[,.!?]{1,}/u.test(sample.value)).length;
      if (fullwidth >= 3 && fullwidth >= halfwidth * 2) rules.push(styleRule(locale, STYLE_NAMES.cjkFullwidth, 'true', 0.9));
      const spaced = samples.filter((sample) => /[\p{Script=Han}]\s[\p{Letter}\p{Number}]|[\p{Letter}\p{Number}]\s[\p{Script=Han}]/u.test(sample.value)).length;
      if (spaced / samples.length >= 0.6) rules.push(styleRule(locale, STYLE_NAMES.cjkLatinSpace, 'true', spaced / samples.length));
      else if (spaced / samples.length <= 0.1) rules.push(styleRule(locale, STYLE_NAMES.cjkLatinSpace, 'false', 0.9));
    }

    // zh formality (您/你)
    if (baseLang(locale) === 'zh') {
      const formal = samples.filter((sample) => sample.value.includes('您')).length;
      const informal = samples.filter((sample) => sample.value.includes('你')).length;
      if (formal + informal >= 5) {
        if (formal >= informal * 2) rules.push(styleRule(locale, STYLE_NAMES.formality, 'formal', 0.9));
        else if (informal >= formal * 2) rules.push(styleRule(locale, STYLE_NAMES.formality, 'informal', 0.9));
      }
    }
  }
  return rules;
}

function styleRule(locale: string, name: string, value: string, confidence: number): RuleRow {
  return {
    kind: 'style',
    locale,
    pattern: name,
    value,
    priority: 80,
    enabled: true,
    origin: 'learned',
    confidence: Number(confidence.toFixed(3)),
    note: 'derived from approved translations',
  };
}

function ngramsOf(text: string, sizes: number[]): string[] {
  const result: string[] = [];
  if (/[\p{Script=Han}\p{Script=Kana}\p{Script=Hangul}]/u.test(text) && !/\s/u.test(text.trim())) {
    for (const size of sizes) {
      for (let i = 0; i + size <= text.length; i += 1) {
        result.push(text.slice(i, i + size));
      }
    }
    return result;
  }
  const tokens = tokenizeWords(text);
  for (const size of sizes) {
    for (let i = 0; i + size <= tokens.length; i += 1) {
      result.push(tokens.slice(i, i + size).join(' '));
    }
  }
  return result;
}

function containsTerm(text: string, term: string, cjk: boolean): boolean {
  if (cjk) return text.includes(term);
  if (/\s/u.test(term)) return text.includes(term);
  return ` ${text} `.includes(` ${term} `);
}

function isStopwordCandidate(ngram: string, cjk: boolean): boolean {
  if (cjk) return ngram.trim().length < 2;
  const trimmed = ngram.trim();
  if (trimmed.length < MIN_LATIN_TERM_LENGTH) return true;
  if (STOPWORDS.has(trimmed)) return true;
  if (/^\d+$/u.test(trimmed)) return true;
  return false;
}
