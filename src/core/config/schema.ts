import { z } from 'zod';

/** Zod schema for `lingoflow.config.json`. Every field is optional thanks to defaults. */

export const formatIdSchema = z.enum(['json', 'yaml', 'properties', 'po', 'arb', 'strings', 'csv', 'tsjs']);
export const engineIdSchema = z.enum(['offline', 'pseudo', 'argos', 'libretranslate', 'deepl', 'openai', 'custom']);
export const severitySchema = z.enum(['error', 'warn', 'info']);

export const catalogSchema = z.object({
  /** Path template, `{locale}` is replaced with the locale code. */
  path: z.string(),
  format: z.union([formatIdSchema, z.literal('auto')]).default('auto'),
  /** Force a locale when the path does not contain `{locale}`. */
  locale: z.string().optional(),
  /** Dots in keys become nested objects. */
  nesting: z.boolean().default(true),
  keySeparator: z.string().default('.'),
  /** Writes keys in the order they appear in the source catalog. */
  keepOrder: z.boolean().default(true),
});

const rewriteSchema = z.object({
  enabled: z.boolean().default(true),
  /** Template used when a hardcoded string is replaced. `{key}` is substituted. */
  callTemplate: z.string().default("t('{key}')"),
  /** Optionally insert an import statement at the top of rewritten files. */
  importStatement: z.string().optional(),
  /** Wrap JSX text nodes in braces: `<b>{t('k')}</b>` instead of `<b>text</b>`. */
  jsxBraces: z.boolean().default(true),
});

const hardcodedSchema = z.object({
  mode: z.enum(['off', 'report', 'extract']).default('report'),
  /** Attributes whose string values are treated as UI copy. */
  attributes: z
    .array(z.string())
    .default(['placeholder', 'title', 'label', 'alt', 'aria-label', 'aria-description', 'tooltip']),
  /** Minimum words for a plain string literal to be considered UI copy. */
  minWords: z.number().int().min(1).default(2),
  /** Extra regexes for strings that must never be extracted. */
  ignore: z.array(z.string()).default([]),
  /** Auto-extract only candidates at or above this confidence (0..1). */
  minConfidence: z.number().min(0).max(1).default(0.75),
  rewrite: rewriteSchema.prefault({}),
});

export const codeSchema = z.object({
  include: z
    .array(z.string())
    .default(['src/**/*.{ts,tsx,js,jsx,mjs,cjs,vue,svelte,astro}', 'app/**/*.{ts,tsx,js,jsx,vue,svelte}']),
  exclude: z
    .array(z.string())
    .default([
      '**/node_modules/**',
      '**/dist/**',
      '**/build/**',
      '**/.git/**',
      '**/coverage/**',
      '**/*.min.js',
      '**/*.d.ts',
    ]),
  /** Functions/properties treated as translation calls. */
  calls: z.array(z.string()).default(['t', 'i18n.t', 'i18next.t', '$t', '$tc', 'translate', 'formatMessage', 'intl.formatMessage']),
  /** JSX components treated as translation sources (`i18nKey`, `id`, `defaultMessage`). */
  components: z.array(z.string()).default(['Trans', 'FormattedMessage', 'I18n', 'T']),
  /** ESLint-style scan disabled switches. */
  hardcoded: hardcodedSchema.prefault({}),
});

export const providerSchema = z.object({
  offline: z
    .object({
      /** Optional user dictionary layered on top of the built-in seed dictionary. */
      dictionary: z.string().optional(),
      /** Locales the offline engine can compose word-by-word. */
      enabled: z.boolean().default(true),
    })
    .prefault({}),
  argos: z
    .object({
      python: z.string().default('python3'),
      modelDir: z.string().optional(),
      /** Optional path to a custom bridge script. */
      bridge: z.string().optional(),
    })
    .prefault({}),
  libretranslate: z
    .object({
      url: z.string().default('http://localhost:5000'),
      apiKeyEnv: z.string().default('LIBRETRANSLATE_API_KEY'),
    })
    .prefault({}),
  deepl: z
    .object({
      endpoint: z.string().default('https://api-free.deepl.com'),
      apiKeyEnv: z.string().default('DEEPL_API_KEY'),
      formality: z.enum(['default', 'more', 'less', 'prefer_more', 'prefer_less']).default('default'),
      /** DeepL target language overrides, e.g. `{ "zh-CN": "ZH" }`. */
      targetMap: z.record(z.string(), z.string()).prefault({}),
    })
    .prefault({}),
  openai: z
    .object({
      baseUrl: z.string().default('https://api.openai.com/v1'),
      model: z.string().default('gpt-4o-mini'),
      apiKeyEnv: z.string().default('OPENAI_API_KEY'),
      temperature: z.number().min(0).max(2).default(0.2),
      /** Batch several keys per request to cut cost/latency. */
      batchSize: z.number().int().min(1).max(50).default(12),
      /** Ask the endpoint for a JSON response (disable for gateways that reject it). */
      jsonMode: z.boolean().default(true),
      maxTokens: z.number().int().positive().default(2048),
      systemPrompt: z.string().optional(),
      /** Extra body fields for compatible gateways. */
      extraBody: z.record(z.string(), z.unknown()).prefault({}),
    })
    .prefault({}),
  custom: z
    .object({
      url: z.string().default(''),
      method: z.enum(['POST', 'GET']).default('POST'),
      headers: z.record(z.string(), z.string()).default({ 'content-type': 'application/json' }),
      bodyTemplate: z.string().default('{"text": {{json.text}}, "source": {{json.source}}, "target": {{json.target}}}'),
      /** Dot path to the translated string inside the JSON response. */
      responsePath: z.string().default('translatedText'),
      apiKeyEnv: z.string().optional(),
      /** `{{apiKey}}` placeholder in headers/body is replaced with the env value. */
      apiKeyHeader: z.string().default('authorization'),
    })
    .prefault({}),
});

export const memorySchema = z.object({
  path: z.string().default('.lingoflow/memory'),
  driver: z.enum(['auto', 'sqlite', 'jsonl']).default('auto'),
  /** Fuzzy match acceptance threshold (0..1). */
  fuzzyThreshold: z.number().min(0).max(1).default(0.72),
  /** Reuse fuzzy matches automatically, otherwise only proposals are reported. */
  fuzzyAutofill: z.boolean().default(true),
  /** Treat entries marked approved as read-only unless `--force`. */
  freezeApproved: z.boolean().default(true),
  /** Derive correction rules from human edits between runs. */
  learnFromEdits: z.boolean().default(true),
  /** Never store strings matching these regexes (privacy). */
  excludePatterns: z.array(z.string()).default([]),
});

export const translationSchema = z.object({
  engine: engineIdSchema.default('offline'),
  fallback: z.array(engineIdSchema).default([]),
  /** Which target entries get (re)translated. */
  policy: z.enum(['missing', 'empty', 'untranslated', 'stale', 'all']).default('missing'),
  concurrency: z.number().int().min(1).max(64).default(4),
  timeoutMs: z.number().int().positive().default(60000),
  retries: z.number().int().min(0).max(10).default(2),
  /** Hard stop after N engine calls; 0 = unlimited. */
  maxItems: z.number().int().min(0).default(0),
  provider: providerSchema.prefault({}),
  memory: memorySchema.prefault({}),
  glossary: z
    .object({
      file: z.string().default('lingoflow.glossary.json'),
      /** Enforce glossary terms in post-processing. */
      enforce: z.boolean().default(true),
      /** Case sensitive source matching. */
      matchCase: z.boolean().default(false),
      /**
       * auto    - shield terms with placeholders for MT engines, prompt them for LLMs
       * protect - always shield with placeholders (guaranteed terms)
       * prompt  - only tell the engine, then verify and report
       * enforce - translate freely, then replace the terms afterwards
       */
      mode: z.enum(['auto', 'protect', 'prompt', 'enforce']).default('auto'),
    })
    .prefault({}),
  style: z
    .object({
      file: z.string().default('lingoflow.style.json'),
      enforce: z.boolean().default(true),
    })
    .prefault({}),
  /** Send strings to the engine unchanged; set false to mask emails/urls first. */
  keepPlaceholders: z.boolean().default(true),
});

export const lengthSchema = z.object({
  enabled: z.boolean().default(true),
  unit: z.enum(['px', 'chars', 'both']).default('both'),
  font: z
    .object({
      family: z.string().default('system-ui'),
      size: z.number().positive().default(14),
      weight: z.number().int().default(400),
      letterSpacing: z.number().default(0),
    })
    .prefault({}),
  default: z
    .object({
      max: z.number().positive().default(60),
      min: z.number().min(0).default(0),
      hard: z.boolean().default(false),
      widget: z.string().default('label'),
    })
    .prefault({}),
  rules: z
    .array(
      z.object({
        /** Glob-ish pattern, e.g. `cta.*`, `*.tooltip`, `button.*`. */
        match: z.string(),
        max: z.number().positive().optional(),
        min: z.number().min(0).optional(),
        hard: z.boolean().optional(),
        widget: z.string().optional(),
      }),
    )
    .default([
      { match: 'cta.*', max: 18, hard: true, widget: 'button' },
      { match: '*.tooltip', max: 120, widget: 'tooltip' },
      { match: '*.title', max: 32, widget: 'heading' },
    ]),
  /** Allowed growth per locale vs the source string (1.4 = +40%). */
  expansion: z.record(z.string(), z.number().positive()).default({ de: 1.4, fr: 1.35, es: 1.3, ru: 1.35, 'zh-CN': 0.7, ja: 0.8, ko: 0.85 }),
  autofix: z
    .object({
      enabled: z.boolean().default(true),
      /** Ordered strategies: reflow -> punctuation -> abbreviate -> shorten -> engine-retranslate */
      strategies: z
        .array(z.enum(['reflow', 'punctuation', 'abbreviate', 'shorten', 'retranslate']))
        .default(['reflow', 'punctuation', 'abbreviate', 'shorten']),
      maxRounds: z.number().int().min(1).max(5).default(2),
    })
    .prefault({}),
});

export const validateSchema = z.object({
  placeholders: z.boolean().default(true),
  icu: z.boolean().default(true),
  tags: z.boolean().default(true),
  whitespace: z.boolean().default(true),
  punctuation: z.boolean().default(true),
  ellipsis: z.boolean().default(true),
  /** CJK typography: fullwidth punctuation, CJK/Latin spacing. */
  cjk: z.boolean().default(true),
  script: z.boolean().default(true),
  dnt: z.boolean().default(true),
  glossary: z.boolean().default(true),
  consistency: z.boolean().default(true),
  /** Same source text must map to the same translation. */
  sameSourceSameTarget: z.boolean().default(true),
  forbidden: z.array(z.string()).default([]),
  /** Severity overrides per issue code. */
  severity: z.record(z.string(), severitySchema).default({
    'length.expansion': 'info',
    'length.wrap': 'info',
    'key.unused': 'info',
    'style.case': 'info',
    'style.formality': 'info',
    'cjk.space': 'info',
    'text.ellipsis': 'info',
    'script.mismatch': 'warn',
  }),
  /** Ignore specific keys/locales, e.g. `{ "zh-CN": ["debug.*"] }`. */
  ignore: z.record(z.string(), z.array(z.string())).prefault({}),
});

export const writeSchema = z.object({
  atomic: z.boolean().default(true),
  backup: z.boolean().default(true),
  indent: z.number().int().min(0).max(8).default(2),
  eol: z.enum(['lf', 'crlf', 'auto']).default('auto'),
  keepOrder: z.boolean().default(true),
  sortKeys: z.boolean().default(false),
  trailingNewline: z.boolean().default(true),
});

export const privacySchema = z.object({
  /** Refuse any engine that talks to the network. */
  offlineOnly: z.boolean().default(false),
  /** Explicit opt-in for remote engines (defence in depth). */
  allowNetwork: z.boolean().default(true),
  /** Mask these patterns before sending text to a remote engine. */
  redact: z.array(z.enum(['email', 'url', 'phone', 'credit-card', 'ipv4'])).default([]),
  /** Informational: LingoFlow never phones home. */
  telemetry: z.literal(false).default(false),
});

export const reportSchema = z.object({
  dir: z.string().default('.lingoflow/report'),
  title: z.string().default('LingoFlow Report'),
  includePreview: z.boolean().default(true),
  theme: z.enum(['auto', 'light', 'dark']).default('auto'),
  failOn: z.enum(['error', 'warn', 'none']).default('error'),
});

export const configSchema = z.object({
  $schema: z.string().optional(),
  sourceLocale: z.string().default('en'),
  locales: z.array(z.string()).default([]),
  catalogs: z.array(catalogSchema).prefault([{ path: 'locales/{locale}.json', format: 'auto' }]),
  code: codeSchema.prefault({}),
  translation: translationSchema.prefault({}),
  length: lengthSchema.prefault({}),
  validate: validateSchema.prefault({}),
  write: writeSchema.prefault({}),
  privacy: privacySchema.prefault({}),
  report: reportSchema.prefault({}),
  workDir: z.string().default('.lingoflow'),
});

export type RawConfig = z.input<typeof configSchema>;
export type ResolvedConfig = z.output<typeof configSchema>;

/** Overrides accepted from the CLI/API: nested objects may be partially set. */
export type DeepPartial<T> = {
  [K in keyof T]?: T[K] extends readonly unknown[] ? T[K] : T[K] extends object ? DeepPartial<T[K]> : T[K];
};

export interface LoadedConfig {
  config: ResolvedConfig;
  path: string | null;
  rootDir: string;
}

export function parseConfig(input: unknown): ResolvedConfig {
  const result = configSchema.safeParse(input ?? {});
  if (!result.success) {
    const issues = result.error.issues
      .slice(0, 12)
      .map((issue) => `  - ${issue.path.join('.') || '<root>'}: ${issue.message}`)
      .join('\n');
    throw new Error(`Invalid lingoflow config:\n${issues}`);
  }
  return result.data;
}
