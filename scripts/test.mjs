#!/usr/bin/env node
/**
 * Cross-platform test runner.
 *
 * `node --test test/*.test.ts` relies on the shell expanding the glob (fine on
 * bash, not on PowerShell) and on Node >= 22 to expand patterns itself. This
 * script resolves the files first so every supported Node version and OS works.
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import fg from 'fast-glob';

const root = path.resolve(import.meta.dirname, '..');
const patterns = process.argv.slice(2);
const files = await fg(patterns.length > 0 ? patterns : ['test/**/*.test.ts'], {
  cwd: root,
  absolute: false,
  onlyFiles: true,
});

if (files.length === 0) {
  console.error('No test files matched');
  process.exit(1);
}

const child = spawn(process.execPath, ['--import', 'tsx', '--test', ...files.sort()], {
  cwd: root,
  stdio: 'inherit',
});

child.on('close', (code) => process.exit(code ?? 1));
