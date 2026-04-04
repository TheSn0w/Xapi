/**
 * Fix stile transitions in transitions.json using correct cache placement data.
 *
 * Problem: `rotation_computed` stile entries used Python-style centrepiece rotation
 * mapping which assigns WRONG direction for rot 0 (south→should be north) and
 * rot 1 (west→should be east).
 *
 * Fix: rotation % 2 determines the crossing axis (even=N-S, odd=E-W).
 * The stile sits on ONE boundary of the object tile, always the positive side:
 *   rot 0/2: stile on NORTH boundary → src=(x,y) dst=(x, y+1)
 *   rot 1/3: stile on EAST boundary  → src=(x,y) dst=(x+1, y)
 *
 * Verified empirically against tile imagery for both Lumbridge and Falador stiles.
 *
 * Usage: npx tsx scripts/fix-stile-transitions.ts
 */
import fs from 'fs';

const LOCATIONS_FILE = 'E:/Desktop/Projects/Tools/map-editor/data/map-locations-all.json';
const TRANSITIONS_FILE = 'D:/SnowsDecoder/Walkability/transitions.json';
const STILE_LOC_ID = '112215';

interface CachePlacement {
  x: number;
  y: number;
  plane: number;
  type: number;
  rotation: number;
}

interface Transition {
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
  [key: string]: any;
}

/**
 * Compute the single correct transition for a stile centrepiece object.
 *
 * Stiles sit on ONE tile boundary. rotation % 2 determines the crossing axis.
 * The stile boundary is always on the positive side of the object tile:
 *   Even rotation (0,2): N-S crossing → stile on north boundary
 *   Odd rotation (1,3):  E-W crossing → stile on east boundary
 *
 * Returns: src = object tile, dst = positive neighbor (with bidir=true, reverse is implied)
 */
function computeStileTransition(placement: CachePlacement): { srcX: number; srcY: number; dstX: number; dstY: number } {
  const { x, y, rotation } = placement;

  if (rotation % 2 === 0) {
    // N-S crossing: stile on north boundary of tile (x,y)
    return { srcX: x, srcY: y, dstX: x, dstY: y + 1 };
  } else {
    // E-W crossing: stile on east boundary of tile (x,y)
    return { srcX: x, srcY: y, dstX: x + 1, dstY: y };
  }
}

function main() {
  // Load cache placements for stiles
  console.log('Loading cache placements...');
  const locData: Record<string, CachePlacement[]> = JSON.parse(fs.readFileSync(LOCATIONS_FILE, 'utf-8'));
  const stilePlacements = locData[STILE_LOC_ID] || [];
  console.log(`Found ${stilePlacements.length} stile placements in cache`);

  // Build lookup by (x, y, plane) for matching
  const placementMap = new Map<string, CachePlacement>();
  for (const p of stilePlacements) {
    placementMap.set(`${p.x},${p.y},${p.plane}`, p);
  }

  // Load transitions
  console.log('Loading transitions...');
  const transData = JSON.parse(fs.readFileSync(TRANSITIONS_FILE, 'utf-8'));
  const transitions: Transition[] = transData.transitions;
  console.log(`Total transitions: ${transitions.length}`);

  // Separate stile rotation_computed/cache_recomputed entries from everything else
  const oldStiles: Transition[] = [];
  const otherTransitions: Transition[] = [];

  for (const t of transitions) {
    if (t.name === 'Stile' && (t.source === 'rotation_computed' || t.source === 'cache_recomputed' || t.source === 'cache_fixed')) {
      oldStiles.push(t);
    } else {
      otherTransitions.push(t);
    }
  }
  console.log(`Found ${oldStiles.length} stile entries to fix/replace`);

  // Deduplicate: get unique stile positions from old entries
  const oldStilePositions = new Set<string>();
  for (const old of oldStiles) {
    oldStilePositions.add(`${old.srcX},${old.srcY},${old.srcP}`);
  }

  // Track which cache placements we've processed
  const processedPlacements = new Set<string>();

  // Recompute from cache for each known stile position
  const newStileEntries: Transition[] = [];
  let matched = 0;
  let unmatched = 0;

  for (const posKey of oldStilePositions) {
    const placement = placementMap.get(posKey);
    if (placement) {
      matched++;
      processedPlacements.add(posKey);

      const c = computeStileTransition(placement);
      // Find an old entry to preserve metadata
      const old = oldStiles.find(t => `${t.srcX},${t.srcY},${t.srcP}` === posKey)!;

      newStileEntries.push({
        type: 'AGILITY',
        srcX: c.srcX,
        srcY: c.srcY,
        srcP: old.srcP,
        dstX: c.dstX,
        dstY: c.dstY,
        dstP: old.dstP,
        name: 'Stile',
        option: old.option,
        cost: old.cost,
        bidir: true,
        source: 'cache_fixed',
      });

      // Log corrections
      const oldEntry = oldStiles.find(t => `${t.srcX},${t.srcY},${t.srcP}` === posKey)!;
      const oldDx = oldEntry.dstX - oldEntry.srcX;
      const oldDy = oldEntry.dstY - oldEntry.srcY;
      const newDx = c.dstX - c.srcX;
      const newDy = c.dstY - c.srcY;
      if (oldDx !== newDx || oldDy !== newDy) {
        console.log(`  Fixed (${c.srcX},${c.srcY}): was dx=${oldDx},dy=${oldDy} → now dx=${newDx},dy=${newDy}`);
      }
    } else {
      unmatched++;
      // Keep original entries ONLY if they're from rotation_computed (original data).
      // Drop cache_recomputed entries — those are artifacts from a previous fix attempt.
      const olds = oldStiles.filter(t => `${t.srcX},${t.srcY},${t.srcP}` === posKey);
      const originals = olds.filter(t => t.source === 'rotation_computed');
      if (originals.length > 0) {
        console.warn(`  No cache data for stile at ${posKey} — keeping original`);
        otherTransitions.push(originals[0]);
      } else {
        console.warn(`  Dropping orphaned cache_recomputed stile at ${posKey}`);
      }
    }
  }

  // Check for cache stile placements not in our transition data
  let newFromCache = 0;
  for (const [key, placement] of placementMap) {
    if (processedPlacements.has(key)) continue;
    const [x, y, p] = key.split(',').map(Number);
    const existing = otherTransitions.some(t =>
      t.name === 'Stile' && t.srcX === x && t.srcY === y && t.srcP === p
    );
    if (!existing) {
      newFromCache++;
      const c = computeStileTransition(placement);
      newStileEntries.push({
        type: 'AGILITY',
        srcX: c.srcX,
        srcY: c.srcY,
        srcP: p,
        dstX: c.dstX,
        dstY: c.dstY,
        dstP: p,
        name: 'Stile',
        option: 'Climb over',
        cost: 2,
        bidir: true,
        source: 'cache_fixed',
      });
      console.log(`  Added new stile from cache: (${c.srcX},${c.srcY}) → (${c.dstX},${c.dstY})`);
    }
  }

  console.log(`\nSummary: Matched=${matched}, Unmatched=${unmatched}, New=${newFromCache}`);
  console.log(`New stile entries: ${newStileEntries.length} (1 per stile)`);

  // Combine and write
  const finalTransitions = [...otherTransitions, ...newStileEntries];
  console.log(`Final total: ${finalTransitions.length} transitions (was ${transitions.length})`);

  // Write back
  transData.transitions = finalTransitions;
  fs.writeFileSync(TRANSITIONS_FILE, JSON.stringify(transData, null, 2));
  console.log('Written corrected transitions.json');
}

main();
