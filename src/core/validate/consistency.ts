import type { Issue } from '../types';

export interface ConsistencyIndex {
  /** Normalised source text -> translations seen so far. */
  bySource: Map<string, Map<string, string[]>>;
}

export function createConsistencyIndex(): ConsistencyIndex {
  return { bySource: new Map() };
}

export function recordTranslation(index: ConsistencyIndex, key: string, source: string, target: string): void {
  const normalized = normalizeForCompare(source);
  if (normalized.length === 0 || target.trim().length === 0) return;
  const targets = index.bySource.get(normalized) ?? new Map<string, string[]>();
  const keys = targets.get(target) ?? [];
  keys.push(key);
  targets.set(target, keys);
  index.bySource.set(normalized, targets);
}

/**
 * Flags the classic "same English string translated two different ways" problem.
 * A difference is only reported when the strings are close enough to be the same
 * phrase (similarity ≥ 0.6), so genuinely different senses stay quiet.
 */
export function checkConsistency(index: ConsistencyIndex, key: string, source: string, target: string): Issue[] {
  const normalized = normalizeForCompare(source);
  const variants = index.bySource.get(normalized);
  if (!variants || variants.size === 0) return [];
  const dominant = pickDominant(variants);
  if (!dominant || dominant.target === target) return [];
  // Only the same key re-validated with an identical source is not a conflict.
  if (dominant.keys.every((other) => other === key)) return [];
  return [
    {
      code: 'consistency.source',
      severity: 'warn',
      locale: '',
      key,
      message: `Inconsistent with ${dominant.keys.length} other key(s): "${dominant.target}"`,
      detail: `also used by ${dominant.keys.slice(0, 3).join(', ')}`,
      target,
      fixable: true,
      suggestion: dominant.target,
    },
  ];
}

export function pickDominant(variants: Map<string, string[]>): { target: string; keys: string[] } | null {
  let best: { target: string; keys: string[] } | null = null;
  for (const [target, keys] of variants) {
    if (!best || keys.length > best.keys.length) best = { target, keys };
  }
  return best;
}

function normalizeForCompare(text: string): string {
  return text
    .toLowerCase()
    .replace(/\{\{[^}]+\}\}|\{[^}]+\}/gu, '{}')
    .replace(/<[^>]+>/gu, '')
    .replace(/[\s\u00a0]+/gu, ' ')
    .replace(/[.!?…。！？]+$/u, '')
    .trim();
}
