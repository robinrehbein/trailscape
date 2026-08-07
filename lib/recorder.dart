/// Aufzeichnung einer Tour über GPS.
///
/// Semantisch an die Web-App-Referenz (recorder.ts) angelehnt, dort auf
/// Basis der Browser-Geolocation-API. Auf Android läuft die Aufzeichnung
/// als Foreground-Service (über [AndroidSettings.foregroundNotificationConfig])
/// weiter, auch wenn das Display gesperrt ist.
library;

import 'dart:async';

import 'package:geolocator/geolocator.dart';

import 'models.dart';
import 'stats.dart';

const double _maxAccuracyM = 50;
const double _maxSpeedFallbackIntervalS = 10;

/// Zeichnet eine Tour über den Standort des Geräts auf.
class Recorder {
  StreamSubscription<Position>? _subscription;
  List<TrackPoint> _collectedPoints = [];
  bool _paused = false;
  int? _pauseStartedAt;
  int _pausedMsAccum = 0;
  int? _startedAtValue;
  double? _lastKnownSpeedKmh;

  /// Ob gerade eine Aufzeichnung läuft (unabhängig von Pause).
  bool get isRecording => _subscription != null;

  /// Ob die laufende Aufzeichnung aktuell pausiert ist.
  bool get isPaused => _paused;

  /// Startzeitpunkt der Aufzeichnung in ms seit Epoch, `null` wenn nicht
  /// aktiv.
  int? get startedAt => _startedAtValue;

  /// Insgesamt in Pausen verbrachte Zeit in ms, inklusive der gerade
  /// laufenden Pause.
  int get pausedMs {
    if (_paused && _pauseStartedAt != null) {
      return _pausedMsAccum +
          (DateTime.now().millisecondsSinceEpoch - _pauseStartedAt!);
    }
    return _pausedMsAccum;
  }

  /// Aktuelle Geschwindigkeit in km/h, oder `null` wenn nicht ermittelbar
  /// bzw. nach [stop].
  double? get currentSpeedKmh {
    if (!isRecording) {
      return null;
    }

    if (_lastKnownSpeedKmh != null) {
      return _lastKnownSpeedKmh;
    }

    if (_collectedPoints.length < 2) {
      return null;
    }

    final last = _collectedPoints[_collectedPoints.length - 1];
    final prev = _collectedPoints[_collectedPoints.length - 2];

    if (last.time != null && prev.time != null) {
      final dtS = (last.time! - prev.time!) / 1000;
      if (dtS > 0 && dtS < _maxSpeedFallbackIntervalS) {
        final distanceKm = haversineM(prev, last) / 1000;
        return distanceKm / (dtS / 3600);
      }
    }

    return null;
  }

  /// Kopie der bisher aufgezeichneten Trackpunkte.
  List<TrackPoint> get points => List.unmodifiable(_collectedPoints);

  /// Startet die Aufzeichnung. Klärt zunächst Standortdienst und
  /// Berechtigungen; bei fehlender Zustimmung wird [onError] mit einer
  /// verständlichen deutschen Meldung aufgerufen und der Start abgebrochen.
  ///
  /// Läuft auf Android als Foreground-Service mit persistenter Notification,
  /// damit die Aufzeichnung auch bei gesperrtem Display weiterläuft.
  Future<void> start({
    required void Function(TrackPoint point, List<TrackPoint> all) onPoint,
    required void Function(String message) onError,
  }) async {
    if (isRecording) {
      onError('Es läuft bereits eine Aufzeichnung.');
      return;
    }

    final serviceEnabled = await Geolocator.isLocationServiceEnabled();
    if (!serviceEnabled) {
      onError(
        'Der Standortdienst ist deaktiviert. Bitte aktiviere ihn in den Geräteeinstellungen.',
      );
      return;
    }

    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
      if (permission == LocationPermission.denied) {
        onError(
          'Standortzugriff wurde verweigert. Bitte erlaube den Zugriff auf deinen Standort.',
        );
        return;
      }
    }

    if (permission == LocationPermission.deniedForever) {
      onError(
        'Standortzugriff wurde dauerhaft verweigert. Bitte erlaube den Zugriff in den App-Einstellungen.',
      );
      return;
    }

    _collectedPoints = [];
    _paused = false;
    _pauseStartedAt = null;
    _pausedMsAccum = 0;
    _startedAtValue = DateTime.now().millisecondsSinceEpoch;
    _lastKnownSpeedKmh = null;

    final locationSettings = AndroidSettings(
      accuracy: LocationAccuracy.best,
      distanceFilter: 3,
      foregroundNotificationConfig: const ForegroundNotificationConfig(
        notificationTitle: 'Trailscape zeichnet auf',
        notificationText: 'Deine Tour wird aufgezeichnet.',
        setOngoing: true,
        enableWakeLock: true,
      ),
    );

    _subscription = Geolocator.getPositionStream(
      locationSettings: locationSettings,
    ).listen(
      (position) => _handlePosition(position, onPoint),
      onError: (Object error) => _handleError(error, onError),
    );
  }

  /// Pausiert die laufende Aufzeichnung. Ohne Wirkung, wenn nicht
  /// aufgezeichnet wird oder bereits pausiert ist.
  void pause() {
    if (!isRecording || _paused) {
      return;
    }
    _paused = true;
    _pauseStartedAt = DateTime.now().millisecondsSinceEpoch;
  }

  /// Setzt eine pausierte Aufzeichnung fort. Ohne Wirkung, wenn nicht
  /// pausiert.
  void resume() {
    if (!_paused) {
      return;
    }
    if (_pauseStartedAt != null) {
      _pausedMsAccum += DateTime.now().millisecondsSinceEpoch - _pauseStartedAt!;
    }
    _paused = false;
    _pauseStartedAt = null;
  }

  /// Beendet die Aufzeichnung und liefert die gesammelten Trackpunkte.
  List<TrackPoint> stop() {
    _subscription?.cancel();
    _subscription = null;
    final points = List<TrackPoint>.from(_collectedPoints);
    _startedAtValue = null;
    _paused = false;
    _pauseStartedAt = null;
    _lastKnownSpeedKmh = null;
    return points;
  }

  void _handlePosition(
    Position position,
    void Function(TrackPoint point, List<TrackPoint> all) onPoint,
  ) {
    if (position.speed >= 0) {
      _lastKnownSpeedKmh = position.speed * 3.6;
    }

    if (position.accuracy > _maxAccuracyM) {
      return;
    }

    if (_paused) {
      return;
    }

    final lastPoint =
        _collectedPoints.isNotEmpty ? _collectedPoints.last : null;
    if (lastPoint != null &&
        lastPoint.lat == position.latitude &&
        lastPoint.lon == position.longitude) {
      return;
    }

    final point = TrackPoint(
      lat: position.latitude,
      lon: position.longitude,
      ele: position.altitude.isFinite ? position.altitude : null,
      time: position.timestamp.millisecondsSinceEpoch,
    );

    _collectedPoints.add(point);
    onPoint(point, List<TrackPoint>.from(_collectedPoints));
  }

  void _handleError(Object error, void Function(String message) onError) {
    String message;
    var shouldStop = false;

    if (error is LocationServiceDisabledException) {
      message = 'Der Standortdienst wurde deaktiviert.';
      shouldStop = true;
    } else if (error is PermissionDeniedException) {
      message =
          'Standortzugriff wurde verweigert. Bitte erlaube den Zugriff auf deinen Standort.';
      shouldStop = true;
    } else if (error is TimeoutException) {
      message = 'Zeitüberschreitung bei der Positionsbestimmung.';
    } else {
      message = 'Unbekannter Fehler bei der Standortbestimmung.';
    }

    onError(message);

    if (shouldStop) {
      stop();
    }
  }
}
