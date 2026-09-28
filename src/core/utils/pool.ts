/** Bounded-concurrency async map. Keeps provider rate limits predictable. */
export async function mapPool<T, R>(
  items: readonly T[],
  limit: number,
  worker: (item: T, index: number) => Promise<R>,
  onProgress?: (done: number, total: number) => void,
): Promise<R[]> {
  const total = items.length;
  const results = new Array<R>(total);
  let cursor = 0;
  let done = 0;
  const size = Math.max(1, Math.min(limit || 1, total || 1));

  const run = async (): Promise<void> => {
    for (;;) {
      const index = cursor;
      cursor += 1;
      if (index >= total) return;
      const item = items[index] as T;
      results[index] = await worker(item, index);
      done += 1;
      onProgress?.(done, total);
    }
  };

  await Promise.all(Array.from({ length: size }, () => run()));
  return results;
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Retries with exponential backoff; used for flaky provider calls. */
export async function retry<T>(
  fn: () => Promise<T>,
  options: { attempts?: number; baseDelayMs?: number; onRetry?: (error: unknown, attempt: number) => void } = {},
): Promise<T> {
  const attempts = options.attempts ?? 3;
  const base = options.baseDelayMs ?? 400;
  let lastError: unknown;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return await fn();
    } catch (error) {
      lastError = error;
      if (attempt === attempts) break;
      options.onRetry?.(error, attempt);
      await sleep(base * 2 ** (attempt - 1));
    }
  }
  throw lastError;
}
