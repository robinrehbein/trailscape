import type { Ride } from "./types";

export type FitnessLevel = "einsteiger" | "fortgeschritten" | "ambitioniert";

export interface FitnessAssessment {
  level: FitnessLevel;
  weeklyKm: number;
  weeklyHm: number;
  weeklyRides: number;
  longestRideKm: number;
  rideCount: number;
}

export const LEVEL_LABELS: Record<FitnessLevel, string> = {
  einsteiger: "Einsteiger",
  fortgeschritten: "Fortgeschritten",
  ambitioniert: "Ambitioniert",
};

const WINDOW_WEEKS = 8;
const WINDOW_MS = WINDOW_WEEKS * 7 * 24 * 60 * 60 * 1000;

function round1(value: number): number {
  return Math.round(value * 10) / 10;
}

function determineLevel(
  weeklyKm: number,
  longestRideKm: number,
  weeklyRides: number,
): FitnessLevel {
  if (weeklyKm >= 100 && longestRideKm >= 70 && weeklyRides >= 2.5) {
    return "ambitioniert";
  }
  if (weeklyKm >= 50 && longestRideKm >= 35 && weeklyRides >= 1.5) {
    return "fortgeschritten";
  }
  return "einsteiger";
}

export function assessFitness(rides: Ride[], now: number = Date.now()): FitnessAssessment {
  const cutoff = now - WINDOW_MS;
  const relevantRides = rides.filter(
    (ride) => ride.createdAt >= cutoff && ride.createdAt <= now && ride.stats.distanceKm > 0,
  );

  const rideCount = relevantRides.length;

  if (rideCount === 0) {
    return {
      level: "einsteiger",
      weeklyKm: 0,
      weeklyHm: 0,
      weeklyRides: 0,
      longestRideKm: 0,
      rideCount: 0,
    };
  }

  let totalKm = 0;
  let totalHm = 0;
  let longestRideKm = 0;

  for (const ride of relevantRides) {
    totalKm += ride.stats.distanceKm;
    totalHm += ride.stats.ascentM;
    if (ride.stats.distanceKm > longestRideKm) {
      longestRideKm = ride.stats.distanceKm;
    }
  }

  const weeklyKm = round1(totalKm / WINDOW_WEEKS);
  const weeklyHm = Math.round(totalHm / WINDOW_WEEKS);
  const weeklyRides = round1(rideCount / WINDOW_WEEKS);

  return {
    level: determineLevel(weeklyKm, longestRideKm, weeklyRides),
    weeklyKm,
    weeklyHm,
    weeklyRides,
    longestRideKm: round1(longestRideKm),
    rideCount,
  };
}
