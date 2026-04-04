import { Viewport } from './Viewport';
import { calibration, applyCalibrationLocal } from './CoordinateTransform';
import { TILE_BASE_URL, REGION_SIZE } from '../config';
import { getAllRegions, isTileBlocked, getWallEdges } from '../data/DataManager';
import { regionToWorldOrigin, regionIdToRegionX, regionIdToRegionY } from '../utils/RegionMath';

/** Tile image cache */
const tileCache = new Map<string, HTMLImageElement>();

/** Set of tile keys currently being loaded */
const loadingTiles = new Set<string>();

/** Callback to request a re-render when a tile loads */
let onTileLoaded: (() => void) | null = null;

/** Layer visibility state */
export interface LayerState {
  walkability: boolean;
  walls: boolean;
  regionGrid: boolean;
  debugGrid: boolean;
  walkabilityOpacity: number;
  wallOpacity: number;
}

function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}

/**
 * Determine the best imagery zoom level for the current viewport zoom.
 * At imagery zoom z, each 1024px tile covers 1024/2^z world tiles.
 * We want tile pixel density >= screen pixel density.
 */
function bestTileZoom(pxPerWorldTile: number): number {
  const z = Math.floor(Math.log2(pxPerWorldTile));
  return clamp(z, -1, 7);
}

/** Load a tile image asynchronously */
function loadTile(key: string): void {
  if (loadingTiles.has(key)) return;
  loadingTiles.add(key);

  const img = new Image();
  img.crossOrigin = 'anonymous';
  img.src = `${TILE_BASE_URL}/${key}.webp`;
  img.onload = () => {
    tileCache.set(key, img);
    loadingTiles.delete(key);
    onTileLoaded?.();
  };
  img.onerror = () => {
    // Cache a 1x1 transparent image so we don't retry
    const placeholder = new Image();
    placeholder.width = 1;
    placeholder.height = 1;
    tileCache.set(key, placeholder);
    loadingTiles.delete(key);
  };
}

/** Last computed tile zoom (exposed for debug) */
let lastTileZoom = 0;

/** Get the current tile zoom level */
export function getCurrentTileZoom(): number { return lastTileZoom; }

/**
 * Total world height in tiles (200 mapsquares * 64 tiles = 12800).
 * Tile files use inverted Y: file_y=0 is the north edge, increasing southward.
 * We compute file_y = totalTilesY - 1 - worldTileY to get the correct file.
 */
const TOTAL_WORLD_Y = 12800;

/** Draw map tile imagery */
function drawTiles(ctx: CanvasRenderingContext2D, vp: Viewport, plane: number, showDebugGrid: boolean): void {
  const z = bestTileZoom(vp.zoom);
  lastTileZoom = z;
  const tileWorldSize = 1024 / Math.pow(2, z);
  const bounds = vp.getVisibleBounds();
  const totalTilesY = Math.floor(TOTAL_WORLD_Y / tileWorldSize);

  const minTX = Math.floor(bounds.minX / tileWorldSize);
  const maxTX = Math.floor(bounds.maxX / tileWorldSize);
  const minTY = Math.floor(bounds.minY / tileWorldSize);
  const maxTY = Math.floor(bounds.maxY / tileWorldSize);

  for (let ty = minTY; ty <= maxTY; ty++) {
    for (let tx = minTX; tx <= maxTX; tx++) {
      if (tx < 0 || ty < 0) continue;

      // Invert Y for file name: tile files have Y=0 at north, increasing south
      const fileY = totalTilesY - 1 - ty;
      if (fileY < 0) continue;

      const key = `p${plane}/${z}/${tx}-${fileY}`;
      const img = tileCache.get(key);

      // World coords of this tile's SW corner
      const worldX = tx * tileWorldSize;
      const worldY = ty * tileWorldSize;

      // Screen position: top-left = worldToScreen(worldX, worldY + tileWorldSize)
      // because screen Y=0 is top, world Y increases upward
      const sx = vp.worldToScreenX(worldX);
      const sy = vp.worldToScreenY(worldY + tileWorldSize);
      const size = tileWorldSize * vp.zoom;

      if (img && img.complete && img.naturalWidth > 1) {
        ctx.drawImage(img, sx, sy, size, size);
      } else if (!img) {
        loadTile(key);
      }

      // Debug tile grid: show tile boundaries and coordinates
      if (showDebugGrid) {
        ctx.strokeStyle = 'rgba(255, 255, 0, 0.5)';
        ctx.lineWidth = 1;
        ctx.strokeRect(sx, sy, size, size);

        if (size > 60) {
          ctx.fillStyle = 'rgba(255, 255, 0, 0.8)';
          ctx.font = '11px monospace';
          ctx.textAlign = 'left';
          ctx.textBaseline = 'top';
          ctx.fillText(`z${z} file(${tx},${fileY}) wld(${tx},${ty})`, sx + 4, sy + 4);
          ctx.fillText(`w(${worldX},${worldY})`, sx + 4, sy + 18);
        }
      }
    }
  }
}

/** Draw walkability overlay (blocked tiles as red rectangles) */
function drawWalkability(ctx: CanvasRenderingContext2D, vp: Viewport, plane: number, opacity: number): void {
  const bounds = vp.getVisibleBounds();
  const tilePx = vp.zoom;
  if (tilePx < 1) return;

  ctx.fillStyle = `rgba(255, 0, 0, ${opacity})`;

  for (const [regionId, region] of getAllRegions()) {
    const origin = regionToWorldOrigin(regionId);
    if (origin.worldX + REGION_SIZE < bounds.minX || origin.worldX > bounds.maxX) continue;
    if (origin.worldY + REGION_SIZE < bounds.minY || origin.worldY > bounds.maxY) continue;

    for (let ly = 0; ly < REGION_SIZE; ly++) {
      for (let lx = 0; lx < REGION_SIZE; lx++) {
        if (!isTileBlocked(region, lx, ly, plane)) continue;

        const cal = applyCalibrationLocal(lx, ly);
        const wx = origin.worldX + cal.x + calibration.offsetX;
        const wy = origin.worldY + cal.y + calibration.offsetY;

        if (wx < bounds.minX - 1 || wx > bounds.maxX + 1) continue;
        if (wy < bounds.minY - 1 || wy > bounds.maxY + 1) continue;

        const sx = vp.worldToScreenX(wx);
        const sy = vp.worldToScreenY(wy + 1);
        ctx.fillRect(sx, sy, tilePx, tilePx);
      }
    }
  }
}

/** Draw wall edge overlay */
function drawWalls(ctx: CanvasRenderingContext2D, vp: Viewport, plane: number, opacity: number): void {
  const bounds = vp.getVisibleBounds();
  const tilePx = vp.zoom;
  if (tilePx < 4) return;

  ctx.strokeStyle = `rgba(255, 255, 0, ${opacity})`;
  ctx.lineWidth = Math.max(1, tilePx / 16);

  for (const [_regionId, region] of getAllRegions()) {
    const walls = getWallEdges(region, plane);

    for (const wall of walls) {
      const wx = wall.x + calibration.offsetX;
      const wy = wall.y + calibration.offsetY;

      if (wx < bounds.minX - 1 || wx > bounds.maxX + 1) continue;
      if (wy < bounds.minY - 1 || wy > bounds.maxY + 1) continue;

      // Tile corners in screen space
      const x0 = vp.worldToScreenX(wx);
      const x1 = vp.worldToScreenX(wx + 1);
      const y0 = vp.worldToScreenY(wy + 1); // top (north)
      const y1 = vp.worldToScreenY(wy);     // bottom (south)

      ctx.beginPath();
      switch (wall.d) {
        case 0: // North
          ctx.moveTo(x0, y0);
          ctx.lineTo(x1, y0);
          break;
        case 2: // East
          ctx.moveTo(x1, y0);
          ctx.lineTo(x1, y1);
          break;
        case 4: // South
          ctx.moveTo(x0, y1);
          ctx.lineTo(x1, y1);
          break;
        case 6: // West
          ctx.moveTo(x0, y0);
          ctx.lineTo(x0, y1);
          break;
        case 1: // NE diagonal
          ctx.moveTo(x0 + (x1 - x0) * 0.7, y0);
          ctx.lineTo(x1, y0 + (y1 - y0) * 0.3);
          break;
        case 3: // SE diagonal
          ctx.moveTo(x1, y0 + (y1 - y0) * 0.7);
          ctx.lineTo(x0 + (x1 - x0) * 0.7, y1);
          break;
        case 5: // SW diagonal
          ctx.moveTo(x0 + (x1 - x0) * 0.3, y1);
          ctx.lineTo(x0, y0 + (y1 - y0) * 0.7);
          break;
        case 7: // NW diagonal
          ctx.moveTo(x0, y0 + (y1 - y0) * 0.3);
          ctx.lineTo(x0 + (x1 - x0) * 0.3, y0);
          break;
      }
      ctx.stroke();
    }
  }
}

/** Draw region grid overlay */
function drawRegionGrid(ctx: CanvasRenderingContext2D, vp: Viewport): void {
  const bounds = vp.getVisibleBounds();
  const tilePx = vp.zoom;

  for (const [regionId] of getAllRegions()) {
    const origin = regionToWorldOrigin(regionId);
    const rx = regionIdToRegionX(regionId);
    const ry = regionIdToRegionY(regionId);

    if (origin.worldX + REGION_SIZE < bounds.minX || origin.worldX > bounds.maxX) continue;
    if (origin.worldY + REGION_SIZE < bounds.minY || origin.worldY > bounds.maxY) continue;

    const x0 = vp.worldToScreenX(origin.worldX);
    const x1 = vp.worldToScreenX(origin.worldX + REGION_SIZE);
    const y0 = vp.worldToScreenY(origin.worldY + REGION_SIZE); // top
    const y1 = vp.worldToScreenY(origin.worldY);               // bottom

    // Boundary
    ctx.strokeStyle = 'rgba(83, 168, 182, 0.6)';
    ctx.lineWidth = 1;
    ctx.setLineDash([4, 4]);
    ctx.strokeRect(x0, y0, x1 - x0, y1 - y0);
    ctx.setLineDash([]);

    // Label at center
    if (tilePx >= 2) {
      const cx = (x0 + x1) / 2;
      const cy = (y0 + y1) / 2;

      const fontSize = clamp(tilePx * 2, 10, 16);
      ctx.font = `${fontSize}px monospace`;
      ctx.fillStyle = 'rgba(83, 168, 182, 0.8)';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';

      ctx.shadowColor = '#000';
      ctx.shadowBlur = 3;
      ctx.fillText(`${regionId}`, cx, cy - fontSize * 0.6);
      ctx.fillText(`(${rx},${ry})`, cx, cy + fontSize * 0.6);
      ctx.shadowBlur = 0;
    }
  }
}

/**
 * Main renderer. Call render() in a requestAnimationFrame loop.
 */
export class Renderer {
  private ctx: CanvasRenderingContext2D;
  private vp: Viewport;
  private plane = 0;
  private layers: LayerState = {
    walkability: false,
    walls: false,
    regionGrid: false,
    debugGrid: true,
    walkabilityOpacity: 0.4,
    wallOpacity: 0.8,
  };
  private renderRequested = false;

  constructor(canvas: HTMLCanvasElement, viewport: Viewport) {
    this.ctx = canvas.getContext('2d')!;
    this.vp = viewport;

    // Wire tile load callback to trigger re-renders
    onTileLoaded = () => this.requestRender();
  }

  get layerState(): LayerState {
    return this.layers;
  }

  setPlane(plane: number): void {
    this.plane = plane;
    this.requestRender();
  }

  setLayerVisible(layer: keyof Pick<LayerState, 'walkability' | 'walls' | 'regionGrid' | 'debugGrid'>, visible: boolean): void {
    this.layers[layer] = visible;
    this.requestRender();
  }

  setWalkabilityOpacity(opacity: number): void {
    this.layers.walkabilityOpacity = opacity;
    this.requestRender();
  }

  setWallOpacity(opacity: number): void {
    this.layers.wallOpacity = opacity;
    this.requestRender();
  }

  requestRender(): void {
    if (this.renderRequested) return;
    this.renderRequested = true;
    // Use setTimeout as fallback -- rAF doesn't fire in background/headless tabs
    const done = () => {
      this.renderRequested = false;
      this.render();
    };
    if (typeof requestAnimationFrame === 'function' && document.visibilityState !== 'hidden') {
      requestAnimationFrame(done);
    } else {
      setTimeout(done, 0);
    }
  }

  render(): void {
    const { ctx, vp } = this;
    const w = vp.canvasW;
    const h = vp.canvasH;

    // Clear
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = '#0a0a1a';
    ctx.fillRect(0, 0, w, h);

    // 1. Map tiles
    drawTiles(ctx, vp, this.plane, this.layers.debugGrid);

    // 2. Walkability overlay
    if (this.layers.walkability) {
      drawWalkability(ctx, vp, this.plane, this.layers.walkabilityOpacity);
    }

    // 3. Wall overlay
    if (this.layers.walls) {
      drawWalls(ctx, vp, this.plane, this.layers.wallOpacity);
    }

    // 4. Region grid
    if (this.layers.regionGrid) {
      drawRegionGrid(ctx, vp);
    }
  }
}
