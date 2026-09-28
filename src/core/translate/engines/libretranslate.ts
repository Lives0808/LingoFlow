import type { TranslateItem, TranslateOutput } from '../../types';
import { LingoFlowError } from '../../errors';
import { fetchJson, type EngineAvailability, type EngineContext, type TranslationEngine } from '../engine';

/**
 * LibreTranslate (self-hosted or public). Self-hosting keeps everything on your
 * own network, which is the recommended privacy-preserving remote setup.
 */
export class LibreTranslateEngine implements TranslationEngine {
  id = 'libretranslate' as const;
  label = 'LibreTranslate';
  privacy = 'remote' as const;
  network = true;
  apiKeyEnv: string | undefined;

  constructor(private options: { url: string; apiKeyEnv: string }) {
    this.apiKeyEnv = options.apiKeyEnv;
  }

  async availability(): Promise<EngineAvailability> {
    if (!this.options.url) return { ok: false, detail: 'provider.libretranslate.url is empty' };
    try {
      const payload = await fetchJson(`${this.options.url.replace(/\/$/u, '')}/languages`, {
        timeoutMs: 8000,
        engine: 'libretranslate',
      });
      const count = Array.isArray(payload) ? payload.length : 0;
      return { ok: true, detail: `reachable at ${this.options.url} (${count} languages)` };
    } catch (error) {
      return { ok: false, detail: (error as Error).message };
    }
  }

  async translate(items: TranslateItem[], ctx: EngineContext): Promise<TranslateOutput[]> {
    const apiKey = this.apiKeyEnv ? process.env[this.apiKeyEnv] : undefined;
    const results: TranslateOutput[] = [];
    const groups = new Map<string, TranslateItem[]>();
    for (const item of items) {
      const key = `${item.from}\u0000${item.to}`;
      const group = groups.get(key) ?? [];
      group.push(item);
      groups.set(key, group);
    }
    for (const [key, group] of groups) {
      const [source, target] = key.split('\u0000') as [string, string];
      const payload = await fetchJson(`${this.options.url.replace(/\/$/u, '')}/translate`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          q: group.map((item) => item.text),
          source: normalizeLibre(source),
          target: normalizeLibre(target),
          format: 'text',
          ...(apiKey ? { api_key: apiKey } : {}),
        }),
        timeoutMs: ctx.config.translation.timeoutMs,
        engine: 'libretranslate',
        onExchange: ctx.onExchange,
      });
      const translated = (payload as { translatedText?: unknown }).translatedText;
      if (!Array.isArray(translated)) {
        throw new LingoFlowError('LibreTranslate response did not contain translatedText[]', { code: 'LIBRE_BAD_RESPONSE' });
      }
      group.forEach((item, index) => {
        const text = translated[index];
        results.push({
          id: item.id,
          text: typeof text === 'string' ? text : item.text,
          engine: this.id,
          confidence: typeof text === 'string' ? 0.8 : 0.1,
        });
      });
    }
    return results;
  }
}

function normalizeLibre(locale: string): string {
  const value = locale.replace(/_/gu, '-');
  const overrides: Record<string, string> = { 'zh-CN': 'zh', 'zh-TW': 'zt', 'zh-Hans': 'zh', 'zh-Hant': 'zt' };
  return overrides[value] ?? value.split('-')[0] ?? value;
}
