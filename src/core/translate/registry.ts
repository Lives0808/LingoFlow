import path from 'node:path';
import type { EngineId } from '../types';
import type { ResolvedConfig } from '../config/schema';
import { LingoFlowError } from '../errors';
import { readTextIfExists } from '../utils/fsx';
import type { TranslationEngine } from './engine';
import { OfflineEngine } from './engines/offline';
import { PseudoEngine, isPseudoLocale } from './engines/pseudo';
import { ArgosEngine } from './engines/argos';
import { LibreTranslateEngine } from './engines/libretranslate';
import { DeepLEngine } from './engines/deepl';
import { OpenAICompatibleEngine } from './engines/openai';
import { CustomHttpEngine } from './engines/custom';

export const ENGINE_IDS: EngineId[] = ['offline', 'pseudo', 'argos', 'libretranslate', 'deepl', 'openai', 'custom'];

export interface EngineSetup {
  config: ResolvedConfig;
  rootDir: string;
}

export async function createEngine(id: EngineId, setup: EngineSetup): Promise<TranslationEngine> {
  const provider = setup.config.translation.provider;
  switch (id) {
    case 'offline': {
      const dictionaryPath = provider.offline.dictionary;
      let userDictionary: Record<string, Record<string, string>> | undefined;
      if (dictionaryPath) {
        const file = path.isAbsolute(dictionaryPath) ? dictionaryPath : path.resolve(setup.rootDir, dictionaryPath);
        const text = await readTextIfExists(file);
        if (text) {
          try {
            userDictionary = JSON.parse(text) as Record<string, Record<string, string>>;
          } catch (error) {
            throw new LingoFlowError(`Cannot parse offline dictionary ${dictionaryPath}: ${(error as Error).message}`, {
              code: 'DICTIONARY_PARSE_ERROR',
            });
          }
        }
      }
      return new OfflineEngine(userDictionary);
    }
    case 'pseudo':
      return new PseudoEngine(0.4);
    case 'argos':
      return new ArgosEngine({
        python: provider.argos.python,
        modelDir: provider.argos.modelDir,
        bridge: provider.argos.bridge,
      });
    case 'libretranslate':
      return new LibreTranslateEngine({ url: provider.libretranslate.url, apiKeyEnv: provider.libretranslate.apiKeyEnv });
    case 'deepl':
      return new DeepLEngine({
        endpoint: provider.deepl.endpoint,
        apiKeyEnv: provider.deepl.apiKeyEnv,
        formality: provider.deepl.formality,
        targetMap: provider.deepl.targetMap,
      });
    case 'openai':
      return new OpenAICompatibleEngine({
        baseUrl: provider.openai.baseUrl,
        model: provider.openai.model,
        apiKeyEnv: provider.openai.apiKeyEnv,
        temperature: provider.openai.temperature,
        batchSize: provider.openai.batchSize,
        maxTokens: provider.openai.maxTokens,
        systemPrompt: provider.openai.systemPrompt,
        jsonMode: provider.openai.jsonMode,
        extraBody: provider.openai.extraBody,
      });
    case 'custom':
      return new CustomHttpEngine({
        url: provider.custom.url,
        method: provider.custom.method,
        headers: provider.custom.headers,
        bodyTemplate: provider.custom.bodyTemplate,
        responsePath: provider.custom.responsePath,
        apiKeyEnv: provider.custom.apiKeyEnv,
        apiKeyHeader: provider.custom.apiKeyHeader,
      });
    default:
      throw new LingoFlowError(`Unknown engine: ${id}`, { code: 'UNKNOWN_ENGINE' });
  }
}

export interface ResolvedEngines {
  primary: TranslationEngine;
  fallbacks: TranslationEngine[];
  /** Engine used for pseudo-locales, if any. */
  pseudo: TranslationEngine;
}

export async function resolveEngines(setup: EngineSetup, override?: EngineId): Promise<ResolvedEngines> {
  const config = setup.config;
  const primaryId = override ?? config.translation.engine;
  const primary = await createEngine(primaryId, setup);
  const fallbacks: TranslationEngine[] = [];
  for (const id of config.translation.fallback) {
    if (id === primaryId) continue;
    fallbacks.push(await createEngine(id, setup));
  }
  for (const engine of [primary, ...fallbacks]) assertPrivacy(engine, config);
  const pseudo = primaryId === 'pseudo' ? primary : new PseudoEngine(0.4);
  return { primary, fallbacks, pseudo };
}

export function engineForLocale(engines: ResolvedEngines, locale: string, config: ResolvedConfig): TranslationEngine {
  if (isPseudoLocale(locale) || config.translation.engine === 'pseudo') return engines.pseudo;
  return engines.primary;
}

/** Blocks remote engines when the project is offline-only. */
export function assertPrivacy(engine: TranslationEngine, config: ResolvedConfig): void {
  if (!engine.network) return;
  if (config.privacy.offlineOnly) {
    throw new LingoFlowError(
      `Engine "${engine.id}" sends text over the network, but privacy.offlineOnly is enabled.`,
      {
        code: 'PRIVACY_BLOCKED',
        hint: 'Switch to the offline/argos engine or set privacy.offlineOnly to false.',
      },
    );
  }
  if (!config.privacy.allowNetwork) {
    throw new LingoFlowError(`Engine "${engine.id}" is remote but privacy.allowNetwork is false.`, {
      code: 'PRIVACY_BLOCKED',
      hint: 'Set privacy.allowNetwork to true, or use a local engine.',
    });
  }
}

export interface EngineDescription {
  id: EngineId;
  label: string;
  privacy: 'local' | 'remote';
  selected: boolean;
  available: boolean;
  detail: string;
}

export async function describeEngines(setup: EngineSetup): Promise<EngineDescription[]> {
  const descriptions: EngineDescription[] = [];
  for (const id of ENGINE_IDS) {
    try {
      const engine = await createEngine(id, setup);
      const availability = await engine.availability();
      descriptions.push({
        id,
        label: engine.label,
        privacy: engine.privacy,
        selected: setup.config.translation.engine === id,
        available: availability.ok,
        detail: availability.detail,
      });
    } catch (error) {
      descriptions.push({
        id,
        label: id,
        privacy: id === 'offline' || id === 'pseudo' || id === 'argos' ? 'local' : 'remote',
        selected: setup.config.translation.engine === id,
        available: false,
        detail: (error as Error).message,
      });
    }
  }
  return descriptions;
}
