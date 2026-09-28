import type { GlossaryTerm, DntTerm } from '../rules/model';
import type { EngineContext } from './engine';

/** Rule lookups used by engines when composing prompts. */
export function glossaryFor(ctx: EngineContext, locale: string): GlossaryTerm[] {
  return ctx.glossaryByLocale?.get(locale) ?? [];
}

export function dntFor(ctx: EngineContext, locale: string): string[] {
  const terms = ctx.dntByLocale?.get(locale) ?? [];
  return terms.map((term: DntTerm) => term.replacement || term.pattern);
}

export function styleHintsFor(ctx: EngineContext, locale: string): string[] {
  return ctx.styleHints?.get(locale) ?? [];
}
