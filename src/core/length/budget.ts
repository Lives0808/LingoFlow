import type { ResolvedConfig } from '../config/schema';
import type { CatalogEntry, LengthBudget } from '../types';
import { keyMatches } from '../catalog/keys';

/** Container presets used for the simulated UI in reports and previews. */
export interface WidgetPreset {
  name: string;
  /** Container width in px at the configured font size. */
  px: number;
  /** Recommended character budget (px / average char width). */
  chars: number;
  label: string;
}

export const WIDGET_PRESETS: Record<string, WidgetPreset> = {
  button: { name: 'button', px: 96, chars: 18, label: 'Button' },
  label: { name: 'label', px: 260, chars: 60, label: 'Form label' },
  input: { name: 'input', px: 180, chars: 40, label: 'Input placeholder' },
  heading: { name: 'heading', px: 220, chars: 32, label: 'Heading' },
  title: { name: 'title', px: 220, chars: 32, label: 'Title' },
  nav: { name: 'nav', px: 128, chars: 24, label: 'Navigation item' },
  menu: { name: 'menu', px: 160, chars: 28, label: 'Menu item' },
  list: { name: 'list', px: 280, chars: 48, label: 'List row' },
  tooltip: { name: 'tooltip', px: 640, chars: 120, label: 'Tooltip' },
  toast: { name: 'toast', px: 420, chars: 80, label: 'Toast' },
  description: { name: 'description', px: 640, chars: 120, label: 'Description' },
  text: { name: 'text', px: 640, chars: 160, label: 'Body text' },
  badge: { name: 'badge', px: 64, chars: 12, label: 'Badge' },
  tab: { name: 'tab', px: 110, chars: 20, label: 'Tab' },
};

export function widgetFor(name: string | undefined): WidgetPreset {
  if (!name) return WIDGET_PRESETS.label as WidgetPreset;
  return WIDGET_PRESETS[name] ?? WIDGET_PRESETS.label ?? { name: 'label', px: 260, chars: 60, label: 'Label' };
}

export interface ResolvedBudget extends LengthBudget {
  /** Container width when the unit is px. */
  containerPx: number;
  widget: string;
  /** Where the budget came from: config | rule:* | comment | default. */
  origin: string;
}

/**
 * Budget resolution order (most specific wins):
 *   1. translator comment `{maxLength: 12}` in the source catalog
 *   2. length.rule matching the key
 *   3. length.default
 */
export function resolveBudget(config: ResolvedConfig, key: string, entry?: CatalogEntry): ResolvedBudget {
  const widgetDefault = widgetFor(config.length.default.widget);
  if (entry?.maxLength && entry.maxLength > 0) {
    return {
      min: config.length.default.min,
      max: entry.maxLength,
      hard: config.length.default.hard,
      unit: 'chars',
      containerPx: widgetDefault.px,
      widget: config.length.default.widget,
      origin: 'comment',
    };
  }
  for (const rule of config.length.rules) {
    if (!keyMatches(rule.match, key)) continue;
    const widget = widgetFor(rule.widget ?? config.length.default.widget);
    return {
      min: rule.min ?? config.length.default.min,
      max: rule.max ?? config.length.default.max,
      hard: rule.hard ?? config.length.default.hard,
      unit: config.length.unit === 'both' ? 'chars' : config.length.unit,
      containerPx: widget.px,
      widget: widget.name,
      origin: `rule:${rule.match}`,
    };
  }
  return {
    min: config.length.default.min,
    max: config.length.default.max,
    hard: config.length.default.hard,
    unit: config.length.unit === 'both' ? 'chars' : config.length.unit,
    containerPx: widgetDefault.px,
    widget: config.length.default.widget,
    origin: 'default',
  };
}

/** Picks the widget used to simulate the UI in the preview/report. */
export function widgetForBudget(budget: ResolvedBudget): WidgetPreset {
  return widgetFor(budget.widget);
}

export function expansionLimit(config: ResolvedConfig, locale: string): number {
  const direct = config.length.expansion[locale];
  if (direct) return direct;
  const base = locale.replace(/_/gu, '-').split('-')[0] as string;
  return config.length.expansion[base] ?? 1.3;
}
