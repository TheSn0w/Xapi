import { defineConfig } from 'vite';
import path from 'path';
import fs from 'fs';

export default defineConfig({
  server: {
    port: 5555,
    strictPort: true,
    proxy: {
      // Proxy tile requests to the local filesystem
      // /tiles/p0/7/400-400.webp -> E:\Desktop\Map Render\p0\7\400-400.webp
    },
  },
  plugins: [
    {
      name: 'serve-tiles',
      configureServer(server) {
        server.middlewares.use('/tiles', (req, res, next) => {
          if (!req.url) return next();

          const tilePath = path.join('E:\\Desktop\\Map Render', decodeURIComponent(req.url));

          // Check if file exists
          fs.stat(tilePath, (err, stats) => {
            if (err || !stats.isFile()) {
              res.statusCode = 404;
              res.end();
              return;
            }

            // Serve with immutable cache headers
            res.setHeader('Content-Type', 'image/webp');
            res.setHeader('Cache-Control', 'public, max-age=31536000, immutable');
            res.setHeader('Access-Control-Allow-Origin', '*');

            const stream = fs.createReadStream(tilePath);
            stream.pipe(res);
            stream.on('error', () => {
              res.statusCode = 500;
              res.end();
            });
          });
        });

        // Serve preprocessed data files
        server.middlewares.use('/data', (req, res, next) => {
          if (!req.url) return next();

          const dataPath = path.join(process.cwd(), 'data', decodeURIComponent(req.url));

          fs.stat(dataPath, (err, stats) => {
            if (err || !stats.isFile()) {
              res.statusCode = 404;
              res.end();
              return;
            }

            res.setHeader('Content-Type', 'application/json');
            res.setHeader('Access-Control-Allow-Origin', '*');

            const stream = fs.createReadStream(dataPath);
            stream.pipe(res);
            stream.on('error', () => {
              res.statusCode = 500;
              res.end();
            });
          });
        });

        // Serve transitions data
        const TRANSITIONS_PATH = path.join(process.cwd(), 'data', 'transitions.json');

        server.middlewares.use('/transitions.json', (_req, res) => {
          fs.stat(TRANSITIONS_PATH, (err, stats) => {
            if (err || !stats.isFile()) { res.statusCode = 404; res.end(); return; }
            res.setHeader('Content-Type', 'application/json');
            res.setHeader('Cache-Control', 'no-cache');
            res.setHeader('Access-Control-Allow-Origin', '*');
            fs.createReadStream(TRANSITIONS_PATH).pipe(res);
          });
        });

        // ── Transition duplicate check (must be registered BEFORE /api/transitions)
        server.middlewares.use('/api/transitions/check', (req, res) => {
          res.setHeader('Access-Control-Allow-Origin', '*');
          res.setHeader('Content-Type', 'application/json');

          if (req.method !== 'GET') { res.statusCode = 405; res.end(); return; }

          try {
            const url = new URL(req.url || '', 'http://localhost');
            const srcX = Number(url.searchParams.get('srcX'));
            const srcY = Number(url.searchParams.get('srcY'));
            const srcP = Number(url.searchParams.get('srcP'));
            const dstX = Number(url.searchParams.get('dstX'));
            const dstY = Number(url.searchParams.get('dstY'));
            const dstP = Number(url.searchParams.get('dstP'));
            const name = url.searchParams.get('name') || '';

            const raw = fs.readFileSync(TRANSITIONS_PATH, 'utf-8');
            const data = JSON.parse(raw);
            const exists = data.transitions.some((t: any) =>
              t.srcX === srcX && t.srcY === srcY && t.srcP === srcP &&
              t.dstX === dstX && t.dstY === dstY && t.dstP === dstP &&
              t.name === name
            );
            res.end(JSON.stringify({ exists }));
          } catch (e: any) {
            res.statusCode = 500;
            res.end(e.message);
          }
        });

        // API: Add/Remove transitions
        server.middlewares.use('/api/transitions', (req, res) => {
          if (req.method !== 'POST' && req.method !== 'DELETE' && req.method !== 'PUT') {
            res.statusCode = 405;
            res.end();
            return;
          }

          let body = '';
          req.on('data', (chunk: Buffer) => { body += chunk.toString(); });
          req.on('end', () => {
            try {
              const payload = JSON.parse(body);
              const raw = fs.readFileSync(TRANSITIONS_PATH, 'utf-8');
              const data = JSON.parse(raw);

              if (req.method === 'POST') {
                const t = payload.transition;
                if (!t) { res.statusCode = 400; res.end('Missing transition'); return; }
                data.transitions.push(t);
                fs.writeFileSync(TRANSITIONS_PATH, JSON.stringify(data, null, 2));
                res.setHeader('Content-Type', 'application/json');
                res.end(JSON.stringify({ ok: true }));
              } else if (req.method === 'PUT') {
                // PUT — move a transition (update src or dst coordinates)
                const { match, side, newX, newY } = payload;
                if (!match || !side) { res.statusCode = 400; res.end('Missing match/side'); return; }
                const found = data.transitions.find((t: any) =>
                  t.srcX === match.srcX && t.srcY === match.srcY && t.srcP === match.srcP &&
                  t.dstX === match.dstX && t.dstY === match.dstY && t.dstP === match.dstP &&
                  t.name === match.name && t.type === match.type
                );
                if (!found) { res.statusCode = 404; res.end('Transition not found'); return; }
                if (side === 'dst') { found.dstX = newX; found.dstY = newY; }
                else { found.srcX = newX; found.srcY = newY; }
                fs.writeFileSync(TRANSITIONS_PATH, JSON.stringify(data, null, 2));
                res.setHeader('Content-Type', 'application/json');
                res.end(JSON.stringify({ ok: true }));
              } else {
                // DELETE — match by key fields (+ source when provided)
                const { srcX, srcY, srcP, dstX, dstY, dstP, name, type, source } = payload;
                const before = data.transitions.length;
                data.transitions = data.transitions.filter((t: any) => {
                  const keyMatch = t.srcX === srcX && t.srcY === srcY && t.srcP === srcP &&
                    t.dstX === dstX && t.dstY === dstY && t.dstP === dstP &&
                    t.name === name && t.type === type;
                  if (!keyMatch) return true;
                  // If source specified, only delete entries with that source
                  if (source !== undefined) return (t.source || '') !== source;
                  // If no source specified, only delete entries without a source
                  return !!t.source;
                });
                const removed = before - data.transitions.length;
                fs.writeFileSync(TRANSITIONS_PATH, JSON.stringify(data, null, 2));
                res.setHeader('Content-Type', 'application/json');
                res.end(JSON.stringify({ ok: true, removed }));
              }
            } catch (e: any) {
              res.statusCode = 500;
              res.end(e.message);
            }
          });
        });

        // ── Player position tracking (from Xapi live capture) ────────────
        let playerPosition = { x: 0, y: 0, plane: 0, timestamp: 0 };

        server.middlewares.use('/api/player-position', (req, res) => {
          res.setHeader('Access-Control-Allow-Origin', '*');
          res.setHeader('Content-Type', 'application/json');

          if (req.method === 'POST') {
            let body = '';
            req.on('data', (chunk: Buffer) => { body += chunk.toString(); });
            req.on('end', () => {
              try {
                const { x, y, plane } = JSON.parse(body);
                playerPosition = { x, y, plane, timestamp: Date.now() };
                res.end(JSON.stringify({ ok: true }));
              } catch (e: any) {
                res.statusCode = 400;
                res.end(e.message);
              }
            });
          } else if (req.method === 'GET') {
            res.end(JSON.stringify(playerPosition));
          } else {
            res.statusCode = 405;
            res.end();
          }
        });

        // Serve individual region walkability files on-demand
        server.middlewares.use('/regions', (req, res, next) => {
          if (!req.url) return next();

          const regionPath = path.join(
            'D:\\SnowsDecoder\\Walkability\\world_walkability\\regions',
            decodeURIComponent(req.url)
          );

          fs.stat(regionPath, (err, stats) => {
            if (err || !stats.isFile()) {
              res.statusCode = 404;
              res.end();
              return;
            }

            res.setHeader('Content-Type', 'application/json');
            res.setHeader('Cache-Control', 'public, max-age=31536000, immutable');
            res.setHeader('Access-Control-Allow-Origin', '*');

            const stream = fs.createReadStream(regionPath);
            stream.pipe(res);
            stream.on('error', () => {
              res.statusCode = 500;
              res.end();
            });
          });
        });
      },
    },
  ],
});
