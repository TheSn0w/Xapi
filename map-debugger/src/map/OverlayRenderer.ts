import L from 'leaflet';
import { calibration, applyCalibrationLocal } from './CoordinateTransform';
import { REGION_SIZE } from '../config';
import { getAllRegions, isTileBlocked, getWallEdges, loadVisibleRegions, onRegionLoad, getTransitionMatchesInBounds, loadTransitions } from '../data/DataManager';
import type { Transition, TransitionMatch } from '../data/DataManager';
import { regionToWorldOrigin, regionIdToRegionX, regionIdToRegionY } from '../utils/RegionMath';

export interface LayerState {
  walkability: boolean;
  walls: boolean;
  regionGrid: boolean;
  tileGrid: boolean;
  transitions: boolean;
  walkabilityOpacity: number;
  wallOpacity: number;
}

const CALIBRATION_KEY = 'rs3-map-debugger-calibration';

/**
 * Canvas overlay that draws walkability, walls, region grid, and tile grid
 * on top of the Leaflet map.
 */
/** Cache for item icon images loaded from the RS3 wiki */
const iconCache = new Map<string, HTMLImageElement | null>(); // null = failed/loading
const iconLoading = new Set<string>();

/** Map item names to wiki image filenames where they differ */
const ICON_NAME_MAP: Record<string, string> = {
  // Lodestones — use a shared lodestone icon
  'Lumbridge Lodestone': 'Home_Teleport_icon',
  'Burthorpe Lodestone': 'Home_Teleport_icon',
  'Lunar Isle Lodestone': 'Home_Teleport_icon',
  'Al Kharid Lodestone': 'Home_Teleport_icon',
  'Ardougne Lodestone': 'Home_Teleport_icon',
  'Bandit Camp Lodestone': 'Home_Teleport_icon',
  'Canifis Lodestone': 'Home_Teleport_icon',
  'Catherby Lodestone': 'Home_Teleport_icon',
  'Draynor Village Lodestone': 'Home_Teleport_icon',
  "Eagle's Peak Lodestone": 'Home_Teleport_icon',
  'Edgeville Lodestone': 'Home_Teleport_icon',
  'Falador Lodestone': 'Home_Teleport_icon',
  'Fremennik Province Lodestone': 'Home_Teleport_icon',
  'Karamja Lodestone': 'Home_Teleport_icon',
  "Oo'glog Lodestone": 'Home_Teleport_icon',
  'Port Sarim Lodestone': 'Home_Teleport_icon',
  "Seers' Village Lodestone": 'Home_Teleport_icon',
  'Taverley Lodestone': 'Home_Teleport_icon',
  'Tirannwn Lodestone': 'Home_Teleport_icon',
  'Varrock Lodestone': 'Home_Teleport_icon',
  'Yanille Lodestone': 'Home_Teleport_icon',
  'Ashdale Lodestone': 'Home_Teleport_icon',
  'Prifddinas Lodestone': 'Home_Teleport_icon',
  'Wilderness Volcano Lodestone': 'Home_Teleport_icon',
  'Anachronia Lodestone': 'Home_Teleport_icon',
  'Fort Forinthry Lodestone': 'Home_Teleport_icon',
  'City of Um Lodestone': 'Home_Teleport_icon',
  'Menaphos Lodestone': 'Home_Teleport_icon',
  'Wendlewick Lodestone': 'Home_Teleport_icon',
  // Item variants — map to base item icon
  'Amulet of glory (c)': 'Amulet_of_glory',
  'Amulet of glory (t)': 'Amulet_of_glory',
  'Amulet of glory (t1)': 'Amulet_of_glory',
  'Amulet of glory (t2)': 'Amulet_of_glory',
  'Amulet of glory (t3)': 'Amulet_of_glory',
  'Amulet of glory (t4)': 'Amulet_of_glory',
  'Ring of duelling (c)': 'Ring_of_duelling',
  'Completionist cape (t)': 'Completionist_cape',
  'Dungeoneering cape (t)': 'Dungeoneering_cape',
  'Herblore cape (t)': 'Herblore_cape',
  'Hunter cape (t)': 'Hunter_cape',
  'TokKul-Zo (Charged)': 'TokKul-Zo',
  'Attuned crystal teleport seed': 'Crystal_teleport_seed',
  // Items where wiki filename differs
  'Games necklace': 'Games_necklace_(8)',
  'Skills necklace': 'Skills_necklace_(4)',
  'Combat bracelet': 'Combat_bracelet_(4)',
  'Ring of duelling': 'Ring_of_duelling_(8)',
  'Ring of slaying': 'Ring_of_slaying_(8)',
  "Explorer's ring": 'Explorer%27s_ring_4',
  'Fremennik sea boots': 'Fremennik_sea_boots_4',
  'Morytania legs': 'Morytania_legs_4',
  'Wilderness sword': 'Wilderness_sword_4',
  "Pharaoh's sceptre": 'Pharaoh%27s_sceptre',
  'Karamja gloves 3': 'Karamja_gloves_3',
  'Karamja gloves 4': 'Karamja_gloves_4',
  'Desert amulet 4': 'Desert_amulet_4',
  'Ardougne cloak 1': 'Ardougne_cloak_1',
};

function getIconUrl(itemName: string): string {
  const mapped = ICON_NAME_MAP[itemName];
  const filename = mapped || itemName.replace(/ /g, '_');
  return `https://runescape.wiki/images/${filename}.png`;
}

function loadIcon(itemName: string, onLoad: () => void): HTMLImageElement | null {
  if (iconCache.has(itemName)) return iconCache.get(itemName)!;
  if (iconLoading.has(itemName)) return null;

  iconLoading.add(itemName);
  const img = new Image();
  img.crossOrigin = 'anonymous';
  img.onload = () => {
    iconCache.set(itemName, img);
    iconLoading.delete(itemName);
    onLoad(); // trigger re-render
  };
  img.onerror = () => {
    iconCache.set(itemName, null);
    iconLoading.delete(itemName);
  };
  img.src = getIconUrl(itemName);
  return null;
}

export class OverlayRenderer {
  private canvas: HTMLCanvasElement;
  private ctx: CanvasRenderingContext2D;
  private map: L.Map;
  private plane = 0;
  private layers: LayerState = {
    walkability: false,
    walls: false,
    regionGrid: false,
    tileGrid: false,
    transitions: false,
    walkabilityOpacity: 0.4,
    wallOpacity: 0.8,
  };
  private rafId = 0;
  /** Set of transition types to show. If empty, show ALL types. */
  private transitionTypeFilter = new Set<string>();
  /** Live player position from Xapi */
  private playerPosition: { x: number; y: number; plane: number; timestamp: number } | null = null;
  /** Whether to show the "Confirmed In-Game" highlight layer */
  private showConfirmedLayer = false;

  constructor(map: L.Map) {
    this.map = map;

    this.canvas = document.createElement('canvas');
    this.canvas.style.position = 'absolute';
    this.canvas.style.top = '0';
    this.canvas.style.left = '0';
    this.canvas.style.zIndex = '450';
    this.canvas.style.pointerEvents = 'none';
    map.getContainer().appendChild(this.canvas);

    this.ctx = this.canvas.getContext('2d')!;

    map.on('move zoom viewreset resize', this.scheduleRedraw, this);
    map.on('moveend zoomend', this.onViewChanged, this);

    // Redraw when new regions load
    onRegionLoad(() => this.scheduleRedraw());

    // Load saved calibration
    this.loadCalibration();

    // Load transitions data
    loadTransitions().then(() => this.scheduleRedraw());

    this.resizeCanvas();
    this.onViewChanged();
  }

  get layerState(): LayerState {
    return this.layers;
  }

  setPlane(plane: number): void {
    this.plane = plane;
    this.scheduleRedraw();
  }

  setLayerVisible(layer: keyof Pick<LayerState, 'walkability' | 'walls' | 'regionGrid' | 'tileGrid' | 'transitions'>, visible: boolean): void {
    this.layers[layer] = visible;
    this.scheduleRedraw();
  }

  /** Set which transition types to display. Pass empty set to show all. */
  setTransitionTypeFilter(enabledTypes: Set<string>): void {
    this.transitionTypeFilter = enabledTypes;
    this.scheduleRedraw();
  }

  /** Update live player position from Xapi. Pass null to clear. */
  setPlayerPosition(pos: { x: number; y: number; plane: number; timestamp: number } | null): void {
    this.playerPosition = pos;
    this.scheduleRedraw();
  }

  /** Toggle the "Confirmed In-Game" highlight layer. */
  setShowConfirmedLayer(show: boolean): void {
    this.showConfirmedLayer = show;
    this.scheduleRedraw();
  }

  setWalkabilityOpacity(opacity: number): void {
    this.layers.walkabilityOpacity = opacity;
    this.scheduleRedraw();
  }

  setWallOpacity(opacity: number): void {
    this.layers.wallOpacity = opacity;
    this.scheduleRedraw();
  }

  /** Save calibration offsets to localStorage */
  saveCalibration(): void {
    const data = {
      offsetX: calibration.offsetX,
      offsetY: calibration.offsetY,
      flipX: calibration.flipX,
      flipY: calibration.flipY,
      swapXY: calibration.swapXY,
    };
    localStorage.setItem(CALIBRATION_KEY, JSON.stringify(data));
  }

  /** Load calibration offsets from localStorage */
  loadCalibration(): void {
    // v2: offset is baked into CRS — clear stale saved offsets from v1
    const VERSION_KEY = 'rs3-map-debugger-cal-v';
    if (localStorage.getItem(VERSION_KEY) !== '2') {
      localStorage.removeItem(CALIBRATION_KEY);
      localStorage.setItem(VERSION_KEY, '2');
    }

    const saved = localStorage.getItem(CALIBRATION_KEY);
    if (!saved) return;
    try {
      const data = JSON.parse(saved);
      calibration.offsetX = data.offsetX ?? 0;
      calibration.offsetY = data.offsetY ?? 0;
      calibration.flipX = data.flipX ?? false;
      calibration.flipY = data.flipY ?? false;
      calibration.swapXY = data.swapXY ?? false;
    } catch { /* ignore corrupt data */ }
  }

  scheduleRedraw(): void {
    if (this.rafId) return;
    this.rafId = requestAnimationFrame(() => {
      this.rafId = 0;
      this.resizeCanvas();
      this.draw();
    });
  }

  /** Called on moveend/zoomend — trigger loading of visible regions */
  private onViewChanged(): void {
    const bounds = this.getVisibleBounds();
    loadVisibleRegions(bounds.minX, bounds.minY, bounds.maxX, bounds.maxY);
    this.scheduleRedraw();
  }

  private resizeCanvas(): void {
    const size = this.map.getSize();
    const dpr = window.devicePixelRatio || 1;
    const w = Math.round(size.x * dpr);
    const h = Math.round(size.y * dpr);
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
      this.canvas.style.width = size.x + 'px';
      this.canvas.style.height = size.y + 'px';
    }
    // Scale context to match DPR so drawing coordinates stay in CSS pixels
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  private draw(): void {
    const { ctx, canvas } = this;
    const dpr = window.devicePixelRatio || 1;
    ctx.clearRect(0, 0, canvas.width / dpr, canvas.height / dpr);

    if (this.layers.tileGrid) {
      this.drawTileGrid();
    }
    if (this.layers.walkability) {
      this.drawWalkability();
    }
    if (this.layers.walls) {
      this.drawWalls();
    }
    if (this.layers.regionGrid) {
      this.drawRegionGrid();
    }
    if (this.layers.transitions) {
      this.drawTransitions();
    }
    // "Confirmed In-Game" layer — draws xapi_live transitions independently
    if (this.showConfirmedLayer) {
      this.drawConfirmedLayer();
    }
    // Player marker — always drawn on top if position is available
    this.drawPlayerMarker();
  }

  /** Convert game coordinate to container pixel position */
  private gameToScreen(gameX: number, gameY: number): L.Point {
    return this.map.latLngToContainerPoint(L.latLng(gameY, gameX));
  }

  /** Get visible game coordinate bounds */
  private getVisibleBounds(): { minX: number; minY: number; maxX: number; maxY: number } {
    const b = this.map.getBounds();
    return {
      minX: b.getWest(),
      maxX: b.getEast(),
      minY: b.getSouth(),
      maxY: b.getNorth(),
    };
  }

  /** Pixels per game tile at current zoom (computed from actual projection) */
  private getTilePx(): number {
    const p0 = this.map.latLngToContainerPoint(L.latLng(0, 0));
    const p1 = this.map.latLngToContainerPoint(L.latLng(0, 1));
    return Math.abs(p1.x - p0.x);
  }

  private drawWalkability(): void {
    const { ctx } = this;
    const bounds = this.getVisibleBounds();
    const tilePx = this.getTilePx();
    if (tilePx < 1) return;

    ctx.fillStyle = `rgba(255, 0, 0, ${this.layers.walkabilityOpacity})`;

    for (const [regionId, region] of getAllRegions()) {
      const origin = regionToWorldOrigin(regionId);
      if (origin.worldX + REGION_SIZE < bounds.minX || origin.worldX > bounds.maxX) continue;
      if (origin.worldY + REGION_SIZE < bounds.minY || origin.worldY > bounds.maxY) continue;

      for (let ly = 0; ly < REGION_SIZE; ly++) {
        for (let lx = 0; lx < REGION_SIZE; lx++) {
          if (!isTileBlocked(region, lx, ly, this.plane)) continue;

          const cal = applyCalibrationLocal(lx, ly);
          const wx = origin.worldX + cal.x + calibration.offsetX;
          const wy = origin.worldY + cal.y + calibration.offsetY;

          if (wx < bounds.minX - 1 || wx > bounds.maxX + 1) continue;
          if (wy < bounds.minY - 1 || wy > bounds.maxY + 1) continue;

          const p = this.gameToScreen(wx, wy + 1);
          ctx.fillRect(p.x, p.y, tilePx, tilePx);

          // Draw grid border around each blocked tile when zoomed in enough
          if (tilePx >= 6) {
            ctx.strokeStyle = `rgba(0, 0, 0, 0.6)`;
            ctx.lineWidth = 1;
            ctx.strokeRect(p.x, p.y, tilePx, tilePx);
          }
        }
      }
    }
  }

  private drawWalls(): void {
    const { ctx } = this;
    const bounds = this.getVisibleBounds();
    const tilePx = this.getTilePx();
    if (tilePx < 4) return;

    ctx.strokeStyle = `rgba(255, 0, 0, ${this.layers.wallOpacity})`;
    ctx.lineWidth = Math.max(3, tilePx / 4);

    for (const [, region] of getAllRegions()) {
      const walls = getWallEdges(region, this.plane);

      for (const wall of walls) {
        const wx = wall.wx + calibration.offsetX;
        const wy = wall.wy + calibration.offsetY;

        if (wx < bounds.minX - 1 || wx > bounds.maxX + 1) continue;
        if (wy < bounds.minY - 1 || wy > bounds.maxY + 1) continue;

        const p0 = this.gameToScreen(wx, wy + 1);       // NW corner
        const p1 = this.gameToScreen(wx + 1, wy + 1);   // NE corner
        const p2 = this.gameToScreen(wx + 1, wy);        // SE corner
        const p3 = this.gameToScreen(wx, wy);             // SW corner

        ctx.beginPath();
        switch (wall.direction) {
          case 'north':
            ctx.moveTo(p0.x, p0.y);
            ctx.lineTo(p1.x, p1.y);
            break;
          case 'east':
            ctx.moveTo(p1.x, p1.y);
            ctx.lineTo(p2.x, p2.y);
            break;
          case 'south':
            ctx.moveTo(p3.x, p3.y);
            ctx.lineTo(p2.x, p2.y);
            break;
          case 'west':
            ctx.moveTo(p0.x, p0.y);
            ctx.lineTo(p3.x, p3.y);
            break;
        }
        ctx.stroke();
      }
    }
  }

  private drawRegionGrid(): void {
    const { ctx } = this;
    const bounds = this.getVisibleBounds();
    const tilePx = this.getTilePx();

    for (const [regionId] of getAllRegions()) {
      const origin = regionToWorldOrigin(regionId);
      const rx = regionIdToRegionX(regionId);
      const ry = regionIdToRegionY(regionId);

      if (origin.worldX + REGION_SIZE < bounds.minX || origin.worldX > bounds.maxX) continue;
      if (origin.worldY + REGION_SIZE < bounds.minY || origin.worldY > bounds.maxY) continue;

      const nw = this.gameToScreen(origin.worldX, origin.worldY + REGION_SIZE);
      const se = this.gameToScreen(origin.worldX + REGION_SIZE, origin.worldY);

      // Boundary
      ctx.strokeStyle = 'rgba(83, 168, 182, 0.6)';
      ctx.lineWidth = 1;
      ctx.setLineDash([4, 4]);
      ctx.strokeRect(nw.x, nw.y, se.x - nw.x, se.y - nw.y);
      ctx.setLineDash([]);

      // Label
      if (tilePx >= 2) {
        const cx = (nw.x + se.x) / 2;
        const cy = (nw.y + se.y) / 2;
        const fontSize = Math.min(16, Math.max(10, tilePx * 2));
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

  /** Draw per-game-tile grid (1x1 world tile outlines) */
  private drawTileGrid(): void {
    const { ctx } = this;
    const bounds = this.getVisibleBounds();
    const tilePx = this.getTilePx();
    if (tilePx < 6) return; // Too small to see individual tiles

    const minX = Math.floor(bounds.minX);
    const maxX = Math.ceil(bounds.maxX);
    const minY = Math.floor(bounds.minY);
    const maxY = Math.ceil(bounds.maxY);

    ctx.strokeStyle = 'rgba(0, 0, 0, 0.3)';
    ctx.lineWidth = 1;

    // Vertical lines
    for (let x = minX; x <= maxX; x++) {
      const top = this.gameToScreen(x, bounds.maxY);
      const bot = this.gameToScreen(x, bounds.minY);
      ctx.beginPath();
      ctx.moveTo(top.x, top.y);
      ctx.lineTo(bot.x, bot.y);
      ctx.stroke();
    }

    // Horizontal lines
    for (let y = minY; y <= maxY; y++) {
      const left = this.gameToScreen(bounds.minX, y);
      const right = this.gameToScreen(bounds.maxX, y);
      ctx.beginPath();
      ctx.moveTo(left.x, left.y);
      ctx.lineTo(right.x, right.y);
      ctx.stroke();
    }
  }

  /** Types that should NOT be drawn as edges even if adjacent (teleports, agility shortcuts etc.) */
  private static NON_EDGE_TYPES = new Set(['TELEPORT', 'LODESTONE', 'FAIRY_RING', 'SPIRIT_TREE', 'ITEM_TELEPORT', 'NPC_TRANSPORT', 'TRANSPORT']);

  /** Color map for transition types */
  private static TRANSITION_COLORS: Record<string, string> = {
    STAIRCASE: '#00ccff',
    DOOR: '#ffdd00',
    PASSAGE: '#66ff66',
    WALL_PASSAGE: '#66ff66',
    ENTRANCE: '#ffaa00',
    NPC_TRANSPORT: '#ff8844',
    TRANSPORT: '#ffaa00',
    AGILITY: '#ffff00',
    TELEPORT: '#cc66ff',
    LODESTONE: '#ffffff',
    FAIRY_RING: '#00ff88',
    SPIRIT_TREE: '#88ff00',
    PORTAL: '#aa88ff',
    ITEM_TELEPORT: '#ff88cc',
    OTHER: '#ff6644',
  };

  /** Draw live player marker from Xapi tracking */
  private drawPlayerMarker(): void {
    const pos = this.playerPosition;
    if (!pos) return;
    // Only show if position is recent (< 5 seconds old)
    if (Date.now() - pos.timestamp > 5000) return;
    // Only show on matching plane
    if (pos.plane !== this.plane) return;

    const { ctx } = this;
    const tilePx = this.getTilePx();
    if (tilePx < 2) return;

    const screenPos = this.gameToScreen(pos.x + 0.5, pos.y + 0.5);
    const r = Math.max(6, tilePx * 0.4);

    // Outer glow
    ctx.save();
    ctx.beginPath();
    ctx.arc(screenPos.x, screenPos.y, r + 3, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(30, 144, 255, 0.25)';
    ctx.fill();

    // Main circle
    ctx.beginPath();
    ctx.arc(screenPos.x, screenPos.y, r, 0, Math.PI * 2);
    ctx.fillStyle = '#1e90ff';
    ctx.fill();
    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = 2;
    ctx.stroke();

    // Label
    if (tilePx >= 4) {
      ctx.font = 'bold 11px monospace';
      ctx.fillStyle = '#ffffff';
      ctx.shadowColor = '#000000';
      ctx.shadowBlur = 3;
      ctx.textAlign = 'center';
      ctx.fillText('You', screenPos.x, screenPos.y - r - 5);
      ctx.shadowBlur = 0;
    }
    ctx.restore();
  }

  /** Draw transitions — edge gaps for doors/passages, diamond markers for others */
  private drawTransitions(): void {
    const { ctx } = this;
    const bounds = this.getVisibleBounds();
    const tilePx = this.getTilePx();
    if (tilePx < 2) return;

    const ox = calibration.offsetX;
    const oy = calibration.offsetY;

    const matches = getTransitionMatchesInBounds(
      bounds.minX - ox, bounds.minY - oy, bounds.maxX - ox, bounds.maxY - oy, this.plane
    );

    // Group non-edge markers by their draw tile to avoid overlapping labels
    const markerGroups = new Map<string, { matches: TransitionMatch[]; colors: string[] }>();

    // Collect edge transitions grouped by src tile to detect diagonal wall doors
    // (opposing pairs: E+W or N+S at the same tile = diagonal wall passage)
    const edgeByTile = new Map<string, { matches: TransitionMatch[]; colors: string[] }>();

    const typeFilter = this.transitionTypeFilter;

    for (const match of matches) {
      const t = match.transition;

      // Skip types not in the active filter (if filter is set)
      if (typeFilter.size > 0 && !typeFilter.has(t.type)) continue;

      // If confirmed layer is active, let it handle xapi_live transitions instead
      if (this.showConfirmedLayer && (t as any).source === 'xapi_live') continue;

      const color = OverlayRenderer.TRANSITION_COLORS[t.type] || '#ff6644';
      const dx = t.dstX - t.srcX;
      const dy = t.dstY - t.srcY;
      const isAdjacent = Math.abs(dx) + Math.abs(dy) === 1;
      const isSamePlane = t.srcP === t.dstP;
      const isEdgeType = !OverlayRenderer.NON_EDGE_TYPES.has(t.type);

      if (isEdgeType && isAdjacent && isSamePlane) {
        // Collect by src tile to detect opposing pairs
        const key = `${t.srcX},${t.srcY}`;
        let group = edgeByTile.get(key);
        if (!group) { group = { matches: [], colors: [] }; edgeByTile.set(key, group); }
        group.matches.push(match);
        group.colors.push(color);
      } else {
        // Group by the tile the marker will be drawn on
        const isDstOnly = t.srcX === 0 && t.srcY === 0 && t.srcP === 0;
        const drawX = (isDstOnly || match.matchedByDst) ? t.dstX : t.srcX;
        const drawY = (isDstOnly || match.matchedByDst) ? t.dstY : t.srcY;
        const key = `${drawX},${drawY}`;
        let group = markerGroups.get(key);
        if (!group) { group = { matches: [], colors: [] }; markerGroups.set(key, group); }
        group.matches.push(match);
        group.colors.push(color);
      }
    }

    // Process edge transitions — detect and render diagonal wall doors
    for (const [, group] of edgeByTile) {
      const deltas = group.matches.map(m => ({
        dx: m.transition.dstX - m.transition.srcX,
        dy: m.transition.dstY - m.transition.srcY,
      }));

      // Check for opposing pairs (E+W or N+S at same src tile = diagonal wall door)
      const hasEast = deltas.some(d => d.dx === 1 && d.dy === 0);
      const hasWest = deltas.some(d => d.dx === -1 && d.dy === 0);
      const hasNorth = deltas.some(d => d.dx === 0 && d.dy === 1);
      const hasSouth = deltas.some(d => d.dx === 0 && d.dy === -1);
      const isOpposingEW = hasEast && hasWest;
      const isOpposingNS = hasNorth && hasSouth;

      if (isOpposingEW || isOpposingNS) {
        // Diagonal wall door — determine direction from staircase pattern
        const t = group.matches[0].transition;
        let isNWSE = false; // default NE-SW
        if (isOpposingEW) {
          // Check if neighbor at (x-1,y+1) also has E+W pair → staircase goes NW → wall is NW-SE
          const nwKey = `${t.srcX - 1},${t.srcY + 1}`;
          const seKey = `${t.srcX + 1},${t.srcY - 1}`;
          if (edgeByTile.has(nwKey) || edgeByTile.has(seKey)) {
            isNWSE = true;
          }
        } else {
          // N+S opposing: check (x+1,y+1) or (x-1,y-1) for NW-SE
          const neKey = `${t.srcX + 1},${t.srcY + 1}`;
          const swKey = `${t.srcX - 1},${t.srcY - 1}`;
          if (edgeByTile.has(neKey) || edgeByTile.has(swKey)) {
            isNWSE = true;
          }
        }
        this.drawDiagonalDoor(group.matches[0].transition, group.colors[0], ox, oy, tilePx, isNWSE);
      } else {
        // Normal edge transitions — draw individually
        for (let i = 0; i < group.matches.length; i++) {
          this.drawEdgeTransition(group.matches[i].transition, group.colors[i], ox, oy, tilePx, group.matches[i].matchedByDst);
        }
      }
    }

    // Draw grouped markers
    for (const [, group] of markerGroups) {
      this.drawGroupedMarkers(group.matches, group.colors, ox, oy, tilePx);
    }

  }

  /** Draw "Confirmed In-Game" layer — renders xapi_live transitions independently */
  private drawConfirmedLayer(): void {
    const { ctx } = this;
    const bounds = this.getVisibleBounds();
    const tilePx = this.getTilePx();
    if (tilePx < 2) return;

    const ox = calibration.offsetX;
    const oy = calibration.offsetY;

    const matches = getTransitionMatchesInBounds(
      bounds.minX - ox, bounds.minY - oy, bounds.maxX - ox, bounds.maxY - oy, this.plane
    );

    for (const match of matches) {
      const t = match.transition;
      if ((t as any).source !== 'xapi_live') continue;

      const color = OverlayRenderer.TRANSITION_COLORS[t.type] || '#00ff66';
      const dx = t.dstX - t.srcX;
      const dy = t.dstY - t.srcY;
      const isAdjacent = Math.abs(dx) + Math.abs(dy) === 1;
      const isSamePlane = t.srcP === t.dstP;
      const isEdgeType = !OverlayRenderer.NON_EDGE_TYPES.has(t.type);

      if (isEdgeType && isAdjacent && isSamePlane) {
        // Draw edge line (door/passage) with green glow
        this.drawEdgeTransition(t, color, ox, oy, tilePx);

        // Green glow overlay
        ctx.save();
        ctx.shadowColor = '#00ff66';
        ctx.shadowBlur = 6;
        this.drawEdgeTransition(t, '#00ff66', ox, oy, tilePx);
        ctx.restore();
      } else {
        // Non-edge: draw diamond marker using gameToScreen for correct positioning
        const tx = match.matchedByDst ? t.dstX : t.srcX;
        const ty = match.matchedByDst ? t.dstY : t.srcY;
        const pos = this.gameToScreen(tx + ox + 0.5, ty + oy + 0.5);
        const cx = pos.x;
        const cy = pos.y;
        const r = Math.max(3, tilePx * 0.2);

        ctx.save();
        ctx.shadowColor = '#00ff66';
        ctx.shadowBlur = 4;
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.moveTo(cx, cy - r);
        ctx.lineTo(cx + r, cy);
        ctx.lineTo(cx, cy + r);
        ctx.lineTo(cx - r, cy);
        ctx.closePath();
        ctx.fill();
        ctx.restore();

        // Label
        if (tilePx >= 10) {
          ctx.font = `${Math.max(7, tilePx * 0.2)}px sans-serif`;
          ctx.fillStyle = '#00ff66';
          ctx.textAlign = 'center';
          ctx.fillText(t.name, cx, cy + r + 8);
        }
      }

      // Checkmark badge
      if (tilePx >= 6) {
        const tx2 = match.matchedByDst ? t.dstX : t.srcX;
        const ty2 = match.matchedByDst ? t.dstY : t.srcY;
        const pos2 = this.gameToScreen(tx2 + ox + 0.5, ty2 + oy + 0.5);
        ctx.font = 'bold 10px sans-serif';
        ctx.fillStyle = '#00ff66';
        ctx.textAlign = 'left';
        ctx.fillText('\u2713', pos2.x + tilePx * 0.4 + 2, pos2.y - tilePx * 0.4 + 4);
      }
    }
  }

  /** Draw a diagonal wall door as a diagonal line through the tile */
  private drawDiagonalDoor(t: Transition, color: string, ox: number, oy: number, tilePx: number, isNWSE: boolean): void {
    const { ctx } = this;
    const sx = t.srcX + ox;
    const sy = t.srcY + oy;

    let x0: number, y0: number, x1: number, y1: number;
    if (isNWSE) {
      // NW-SE diagonal: draw from NW corner to SE corner
      x0 = sx;     y0 = sy + 1; // NW corner
      x1 = sx + 1; y1 = sy;     // SE corner
    } else {
      // NE-SW diagonal: draw from NE corner to SW corner
      x0 = sx + 1; y0 = sy + 1; // NE corner
      x1 = sx;     y1 = sy;     // SW corner
    }

    const p0 = this.gameToScreen(x0, y0);
    const p1 = this.gameToScreen(x1, y1);

    ctx.strokeStyle = color;
    ctx.lineWidth = Math.max(3, tilePx / 4);
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(p0.x, p0.y);
    ctx.lineTo(p1.x, p1.y);
    ctx.stroke();
    ctx.lineCap = 'butt';

    // Label
    if (tilePx >= 16) {
      const mx = (p0.x + p1.x) / 2;
      const my = (p0.y + p1.y) / 2;
      const fontSize = Math.max(8, Math.min(11, tilePx * 0.12));
      ctx.font = `${fontSize}px monospace`;
      ctx.fillStyle = '#ffffff';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'bottom';
      ctx.shadowColor = '#000';
      ctx.shadowBlur = 3;
      ctx.fillText(t.name, mx, my - 4);
      ctx.shadowBlur = 0;
    }
  }

  /** Draw a door/passage as a colored gap on the tile edge */
  private drawEdgeTransition(t: Transition, color: string, ox: number, oy: number, tilePx: number, isDstSide: boolean = false): void {
    const { ctx } = this;
    // When rendering the dst side of a bidir transition, flip perspective:
    // the edge is still between src and dst, but label anchors to dst tile
    const anchorX = isDstSide ? t.dstX : t.srcX;
    const anchorY = isDstSide ? t.dstY : t.srcY;
    const dx = t.dstX - t.srcX;
    const dy = t.dstY - t.srcY;

    // The edge is between src and dst — compute the shared boundary
    let edgeX0: number, edgeY0: number, edgeX1: number, edgeY1: number;

    if (dx === 1) {
      // Door on east edge of src tile (= west edge of dst tile)
      edgeX0 = t.srcX + 1 + ox; edgeY0 = t.srcY + oy;
      edgeX1 = t.srcX + 1 + ox; edgeY1 = t.srcY + 1 + oy;
    } else if (dx === -1) {
      // Door on west edge of src tile (= east edge of dst tile)
      edgeX0 = t.srcX + ox; edgeY0 = t.srcY + oy;
      edgeX1 = t.srcX + ox; edgeY1 = t.srcY + 1 + oy;
    } else if (dy === 1) {
      // Door on north edge of src tile (= south edge of dst tile)
      edgeX0 = t.srcX + ox; edgeY0 = t.srcY + 1 + oy;
      edgeX1 = t.srcX + 1 + ox; edgeY1 = t.srcY + 1 + oy;
    } else {
      // Door on south edge of src tile (= north edge of dst tile)
      edgeX0 = t.srcX + ox; edgeY0 = t.srcY + oy;
      edgeX1 = t.srcX + 1 + ox; edgeY1 = t.srcY + oy;
    }

    const p0 = this.gameToScreen(edgeX0, edgeY0);
    const p1 = this.gameToScreen(edgeX1, edgeY1);

    // Draw the passable edge as a thick green line
    ctx.strokeStyle = color;
    ctx.lineWidth = Math.max(3, tilePx / 4);
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(p0.x, p0.y);
    ctx.lineTo(p1.x, p1.y);
    ctx.stroke();
    ctx.lineCap = 'butt';

    // Label offset away from the edge line
    if (tilePx >= 16) {
      const mx = (p0.x + p1.x) / 2;
      const my = (p0.y + p1.y) / 2;
      const isVertical = Math.abs(p0.x - p1.x) < 1; // vertical edge = east/west door
      const fontSize = Math.max(8, Math.min(11, tilePx * 0.12));
      const offset = tilePx * 0.5 + fontSize;

      ctx.font = `${fontSize}px monospace`;
      ctx.fillStyle = '#ffffff';
      ctx.shadowColor = '#000';
      ctx.shadowBlur = 3;

      if (isVertical) {
        // Offset label to the left of a vertical edge
        ctx.textAlign = 'right';
        ctx.textBaseline = 'middle';
        ctx.fillText(t.name, mx - offset * 0.3, my);
      } else {
        // Offset label above a horizontal edge
        ctx.textAlign = 'center';
        ctx.textBaseline = 'bottom';
        ctx.fillText(t.name, mx, my - offset * 0.3);
      }
      ctx.shadowBlur = 0;
    }
  }

  /** Draw grouped markers that share the same tile — icon + stacked labels */
  private drawGroupedMarkers(matches: TransitionMatch[], colors: string[], ox: number, oy: number, tilePx: number): void {
    const { ctx } = this;
    const m0 = matches[0];
    const t0 = m0.transition;
    const isDstOnly0 = t0.srcX === 0 && t0.srcY === 0 && t0.srcP === 0;
    const drawX = (isDstOnly0 || m0.matchedByDst) ? t0.dstX : t0.srcX;
    const drawY = (isDstOnly0 || m0.matchedByDst) ? t0.dstY : t0.srcY;
    const pos = this.gameToScreen(drawX + ox + 0.5, drawY + oy + 0.5);
    const markerSize = Math.max(4, tilePx * 0.4);

    // Check if any match in this group is a dst-side bidir (not a dst-only teleport)
    const hasBidirDst = matches.some(m => m.matchedByDst && !(m.transition.srcX === 0 && m.transition.srcY === 0 && m.transition.srcP === 0));

    // Collect unique item icons for rendering (ITEM_TELEPORT and LODESTONE)
    // Deduplicate by resolved icon URL so glory variants don't show 8 identical icons
    const iconTypes = new Set(['ITEM_TELEPORT', 'LODESTONE']);
    const iconItems: string[] = [];
    const seenIconUrls = new Set<string>();
    for (const m of matches) {
      if (iconTypes.has(m.transition.type)) {
        const url = getIconUrl(m.transition.name);
        if (!seenIconUrls.has(url)) {
          seenIconUrls.add(url);
          iconItems.push(m.transition.name);
        }
      }
    }

    // Try to draw item icons
    let drewIcon = false;
    const iconSize = Math.max(20, Math.min(32, tilePx * 0.8));
    if (iconItems.length > 0 && tilePx >= 2) {
      let iconX = pos.x - (iconItems.length * iconSize) / 2 + iconSize / 2;
      for (const itemName of iconItems) {
        const icon = loadIcon(itemName, () => this.scheduleRedraw());
        if (icon) {
          ctx.globalAlpha = 0.95;
          ctx.drawImage(icon, iconX - iconSize / 2, pos.y - iconSize / 2, iconSize, iconSize);
          // Dark outline around icon for visibility
          ctx.strokeStyle = '#000';
          ctx.lineWidth = 1;
          ctx.strokeRect(iconX - iconSize / 2, pos.y - iconSize / 2, iconSize, iconSize);
          ctx.globalAlpha = 1;
          drewIcon = true;
        }
        iconX += iconSize + 2;
      }
    }

    // Fall back to diamond if no icons loaded
    if (!drewIcon) {
      ctx.globalAlpha = 0.85;
      ctx.beginPath();
      ctx.moveTo(pos.x, pos.y - markerSize);
      ctx.lineTo(pos.x + markerSize, pos.y);
      ctx.lineTo(pos.x, pos.y + markerSize);
      ctx.lineTo(pos.x - markerSize, pos.y);
      ctx.closePath();

      if (hasBidirDst) {
        // Hollow diamond for bidir destination side
        ctx.strokeStyle = colors[0];
        ctx.lineWidth = 2;
        ctx.stroke();
      } else {
        ctx.fillStyle = colors[0];
        ctx.fill();
        ctx.strokeStyle = '#000';
        ctx.lineWidth = 1;
        ctx.stroke();
      }
      ctx.globalAlpha = 1;
    }

    if (tilePx < 12) return;

    const fontSize = Math.max(9, Math.min(12, tilePx * 0.15));
    const lineHeight = fontSize + 2;
    ctx.font = `${fontSize}px monospace`;
    ctx.textAlign = 'center';
    ctx.shadowColor = '#000';
    ctx.shadowBlur = 3;

    // Build deduplicated labels — collapse item variants like "Amulet of glory (t4)" to base name
    const seen = new Set<string>();
    const labels: { text: string; color: string }[] = [];
    for (let i = 0; i < matches.length; i++) {
      const t = matches[i].transition;
      const isDstOnly = t.srcX === 0 && t.srcY === 0 && t.srcP === 0;
      const isBidirDst = matches[i].matchedByDst && !isDstOnly;
      let label: string;
      if (isDstOnly) {
        // Strip variant suffixes: "Amulet of glory (t4)" → "Amulet of glory"
        const baseName = t.name.replace(/ \([^)]*\)$/, '');
        label = `${baseName} → ${t.option}`;
      } else if (isBidirDst) {
        label = `← ${t.name}`;
      } else if (t.dstP !== t.srcP) {
        label = `${t.name} (p${t.srcP}→p${t.dstP})`;
      } else {
        label = t.name;
      }
      if (!seen.has(label)) {
        seen.add(label);
        labels.push({ text: label, color: colors[i] });
      }
    }

    // Draw labels stacked upward from the marker/icon
    const labelBase = drewIcon ? pos.y - iconSize / 2 - 2 : pos.y - markerSize - 2;
    ctx.textBaseline = 'bottom';
    let y = labelBase;
    for (let i = 0; i < labels.length; i++) {
      ctx.fillStyle = '#ffffff';
      ctx.fillText(labels[i].text, pos.x, y);
      y -= lineHeight;
    }
    ctx.shadowBlur = 0;
  }
}
