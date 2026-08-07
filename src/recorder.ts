import type { TrackPoint } from "./types";

const MAX_ACCURACY_M = 50;

export class Recorder {
  private watchId: number | null = null;
  private collectedPoints: TrackPoint[] = [];

  get isRecording(): boolean {
    return this.watchId !== null;
  }

  get points(): TrackPoint[] {
    return this.collectedPoints.slice();
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

  stop(): TrackPoint[] {
    if (this.watchId !== null) {
      navigator.geolocation.clearWatch(this.watchId);
      this.watchId = null;
    }
    return this.collectedPoints.slice();
  }

  private handlePosition(
    position: GeolocationPosition,
    onPoint: (point: TrackPoint, all: TrackPoint[]) => void
  ): void {
    const { coords } = position;

    if (coords.accuracy > MAX_ACCURACY_M) {
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
