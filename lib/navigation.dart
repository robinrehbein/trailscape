/// Navigation entlang einer festen Route.
///
/// 1:1-Portierung der getesteten Web-Referenz (`web/src/navigation.ts`):
/// Die Position wird auf die Route projiziert und liefert Fortschritt sowie
/// Abweichung inklusive Hysterese für den Off-Route-Zustand.
library;

import 'dart:math' as math;

import 'models.dart';
import 'stats.dart';

/// Meter pro Breitengrad (äquirektanguläre Näherung).
const double _mPerDegLat = 111320;

/// Halbe Fensterbreite in Segmenten für die lokale Suche.
const int _searchWindowSegments = 50;

/// Ab diesem Fenster-Abstand wird einmalig global gesucht.
const double _globalSearchThresholdM = 200;

/// Abstand, ab dem die Position als "abseits" gilt.
const double _offRouteEnterM = 60;

/// Abstand, ab dem die Position wieder als "auf Route" gilt.
const double _offRouteExitM = 35;

/// Wie lange der Abstand durchgehend zu groß sein muss.
const int _offRouteDelayMs = 5000;

double _toRad(double deg) => deg * math.pi / 180;

/// Ergebnis der Projektion auf ein Routensegment.
class _Projection {
  const _Projection({
    required this.segmentIndex,
    required this.t,
    required this.distanceM,
  });

  final int segmentIndex;

  /// Parameter auf dem Segment, 0 = Anfang, 1 = Ende.
  final double t;
  final double distanceM;
}

/// Navigation entlang einer festen Route: projiziert die aktuelle Position auf
/// die Route und liefert Fortschritt sowie Abweichung.
///
/// Die Projektion rechnet lokal in einer äquirektangulären Näherung (Meter pro
/// Grad), was für Abstände von wenigen Kilometern ausreichend genau und
/// deutlich schneller als Haversine pro Segment ist. Die Distanzen entlang der
/// Route stammen dagegen aus der exakten Haversine-Vorberechnung.
class RouteNavigator {
  factory RouteNavigator(List<TrackPoint> route) =>
      RouteNavigator._(route, _buildCumulative(route));

  RouteNavigator._(this._route, List<double> cumulativeM)
      : _cumulativeM = cumulativeM,
        _totalM = cumulativeM.last;

  static List<double> _buildCumulative(List<TrackPoint> route) {
    if (route.length < 2) {
      throw ArgumentError('Route benötigt mindestens 2 Punkte.');
    }

    final cumulativeM = List<double>.filled(route.length, 0);
    for (var i = 1; i < route.length; i++) {
      cumulativeM[i] = cumulativeM[i - 1] + haversineM(route[i - 1], route[i]);
    }
    return cumulativeM;
  }

  final List<TrackPoint> _route;

  /// Kumulative Distanz in Metern bis zum jeweiligen Routenpunkt.
  final List<double> _cumulativeM;
  final double _totalM;

  /// Zuletzt getroffenes Segment, Startpunkt der gefensterten Suche.
  int _lastSegmentIndex = 0;
  bool _offRouteState = false;

  /// Zeitpunkt, seit dem der Abstand durchgehend zu groß ist.
  int? _farSinceMs;

  /// Gesamtlänge der Route in Kilometern.
  double get totalKm => _totalM / 1000;

  /// Aktualisiert den Navigationszustand für die aktuelle Position.
  NavState update({required double lat, required double lon, int? now}) {
    final nowMs = now ?? DateTime.now().millisecondsSinceEpoch;
    final mPerDegLon = _mPerDegLat * math.cos(_toRad(lat));
    final lastSegment = _route.length - 2;

    final from = math.max(0, _lastSegmentIndex - _searchWindowSegments);
    final to =
        math.min(lastSegment, _lastSegmentIndex + _searchWindowSegments);

    var best = _searchRange(lat, lon, mPerDegLon, from, to);

    // Der Nutzer könnte die Route weit verlassen haben oder gesprungen sein:
    // dann lohnt sich eine einmalige globale Suche.
    if (best.distanceM > _globalSearchThresholdM &&
        (from > 0 || to < lastSegment)) {
      final global = _searchRange(lat, lon, mPerDegLon, 0, lastSegment);
      if (global.distanceM < best.distanceM) {
        best = global;
      }
    }

    _lastSegmentIndex = best.segmentIndex;

    final segmentStartM = _cumulativeM[best.segmentIndex];
    final segmentLengthM = _cumulativeM[best.segmentIndex + 1] - segmentStartM;
    final doneM = math.min(_totalM, segmentStartM + best.t * segmentLengthM);
    final remainingM = math.max(0.0, _totalM - doneM);

    return NavState(
      nearestIndex:
          best.t <= 0.5 ? best.segmentIndex : best.segmentIndex + 1,
      distanceToRouteM: best.distanceM,
      doneKm: doneM / 1000,
      remainingKm: remainingM / 1000,
      offRoute: _updateOffRoute(best.distanceM, nowMs),
    );
  }

  /// Bestes Segment im Indexbereich [from, to] (jeweils einschließlich).
  _Projection _searchRange(
    double lat,
    double lon,
    double mPerDegLon,
    int from,
    int to,
  ) {
    var bestIndex = from;
    var bestT = 0.0;
    var bestDistanceM = double.infinity;

    for (var i = from; i <= to; i++) {
      final a = _route[i];
      final b = _route[i + 1];

      // Lokales Meter-Koordinatensystem mit der Position im Ursprung.
      final ax = (a.lon - lon) * mPerDegLon;
      final ay = (a.lat - lat) * _mPerDegLat;
      final bx = (b.lon - lon) * mPerDegLon;
      final by = (b.lat - lat) * _mPerDegLat;

      final dx = bx - ax;
      final dy = by - ay;
      final lengthSq = dx * dx + dy * dy;

      var t = 0.0;
      if (lengthSq > 0) {
        t = (-ax * dx - ay * dy) / lengthSq;
        t = t < 0
            ? 0
            : t > 1
                ? 1
                : t;
      }

      final px = ax + t * dx;
      final py = ay + t * dy;
      final distanceM = math.sqrt(px * px + py * py);

      if (distanceM < bestDistanceM) {
        bestDistanceM = distanceM;
        bestIndex = i;
        bestT = t;
      }
    }

    return _Projection(
      segmentIndex: bestIndex,
      t: bestT,
      distanceM: bestDistanceM,
    );
  }

  /// Hysterese: abseits erst, wenn der Abstand seit mindestens 5 Sekunden
  /// durchgehend über 60 m liegt; zurück auf der Route, sobald er einmal
  /// unter 35 m fällt. Dazwischen bleibt der Zustand unverändert.
  bool _updateOffRoute(double distanceM, int now) {
    if (distanceM < _offRouteExitM) {
      _farSinceMs = null;
      _offRouteState = false;
      return _offRouteState;
    }

    if (distanceM > _offRouteEnterM) {
      final farSince = _farSinceMs;
      if (farSince == null) {
        _farSinceMs = now;
      } else if (now - farSince >= _offRouteDelayMs) {
        _offRouteState = true;
      }
      return _offRouteState;
    }

    // 35 m ≤ Abstand ≤ 60 m: Zustand halten, Zähler zurücksetzen.
    _farSinceMs = null;
    return _offRouteState;
  }
}
