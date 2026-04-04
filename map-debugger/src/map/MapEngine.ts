import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

/**
 * RS3 CRS: CRS.Simple with transformation matched to our local tile files.
 * Our tile imagery is offset +16 tiles from true game coordinates in both axes.
 * Transformation(1, 16, -1, 12784) bakes this offset into the CRS so that
 * LatLng values represent TRUE game coordinates while tiles load correctly:
 *   pixelX = gameX + 16     (tile imagery is 16 tiles east of game coords)
 *   pixelY = 12784 - gameY  (Y inverted, shifted 16 tiles south)
 */
const RS3_CRS = L.Util.extend({}, L.CRS.Simple, {
  transformation: new L.Transformation(1, 16, -1, 12784),
});

export function createMap(container: HTMLElement): L.Map {
  const map = L.map(container, {
    crs: RS3_CRS,
    minZoom: -5,
    maxZoom: 7,
    zoomControl: true,
    attributionControl: false,
    zoomSnap: 1,
    zoomDelta: 1,
    doubleClickZoom: false,
  });

  // Center on Lumbridge (3232, 3232), zoom 2
  map.setView(L.latLng(3232, 3232), 2);

  return map;
}

export function createTileLayer(plane: number): L.TileLayer {
  return L.tileLayer(`/tiles/p${plane}/{z}/{x}-{y}.webp`, {
    tileSize: 1024,
    minNativeZoom: -4,
    maxNativeZoom: 7,
    minZoom: -5,
    maxZoom: 7,
    noWrap: true,
    keepBuffer: 2,
  });
}
