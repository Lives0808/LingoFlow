<div align="center">

# LingoFlow

**代码感知的 i18n 流水线：直读源码 · 多格式文案自动翻译 · 多语对齐 · 长度校验 · 回写代码 · 规则沉淀**

*Code-aware i18n pipeline: read your source, translate every locale file, align locales, validate UI length, write back, and grow a private rule base.*

[![CI](https://github.com/Lives0808/LingoFlow/actions/workflows/ci.yml/badge.svg)](https://github.com/Lives0808/LingoFlow/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Lives0808/LingoFlow)](https://github.com/Lives0808/LingoFlow/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Node](https://img.shields.io/badge/node-%3E%3D20.11-brightgreen)

</div>

---

## 一句话

`lingoflow sync` —— 扫描代码 → 抽取硬编码文案 → 翻译新增词条 → 对齐多语言 → 校验长度/占位符/ICU/排版 → 自动修复 → 回写代码与语言文件 → 沉淀私有记忆库与规则 → 生成可视化报告。**一条命令，新增方案全流程闭环。**

```bash
lingoflow sync                                 # 全局安装后
node lingoflow.mjs sync                        # 单文件版
npx --yes ./lingoflow-0.1.0.tgz sync           # 直接用 Release 里的 npm 包
```

![workflow](https://img.shields.io/badge/scan%20→%20translate%20→%20check%20→%20fix%20→%20write%20back%20→%20learn-one%20command-4f46e5)

---

## 为什么需要它

| 痛点 | LingoFlow 的做法 |
| --- | --- |
| 语言文件散落在 JSON / YAML / PO / ARB / .strings / CSV / TS 里 | 8 种格式统一读写，注释与键序原样保留 |
| 新方案上线前没人知道文案会不会溢出按钮 | 按字体度量估算 px / 字符数，内置 14 类控件预算，模拟 UI 逐条渲染并真实测量 |
| 翻译完了才发现占位符丢了、ICU 复数写错 | 占位符集合比对、ICU 结构校验、CLDR 复数类别校验、HTML 标签配对 |
| 同一个英文词被翻成两种说法 | 一致性校验 + 术语表强制生效 |
| 人工改过的翻译被下一次机器翻译覆盖 | 审批/冻结机制（防返工），冲突显式告警 |
| 每次都要重复纠正同类错误 | 从审校差异中**自动学习修正规则**并复用到新文案 |
| 担心隐私 / 断网 / 内网 | 内置离线引擎，可选本地模型（Argos）、本地 LLM（Ollama/vLLM）、或自定义 API |

---

## 安装

三种方式，任选其一（全部不依赖网络服务）：

```bash
# 1) 单文件版（推荐，零依赖，一条 curl 即可）
curl -fsSL https://github.com/Lives0808/LingoFlow/releases/latest/download/lingoflow.mjs -o lingoflow.mjs
node lingoflow.mjs --help

# 2) 从 Release 的 npm 包全局安装
curl -fsSLO https://github.com/Lives0808/LingoFlow/releases/latest/download/lingoflow-0.1.0.tgz
npm install -g ./lingoflow-0.1.0.tgz
lingoflow --help

# 3) 从源码构建（贡献者）
git clone https://github.com/Lives0808/LingoFlow.git && cd LingoFlow
npm install && npm run build
node bin/lingoflow.mjs --help
```

要求 Node.js ≥ 20.11（Node 22.5+ 会自动启用 SQLite 记忆库，低版本自动降级为 JSONL）。
> 想用 `npx lingoflow`？包已准备好（`lingoflow-0.1.0.tgz`），发布到 npm registry 后即可直接 `npx`。

---

## 快速开始

```bash
cd your-app
lingoflow init --preset react        # 生成配置 + 术语表 + 风格规则 + 语言文件
lingoflow doctor                     # 检查环境、语言文件、引擎可用性
lingoflow scan                       # 代码里用了哪些 key？哪些是硬编码？
lingoflow sync --dry-run             # 预览所有改动，不落盘
lingoflow sync                       # 一键：翻译 + 校验 + 修复 + 回写 + 学习
lingoflow preview --serve            # 本地打开 UI 适配预览（真实 DOM 溢出检测）
```

`init` 支持预设：`react` / `vue` / `flutter` / `ios` / `gettext` / `web` / `minimal`。

### 命令总览

| 命令 | 作用 |
| --- | --- |
| `sync` | 一键全流程（扫描/抽取/翻译/修复/校验/回写/学习/报告） |
| `translate` | 只做翻译与回写 |
| `check` | 只做校验，`--fail-on warn` 可直接做 CI 门禁 |
| `fix` | 应用确定性修复（换行、标点、间距、占位符、超长适配） |
| `align` | 多语对齐：报告缺漏、`--fill` 补齐、`--prune` 清理 |
| `scan` | 源码扫描：缺失 key、未使用 key、动态 key、硬编码文案 |
| `extract` | 把硬编码文案换成 `t('key')` 并写入源语言文件 |
| `preview` | 生成 UI 适配预览，`--serve` 本地实时刷新 |
| `report` | 输出 `index.html` / `report.json` / `report.md` / `report.sarif` |
| `memory` | 私有翻译记忆库：list / add / approve / freeze / stats / export / import |
| `rules` | 规则库：glossary / correction / style / dnt，支持 `learn` 与 `export` |
| `engines` | 查看各引擎就绪状态 |
| `export` / `import` | XLIFF / CSV / JSON 交付给译者并回灌 |
| `watch` | 监听源码与语言文件，自动增量同步 |

---

## 核心能力

### 1. 直读代码，双向回写

- 识别 `t()`、`i18n.t()`、`$t()`、`formatMessage({ id })`、`<Trans i18nKey>`、`<FormattedMessage id>` 等调用（可配置）
- 扫描 JSX 文本、`placeholder` / `title` / `aria-label` 等 UI 属性里的**硬编码文案**
- `lingoflow extract --write` 直接改成 `t('key')` 并写入源语言文件，**从源头止血**

### 2. 多格式语言文件

`JSON / JSON5 / YAML / .properties / .po (gettext) / .arb (Flutter) / .strings (Apple) / CSV / TS-JS 模块`

读写均**保留注释、键顺序、缩进、换行风格**；多语言 CSV 只更新本语言那一列。

### 3. 翻译引擎：隐私可选

| 引擎 | 位置 | 说明 |
| --- | --- | --- |
| `offline` | 本地 | 内置引擎：术语表 + 记忆库 + 种子词典，零网络，断网可用 |
| `pseudo` | 本地 | 伪本地化（+40% 膨胀、重音字符），**上线前压测布局** |
| `argos` | 本地 | Argos Translate 本地神经模型，纯离线 |
| `openai` | 本地/远程 | 任意 OpenAI 兼容端点；指向 `localhost` 即为本地 LLM（Ollama / LM Studio / vLLM） |
| `libretranslate` | 自托管 | 内网部署，数据不出内网 |
| `deepl` | 远程 | 欧洲语系质量最佳 |
| `custom` | 任意 | 模板化 HTTP，接自家翻译服务 |

`privacy.offlineOnly: true` 会直接拒绝任何联网引擎，从机制上保证数据不外发；`privacy.redact` 可在发送前屏蔽邮箱/URL/电话等。

### 4. 长度与 UI 适配合规

- 按字体（family/size/weight）估算 **px 宽度**与字符数，内置 `button / label / input / heading / nav / menu / list / tooltip / toast / badge / tab …` 预算
- 规则匹配任意粒度：`cta.*`、`*.tooltip`、`key.title`
- 语言膨胀预算：`de: 1.4`、`zh-CN: 0.7`…
- 超长时按策略自动适配：`reflow → punctuation → abbreviate → shorten`，`hard` 预算仍超标则报错交人工
- 报告里用**真实 DOM 测量** `scrollWidth > clientWidth`，把"到底会不会溢出"变成事实
- 伪本地化预览：**翻译之前**就能看到布局问题

### 5. 校验矩阵（CI 可门禁）

占位符缺失/多余 · ICU 结构 · CLDR 复数类别 · HTML 标签配对与属性 · 首尾空白 · 重复空格 · 换行数量 · 省略号 · CJK 全角标点 · CJK/拉丁空格 · 脚本错配（德语里混中文） · 术语未生效 · 免翻译词被改 · 同源不同译 · 禁用词 · 大小写风格 · 长度/膨胀/换行 · 冻结保护 · key 未定义/未使用。

### 6. 沉淀私有数据库与规则（防返工）

- **翻译记忆库（TM）**：SQLite（`node:sqlite`）或 JSONL，精确 + 模糊匹配；记录引擎、置信度、来源 key、审批与冻结状态
- **术语表（glossary）**、**风格规则（style）**、**修正规则（correction）**、**免翻译（dnt）**统一存入同一私有库，也可导出为可进 Git 的 `lingoflow.glossary.json` / `lingoflow.style.json`
- `lingoflow rules learn`：从**审校差异**中学习修正规则（例：把人改过的 `登入 → 登录` 变成规则），下次自动应用
- `lingoflow glossary learn`：从历史翻译中挖掘高频一致的术语对
- **审批/冻结**：`memory approve --freeze` 之后，任何自动流程都不会覆盖（连 `--force` 也不行），仅在发现差异时显式告警

### 7. 报告与集成

`index.html`（可视化，含模拟 UI 与伪本地化）/ `report.json` / `report.md` / `report.sarif`（GitHub Code Scanning 直接标注到文件）。

---

## 配置示例

```jsonc
{
  "$schema": "https://raw.githubusercontent.com/Lives0808/LingoFlow/main/schema/lingoflow.schema.json",
  "sourceLocale": "en",
  "locales": ["en", "zh-CN", "ja", "de", "en-XA"],
  "catalogs": [{ "path": "locales/{locale}.json", "format": "auto" }],
  "translation": {
    "engine": "offline",
    "policy": "missing",
    "memory": { "driver": "auto", "fuzzyThreshold": 0.75, "freezeApproved": true, "learnFromEdits": true },
    "glossary": { "file": "lingoflow.glossary.json", "mode": "auto" }
  },
  "length": {
    "unit": "both",
    "font": { "family": "system-ui", "size": 14, "weight": 400 },
    "default": { "max": 60, "widget": "label" },
    "rules": [{ "match": "cta.*", "max": 18, "hard": true, "widget": "button" }],
    "expansion": { "de": 1.4, "zh-CN": 0.7 }
  },
  "validate": { "placeholders": true, "icu": true, "tags": true, "cjk": true, "consistency": true },
  "privacy": { "offlineOnly": true, "allowNetwork": false },
  "report": { "failOn": "error" }
}
```

完整字段说明见 [`docs/configuration.md`](docs/configuration.md)。

---

## CI 集成

```yaml
- uses: actions/setup-node@v4
  with: { node-version: 22 }
- run: node lingoflow.mjs check --fail-on warn
- uses: github/codeql-action/upload-sarif@v3
  with: { sarif_file: .lingoflow/report/report.sarif }
```

`--fail-on error|warn|none` 控制退出码；SARIF 让问题直接出现在 PR 的 Files changed 里。

---

## 安全与隐私

- 默认 `offline`：**任何内容都不离开本机**
- 未配置 `privacy.allowNetwork` 或开启 `offlineOnly` 时，联网引擎会被直接拒绝
- API Key 只从环境变量读取，**从不写入磁盘**
- 无遥测、无回传、无远端依赖；报告是本地静态文件

---

## 架构

```
src/
  core/
    catalog/   8 种语言文件格式读写（注释/键序无损）
    code/      JS/TS/JSX 词法分析、i18n 调用扫描、代码回写
    translate/ 引擎适配层 + 编排器（记忆库 → 模糊 → 引擎 → 术语 → 修复 → 校验）
    validate/  占位符 / ICU / 标签 / 排版 / 长度 / 一致性
    fix/       换行、标点、CJK 排版、缩写适配
    length/    字体度量与预算
    rules/     术语、风格、修正规则与自动学习
    tm/        SQLite / JSONL 双驱动记忆库
  pipeline/    scan · extract · plan · sync · check · align
  report/      HTML / JSON / Markdown / SARIF
  commands/    CLI 命令层
```

---

## English

**LingoFlow is a code-aware i18n pipeline.** Point it at a repository and it will:

1. **Read your code** — find `t()` / `<Trans>` usages, missing keys, unused keys and hardcoded UI copy.
2. **Translate every locale file** — JSON, JSON5, YAML, `.properties`, gettext `.po`, Flutter `.arb`, Apple `.strings`, CSV and TS/JS modules, with comments and key order preserved.
3. **Align locales** — report and fill missing keys, prune stale ones, keep one canonical order.
4. **Validate before it hurts** — placeholders, ICU structure, CLDR plural categories, HTML tags, CJK typography, terminology consistency and **UI length budgets measured in pixels** for 14 widget types.
5. **Write back** — to locale files *and* to your source code (`lingoflow extract --write` turns hardcoded strings into keys).
6. **Grow a private knowledge base** — translation memory (SQLite or JSONL), glossary, style rules and **learned correction rules** derived from past reviewer edits, plus approve/freeze protection so approved wording is never overwritten.

```bash
lingoflow init --preset react && lingoflow sync
```

Privacy first: the built-in engine works offline, local models (Argos), local LLMs (any OpenAI-compatible endpoint on `localhost`), self-hosted LibreTranslate, or DeepL/custom HTTP when you explicitly allow network access. No telemetry.

Docs: [`docs/engines.md`](docs/engines.md) · [`docs/configuration.md`](docs/configuration.md) · [`docs/workflow.md`](docs/workflow.md)

---

## License

MIT © [Lives0808](https://github.com/Lives0808)
