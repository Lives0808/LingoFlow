# Changelog

All notable changes to LingoFlow are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/).

## [0.2.0] — 2026-09-28

Adds **LingoFlow for Android**: a native Kotlin + Jetpack Compose app that shares the CLI's
configuration, catalog formats and validation rules.

### Added

**Android app (`android/`)**
- Native Compose UI (no WebView, no Node runtime): overview, issues, editor, UI-fit preview,
  memory/rule management and settings, with a single state holder (`MainViewModel`).
- Opens any project folder through the system picker (SAF) or the bundled demo project; reads
  `lingoflow.config.json` and the same eight catalog formats, preserving comments and key order.
- **Length & UI fit with real font metrics** (`Paint.measureText` instead of approximations),
  14 widget presets, glob-matched budgets, per-locale expansion budgets, pixel-accurate overflow
  detection and a pseudo-locale (+40%) toggle.
- Full validation matrix on-device: placeholders, ICU structure + CLDR plural categories, HTML
  tags, whitespace, punctuation, CJK typography, script mismatch, glossary/DNT, consistency and
  forbidden terms — the same issue codes as the CLI.
- Translation engines: built-in offline engine (glossary + memory + seed dictionary),
  pseudo-locale, OpenAI-compatible endpoints (including local Ollama/LM Studio), LibreTranslate
  and a custom HTTP template. Plans are previewed before anything is written.
- Anti-rework: approve **and freeze** translations; later runs (even forced) never overwrite them
  and report the difference instead. `Learn from edits` mines reviewer changes into correction
  rules, approved copy into style rules and repeated term pairs into glossary candidates.
- Private SQLite translation memory in app storage, API keys encrypted with an Android Keystore
  key, `Block every network engine` enforced in code, no telemetry, no accounts.
- HTML / Markdown / JSON reports written next to the project or shared through the system sheet.
- 48 JVM unit tests covering the core (parsers, formats, validation, learning, pipeline,
  demo project) plus a signed-release build in CI.

**Tooling**
- `android/` Gradle project (AGP 8.13 + Kotlin 2.3 + Compose BOM, minSdk 26, targetSdk 36),
  committed open-source release keystore for update continuity, `android.yml` and a combined
  release workflow that ships the APK next to the CLI artifacts.

### Changed

- CLI version bumped to 0.2.0 to keep one product version across platforms.
- Release workflow now builds and attaches `lingoflow-android-<version>.apk`,
  `-debug.apk`, the npm tarball, the single-file CLI bundle and checksums.

## [0.1.0] — 2026-09-28

First public release. 🎉

### Added

**Code awareness**
- Dependency-free JS/TS/JSX lexer that finds `t()`, `i18n.t()`, `$t()`, `formatMessage({ id })`, `<Trans i18nKey>`, `<FormattedMessage id>` and reports dynamic keys.
- Hardcoded UI copy detection (JSX text, `placeholder` / `title` / `aria-label` …) with confidence scores.
- `lingoflow extract` rewrites hardcoded strings into translation calls and writes the new keys into the source catalog.

**Catalog formats (lossless round-trip)**
- JSON/JSON5/JSONC, YAML, Java `.properties`, gettext `.po` (context + plurals), Flutter `.arb` (with `@` metadata), Apple `.strings`, CSV (multi-locale aware) and TS/JS modules.
- Comments, key order, indentation and line endings are preserved; writes are atomic with `.bak` backups and conflict detection.

**Translation engines**
- Built-in offline engine (glossary + memory + seed dictionary), pseudo-localisation, Argos Translate bridge, any OpenAI-compatible endpoint (including local Ollama/vLLM), LibreTranslate, DeepL and a template-driven custom HTTP engine.
- Engine cache, retries, fallback chain and a hard `privacy.offlineOnly` switch that blocks every remote engine.

**Validation**
- Placeholders (`{name}`, `{{count}}`, `%s`, `%1$s`, `%(name)s`, `${x}`, `$t(key)`), ICU structure, CLDR plural categories, HTML tag balance/attributes, whitespace, punctuation, ellipsis, CJK typography, script mismatch, terminology, do-not-translate, same-source-same-target consistency, forbidden terms and soft/hard length budgets.
- Severity overrides and per-locale ignore patterns.

**UI fit & length**
- Font-metric based px/char budgets for 14 widget presets, glob-matched length rules, per-locale expansion budgets.
- Deterministic auto-fixes (reflow, punctuation, CJK spacing, placeholder repair, abbreviation/shortening) with an explicit `auto.fixed` trail in the report.
- HTML report with a simulated UI where overflow is verified by real DOM measurement, plus a pseudo-locale switch and a live-reloading `preview --serve` server.

**Private knowledge base**
- Translation memory on SQLite (`node:sqlite`) or JSONL with exact + fuzzy matching, engine/confidence/refs tracking, approve/freeze protection (防返工) that even `--force` cannot override.
- Glossary, style, correction and do-not-translate rules in the same private store, importable/exportable as git-friendly JSON.
- Learning: correction rules derived from reviewer edits, glossary candidates mined from history, and house-style detection (capitalisation, trailing punctuation, ellipsis, CJK spacing, formality).

**Workflow & automation**
- `sync` one-click pipeline (scan → extract → align → translate → fix → validate → write back → learn → report).
- `check` / `fix` / `align` / `scan` / `report` / `preview` / `watch` and full memory/rule management commands.
- HTML, JSON, Markdown and SARIF reports; `--fail-on error|warn|none` for CI gating; XLIFF/CSV/JSON handoff and re-import.

**Distribution**
- Single-file bundle (zero runtime dependencies), installable npm tarball and SHA-256 checksums attached to the GitHub release.
- 39 unit/integration tests covering formats, validation, scanning, memory, rules, fixing and the end-to-end pipeline.

[0.2.0]: https://github.com/Lives0808/LingoFlow/releases/tag/v0.2.0
[0.1.0]: https://github.com/Lives0808/LingoFlow/releases/tag/v0.1.0
