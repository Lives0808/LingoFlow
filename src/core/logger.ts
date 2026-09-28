import pc from 'picocolors';

export type LogLevel = 'silent' | 'error' | 'warn' | 'info' | 'debug';

const LEVEL_ORDER: Record<LogLevel, number> = {
  silent: 0,
  error: 1,
  warn: 2,
  info: 3,
  debug: 4,
};

export interface LoggerOptions {
  level?: LogLevel;
  json?: boolean;
  color?: boolean;
}

/**
 * Small dependency-free logger.
 * Human output goes to stderr so `--json` payloads on stdout stay machine readable.
 */
export class Logger {
  level: LogLevel;
  json: boolean;
  private colorEnabled: boolean;

  constructor(options: LoggerOptions = {}) {
    this.level = options.level ?? 'info';
    this.json = options.json ?? false;
    this.colorEnabled = options.color ?? supportsColor();
  }

  child(): Logger {
    return new Logger({ level: this.level, json: this.json, color: this.colorEnabled });
  }

  configure(options: LoggerOptions): void {
    if (options.level) this.level = options.level;
    if (options.json !== undefined) this.json = options.json;
    if (options.color !== undefined) this.colorEnabled = options.color;
  }

  get color(): boolean {
    return this.colorEnabled;
  }

  enabled(level: Exclude<LogLevel, 'silent'>): boolean {
    return LEVEL_ORDER[this.level] >= LEVEL_ORDER[level];
  }

  private write(text: string): void {
    process.stderr.write(text.endsWith('\n') ? text : `${text}\n`);
  }

  private paint(enabled: boolean, text: string, fn: (s: string) => string): string {
    if (!this.colorEnabled || this.json) return text;
    return enabled ? fn(text) : text;
  }

  error(message: string, ...rest: unknown[]): void {
    if (!this.enabled('error')) return;
    this.write(`${this.paint(true, 'error', pc.red)}\u2028 ${message}${formatRest(rest)}`.replace('\u2028', ' '));
  }

  warn(message: string, ...rest: unknown[]): void {
    if (!this.enabled('warn')) return;
    this.write(`${pc.yellow('warn')} ${message}${formatRest(rest)}`);
  }

  info(message: string): void {
    if (!this.enabled('info')) return;
    this.write(message);
  }

  success(message: string): void {
    if (!this.enabled('info')) return;
    this.write(`${pc.green('✓')} ${message}`);
  }

  step(message: string): void {
    if (!this.enabled('info')) return;
    this.write(`${pc.cyan('›')} ${message}`);
  }

  dim(message: string): void {
    if (!this.enabled('info')) return;
    this.write(pc.dim(message));
  }

  debug(message: string): void {
    if (!this.enabled('debug')) return;
    this.write(pc.gray(`[debug] ${message}`));
  }

  /** Emits a progress line that can be overwritten by the next call. */
  progress(done: number, total: number, label = ''): void {
    if (!this.enabled('info')) return;
    if (this.json) return;
    const width = 24;
    const ratio = total === 0 ? 1 : Math.min(1, done / total);
    const filled = Math.round(ratio * width);
    const bar = `${'█'.repeat(filled)}${'░'.repeat(width - filled)}`;
    const percent = String(Math.round(ratio * 100)).padStart(3, ' ');
    process.stderr.write(`\r${pc.cyan(bar)} ${percent}% ${label}          `);
    if (done >= total) process.stderr.write('\n');
  }

  /** Machine readable payload, only in --json mode. */
  payload(data: unknown): void {
    if (this.json) process.stdout.write(`${JSON.stringify(data, null, 2)}\n`);
  }

  table(rows: Array<Array<string | number>>, options: { head?: string[] } = {}): void {
    if (!this.enabled('info') || rows.length === 0) return;
    const all = options.head ? [options.head, ...rows] : rows;
    const widths: number[] = [];
    for (const row of all) {
      row.forEach((cell, i) => {
        widths[i] = Math.max(widths[i] ?? 0, displayWidth(String(cell)));
      });
    }
    const render = (row: Array<string | number>): string =>
      row
        .map((cell, i) => pad(String(cell), widths[i] ?? 0))
        .join('  ')
        .replace(/\s+$/u, '');
    if (options.head) this.write(pc.bold(render(options.head)));
    for (const row of rows) this.write(render(row));
  }
}

function formatRest(rest: unknown[]): string {
  if (rest.length === 0) return '';
  return ` ${rest.map((item) => (item instanceof Error ? item.message : String(item))).join(' ')}`;
}

function pad(text: string, width: number): string {
  const diff = width - displayWidth(text);
  return diff > 0 ? `${text}${' '.repeat(diff)}` : text;
}

/** Width aware padding so CJK tables stay aligned in a terminal. */
export function displayWidth(text: string): number {
  let width = 0;
  for (const char of text) {
    const code = char.codePointAt(0) ?? 0;
    width += isWideCodePoint(code) ? 2 : 1;
  }
  return width;
}

function isWideCodePoint(code: number): boolean {
  return (
    (code >= 0x1100 && code <= 0x115f) ||
    (code >= 0x2e80 && code <= 0xa4cf) ||
    (code >= 0xac00 && code <= 0xd7a3) ||
    (code >= 0xf900 && code <= 0xfaff) ||
    (code >= 0xfe30 && code <= 0xfe6f) ||
    (code >= 0xff00 && code <= 0xff60) ||
    (code >= 0xffe0 && code <= 0xffe6) ||
    (code >= 0x1f300 && code <= 0x1f9ff) ||
    (code >= 0x20000 && code <= 0x3fffd)
  );
}

function supportsColor(): boolean {
  if (process.env.NO_COLOR) return false;
  if (process.env.FORCE_COLOR) return true;
  return Boolean(process.stderr.isTTY);
}

export const logger = new Logger();
