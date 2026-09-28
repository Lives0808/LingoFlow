import type { HardcodedCandidate } from '../types';
import { LingoFlowError } from '../errors';

export interface Rewrite {
  start: number;
  end: number;
  replacement: string;
}

export interface RewriteResult {
  content: string;
  applied: number;
}

/** Renders the replacement expression for a hardcoded string. */
export function renderReplacement(
  candidate: Pick<HardcodedCandidate, 'kind' | 'key'>,
  options: { callTemplate: string; jsxBraces: boolean },
): string {
  const call = options.callTemplate.replace(/\{key\}/gu, escapeForCall(candidate.key));
  if (candidate.kind === 'jsx-text') {
    return options.jsxBraces ? `{${call}}` : call;
  }
  if (candidate.kind === 'attribute') {
    return options.jsxBraces ? `{${call}}` : call;
  }
  return call;
}

function escapeForCall(key: string): string {
  return key.replace(/\\/gu, '\\\\').replace(/'/gu, "\\'");
}

/**
 * Applies rewrites from the end of the file backwards so offsets stay valid.
 * Refuses overlapping ranges instead of producing broken code.
 */
export function applyRewrites(source: string, rewrites: Rewrite[]): RewriteResult {
  const sorted = [...rewrites].sort((a, b) => b.start - a.start);
  let content = source;
  let applied = 0;
  let lastStart = source.length + 1;
  for (const rewrite of sorted) {
    if (rewrite.end > lastStart) {
      continue; // overlapping (nested JSX text); skip the outer rewrite
    }
    if (rewrite.start < 0 || rewrite.end > source.length) continue;
    content = `${content.slice(0, rewrite.start)}${rewrite.replacement}${content.slice(rewrite.end)}`;
    lastStart = rewrite.start;
    applied += 1;
  }
  return { content, applied };
}

/** Inserts an import statement once, after the leading comment block. */
export function ensureImport(source: string, statement: string): string {
  const needle = statement.trim();
  if (needle.length === 0) return source;
  if (source.includes(needle)) return source;
  const lines = source.split('\n');
  let insertAt = 0;
  // Keep shebang and the file header comment where they are.
  if (lines[0]?.startsWith('#!')) insertAt = 1;
  while (insertAt < lines.length) {
    const line = lines[insertAt]?.trim() ?? '';
    if (line.startsWith('//') || line.startsWith('/*') || line.startsWith('*') || line.endsWith('*/') || line.length === 0) {
      insertAt += 1;
      continue;
    }
    break;
  }
  // Prefer inserting after the last existing import so ordering stays stable.
  let lastImport = -1;
  for (let i = 0; i < lines.length; i += 1) {
    if (/^\s*import\s/u.test(lines[i] ?? '')) lastImport = i;
  }
  const position = lastImport >= 0 ? lastImport + 1 : insertAt;
  lines.splice(position, 0, statement.trim());
  return lines.join('\n');
}

export function assertSafeRewrite(source: string, result: RewriteResult): void {
  if (result.applied === 0) return;
  const delta = Math.abs(result.content.length - source.length);
  if (delta > source.length * 0.5 + 10_000) {
    throw new LingoFlowError('Code rewrite produced an unexpected large diff; aborting to protect your source files.', {
      code: 'REWRITE_UNSAFE',
      hint: 'Run with --dry-run first and check the reported ranges.',
    });
  }
}
