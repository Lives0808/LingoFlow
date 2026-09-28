# 工作流 · Workflows

本文覆盖四条主线：**一键新增方案**、**UI 长度与样式前置校验**、**规则沉淀与防返工**、**CI 集成**。

---

## 1. 一键新增方案（新功能/新语言上线）

```bash
lingoflow sync
```

内部顺序（每一步都有对应的独立命令）：

```
① scan        源码扫描：用了哪些 key、缺哪些 key、哪些是硬编码
② extract     硬编码文案 → t('key')，写回源码 + 源语言文件（--extract）
③ align       多语对齐：补缺、清理、统一顺序
④ translate   记忆库精确 → 模糊复用 → 引擎翻译（术语保护 + 缓存）
⑤ fix         换行、标点、CJK 排版、占位符修复、超长缩写适配
⑥ validate    占位符 / ICU / 标签 / 排版 / 一致性 / 长度 / UI 适配
⑦ write back  原子回写语言文件（含 .bak 备份与冲突检测）
⑧ learn       审校差异 → 修正规则；历史文案 → 术语与风格规则
⑨ report      HTML / JSON / Markdown / SARIF
```

安全默认：

```bash
lingoflow sync --dry-run          # 全流程演练，不落盘
lingoflow sync --key cta.save,app.title   # 只处理指定 key
lingoflow sync --locales zh-CN    # 只处理指定语言
```

---

## 2. UI 长度与样式前置校验

### 2.1 配置预算

```jsonc
"length": {
  "unit": "both",
  "font": { "size": 14, "weight": 400 },
  "rules": [
    { "match": "cta.*",      "max": 18,  "hard": true, "widget": "button" },
    { "match": "*.tooltip",  "max": 120, "widget": "tooltip" },
    { "match": "nav.*",      "max": 24,  "widget": "nav" }
  ],
  "expansion": { "de": 1.4, "zh-CN": 0.7 }
}
```

`lingoflow check` 会同时报出：字符数、估算 px、控件宽度、预计行数、相对源文案的膨胀比。

### 2.2 在浏览器里真实测量

```bash
lingoflow preview --serve
# ➜ http://127.0.0.1:4173/
```

- 每个词条按所属控件宽度真实渲染，页面加载时执行 `scrollWidth > clientWidth` 检测并标红
- 顶部 `pseudo` 按钮一键切换伪本地化文本（+40%）
- 编辑语言文件或源码后页面自动刷新
- 所有计算都在本地完成，报告是一个静态 HTML

### 2.3 伪本地化压测

在 `locales` 中加入 `en-XA`，`sync` 会用 `pseudo` 引擎生成重音 + 膨胀文本：

```jsonc
{ "locales": ["en", "zh-CN", "ja", "en-XA"] }
```

**在真实翻译开始之前**就能发现按钮截断、导航换行、tooltip 溢出。

### 2.4 风格统一

`lingoflow.style.json`（或直接写进数据库）：

```json
{
  "rules": [
    { "name": "punctuation.trailing", "value": "never", "locale": "zh-CN" },
    { "name": "cjk.fullwidth", "value": "true", "locale": "zh-CN" },
    { "name": "cjk.latin-space", "value": "true", "locale": "zh-CN" },
    { "name": "formality", "value": "formal", "locale": "zh-CN" },
    { "name": "punctuation.ellipsis", "value": "unicode", "locale": "zh-CN" },
    { "name": "capitalization", "value": "sentence", "locale": "en" }
  ]
}
```

可用风格键：`punctuation.trailing`(`never|always`) · `punctuation.ellipsis`(`unicode|ascii`) · `capitalization`(`sentence|title|lower`) · `cjk.fullwidth` · `cjk.latin-space` · `formality`(`formal|informal`)。
风格既影响**校验**也影响**自动修复**，所以新老文案会保持一致。

---

## 3. 规则沉淀与防返工

### 3.1 审校一次，永久生效

```bash
# 1) 第一次同步：机器输出 + 原文一起存入记忆库（suggestions）
lingoflow sync

# 2) 译者在语言文件里把「登入」改成「登录」（多处）

# 3) 学习审校差异
lingoflow rules learn
# ✓ learned 0 glossary · 2 correction · 1 style rule(s)

# 4) 之后所有新文案自动按同样方式修正
lingoflow sync
```

规则默认在**同一修正出现 ≥ 2 次**时才启用（1 次会以 disabled 形式记录，便于人工确认）：

```bash
lingoflow rules list --kind correction
lingoflow rules add --kind correction --pattern '"old"' --value '"new"' --locale zh-CN
lingoflow rules rm --id 12
```

### 3.2 术语自动挖掘

```bash
lingoflow glossary learn            # 从历史翻译中找高频一致术语对
lingoflow glossary add Settings 设置 --locale zh-CN
lingoflow glossary list
```

挖掘条件：源 n-gram 出现 ≥ 3 次且目标译法一致率 ≥ 70%。中文按 2–4 字滑窗，英文按 1–3 词滑窗。

### 3.3 防返工：审批与冻结

```bash
lingoflow memory approve --id 42 --freeze       # 定稿并锁定
lingoflow memory approve --src "Save changes" --locale zh-CN --freeze
lingoflow memory approve --id 42 --unfreeze     # 需要再改时解锁
```

冻结后的条目**任何自动流程都不会覆盖**（包括 `sync --force`）。若发现语言文件里的值与已批准值不一致，报告会给出 `frozen.protected` 告警，而不是静默改写。

### 3.4 规则进 Git

```bash
lingoflow rules export     # 写出 glossary / style / corrections 文件
```

`lingoflow.glossary.json`、`lingoflow.style.json`、`.lingoflow/corrections.json` 都可以提交到仓库，团队共享；数据库本身（`.lingoflow/memory`）保持私有、不进 Git。

---

## 4. CI 集成

```yaml
name: i18n
on: [pull_request]

jobs:
  lingoflow:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 22 }
      - name: Install LingoFlow
        run: |
          curl -fsSL -o lingoflow.mjs https://github.com/Lives0808/LingoFlow/releases/latest/download/lingoflow.mjs
      - run: node lingoflow.mjs check --fail-on warn
      - if: always()
        uses: actions/upload-artifact@v4
        with:
          name: lingoflow-report
          path: .lingoflow/report/
      - if: always()
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: .lingoflow/report/report.sarif
```

- `--fail-on error`：只有硬错误（占位符丢失、ICU 破损、硬长度超限、key 未定义）阻断
- `--fail-on warn`：更严格，兼容性/风格类警告也阻断
- SARIF 会把问题**精确标注到具体语言文件**，直接出现在 PR 的 Files changed

只校验本次改动的 key：

```bash
lingoflow check --locales zh-CN
```

---

## 5. 译者交付闭环

```bash
lingoflow export --format xliff --out handoff/       # 交付
# …译者用 Trados / memoQ / 在线 TMS 处理…
lingoflow import --in handoff/messages.zh-CN.xlf --write
lingoflow memory import --in approved-zh.json --approved
lingoflow check
```

CSV 导出包含 `key,source,target,comment,maxLength,status` 六列，方便用表格协作。

---

## 6. 常见问题

**Q：会不会覆盖同事的修改？**
A：不会。写入前比对文件内容哈希，被外部改过时直接拒绝并提示重跑；同时有 `.bak` 备份与原子写入。

**Q：语言文件中已有的翻译会被重写吗？**
A：默认 `policy: missing`，只补缺失的。需要重译时显式 `--force`，且冻结条目仍受保护。

**Q：如何完全离线且不给模型任何数据？**
A：`"privacy": { "offlineOnly": true }` + `engine: "offline"`，或 `engine: "argos"` 用本地模型。

**Q：记忆库放哪？**
A：默认 `.lingoflow/memory/lingoflow.db`（SQLite，Node ≥ 22.5）或 `.lingoflow/memory/*.jsonl`。建议把 `.lingoflow/` 加进 `.gitignore`。

**Q：能只针对某个语言跑 CI 吗？**
A：`lingoflow check --locales zh-CN,ja --fail-on warn`。
