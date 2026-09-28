import { promises as fs, watch } from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { URL } from 'node:url';
import { logger } from '../core/logger';

export interface PreviewServerOptions {
  dir: string;
  port: number;
  host?: string;
  /** Called when a watched file changes; should regenerate the report. */
  rebuild?: () => Promise<void>;
  watchDirs?: string[];
}

export interface PreviewServer {
  url: string;
  close: () => Promise<void>;
}

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.md': 'text/markdown; charset=utf-8',
  '.sarif': 'application/json; charset=utf-8',
};

const LIVE_RELOAD_SCRIPT = `
<script>
(function () {
  var source = new EventSource('/__lingoflow/livereload');
  source.addEventListener('reload', function () { location.reload(); });
})();
</script>
`;

/**
 * Serves the generated report with live reload.
 * `lingoflow preview --serve` turns the report into a local, private review UI.
 */
export async function startPreviewServer(options: PreviewServerOptions): Promise<PreviewServer> {
  const host = options.host ?? '127.0.0.1';
  const clients = new Set<http.ServerResponse>();
  let rebuilding = false;
  let pending = false;

  const server = http.createServer(async (request, response) => {
    const url = new URL(request.url ?? '/', `http://${host}`);
    if (url.pathname === '/__lingoflow/livereload') {
      response.writeHead(200, {
        'content-type': 'text/event-stream',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
      });
      response.write('retry: 1000\n\n');
      clients.add(response);
      request.on('close', () => clients.delete(response));
      return;
    }
    const relative = decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname);
    const target = path.join(options.dir, relative);
    if (!target.startsWith(options.dir)) {
      response.writeHead(403).end('forbidden');
      return;
    }
    try {
      const body = await fs.readFile(target);
      const type = MIME[path.extname(target).toLowerCase()] ?? 'application/octet-stream';
      if (type.startsWith('text/html')) {
        const html = body.toString('utf8').replace('</body>', `${LIVE_RELOAD_SCRIPT}</body>`);
        response.writeHead(200, { 'content-type': type, 'cache-control': 'no-store' }).end(html);
        return;
      }
      response.writeHead(200, { 'content-type': type, 'cache-control': 'no-store' }).end(body);
    } catch {
      response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' }).end('not found');
    }
  });

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(options.port, host, () => resolve());
  });
  const address = server.address();
  const port = typeof address === 'object' && address ? address.port : options.port;
  const url = `http://${host}:${port}/`;

  const watchers: Array<ReturnType<typeof watch>> = [];
  if (options.rebuild && options.watchDirs) {
    const trigger = async (): Promise<void> => {
      if (rebuilding) {
        pending = true;
        return;
      }
      rebuilding = true;
      try {
        await options.rebuild?.();
        for (const client of clients) client.write('event: reload\ndata: {}\n\n');
        logger.info('report rebuilt');
      } catch (error) {
        logger.error(`rebuild failed: ${(error as Error).message}`);
      } finally {
        rebuilding = false;
        if (pending) {
          pending = false;
          void trigger();
        }
      }
    };
    let timer: NodeJS.Timeout | null = null;
    const schedule = (): void => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => void trigger(), 350);
    };
    for (const dir of options.watchDirs) {
      try {
        const watcher = watch(dir, { recursive: true }, schedule);
        watchers.push(watcher);
      } catch {
        // Directories that cannot be watched are skipped silently.
      }
    }
  }

  return {
    url,
    close: async () => {
      for (const watcher of watchers) watcher.close();
      for (const client of clients) client.end();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    },
  };
}
