import "./style.css";

import {
  clearTrack,
  getMap,
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
import { Planner } from "./planner";
import type { PlannerCallbacks } from "./planner";
import type { PlannedRoute, RoutingProfile } from "./routing";
import type { Ride, RideStats, TrackPoint } from "./types";
import { getSyncConfig, setSyncConfig, syncRides } from "./sync";
import { cachedTileCount, clearTileCache, downloadRegion } from "./offline";
import { assessFitness, LEVEL_LABELS } from "./fitness";
import type { FitnessAssessment } from "./fitness";
import {
  currentWeekIndex,
  generatePlan,
  loadPlan,
  savePlan,
  weekKm,
  WEEK_KIND_LABELS,
} from "./training";
import type { Goal, TrainingPlan } from "./training";

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

const btnPlanEl = document.getElementById("btn-plan") as HTMLButtonElement;
const planPanelEl = document.getElementById("plan-panel")!;
const planInfoEl = document.getElementById("plan-info")!;
const planProfileEl = document.getElementById("plan-profile") as HTMLSelectElement;
const btnPlanUndoEl = document.getElementById("btn-plan-undo") as HTMLButtonElement;
const btnPlanClearEl = document.getElementById("btn-plan-clear") as HTMLButtonElement;
const btnPlanSaveEl = document.getElementById("btn-plan-save") as HTMLButtonElement;
const btnPlanExportEl = document.getElementById("btn-plan-export") as HTMLButtonElement;

const offlineStatusEl = document.getElementById("offline-status")!;
const btnOfflineSaveEl = document.getElementById("btn-offline-save") as HTMLButtonElement;
const btnOfflineClearEl = document.getElementById("btn-offline-clear") as HTMLButtonElement;

const syncUrlEl = document.getElementById("sync-url") as HTMLInputElement;
const syncTokenEl = document.getElementById("sync-token") as HTMLInputElement;
const btnSyncEl = document.getElementById("btn-sync") as HTMLButtonElement;
const syncStatusEl = document.getElementById("sync-status")!;

const btnTrainingEl = document.getElementById("btn-training") as HTMLButtonElement;
const btnTrainingCloseEl = document.getElementById("btn-training-close") as HTMLButtonElement;
const trainingPanelEl = document.getElementById("training-panel")!;
const fitnessCardEl = document.getElementById("fitness-card")!;
const goalFormEl = document.getElementById("goal-form") as HTMLFormElement;
const goalNameEl = document.getElementById("goal-name") as HTMLInputElement;
const goalDistanceEl = document.getElementById("goal-distance") as HTMLInputElement;
const goalAscentEl = document.getElementById("goal-ascent") as HTMLInputElement;
const goalDateEl = document.getElementById("goal-date") as HTMLInputElement;
const btnGoalDeleteEl = document.getElementById("btn-goal-delete") as HTMLButtonElement;
const goalStatusEl = document.getElementById("goal-status")!;
const planSectionEl = document.getElementById("plan-section")!;
const planTitleEl = document.getElementById("plan-title")!;
const planWeeksEl = document.getElementById("plan-weeks")!;

/* ---------------------------------------------------------------- State */

const recorder = new Recorder();
let rides: Ride[] = [];
let currentRideId: string | null = null;

let planner: Planner | null = null;
let planning = false;
let plannedRoute: PlannedRoute | null = null;

const RECORD_LABEL = "● Aufzeichnen";
const STOP_LABEL = "■ Stopp";
const PLAN_LABEL = "Route planen";
const PLAN_STOP_LABEL = "Planung beenden";
const PLAN_INFO_DEFAULT =
  "Klicke auf die Karte, um Wegpunkte zu setzen. Ziehen verschiebt, Rechtsklick entfernt.";

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

function toDateInputValue(timestamp: number): string {
  const d = new Date(timestamp);
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}`;
}

function formatShortDate(timestamp: number): string {
  const d = new Date(timestamp);
  const dd = String(d.getDate()).padStart(2, "0");
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  return `${dd}.${mm}.`;
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

function showProfileForPoints(points: TrackPoint[]): void {
  const hasElevation = renderProfile(profileEl, points, (index: number | null) => {
    if (index === null) {
      hidePositionMarker();
    } else {
      showPositionMarker(points[index]!);
    }
  });

  profilePanelEl.hidden = !hasElevation;
  mapPaneEl.classList.toggle("has-profile", hasElevation);
}

function showProfile(ride: Ride): void {
  showProfileForPoints(ride.points);
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
    if (planning) {
      exitPlanning();
    }
    startRecording();
  }
}

/* ------------------------------------------------------ Routenplanung */

function setPlanInfo(text: string, isError = false): void {
  planInfoEl.textContent = text;
  planInfoEl.style.color = isError ? "var(--danger)" : "";
}

function plannerCallbacks(): PlannerCallbacks {
  return {
    onRouteChanged(route: PlannedRoute | null, waypointCount: number): void {
      plannedRoute = route;
      btnPlanSaveEl.disabled = !route;
      btnPlanExportEl.disabled = !route;

      if (route) {
        setPlanInfo(
          `${formatKm(route.distanceKm)} km · ${Math.round(route.ascentM)} Hm ↑ · ${waypointCount} Wegpunkte`
        );
        showProfileForPoints(route.points);
      } else {
        if (waypointCount > 0) {
          const suffix = waypointCount === 1 ? "Wegpunkt" : "Wegpunkte";
          setPlanInfo(`${waypointCount} ${suffix} – setze mindestens 2.`);
        } else {
          setPlanInfo(PLAN_INFO_DEFAULT);
        }
        hideProfile();
      }
    },
    onBusy(busy: boolean): void {
      if (busy) {
        planInfoEl.textContent = `${planInfoEl.textContent} · berechne…`;
      }
    },
    onError(message: string): void {
      setPlanInfo(message, true);
    },
  };
}

function setPlanningUi(active: boolean): void {
  planning = active;
  planPanelEl.hidden = !active;
  btnPlanEl.classList.toggle("active", active);
  btnPlanEl.textContent = active ? PLAN_STOP_LABEL : PLAN_LABEL;
}

function exitPlanning(): void {
  if (planner) {
    planner.clear();
    planner.disable();
  }
  setPlanningUi(false);
  plannedRoute = null;
  hideProfile();
}

function enterPlanning(): void {
  if (recorder.isRecording) {
    alert("Beende zuerst die Aufzeichnung.");
    return;
  }

  currentRideId = null;
  clearTrack();
  hideStats();
  hideProfile();
  markActiveRide();

  if (!planner) {
    const map = getMap();
    if (!map) {
      return;
    }
    planner = new Planner(map, plannerCallbacks());
  }

  planner.enable();
  setPlanInfo(PLAN_INFO_DEFAULT);
  setPlanningUi(true);
}

function togglePlanning(): void {
  if (planning) {
    exitPlanning();
  } else {
    enterPlanning();
  }
}

async function savePlannedRoute(): Promise<void> {
  if (!plannedRoute) {
    return;
  }

  const suggestion = `Route ${formatDate(Date.now())}`;
  const answer = prompt("Name der Route", suggestion);
  const name = answer && answer.trim() ? answer.trim() : suggestion;

  const stats = computeStats(plannedRoute.points);
  stats.distanceKm = plannedRoute.distanceKm;
  stats.ascentM = plannedRoute.ascentM;

  const ride: Ride = {
    id: crypto.randomUUID(),
    name,
    createdAt: Date.now(),
    points: plannedRoute.points,
    stats,
  };

  try {
    await saveRide(ride);
    await refreshRideList();
    exitPlanning();
    await selectRide(ride.id);
  } catch (error) {
    alert(errorMessage(error));
  }
}

function exportPlannedRoute(): void {
  if (!plannedRoute) {
    return;
  }

  try {
    const xml = buildGpx("trailscape-route", plannedRoute.points);
    const blob = new Blob([xml], { type: "application/gpx+xml" });
    const url = URL.createObjectURL(blob);

    const link = document.createElement("a");
    link.href = url;
    link.download = "trailscape-route.gpx";
    document.body.append(link);
    link.click();
    link.remove();

    URL.revokeObjectURL(url);
  } catch (error) {
    alert(errorMessage(error));
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

/* ---------------------------------------------------------------- Training */

function togglePanelVisibility(el: HTMLElement): boolean {
  const wasHidden = el.hidden;
  el.hidden = !wasHidden;
  return wasHidden;
}

function renderFitnessCard(assessment: FitnessAssessment): void {
  fitnessCardEl.replaceChildren();

  const levelEl = document.createElement("span");
  levelEl.className = "fitness-level";
  levelEl.textContent = LEVEL_LABELS[assessment.level];
  fitnessCardEl.append(levelEl);

  const metricsEl = document.createElement("div");
  metricsEl.className = "fitness-metrics";

  const metrics: Array<[string, string]> = [
    [formatKm(assessment.weeklyKm), "km/Woche"],
    [String(assessment.weeklyHm), "Hm/Woche"],
    [String(assessment.weeklyRides), "Fahrten/Woche"],
    [formatKm(assessment.longestRideKm), "km längste Tour"],
  ];

  for (const [value, label] of metrics) {
    const entryEl = document.createElement("span");
    const strongEl = document.createElement("strong");
    strongEl.textContent = value;
    entryEl.append(strongEl, document.createTextNode(` ${label}`));
    metricsEl.append(entryEl);
  }

  fitnessCardEl.append(metricsEl);

  if (assessment.rideCount === 0) {
    const hintEl = document.createElement("p");
    hintEl.className = "muted";
    hintEl.textContent =
      "Noch keine Touren der letzten 8 Wochen vorhanden – die Einstufung ist daher konservativ.";
    fitnessCardEl.append(hintEl);
  }
}

function renderPlanWeeks(plan: TrainingPlan, planRides: Ride[]): void {
  planTitleEl.textContent = `${plan.goal.name} – ${plan.goal.distanceKm} km am ${formatDate(plan.goal.date)}`;

  planWeeksEl.replaceChildren();
  const activeIndex = currentWeekIndex(plan);

  for (const week of plan.weeks) {
    const li = document.createElement("li");
    li.className = "week";
    if (week.index === activeIndex) {
      li.classList.add("current");
    } else if (week.index < activeIndex) {
      li.classList.add("past");
    }

    const headEl = document.createElement("div");
    headEl.className = "week-head";

    const titleEl = document.createElement("span");
    titleEl.className = "week-title";
    titleEl.textContent =
      `Woche ${week.index + 1} · ${formatShortDate(week.start)}–${formatShortDate(week.end)}`;

    const kindEl = document.createElement("span");
    kindEl.className = `week-kind ${week.kind}`;
    kindEl.textContent = WEEK_KIND_LABELS[week.kind];

    headEl.append(titleEl, kindEl);
    li.append(headEl);

    const isPastOrCurrent = week.index <= activeIndex;
    const ridden = isPastOrCurrent ? weekKm(week, planRides) : 0;
    const pct = week.targetKm > 0 ? Math.min(100, (ridden / week.targetKm) * 100) : 0;

    const progressEl = document.createElement("div");
    progressEl.className = "week-progress";
    const progressBarEl = document.createElement("span");
    progressBarEl.style.width = `${pct}%`;
    progressEl.append(progressBarEl);
    li.append(progressEl);

    const progressLabelEl = document.createElement("div");
    progressLabelEl.className = "week-progress-label";
    progressLabelEl.textContent = isPastOrCurrent
      ? `${formatKm(ridden)} von ${week.targetKm} km`
      : `Ziel: ${week.targetKm} km`;
    li.append(progressLabelEl);

    const sessionsEl = document.createElement("ul");
    sessionsEl.className = "sessions";
    for (const session of week.sessions) {
      const sessionLi = document.createElement("li");

      const dayEl = document.createElement("span");
      dayEl.className = "session-day";
      dayEl.textContent = session.day;

      const bodyEl = document.createElement("span");
      const strongEl = document.createElement("strong");
      strongEl.textContent = session.title;
      bodyEl.append(strongEl, document.createTextNode(` – ${session.description}`));

      const kmEl = document.createElement("span");
      kmEl.className = "session-km";
      kmEl.textContent = `${session.targetKm} km`;

      sessionLi.append(dayEl, bodyEl, kmEl);
      sessionsEl.append(sessionLi);
    }
    li.append(sessionsEl);

    planWeeksEl.append(li);
  }

  planSectionEl.hidden = false;
}

async function renderTraining(): Promise<void> {
  const trainingRides = await listRides();
  const assessment = assessFitness(trainingRides);
  renderFitnessCard(assessment);

  const plan = loadPlan();
  if (plan) {
    goalNameEl.value = plan.goal.name;
    goalDistanceEl.value = String(plan.goal.distanceKm);
    goalAscentEl.value = plan.goal.ascentM === null ? "" : String(plan.goal.ascentM);
    goalDateEl.value = toDateInputValue(plan.goal.date);
    btnGoalDeleteEl.hidden = false;
    renderPlanWeeks(plan, trainingRides);
  } else {
    planSectionEl.hidden = true;
    btnGoalDeleteEl.hidden = true;
  }
}

function toggleTrainingPanel(): void {
  const wasHidden = togglePanelVisibility(trainingPanelEl);
  if (wasHidden) {
    void renderTraining();
  }
}

function readGoalForm(): Goal | null {
  const name = goalNameEl.value.trim();
  const distanceKm = Number(goalDistanceEl.value);
  const ascentRaw = goalAscentEl.value.trim();
  const dateValue = goalDateEl.value;

  if (!name) {
    goalStatusEl.textContent = "Bitte einen Namen für das Ziel angeben.";
    return null;
  }
  if (!Number.isFinite(distanceKm) || distanceKm <= 0) {
    goalStatusEl.textContent = "Bitte eine gültige Distanz angeben.";
    return null;
  }
  if (!dateValue) {
    goalStatusEl.textContent = "Bitte ein Zieldatum angeben.";
    return null;
  }

  const ascentM = ascentRaw && Number.isFinite(Number(ascentRaw)) ? Number(ascentRaw) : null;
  const date = new Date(`${dateValue}T12:00:00`).getTime();

  return { name, distanceKm, ascentM, date };
}

async function handleGoalSubmit(event: Event): Promise<void> {
  event.preventDefault();
  goalStatusEl.textContent = "";

  const goal = readGoalForm();
  if (!goal) {
    return;
  }

  const goalRides = await listRides();
  const assessment = assessFitness(goalRides);

  try {
    const plan = generatePlan(goal, assessment);
    savePlan(plan);
    goalStatusEl.textContent = `Plan mit ${plan.weeks.length} Wochen erstellt.`;
    btnGoalDeleteEl.hidden = false;
    renderPlanWeeks(plan, goalRides);
  } catch (error) {
    goalStatusEl.textContent = errorMessage(error);
  }
}

function deleteGoalPlan(): void {
  if (!confirm("Trainingsplan wirklich löschen?")) {
    return;
  }

  savePlan(null);
  goalStatusEl.textContent = "";
  planSectionEl.hidden = true;
  btnGoalDeleteEl.hidden = true;
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

/* -------------------------------------------------------------------- Offline */

async function refreshOfflineStatus(): Promise<void> {
  const count = await cachedTileCount();
  offlineStatusEl.textContent = `${count} Kacheln gespeichert`;
}

async function saveVisibleRegion(): Promise<void> {
  const map = getMap();
  if (!map) {
    return;
  }

  const bounds = map.getBounds();
  const region = {
    north: bounds.getNorth(),
    south: bounds.getSouth(),
    east: bounds.getEast(),
    west: bounds.getWest(),
  };
  const zoom = map.getZoom();
  const minZoom = zoom;
  const maxZoom = Math.min(zoom + 2, 17);

  btnOfflineSaveEl.disabled = true;
  try {
    const result = await downloadRegion(region, minZoom, maxZoom, (p) => {
      offlineStatusEl.textContent = `Lade Kacheln … ${p.done}/${p.total}`;
    });
    offlineStatusEl.textContent = `${result.downloaded} neu, ${result.skipped} vorhanden, ${result.failed} Fehler`;
    setTimeout(() => {
      void refreshOfflineStatus();
    }, 2500);
  } catch (error) {
    offlineStatusEl.textContent = errorMessage(error);
  } finally {
    btnOfflineSaveEl.disabled = false;
  }
}

async function clearOfflineTiles(): Promise<void> {
  if (!confirm("Alle gespeicherten Kartenkacheln löschen?")) {
    return;
  }

  await clearTileCache();
  await refreshOfflineStatus();
}

/* ----------------------------------------------------------------- Sync */

function loadSyncConfig(): void {
  const config = getSyncConfig();
  if (config) {
    syncUrlEl.value = config.url;
    syncTokenEl.value = config.token;
  }
}

async function runSync(): Promise<void> {
  const url = syncUrlEl.value.trim();
  const token = syncTokenEl.value.trim();

  if (!url || !token) {
    syncStatusEl.textContent = "Bitte Server-URL und Token eintragen.";
    return;
  }

  setSyncConfig({ url, token });
  btnSyncEl.disabled = true;
  syncStatusEl.textContent = "Synchronisiere …";

  try {
    const result = await syncRides();
    syncStatusEl.textContent = `✓ ${result.pushed} hochgeladen, ${result.pulled} geladen, ${result.total} Touren`;
    await refreshRideList();
  } catch (error) {
    syncStatusEl.textContent = errorMessage(error);
  } finally {
    btnSyncEl.disabled = false;
  }
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

  btnPlanEl.addEventListener("click", togglePlanning);
  planProfileEl.addEventListener("change", () => {
    planner?.setProfile(planProfileEl.value as RoutingProfile);
  });
  btnPlanUndoEl.addEventListener("click", () => {
    planner?.undo();
  });
  btnPlanClearEl.addEventListener("click", () => {
    planner?.clear();
  });
  btnPlanSaveEl.addEventListener("click", () => {
    void savePlannedRoute();
  });
  btnPlanExportEl.addEventListener("click", exportPlannedRoute);

  btnOfflineSaveEl.addEventListener("click", () => {
    void saveVisibleRegion();
  });
  btnOfflineClearEl.addEventListener("click", () => {
    void clearOfflineTiles();
  });
  void refreshOfflineStatus();

  loadSyncConfig();
  btnSyncEl.addEventListener("click", () => {
    void runSync();
  });

  btnTrainingEl.addEventListener("click", toggleTrainingPanel);
  btnTrainingCloseEl.addEventListener("click", () => {
    trainingPanelEl.hidden = true;
  });
  goalFormEl.addEventListener("submit", (event) => {
    void handleGoalSubmit(event);
  });
  btnGoalDeleteEl.addEventListener("click", deleteGoalPlan);

  registerServiceWorker();

  void refreshRideList().catch((error: unknown) => {
    alert(errorMessage(error));
  });
}

init();
