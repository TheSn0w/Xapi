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

        // ── Wall edge editing (delete walls from .dat + JSON) ────────────
        const DAT_REGIONS_DIR = 'E:\\Desktop\\Projects\\Tools\\pathfinder\\navdata\\regions';
        const JSON_REGIONS_DIR = 'D:\\SnowsDecoder\\Walkability\\world_walkability\\regions';

        // BNAV header: 4B magic + 2B version + 4B regionId + 1B plane = 11 bytes
        const BNAV_HEADER_SIZE = 11;
        const FLAG_NORTH = 0x02; // bit 1
        const FLAG_EAST  = 0x04; // bit 2
        const FLAG_SOUTH = 0x08; // bit 3
        const FLAG_WEST  = 0x10; // bit 4

        // Cardinal flag bits
        const DIRECTION_FLAG: Record<string, number> = {
          north: FLAG_NORTH, east: FLAG_EAST, south: FLAG_SOUTH, west: FLAG_WEST,
        };
        const RECIPROCAL: Record<string, { flag: number; dx: number; dy: number; dir: string }> = {
          north: { flag: FLAG_SOUTH, dx: 0,  dy: 1,  dir: 'south' },
          south: { flag: FLAG_NORTH, dx: 0,  dy: -1, dir: 'north' },
          east:  { flag: FLAG_WEST,  dx: 1,  dy: 0,  dir: 'west'  },
          west:  { flag: FLAG_EAST,  dx: -1, dy: 0,  dir: 'east'  },
        };

        // Diagonal flag bits (in the diagFlags array, offset after cardinal flags + transitions + POIs + connectors)
        const DIAG_NE = 0x01, DIAG_NW = 0x02, DIAG_SE = 0x04, DIAG_SW = 0x08;
        // 'nwse' = \ wall blocks NE and SW; 'nesw' = / wall blocks NW and SE
        const DIAG_WALL: Record<string, number> = {
          nwse: DIAG_NE | DIAG_SW,
          nesw: DIAG_NW | DIAG_SE,
        };

        const ALL_DIRECTIONS = ['north', 'south', 'east', 'west', 'nwse', 'nesw'];

        /** Find the diagonal flags offset in a BNAV .dat buffer (v3+ only). */
        function findDiagOffset(datBuf: Buffer): number | null {
          const version = datBuf.readUInt16BE(4);
          if (version < 3) return null;
          let offset = BNAV_HEADER_SIZE + 4096; // skip cardinal flags
          // Skip transitions
          if (offset + 4 > datBuf.length) return null;
          const transCount = datBuf.readInt32BE(offset); offset += 4;
          for (let i = 0; i < transCount; i++) {
            offset += 1; // type
            offset += 4 * 6; // 6 ints (srcX,srcY,srcP,dstX,dstY,dstP)
            offset += 4; // objectId
            // objectName (UTF: 2B length + chars)
            if (offset + 2 > datBuf.length) return null;
            const nameLen = datBuf.readUInt16BE(offset); offset += 2 + nameLen;
            // interactOption (UTF)
            if (offset + 2 > datBuf.length) return null;
            const optLen = datBuf.readUInt16BE(offset); offset += 2 + optLen;
            offset += 4; // costTicks
            // requirements (UTF)
            if (offset + 2 > datBuf.length) return null;
            const reqLen = datBuf.readUInt16BE(offset); offset += 2 + reqLen;
          }
          // Skip POIs
          if (offset + 4 > datBuf.length) return null;
          const poiCount = datBuf.readInt32BE(offset); offset += 4;
          for (let i = 0; i < poiCount; i++) {
            offset += 4 * 3; // x, y, plane
            if (offset + 2 > datBuf.length) return null;
            const pNameLen = datBuf.readUInt16BE(offset); offset += 2 + pNameLen;
            if (offset + 2 > datBuf.length) return null;
            const pTypeLen = datBuf.readUInt16BE(offset); offset += 2 + pTypeLen;
          }
          // Skip connectors
          if (offset + 4 > datBuf.length) return null;
          const connCount = datBuf.readInt32BE(offset); offset += 4;
          offset += connCount * 16; // 4 ints each
          // diagFlags start here
          if (offset + 4096 > datBuf.length) return null;
          return offset;
        }

        /** Shared wall edit handler for both DELETE (remove wall) and POST (add wall). */
        server.middlewares.use('/api/walls', (req, res) => {
          res.setHeader('Access-Control-Allow-Origin', '*');
          res.setHeader('Access-Control-Allow-Methods', 'DELETE, POST, OPTIONS');
          res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
          res.setHeader('Content-Type', 'application/json');

          if (req.method === 'OPTIONS') { res.statusCode = 204; res.end(); return; }
          if (req.method !== 'DELETE' && req.method !== 'POST') { res.statusCode = 405; res.end(); return; }

          const isAdd = req.method === 'POST'; // POST = add wall, DELETE = remove wall

          let body = '';
          req.on('data', (chunk: Buffer) => { body += chunk.toString(); });
          req.on('end', () => {
            try {
              const { regionId, plane, localX, localY, direction } = JSON.parse(body);

              if (!ALL_DIRECTIONS.includes(direction)) {
                res.statusCode = 400;
                res.end(JSON.stringify({ error: 'Invalid direction: ' + direction }));
                return;
              }

              const datPath = path.join(DAT_REGIONS_DIR, `${regionId}_${plane}.dat`);
              if (!fs.existsSync(datPath)) {
                res.statusCode = 404;
                res.end(JSON.stringify({ error: 'DAT file not found' }));
                return;
              }

              const datBuf = Buffer.from(fs.readFileSync(datPath));
              if (datBuf.toString('ascii', 0, 4) !== 'BNAV') {
                res.statusCode = 400;
                res.end(JSON.stringify({ error: 'Invalid BNAV magic' }));
                return;
              }

              const tileIdx = localY * 64 + localX;
              const isDiag = direction === 'nwse' || direction === 'nesw';

              if (isDiag) {
                // ── Diagonal wall ──
                const diagOffset = findDiagOffset(datBuf);
                if (diagOffset === null) {
                  res.statusCode = 400;
                  res.end(JSON.stringify({ error: 'No diagonal flags in this .dat (v2 file)' }));
                  return;
                }
                const mask = DIAG_WALL[direction];
                if (isAdd) {
                  // Add wall = CLEAR diagonal bits (block diagonal movement)
                  datBuf[diagOffset + tileIdx] &= ~mask;
                } else {
                  // Remove wall = SET diagonal bits (allow diagonal movement)
                  datBuf[diagOffset + tileIdx] |= mask;
                }
              } else {
                // ── Cardinal wall ──
                const flagsOffset = BNAV_HEADER_SIZE;
                if (isAdd) {
                  // Add wall = CLEAR the direction flag (block movement)
                  datBuf[flagsOffset + tileIdx] &= ~DIRECTION_FLAG[direction];
                  // CLEAR reciprocal on neighbor
                  const recip = RECIPROCAL[direction];
                  const nX = localX + recip.dx, nY = localY + recip.dy;
                  if (nX >= 0 && nX < 64 && nY >= 0 && nY < 64) {
                    datBuf[flagsOffset + nY * 64 + nX] &= ~recip.flag;
                  }
                } else {
                  // Remove wall = SET the direction flag (allow movement)
                  datBuf[flagsOffset + tileIdx] |= DIRECTION_FLAG[direction];
                  const recip = RECIPROCAL[direction];
                  const nX = localX + recip.dx, nY = localY + recip.dy;
                  if (nX >= 0 && nX < 64 && nY >= 0 && nY < 64) {
                    datBuf[flagsOffset + nY * 64 + nX] |= recip.flag;
                  }
                }
              }

              fs.writeFileSync(datPath, datBuf);

              // ── Update JSON file ──
              const jsonPath = path.join(JSON_REGIONS_DIR, `${regionId}.json`);
              if (fs.existsSync(jsonPath)) {
                const jsonRaw = fs.readFileSync(jsonPath, 'utf-8');
                const jsonData = JSON.parse(jsonRaw);
                const planeData = jsonData.planes?.find((p: any) => p.plane === plane);
                if (planeData && Array.isArray(planeData.wall_edges)) {
                  if (isAdd) {
                    // Add the wall edge entry
                    const orientation = isDiag ? 'diagonal' : 'cardinal';
                    planeData.wall_edges.push({ x: localX, y: localY, direction, orientation });
                    // Add reciprocal for cardinal walls
                    if (!isDiag) {
                      const recip = RECIPROCAL[direction];
                      const nX = localX + recip.dx, nY = localY + recip.dy;
                      if (nX >= 0 && nX < 64 && nY >= 0 && nY < 64) {
                        planeData.wall_edges.push({ x: nX, y: nY, direction: recip.dir, orientation: 'cardinal' });
                      }
                    }
                  } else {
                    // Remove wall edge entries
                    planeData.wall_edges = planeData.wall_edges.filter((w: any) =>
                      !(w.x === localX && w.y === localY && w.direction === direction)
                    );
                    if (!isDiag) {
                      const recip = RECIPROCAL[direction];
                      const nX = localX + recip.dx, nY = localY + recip.dy;
                      if (nX >= 0 && nX < 64 && nY >= 0 && nY < 64) {
                        planeData.wall_edges = planeData.wall_edges.filter((w: any) =>
                          !(w.x === nX && w.y === nY && w.direction === recip.dir)
                        );
                      }
                    }
                  }
                  fs.writeFileSync(jsonPath, JSON.stringify(jsonData, null, 2));
                }
              }

              const action = isAdd ? 'added' : 'removed';
              res.end(JSON.stringify({ ok: true, [action]: { localX, localY, direction } }));
            } catch (e: any) {
              res.statusCode = 500;
              res.end(JSON.stringify({ error: e.message }));
            }
          });
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
            res.setHeader('Cache-Control', 'no-cache');
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
