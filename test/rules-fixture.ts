import { buildRuleSet } from '../src/core/rules/model';
import type { RuleRow } from '../src/core/types';

/** Shared rule fixture: glossary + style + a learned correction. */
export const BUILD_RULESET = buildRuleSet([
  {
    kind: 'glossary',
    locale: 'zh-CN',
    pattern: 'Settings',
    value: JSON.stringify({ target: '设置' }),
    priority: 60,
    enabled: true,
    origin: 'import',
    confidence: 1,
  },
  {
    kind: 'style',
    locale: 'zh-CN',
    pattern: 'punctuation.trailing',
    value: 'never',
    priority: 80,
    enabled: true,
    origin: 'import',
    confidence: 1,
  },
  {
    kind: 'style',
    locale: 'zh-CN',
    pattern: 'cjk.fullwidth',
    value: 'true',
    priority: 80,
    enabled: true,
    origin: 'import',
    confidence: 1,
  },
  {
    kind: 'dnt',
    locale: null,
    pattern: 'LingoFlow',
    value: JSON.stringify({ replacement: 'LingoFlow' }),
    priority: 90,
    enabled: true,
    origin: 'manual',
    confidence: 1,
  },
] satisfies RuleRow[]);
