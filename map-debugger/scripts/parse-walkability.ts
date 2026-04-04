/**
 * Parse walkability data for 9 calibration regions around Lumbridge.
 * Reads from D:\SnowsDecoder\Walkability\world_walkability\regions\
 * Outputs to data/calibration-regions.json
 */
import fs from 'fs';
import path from 'path';

const WALKABILITY_DIR = 'D:\\SnowsDecoder\\Walkability\\world_walkability\\regions';
const OUTPUT_DIR = path.join(process.cwd(), 'data');
const OUTPUT_FILE = path.join(OUTPUT_DIR, 'calibration-regions.json');

// 3x3 grid centered on Lumbridge (map_x=50, map_z=50)
const CENTER_X = 50;
const CENTER_Z = 50;
const RADIUS = 1;

function getCalibrationRegionIds(): number[] {
  const ids: number[] = [];
  for (let dx = -RADIUS; dx <= RADIUS; dx++) {
    for (let dz = -RADIUS; dz <= RADIUS; dz++) {
      const mx = CENTER_X + dx;
      const mz = CENTER_Z + dz;
      ids.push((mx << 8) | mz);
    }
  }
  return ids;
}

async function main() {
  console.log('Parsing walkability data for calibration regions...');
  console.log(`Source: ${WALKABILITY_DIR}`);
  console.log(`Output: ${OUTPUT_FILE}`);

  // Ensure output directory exists
  if (!fs.existsSync(OUTPUT_DIR)) {
    fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  }

  const regionIds = getCalibrationRegionIds();
  console.log(`Region IDs: ${regionIds.join(', ')}`);

  const regions: any[] = [];

  for (const id of regionIds) {
    const filePath = path.join(WALKABILITY_DIR, `${id}.json`);

    if (!fs.existsSync(filePath)) {
      console.warn(`  MISSING: ${filePath}`);
      continue;
    }

    const data = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
    regions.push(data);

    const planeCount = data.plane_count || data.planes?.length || 0;
    const blockedTotal = data.blocked_tile_count_total || 0;
    const wallTotal = data.wall_edge_count_total || 0;
    console.log(`  Loaded region ${id} (map_x=${data.map_x}, map_z=${data.map_z}) - ${planeCount} planes, ${blockedTotal} blocked, ${wallTotal} walls`);
  }

  console.log(`\nWriting ${regions.length} regions to ${OUTPUT_FILE}...`);
  fs.writeFileSync(OUTPUT_FILE, JSON.stringify(regions, null, 2));

  // Print summary
  let totalBlocked = 0;
  let totalWalls = 0;
  for (const r of regions) {
    for (const p of (r.planes || [])) {
      totalBlocked += (p.blocked_rows || []).reduce((sum: number, row: string) =>
        sum + [...row].filter(c => c === '#').length, 0);
      totalWalls += (p.wall_edges || []).length;
    }
  }

  console.log(`\nSummary:`);
  console.log(`  Regions: ${regions.length}`);
  console.log(`  Total blocked tiles (all planes): ${totalBlocked}`);
  console.log(`  Total wall edges (all planes): ${totalWalls}`);
  console.log('\nDone!');
}

main().catch(console.error);
