# 翻译引擎 · Engines

LingoFlow 把"翻译"抽象成可插拔引擎。默认使用**内置离线引擎**，任何内容都不离开本机。

```bash
lingoflow engines          # 查看所有引擎是否就绪
lingoflow engines --json   # 机器可读
```

---

## 1. `offline` — 内置离线引擎（默认）

零依赖、零网络、断网可用。工作方式：

1. **翻译记忆库**精确匹配 → 2. **模糊匹配**（相似源文案复用）→ 3. **术语表**强制生效 → 4. **种子词典**（86 组常见 UI 词汇，覆盖 zh-CN/zh-TW/ja/ko/de/fr/es/ru）→ 5. 其余部分原样保留并标记为"未覆盖"。

它擅长：按钮/标题/表单标签等短 UI 文案、已有记忆库的项目、内网与合规场景。
随着记忆库与术语表增长，质量会持续提升；长句建议接入下方任一引擎。

自定义词典（叠加在内置词典之上）：

```jsonc
// lingoflow.config.json
{ "translation": { "provider": { "offline": { "dictionary": "my-dictionary.json" } } } }
```

```json
{ "save changes": { "zh-CN": "保存更改", "ja": "変更を保存" } }
```

---

## 2. `pseudo` — 伪本地化

把字符串重音化并膨胀 40%（`⟦Ệxámplé···⟧`），**在真实翻译开始前**暴露布局问题。占位符与 HTML 标签会被完整保留。

对 `en-XA`、`ar-XB`、`qps-Ploc`、`xx` 等约定语言自动生效，也可以显式指定：

```bash
lingoflow translate --locales en-XA --engine pseudo
```

---

## 3. `argos` — 本地神经模型（完全离线）

```bash
pip install argostranslate

# 安装语言包（示例：英→中）
python3 - <<'PY'
import argostranslate.package as p
p.update_package_index()
for pack in p.get_available_packages():
    if pack.from_code == 'en' and pack.to_code in {'zh', 'ja', 'de'}:
        p.install_from_index(pack)
PY
```

```jsonc
{ "translation": { "engine": "argos", "provider": { "argos": { "python": "python3", "modelDir": "~/.local/share/argos-translate" } } } }
```

LingoFlow 通过内置的 Python 桥接脚本（自动写入 `.lingoflow/engines/argos_bridge.py`）一次性批量翻译，避免反复启动进程。

---

## 4. `openai` — OpenAI 兼容端点（含本地 LLM）

同一个适配器同时覆盖：OpenAI、Azure 兼容网关、DeepSeek、以及**完全本地的 Ollama / LM Studio / vLLM**。

```jsonc
// 本地 Ollama（数据不出内网）
{
  "translation": {
    "engine": "openai",
    "provider": {
      "openai": {
        "baseUrl": "http://localhost:11434/v1",
        "model": "qwen2.5:7b",
        "apiKeyEnv": "OPENAI_API_KEY",     // 本地端点不需要真实 key
        "batchSize": 12,
        "temperature": 0.2,
        "jsonMode": true
      }
    }
  }
}
```

云端示例（需显式允许联网）：

```bash
export OPENAI_API_KEY=sk-...
```

```jsonc
{ "translation": { "engine": "openai", "provider": { "openai": { "baseUrl": "https://api.openai.com/v1", "model": "gpt-4o-mini" } } },
  "privacy": { "allowNetwork": true, "offlineOnly": false } }
```

提示词会自动带上：目标语言排版规则（全角标点、正式/非正式、RTL…）、术语表、禁译词、每条文案的长度预算与上下文注释，并要求返回 JSON。

---

## 5. `libretranslate` — 自托管机器翻译

```bash
docker run -p 5000:5000 libretranslate/libretranslate
```

```jsonc
{ "translation": { "engine": "libretranslate", "provider": { "libretranslate": { "url": "http://localhost:5000", "apiKeyEnv": "LIBRETRANSLATE_API_KEY" } } } }
```

---

## 6. `deepl` — 远程高质量翻译

```bash
export DEEPL_API_KEY=...
```

```jsonc
{ "translation": { "engine": "deepl", "provider": { "deepl": {
  "endpoint": "https://api-free.deepl.com",
  "formality": "prefer_more",
  "targetMap": { "zh-CN": "ZH-HANS", "zh-TW": "ZH-HANT" } } } },
  "privacy": { "allowNetwork": true } }
```

---

## 7. `custom` — 模板化 HTTP

接内部翻译服务：

```jsonc
{ "translation": { "engine": "custom", "provider": { "custom": {
  "url": "https://mt.internal/api/translate",
  "method": "POST",
  "headers": { "content-type": "application/json", "authorization": "Bearer {{apiKey}}" },
  "apiKeyEnv": "INTERNAL_MT_KEY",
  "bodyTemplate": "{\"q\": {{json.text}}, \"source\": {{json.source}}, \"target\": {{json.target}}}",
  "responsePath": "data.translation"
} } } }
```

`bodyTemplate` 支持 `{{json.text}}` / `{{json.source}}` / `{{json.target}}` / `{{json.key}}` / `{{json.comment}}` / `{{json.maxChars}}` / `{{apiKey}}`；URL 里可用 `{{text}}`、`{{source}}`、`{{target}}`（自动 URL 编码）；`responsePath` 是响应 JSON 的点路径，支持数组下标（`data.0.text`）。

---

## 兜底、重试与缓存

- `translation.fallback: ["offline"]`：主引擎整体失败时按顺序尝试兜底引擎
- `translation.retries`：指数退避重试
- 引擎结果按 `引擎 + 源 + 目标 + 文本 + 规则版本` 做哈希缓存，重复运行不会重复计费
- `privacy.offlineOnly: true` 会**直接拒绝**任何联网引擎（含兜底链），这是硬性开关
