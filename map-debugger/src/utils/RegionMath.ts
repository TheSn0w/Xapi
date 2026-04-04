import { REGION_SIZE } from '../config';

/** Compute region ID from world coordinates */
export function worldToRegionId(worldX: number, worldY: number): number {
  return ((worldX >> 6) << 8) | (worldY >> 6);
}

/** Extract regionX (map_x) from region ID */
export function regionIdToRegionX(regionId: number): number {
  return regionId >> 8;
}

/** Extract regionY (map_z) from region ID */
export function regionIdToRegionY(regionId: number): number {
  return regionId & 0xFF;
}

/** Get world coordinate of the SW corner of a region */
export function regionToWorldOrigin(regionId: number): { worldX: number; worldY: number } {
  return {
    worldX: regionIdToRegionX(regionId) * REGION_SIZE,
    worldY: regionIdToRegionY(regionId) * REGION_SIZE,
  };
}

/** Get local coordinates within region */
export function worldToLocal(worldX: number, worldY: number): { localX: number; localY: number } {
  return {
    localX: worldX & 63,
    localY: worldY & 63,
  };
}

/** Tile index within a 64x64 region grid */
export function tileIndex(localX: number, localY: number): number {
  return localY * REGION_SIZE + localX;
}
