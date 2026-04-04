/** Tile imagery base URL (served by Vite middleware) */
export const TILE_BASE_URL = '/tiles';

/** Preprocessed data base URL */
export const DATA_BASE_URL = '/data';

/** Tile image dimensions in pixels */
export const PX_PER_TILE = 1024;

/** World tiles covered by one image tile side at max zoom */
export const WORLD_TILES_PER_IMAGE_TILE = 8;

/** Pixels per world tile at max zoom (1024 / 8) */
export const PX_PER_WORLD_TILE = PX_PER_TILE / WORLD_TILES_PER_IMAGE_TILE;

/** Region side length in world tiles */
export const REGION_SIZE = 64;

/** Image tiles per region side at max zoom (64 / 8) */
export const IMAGE_TILES_PER_REGION = REGION_SIZE / WORLD_TILES_PER_IMAGE_TILE;

/** Max zoom level (highest detail) */
export const MAX_ZOOM = 7;

/** Min zoom level (most zoomed out with content) */
export const MIN_ZOOM = -1;

/** Number of planes */
export const PLANE_COUNT = 4;

/**
 * 3x3 calibration region grid centered on Lumbridge.
 * Center region: 12850 (map_x=50, map_z=50), world (3200, 3200).
 */
export const CALIBRATION_CENTER = { mapX: 50, mapZ: 50 };
export const CALIBRATION_RADIUS = 1; // 1 = 3x3

/** Compute the 9 region IDs for calibration */
export function getCalibrationRegionIds(): number[] {
  const ids: number[] = [];
  for (let dx = -CALIBRATION_RADIUS; dx <= CALIBRATION_RADIUS; dx++) {
    for (let dz = -CALIBRATION_RADIUS; dz <= CALIBRATION_RADIUS; dz++) {
      const mx = CALIBRATION_CENTER.mapX + dx;
      const mz = CALIBRATION_CENTER.mapZ + dz;
      ids.push((mx << 8) | mz);
    }
  }
  return ids;
}
