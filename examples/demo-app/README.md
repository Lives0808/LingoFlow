# LingoFlow demo app

A tiny, self-contained project that exercises the whole pipeline — scanning,
translation, alignment, length checks, fixes, learning and reporting.

```bash
cd examples/demo-app

node ../../bin/lingoflow.mjs doctor          # environment + engines
node ../../bin/lingoflow.mjs scan            # keys in code, hardcoded copy
node ../../bin/lingoflow.mjs sync --dry-run  # preview every change
node ../../bin/lingoflow.mjs sync            # apply
node ../../bin/lingoflow.mjs preview --serve # UI fit preview with live reload (offline-only)
```

What the fixture contains on purpose:

| Item | Why |
| --- | --- |
| `locales/zh-CN.json` with an empty value, an untranslated string and a Chinese ICU argument name | exercises `empty`, `untranslated`, `placeholder.extra` and `auto.fixed` |
| `locales/ja.json` / `de.json` with missing keys | `sync` translates them, `align` can fill them |
| `en-XA` pseudo locale | layout testing **before** real translation |
| `cta.*` length rule (18 chars, hard) | German `"Änderungen speichern"` gets abbreviated to fit |
| `src/App.tsx` with hardcoded strings | `lingoflow extract --write` turns them into keys |
| `lingoflow.glossary.json` / `lingoflow.style.json` | brand term protection, Chinese typography and formality |

The config sets `"privacy": { "offlineOnly": true }`, so the demo runs entirely
on the built-in offline engine — no network, no keys. The built-in engine is a
dictionary/TM composer: short labels come out right, longer sentences stay
partially untranslated (and are reported as warnings, never silently accepted).
Pass `--fail-on none` when running the demo pipeline in scripts, since the
fixture intentionally contains a broken ICU string to demonstrate validation.

Everything LingoFlow creates lives in `.lingoflow/` (SQLite/JSONL memory, engine
cache, reports, backups) and is git-ignored.
