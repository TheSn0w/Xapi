import { REGION_SIZE } from '../config';

// ── Transitions ────────────────────────────────────────

export interface Transition {
  type: string;
  srcX: number;
  srcY: number;
  srcP: number;
  dstX: number;
  dstY: number;
  dstP: number;
  name: string;
  option: string;
  cost: number;
  bidir: boolean;
  source?: string;
}

let transitionData: Transition[] = [];
let transitionsLoaded = false;

// ── Transition blacklist (non-traversal false positives) ──

/** Types that are always valid traversal — bypass all blacklist checks */
const TRUSTED_TYPES = new Set([
  'DOOR', 'STAIRCASE', 'PORTAL', 'FAIRY_RING', 'SPIRIT_TREE', 'LODESTONE',
  'MINE_CART', 'GNOME_GLIDER', 'BOAT', 'CHARTER_SHIP', 'MAGIC_CARPET',
  'CANOE', 'EAGLE', 'BALLOON', 'BRIDGE', 'WALL_PASSAGE', 'WILDERNESS_OBELISK',
  'TRANSPORT', 'NPC_TRANSPORT', 'TELEPORT', 'ITEM_TELEPORT',
]);

/** Keywords in name that indicate non-traversal containers */
const BLACKLIST_KEYWORDS = [
  'chest', 'trunk', 'cabinet', 'drawer', 'locker', 'bureau',
  'casket', 'strongbox', 'cupboard', 'crate', 'barrel', 'sack',
];

/** Exact names that are non-traversal (levers, switches, altars, platforms, etc.) */
const BLACKLIST_NAMES = new Set([
  // Levers/switches/puzzle mechanics
  'Switch', 'Lever', 'Button', 'An old lever', 'Column Switch',
  'Flame Switch', 'Reset Lever', 'Undo Lever',
  'Pressure release (lawful)', 'Pressure release (chaotic)',
  'Pressure release (good)', 'Pressure release (evil)',
  'Blood lock', 'Blood valve', 'Stone shard', 'Pitch bucket',
  // Altars
  'Air altar', 'Mind altar', 'Water altar', 'Earth altar', 'Fire altar',
  'Body altar', 'Cosmic altar', 'Law altar', 'Nature altar', 'Chaos altar',
  'Death altar', 'Blood altar', 'Soul altar', 'Astral altar', 'Time altar',
  'Runecrafting altar',
  // Non-movement 'Use' objects
  'Fletching workbench', 'An experimental anvil', 'Exercise mat',
  'Pottery oven', 'Pottery Oven', 'Viewing orb', 'Viewing panel',
  'Control panel', "Yewnock's exchanger", 'Darkmeyer Treasury',
  'Bank', 'Bank Box', 'Bonfire', 'Empty bed', 'Full fishing net',
  'Trawler net', 'Mountaineering gear', 'Temple of Ikov',
  'Storage spot (calcified fungus)', 'Storage spot (fungal spores)',
  'Storage spot (fungal algae)', 'Storage spot (timber fungus)',
  'Sword cabinet', 'Apple Barrel', 'Dead man\'s chest', 'Storage Chest',
  // Dungeoneering platforms
  'Dream puff', 'Float platform', 'Mist platform', 'Platform edge',
  'Comet platform', 'Greater missile platform', 'Flesh platform',
  'Skeletal platform', 'Conjuration platform', 'Greater conjuration platform',
  'Platform', 'Shackle Platform',
  // Dungeoneering statues
  'Goblin statue', 'Ork statue', 'Ourg statue', 'Statue', 'Champion statue',
]);

function isTransitionBlacklisted(name: string, type: string): boolean {
  // Trusted types always pass
  if (TRUSTED_TYPES.has(type)) return false;
  // Check exact name blacklist
  if (BLACKLIST_NAMES.has(name)) return true;
  // Check keyword blacklist
  const lower = name.toLowerCase();
  return BLACKLIST_KEYWORDS.some(kw => lower.includes(kw));
}

export async function loadTransitions(): Promise<void> {
  if (transitionsLoaded) return;
  await reloadTransitions();
}

/** Force re-fetch transitions from the server (picks up new Xapi-sent entries). */
export async function reloadTransitions(): Promise<void> {
  try {
    const resp = await fetch('/transitions.json');
    if (!resp.ok) return;
    const data = await resp.json();
    const raw: any[] = data.transitions || [];
    // Filter out non-traversal false positives
    transitionData = raw.filter(t => !isTransitionBlacklisted(t.name, t.type));
    transitionsLoaded = true;
    console.log(`Loaded ${transitionData.length} transitions (filtered ${raw.length - transitionData.length} non-traversal)`);
    for (const cb of loadCallbacks) cb();
  } catch { /* ignore */ }
}

/** A transition matched with context about which side (src or dst) was in bounds */
export interface TransitionMatch {
  transition: Transition;
  /** True if this match was found via the destination coords (bidir dst side, or dst-only teleport) */
  matchedByDst: boolean;
}

export function getTransitionsInBounds(
  minX: number, minY: number, maxX: number, maxY: number, plane: number
): Transition[] {
  return getTransitionMatchesInBounds(minX, minY, maxX, maxY, plane).map(m => m.transition);
}

export function getTransitionMatchesInBounds(
  minX: number, minY: number, maxX: number, maxY: number, plane: number
): TransitionMatch[] {
  const results: TransitionMatch[] = [];

  for (const t of transitionData) {
    const isDstOnly = t.srcX === 0 && t.srcY === 0 && t.srcP === 0;

    // Match by source position (normal case)
    if (!isDstOnly &&
        t.srcX >= minX && t.srcX <= maxX &&
        t.srcY >= minY && t.srcY <= maxY &&
        t.srcP === plane) {
      results.push({ transition: t, matchedByDst: false });
    }

    // Match by destination position for:
    // 1. Destination-only transitions (src=0,0,0) — item teleports, lodestones
    // 2. Bidirectional adjacent same-plane transitions (doors, passages) — show on BOTH sides
    const isAdjacentBidir = t.bidir && t.srcP === t.dstP &&
        Math.abs(t.dstX - t.srcX) + Math.abs(t.dstY - t.srcY) <= 2;
    if ((isDstOnly || isAdjacentBidir) &&
        t.dstX >= minX && t.dstX <= maxX &&
        t.dstY >= minY && t.dstY <= maxY &&
        t.dstP === plane) {
      // Avoid duplicate if src and dst are the same tile (shouldn't happen but be safe)
      if (isDstOnly || t.dstX !== t.srcX || t.dstY !== t.srcY || t.dstP !== t.srcP) {
        results.push({ transition: t, matchedByDst: true });
      }
    }
  }

  return results;
}

export function isTransitionsLoaded(): boolean {
  return transitionsLoaded;
}

/** Get all transitions at a specific tile (by src or dst) */
export function getTransitionsAtTile(worldX: number, worldY: number, plane: number): TransitionMatch[] {
  const results: TransitionMatch[] = [];
  for (const t of transitionData) {
    const isDstOnly = t.srcX === 0 && t.srcY === 0 && t.srcP === 0;
    if (!isDstOnly && t.srcX === worldX && t.srcY === worldY && t.srcP === plane) {
      results.push({ transition: t, matchedByDst: false });
    }
    const isAdjacentBidir2 = t.bidir && t.srcP === t.dstP &&
        Math.abs(t.dstX - t.srcX) + Math.abs(t.dstY - t.srcY) <= 2;
    if ((isDstOnly || isAdjacentBidir2) && t.dstX === worldX && t.dstY === worldY && t.dstP === plane) {
      results.push({ transition: t, matchedByDst: true });
    }
  }
  return results;
}

/** Add a transition via API and update local cache */
export async function addTransition(t: Transition): Promise<boolean> {
  try {
    const resp = await fetch('/api/transitions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ transition: t }),
    });
    if (!resp.ok) return false;
    transitionData.push(t);
    for (const cb of loadCallbacks) cb();
    return true;
  } catch { return false; }
}

/** Move a transition's src or dst coordinates via API and update local cache */
export async function moveTransition(
  t: Transition, side: 'src' | 'dst', newX: number, newY: number
): Promise<boolean> {
  try {
    const resp = await fetch('/api/transitions', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        match: {
          srcX: t.srcX, srcY: t.srcY, srcP: t.srcP,
          dstX: t.dstX, dstY: t.dstY, dstP: t.dstP,
          name: t.name, type: t.type,
        },
        side,
        newX,
        newY,
      }),
    });
    if (!resp.ok) return false;
    // Update local cache
    const found = transitionData.find(x =>
      x.srcX === t.srcX && x.srcY === t.srcY && x.srcP === t.srcP &&
      x.dstX === t.dstX && x.dstY === t.dstY && x.dstP === t.dstP &&
      x.name === t.name && x.type === t.type
    );
    if (found) {
      if (side === 'dst') { found.dstX = newX; found.dstY = newY; }
      else { found.srcX = newX; found.srcY = newY; }
    }
    for (const cb of loadCallbacks) cb();
    return true;
  } catch { return false; }
}

/** Remove a transition via API and update local cache */
export async function removeTransition(t: Transition): Promise<boolean> {
  try {
    const payload: any = {
      srcX: t.srcX, srcY: t.srcY, srcP: t.srcP,
      dstX: t.dstX, dstY: t.dstY, dstP: t.dstP,
      name: t.name, type: t.type,
    };
    if (t.source) payload.source = t.source;
    const resp = await fetch('/api/transitions', {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (!resp.ok) return false;
    // Remove from local cache (match source too)
    const idx = transitionData.findIndex(x =>
      x.srcX === t.srcX && x.srcY === t.srcY && x.srcP === t.srcP &&
      x.dstX === t.dstX && x.dstY === t.dstY && x.dstP === t.dstP &&
      x.name === t.name && x.type === t.type &&
      (t.source ? (x as any).source === t.source : !(x as any).source)
    );
    if (idx >= 0) transitionData.splice(idx, 1);
    for (const cb of loadCallbacks) cb();
    return true;
  } catch { return false; }
}

/** Walkability plane data from preprocessed JSON */
export interface WalkabilityPlane {
  plane: number;
  /** 64 strings of 64 chars each, '#' = blocked, '.' = walkable */
  blocked_rows: string[];
  /** Wall edges with LOCAL coordinates within the region */
  wall_edges: RawWallEdge[];
}

/** Wall edge as stored in the JSON files (local coords, string direction) */
export interface RawWallEdge {
  x: number;       // local X (0-63)
  y: number;       // local Y (0-63)
  direction: 'north' | 'south' | 'east' | 'west' | 'nesw' | 'nwse';
  orientation: 'cardinal' | 'diagonal';
}

/** Wall edge with world coordinates for rendering */
export interface WallEdge {
  wx: number;      // world X
  wy: number;      // world Y
  direction: string;
}

export interface RegionWalkability {
  archive_id: number;
  map_x: number;
  map_z: number;
  grid_size: number;
  plane_count: number;
  planes: WalkabilityPlane[];
}

/** Loaded region data: maps regionId -> RegionWalkability */
const regionCache = new Map<number, RegionWalkability>();

/** Regions currently being fetched (prevent duplicate requests) */
const pendingFetches = new Set<number>();

/** Callbacks to invoke when new regions finish loading */
const loadCallbacks: Array<() => void> = [];

/** Register a callback for when new region data loads */
export function onRegionLoad(cb: () => void): void {
  loadCallbacks.push(cb);
}

/**
 * Load regions visible in the given world coordinate bounds.
 * Fetches any regions not already cached.
 */
export function loadVisibleRegions(minX: number, minY: number, maxX: number, maxY: number): void {
  const minRX = Math.max(0, Math.floor(minX / REGION_SIZE));
  const maxRX = Math.min(255, Math.floor(maxX / REGION_SIZE));
  const minRY = Math.max(0, Math.floor(minY / REGION_SIZE));
  const maxRY = Math.min(255, Math.floor(maxY / REGION_SIZE));

  for (let rx = minRX; rx <= maxRX; rx++) {
    for (let ry = minRY; ry <= maxRY; ry++) {
      const regionId = (rx << 8) | ry;
      if (regionCache.has(regionId) || pendingFetches.has(regionId)) continue;
      fetchRegion(regionId);
    }
  }
}

async function fetchRegion(regionId: number): Promise<void> {
  pendingFetches.add(regionId);
  try {
    const resp = await fetch(`/regions/${regionId}.json`);
    if (!resp.ok) return; // Region doesn't exist, that's fine
    const data: RegionWalkability = await resp.json();
    regionCache.set(regionId, data);
    // Notify listeners
    for (const cb of loadCallbacks) cb();
  } catch {
    // Silently ignore fetch errors (missing regions, network issues)
  } finally {
    pendingFetches.delete(regionId);
  }
}

/** Get cached region data */
export function getRegion(regionId: number): RegionWalkability | undefined {
  return regionCache.get(regionId);
}

/** Get all loaded regions */
export function getAllRegions(): Map<number, RegionWalkability> {
  return regionCache;
}

/**
 * Check if a tile is blocked in a region.
 */
export function isTileBlocked(region: RegionWalkability, localX: number, localY: number, plane: number): boolean | undefined {
  const planeData = region.planes.find(p => p.plane === plane);
  if (!planeData) return undefined;

  if (localY < 0 || localY >= 64 || localX < 0 || localX >= 64) return undefined;

  const row = planeData.blocked_rows[localY];
  if (!row || localX >= row.length) return undefined;

  return row[localX] === '#';
}

/** Evict a region from the cache so it will be re-fetched from the server */
export function invalidateRegion(regionId: number): void {
  regionCache.delete(regionId);
  pendingFetches.delete(regionId);
}

/** Re-fetch a single region from the server (after editing wall data) */
export async function refetchRegion(regionId: number): Promise<void> {
  regionCache.delete(regionId);
  pendingFetches.delete(regionId);
  await fetchRegion(regionId);
}

/** Delete a wall edge via the backend API. Returns true on success. */
type WallDirection = 'north' | 'south' | 'east' | 'west' | 'nwse' | 'nesw';

/** Delete a wall edge via the backend API. Returns true on success. */
export async function removeWallEdge(
  regionId: number, plane: number, localX: number, localY: number,
  direction: WallDirection
): Promise<boolean> {
  try {
    const resp = await fetch('/api/walls', {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ regionId, plane, localX, localY, direction }),
    });
    if (!resp.ok) return false;
    invalidateRegion(regionId);
    return true;
  } catch { return false; }
}

/** Add a wall edge via the backend API. Returns true on success. */
export async function addWallEdge(
  regionId: number, plane: number, localX: number, localY: number,
  direction: WallDirection
): Promise<boolean> {
  try {
    const resp = await fetch('/api/walls', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ regionId, plane, localX, localY, direction }),
    });
    if (!resp.ok) return false;
    invalidateRegion(regionId);
    return true;
  } catch { return false; }
}

/**
 * Get wall edges for a region on a specific plane, converted to world coordinates.
 */
export function getWallEdges(region: RegionWalkability, plane: number): WallEdge[] {
  const planeData = region.planes.find(p => p.plane === plane);
  if (!planeData) return [];

  const originX = region.map_x * REGION_SIZE;
  const originY = region.map_z * REGION_SIZE;

  return planeData.wall_edges
    .map(w => ({
      wx: originX + w.x,
      wy: originY + w.y,
      direction: w.direction,
    }));
}
