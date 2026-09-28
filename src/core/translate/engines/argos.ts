import { spawn } from 'node:child_process';
import path from 'node:path';
import type { TranslateItem, TranslateOutput } from '../../types';
import { LingoFlowError } from '../../errors';
import { ensureDir, writeAtomic } from '../../utils/fsx';
import type { EngineAvailability, EngineContext, TranslationEngine } from '../engine';

/** Embedded so the bridge works from npm installs and standalone bundles alike. */
export const ARGOS_BRIDGE = `#!/usr/bin/env python3
"""LingoFlow <-> Argos Translate bridge. Reads JSON on stdin, writes JSON on stdout."""
import json
import sys


def main() -> int:
    if "--check" in sys.argv:
        try:
            import argostranslate.translate as translate  # noqa: F401
            import argostranslate as argos
            version = getattr(argos, "__version__", "unknown")
            print(json.dumps({"ok": True, "version": version}))
        except Exception as error:  # pragma: no cover - environment dependent
            print(json.dumps({"ok": False, "error": str(error)}))
        return 0

    try:
        payload = json.load(sys.stdin)
    except Exception as error:
        print(json.dumps({"error": f"invalid payload: {error}"}))
        return 1

    try:
        from argostranslate import translate
    except Exception as error:
        print(json.dumps({"error": f"argostranslate is not installed: {error}"}))
        return 1

    source = payload.get("from")
    target = payload.get("to")
    results = []
    for item in payload.get("items", []):
        try:
            text = translate.translate(item["text"], source, target)
            results.append({"id": item["id"], "text": text})
        except Exception as error:
            results.append({"id": item["id"], "text": item["text"], "error": str(error)})
    print(json.dumps({"items": results}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
`;

/**
 * Local neural machine translation through Argos Translate (Python).
 * Fully offline once language packages are installed:
 *   pip install argostranslate
 *   python3 -c "import argostranslate.package as p; p.update_package_index(); \
 *               p.install_from_index(p.get_available_packages()[0])"
 */
export class ArgosEngine implements TranslationEngine {
  id = 'argos' as const;
  label = 'Argos Translate (local model)';
  privacy = 'local' as const;
  network = false;
  private bridgePath = '';
  private python: string;

  constructor(private options: { python: string; modelDir?: string; bridge?: string }) {
    this.python = options.python;
  }

  private async ensureBridge(ctx: EngineContext): Promise<string> {
    if (this.options.bridge) return this.options.bridge;
    if (this.bridgePath) return this.bridgePath;
    const dir = path.join(ctx.rootDir, ctx.config.workDir || '.lingoflow', 'engines');
    await ensureDir(dir);
    const file = path.join(dir, 'argos_bridge.py');
    await writeAtomic(file, ARGOS_BRIDGE);
    this.bridgePath = file;
    return file;
  }

  async availability(): Promise<EngineAvailability> {
    try {
      const output = await runPython(this.python, ['-c', 'import argostranslate, sys; print("ok")'], undefined, {
        ARGOS_PACKAGE_DIR: this.options.modelDir ?? '',
      });
      if (output.stdout.includes('ok')) {
        return {
          ok: true,
          detail: 'argostranslate is importable — install language packages with: python3 -m argostranslate.package install <from> <to>',
        };
      }
      return {
        ok: false,
        detail: `${output.stderr.trim() || 'python probe failed'} — install with: pip install argostranslate`,
      };
    } catch (error) {
      return { ok: false, detail: `cannot run ${this.python}: ${(error as Error).message}` };
    }
  }

  async translate(items: TranslateItem[], ctx: EngineContext): Promise<TranslateOutput[]> {
    const bridge = await this.ensureBridge(ctx);
    const groups = groupByLocalePair(items);
    const results: TranslateOutput[] = [];
    for (const [pair, groupItems] of groups) {
      const [from, to] = pair.split('\u0000') as [string, string];
      const payload = JSON.stringify({
        from,
        to,
        items: groupItems.map((item) => ({ id: item.id, text: item.text })),
      });
      const output = await runPython(this.python, [bridge], payload, { ARGOS_PACKAGE_DIR: this.options.modelDir ?? '' });
      let parsed: { items?: Array<{ id: string; text: string; error?: string }>; error?: string };
      try {
        parsed = JSON.parse(output.stdout) as typeof parsed;
      } catch {
        throw new LingoFlowError(`Argos bridge returned invalid JSON: ${output.stdout.slice(0, 200)}`, {
          code: 'ARGOS_BAD_OUTPUT',
          hint: output.stderr.slice(0, 400),
        });
      }
      if (parsed.error) {
        throw new LingoFlowError(`Argos: ${parsed.error}`, { code: 'ARGOS_ERROR' });
      }
      for (const entry of parsed.items ?? []) {
        results.push({
          id: entry.id,
          text: entry.text,
          engine: this.id,
          confidence: entry.error ? 0.1 : 0.85,
          notes: entry.error ? [entry.error] : undefined,
        });
      }
    }
    return results;
  }
}

function groupByLocalePair(items: TranslateItem[]): Map<string, TranslateItem[]> {
  const groups = new Map<string, TranslateItem[]>();
  for (const item of items) {
    const key = `${item.from}\u0000${item.to}`;
    const group = groups.get(key) ?? [];
    group.push(item);
    groups.set(key, group);
  }
  return groups;
}

function runPython(
  python: string,
  args: string[],
  stdin: string | undefined,
  env: Record<string, string>,
): Promise<{ stdout: string; stderr: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn(python, args, {
      env: { ...process.env, ...(env.ARGOS_PACKAGE_DIR ? { ARGOS_PACKAGE_DIR: env.ARGOS_PACKAGE_DIR } : {}) },
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => {
      stdout += String(chunk);
    });
    child.stderr.on('data', (chunk) => {
      stderr += String(chunk);
    });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code === 0) resolve({ stdout, stderr });
      else reject(new LingoFlowError(`python exited with code ${code}: ${stderr.slice(0, 300) || stdout.slice(0, 300)}`, { code: 'ARGOS_PROCESS_FAILED' }));
    });
    if (stdin !== undefined) {
      child.stdin.write(stdin);
      child.stdin.end();
    } else {
      child.stdin.end();
    }
  });
}
