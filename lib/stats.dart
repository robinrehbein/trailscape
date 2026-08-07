/// Fahrt-Statistiken: Distanz, Dauer, Geschwindigkeit, Höhenmeter.
///
/// Semantisch identisch zur Web-App-Referenz (stats.ts), damit Ride-Daten
/// zwischen Selfhost-Sync-Server, Web-App und dieser App konsistent bleiben.
library;

import 'dart:math' as math;

import 'models.dart';

const double _earthRadiusM = 6371000;
const double _movingSpeedThresholdKmh = 1;
const double _elevationHysteresisM = 3;

double _toRad(double deg) => deg * math.pi / 180;

/// Distanz zwischen zwei Punkten in Metern (Haversine-Formel).
double haversineM(TrackPoint a, TrackPoint b) {
  final dLat = _toRad(b.lat - a.lat);
  final dLon = _toRad(b.lon - a.lon);
  final lat1 = _toRad(a.lat);
  final lat2 = _toRad(b.lat);

  final h = math.pow(math.sin(dLat / 2), 2) +
      math.cos(lat1) * math.cos(lat2) * math.pow(math.sin(dLon / 2), 2);
  final c = 2 * math.atan2(math.sqrt(h), math.sqrt(1 - h));

  return _earthRadiusM * c;
}

const RideStats _emptyStats = RideStats(
  distanceKm: 0,
  durationS: null,
  movingTimeS: null,
  avgSpeedKmh: null,
  ascentM: 0,
  descentM: 0,
);

class _Elevation {
  const _Elevation(this.ascentM, this.descentM);

  final double ascentM;
  final double descentM;
}

_Elevation _computeElevation(List<TrackPoint> points) {
  final withEle = points.where((p) => p.ele != null).toList();

  if (withEle.length < 2) {
    return const _Elevation(0, 0);
  }

  var ascentM = 0.0;
  var descentM = 0.0;
  var referenceEle = withEle[0].ele!;

  for (var i = 1; i < withEle.length; i++) {
    final diff = withEle[i].ele! - referenceEle;

    if (diff.abs() >= _elevationHysteresisM) {
      if (diff > 0) {
        ascentM += diff;
      } else {
        descentM += -diff;
      }
      referenceEle = withEle[i].ele!;
    }
  }

  return _Elevation(ascentM, descentM);
}

/// Berechnet Fahrt-Statistiken aus einer Liste von Trackpunkten.
/// Höhenmeter werden mit einer Hysterese-Schwelle von 3 m geglättet,
/// um GPS-Rauschen nicht als Anstieg/Abstieg zu zählen.
RideStats computeStats(List<TrackPoint> points) {
  if (points.length < 2) {
    return _emptyStats;
  }

  var distanceM = 0.0;
  var movingTimeS = 0.0;
  var hasMovingTimeData = false;

  for (var i = 1; i < points.length; i++) {
    final prev = points[i - 1];
    final curr = points[i];
    final segmentM = haversineM(prev, curr);
    distanceM += segmentM;

    if (prev.time != null && curr.time != null) {
      final dtS = (curr.time! - prev.time!) / 1000;
      if (dtS > 0) {
        hasMovingTimeData = true;
        final speedKmh = (segmentM / 1000 / dtS) * 3600;
        if (speedKmh > _movingSpeedThresholdKmh) {
          movingTimeS += dtS;
        }
      }
    }
  }

  final distanceKm = distanceM / 1000;

  final firstTime = points.first.time;
  final lastTime = points.last.time;
  final durationS = firstTime != null && lastTime != null
      ? (lastTime - firstTime) / 1000
      : null;

  final resolvedMovingTimeS = hasMovingTimeData ? movingTimeS : null;

  double? avgSpeedKmh;
  if (resolvedMovingTimeS != null && resolvedMovingTimeS > 0) {
    avgSpeedKmh = distanceKm / (resolvedMovingTimeS / 3600);
  } else if (durationS != null && durationS > 0) {
    avgSpeedKmh = distanceKm / (durationS / 3600);
  }

  final elevation = _computeElevation(points);

  return RideStats(
    distanceKm: distanceKm,
    durationS: durationS?.round(),
    movingTimeS: resolvedMovingTimeS?.round(),
    avgSpeedKmh: avgSpeedKmh,
    ascentM: elevation.ascentM,
    descentM: elevation.descentM,
  );
}

/// Formatiert Sekunden als "H:MM:SS" bzw. "M:SS", "–" bei null.
String formatDuration(int? s) {
  if (s == null) {
    return '–';
  }

  final totalS = math.max(0, s);
  final hours = totalS ~/ 3600;
  final minutes = (totalS % 3600) ~/ 60;
  final seconds = totalS % 60;

  final mm = minutes.toString().padLeft(2, '0');
  final ss = seconds.toString().padLeft(2, '0');

  if (hours > 0) {
    return '$hours:$mm:$ss';
  }

  return '$minutes:$ss';
}

/// Formatiert Kilometer mit einer Nachkommastelle, z. B. "42.3".
String formatKm(double km) => km.toStringAsFixed(1);
