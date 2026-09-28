# Changelog

All notable changes to LingoFlow are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/).

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

[0.1.0]: https://github.com/Lives0808/LingoFlow/releases/tag/v0.1.0
