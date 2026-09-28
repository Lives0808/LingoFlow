/**
 * Minimal XLIFF 1.2 reader/writer. Enough for handing a file to a translator or
 * a TMS and importing the result back, without adding an XML dependency.
 */

export interface XliffUnit {
  id: string;
  source: string;
  target: string;
  comment?: string;
}

export interface XliffFile {
  sourceLanguage: string;
  targetLanguage: string;
  units: XliffUnit[];
}

export function renderXliff(file: XliffFile): string {
  const units = file.units
    .map((unit) => {
      const lines = [`    <trans-unit id="${escapeXml(unit.id)}" resname="${escapeXml(unit.id)}">`];
      if (unit.comment) lines.push(`      <note>${escapeXml(unit.comment)}</note>`);
      lines.push(`      <source>${escapeXml(unit.source)}</source>`);
      lines.push(`      <target>${escapeXml(unit.target)}</target>`);
      lines.push('    </trans-unit>');
      return lines.join('\n');
    })
    .join('\n');
  return `<?xml version="1.0" encoding="UTF-8"?>
<xliff version="1.2">
  <file source-language="${escapeXml(file.sourceLanguage)}" target-language="${escapeXml(file.targetLanguage)}" datatype="plaintext">
    <body>
${units}
    </body>
  </file>
</xliff>
`;
}

export function parseXliff(text: string): XliffFile {
  const headerMatch = /<file\b[^>]*>/iu.exec(text);
  const sourceLanguage = attributeOf(headerMatch?.[0] ?? '', 'source-language') ?? 'en';
  const targetLanguage = attributeOf(headerMatch?.[0] ?? '', 'target-language') ?? '';
  const units: XliffUnit[] = [];
  const unitRegex = /<trans-unit\b([^>]*)>([\s\S]*?)<\/trans-unit>/giu;
  let match: RegExpExecArray | null;
  while ((match = unitRegex.exec(text)) !== null) {
    const attributes = match[1] ?? '';
    const body = match[2] ?? '';
    const id = attributeOf(attributes, 'resname') ?? attributeOf(attributes, 'id') ?? '';
    const source = tagText(body, 'source');
    const target = tagText(body, 'target') ?? '';
    const note = tagText(body, 'note');
    if (!id) continue;
    units.push({ id: decodeXml(id), source: decodeXml(source ?? ''), target: decodeXml(target), comment: note ? decodeXml(note) : undefined });
  }
  return { sourceLanguage, targetLanguage, units };
}

function attributeOf(tag: string, name: string): string | null {
  const regex = new RegExp(`${name}\\s*=\\s*"([^"]*)"`, 'iu');
  const match = regex.exec(tag);
  return match?.[1] ?? null;
}

function tagText(body: string, tag: string): string | null {
  const regex = new RegExp(`<${tag}\\b[^>]*>([\\s\\S]*?)</${tag}>`, 'iu');
  const match = regex.exec(body);
  if (!match) return null;
  // Strip nested inline tags (<g>, <ph>, <bpt> …) but keep their text.
  return (match[1] ?? '').replace(/<[^>]+>/gu, '');
}

function escapeXml(value: string): string {
  return value
    .replace(/&/gu, '&amp;')
    .replace(/</gu, '&lt;')
    .replace(/>/gu, '&gt;')
    .replace(/"/gu, '&quot;');
}

function decodeXml(value: string): string {
  return value
    .replace(/&lt;/gu, '<')
    .replace(/&gt;/gu, '>')
    .replace(/&quot;/gu, '"')
    .replace(/&apos;/gu, "'")
    .replace(/&#(\d+);/gu, (_match, code: string) => String.fromCodePoint(Number(code)))
    .replace(/&amp;/gu, '&');
}
