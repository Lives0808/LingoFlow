# Contributing to LingoFlow

Thanks for helping! LingoFlow aims to be a dependable, dependency-light tool, so a
few rules keep it that way.

## Setup

```bash
npm install
npm run typecheck
npm test          # node:test via tsx
npm run dev -- --help
```

Node.js ≥ 20.11 is required. SQLite-backed features additionally use `node:sqlite`
(Node ≥ 22.5) and fall back to the JSONL driver automatically.

## Ground rules

- **No new runtime dependency without a strong reason.** The current set is
  `commander`, `zod`, `yaml`, `fast-glob`, `picocolors`. Anything parsing-heavy
  (JS lexing, PO/XLIFF/ICU parsing, fuzzy matching) is implemented in-repo.
- **Never lose user data.** Writes go through `writeAtomic` with conflict
  detection; catalog round-trips must preserve comments, key order and line
  endings. Add a test for every format change.
- **Never evaluate user code.** Parsing is static; no `eval`, no `new Function`,
  no child process except the explicit Python bridge for Argos.
- **Privacy defaults.** New engines must declare `privacy: 'local' | 'remote'`
  and respect `privacy.offlineOnly`.

## Adding a catalog format

1. Implement `CatalogFormat` in `src/core/catalog/formats/<name>.ts`
   (`decode`, `encode`, no side effects).
2. Register it in `src/core/catalog/formats/index.ts` and the `FormatId` union
   in `src/core/types.ts`.
3. Add a round-trip test to `test/formats.test.ts` covering comments, escapes and
   a plural/ICU string.

## Adding a translation engine

1. Implement `TranslationEngine` in `src/core/translate/engines/<name>.ts`.
2. Wire it into `createEngine` + `providerSchema` (all options need defaults) and
   `describeEngines`.
3. Document it in `docs/engines.md` and add a unit test with a fake transport if
   the engine talks HTTP.

## Adding a validation rule

1. Add the code to `IssueCode` in `src/core/types.ts` and a default severity in
   `src/core/validate/index.ts`.
2. Emit it from the right checker (`validate/*.ts`) — include `fixable` when
   `core/fix` can repair it, and implement the fix.
3. Cover it in `test/validate.test.ts`.

## Pull requests

- Keep commits focused; describe the user-visible behaviour in the PR body.
- Run `npm run typecheck && npm test && npm run build` before pushing.
- Update `CHANGELOG.md` under “Unreleased” for user-visible changes.
