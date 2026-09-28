# 配置参考 · Configuration reference

配置文件按顺序查找：`lingoflow.config.json` → `.jsonc` → `.yaml` → `.yml`（可从子目录向上查找）。
全部字段都有默认值，JSONC 注释与尾随逗号都允许。

JSON Schema：<https://raw.githubusercontent.com/Lives0808/LingoFlow/main/schema/lingoflow.schema.json>

---

## 顶层

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `sourceLocale` | `en` | 源语言。默认会被加入 `locales`。 |
| `locales` | `[]` | 所有语言，含源语言。 |
| `workDir` | `.lingoflow` | 运行时目录（记忆库、报告、缓存、备份）。 |
| `catalogs[]` | `locales/{locale}.json` | 语言文件定义，见下。 |

### `catalogs[]`

```jsonc
{
  "path": "locales/{locale}.json",  // {locale} 会被替换
  "format": "auto",                 // auto | json | yaml | properties | po | arb | strings | csv | tsjs
  "locale": "en",                   // 可选：该文件固定属于某语言
  "nesting": true,                  // 点号键是否写成嵌套对象
  "keySeparator": ".",
  "keepOrder": true
}
```

一个项目可以声明多个 catalog（例如同时存在 JSON 与 PO）。同一个 `catalogs[]` 条目在所有语言之间共享路径模板，因此 `locales/en.json` 会自动对应 `locales/zh-CN.json`。

---

## `code` —— 代码扫描

```jsonc
{
  "include": ["src/**/*.{ts,tsx,js,jsx,vue,svelte,astro}"],
  "exclude": ["**/node_modules/**", "**/dist/**"],
  "calls": ["t", "i18n.t", "i18next.t", "$t", "translate", "formatMessage", "intl.formatMessage"],
  "components": ["Trans", "FormattedMessage", "I18n", "T"],
  "hardcoded": {
    "mode": "report",              // off | report | extract
    "attributes": ["placeholder", "title", "label", "alt", "aria-label"],
    "minWords": 2,
    "minConfidence": 0.75,
    "ignore": ["^TODO", "^http"],
    "rewrite": {
      "enabled": true,
      "callTemplate": "t('{key}')",
      "importStatement": "import { t } from '@/i18n';",  // 可选
      "jsxBraces": true
    }
  }
}
```

- `formatMessage({ id: 'x' })`、`t(key)`、模板字符串中的 `${}` 会被识别为**动态 key** 并单独列出。
- `mode: "extract"` 等价于每次 `sync` 自动执行 `extract --write`。
- 扫描基于内置词法分析器（不是正则），能正确跳过注释、正则字面量、字符串内的括号。

---

## `translation`

```jsonc
{
  "engine": "offline",                  // offline | pseudo | argos | libretranslate | deepl | openai | custom
  "fallback": ["offline"],              // 主引擎失败时的兜底顺序
  "policy": "missing",                  // missing | empty | untranslated | stale | all
  "concurrency": 4,
  "timeoutMs": 60000,
  "retries": 2,
  "maxItems": 0,                        // 0 = 不限制；>0 用于控制成本
  "keepPlaceholders": true,
  "memory": {
    "path": ".lingoflow/memory",
    "driver": "auto",                   // auto | sqlite | jsonl
    "fuzzyThreshold": 0.72,
    "fuzzyAutofill": true,
    "freezeApproved": true,
    "learnFromEdits": true,
    "excludePatterns": []               // 命中则永不入库（隐私）
  },
  "glossary": { "file": "lingoflow.glossary.json", "enforce": true, "matchCase": false, "mode": "auto" },
  "style": { "file": "lingoflow.style.json", "enforce": true },
  "provider": { /* 见 docs/engines.md */ }
}
```

### `policy` 语义

| 值 | 行为 |
| --- | --- |
| `missing` | 只处理缺失 key（默认，最省） |
| `empty` | 处理缺失 + 空字符串 |
| `untranslated` | 额外处理"与源语言完全相同"的条目 |
| `stale` | 额外处理"记忆库中没有记录/与当前值不一致"的条目 |
| `all` | 全部重译（冻结条目仍受保护） |

### `glossary.mode`

| 值 | 行为 |
| --- | --- |
| `auto` | LLM 引擎走提示词，机器翻译引擎走占位符保护（推荐） |
| `protect` | 全部用占位符保护，术语 100% 生效 |
| `prompt` | 只提示不强制，随后校验并报告 |
| `enforce` | 完全自由翻译，事后替换术语 |

---

## `length` —— 长度与 UI 适配

```jsonc
{
  "enabled": true,
  "unit": "both",                       // px | chars | both
  "font": { "family": "system-ui", "size": 14, "weight": 400, "letterSpacing": 0 },
  "default": { "max": 60, "min": 0, "hard": false, "widget": "label" },
  "rules": [
    { "match": "cta.*",     "max": 18,  "hard": true, "widget": "button" },
    { "match": "nav.*",     "max": 24,  "widget": "nav" },
    { "match": "*.tooltip", "max": 120, "widget": "tooltip" },
    { "match": "*.title",   "max": 32,  "widget": "heading" }
  ],
  "expansion": { "de": 1.4, "fr": 1.35, "zh-CN": 0.7, "ja": 0.8 },
  "autofix": { "enabled": true, "strategies": ["reflow", "punctuation", "abbreviate", "shorten"], "maxRounds": 2 }
}
```

- `match` 支持 glob：`*` 匹配单层，`**` 跨层。
- `hard: true` 时超长报 **error**（CI 可阻断）；否则为 warning。
- 源语言文件里写 `{maxLength: 12}` 形式注释可覆盖预算（JSON 用 `// 注释` 写在键上方）。
- 内置控件预设：`button label input heading title nav menu list tooltip toast description text badge tab`。
- 伪本地化语言（`en-XA`、`qps-Ploc`…）自动使用 `pseudo` 引擎。

---

## `validate`

```jsonc
{
  "placeholders": true, "icu": true, "tags": true, "whitespace": true,
  "punctuation": true, "ellipsis": true, "cjk": true, "script": true,
  "dnt": true, "glossary": true, "consistency": true, "sameSourceSameTarget": true,
  "forbidden": ["lorem ipsum"],
  "severity": { "length.expansion": "info", "cjk.space": "info", "script.mismatch": "warn" },
  "ignore": { "zh-CN": ["debug.*"], "*": ["dev.*"] }
}
```

`severity` 可把任意 issue code 调成 `error|warn|info`；`ignore` 按语言忽略 key 模式（`*` 代表所有语言）。

---

## `write`

```jsonc
{ "atomic": true, "backup": true, "indent": 2, "eol": "auto", "keepOrder": true, "sortKeys": false, "trailingNewline": true }
```

- 原子写入（临时文件 + rename），中断不会产生半截文件。
- `eol: auto` 跟随文件原有风格；`backup: true` 覆盖前写 `<file>.bak`。
- 写入前做冲突检测：文件被外部改过则拒绝覆盖并提示重跑。

---

## `privacy`

```jsonc
{ "offlineOnly": false, "allowNetwork": true, "redact": ["email", "url", "phone", "credit-card", "ipv4"], "telemetry": false }
```

`telemetry` 固定为 `false`（Schema 层面禁止开启）。

---

## `report`

```jsonc
{ "dir": ".lingoflow/report", "title": "LingoFlow Report", "includePreview": true, "theme": "auto", "failOn": "error" }
```

`failOn` 决定 `check` / `sync` 的退出码：`error`（默认）| `warn` | `none`。
