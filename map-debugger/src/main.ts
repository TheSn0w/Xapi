import L from 'leaflet';
import { createMap, createTileLayer } from './map/MapEngine';
import { OverlayRenderer } from './map/OverlayRenderer';
import { calibration } from './map/CoordinateTransform';
import { getRegion, isTileBlocked, getWallEdges, getTransitionsAtTile, addTransition, removeTransition, moveTransition, reloadTransitions } from './data/DataManager';
import type { Transition, TransitionMatch } from './data/DataManager';
import { worldToRegionId, worldToLocal } from './utils/RegionMath';

// ── State ──────────────────────────────────────────────
let currentPlane = 0;
let map: L.Map;
let tileLayer: L.TileLayer;
let overlay: OverlayRenderer;

// ── Initialize ─────────────────────────────────────────
function init() {
  const container = document.getElementById('map') as HTMLElement;
  map = createMap(container);
  tileLayer = createTileLayer(currentPlane);
  tileLayer.addTo(map);

  overlay = new OverlayRenderer(map);

  // Expose for debug console access
  (window as any).__dbg = { get map() { return map; }, get overlay() { return overlay; } };

  // Wire up UI
  setupPlaneButtons();
  setupLayerToggles();
  setupCalibrationControls();
  setupOpacitySliders();
  setupKeyboardShortcuts();
  setupCoordReadout();
  setupTileClick();
  setupGoTo();
  setupPlayerTracking();

  updateZoomDisplay();
  map.on('zoomend', updateZoomDisplay);

  // Sync UI with loaded calibration
  syncCalibrationUI();

  // Calibration section collapse toggle
  document.getElementById('calibration-toggle')?.addEventListener('click', () => {
    const body = document.getElementById('calibration-body')!;
    const label = document.querySelector('#calibration-toggle span')!;
    if (body.style.display === 'none') {
      body.style.display = '';
      label.textContent = 'expanded';
    } else {
      body.style.display = 'none';
      label.textContent = 'locked';
    }
  });

  console.log('RS3 Map Debugger initialized — regions load on-demand as you pan');
}

// ── Coordinate readout ───────────────────────────────
function setupCoordReadout() {
  map.on('mousemove', (e: L.LeafletMouseEvent) => {
    const gameX = Math.floor(e.latlng.lng);
    const gameY = Math.floor(e.latlng.lat);
    updateCoordDisplay(gameX, gameY);
  });
}

function updateCoordDisplay(worldX: number, worldY: number) {
  const tileX = Math.floor(worldX / 8);
  const tileY = Math.floor(worldY / 8);
  const regionId = worldToRegionId(worldX, worldY);
  const local = worldToLocal(worldX, worldY);

  const worldEl = document.getElementById('coord-world');
  const tileEl = document.getElementById('coord-tile');
  const regionEl = document.getElementById('coord-region');
  const localEl = document.getElementById('coord-local');

  if (worldEl) worldEl.textContent = `(${worldX}, ${worldY})`;
  if (tileEl) tileEl.textContent = `(${tileX}, ${tileY})`;
  if (regionEl) regionEl.textContent = `${regionId}`;
  if (localEl) localEl.textContent = `(${local.localX}, ${local.localY})`;
}

function updateZoomDisplay() {
  const zoomEl = document.getElementById('coord-zoom');
  if (zoomEl) zoomEl.textContent = `${map.getZoom()}`;
}

// ── Go To coordinate ──────────────────────────────────
function setupGoTo() {
  const xInput = document.getElementById('goto-x') as HTMLInputElement;
  const yInput = document.getElementById('goto-y') as HTMLInputElement;
  const pInput = document.getElementById('goto-p') as HTMLInputElement;
  const goBtn = document.getElementById('goto-btn')!;

  function goToCoord() {
    const x = parseInt(xInput.value);
    const y = parseInt(yInput.value);
    const p = parseInt(pInput.value) || 0;
    if (isNaN(x) || isNaN(y)) return;

    // Switch plane if different
    if (p !== currentPlane) switchPlane(p);

    // Navigate — ensure at least zoom 5 so the area is visible
    const zoom = Math.max(map.getZoom(), 5);
    map.setView(L.latLng(y, x), zoom);
  }

  goBtn.addEventListener('click', goToCoord);

  // Enter key in any input triggers go
  [xInput, yInput, pInput].forEach(input => {
    input?.addEventListener('keydown', (e: KeyboardEvent) => {
      if (e.key === 'Enter') goToCoord();
    });
  });
}

// ── Live player tracking (Xapi) ──────────────────────────
function setupPlayerTracking() {
  let trackingEnabled = false;
  let followEnabled = false;
  let pollInterval: ReturnType<typeof setInterval> | null = null;
  let transitionReloadInterval: ReturnType<typeof setInterval> | null = null;

  const playerToggle = document.getElementById('toggle-player') as HTMLInputElement | null;
  const confirmedToggle = document.getElementById('toggle-confirmed') as HTMLInputElement | null;
  const followToggle = document.getElementById('toggle-follow') as HTMLInputElement | null;

  // Reload transitions from disk periodically (picks up Xapi-sent entries)
  function startTransitionReload() {
    if (transitionReloadInterval) return;
    transitionReloadInterval = setInterval(() => reloadTransitions(), 5000);
  }
  function stopTransitionReload() {
    if (transitionReloadInterval) { clearInterval(transitionReloadInterval); transitionReloadInterval = null; }
  }

  // Player position polling
  function startPolling() {
    if (pollInterval) return;
    startTransitionReload();
    pollInterval = setInterval(async () => {
      try {
        const res = await fetch('/api/player-position');
        if (!res.ok) return;
        const pos = await res.json();
        if (pos.timestamp > 0) {
          overlay.setPlayerPosition(pos);
          if (followEnabled) {
            map.panTo(L.latLng(pos.y + 0.5, pos.x + 0.5), { animate: false });
          }
        } else {
          overlay.setPlayerPosition(null);
        }
      } catch {
        overlay.setPlayerPosition(null);
      }
    }, 600);
  }

  function stopPolling() {
    if (pollInterval) {
      clearInterval(pollInterval);
      pollInterval = null;
    }
    stopTransitionReload();
    overlay.setPlayerPosition(null);
  }

  // Wire player toggle
  if (playerToggle) {
    playerToggle.addEventListener('change', () => {
      trackingEnabled = playerToggle.checked;
      if (trackingEnabled) startPolling();
      else stopPolling();
      saveLayerSettings();
    });
  }

  // Wire follow toggle
  if (followToggle) {
    followToggle.addEventListener('change', () => {
      followEnabled = followToggle.checked;
      saveLayerSettings();
    });
  }

  // Wire confirmed layer toggle
  if (confirmedToggle) {
    confirmedToggle.addEventListener('change', () => {
      overlay.setShowConfirmedLayer(confirmedToggle.checked);
      saveLayerSettings();
    });
  }

  // Restore from saved settings
  const saved = loadLayerSettings();
  if (saved.playerTracking) {
    if (playerToggle) playerToggle.checked = true;
    trackingEnabled = true;
    startPolling();
  }
  if (saved.followPlayer) {
    if (followToggle) followToggle.checked = true;
    followEnabled = true;
  }
  if (saved.confirmedLayer) {
    if (confirmedToggle) confirmedToggle.checked = true;
    overlay.setShowConfirmedLayer(true);
  }
}

// ── Plane switching ────────────────────────────────────
function setupPlaneButtons() {
  document.querySelectorAll<HTMLButtonElement>('.plane-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      switchPlane(parseInt(btn.dataset.plane || '0'));
    });
  });
}

function switchPlane(plane: number) {
  currentPlane = plane;
  document.querySelectorAll<HTMLButtonElement>('.plane-btn').forEach(btn => {
    btn.classList.toggle('active', parseInt(btn.dataset.plane || '0') === plane);
  });

  map.removeLayer(tileLayer);
  tileLayer = createTileLayer(plane);
  tileLayer.addTo(map);

  overlay.setPlane(plane);

  const planeEl = document.getElementById('coord-plane');
  if (planeEl) planeEl.textContent = String(plane);
}

// ── Layer toggles ──────────────────────────────────────
const LAYER_STORAGE_KEY = 'layer-settings';

interface LayerSettings {
  walkability: boolean;
  walls: boolean;
  regionGrid: boolean;
  tileGrid: boolean;
  transitions: boolean;
  /** Transition type filter — lists UNCHECKED groups' data-types values */
  disabledTransitionTypes?: string[];
  /** Xapi live player tracking */
  playerTracking?: boolean;
  /** Follow player — pan map to player position */
  followPlayer?: boolean;
  /** Confirmed In-Game highlight layer */
  confirmedLayer?: boolean;
}

function loadLayerSettings(): LayerSettings | null {
  try {
    const raw = localStorage.getItem(LAYER_STORAGE_KEY);
    if (!raw) return null;
    return JSON.parse(raw) as LayerSettings;
  } catch { return null; }
}

function saveLayerSettings() {
  // Collect disabled transition type groups
  const disabled: string[] = [];
  document.querySelectorAll<HTMLInputElement>('.trans-filter').forEach(cb => {
    if (!cb.checked) disabled.push(cb.dataset.types!);
  });

  const settings: LayerSettings = {
    walkability: (document.getElementById('layer-walkability') as HTMLInputElement)?.checked ?? false,
    walls: (document.getElementById('layer-walls') as HTMLInputElement)?.checked ?? false,
    regionGrid: (document.getElementById('layer-grid') as HTMLInputElement)?.checked ?? false,
    tileGrid: (document.getElementById('layer-tile-grid') as HTMLInputElement)?.checked ?? false,
    transitions: (document.getElementById('layer-transitions') as HTMLInputElement)?.checked ?? false,
    disabledTransitionTypes: disabled,
    playerTracking: (document.getElementById('toggle-player') as HTMLInputElement)?.checked ?? false,
    followPlayer: (document.getElementById('toggle-follow') as HTMLInputElement)?.checked ?? false,
    confirmedLayer: (document.getElementById('toggle-confirmed') as HTMLInputElement)?.checked ?? false,
  };
  localStorage.setItem(LAYER_STORAGE_KEY, JSON.stringify(settings));
}

/** Rebuild the transition type filter from checkbox state and apply to overlay */
function applyTransitionFilter() {
  const enabled = new Set<string>();
  let allChecked = true;
  document.querySelectorAll<HTMLInputElement>('.trans-filter').forEach(cb => {
    const types = cb.dataset.types!.split(',');
    if (cb.checked) {
      types.forEach(t => enabled.add(t));
    } else {
      allChecked = false;
    }
  });
  // If all checked, pass empty set (show everything, including unlisted types)
  overlay.setTransitionTypeFilter(allChecked ? new Set() : enabled);
}

function setupLayerToggles() {
  const walkCheck = document.getElementById('layer-walkability') as HTMLInputElement;
  const wallCheck = document.getElementById('layer-walls') as HTMLInputElement;
  const gridCheck = document.getElementById('layer-grid') as HTMLInputElement;
  const tileGridCheck = document.getElementById('layer-tile-grid') as HTMLInputElement;
  const transCheck = document.getElementById('layer-transitions') as HTMLInputElement;

  const filtersDiv = document.getElementById('transition-filters')!;

  // Restore saved settings
  const saved = loadLayerSettings();
  if (saved) {
    if (walkCheck) { walkCheck.checked = saved.walkability; overlay.setLayerVisible('walkability', saved.walkability); }
    if (wallCheck) { wallCheck.checked = saved.walls; overlay.setLayerVisible('walls', saved.walls); }
    if (gridCheck) { gridCheck.checked = saved.regionGrid; overlay.setLayerVisible('regionGrid', saved.regionGrid); }
    if (tileGridCheck) { tileGridCheck.checked = saved.tileGrid; overlay.setLayerVisible('tileGrid', saved.tileGrid); }
    if (transCheck) {
      transCheck.checked = saved.transitions;
      overlay.setLayerVisible('transitions', saved.transitions);
      filtersDiv.style.display = saved.transitions ? '' : 'none';
    }
    // Restore disabled transition type filters
    if (saved.disabledTransitionTypes?.length) {
      const disabledSet = new Set(saved.disabledTransitionTypes);
      document.querySelectorAll<HTMLInputElement>('.trans-filter').forEach(cb => {
        if (disabledSet.has(cb.dataset.types!)) cb.checked = false;
      });
    }
    applyTransitionFilter();
  }

  walkCheck?.addEventListener('change', () => { overlay.setLayerVisible('walkability', walkCheck.checked); saveLayerSettings(); });
  wallCheck?.addEventListener('change', () => { overlay.setLayerVisible('walls', wallCheck.checked); saveLayerSettings(); });
  gridCheck?.addEventListener('change', () => { overlay.setLayerVisible('regionGrid', gridCheck.checked); saveLayerSettings(); });
  tileGridCheck?.addEventListener('change', () => { overlay.setLayerVisible('tileGrid', tileGridCheck.checked); saveLayerSettings(); });
  transCheck?.addEventListener('change', () => {
    overlay.setLayerVisible('transitions', transCheck.checked);
    filtersDiv.style.display = transCheck.checked ? '' : 'none';
    saveLayerSettings();
  });

  // Wire up transition sub-filters
  document.querySelectorAll<HTMLInputElement>('.trans-filter').forEach(cb => {
    cb.addEventListener('change', () => {
      applyTransitionFilter();
      saveLayerSettings();
    });
  });
}

// ── Calibration controls ───────────────────────────────
function setupCalibrationControls() {
  const offsetXVal = document.getElementById('offset-x-val')!;
  const offsetYVal = document.getElementById('offset-y-val')!;

  document.querySelectorAll<HTMLButtonElement>('.offset-btn[data-axis]').forEach(btn => {
    btn.addEventListener('click', () => {
      const axis = btn.dataset.axis as 'x' | 'y';
      const delta = parseInt(btn.dataset.delta || '0');
      if (axis === 'x') calibration.offsetX += delta;
      if (axis === 'y') calibration.offsetY += delta;
      offsetXVal.textContent = String(calibration.offsetX);
      offsetYVal.textContent = String(calibration.offsetY);
      overlay.saveCalibration();
      overlay.scheduleRedraw();
    });
  });

  const flipY = document.getElementById('flip-y') as HTMLInputElement;
  const flipX = document.getElementById('flip-x') as HTMLInputElement;
  const swapXY = document.getElementById('swap-xy') as HTMLInputElement;

  flipY?.addEventListener('change', () => { calibration.flipY = flipY.checked; overlay.saveCalibration(); overlay.scheduleRedraw(); });
  flipX?.addEventListener('change', () => { calibration.flipX = flipX.checked; overlay.saveCalibration(); overlay.scheduleRedraw(); });
  swapXY?.addEventListener('change', () => { calibration.swapXY = swapXY.checked; overlay.saveCalibration(); overlay.scheduleRedraw(); });

  document.getElementById('reset-offset')?.addEventListener('click', () => {
    calibration.offsetX = 0;
    calibration.offsetY = 0;
    calibration.flipX = false;
    calibration.flipY = false;
    calibration.swapXY = false;
    offsetXVal.textContent = '0';
    offsetYVal.textContent = '0';
    flipY.checked = false;
    flipX.checked = false;
    swapXY.checked = false;
    overlay.saveCalibration();
    overlay.scheduleRedraw();
  });
}

/** Sync UI controls to match loaded calibration state */
function syncCalibrationUI() {
  const offsetXVal = document.getElementById('offset-x-val');
  const offsetYVal = document.getElementById('offset-y-val');
  const flipY = document.getElementById('flip-y') as HTMLInputElement;
  const flipX = document.getElementById('flip-x') as HTMLInputElement;
  const swapXY = document.getElementById('swap-xy') as HTMLInputElement;

  if (offsetXVal) offsetXVal.textContent = String(calibration.offsetX);
  if (offsetYVal) offsetYVal.textContent = String(calibration.offsetY);
  if (flipY) flipY.checked = calibration.flipY;
  if (flipX) flipX.checked = calibration.flipX;
  if (swapXY) swapXY.checked = calibration.swapXY;
}

// ── Opacity sliders ────────────────────────────────────
function setupOpacitySliders() {
  const walkSlider = document.getElementById('opacity-walkability') as HTMLInputElement;
  const walkVal = document.getElementById('opacity-walkability-val')!;
  const wallSlider = document.getElementById('opacity-walls') as HTMLInputElement;
  const wallVal = document.getElementById('opacity-walls-val')!;

  walkSlider?.addEventListener('input', () => {
    const v = parseInt(walkSlider.value);
    walkVal.textContent = `${v}%`;
    overlay.setWalkabilityOpacity(v / 100);
  });

  wallSlider?.addEventListener('input', () => {
    const v = parseInt(wallSlider.value);
    wallVal.textContent = `${v}%`;
    overlay.setWallOpacity(v / 100);
  });
}

// ── Keyboard shortcuts ─────────────────────────────────
function setupKeyboardShortcuts() {
  document.addEventListener('keydown', (e: KeyboardEvent) => {
    if (e.key === 'Escape' && moveMode) { exitMoveMode(); return; }
    if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;

    switch (e.key.toLowerCase()) {
      case 'w': {
        const check = document.getElementById('layer-walkability') as HTMLInputElement;
        check.checked = !check.checked;
        overlay.setLayerVisible('walkability', check.checked);
        saveLayerSettings();
        break;
      }
      case 'e': {
        const check = document.getElementById('layer-walls') as HTMLInputElement;
        check.checked = !check.checked;
        overlay.setLayerVisible('walls', check.checked);
        saveLayerSettings();
        break;
      }
      case 'g': {
        const check = document.getElementById('layer-grid') as HTMLInputElement;
        check.checked = !check.checked;
        overlay.setLayerVisible('regionGrid', check.checked);
        saveLayerSettings();
        break;
      }
      case 't': {
        const check = document.getElementById('layer-tile-grid') as HTMLInputElement;
        check.checked = !check.checked;
        overlay.setLayerVisible('tileGrid', check.checked);
        saveLayerSettings();
        break;
      }
      case 'r': {
        const check = document.getElementById('layer-transitions') as HTMLInputElement;
        check.checked = !check.checked;
        overlay.setLayerVisible('transitions', check.checked);
        document.getElementById('transition-filters')!.style.display = check.checked ? '' : 'none';
        saveLayerSettings();
        break;
      }
      case 'f': {
        const check = document.getElementById('toggle-follow') as HTMLInputElement;
        if (check) {
          check.checked = !check.checked;
          check.dispatchEvent(new Event('change'));
        }
        break;
      }
      case '1': switchPlane(0); break;
      case '2': switchPlane(1); break;
      case '3': switchPlane(2); break;
      case '4': switchPlane(3); break;
    }
  });
}

// ── Move mode state ──────────────────────────────────────
let moveMode: { transition: Transition; side: 'src' | 'dst' } | null = null;

function enterMoveMode(transition: Transition, side: 'src' | 'dst') {
  moveMode = { transition, side };
  document.getElementById('move-mode-banner')!.style.display = '';
  document.getElementById('move-mode-label')!.textContent =
    `Moving ${side} of "${transition.name}" — click new location`;
  document.getElementById('map')!.style.cursor = 'crosshair';
}

function exitMoveMode() {
  moveMode = null;
  document.getElementById('move-mode-banner')!.style.display = 'none';
  document.getElementById('map')!.style.cursor = '';
}

// ── Tile click inspection + transition edit ──────────────
let clickedTileX = 0;
let clickedTileY = 0;

function setupTileClick() {
  const infoContent = document.getElementById('tile-info-content')!;
  const transListEl = document.getElementById('transition-list')!;
  const addToggle = document.getElementById('add-toggle')!;
  const addForm = document.getElementById('add-transition-form')!;

  // Toggle add form visibility
  addToggle.addEventListener('click', () => {
    addForm.style.display = addForm.style.display === 'none' ? '' : 'none';
  });

  // Wire up add button
  document.getElementById('add-transition-btn')?.addEventListener('click', async () => {
    const t: Transition = {
      type: (document.getElementById('add-type') as HTMLSelectElement).value,
      srcX: clickedTileX,
      srcY: clickedTileY,
      srcP: currentPlane,
      dstX: parseInt((document.getElementById('add-dst-x') as HTMLInputElement).value) || 0,
      dstY: parseInt((document.getElementById('add-dst-y') as HTMLInputElement).value) || 0,
      dstP: parseInt((document.getElementById('add-dst-p') as HTMLInputElement).value) || 0,
      name: (document.getElementById('add-name') as HTMLInputElement).value || 'Unknown',
      option: (document.getElementById('add-option') as HTMLInputElement).value || '',
      cost: parseInt((document.getElementById('add-cost') as HTMLInputElement).value) || 1,
      bidir: (document.getElementById('add-bidir') as HTMLInputElement).checked,
    };
    const ok = await addTransition(t);
    if (ok) {
      overlay.scheduleRedraw();
      refreshTransitionList(clickedTileX, clickedTileY, currentPlane);
    } else {
      alert('Failed to add transition');
    }
  });

  // Wire up move mode cancel button
  document.getElementById('move-mode-cancel')?.addEventListener('click', () => exitMoveMode());

  map.on('click', async (e: L.LeafletMouseEvent) => {
    const worldX = Math.floor(e.latlng.lng);
    const worldY = Math.floor(e.latlng.lat);

    // Handle move mode — relocate the transition
    if (moveMode) {
      const { transition, side } = moveMode;
      exitMoveMode();
      const ok = await moveTransition(transition, side, worldX, worldY);
      if (ok) {
        overlay.scheduleRedraw();
        clickedTileX = worldX;
        clickedTileY = worldY;
        refreshTransitionList(worldX, worldY, currentPlane);
      } else {
        alert('Failed to move transition');
      }
      return;
    }

    clickedTileX = worldX;
    clickedTileY = worldY;
    const regionId = worldToRegionId(worldX, worldY);
    const local = worldToLocal(worldX, worldY);
    const region = getRegion(regionId);

    let html = `<div style="font-size: 11px; line-height: 1.6;">`;
    html += `<b>World:</b> (${worldX}, ${worldY})<br/>`;
    html += `<b>Region:</b> ${regionId}<br/>`;
    html += `<b>Local:</b> (${local.localX}, ${local.localY})<br/>`;
    html += `<b>Plane:</b> ${currentPlane}<br/>`;

    if (region) {
      const blocked = isTileBlocked(region, local.localX, local.localY, currentPlane);
      html += `<b>Blocked:</b> <span style="color: ${blocked ? '#ff4444' : '#44ff44'}">${blocked === undefined ? 'no data' : blocked ? 'YES' : 'NO'}</span><br/>`;

      const walls = getWallEdges(region, currentPlane);
      const tileWalls = walls.filter(w => w.wx === worldX && w.wy === worldY);
      if (tileWalls.length > 0) {
        html += `<b>Walls:</b> ${tileWalls.map(w => w.direction).join(', ')}<br/>`;
      }
    } else {
      html += `<span style="color: #666;">No data for this region</span>`;
    }

    html += `</div>`;
    infoContent.innerHTML = html;

    // Show transition list and add toggle
    addToggle.style.display = '';
    refreshTransitionList(worldX, worldY, currentPlane);
  });

  // Double-click: teleport camera to transition destination
  map.on('dblclick', (e: L.LeafletMouseEvent) => {
    const worldX = Math.floor(e.latlng.lng);
    const worldY = Math.floor(e.latlng.lat);
    const matches = getTransitionsAtTile(worldX, worldY, currentPlane);
    if (matches.length === 0) return;

    // Pick the first transition; use dst if we're on the src side, src if on dst side
    const match = matches[0];
    const t = match.transition;
    const targetX = match.matchedByDst ? t.srcX : t.dstX;
    const targetY = match.matchedByDst ? t.srcY : t.dstY;
    const targetP = match.matchedByDst ? t.srcP : t.dstP;

    // Switch plane if needed
    if (targetP !== currentPlane) {
      switchPlane(targetP);
    }

    map.panTo(L.latLng(targetY + 0.5, targetX + 0.5), { animate: true });
  });
}

function refreshTransitionList(worldX: number, worldY: number, plane: number) {
  const transListEl = document.getElementById('transition-list')!;
  const matches = getTransitionsAtTile(worldX, worldY, plane);

  if (matches.length === 0) {
    transListEl.innerHTML = '<div style="font-size: 10px; color: #555; padding: 4px 0;">No transitions at this tile</div>';
    return;
  }

  let html = '';
  for (let idx = 0; idx < matches.length; idx++) {
    const match = matches[idx];
    const t = match.transition;
    const isDstOnly = t.srcX === 0 && t.srcY === 0 && t.srcP === 0;
    const side = match.matchedByDst ? 'dst' : 'src';
    const moveSide = (match.matchedByDst || isDstOnly) ? 'dst' : 'src';
    const otherCoord = match.matchedByDst
      ? `from (${t.srcX},${t.srcY},${t.srcP})`
      : `to (${t.dstX},${t.dstY},${t.dstP})`;
    html += `<div class="trans-row">`;
    html += `<div class="trans-info">`;
    html += `<span class="trans-type">[${t.type}]</span> ${escHtml(t.name)}`;
    if (t.option) html += ` <span style="color:#888;">(${escHtml(t.option)})</span>`;
    html += `<br/><span class="trans-side">${side} side · ${otherCoord}${t.bidir ? ' · bidir' : ''}</span>`;
    html += `</div>`;
    html += `<button class="trans-move-btn" data-idx="${idx}" data-side="${moveSide}">Move</button>`;
    html += `<button class="trans-del-btn" data-src-x="${t.srcX}" data-src-y="${t.srcY}" data-src-p="${t.srcP}" data-dst-x="${t.dstX}" data-dst-y="${t.dstY}" data-dst-p="${t.dstP}" data-name="${escAttr(t.name)}" data-type="${t.type}" data-source="${escAttr((t as any).source || '')}">Del</button>`;
    html += `</div>`;
  }
  transListEl.innerHTML = html;

  // Wire up move buttons
  transListEl.querySelectorAll<HTMLButtonElement>('.trans-move-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const idx = parseInt(btn.dataset.idx!);
      const side = btn.dataset.side! as 'src' | 'dst';
      enterMoveMode(matches[idx].transition, side);
    });
  });

  // Wire up delete buttons
  transListEl.querySelectorAll<HTMLButtonElement>('.trans-del-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      const source = btn.dataset.source || undefined;
      const t: Transition = {
        type: btn.dataset.type!,
        srcX: parseInt(btn.dataset.srcX!),
        srcY: parseInt(btn.dataset.srcY!),
        srcP: parseInt(btn.dataset.srcP!),
        dstX: parseInt(btn.dataset.dstX!),
        dstY: parseInt(btn.dataset.dstY!),
        dstP: parseInt(btn.dataset.dstP!),
        name: btn.dataset.name!,
        option: '',
        cost: 0,
        bidir: false,
        source,
      };
      const ok = await removeTransition(t);
      if (ok) {
        overlay.scheduleRedraw();
        refreshTransitionList(clickedTileX, clickedTileY, currentPlane);
      } else {
        alert('Failed to delete transition');
      }
    });
  });
}

function escHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function escAttr(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// ── Start ──────────────────────────────────────────────
init();
