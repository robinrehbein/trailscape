import { haversineM } from "./stats";
import type { TrackPoint } from "./types";

/** Meter pro Breitengrad (äquirektanguläre Näherung). */
const M_PER_DEG_LAT = 111320;
/** Halbe Fensterbreite in Segmenten für die lokale Suche. */
const SEARCH_WINDOW_SEGMENTS = 50;
/** Ab diesem Fenster-Abstand wird einmalig global gesucht. */
const GLOBAL_SEARCH_THRESHOLD_M = 200;
/** Abstand, ab dem die Position als "abseits" gilt. */
const OFF_ROUTE_ENTER_M = 60;
/** Abstand, ab dem die Position wieder als "auf Route" gilt. */
const OFF_ROUTE_EXIT_M = 35;
/** Wie lange der Abstand durchgehend zu groß sein muss. */
const OFF_ROUTE_DELAY_MS = 5000;

export interface NavState {
  /** Index des nächstgelegenen Routenpunkts (Original-Array). */
  nearestIndex: number;
  /** Kürzester Abstand zur Route in Metern. */
  distanceToRouteM: number;
  /** Zurückgelegte Distanz entlang der Route bis zur projizierten Position. */
  doneKm: number;
  /** Verbleibende Distanz entlang der Route. */
  remainingKm: number;
  /** Abseits der Route (mit Hysterese). */
  offRoute: boolean;
}

interface Projection {
  segmentIndex: number;
  /** Parameter auf dem Segment, 0 = Anfang, 1 = Ende. */
  t: number;
  distanceM: number;
}

function toRad(deg: number): number {
  return (deg * Math.PI) / 180;
}

/**
 * Navigation entlang einer festen Route: projiziert die aktuelle Position auf
 * die Route und liefert Fortschritt sowie Abweichung.
 *
 * Die Projektion rechnet lokal in einer äquirektangulären Näherung (Meter pro
 * Grad), was für Abstände von wenigen Kilometern ausreichend genau und
 * deutlich schneller als Haversine pro Segment ist. Die Distanzen entlang der
 * Route stammen dagegen aus der exakten Haversine-Vorberechnung.
 */
export class RouteNavigator {
  private readonly route: TrackPoint[];
  /** Kumulative Distanz in Metern bis zum jeweiligen Routenpunkt. */
  private readonly cumulativeM: number[];
  private readonly totalM: number;

  /** Zuletzt getroffenes Segment, Startpunkt der gefensterten Suche. */
  private lastSegmentIndex = 0;
  private offRouteState = false;
  /** Zeitpunkt, seit dem der Abstand durchgehend zu groß ist. */
  private farSinceMs: number | null = null;

  constructor(route: TrackPoint[]) {
    if (route.length < 2) {
      throw new Error("Route benötigt mindestens 2 Punkte.");
    }

    this.route = route;

    const cumulativeM = new Array<number>(route.length);
    cumulativeM[0] = 0;
    for (let i = 1; i < route.length; i++) {
      cumulativeM[i] = cumulativeM[i - 1] + haversineM(route[i - 1], route[i]);
    }

    this.cumulativeM = cumulativeM;
    this.totalM = cumulativeM[cumulativeM.length - 1];
  }

  /** Gesamtlänge der Route in Kilometern. */
  get totalKm(): number {
    return this.totalM / 1000;
  }

  /** Aktualisiert den Navigationszustand für die aktuelle Position. */
  update(pos: { lat: number; lon: number }, now: number = Date.now()): NavState {
    const mPerDegLon = M_PER_DEG_LAT * Math.cos(toRad(pos.lat));
    const lastSegment = this.route.length - 2;

    const from = Math.max(0, this.lastSegmentIndex - SEARCH_WINDOW_SEGMENTS);
    const to = Math.min(lastSegment, this.lastSegmentIndex + SEARCH_WINDOW_SEGMENTS);

    let best = this.searchRange(pos, mPerDegLon, from, to);

    // Der Nutzer könnte die Route weit verlassen haben oder gesprungen sein:
    // dann lohnt sich eine einmalige globale Suche.
    if (best.distanceM > GLOBAL_SEARCH_THRESHOLD_M && (from > 0 || to < lastSegment)) {
      const global = this.searchRange(pos, mPerDegLon, 0, lastSegment);
      if (global.distanceM < best.distanceM) {
        best = global;
      }
    }

    this.lastSegmentIndex = best.segmentIndex;

    const segmentStartM = this.cumulativeM[best.segmentIndex];
    const segmentLengthM = this.cumulativeM[best.segmentIndex + 1] - segmentStartM;
    const doneM = Math.min(this.totalM, segmentStartM + best.t * segmentLengthM);
    const remainingM = Math.max(0, this.totalM - doneM);

    return {
      nearestIndex: best.t <= 0.5 ? best.segmentIndex : best.segmentIndex + 1,
      distanceToRouteM: best.distanceM,
      doneKm: doneM / 1000,
      remainingKm: remainingM / 1000,
      offRoute: this.updateOffRoute(best.distanceM, now),
    };
  }

  /** Bestes Segment im Indexbereich [from, to] (jeweils einschließlich). */
  private searchRange(
    pos: { lat: number; lon: number },
    mPerDegLon: number,
    from: number,
    to: number,
  ): Projection {
    let bestIndex = from;
    let bestT = 0;
    let bestDistanceM = Number.POSITIVE_INFINITY;

    for (let i = from; i <= to; i++) {
      const a = this.route[i];
      const b = this.route[i + 1];

      // Lokales Meter-Koordinatensystem mit der Position im Ursprung.
      const ax = (a.lon - pos.lon) * mPerDegLon;
      const ay = (a.lat - pos.lat) * M_PER_DEG_LAT;
      const bx = (b.lon - pos.lon) * mPerDegLon;
      const by = (b.lat - pos.lat) * M_PER_DEG_LAT;

      const dx = bx - ax;
      const dy = by - ay;
      const lengthSq = dx * dx + dy * dy;

      let t = 0;
      if (lengthSq > 0) {
        t = (-ax * dx - ay * dy) / lengthSq;
        t = t < 0 ? 0 : t > 1 ? 1 : t;
      }

      const px = ax + t * dx;
      const py = ay + t * dy;
      const distanceM = Math.sqrt(px * px + py * py);

      if (distanceM < bestDistanceM) {
        bestDistanceM = distanceM;
        bestIndex = i;
        bestT = t;
      }
    }

    return { segmentIndex: bestIndex, t: bestT, distanceM: bestDistanceM };
  }

  /**
   * Hysterese: abseits erst, wenn der Abstand seit mindestens 5 Sekunden
   * durchgehend über 60 m liegt; zurück auf der Route, sobald er einmal
   * unter 35 m fällt. Dazwischen bleibt der Zustand unverändert.
   */
  private updateOffRoute(distanceM: number, now: number): boolean {
    if (distanceM < OFF_ROUTE_EXIT_M) {
      this.farSinceMs = null;
      this.offRouteState = false;
      return this.offRouteState;
    }

    if (distanceM > OFF_ROUTE_ENTER_M) {
      if (this.farSinceMs === null) {
        this.farSinceMs = now;
      } else if (now - this.farSinceMs >= OFF_ROUTE_DELAY_MS) {
        this.offRouteState = true;
      }
      return this.offRouteState;
    }

    // 35 m ≤ Abstand ≤ 60 m: Zustand halten, Zähler zurücksetzen.
    this.farSinceMs = null;
    return this.offRouteState;
  }
}
