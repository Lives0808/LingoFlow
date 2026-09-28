import { chmod, mkdir, rm, copyFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { build } from 'esbuild';

const root = path.resolve(import.meta.dirname, '..');

// Bundled CommonJS dependencies (yaml, fast-glob, picocolors) call require() internally,
// which ESM output cannot do without a shim.
const requireShim = [
  'import { createRequire as __lingoflowCreateRequire } from "node:module";',
  'const require = __lingoflowCreateRequire(import.meta.url);',
].join('\n');

const shared = {
  bundle: true,
  platform: 'node',
  target: 'node20',
  format: 'esm',
  sourcemap: false,
  minify: false,
  logLevel: 'info',
  external: [],
  define: { 'process.env.NODE_ENV': '"production"' },
};

async function main() {
  await rm(path.join(root, 'dist'), { recursive: true, force: true });
  await mkdir(path.join(root, 'dist'), { recursive: true });
  await mkdir(path.join(root, 'bin'), { recursive: true });

  await build({
    ...shared,
    entryPoints: [path.join(root, 'src/index.ts')],
    outfile: path.join(root, 'dist/index.js'),
    banner: { js: requireShim },
  });

  await build({
    ...shared,
    entryPoints: [path.join(root, 'src/bin.ts')],
    outfile: path.join(root, 'bin/lingoflow.mjs'),
    banner: { js: `#!/usr/bin/env node\n${requireShim}` },
  });

  await chmod(path.join(root, 'bin/lingoflow.mjs'), 0o755);

  if (existsSync(path.join(root, 'schema/lingoflow.schema.json'))) {
    await mkdir(path.join(root, 'dist/schema'), { recursive: true });
    await copyFile(
      path.join(root, 'schema/lingoflow.schema.json'),
      path.join(root, 'dist/schema/lingoflow.schema.json'),
    );
  }

  // Type declarations for programmatic consumers (dist/index.d.ts).
  const tsc = spawnSync(process.execPath, [path.join(root, 'node_modules/typescript/lib/tsc.js'), '-p', root], {
    stdio: 'inherit',
  });
  if (tsc.status !== 0) {
    console.error('TypeScript declaration emit failed');
    process.exit(tsc.status ?? 1);
  }

  console.log('\nBuild complete: dist/index.js, dist/index.d.ts, bin/lingoflow.mjs');
}

await main();
