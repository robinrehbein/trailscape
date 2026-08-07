import type { TrackPoint } from "./types";

export interface Waypoint {
  lat: number;
  lon: number;
}

export interface PlannedRoute {
  points: TrackPoint[];
  distanceKm: number;
  ascentM: number;
}

export type RoutingProfile = "trekking" | "fastbike" | "shortest";

function parseNumericProperty(value: unknown): number {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string") {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  return 0;
}

export async function fetchRoute(waypoints: Waypoint[], profile: RoutingProfile): Promise<PlannedRoute> {
  if (waypoints.length < 2) {
    throw new Error("Mindestens zwei Wegpunkte nötig.");
  }

  const lonlats = waypoints
    .map((wp) => `${wp.lon.toFixed(6)},${wp.lat.toFixed(6)}`)
    .join("|");
  const url = `https://brouter.de/brouter?lonlats=${lonlats}&profile=${profile}&alternativeidx=0&format=geojson`;

  let response: Response;
  try {
    response = await fetch(url);
  } catch {
    throw new Error("Routing-Server nicht erreichbar. Bist du online?");
  }

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`Route konnte nicht berechnet werden: ${text}`);
  }

  let data: unknown;
  try {
    data = await response.json();
  } catch {
    throw new Error("Unerwartete Antwort vom Routing-Server.");
  }

  if (
    typeof data !== "object" ||
    data === null ||
    !("features" in data) ||
    !Array.isArray((data as { features: unknown }).features)
  ) {
    throw new Error("Unerwartete Antwort vom Routing-Server.");
  }

  const features = (data as { features: unknown[] }).features;
  const feature = features[0];
  if (
    typeof feature !== "object" ||
    feature === null ||
    !("geometry" in feature) ||
    !("properties" in feature)
  ) {
    throw new Error("Unerwartete Antwort vom Routing-Server.");
  }

  const geometry = (feature as { geometry: unknown }).geometry;
  if (
    typeof geometry !== "object" ||
    geometry === null ||
    !("coordinates" in geometry) ||
    !Array.isArray((geometry as { coordinates: unknown }).coordinates)
  ) {
    throw new Error("Unerwartete Antwort vom Routing-Server.");
  }

  const coordinates = (geometry as { coordinates: unknown[] }).coordinates;
  const points: TrackPoint[] = coordinates.map((coord) => {
    if (!Array.isArray(coord) || coord.length < 2) {
      throw new Error("Unerwartete Antwort vom Routing-Server.");
    }
    const [lon, lat, ele] = coord as [unknown, unknown, unknown];
    if (typeof lon !== "number" || typeof lat !== "number") {
      throw new Error("Unerwartete Antwort vom Routing-Server.");
    }
    const point: TrackPoint = { lat, lon };
    if (typeof ele === "number" && Number.isFinite(ele)) {
      point.ele = ele;
    }
    return point;
  });

  const properties = (feature as { properties: unknown }).properties;
  const props = typeof properties === "object" && properties !== null ? (properties as Record<string, unknown>) : {};

  const distanceM = parseNumericProperty(props["track-length"]);
  const ascentM = parseNumericProperty(props["filtered ascend"]);

  return {
    points,
    distanceKm: distanceM / 1000,
    ascentM,
  };
}
