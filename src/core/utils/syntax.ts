import { extractPlaceholders } from '../validate/placeholders';
import { extractTags } from '../validate/tags';

export interface MaskedSyntax {
  /** Text with placeholders and tags replaced by invisible sentinels. */
  text: string;
  restore(value: string): string;
}

/**
 * Shields placeholders (`{name}`, `{{count}}`, `%s`, `{n, plural, …}`) and HTML
 * tags so text transforms can never rewrite them. Tag offsets come from the
 * shared tag helper, keeping validation and fixing consistent.
 */
export function maskSyntax(text: string): MaskedSyntax {
  const ranges: Array<[number, number]> = extractPlaceholders(text).map((token) => [token.start, token.end]);
  const tagRegex = /<\/?[A-Za-z][\w:.-]*(?:"[^"]*"|'[^']*'|[^>"'])*?\/?>/gu;
  let match: RegExpExecArray | null;
  while ((match = tagRegex.exec(text)) !== null) ranges.push([match.index, match.index + match[0].length]);
  if (ranges.length === 0) {
    return { text, restore: (value: string) => value };
  }
  ranges.sort((a, b) => a[0] - b[0]);
  const sentinels = new Map<string, string>();
  let masked = text;
  let counter = 0;
  for (const [start, end] of [...ranges].reverse()) {
    const sentinel = `\u2060${counter++}\u2060`;
    sentinels.set(sentinel, text.slice(start, end));
    masked = `${masked.slice(0, start)}${sentinel}${masked.slice(end)}`;
  }
  return {
    text: masked,
    restore: (value: string) => {
      let output = value;
      for (const [sentinel, original] of sentinels) output = output.split(sentinel).join(original);
      return output;
    },
  };
}

/** Runs `transform` with placeholders and tags shielded. */
export function withMaskedSyntax(text: string, transform: (value: string) => string): string {
  const masked = maskSyntax(text);
  return masked.restore(transform(masked.text));
}

/** Exposes the tag ranges for callers that need offsets (previews, reports). */
export function tagRangesOf(text: string): Array<[number, number]> {
  return extractTags(text).map((tag) => {
    const start = text.indexOf(tag.raw);
    return [start, start + tag.raw.length] as [number, number];
  });
}
