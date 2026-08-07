import * as L from "leaflet";
import "leaflet/dist/leaflet.css";
import type { TrackPoint } from "./types";

const TRACK_COLOR = "#2d5a3d";
const LIVE_COLOR = "#b3382c";
const GERMANY_CENTER: L.LatLngExpression = [51.0, 10.0];
const GERMANY_ZOOM = 6;
const MIN_LIVE_ZOOM = 15;

let map: L.Map | null = null;
let trackLayer: L.Polyline | null = null;
let liveLayer: L.Polyline | null = null;
let liveCentered = false;
let positionMarker: L.CircleMarker | null = null;

function toLatLngs(points: TrackPoint[]): L.LatLngExpression[] {
  return points.map((point) => [point.lat, point.lon] as L.LatLngExpression);
}

function removeLayer(layer: L.Polyline | null): null {
  if (layer && map) {
    map.removeLayer(layer);
  }
  return null;
}

/** Initialisiert die Karte im übergebenen Container. Mehrfachaufrufe sind No-Ops. */
export function initMap(el: HTMLElement): void {
  if (map) {
    return;
  }

  map = L.map(el, { zoomControl: true }).setView(GERMANY_CENTER, GERMANY_ZOOM);

  const osm = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution:
      '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>-Mitwirkende',
  });

  const topo = L.tileLayer("https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png", {
    maxZoom: 17,
    attribution:
      'Kartendaten: &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>-Mitwirkende, ' +
      'SRTM | Kartendarstellung: &copy; <a href="https://opentopomap.org">OpenTopoMap</a> ' +
      '(<a href="https://creativecommons.org/licenses/by-sa/3.0/">CC-BY-SA</a>)',
  });

  osm.addTo(map);

  L.control
    .layers(
      { OpenStreetMap: osm, OpenTopoMap: topo },
      {},
      { position: "topright", collapsed: true }
    )
    .addTo(map);
}

/** Zeigt eine gespeicherte Tour an und zoomt auf ihre Ausdehnung. */
export function showTrack(points: TrackPoint[]): void {
  if (!map) {
    return;
  }

  clearTrack();

  if (points.length === 0) {
    return;
  }

  const latLngs = toLatLngs(points);
  trackLayer = L.polyline(latLngs, {
    color: TRACK_COLOR,
    weight: 4,
    opacity: 0.9,
    lineJoin: "round",
    lineCap: "round",
  }).addTo(map);

  map.fitBounds(trackLayer.getBounds(), { padding: [32, 32] });
}

/** Aktualisiert die laufende Aufzeichnung und folgt dem letzten Punkt. */
export function updateLiveTrack(points: TrackPoint[]): void {
  if (!map || points.length === 0) {
    return;
  }

  const latLngs = toLatLngs(points);

  if (liveLayer) {
    liveLayer.setLatLngs(latLngs);
  } else {
    liveLayer = L.polyline(latLngs, {
      color: LIVE_COLOR,
      weight: 4,
      opacity: 0.9,
      lineJoin: "round",
      lineCap: "round",
    }).addTo(map);
  }

  const last = points[points.length - 1];
  const center: L.LatLngExpression = [last.lat, last.lon];

  if (!liveCentered) {
    map.setView(center, Math.max(map.getZoom(), MIN_LIVE_ZOOM));
    liveCentered = true;
  } else {
    map.setView(center, map.getZoom());
  }
}

/** Entfernt alle Track-Layer von der Karte. */
export function clearTrack(): void {
  trackLayer = removeLayer(trackLayer);
  liveLayer = removeLayer(liveLayer);
  liveCentered = false;
  hidePositionMarker();
}

/** Zeigt einen einzelnen Positions-Marker an der gegebenen Position an. */
export function showPositionMarker(point: TrackPoint): void {
  if (!map) {
    return;
  }

  const latLng: L.LatLngExpression = [point.lat, point.lon];

  if (positionMarker) {
    positionMarker.setLatLng(latLng);
  } else {
    positionMarker = L.circleMarker(latLng, {
      radius: 6,
      color: "#ffffff",
      weight: 2,
      fillColor: "#b3382c",
      fillOpacity: 1,
    }).addTo(map);
  }
}

/** Entfernt den Positions-Marker von der Karte. */
export function hidePositionMarker(): void {
  if (positionMarker && map) {
    map.removeLayer(positionMarker);
  }
}

/** Gibt die interne Leaflet-Map-Instanz zurück oder null, falls initMap noch nicht lief. */
export function getMap(): L.Map | null {
  return map;
}
