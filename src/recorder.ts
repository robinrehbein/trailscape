import { haversineM } from "./stats";
import type { TrackPoint } from "./types";

const MAX_ACCURACY_M = 50;
const MAX_SPEED_FALLBACK_INTERVAL_S = 10;

export class Recorder {
  private watchId: number | null = null;
  private collectedPoints: TrackPoint[] = [];
  private paused = false;
  private pauseStartedAt: number | null = null;
  private pausedMsAccum = 0;
  private startedAtValue: number | null = null;
  private lastKnownSpeedKmh: number | null = null;

  get isRecording(): boolean {
    return this.watchId !== null;
  }

  get isPaused(): boolean {
    return this.paused;
  }

  get points(): TrackPoint[] {
    return this.collectedPoints.slice();
  }

  get startedAt(): number | null {
    return this.startedAtValue;
  }

  get pausedMs(): number {
    if (this.paused && this.pauseStartedAt !== null) {
      return this.pausedMsAccum + (Date.now() - this.pauseStartedAt);
    }
    return this.pausedMsAccum;
  }

  get currentSpeedKmh(): number | null {
    if (this.watchId === null) {
      return null;
    }

    if (this.lastKnownSpeedKmh !== null) {
      return this.lastKnownSpeedKmh;
    }

    const last = this.collectedPoints[this.collectedPoints.length - 1];
    const prev = this.collectedPoints[this.collectedPoints.length - 2];

    if (last && prev && last.time !== undefined && prev.time !== undefined) {
      const dtS = (last.time - prev.time) / 1000;
      if (dtS > 0 && dtS < MAX_SPEED_FALLBACK_INTERVAL_S) {
        const distanceKm = haversineM(prev, last) / 1000;
        return distanceKm / (dtS / 3600);
      }
    }

    return null;
  }

  start(
    onPoint: (point: TrackPoint, all: TrackPoint[]) => void,
    onError: (message: string) => void
  ): void {
    if (this.isRecording) {
      throw new Error("Es läuft bereits eine Aufzeichnung.");
    }

    if (!("geolocation" in navigator)) {
      throw new Error("Geolocation wird von diesem Gerät bzw. Browser nicht unterstützt.");
    }

    this.collectedPoints = [];
    this.paused = false;
    this.pauseStartedAt = null;
    this.pausedMsAccum = 0;
    this.startedAtValue = Date.now();
    this.lastKnownSpeedKmh = null;

    this.watchId = navigator.geolocation.watchPosition(
      (position) => this.handlePosition(position, onPoint),
      (error) => this.handleError(error, onError),
      {
        enableHighAccuracy: true,
        maximumAge: 0,
        timeout: 15000,
      }
    );
  }

  pause(): void {
    if (!this.isRecording || this.paused) {
      return;
    }
    this.paused = true;
    this.pauseStartedAt = Date.now();
  }

  resume(): void {
    if (!this.paused) {
      return;
    }
    if (this.pauseStartedAt !== null) {
      this.pausedMsAccum += Date.now() - this.pauseStartedAt;
    }
    this.paused = false;
    this.pauseStartedAt = null;
  }

  stop(): TrackPoint[] {
    if (this.watchId !== null) {
      navigator.geolocation.clearWatch(this.watchId);
      this.watchId = null;
    }
    const points = this.collectedPoints.slice();
    this.startedAtValue = null;
    this.paused = false;
    this.pauseStartedAt = null;
    this.lastKnownSpeedKmh = null;
    return points;
  }

  private handlePosition(
    position: GeolocationPosition,
    onPoint: (point: TrackPoint, all: TrackPoint[]) => void
  ): void {
    const { coords } = position;

    if (coords.speed !== null && coords.speed >= 0) {
      this.lastKnownSpeedKmh = coords.speed * 3.6;
    }

    if (coords.accuracy > MAX_ACCURACY_M) {
      return;
    }

    if (this.paused) {
      return;
    }

    const lastPoint = this.collectedPoints[this.collectedPoints.length - 1];
    if (lastPoint && lastPoint.lat === coords.latitude && lastPoint.lon === coords.longitude) {
      return;
    }

    const point: TrackPoint = {
      lat: coords.latitude,
      lon: coords.longitude,
      time: position.timestamp,
    };

    if (coords.altitude !== null) {
      point.ele = coords.altitude;
    }

    this.collectedPoints.push(point);
    onPoint(point, this.collectedPoints.slice());
  }

  private handleError(error: GeolocationPositionError, onError: (message: string) => void): void {
    let message: string;

    switch (error.code) {
      case error.PERMISSION_DENIED:
        message = "Standortzugriff wurde verweigert. Bitte erlaube den Zugriff auf deinen Standort.";
        break;
      case error.POSITION_UNAVAILABLE:
        message = "Position ist derzeit nicht verfügbar.";
        break;
      case error.TIMEOUT:
        message = "Zeitüberschreitung bei der Positionsbestimmung.";
        break;
      default:
        message = "Unbekannter Fehler bei der Standortbestimmung.";
        break;
    }

    onError(message);

    if (error.code === error.PERMISSION_DENIED) {
      this.stop();
    }
  }
}
