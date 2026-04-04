import { WORLD_TILES_PER_IMAGE_TILE, MAX_ZOOM, REGION_SIZE } from '../config';

/**
 * Calibration offset in world tiles.
 * These values are adjusted during calibration to align overlays with imagery.
 * Positive offsetX shifts overlay data to the right (east).
 * Positive offsetY shifts overlay data up (north).
 */
export interface CalibrationState {
  offsetX: number;  // world tile offset for overlay data
  offsetY: number;  // world tile offset for overlay data
  flipY: boolean;   // flip Y-axis in overlay data
  flipX: boolean;   // flip X-axis in overlay data
  swapXY: boolean;  // swap X/Y in data coordinates
}

export const calibration: CalibrationState = {
  offsetX: 0,
  offsetY: 0,
  flipY: false,
  flipX: false,
  swapXY: false,
};

/**
 * Convert world tile coordinates to image tile coordinates at a given zoom level.
 */
export function worldToImageTile(worldX: number, worldY: number, zoom: number) {
  const scale = WORLD_TILES_PER_IMAGE_TILE * Math.pow(2, MAX_ZOOM - zoom);
  return {
    tileX: Math.floor(worldX / scale),
    tileY: Math.floor(worldY / scale),
  };
}

/**
 * Convert world tile coordinates to Leaflet LatLng.
 * In CRS.Simple: lat = y, lng = x.
 * We define 1 Leaflet unit = 1 world tile.
 */
export function worldToLatLng(worldX: number, worldY: number): [number, number] {
  // Leaflet LatLng is [lat, lng] = [y, x]
  return [worldY, worldX];
}

/**
 * Convert Leaflet LatLng to world tile coordinates.
 */
export function latLngToWorld(lat: number, lng: number): { worldX: number; worldY: number } {
  return {
    worldX: Math.floor(lng),
    worldY: Math.floor(lat),
  };
}

/**
 * Apply calibration transform to overlay data coordinates.
 * Takes raw data coordinates (from walkability/wall JSON) and returns
 * the adjusted world coordinates for rendering on the map.
 */
export function applyCalibration(dataX: number, dataY: number, regionOriginX: number, regionOriginY: number): { x: number; y: number } {
  let x = dataX;
  let y = dataY;

  if (calibration.swapXY) {
    [x, y] = [y, x];
  }

  if (calibration.flipX) {
    // Flip within the region: localX becomes (63 - localX)
    const localX = x - regionOriginX;
    x = regionOriginX + (REGION_SIZE - 1 - localX);
  }

  if (calibration.flipY) {
    const localY = y - regionOriginY;
    y = regionOriginY + (REGION_SIZE - 1 - localY);
  }

  x += calibration.offsetX;
  y += calibration.offsetY;

  return { x, y };
}

/**
 * Apply calibration to local coordinates within a region.
 * Returns adjusted local coordinates (0-63).
 */
export function applyCalibrationLocal(localX: number, localY: number): { x: number; y: number } {
  let x = localX;
  let y = localY;

  if (calibration.swapXY) {
    [x, y] = [y, x];
  }

  if (calibration.flipX) {
    x = REGION_SIZE - 1 - x;
  }

  if (calibration.flipY) {
    y = REGION_SIZE - 1 - y;
  }

  return { x, y };
}
