/** Typed error used across the CLI so exit codes stay predictable. */
export class LingoFlowError extends Error {
  readonly code: string;
  readonly hint?: string;
  readonly exitCode: number;

  constructor(message: string, options: { code?: string; hint?: string; exitCode?: number; cause?: unknown } = {}) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = 'LingoFlowError';
    this.code = options.code ?? 'LINGOFLOW_ERROR';
    this.hint = options.hint;
    this.exitCode = options.exitCode ?? 1;
  }
}

export function isLingoFlowError(error: unknown): error is LingoFlowError {
  return error instanceof LingoFlowError;
}

export function toError(error: unknown): Error {
  if (error instanceof Error) return error;
  return new Error(typeof error === 'string' ? error : JSON.stringify(error));
}

/** Guard used by engines: a missing feature should degrade, never crash the run. */
export function notImplemented(what: string): never {
  throw new LingoFlowError(`${what} is not implemented yet`, { code: 'NOT_IMPLEMENTED' });
}
