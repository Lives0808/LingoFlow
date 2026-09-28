import type { EngineId, TranslateItem, TranslateOutput } from '../types';
import type { ResolvedConfig } from '../config/schema';
import type { Logger } from '../logger';
import type { DntTerm, GlossaryTerm } from '../rules/model';
import { LingoFlowError } from '../errors';

export interface EngineContext {
  config: ResolvedConfig;
  logger: Logger;
  rootDir: string;
  /** Glossary terms per locale, injected as hints for LLM engines. */
  glossaryByLocale?: Map<string, GlossaryTerm[]>;
  /** Do-not-translate terms per locale. */
  dntByLocale?: Map<string, DntTerm[]>;
  /** Style hints per locale (already resolved to plain sentences). */
  styleHints?: Map<string, string[]>;
  /** Called with the raw provider payload for `--debug` runs. */
  onExchange?: (info: { engine: string; request: unknown; response: unknown }) => void;
}

export interface EngineAvailability {
  ok: boolean;
  detail: string;
}

export interface TranslationEngine {
  id: EngineId;
  label: string;
  privacy: 'local' | 'remote';
  /** Environment variable that must be set, when any. */
  apiKeyEnv?: string;
  /** True when the engine needs network access. */
  network: boolean;
  availability(): Promise<EngineAvailability>;
  translate(items: TranslateItem[], ctx: EngineContext): Promise<TranslateOutput[]>;
}

export function requireApiKey(envName: string, engine: string): string {
  const value = process.env[envName];
  if (!value) {
    throw new LingoFlowError(`${engine} requires the ${envName} environment variable.`, {
      code: 'MISSING_API_KEY',
      hint: `Export it first: export ${envName}=... (LingoFlow never stores keys).`,
    });
  }
  return value;
}

export async function fetchJson(
  url: string,
  init: RequestInit & { timeoutMs: number; engine: string; onExchange?: EngineContext['onExchange'] },
): Promise<unknown> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), init.timeoutMs);
  try {
    const response = await fetch(url, { ...init, signal: controller.signal });
    const text = await response.text();
    let payload: unknown = text;
    try {
      payload = text.length > 0 ? JSON.parse(text) : null;
    } catch {
      payload = text;
    }
    init.onExchange?.({ engine: init.engine, request: { url, method: init.method ?? 'GET' }, response: payload });
    if (!response.ok) {
      const detail = typeof payload === 'string' ? payload.slice(0, 400) : JSON.stringify(payload).slice(0, 400);
      throw new LingoFlowError(`${init.engine} request failed (${response.status} ${response.statusText}): ${detail}`, {
        code: 'ENGINE_HTTP_ERROR',
      });
    }
    return payload;
  } catch (error) {
    if (error instanceof LingoFlowError) throw error;
    const message = (error as Error).name === 'AbortError' ? `timed out after ${init.timeoutMs}ms` : (error as Error).message;
    throw new LingoFlowError(`${init.engine} request failed: ${message}`, {
      code: 'ENGINE_REQUEST_FAILED',
      hint: 'Check the endpoint, network access and whether offline-only mode is desired.',
      cause: error,
    });
  } finally {
    clearTimeout(timeout);
  }
}

export function normalizeEngineOutput(items: TranslateItem[], outputs: TranslateOutput[], engineId: string): TranslateOutput[] {
  const byId = new Map(outputs.map((output) => [output.id, output]));
  return items.map((item) => {
    const found = byId.get(item.id);
    if (found) return found;
    return { id: item.id, text: item.text, engine: engineId, confidence: 0.1, notes: ['missing from engine response'] };
  });
}
