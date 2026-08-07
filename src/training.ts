import type { Ride } from "./types";
import type { FitnessAssessment, FitnessLevel } from "./fitness";

export interface Goal {
  name: string;
  distanceKm: number;
  /** Höhenmeter des Ziels, null wenn unbekannt */
  ascentM: number | null;
  /** Zeitstempel des Zieltermins in ms seit Epoch */
  date: number;
}

export interface TrainingSession {
  /** Deutscher Wochentag, z. B. "Di" */
  day: string;
  title: string;
  description: string;
  targetKm: number;
}

export type WeekKind = "aufbau" | "erholung" | "taper" | "zielwoche";

export interface TrainingWeek {
  index: number;
  /** Montag 00:00 lokaler Zeit */
  start: number;
  /** Montag der Folgewoche 00:00 lokaler Zeit (exklusiv) */
  end: number;
  kind: WeekKind;
  targetKm: number;
  sessions: TrainingSession[];
}

export interface TrainingPlan {
  createdAt: number;
  goal: Goal;
  level: FitnessLevel;
  weeks: TrainingWeek[];
}

export const WEEK_KIND_LABELS: Record<WeekKind, string> = {
  aufbau: "Aufbau",
  erholung: "Erholung",
  taper: "Taper",
  zielwoche: "Zielwoche",
};

const STORAGE_KEY = "trailscape.plan";

const WEEKDAYS = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"] as const;

const MIN_WEEKS = 3;
const MAX_WEEKS = 52;

const ERROR_TOO_SOON = "Das Ziel liegt zu nah in der Zukunft – plane mindestens 3 Wochen ein.";
const ERROR_TOO_FAR = "Das Ziel liegt mehr als ein Jahr entfernt.";

/** Basisvolumen pro Woche in km, falls die bisherige Belastung darunter liegt */
const LEVEL_BASE_KM: Record<FitnessLevel, number> = {
  einsteiger: 40,
  fortgeschritten: 70,
  ambitioniert: 110,
};

const RECOVERY_FACTOR = 0.6;
const TAPER_FACTOR = 0.5;
const PEAK_DISTANCE_FACTOR = 1.3;
const PEAK_CAP_FACTOR = 2.2;
const ACTIVATION_KM = 15;
const CLIMBING_HINT = " Baue dabei bewusst Anstiege ein, um dich an die Höhenmeter des Ziels zu gewöhnen.";
const CLIMB_HINT_THRESHOLD_M = 1000;

/** Montag 00:00 lokaler Zeit der Woche, in der `timestamp` liegt */
function startOfWeek(timestamp: number): number {
  const date = new Date(timestamp);
  date.setHours(0, 0, 0, 0);
  // getDay(): 0 = Sonntag … 6 = Samstag
  const offset = (date.getDay() + 6) % 7;
  date.setDate(date.getDate() - offset);
  return date.getTime();
}

/** Addiert `weeks` Wochen und bleibt dabei DST-sicher auf 00:00 lokaler Zeit */
function addWeeks(timestamp: number, weeks: number): number {
  const date = new Date(timestamp);
  date.setDate(date.getDate() + weeks * 7);
  date.setHours(0, 0, 0, 0);
  return date.getTime();
}

/** Index des Wochentags (0 = Mo … 6 = So) */
function weekdayIndex(timestamp: number): number {
  return (new Date(timestamp).getDay() + 6) % 7;
}

function round5(km: number): number {
  return Math.max(5, Math.round(km / 5) * 5);
}

function round1(value: number): number {
  return Math.round(value * 10) / 10;
}

function sessionKm(weekKm: number, share: number): number {
  return Math.max(1, Math.round(weekKm * share));
}

function longTourDescription(goal: Goal): string {
  const base =
    "Die Schlüsseleinheit der Woche: gleichmäßig im Grundlagentempo fahren und konsequent essen und trinken.";
  if (goal.ascentM !== null && goal.ascentM >= CLIMB_HINT_THRESHOLD_M) {
    return base + CLIMBING_HINT;
  }
  return base;
}

function buildSessions(
  kind: WeekKind,
  level: FitnessLevel,
  targetKm: number,
  goal: Goal,
): TrainingSession[] {
  if (kind === "zielwoche") {
    return zielwocheSessions(goal);
  }

  if (kind === "erholung") {
    return [
      {
        day: "Di",
        title: "Lockere Ausfahrt",
        description:
          "Entspannt rollen, kleine Gänge und hohe Trittfrequenz – diese Woche dient ausschließlich der Erholung.",
        targetKm: sessionKm(targetKm, 0.5),
      },
      {
        day: "Sa",
        title: "Ruhige Runde",
        description:
          "Gemütliche Ausfahrt ohne Leistungsdruck, halte den Puls durchgehend im niedrigen Bereich.",
        targetKm: sessionKm(targetKm, 0.5),
      },
    ];
  }

  if (kind === "taper") {
    return [
      {
        day: "Di",
        title: "Locker mit Antritten",
        description:
          "Locker rollen und dabei 3 kurze Antritte über je 30 Sekunden einstreuen, um spritzig zu bleiben.",
        targetKm: sessionKm(targetKm, 0.55),
      },
      {
        day: "Do",
        title: "Kurze lockere Ausfahrt",
        description:
          "Kurz und ruhig fahren, danach Material checken und die Beine bewusst schonen.",
        targetKm: sessionKm(targetKm, 0.45),
      },
    ];
  }

  return aufbauSessions(level, targetKm, goal);
}

function aufbauSessions(level: FitnessLevel, targetKm: number, goal: Goal): TrainingSession[] {
  if (level === "einsteiger") {
    const withRecovery = targetKm >= 60;
    const sessions: TrainingSession[] = [
      {
        day: "Di",
        title: "Lockere Ausfahrt GA1",
        description:
          "Ruhiges Grundlagentempo – du solltest dich während der gesamten Fahrt unterhalten können.",
        targetKm: sessionKm(targetKm, withRecovery ? 0.3 : 0.4),
      },
      {
        day: "Sa",
        title: "Lange Tour",
        description: longTourDescription(goal),
        targetKm: sessionKm(targetKm, withRecovery ? 0.5 : 0.6),
      },
    ];
    if (withRecovery) {
      sessions.push({
        day: "So",
        title: "Regeneration locker",
        description:
          "Kurze Regenerationsrunde im leichten Gang, bewusst niedrige Intensität für frische Beine.",
        targetKm: sessionKm(targetKm, 0.2),
      });
    }
    return sessions;
  }

  if (level === "fortgeschritten") {
    return [
      {
        day: "Di",
        title: "GA1",
        description:
          "Lockere Grundlageneinheit zum Auffüllen des Wochenvolumens, Puls konstant im GA1-Bereich halten.",
        targetKm: sessionKm(targetKm, 0.25),
      },
      {
        day: "Do",
        title: "Intervalle",
        description:
          "Nach 20 Minuten Einfahren 4×8 Minuten zügig im Schwellenbereich, dazwischen 4 Minuten locker rollen.",
        targetKm: sessionKm(targetKm, 0.2),
      },
      {
        day: "Sa",
        title: "Lange Tour",
        description: longTourDescription(goal),
        targetKm: sessionKm(targetKm, 0.55),
      },
    ];
  }

  return [
    {
      day: "Di",
      title: "GA1",
      description:
        "Ruhige Grundlageneinheit, gleichmäßige Belastung ohne Spitzen und ohne Sprints.",
      targetKm: sessionKm(targetKm, 0.2),
    },
    {
      day: "Mi",
      title: "Intervalle",
      description:
        "Nach dem Einfahren 5×6 Minuten hart an der Schwelle mit je 3 Minuten lockerer Pause dazwischen.",
      targetKm: sessionKm(targetKm, 0.2),
    },
    {
      day: "Sa",
      title: "Lange Tour",
      description: longTourDescription(goal),
      targetKm: sessionKm(targetKm, 0.45),
    },
    {
      day: "So",
      title: "GA1 kompensatorisch",
      description:
        "Kompensationsrunde mit hoher Trittfrequenz, um die Beine nach der langen Tour wieder locker zu fahren.",
      targetKm: sessionKm(targetKm, 0.15),
    },
  ];
}

function zielwocheSessions(goal: Goal): TrainingSession[] {
  const eventIndex = weekdayIndex(goal.date);
  const eventDay = WEEKDAYS[eventIndex];
  const eventKm = Math.max(1, Math.round(goal.distanceKm));

  const eventSession: TrainingSession = {
    day: eventDay,
    title: `Zielevent: ${goal.name}`,
    description:
      goal.ascentM !== null && goal.ascentM >= CLIMB_HINT_THRESHOLD_M
        ? `Dein Zielevent über ${eventKm} km und rund ${Math.round(goal.ascentM)} Hm – teile dir die Kraft an den Anstiegen ein und trinke von Beginn an regelmäßig.`
        : `Dein Zielevent über ${eventKm} km – starte kontrolliert, halte dein Tempo und versorge dich unterwegs konsequent.`,
    targetKm: eventKm,
  };

  const activationDay = eventIndex > 1 ? "Di" : eventIndex === 1 ? "Mo" : null;
  if (activationDay === null) {
    return [eventSession];
  }

  return [
    {
      day: activationDay,
      title: "Aktivierung locker",
      description:
        "Kurze lockere Runde mit ein paar Antritten, danach Rad und Verpflegung für den Zieltag vorbereiten.",
      targetKm: ACTIVATION_KM,
    },
    eventSession,
  ];
}

function planWeekKinds(weekCount: number): WeekKind[] {
  const kinds: WeekKind[] = [];
  const lastBuildIndex = weekCount - 3;

  for (let i = 0; i < weekCount; i += 1) {
    if (i === weekCount - 1) {
      kinds.push("zielwoche");
    } else if (i === weekCount - 2) {
      kinds.push("taper");
    } else if (i % 4 === 3 && i !== lastBuildIndex) {
      // Jede 4. Woche ist Erholung – außer sie wäre die letzte Aufbauwoche vor dem Taper.
      kinds.push("erholung");
    } else {
      kinds.push("aufbau");
    }
  }

  return kinds;
}

export function generatePlan(
  goal: Goal,
  assessment: FitnessAssessment,
  now: number = Date.now(),
): TrainingPlan {
  const firstMonday = startOfWeek(now);
  const goalMonday = startOfWeek(goal.date);
  const weekCount = Math.round((goalMonday - firstMonday) / (7 * 24 * 60 * 60 * 1000)) + 1;

  if (weekCount < MIN_WEEKS) {
    throw new Error(ERROR_TOO_SOON);
  }
  if (weekCount > MAX_WEEKS) {
    throw new Error(ERROR_TOO_FAR);
  }

  const level = assessment.level;
  const startKm = Math.max(assessment.weeklyKm, LEVEL_BASE_KM[level]);
  const peakKm = Math.min(
    Math.max(goal.distanceKm * PEAK_DISTANCE_FACTOR, startKm),
    startKm * PEAK_CAP_FACTOR,
  );

  const kinds = planWeekKinds(weekCount);
  const buildCount = kinds.filter((kind) => kind === "aufbau").length;

  const weeks: TrainingWeek[] = [];
  let buildSeen = 0;
  let previousKm = startKm;

  for (let i = 0; i < weekCount; i += 1) {
    const kind = kinds[i];
    let targetKm: number;

    if (kind === "aufbau") {
      const progress = buildCount > 1 ? buildSeen / (buildCount - 1) : 1;
      targetKm = round5(startKm + (peakKm - startKm) * progress);
      buildSeen += 1;
    } else if (kind === "erholung") {
      targetKm = round5(previousKm * RECOVERY_FACTOR);
    } else if (kind === "taper") {
      targetKm = round5(peakKm * TAPER_FACTOR);
    } else {
      const sessions = zielwocheSessions(goal);
      targetKm = sessions.reduce((sum, session) => sum + session.targetKm, 0);
    }

    previousKm = targetKm;

    const start = addWeeks(firstMonday, i);
    weeks.push({
      index: i,
      start,
      end: addWeeks(firstMonday, i + 1),
      kind,
      targetKm,
      sessions: buildSessions(kind, level, targetKm, goal),
    });
  }

  return {
    createdAt: now,
    goal,
    level,
    weeks,
  };
}

export function loadPlan(): TrainingPlan | null {
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
  if (raw === null) {
    return null;
  }

  try {
    const parsed = JSON.parse(raw) as TrainingPlan | null;
    if (parsed === null || typeof parsed !== "object" || !Array.isArray(parsed.weeks)) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

export function savePlan(plan: TrainingPlan | null): void {
  try {
    if (plan === null) {
      localStorage.removeItem(STORAGE_KEY);
      return;
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(plan));
  } catch {
    // Speicher nicht verfügbar (z. B. privater Modus) – Plan bleibt nur im Speicher.
  }
}

export function currentWeekIndex(plan: TrainingPlan, now: number = Date.now()): number {
  const weeks = plan.weeks;
  if (weeks.length === 0) {
    return -1;
  }
  if (now < weeks[0].start) {
    return -1;
  }

  for (const week of weeks) {
    if (now >= week.start && now < week.end) {
      return week.index;
    }
  }

  return weeks.length - 1;
}

export function weekKm(week: TrainingWeek, rides: Ride[]): number {
  let total = 0;
  for (const ride of rides) {
    if (ride.createdAt >= week.start && ride.createdAt < week.end) {
      total += ride.stats.distanceKm;
    }
  }
  return round1(total);
}
