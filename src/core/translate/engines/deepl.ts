import type { TranslateItem, TranslateOutput } from '../../types';
import { LingoFlowError } from '../../errors';
import { fetchJson, requireApiKey, type EngineAvailability, type EngineContext, type TranslationEngine } from '../engine';

const DEFAULT_TARGET_MAP: Record<string, string> = {
  'zh-CN': 'ZH-HANS',
  'zh-Hans': 'ZH-HANS',
  'zh-TW': 'ZH-HANT',
  'zh-Hant': 'ZH-HANT',
  en: 'EN-US',
  'en-GB': 'EN-GB',
  'en-US': 'EN-US',
  'pt-BR': 'PT-BR',
  'pt-PT': 'PT-PT',
};

/**
 * DeepL. Highest quality for European languages, but it is a remote service:
 * enable it explicitly and keep `privacy.offlineOnly` off only if that is allowed.
 */
export class DeepLEngine implements TranslationEngine {
  id = 'deepl' as const;
  label = 'DeepL';
  privacy = 'remote' as const;
  network = true;
  apiKeyEnv: string;

  constructor(
    private options: {
      endpoint: string;
      apiKeyEnv: string;
      formality: string;
      targetMap: Record<string, string>;
    },
  ) {
    this.apiKeyEnv = options.apiKeyEnv;
  }

  async availability(): Promise<EngineAvailability> {
    if (!process.env[this.apiKeyEnv]) return { ok: false, detail: `set ${this.apiKeyEnv} to enable DeepL` };
    return { ok: true, detail: `endpoint ${this.options.endpoint}` };
  }

  async translate(items: TranslateItem[], ctx: EngineContext): Promise<TranslateOutput[]> {
    const apiKey = requireApiKey(this.apiKeyEnv, 'DeepL');
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
      const body: Record<string, unknown> = {
        text: group.map((item) => item.text),
        target_lang: this.mapTarget(target),
        source_lang: this.mapSource(source),
      };
      if (this.options.formality !== 'default') body.formality = this.options.formality;
      const payload = await fetchJson(`${this.options.endpoint.replace(/\/$/u, '')}/v2/translate`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: `DeepL-Auth-Key ${apiKey}` },
        body: JSON.stringify(body),
        timeoutMs: ctx.config.translation.timeoutMs,
        engine: 'deepl',
        onExchange: ctx.onExchange,
      });
      const translations = (payload as { translations?: Array<{ text?: string }> }).translations;
      if (!Array.isArray(translations)) {
        throw new LingoFlowError('DeepL response did not contain translations[]', { code: 'DEEPL_BAD_RESPONSE' });
      }
      group.forEach((item, index) => {
        const text = translations[index]?.text;
        results.push({
          id: item.id,
          text: typeof text === 'string' ? text : item.text,
          engine: this.id,
          confidence: typeof text === 'string' ? 0.92 : 0.1,
        });
      });
    }
    return results;
  }

  private mapTarget(locale: string): string {
    const override = this.options.targetMap[locale];
    if (override) return override;
    const normalized = locale.replace(/_/gu, '-');
    const mapped = DEFAULT_TARGET_MAP[normalized] ?? DEFAULT_TARGET_MAP[normalized.split('-')[0] as string];
    if (mapped) return mapped;
    return (normalized.split('-')[0] as string).toUpperCase();
  }

  private mapSource(locale: string): string {
    return (locale.replace(/_/gu, '-').split('-')[0] as string).toUpperCase();
  }
}
