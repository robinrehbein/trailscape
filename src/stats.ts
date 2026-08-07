import type { RideStats, TrackPoint } from "./types";

const EARTH_RADIUS_M = 6371000;
const MOVING_SPEED_THRESHOLD_KMH = 1;
const ELEVATION_HYSTERESIS_M = 3;

function toRad(deg: number): number {
  return (deg * Math.PI) / 180;
}

/** Distanz zwischen zwei Punkten in Metern (Haversine-Formel). */
export function haversineM(a: TrackPoint, b: TrackPoint): number {
  const dLat = toRad(b.lat - a.lat);
  const dLon = toRad(b.lon - a.lon);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);

  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  const c = 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));

  return EARTH_RADIUS_M * c;
}

function emptyStats(): RideStats {
  return {
    distanceKm: 0,
    durationS: null,
    movingTimeS: null,
    avgSpeedKmh: null,
    ascentM: 0,
    descentM: 0,
  };
}

function computeElevation(points: TrackPoint[]): { ascentM: number; descentM: number } {
  const withEle = points.filter((p) => p.ele !== undefined) as (TrackPoint & { ele: number })[];

  if (withEle.length < 2) {
    return { ascentM: 0, descentM: 0 };
  }

  let ascentM = 0;
  let descentM = 0;
  let referenceEle = withEle[0].ele;

  for (let i = 1; i < withEle.length; i++) {
    const diff = withEle[i].ele - referenceEle;

    if (Math.abs(diff) >= ELEVATION_HYSTERESIS_M) {
      if (diff > 0) {
        ascentM += diff;
      } else {
        descentM += -diff;
      }
      referenceEle = withEle[i].ele;
    }
  }

  return { ascentM, descentM };
}

/**
 * Berechnet Fahrt-Statistiken aus einer Liste von Trackpunkten.
 * Höhenmeter werden mit einer Hysterese-Schwelle von 3 m geglättet,
 * um GPS-Rauschen nicht als Anstieg/Abstieg zu zählen.
 */
export function computeStats(points: TrackPoint[]): RideStats {
  if (points.length < 2) {
    return emptyStats();
  }

  let distanceM = 0;
  let movingTimeS = 0;
  let hasMovingTimeData = false;

  for (let i = 1; i < points.length; i++) {
    const prev = points[i - 1];
    const curr = points[i];
    const segmentM = haversineM(prev, curr);
    distanceM += segmentM;

    if (prev.time !== undefined && curr.time !== undefined) {
      const dtS = (curr.time - prev.time) / 1000;
      if (dtS > 0) {
        hasMovingTimeData = true;
        const speedKmh = (segmentM / 1000 / dtS) * 3600;
        if (speedKmh > MOVING_SPEED_THRESHOLD_KMH) {
          movingTimeS += dtS;
        }
      }
    }
  }

  const distanceKm = distanceM / 1000;

  const firstTime = points[0].time;
  const lastTime = points[points.length - 1].time;
  const durationS =
    firstTime !== undefined && lastTime !== undefined ? (lastTime - firstTime) / 1000 : null;

  const resolvedMovingTimeS = hasMovingTimeData ? movingTimeS : null;

  let avgSpeedKmh: number | null = null;
  if (resolvedMovingTimeS !== null && resolvedMovingTimeS > 0) {
    avgSpeedKmh = distanceKm / (resolvedMovingTimeS / 3600);
  } else if (durationS !== null && durationS > 0) {
    avgSpeedKmh = distanceKm / (durationS / 3600);
  }

  const { ascentM, descentM } = computeElevation(points);

  return {
    distanceKm,
    durationS,
    movingTimeS: resolvedMovingTimeS,
    avgSpeedKmh,
    ascentM,
    descentM,
  };
}

/** Formatiert Sekunden als "H:MM:SS" bzw. "M:SS", "–" bei null. */
export function formatDuration(s: number | null): string {
  if (s === null) {
    return "–";
  }

  const totalS = Math.max(0, Math.round(s));
  const hours = Math.floor(totalS / 3600);
  const minutes = Math.floor((totalS % 3600) / 60);
  const seconds = totalS % 60;

  const mm = String(minutes).padStart(2, "0");
  const ss = String(seconds).padStart(2, "0");

  if (hours > 0) {
    return `${hours}:${mm}:${ss}`;
  }

  return `${minutes}:${ss}`;
}

/** Formatiert Kilometer mit einer Nachkommastelle, z. B. "42.3". */
export function formatKm(km: number): string {
  return km.toFixed(1);
}
