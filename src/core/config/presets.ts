import type { RawConfig } from './schema';

export interface Preset {
  id: string;
  title: string;
  description: string;
  config: RawConfig;
}

/**
 * Stack presets keep `lingoflow init` to one question.
 * Every preset stays fully editable in lingoflow.config.json afterwards.
 */
export const PRESETS: Preset[] = [
  {
    id: 'react',
    title: 'React / Next.js (i18next)',
    description: 'JSON catalogs, t() / <Trans>, JSX rewrite support.',
    config: {
      sourceLocale: 'en',
      locales: ['en', 'zh-CN', 'ja', 'de'],
      catalogs: [{ path: 'locales/{locale}.json', format: 'auto' }],
      code: {
        include: ['src/**/*.{ts,tsx,js,jsx}', 'app/**/*.{ts,tsx}', 'pages/**/*.{ts,tsx}'],
        calls: ['t', 'i18n.t', 'i18next.t', '$t', 'formatMessage', 'intl.formatMessage'],
        components: ['Trans', 'FormattedMessage'],
        hardcoded: { mode: 'report', attributes: ['placeholder', 'title', 'aria-label', 'alt', 'label'] },
      },
      translation: {
        engine: 'offline',
        policy: 'missing',
        glossary: { file: 'lingoflow.glossary.json', enforce: true },
        style: { file: 'lingoflow.style.json', enforce: true },
      },
      length: {
        rules: [
          { match: 'cta.*', max: 18, hard: true, widget: 'button' },
          { match: '*.tooltip', max: 120, widget: 'tooltip' },
          { match: '*.title', max: 32, widget: 'heading' },
        ],
      },
      report: { title: 'LingoFlow · React i18n report', failOn: 'error' },
    },
  },
  {
    id: 'vue',
    title: 'Vue / Nuxt (vue-i18n)',
    description: 'JSON catalogs, $t / t / <i18n-t> usage.',
    config: {
      sourceLocale: 'en',
      locales: ['en', 'zh-CN', 'ja'],
      catalogs: [{ path: 'locales/{locale}.json', format: 'auto' }],
      code: {
        include: ['src/**/*.{ts,js,vue}', 'pages/**/*.vue', 'components/**/*.vue'],
        calls: ['$t', 't', '$tc', '$te', 'i18n.t'],
        components: ['i18n-t', 'I18nT'],
      },
      translation: { engine: 'offline', glossary: {}, style: {} },
    },
  },
  {
    id: 'flutter',
    title: 'Flutter (ARB)',
    description: 'ARB catalogs with @-metadata, ICU plurals included.',
    config: {
      sourceLocale: 'en',
      locales: ['en', 'zh-CN', 'ja', 'de'],
      catalogs: [{ path: 'lib/l10n/app_{locale}.arb', format: 'arb' }],
      code: {
        include: ['lib/**/*.dart'],
        calls: ['AppLocalizations.of(context)', 'context.l10n', 'S.of(context)'],
        components: [],
      },
      translation: { engine: 'offline', glossary: {}, style: {} },
      length: { default: { max: 60, min: 0, hard: false, widget: 'label' } },
    },
  },
  {
    id: 'ios',
    title: 'iOS / macOS (.strings)',
    description: 'Localizable.strings with optional comments.',
    config: {
      sourceLocale: 'en',
      locales: ['en', 'zh-Hans', 'ja'],
      catalogs: [{ path: '{locale}.lproj/Localizable.strings', format: 'strings' }],
      code: { include: ['**/*.swift', '**/*.m', '**/*.mm'], calls: ['NSLocalizedString', 'String(localized:)', 'LocalizedStringKey'], components: [] },
      translation: { engine: 'offline', glossary: {}, style: {} },
    },
  },
  {
    id: 'web',
    title: 'Plain web / static site',
    description: 'JSON or YAML catalogs fetched at runtime.',
    config: {
      sourceLocale: 'en',
      locales: ['en', 'zh-CN', 'ja', 'fr', 'de', 'es'],
      catalogs: [{ path: 'i18n/{locale}.json', format: 'auto' }],
      code: { include: ['src/**/*.{ts,js,tsx,jsx,html}', 'public/**/*.js'], calls: ['t', 'translate', 'i18n.t'], components: [] },
      translation: { engine: 'offline', glossary: {}, style: {} },
    },
  },
  {
    id: 'gettext',
    title: 'Gettext (.po) — Django / Rails / C',
    description: 'PO catalogs, msgctxt and plural forms.',
    config: {
      sourceLocale: 'en',
      locales: ['en', 'zh_CN', 'ja', 'de'],
      catalogs: [{ path: 'locale/{locale}/LC_MESSAGES/messages.po', format: 'po', nesting: false }],
      code: { include: ['**/*.py', '**/*.rb', '**/*.c', '**/*.cpp'], calls: ['_', 'gettext', 'ngettext', 'pgettext'], components: [] },
      translation: { engine: 'offline', glossary: {}, style: {} },
    },
  },
  {
    id: 'minimal',
    title: 'Minimal',
    description: 'Just a source locale and one JSON catalog; add the rest later.',
    config: {
      sourceLocale: 'en',
      locales: ['en', 'zh-CN'],
      catalogs: [{ path: 'locales/{locale}.json', format: 'json' }],
      code: { include: ['src/**/*.{ts,tsx,js,jsx}'], calls: ['t', 'i18n.t'], components: [] },
      translation: { engine: 'offline', glossary: {}, style: {} },
    },
  },
];

export function findPreset(id: string): Preset | undefined {
  return PRESETS.find((preset) => preset.id === id.toLowerCase());
}
