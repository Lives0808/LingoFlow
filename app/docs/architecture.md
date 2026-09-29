# LingoFlow 架构文档 · Architecture

> 隐私优先的跨平台翻译工作流。所有数据留在本地，用户自带 API Key，不提供公共密钥。

## 一、功能架构

```
┌──────────────────────────── 用户界面（共享 Compose Multiplatform） ────────────────────────────┐
│  项目管理        双语对照翻译        术语库        平行语料库      词汇本      设置/OCR/导出      │
│  Projects  →  Translate (source│target)  Terms  →  Corpus  →  Vocabulary  →  Settings         │
└───────────────────────────────────────────┬───────────────────────────────────────────────────┘
                                            │  AppViewModel（单一状态容器，StateFlow）
┌───────────────────────────────────────────┴───────────────────────────────────────────────────┐
│                                  领域层（shared/commonMain，纯 Kotlin）                        │
│                                                                                               │
│  doc/        文档模型（段落结构）+ 导入器（txt/md/srt）+ 导出器（双语 MD/CSV/JSON/SRT/HTML）      │
│  Terms       术语提取（频次+大小写+CJK 滑窗）· 强制匹配 · 高亮                                   │
│  Corpus      平行语料：人工修正自动入库 → 相似句模糊复用（Fuzzy）                                 │
│  Translator  上下文记忆（前文 source⇒target）+ 术语注入 + 语料提示 + 批量/进度回调               │
│  ChatEngine  模型抽象：离线引擎 / OpenAI 兼容 / DeepSeek / 自定义端点                            │
│  Exporters   双语 Markdown · 纯译文 MD · TXT · CSV · JSON · SRT · 可打印 HTML（→PDF）           │
│  Workspace   WorkspaceStore 接口：项目/文档/术语/语料/词汇/设置/密钥（实现见平台层）              │
└───────────────────────────────────────────┬───────────────────────────────────────────────────┘
                                            │
        ┌───────────────────────────────────┼───────────────────────────────────┐
        │                                   │                                   │
┌───────┴────────┐              ┌───────────┴────────────┐          ┌───────────┴────────────┐
│  jvmCore       │              │  androidApp            │          │  desktopApp            │
│  （纯 JVM 共享）│              │  （Android 壳）         │          │  （macOS/Win/Linux 壳） │
│                │              │                        │          │                        │
│ • 文件工作区    │              │ • SAF 文件夹/文件选择    │          │ • AWT 文件对话框        │
│ • DOCX/PDF 解析 │              │ • ML Kit 离线 OCR       │          │ • 系统剪贴板            │
│ • OkHttp 引擎   │              │ • Keystore 加密密钥     │          │ • Tesseract（可选）      │
│ • 剪贴板/密钥   │              │ • 裁剪擦除编辑器         │          │ • 快捷键 ⌘1..5/⌘T/⌘O    │
└────────────────┘              └────────────────────────┘          └────────────────────────┘
```

**数据流（一次翻译）**

```
导入文档 → 段落切分（结构保留）→ 术语强制匹配 → 语料相似句复用
        → 模型批量请求（术语表 + 语料提示 + 前文上下文）
        → 人工逐段修改 → 自动写入平行语料库 → 下次相似句直接复用
```

## 二、KMP 技术选型

| 关注点 | 选型 | 理由 / 取舍 |
| --- | --- | --- |
| 语言 | Kotlin 2.3 | KMP 一等公民；与既有 Android 代码同源 |
| UI | **Compose Multiplatform 1.12** | 一套 UI 跑 Android 与桌面，省掉双端重写；缺点见「边界限制」 |
| 构建 | Gradle 8.14 + AGP 8.13 | 与 Android 生态对齐；`libs.versions.toml` 统一版本 |
| 并发 | kotlinx-coroutines | 平台无关的 `suspend`，IO 切换到 `Dispatchers.IO` |
| 序列化 | 自研容错 JSON（`Jsonish`） | 语言文件/工程文件常带注释与尾逗号；同时避免引入 kotlinx.serialization 编译器插件（构建更简单、体积更小） |
| 网络 | OkHttp（JVM/Android 共用） | 只在 `jvmCore` 出现，commonMain 通过 `ChatEngine` 接口解耦；桌面与手机同一实现 |
| 本地存储 | 纯 JSON/JSONL 文件 | 可 diff、可 git、可手工拷贝；隐私卖点不需要数据库 |
| Android OCR | ML Kit `text-recognition-chinese`（端上） | 离线、免费、中日韩+拉丁；桌面端回退到 Tesseract CLI（可选），不做静默下载 |
| 密钥 | Android Keystore AES-GCM / 桌面 0600 文件 | 密钥从不出现在备份里；无账号体系 |
| 文档解析 | DOCX：自写 OOXML 解析；PDF：自写 Flate + ToUnicode 解析 | 零依赖、体积小；代价是 PDF 保真度有限（见下） |

**为什么不用 Ktor / Room / SQLDelight？** 当前需求（文件存储 + 一个 HTTP 客户端）用 JDK 与 OkHttp 即可，减少编译期插件与二进制体积；如果后续要接 iOS 或云端同步，再替换为 Ktor + SQLDelight 是平滑的（接口已经在 `WorkspaceStore` / `ChatEngine` 后面）。

## 三、核心代码示例

### 1. 项目级上下文记忆 + 术语注入（`DocumentTranslator`）

```kotlin
private fun buildPrompt(document: Document, batch: List<Segment>, context: String): Prompt {
    val glossary = TermMatcher.matches(document.translatableSegments.joinToString("\n") { it.source }, terms)
    val system = buildString {
        append("SOURCE LANGUAGE: ").append(document.sourceLang).append('\n')
        append("TARGET LANGUAGE: ").append(document.targetLang).append('\n')
        append(options.stylePreset.systemPrompt).append('\n')
        if (glossary.isNotEmpty()) {
            append("GLOSSARY (must be used exactly):\n")
            glossary.take(60).forEach { append("- ").append(it.source).append(" ⇒ ").append(it.target).append('\n') }
        }
        append(CorpusMatcher.memoryBlock(batch.first().source, corpus))   // 用户自己的历史译法
        if (context.isNotEmpty()) append("CONTEXT (already translated):\n").append(context)
    }
    // 每段逐行返回 `<id>: <translation>`，便于流式回填 UI
}
```

### 2. 人工修正 → 平行语料（防返工闭环）

```kotlin
fun editSegment(segmentId: Int, text: String) {
    val updated = document.updateSegment(segmentId) { it.copy(target = text, locked = true, origin = "human") }
    scope.launch {
        store.saveDocument(project.id, updated)
        store.appendCorpus(project.id, listOf(CorpusEntry(source = segment.source, target = text, confirmed = true, ...)))
    }
}
```

### 3. 术语自动提取（无模型，纯本地）

```kotlin
Regex("\\b([A-Z][\\p{L}\\p{N}+#.-]*(?:\\s+(?:of|the|and)?\\s*[A-Z][\\p{L}\\p{N}+#.-]*){0,3})")
    .findAll(text)                       // 连续大写短语 → API key / Kotlin Multiplatform
    .filterNot { it.value.lowercase() in stopwords }
// CJK：2-6 字滑窗，统计重复次数，按 频次 + 长度 + 类型 打分
```

### 4. PDF 文本提取（自写，零依赖）

```kotlin
forEachStream(bytes) { stream, dict ->
    if (dict.contains("ToUnicode")) parseToUnicode(stream.decodeToString(), cmap)  // CMap → Unicode
}
forEachStream(bytes) { stream, _ -> extractTextOperators(stream.decodeToString(), cmap) }  // BT/ET · Tj/TJ
```

## 四、缺陷与边界限制（坦诚版）

### 功能边界

| 限制 | 说明 | 缓解方式 |
| --- | --- | --- |
| **PDF 提取是"尽力而为"** | 自写解析器处理 FlateDecode + ToUnicode CMap。复杂排版（多栏、表格、旋转文字）会串行或丢行；**扫描件 PDF 完全无法提取**（没有文本层）。 | UI 明确提示；扫描件请用 **拍照 OCR**；分栏 PDF 建议先转 Markdown/DOCX |
| **DOCX 只取正文段落** | 忽略表格、文本框、图片、批注、页眉页脚；样式只用于识别标题级别。 | 简单文档可直接用；复杂排版建议导出 Markdown |
| **桌面 OCR 依赖外部 Tesseract** | 不静默下载模型；未安装时明确报错并指向 Android 端或 `brew install tesseract tesseract-lang`。 | 手机端为端上 ML Kit，开箱即用 |
| **PDF 导出 = 打印到 PDF** | 生成自包含 HTML，通过浏览器打印为 PDF；没有直接写 PDF 字库的能力（内嵌 CJK 字体需要几十 MB 字体文件）。 | 需要精确排版时用 Markdown/HTML |
| **机器翻译质量取决于模型** | 内置离线引擎是「术语表 + 平行语料 + 种子词典」的组合，长句质量有限；这是隐私优先的必然取舍。 | 在设置里填自己的 Key（DeepSeek/OpenAI 兼容/本地 Ollama） |
| **上下文记忆有窗口限制** | 只回传前 N 段（默认 6）而不是整篇文档，超长文档的跨章节一致性仍依赖术语表与语料库。 | 调大 context window；把关键名词加入术语表 |

### 工程边界

- **Compose Multiplatform 桌面端**首屏需要 1–3 秒（JVM + Skiko 初始化），安装包约 40–60 MB（自带 JRE 时更大）；移动端 12–15 MB。
- **Android 相机权限**只用于 OCR 拍照；不申请存储权限（走 SAF），不申请定位。
- **i18n 界面语言**目前只有中英双语字符串硬编码在源码里，未接入资源系统。
- **无自动更新**：安装包手动升级（GitHub Release），刻意不做后台联网检查。
- **没有单元测试覆盖 UI**：`commonTest` 覆盖文档解析、术语提取、语料复用、翻译编排；Compose 界面靠手工验证（CI 里跑编译 + APK/安装包构建）。
- **并行请求是"每文档串行批次"**：同一文档内按批次顺序请求以保证上下文连贯，多文档不并行；`concurrency` 配置项预留但尚未启用。

### 隐私相关的诚实说明

- 只有当你**主动选择**非离线引擎并填入 Key 时，文本才会发往该端点；请求直接从设备到端点，LingoFlow 没有中转服务。
- 桌面端密钥以 0600 权限文件保存（不是系统钥匙串）；Android 用 Keystore 加密。若要更高安全等级，请使用本地端点（Ollama 等）。
- 「离线引擎」不会发起任何网络请求，可用飞行模式验证。
