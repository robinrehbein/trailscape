import * as L from "leaflet";
import { fetchRoute } from "./routing";
import type { PlannedRoute, RoutingProfile, Waypoint } from "./routing";

const START_COLOR = "#2d5a3d";
const END_COLOR = "#b3382c";
const VIA_COLOR = "#2563eb";
const ROUTE_COLOR = "#2563eb";
const MARKER_SIZE = 12;
const DEFAULT_PROFILE: RoutingProfile = "trekking";
const MARKER_HINT = "Ziehen zum Verschieben, Rechtsklick zum Entfernen";

interface PlannerWaypoint {
  lat: number;
  lon: number;
  color: string;
  marker: L.Marker;
}

export interface PlannerCallbacks {
  onRouteChanged: (route: PlannedRoute | null, waypointCount: number) => void;
  onBusy: (busy: boolean) => void;
  onError: (message: string) => void;
}

function createIcon(color: string): L.DivIcon {
  return L.divIcon({
    className: "planner-waypoint",
    html:
      '<span style="display:block;box-sizing:border-box;' +
      `width:${MARKER_SIZE}px;height:${MARKER_SIZE}px;border-radius:50%;` +
      `background:${color};border:2px solid #ffffff;` +
      'box-shadow:0 0 2px rgba(0,0,0,0.45);"></span>',
    iconSize: [MARKER_SIZE, MARKER_SIZE],
    iconAnchor: [MARKER_SIZE / 2, MARKER_SIZE / 2],
  });
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return "Route konnte nicht berechnet werden.";
}

/** Interaktive Routenplanung: Wegpunkte per Klick setzen und Route berechnen lassen. */
export class Planner {
  private readonly map: L.Map;
  private readonly callbacks: PlannerCallbacks;
  private readonly waypoints: PlannerWaypoint[] = [];
  private routeLayer: L.Polyline | null = null;
  private currentRoute: PlannedRoute | null = null;
  private profile: RoutingProfile = DEFAULT_PROFILE;
  private enabled = false;
  private busy = false;
  /** Laufende Nummer der jüngsten Anfrage; ältere Antworten werden verworfen. */
  private requestSeq = 0;

  private readonly handleMapClick = (event: L.LeafletMouseEvent): void => {
    this.addWaypoint(event.latlng.lat, event.latlng.lng);
  };

  constructor(map: L.Map, callbacks: PlannerCallbacks) {
    this.map = map;
    this.callbacks = callbacks;
  }

  /** Aktuell berechnete Route oder `null`. */
  get route(): PlannedRoute | null {
    return this.currentRoute;
  }

  /** Anzahl der gesetzten Wegpunkte. */
  get waypointCount(): number {
    return this.waypoints.length;
  }

  /** Aktiviert das Setzen von Wegpunkten per Kartenklick. Mehrfachaufrufe sind No-Ops. */
  enable(): void {
    if (this.enabled) {
      return;
    }
    this.enabled = true;
    this.map.on("click", this.handleMapClick);
    this.map.getContainer().style.cursor = "crosshair";
  }

  /** Beendet den Planungsmodus. Wegpunkte und Route bleiben erhalten. */
  disable(): void {
    if (!this.enabled) {
      return;
    }
    this.enabled = false;
    this.map.off("click", this.handleMapClick);
    this.map.getContainer().style.cursor = "";
  }

  /** Setzt das Routing-Profil und berechnet bei genügend Wegpunkten neu. */
  setProfile(profile: RoutingProfile): void {
    if (this.profile === profile) {
      return;
    }
    this.profile = profile;
    if (this.waypoints.length >= 2) {
      this.recompute();
    }
  }

  /** Entfernt den zuletzt gesetzten Wegpunkt. */
  undo(): void {
    const waypoint = this.waypoints.pop();
    if (!waypoint) {
      return;
    }
    this.detachMarker(waypoint);
    this.updateRoles();
    this.recompute();
  }

  /** Entfernt alle Wegpunkte und die Route von der Karte. */
  clear(): void {
    this.invalidatePending();
    for (const waypoint of this.waypoints) {
      this.detachMarker(waypoint);
    }
    this.waypoints.length = 0;
    this.removeRouteLayer();
    this.currentRoute = null;
    this.setBusy(false);
    this.callbacks.onRouteChanged(null, 0);
  }

  private addWaypoint(lat: number, lon: number): void {
    const marker = L.marker([lat, lon], {
      draggable: true,
      icon: createIcon(VIA_COLOR),
      title: MARKER_HINT,
      keyboard: false,
    });

    const waypoint: PlannerWaypoint = { lat, lon, color: VIA_COLOR, marker };

    marker.on("dragend", () => {
      const position = marker.getLatLng();
      waypoint.lat = position.lat;
      waypoint.lon = position.lng;
      this.recompute();
    });

    marker.on("contextmenu", (event: L.LeafletMouseEvent) => {
      L.DomEvent.preventDefault(event.originalEvent);
      this.removeWaypoint(waypoint);
    });

    marker.addTo(this.map);
    this.waypoints.push(waypoint);
    this.updateRoles();
    this.recompute();
  }

  private removeWaypoint(waypoint: PlannerWaypoint): void {
    const index = this.waypoints.indexOf(waypoint);
    if (index < 0) {
      return;
    }
    this.waypoints.splice(index, 1);
    this.detachMarker(waypoint);
    this.updateRoles();
    this.recompute();
  }

  private detachMarker(waypoint: PlannerWaypoint): void {
    waypoint.marker.off();
    this.map.removeLayer(waypoint.marker);
  }

  /** Färbt Start grün, Ziel rot und alle Zwischenpunkte blau. */
  private updateRoles(): void {
    const last = this.waypoints.length - 1;
    this.waypoints.forEach((waypoint, index) => {
      let color = VIA_COLOR;
      if (index === 0) {
        color = START_COLOR;
      } else if (index === last) {
        color = END_COLOR;
      }
      if (waypoint.color !== color) {
        waypoint.color = color;
        waypoint.marker.setIcon(createIcon(color));
      }
    });
  }

  private recompute(): void {
    const count = this.waypoints.length;

    if (count < 2) {
      this.invalidatePending();
      this.removeRouteLayer();
      this.currentRoute = null;
      this.setBusy(false);
      this.callbacks.onRouteChanged(null, count);
      return;
    }

    const seq = ++this.requestSeq;
    const waypoints: Waypoint[] = this.waypoints.map((waypoint) => ({
      lat: waypoint.lat,
      lon: waypoint.lon,
    }));

    this.setBusy(true);
    void this.runRequest(seq, waypoints);
  }

  private async runRequest(seq: number, waypoints: Waypoint[]): Promise<void> {
    try {
      const route = await fetchRoute(waypoints, this.profile);
      if (seq !== this.requestSeq) {
        return;
      }
      this.currentRoute = route;
      this.drawRoute(route);
      this.callbacks.onRouteChanged(route, this.waypoints.length);
      this.setBusy(false);
    } catch (error) {
      if (seq !== this.requestSeq) {
        return;
      }
      this.currentRoute = null;
      this.removeRouteLayer();
      this.callbacks.onError(errorMessage(error));
      this.callbacks.onRouteChanged(null, this.waypoints.length);
      this.setBusy(false);
    }
  }

  private drawRoute(route: PlannedRoute): void {
    this.removeRouteLayer();

    if (route.points.length === 0) {
      return;
    }

    const latLngs = route.points.map(
      (point) => [point.lat, point.lon] as L.LatLngExpression
    );

    this.routeLayer = L.polyline(latLngs, {
      color: ROUTE_COLOR,
      weight: 5,
      opacity: 0.85,
      dashArray: "8 6",
      lineJoin: "round",
      lineCap: "round",
    }).addTo(this.map);
  }

  private removeRouteLayer(): void {
    if (this.routeLayer) {
      this.map.removeLayer(this.routeLayer);
      this.routeLayer = null;
    }
  }

  /** Entwertet eine eventuell laufende Anfrage, ohne sie abzubrechen. */
  private invalidatePending(): void {
    this.requestSeq += 1;
  }

  private setBusy(busy: boolean): void {
    if (this.busy === busy) {
      return;
    }
    this.busy = busy;
    this.callbacks.onBusy(busy);
  }
}
