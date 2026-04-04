/**
 * Custom viewport for the RS3 map canvas.
 * Port of the working map editor's Viewport.java.
 *
 * Coordinate system:
 * - World: X increases east, Y increases north
 * - Screen: X increases right, Y increases down
 * - Y-flip happens only in worldToScreen/screenToWorld
 * - zoom = pixels per world tile (continuous)
 */

const MIN_ZOOM = 0.125;  // ~8 world tiles per pixel
const MAX_ZOOM = 128;    // 128 px per world tile (matches imagery zoom 7)

function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}

export class Viewport {
  /** Center of view in world coordinates */
  worldX = 3232;
  worldY = 3232;

  /** Pixels per world tile (continuous zoom) */
  zoom = 4;

  /** Canvas dimensions in pixels */
  canvasW = 0;
  canvasH = 0;

  worldToScreenX(wx: number): number {
    return (wx - this.worldX) * this.zoom + this.canvasW / 2;
  }

  worldToScreenY(wy: number): number {
    return -(wy - this.worldY) * this.zoom + this.canvasH / 2;
  }

  screenToWorldX(sx: number): number {
    return (sx - this.canvasW / 2) / this.zoom + this.worldX;
  }

  screenToWorldY(sy: number): number {
    return -(sy - this.canvasH / 2) / this.zoom + this.worldY;
  }

  /** Zoom centered on a screen point, preserving the world point under cursor */
  zoomAt(sx: number, sy: number, factor: number): void {
    const wx = this.screenToWorldX(sx);
    const wy = this.screenToWorldY(sy);
    this.zoom = clamp(this.zoom * factor, MIN_ZOOM, MAX_ZOOM);
    this.worldX = wx - (sx - this.canvasW / 2) / this.zoom;
    this.worldY = wy + (sy - this.canvasH / 2) / this.zoom;
  }

  /** Get visible world bounds */
  getVisibleBounds(): { minX: number; minY: number; maxX: number; maxY: number } {
    return {
      minX: this.screenToWorldX(0),
      maxX: this.screenToWorldX(this.canvasW),
      minY: this.screenToWorldY(this.canvasH),  // bottom of screen = min world Y
      maxY: this.screenToWorldY(0),              // top of screen = max world Y
    };
  }

  /** Update canvas dimensions (call on resize) */
  resize(w: number, h: number): void {
    this.canvasW = w;
    this.canvasH = h;
  }
}
