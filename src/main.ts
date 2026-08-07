import "./style.css";

import {
  clearTrack,
  hidePositionMarker,
  initMap,
  showPositionMarker,
  showTrack,
  updateLiveTrack,
} from "./map";
import { buildGpx, parseGpx } from "./gpx";
import { clearProfile, renderProfile } from "./profile";
import { computeStats, formatDuration, formatKm } from "./stats";
import { deleteRide, getRide, listRides, saveRide } from "./storage";
import { Recorder } from "./recorder";
import type { Ride, RideStats, TrackPoint } from "./types";

/* ------------------------------------------------------------------ DOM */

const mapEl = document.getElementById("map")!;
const mapPaneEl = document.getElementById("map-pane")!;
const profilePanelEl = document.getElementById("profile-panel")!;
const profileEl = document.getElementById("profile")!;
const rideListEl = document.getElementById("ride-list")!;
const rideListEmptyEl = document.getElementById("ride-list-empty")!;

const statsPanelEl = document.getElementById("stats-panel")!;
const statDistanceEl = document.getElementById("stat-distance")!;
const statDurationEl = document.getElementById("stat-duration")!;
const statSpeedEl = document.getElementById("stat-speed")!;
const statAscentEl = document.getElementById("stat-ascent")!;

const gpxInputEl = document.getElementById("gpx-input") as HTMLInputElement;
const btnRecordEl = document.getElementById("btn-record") as HTMLButtonElement;
const btnExportEl = document.getElementById("btn-export") as HTMLButtonElement;
const btnDeleteEl = document.getElementById("btn-delete") as HTMLButtonElement;

const recordBannerEl = document.getElementById("record-banner")!;
const recordInfoEl = document.getElementById("record-info")!;

/* ---------------------------------------------------------------- State */

const recorder = new Recorder();
let rides: Ride[] = [];
let currentRideId: string | null = null;

const RECORD_LABEL = "● Aufzeichnen";
const STOP_LABEL = "■ Stopp";

/* -------------------------------------------------------------- Helpers */

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function formatDate(timestamp: number): string {
  return new Date(timestamp).toLocaleDateString("de-DE", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
  });
}

function safeFileName(name: string): string {
  const cleaned = name.trim().replace(/[^a-zA-Z0-9\-_]+/g, "_").replace(/^_+|_+$/g, "");
  return cleaned.length > 0 ? cleaned : "tour";
}

function firstTimestamp(points: TrackPoint[]): number {
  const first = points[0] as TrackPoint | undefined;
  return first?.time ?? Date.now();
}

/* ----------------------------------------------------------- Tourenliste */

function markActiveRide(): void {
  for (const item of Array.from(rideListEl.children)) {
    const isActive = item instanceof HTMLElement && item.dataset.rideId === currentRideId;
    item.classList.toggle("active", isActive);
  }
}

function renderRideList(): void {
  rideListEl.replaceChildren();

  for (const ride of rides) {
    const item = document.createElement("li");
    item.dataset.rideId = ride.id;

    const name = document.createElement("span");
    name.className = "ride-name";
    name.textContent = ride.name;

    const meta = document.createElement("span");
    meta.className = "ride-meta";
    meta.textContent =
      `${formatDate(ride.createdAt)} · ${formatKm(ride.stats.distanceKm)} km · ` +
      formatDuration(ride.stats.durationS);

    item.append(name, meta);
    item.addEventListener("click", () => {
      void selectRide(ride.id);
    });

    rideListEl.append(item);
  }

  rideListEmptyEl.hidden = rides.length > 0;
  markActiveRide();
}

async function refreshRideList(): Promise<void> {
  rides = await listRides();
  renderRideList();
}

/* ----------------------------------------------------------- Statistiken */

function showStats(stats: RideStats): void {
  statDistanceEl.textContent = formatKm(stats.distanceKm);
  statDurationEl.textContent = formatDuration(stats.durationS);
  statSpeedEl.textContent =
    stats.avgSpeedKmh === null ? "–" : stats.avgSpeedKmh.toFixed(1);
  statAscentEl.textContent = String(Math.round(stats.ascentM));
  statsPanelEl.hidden = false;
}

function hideStats(): void {
  statsPanelEl.hidden = true;
}

function showProfile(ride: Ride): void {
  const hasElevation = renderProfile(profileEl, ride.points, (index: number | null) => {
    if (index === null) {
      hidePositionMarker();
    } else {
      showPositionMarker(ride.points[index]!);
    }
  });

  profilePanelEl.hidden = !hasElevation;
  mapPaneEl.classList.toggle("has-profile", hasElevation);
}

function hideProfile(): void {
  clearProfile(profileEl);
  profilePanelEl.hidden = true;
  mapPaneEl.classList.remove("has-profile");
}

async function selectRide(id: string): Promise<void> {
  const ride = await getRide(id);
  if (!ride) {
    await refreshRideList();
    return;
  }

  currentRideId = ride.id;
  showTrack(ride.points);
  showStats(ride.stats);
  showProfile(ride);
  markActiveRide();
}

/* ----------------------------------------------------------- GPX-Import */

async function handleGpxImport(): Promise<void> {
  const file = gpxInputEl.files && gpxInputEl.files[0];
  if (!file) {
    return;
  }

  try {
    const xml = await file.text();
    const parsed = parseGpx(xml);
    const points = parsed.points;
    const stats = computeStats(points);
    const fallbackName = file.name.replace(/\.[^.]+$/, "");
    const name = parsed.name && parsed.name.trim() ? parsed.name.trim() : fallbackName;

    const ride: Ride = {
      id: crypto.randomUUID(),
      name,
      createdAt: firstTimestamp(points),
      points,
      stats,
    };

    await saveRide(ride);
    await refreshRideList();
    await selectRide(ride.id);
  } catch (error) {
    alert(errorMessage(error));
  } finally {
    // Zurücksetzen, damit dieselbe Datei erneut ausgewählt werden kann.
    gpxInputEl.value = "";
  }
}

/* ---------------------------------------------------------- Aufzeichnung */

function setRecordingUi(active: boolean): void {
  btnRecordEl.textContent = active ? STOP_LABEL : RECORD_LABEL;
  btnRecordEl.classList.toggle("recording", active);
  recordBannerEl.hidden = !active;
  if (!active) {
    recordInfoEl.textContent = "Aufzeichnung läuft …";
  }
}

function handleRecordedPoint(_point: TrackPoint, all: TrackPoint[]): void {
  updateLiveTrack(all);
  recordInfoEl.textContent = `Aufzeichnung · ${formatKm(computeStats(all).distanceKm)} km`;
}

function startRecording(): void {
  try {
    recorder.start(handleRecordedPoint, (message) => alert(message));
  } catch (error) {
    alert(errorMessage(error));
    return;
  }

  currentRideId = null;
  clearTrack();
  hideStats();
  hideProfile();
  markActiveRide();
  setRecordingUi(true);
  recordInfoEl.textContent = `Aufzeichnung · ${formatKm(0)} km`;
}

async function stopRecording(): Promise<void> {
  const points = recorder.stop();
  setRecordingUi(false);

  if (points.length < 2) {
    alert("Zu wenige GPS-Punkte aufgezeichnet.");
    return;
  }

  const suggestion = `Tour ${formatDate(Date.now())}`;
  const answer = prompt("Name der Tour", suggestion);
  const name = answer && answer.trim() ? answer.trim() : suggestion;

  const ride: Ride = {
    id: crypto.randomUUID(),
    name,
    createdAt: firstTimestamp(points),
    points,
    stats: computeStats(points),
  };

  try {
    await saveRide(ride);
    await refreshRideList();
    await selectRide(ride.id);
  } catch (error) {
    alert(errorMessage(error));
  }
}

function toggleRecording(): void {
  if (recorder.isRecording) {
    void stopRecording();
  } else {
    startRecording();
  }
}

/* ----------------------------------------------------- Export & Löschen */

async function exportCurrentRide(): Promise<void> {
  if (!currentRideId) {
    return;
  }

  const ride = await getRide(currentRideId);
  if (!ride) {
    return;
  }

  try {
    const xml = buildGpx(ride.name, ride.points);
    const blob = new Blob([xml], { type: "application/gpx+xml" });
    const url = URL.createObjectURL(blob);

    const link = document.createElement("a");
    link.href = url;
    link.download = `${safeFileName(ride.name)}.gpx`;
    document.body.append(link);
    link.click();
    link.remove();

    URL.revokeObjectURL(url);
  } catch (error) {
    alert(errorMessage(error));
  }
}

async function deleteCurrentRide(): Promise<void> {
  if (!currentRideId) {
    return;
  }

  const ride = await getRide(currentRideId);
  const label = ride ? `„${ride.name}“` : "diese Tour";
  if (!confirm(`Soll ${label} wirklich gelöscht werden?`)) {
    return;
  }

  await deleteRide(currentRideId);
  currentRideId = null;
  await refreshRideList();
  clearTrack();
  hideStats();
  hideProfile();
}

/* ----------------------------------------------------------- Service Worker */

function registerServiceWorker(): void {
  const meta = import.meta as ImportMeta & { env?: { PROD?: boolean } };
  if (!meta.env?.PROD || !("serviceWorker" in navigator)) {
    return;
  }

  navigator.serviceWorker.register("/sw.js").catch(() => {
    /* Registrierung ist optional – Fehler bewusst ignorieren. */
  });
}

/* -------------------------------------------------------------- Bootstrap */

function init(): void {
  initMap(mapEl);

  gpxInputEl.addEventListener("change", () => {
    void handleGpxImport();
  });
  btnRecordEl.addEventListener("click", toggleRecording);
  btnExportEl.addEventListener("click", () => {
    void exportCurrentRide();
  });
  btnDeleteEl.addEventListener("click", () => {
    void deleteCurrentRide();
  });

  registerServiceWorker();

  void refreshRideList().catch((error: unknown) => {
    alert(errorMessage(error));
  });
}

init();
