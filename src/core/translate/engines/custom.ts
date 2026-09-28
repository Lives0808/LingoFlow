import type { TranslateItem, TranslateOutput } from '../../types';
import { LingoFlowError } from '../../errors';
import { fetchJson, type EngineAvailability, type EngineContext, type TranslationEngine } from '../engine';

/**
 * Template-driven HTTP engine for any internal/self-hosted translation service.
 * `bodyTemplate` may reference `{{json.text}}`, `{{json.source}}`, `{{json.target}}`
 * and `{{apiKey}}`; `responsePath` is a dot path into the JSON response.
 */
export class CustomHttpEngine implements TranslationEngine {
  id = 'custom' as const;
  label = 'Custom HTTP endpoint';
  privacy = 'remote' as const;
  network = true;
  apiKeyEnv?: string;

  constructor(
    private options: {
      url: string;
      method: 'POST' | 'GET';
      headers: Record<string, string>;
      bodyTemplate: string;
      responsePath: string;
      apiKeyEnv?: string;
      apiKeyHeader: string;
    },
  ) {
    this.apiKeyEnv = options.apiKeyEnv;
  }

  async availability(): Promise<EngineAvailability> {
    if (!this.options.url) return { ok: false, detail: 'provider.custom.url is empty' };
    if (this.options.apiKeyEnv && !process.env[this.options.apiKeyEnv]) {
      return { ok: false, detail: `set ${this.options.apiKeyEnv}` };
    }
    return { ok: true, detail: `${this.options.method} ${this.options.url}` };
  }

  async translate(items: TranslateItem[], ctx: EngineContext): Promise<TranslateOutput[]> {
    const results: TranslateOutput[] = [];
    const apiKey = this.options.apiKeyEnv ? (process.env[this.options.apiKeyEnv] ?? '') : '';
    for (const item of items) {
      const url = this.options.url
        .replace(/\{\{source\}\}/gu, encodeURIComponent(item.from))
        .replace(/\{\{target\}\}/gu, encodeURIComponent(item.to))
        .replace(/\{\{text\}\}/gu, encodeURIComponent(item.text));
      const body = this.options.method === 'POST' ? renderTemplate(this.options.bodyTemplate, item, apiKey, ctx) : undefined;
      const headers: Record<string, string> = { ...this.options.headers };
      if (apiKey) {
        const headerName = this.options.apiKeyHeader || 'authorization';
        headers[headerName] = headers[headerName] ? headers[headerName].replace('{{apiKey}}', apiKey) : `Bearer ${apiKey}`;
      } else {
        for (const [name, value] of Object.entries(headers)) headers[name] = value.replace('{{apiKey}}', '');
      }
      const payload = await fetchJson(url, {
        method: this.options.method,
        headers,
        body,
        timeoutMs: ctx.config.translation.timeoutMs,
        engine: 'custom',
        onExchange: ctx.onExchange,
      });
      const text = readDotPath(payload, this.options.responsePath);
      if (typeof text !== 'string') {
        throw new LingoFlowError(
          `Custom engine response did not contain a string at "${this.options.responsePath}"`,
          { code: 'CUSTOM_BAD_RESPONSE' },
        );
      }
      results.push({ id: item.id, text, engine: this.id, confidence: 0.8 });
    }
    return results;
  }
}

function renderTemplate(template: string, item: TranslateItem, apiKey: string, ctx: EngineContext): string {
  const values: Record<string, unknown> = {
    text: item.text,
    source: item.from,
    target: item.to,
    key: item.key,
    comment: item.comment ?? '',
    maxChars: item.maxChars ?? 0,
    locale: ctx.config.sourceLocale,
  };
  return template
    .replace(/\{\{json\.([\w.]+)\}\}/gu, (_match, path: string) => JSON.stringify(readDotPath(values, path) ?? null))
    .replace(/\{\{raw\.([\w.]+)\}\}/gu, (_match, path: string) => String(readDotPath(values, path) ?? ''))
    .replace(/\{\{apiKey\}\}/gu, apiKey);
}

export function readDotPath(value: unknown, path: string): unknown {
  if (!path) return value;
  let cursor: unknown = value;
  for (const segment of path.split('.')) {
    if (cursor === null || cursor === undefined) return undefined;
    if (Array.isArray(cursor)) {
      const index = Number.parseInt(segment, 10);
      cursor = Number.isNaN(index) ? undefined : cursor[index];
      continue;
    }
    if (typeof cursor !== 'object') return undefined;
    cursor = (cursor as Record<string, unknown>)[segment];
  }
  return cursor;
}
