export interface TagToken {
  raw: string;
  name: string;
  closing: boolean;
  selfClosing: boolean;
  attributes: string;
}

const TAG_RE = /<(\/?)([A-Za-z][\w:.-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(\/?)>/gu;

export function extractTags(text: string): TagToken[] {
  const tokens: TagToken[] = [];
  TAG_RE.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = TAG_RE.exec(text)) !== null) {
    tokens.push({
      raw: match[0],
      closing: match[1] === '/',
      name: (match[2] ?? '').toLowerCase(),
      attributes: (match[3] ?? '').trim(),
      selfClosing: match[4] === '/',
    });
  }
  return tokens;
}

export interface TagComparison {
  missing: string[];
  extra: string[];
  unbalanced: boolean;
  attributeIssues: string[];
}

export function compareTags(source: string, target: string): TagComparison {
  const sourceTags = extractTags(source);
  const targetTags = extractTags(target);
  const sourceOpen = sourceTags.filter((tag) => !tag.closing && !tag.selfClosing).map((tag) => tag.name);
  const targetOpen = targetTags.filter((tag) => !tag.closing && !tag.selfClosing).map((tag) => tag.name);
  const missing = sourceOpen.filter((name) => !targetOpen.includes(name));
  const extra = targetOpen.filter((name) => !sourceOpen.includes(name));

  // Balance check on the target.
  const stack: string[] = [];
  let unbalanced = false;
  for (const tag of targetTags) {
    if (tag.selfClosing) continue;
    if (!tag.closing) stack.push(tag.name);
    else if (stack.pop() !== tag.name) unbalanced = true;
  }
  if (stack.length > 0) unbalanced = true;

  const attributeIssues: string[] = [];
  for (const sourceTag of sourceTags) {
    if (sourceTag.selfClosing || sourceTag.closing || sourceTag.attributes.length === 0) continue;
    const targetTag = targetTags.find((candidate) => candidate.name === sourceTag.name && !candidate.closing);
    if (!targetTag) continue;
    const attributes = attributeNamesOf(sourceTag.attributes);
    for (const attribute of attributes) {
      if (attribute.startsWith('aria-') || attribute === 'class' || attribute === 'style') continue;
      if (!targetTag.attributes.includes(attribute)) {
        attributeIssues.push(`<${sourceTag.name}> lost the "${attribute}" attribute`);
      }
    }
  }

  return { missing, extra, unbalanced, attributeIssues };
}

function attributeNamesOf(attributes: string): string[] {
  const names: string[] = [];
  const regex = /([A-Za-z_:][\w:.-]*)\s*=/gu;
  let match: RegExpExecArray | null;
  while ((match = regex.exec(attributes)) !== null) {
    names.push((match[1] ?? '').toLowerCase());
  }
  return names;
}

export const HTML_ENTITIES: Record<string, string> = {
  '&amp;': '&',
  '&lt;': '<',
  '&gt;': '>',
  '&quot;': '"',
  '&apos;': "'",
  '&nbsp;': '\u00a0',
};

export function hasHtmlEntities(text: string): boolean {
  return /&(?:amp|lt|gt|quot|apos|nbsp|#\d+|#x[0-9a-f]+);/iu.test(text);
}
