export interface TrackPoint {
  lat: number;
  lon: number;
  /** Höhe in Metern */
  ele?: number;
  /** Zeitstempel in ms seit Epoch */
  time?: number;
}

export interface RideStats {
  distanceKm: number;
  durationS: number | null;
  movingTimeS: number | null;
  avgSpeedKmh: number | null;
  ascentM: number;
  descentM: number;
}

export interface Ride {
  id: string;
  name: string;
  createdAt: number;
  points: TrackPoint[];
  stats: RideStats;
}
