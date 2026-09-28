import type { TranslateItem, TranslateOutput } from '../../types';
import { LingoFlowError } from '../../errors';
import { dntFor, glossaryFor, styleHintsFor } from '../hints';
import { fetchJson, requireApiKey, type EngineAvailability, type EngineContext, type TranslationEngine } from '../engine';
import { buildSystemPrompt, buildUserPrompt, parseModelTranslations, type PromptContext } from '../prompt';

/**
 * Any OpenAI-compatible chat completions endpoint.
 * Point `provider.openai.baseUrl` at a *local* server (Ollama, LM Studio, vLLM)
 * to keep the convenience of an LLM without sending text to a third party.
 */
export class OpenAICompatibleEngine implements TranslationEngine {
  id = 'openai' as const;
  label = 'OpenAI-compatible chat model';
  privacy = 'remote' as const;
  network = true;
  apiKeyEnv: string;

  constructor(
    private options: {
      baseUrl: string;
      model: string;
      apiKeyEnv: string;
      temperature: number;
      batchSize: number;
      maxTokens: number;
      systemPrompt?: string;
      jsonMode: boolean;
      extraBody: Record<string, unknown>;
    },
  ) {
    this.apiKeyEnv = options.apiKeyEnv;
  }

  private get isLocalEndpoint(): boolean {
    return /localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\]/iu.test(this.options.baseUrl);
  }

  async availability(): Promise<EngineAvailability> {
    const local = this.isLocalEndpoint;
    if (!local && !process.env[this.apiKeyEnv]) {
      return { ok: false, detail: `set ${this.apiKeyEnv} (or point baseUrl at a local server)` };
    }
    return {
      ok: true,
      detail: `${this.options.baseUrl} · model ${this.options.model}${local ? ' · local endpoint' : ''}`,
    };
  }

  async translate(items: TranslateItem[], ctx: EngineContext): Promise<TranslateOutput[]> {
    const apiKey = this.isLocalEndpoint ? (process.env[this.apiKeyEnv] ?? 'local') : requireApiKey(this.apiKeyEnv, 'OpenAI-compatible engine');
    const results: TranslateOutput[] = [];
    const groups = new Map<string, TranslateItem[]>();
    for (const item of items) {
      const key = item.to;
      const group = groups.get(key) ?? [];
      group.push(item);
      groups.set(key, group);
    }
    for (const [targetLocale, group] of groups) {
      const promptContext: PromptContext = {
        sourceLocale: group[0]?.from ?? ctx.config.sourceLocale,
        targetLocale,
        glossary: glossaryFor(ctx, targetLocale),
        dnt: dntFor(ctx, targetLocale),
        styleHints: styleHintsFor(ctx, targetLocale),
      };
      const system = this.options.systemPrompt ?? buildSystemPrompt(promptContext);
      for (let i = 0; i < group.length; i += this.options.batchSize) {
        const batch = group.slice(i, i + this.options.batchSize);
        const body: Record<string, unknown> = {
          model: this.options.model,
          temperature: this.options.temperature,
          max_tokens: this.options.maxTokens,
          messages: [
            { role: 'system', content: system },
            { role: 'user', content: buildUserPrompt(batch, promptContext) },
          ],
          ...(this.options.jsonMode ? { response_format: { type: 'json_object' } } : {}),
          ...this.options.extraBody,
        };
        const payload = await fetchJson(`${this.options.baseUrl.replace(/\/$/u, '')}/chat/completions`, {
          method: 'POST',
          headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
          body: JSON.stringify(body),
          timeoutMs: ctx.config.translation.timeoutMs,
          engine: 'openai',
          onExchange: ctx.onExchange,
        });
        const content = extractContent(payload);
        if (content === null) {
          throw new LingoFlowError('Chat completion response did not contain message content', { code: 'OPENAI_BAD_RESPONSE' });
        }
        const parsed = parseModelTranslations(content);
        for (const item of batch) {
          const translated = parsed.get(item.id);
          if (translated === undefined) {
            results.push({
              id: item.id,
              text: item.text,
              engine: this.id,
              confidence: 0.1,
              notes: ['model did not return this id'],
            });
            continue;
          }
          results.push({ id: item.id, text: translated, engine: this.id, confidence: 0.85 });
        }
      }
    }
    return results;
  }
}

function extractContent(payload: unknown): string | null {
  const choices = (payload as { choices?: Array<{ message?: { content?: unknown }; text?: unknown }> }).choices;
  if (!Array.isArray(choices) || choices.length === 0) return null;
  const first = choices[0];
  const content = first?.message?.content ?? first?.text;
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content
      .map((part) => (typeof part === 'string' ? part : typeof (part as { text?: unknown }).text === 'string' ? (part as { text: string }).text : ''))
      .join('');
  }
  return null;
}
