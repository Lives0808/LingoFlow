# LingoFlow · 跨平台翻译工作流

**Privacy-first translation workflow — build your own terminology corpus.**
**隐私优先的跨平台翻译工作流，搭建属于你的专属术语库。**

Kotlin Multiplatform · Android + macOS + Windows + Linux · 本地存储 · 自带 API Key

---

## 为什么做这个

翻译工具大多把「术语、上下文、习惯」留在云端，换个工具就全丢了。LingoFlow 的做法相反：

- **项目化**：每个项目（如「雅思阅读」「开源项目 README」）独立保存术语表、平行语料、词汇本与文档
- **上下文记忆**：同一份文档翻译时，模型会拿到前文的原文与译文，专业名词、人称、语气前后一致
- **越用越准**：你手动改过的译文自动进入本地平行语料库，之后遇到相似句子优先复用你的译法
- **隐私优先**：全部数据是本地 JSON 文件；翻译请求由你自己的 API Key 从本机直连端点（也支持完全离线的内置引擎与本地 Ollama）

## 功能一览

| 功能 | 状态 | 说明 |
| --- | --- | --- |
| 项目管理 | ✅ | 多项目，各自独立术语/语料/词汇/文档 |
| 本地术语库 | ✅ | 手动添加 + 自动提取（频次/大小写/CJK 滑窗）+ 翻译时强制匹配 + 双语视图高亮 |
| 文档导入 | ✅ | Markdown / TXT / SRT / DOCX / PDF（PDF 为尽力提取，扫描件请用 OCR） |
| 双语对照视图 | ✅ | 原文与译文并排，逐段编辑，AI 释义（桌面悬浮 / 手机点击） |
| 上下文记忆 | ✅ | 前 N 段 source⇒target + 术语表 + 语料提示进入 prompt |
| 平行语料积累 | ✅ | 人工修正自动入库，相似句模糊复用（可调阈值） |
| 自有 API Key | ✅ | OpenAI 兼容 / DeepSeek / 自定义端点 / 本地 Ollama；无公共密钥 |
| 多模型与风格预设 | ✅ | 通用 / 技术文档 / 开源 README / 学术 / 文学 / 口语 / 商务 |
| 语言润色模式 | ✅ | 英文改写、学术书面化、精简 |
| 图片 OCR 翻译 | ✅（Android） | 端上 ML Kit（中日韩+拉丁），拍照/相册 + 裁剪擦除；桌面端可用 Tesseract（可选） |
| 双语词汇本 | ✅ | 一键收藏生词（含例句），导出 CSV 或 Anki TSV |
| 导出 | ✅ | 双语 Markdown、纯译文、TXT、CSV、JSON、SRT、可打印 HTML（→PDF） |
| 深色/浅色主题 | ✅ | 跟随设置，与 GitHub 风格一致 |
| 快捷键（桌面） | ✅ | ⌘/Ctrl+1..5 切换页面，⌘T 翻译，⌘O 导入，⌘E 提取术语，⌘K 术语库 |
| 实时剪贴板监听 | ⏳ P2 | 未实现（避免常驻监听与隐私争议） |
| 跨端同步 | ⏳ | 通过复制项目文件夹实现（不做云端） |

## 界面

> 截图来自真实运行的桌面版与 Android 版（见 `docs/screenshots/`）。

| 项目管理 | 文档导入 |
| --- | --- |
| ![projects](docs/screenshots/android-projects.png) | ![documents](docs/screenshots/android-documents.png) |

| 双语对照（原文 / 译文并排 + 术语高亮） | 本地术语库 |
| --- | --- |
| ![bilingual](docs/screenshots/android-bilingual.png) | ![terms](docs/screenshots/android-terms.png) |

> 截图取自 Android 模拟器上真实运行的构建产物（同一套 Compose UI 也运行在 macOS/Windows/Linux 桌面端）。
> 桌面截图需要在有图形会话的机器上运行 `./gradlew :desktopApp:run` 获取。

## 安装

```bash
# Android（Android 8.0+）
adb install lingoflow-android-0.3.0.apk

# macOS / Windows / Linux：从 Release 下载安装包
# LingoFlow-0.3.0.dmg / LingoFlow-0.3.0.msi / lingoflow_0.3.0_amd64.deb
```

## 开发

```bash
cd app
./gradlew :jvmCore:test :shared:desktopTest      # 领域层测试（文档/术语/语料/翻译编排）
./gradlew :desktopApp:run                        # 桌面端（Linux/macOS/Windows）
./gradlew :androidApp:assembleDebug              # Android APK
./gradlew :desktopApp:packageDmg                 # 桌面安装包（对应平台）
```

数据目录：

- macOS `~/Library/Application Support/LingoFlow`
- Windows `%APPDATA%/LingoFlow`
- Linux `~/.local/share/lingoflow`
- Android 应用私有目录 `files/workspace`（无存储权限，走 SAF 访问外部文件）

## 架构与限制

- 详细功能架构、KMP 技术选型、核心代码示例与**缺陷边界**见 [`docs/architecture.md`](docs/architecture.md)
- 一句话总结取舍：**为了隐私与可移植性，宁可放弃 PDF 排版保真度与云端便利**；扫描件走 OCR，复杂 PDF 建议先转 Markdown。

## 与 CLI 的关系

同一个仓库里还有 `lingoflow` CLI（Node）：面向**代码仓库 i18n**（扫描源码、翻译语言文件、长度校验、SARIF 报告）。App 面向**文档翻译**。两者共享同一套「术语 + 记忆 + 防返工」理念。
