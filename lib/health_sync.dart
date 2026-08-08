/// Anbindung an Google Health Connect.
///
/// Auf Samsung-Geräten spiegelt Samsung Health die auf der Galaxy Watch
/// aufgezeichneten Trainings sowie Vitaldaten nach Health Connect. Dieses
/// Modul liest von dort
///
///  * Rad-Workouts (ExerciseSession) inklusive GPS-Route und Herzfrequenz und
///    bildet sie auf das bestehende [Ride]-Modell ab, und
///  * Vitaldaten (Ruhepuls, Schlaf) als Tagesserien mit Wochentrend.
///
/// Alle Plugin-Aufrufe laufen über die Abstraktion [HealthGateway], damit die
/// Ableitungs- und Aggregationslogik ohne Gerät testbar bleibt. Die
/// produktive Implementierung ist [HealthPluginGateway] (Paket `health`).
///
/// Bekannte Grenzen der Plattform (Stand `health` 13.3.x):
///
///  * **VO2max** wird vom Paket gar nicht angeboten (kein `HealthDataType`
///    dafür, weder für Health Connect noch für HealthKit). [VitalsSummary]
///    hält das Feld trotzdem vor; [HealthPluginGateway.readVo2Max] liefert
///    stets eine leere Liste und meldet den Datentyp als nicht verfügbar.
///  * **GPS-Routen** liefert Health Connect nur, solange die App im
///    Vordergrund läuft, und für fremde Apps (z. B. Samsung Health) nur, wenn
///    die Nutzerin in der Health-Connect-App unter
///    „App-Berechtigungen → Trailscape → Trainingsrouten“ dauerhaft zugestimmt
///    hat. Fehlt die Route, wird die Tour ohne Trackpunkte importiert
///    (Distanz/Dauer/Herzfrequenz bleiben erhalten).
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:health/health.dart' as hc;
import 'package:shared_preferences/shared_preferences.dart';

import 'models.dart';
import 'stats.dart';

/// Speicherschlüssel für den Zeitpunkt des letzten Imports (ms seit Epoch).
const String healthSyncStorageKey = 'trailscape.healthsync';

/// Wie weit zurück importiert wird, wenn noch nie synchronisiert wurde.
const Duration healthSyncInitialWindow = Duration(days: 90);

/// Ab welchem zeitlichen Überlappungsanteil eine Health-Connect-Session als
/// bereits vorhandene Tour gilt und übersprungen wird (strikt größer).
const double healthSyncOverlapThreshold = 0.5;

// ---------------------------------------------------------------------------
// Datentypen der Abstraktionsschicht
// ---------------------------------------------------------------------------

/// Verfügbarkeit von Health Connect auf diesem Gerät.
enum HealthAvailability {
  /// Health Connect ist installiert und nutzbar.
  verfuegbar,

  /// Health Connect ist nicht installiert.
  nichtInstalliert,

  /// Health Connect ist installiert, muss aber aktualisiert werden.
  updateNoetig,

  /// Die Plattform unterstützt Health Connect nicht (z. B. Desktop/Web).
  nichtUnterstuetzt,
}

/// Ergebnis von [HealthSyncService.checkAvailability].
class HealthConnection {
  const HealthConnection({
    required this.availability,
    required this.hasPermissions,
  });

  final HealthAvailability availability;

  /// Ob alle benötigten Leserechte bereits erteilt sind.
  final bool hasPermissions;

  /// Ob sofort gelesen werden kann.
  bool get isReady =>
      availability == HealthAvailability.verfuegbar && hasPermissions;

  /// Ob eine Berechtigungsabfrage sinnvoll ist.
  bool get needsPermissions =>
      availability == HealthAvailability.verfuegbar && !hasPermissions;

  /// Für die UI verwendbare deutsche Beschreibung des Zustands.
  String get message => switch (availability) {
        HealthAvailability.nichtUnterstuetzt =>
          'Health Connect wird auf diesem Gerät nicht unterstützt.',
        HealthAvailability.nichtInstalliert =>
          'Health Connect ist nicht installiert. Bitte installiere die App aus '
              'dem Play Store, damit Trailscape auf die Watch-Daten zugreifen kann.',
        HealthAvailability.updateNoetig =>
          'Health Connect muss aktualisiert werden, bevor Trailscape darauf '
              'zugreifen kann.',
        HealthAvailability.verfuegbar => hasPermissions
            ? 'Health Connect ist verbunden.'
            : 'Trailscape braucht noch deine Zustimmung, um Health-Connect-Daten '
                'zu lesen.',
      };
}

/// Fehler, der eine Synchronisation komplett verhindert.
class HealthSyncException implements Exception {
  const HealthSyncException(this.message);

  /// Für die UI geeignete deutsche Meldung.
  final String message;

  @override
  String toString() => message;
}

/// Art eines Workouts, soweit für Trailscape relevant.
enum HealthActivityKind {
  /// Radfahren im Freien.
  radfahren,

  /// Radfahren auf der Rolle / im Studio (ohne GPS-Route).
  radfahrenIndoor,

  /// Alles andere (Laufen, Wandern, ...).
  sonstiges,
}

/// Ein Workout (ExerciseSession) aus Health Connect.
class HealthWorkout {
  const HealthWorkout({
    required this.id,
    required this.start,
    required this.end,
    required this.kind,
    this.distanceM,
    this.energyKcal,
    this.sourceName,
  });

  /// Stabile ID des Health-Connect-Datensatzes.
  final String id;
  final DateTime start;
  final DateTime end;
  final HealthActivityKind kind;

  /// Vom Gerät gemessene Gesamtdistanz in Metern.
  final double? distanceM;

  /// Verbrauchte Energie in kcal.
  final int? energyKcal;

  /// Name der Quell-App (z. B. `com.sec.android.app.shealth`).
  final String? sourceName;

  /// Ob es sich um ein Rad-Workout handelt (drinnen oder draußen).
  bool get isCycling =>
      kind == HealthActivityKind.radfahren ||
      kind == HealthActivityKind.radfahrenIndoor;

  Duration get duration => end.difference(start);
}

/// Ein einzelner GPS-Punkt einer Trainingsroute.
class HealthRoutePoint {
  const HealthRoutePoint({
    required this.lat,
    required this.lon,
    required this.time,
    this.ele,
  });

  final double lat;
  final double lon;
  final DateTime time;
  final double? ele;
}

/// Eine Herzfrequenz-Messung.
class HealthHeartRateSample {
  const HealthHeartRateSample({required this.time, required this.bpm});

  final DateTime time;
  final double bpm;
}

/// Ein Messwert mit Zeitpunkt (Ruhepuls, VO2max, ...).
class HealthNumericSample {
  const HealthNumericSample({required this.time, required this.value});

  final DateTime time;
  final double value;
}

/// Eine Schlafphase bzw. -sitzung.
class HealthSleepSession {
  const HealthSleepSession({required this.start, required this.end});

  final DateTime start;
  final DateTime end;

  Duration get duration => end.difference(start);
}

/// Zugriff auf die Health-Plattform. Wird von [HealthSyncService] benutzt und
/// in Tests durch eine Attrappe ersetzt.
abstract class HealthGateway {
  /// Zustand der Health-Connect-Installation.
  Future<HealthAvailability> availability();

  /// Ob alle benötigten Leserechte erteilt sind.
  Future<bool> hasPermissions();

  /// Fragt die benötigten Leserechte an. Liefert `true` bei Zustimmung.
  Future<bool> requestPermissions();

  /// Alle Workouts im Zeitraum `[from, to]`.
  Future<List<HealthWorkout>> readWorkouts({
    required DateTime from,
    required DateTime to,
  });

  /// GPS-Routen im Zeitraum, nach Workout-ID ([HealthWorkout.id]) gruppiert.
  /// Workouts ohne (freigegebene) Route fehlen in der Map.
  Future<Map<String, List<HealthRoutePoint>>> readRoutes({
    required DateTime from,
    required DateTime to,
  });

  /// Herzfrequenz-Zeitreihe im Zeitraum.
  Future<List<HealthHeartRateSample>> readHeartRate({
    required DateTime from,
    required DateTime to,
  });

  /// Ruhepuls-Messungen im Zeitraum.
  Future<List<HealthNumericSample>> readRestingHeartRate({
    required DateTime from,
    required DateTime to,
  });

  /// Schlafsitzungen im Zeitraum.
  Future<List<HealthSleepSession>> readSleepSessions({
    required DateTime from,
    required DateTime to,
  });

  /// VO2max-Messungen im Zeitraum. Health Connect kennt den Datentyp, das
  /// `health`-Paket bietet ihn aber nicht an — die produktive Implementierung
  /// wirft daher [UnsupportedError].
  Future<List<HealthNumericSample>> readVo2Max({
    required DateTime from,
    required DateTime to,
  });
}

// ---------------------------------------------------------------------------
// Vitaldaten
// ---------------------------------------------------------------------------

/// Ein Tageswert einer Vitaldaten-Reihe.
class DailyValue {
  const DailyValue({required this.day, required this.value});

  /// Kalendertag (lokal, auf Mitternacht normalisiert).
  final DateTime day;
  final double value;
}

/// Tagesserie mit 7-Tage-Trend gegenüber der Vorwoche.
class VitalsTrend {
  const VitalsTrend({
    required this.series,
    required this.lastWeekAvg,
    required this.previousWeekAvg,
  });

  /// Leere Reihe ohne Trend.
  const VitalsTrend.empty()
      : series = const [],
        lastWeekAvg = null,
        previousWeekAvg = null;

  /// Tageswerte, aufsteigend nach Datum.
  final List<DailyValue> series;

  /// Mittelwert der letzten 7 Tage, `null` wenn keine Werte vorliegen.
  final double? lastWeekAvg;

  /// Mittelwert der 7 Tage davor, `null` wenn keine Werte vorliegen.
  final double? previousWeekAvg;

  bool get hasData => series.isNotEmpty;

  /// Ob sich beide Wochen vergleichen lassen.
  bool get hasTrend => lastWeekAvg != null && previousWeekAvg != null;

  /// Absolute Veränderung (letzte Woche minus Vorwoche).
  double? get delta =>
      hasTrend ? _round1(lastWeekAvg! - previousWeekAvg!) : null;

  /// Relative Veränderung in Prozent.
  double? get deltaPercent {
    if (!hasTrend || previousWeekAvg == 0) {
      return null;
    }
    return _round1((lastWeekAvg! - previousWeekAvg!) / previousWeekAvg! * 100);
  }

  /// Neuester Tageswert.
  double? get latest => series.isEmpty ? null : series.last.value;

  /// Kleinster Tageswert.
  double? get min => series.isEmpty
      ? null
      : series.map((v) => v.value).reduce((a, b) => a < b ? a : b);

  /// Größter Tageswert.
  double? get max => series.isEmpty
      ? null
      : series.map((v) => v.value).reduce((a, b) => a > b ? a : b);
}

/// Datentypen, die beim Lesen der Vitaldaten fehlschlagen können.
enum VitalsDataKind { ruhepuls, schlaf, vo2max }

/// Ergebnis von [HealthSyncService.readVitals].
class VitalsSummary {
  const VitalsSummary({
    required this.days,
    required this.from,
    required this.to,
    required this.restingHeartRate,
    required this.sleepHours,
    this.vo2max,
    this.vo2maxAt,
    this.unavailable = const {},
  });

  /// Betrachtetes Fenster in Tagen.
  final int days;
  final DateTime from;
  final DateTime to;

  /// Ruhepuls in bpm je Tag.
  final VitalsTrend restingHeartRate;

  /// Schlafdauer in Stunden je Tag (dem Aufwachtag zugeordnet).
  final VitalsTrend sleepHours;

  /// Zuletzt gemessener VO2max-Wert, falls die Plattform ihn liefert.
  final double? vo2max;

  /// Zeitpunkt der VO2max-Messung.
  final DateTime? vo2maxAt;

  /// Datentypen, die nicht gelesen werden konnten (fehlende Berechtigung,
  /// Plattform-Grenze, Fehler). Die übrigen Werte bleiben trotzdem gültig.
  final Set<VitalsDataKind> unavailable;

  /// Ob überhaupt Daten vorliegen.
  bool get isEmpty =>
      !restingHeartRate.hasData && !sleepHours.hasData && vo2max == null;
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

/// Liest Touren und Vitaldaten aus Health Connect.
class HealthSyncService {
  HealthSyncService({HealthGateway? gateway, DateTime Function()? now})
      : gateway = gateway ?? HealthPluginGateway(),
        _now = now ?? DateTime.now;

  final HealthGateway gateway;
  final DateTime Function() _now;

  /// Prüft Installation und Berechtigungen in einem Rutsch.
  Future<HealthConnection> checkAvailability() async {
    final availability = await gateway.availability();
    if (availability != HealthAvailability.verfuegbar) {
      return HealthConnection(
        availability: availability,
        hasPermissions: false,
      );
    }

    bool granted;
    try {
      granted = await gateway.hasPermissions();
    } catch (_) {
      granted = false;
    }

    return HealthConnection(
      availability: availability,
      hasPermissions: granted,
    );
  }

  /// Fragt die benötigten Leserechte an.
  ///
  /// Liefert `false`, wenn Health Connect nicht verfügbar ist oder die
  /// Nutzerin ablehnt.
  Future<bool> requestPermissions() async {
    if (await gateway.availability() != HealthAvailability.verfuegbar) {
      return false;
    }
    if (await gateway.hasPermissions()) {
      return true;
    }
    return gateway.requestPermissions();
  }

  /// Zeitpunkt des letzten erfolgreichen Imports, `null` wenn noch nie.
  Future<DateTime?> lastImportAt() async {
    final prefs = await SharedPreferences.getInstance();
    final ms = prefs.getInt(healthSyncStorageKey);
    if (ms == null) {
      return null;
    }
    return DateTime.fromMillisecondsSinceEpoch(ms);
  }

  /// Setzt den Import-Zeitstempel. `null` löscht ihn (nächster Import
  /// betrachtet dann wieder [healthSyncInitialWindow]).
  Future<void> setLastImportAt(DateTime? when) async {
    final prefs = await SharedPreferences.getInstance();
    if (when == null) {
      await prefs.remove(healthSyncStorageKey);
    } else {
      await prefs.setInt(healthSyncStorageKey, when.millisecondsSinceEpoch);
    }
  }

  /// Importiert neue Rad-Workouts als [Ride]s.
  ///
  /// [since] begrenzt den betrachteten Zeitraum; ohne Angabe wird der
  /// gespeicherte Zeitstempel des letzten Imports benutzt, andernfalls
  /// [healthSyncInitialWindow].
  ///
  /// [existing] sind die bereits gespeicherten Touren. Eine Session wird
  /// übersprungen, wenn ihre ID schon vorkommt oder sie sich zu mehr als
  /// [healthSyncOverlapThreshold] zeitlich mit einer bestehenden Tour
  /// überschneidet.
  ///
  /// Die zurückgegebenen Touren sind **noch nicht gespeichert** — das
  /// übernimmt die UI über `AppState.addRide`. Nach einem erfolgreichen
  /// Durchlauf wird der Import-Zeitstempel auf „jetzt“ gesetzt.
  ///
  /// Wirft [HealthSyncException], wenn Health Connect nicht verfügbar ist
  /// oder die Berechtigungen fehlen.
  Future<List<Ride>> importNewRides({
    DateTime? since,
    required List<Ride> existing,
  }) async {
    final connection = await checkAvailability();
    if (!connection.isReady) {
      throw HealthSyncException(connection.message);
    }

    final to = _now();
    final from = since ??
        await lastImportAt() ??
        to.subtract(healthSyncInitialWindow);

    final List<HealthWorkout> workouts;
    try {
      workouts = await gateway.readWorkouts(from: from, to: to);
    } catch (error) {
      throw HealthSyncException(
        'Die Trainings konnten nicht aus Health Connect gelesen werden: $error',
      );
    }

    final cycling = workouts.where((w) => w.isCycling).toList()
      ..sort((a, b) => a.start.compareTo(b.start));

    final candidates = <HealthWorkout>[];
    final existingRanges = existing.map(rideTimeRange).toList();
    final existingIds = existing.map((r) => r.id).toSet();

    for (final workout in cycling) {
      if (existingIds.contains(healthRideId(workout.id))) {
        continue;
      }
      if (_overlapsExisting(workout, existingRanges)) {
        continue;
      }
      candidates.add(workout);
      // Innerhalb eines Laufs importierte Touren zaehlen ebenfalls als
      // bestehend, damit zwei nahezu identische Sessions nicht doppelt landen.
      existingRanges.add((start: workout.start, end: workout.end));
      existingIds.add(healthRideId(workout.id));
    }

    if (candidates.isEmpty) {
      await setLastImportAt(to);
      return const [];
    }

    final windowStart = candidates.first.start;
    final windowEnd = candidates
        .map((w) => w.end)
        .reduce((a, b) => a.isAfter(b) ? a : b);

    // Routen sind pro Session wenige Datensätze — eine Abfrage über das ganze
    // Fenster reicht. Die Herzfrequenz wird dagegen je Workout gelesen: über
    // 90 Tage kämen sonst leicht sechsstellige Messreihen zusammen.
    final routes = await _readOptional(
      () => gateway.readRoutes(from: windowStart, to: windowEnd),
      const <String, List<HealthRoutePoint>>{},
    );

    final rides = <Ride>[];
    for (final workout in candidates) {
      final heartRate = await _readOptional(
        () => gateway.readHeartRate(from: workout.start, to: workout.end),
        const <HealthHeartRateSample>[],
      );
      rides.add(
        buildRideFromWorkout(
          workout,
          route: routes[workout.id] ?? const [],
          heartRate: heartRate,
        ),
      );
    }

    await setLastImportAt(to);
    return rides;
  }

  /// Liest Ruhepuls, Schlaf und (falls verfügbar) VO2max der letzten [days]
  /// Tage und verdichtet sie zu Tagesserien mit 7-Tage-Trend.
  ///
  /// Wirft nicht: Fällt ein einzelner Datentyp aus (fehlende Berechtigung,
  /// Plattform-Grenze), landet er in [VitalsSummary.unavailable]; die übrigen
  /// Werte werden trotzdem geliefert.
  Future<VitalsSummary> readVitals({int days = 14}) async {
    final windowDays = math.max(1, days);
    final to = _now();
    final from = _startOfDay(to).subtract(Duration(days: windowDays - 1));

    final unavailable = <VitalsDataKind>{};

    final resting = await _readOptional<List<HealthNumericSample>>(
      () => gateway.readRestingHeartRate(from: from, to: to),
      const [],
      onError: () => unavailable.add(VitalsDataKind.ruhepuls),
    );
    final sleep = await _readOptional<List<HealthSleepSession>>(
      () => gateway.readSleepSessions(from: from, to: to),
      const [],
      onError: () => unavailable.add(VitalsDataKind.schlaf),
    );
    final vo2 = await _readOptional<List<HealthNumericSample>>(
      () => gateway.readVo2Max(from: from, to: to),
      const [],
      onError: () => unavailable.add(VitalsDataKind.vo2max),
    );

    final restingSeries = _dailyAverages(
      resting.map((s) => (day: _startOfDay(s.time), value: s.value)),
    );
    final sleepSeries = _dailySums(
      sleep.map(
        (s) => (
          // Der Schlaf wird dem Aufwachtag zugeordnet.
          day: _startOfDay(s.end),
          value: s.duration.inMinutes / 60.0,
        ),
      ),
    );

    HealthNumericSample? latestVo2;
    for (final sample in vo2) {
      if (latestVo2 == null || sample.time.isAfter(latestVo2.time)) {
        latestVo2 = sample;
      }
    }

    return VitalsSummary(
      days: windowDays,
      from: from,
      to: to,
      restingHeartRate: _buildTrend(restingSeries, to),
      sleepHours: _buildTrend(sleepSeries, to),
      vo2max: latestVo2 == null ? null : _round1(latestVo2.value),
      vo2maxAt: latestVo2?.time,
      unavailable: unavailable,
    );
  }

  bool _overlapsExisting(
    HealthWorkout workout,
    List<({DateTime start, DateTime end})> ranges,
  ) {
    for (final range in ranges) {
      final ratio = overlapRatio(
        aStart: workout.start,
        aEnd: workout.end,
        bStart: range.start,
        bEnd: range.end,
      );
      if (ratio > healthSyncOverlapThreshold) {
        return true;
      }
    }
    return false;
  }

  Future<T> _readOptional<T>(
    Future<T> Function() read,
    T fallback, {
    void Function()? onError,
  }) async {
    try {
      return await read();
    } catch (_) {
      onError?.call();
      return fallback;
    }
  }
}

// ---------------------------------------------------------------------------
// Ableitungen (frei testbar, ohne Plugin)
// ---------------------------------------------------------------------------

/// Ride-ID für ein Health-Connect-Workout. Aus der Datensatz-ID abgeleitet,
/// damit ein zweiter Import dieselbe Tour erkennt. Nicht dateisystemtaugliche
/// Zeichen werden ersetzt (Touren liegen als `<id>.json` auf der Platte).
String healthRideId(String workoutId) {
  final safe = workoutId.replaceAll(RegExp(r'[^A-Za-z0-9_-]'), '-');
  return 'hc-$safe';
}

/// Zeitraum einer bestehenden Tour. Bevorzugt die Trackpunkt-Zeitstempel,
/// sonst `createdAt` plus Dauer.
({DateTime start, DateTime end}) rideTimeRange(Ride ride) {
  final times = ride.points
      .map((p) => p.time)
      .whereType<int>()
      .toList(growable: false);

  var startMs = ride.createdAt;
  int? endMs;

  if (times.isNotEmpty) {
    startMs = times.reduce(math.min);
    endMs = times.reduce(math.max);
  }

  final durationS = ride.stats.durationS;
  if (endMs == null && durationS != null && durationS > 0) {
    endMs = startMs + durationS * 1000;
  }

  return (
    start: DateTime.fromMillisecondsSinceEpoch(startMs),
    end: DateTime.fromMillisecondsSinceEpoch(math.max(endMs ?? startMs, startMs)),
  );
}

/// Anteil des Zeitraums A, der von Zeitraum B überdeckt wird (0..1).
///
/// Für einen punktförmigen Zeitraum A (Start == Ende) gilt 1, wenn der Punkt
/// in B liegt, sonst 0.
double overlapRatio({
  required DateTime aStart,
  required DateTime aEnd,
  required DateTime bStart,
  required DateTime bEnd,
}) {
  final aFrom = aStart.millisecondsSinceEpoch;
  final aTo = math.max(aEnd.millisecondsSinceEpoch, aFrom);
  final bFrom = bStart.millisecondsSinceEpoch;
  final bTo = math.max(bEnd.millisecondsSinceEpoch, bFrom);

  final overlap = math.min(aTo, bTo) - math.max(aFrom, bFrom);
  final durationA = aTo - aFrom;

  if (durationA <= 0) {
    return aFrom >= bFrom && aFrom <= bTo ? 1 : 0;
  }
  if (overlap <= 0) {
    return 0;
  }
  return overlap / durationA;
}

/// Bildet ein Health-Connect-Workout auf das [Ride]-Modell ab.
///
/// [route] sind die GPS-Punkte der Session (ggf. leer), [heartRate] eine
/// Herzfrequenz-Zeitreihe, aus der die zum Workout gehörenden Messungen
/// gefiltert werden.
Ride buildRideFromWorkout(
  HealthWorkout workout, {
  List<HealthRoutePoint> route = const [],
  List<HealthHeartRateSample> heartRate = const [],
}) {
  final samples = heartRate
      .where((s) =>
          !s.time.isBefore(workout.start) && !s.time.isAfter(workout.end))
      .toList()
    ..sort((a, b) => a.time.compareTo(b.time));

  final sorted = List<HealthRoutePoint>.from(route)
    ..sort((a, b) => a.time.compareTo(b.time));

  final points = sorted
      .map((p) => TrackPoint(
            lat: p.lat,
            lon: p.lon,
            ele: p.ele,
            time: p.time.millisecondsSinceEpoch,
            hr: _nearestHr(samples, p.time),
          ))
      .toList(growable: false);

  final geo = points.length >= 2 ? computeStats(points) : null;

  final durationS = workout.duration.inSeconds > 0
      ? workout.duration.inSeconds
      : (geo?.durationS);

  // Die vom Gerät gemessene Distanz ist genauer als die aus der (geglätteten
  // und ggf. ausgedünnten) Route berechnete und wird daher bevorzugt.
  final distanceKm = workout.distanceM != null && workout.distanceM! > 0
      ? workout.distanceM! / 1000
      : (geo?.distanceKm ?? 0);

  final movingTimeS = geo?.movingTimeS;
  double? avgSpeedKmh;
  if (movingTimeS != null && movingTimeS > 0) {
    avgSpeedKmh = distanceKm / (movingTimeS / 3600);
  } else if (durationS != null && durationS > 0) {
    avgSpeedKmh = distanceKm / (durationS / 3600);
  }

  int? avgHr;
  int? maxHr;
  if (samples.isNotEmpty) {
    var sum = 0.0;
    var peak = samples.first.bpm;
    for (final sample in samples) {
      sum += sample.bpm;
      if (sample.bpm > peak) {
        peak = sample.bpm;
      }
    }
    avgHr = (sum / samples.length).round();
    maxHr = peak.round();
  }

  return Ride(
    id: healthRideId(workout.id),
    name: healthRideName(workout),
    createdAt: workout.start.millisecondsSinceEpoch,
    points: points,
    stats: RideStats(
      distanceKm: distanceKm,
      durationS: durationS,
      movingTimeS: movingTimeS,
      avgSpeedKmh: avgSpeedKmh,
      ascentM: geo?.ascentM ?? 0,
      descentM: geo?.descentM ?? 0,
      avgHrBpm: avgHr,
      maxHrBpm: maxHr,
    ),
  );
}

/// Name einer importierten Tour, im Stil der App: „Tour 08.08.2026 (Watch)“.
String healthRideName(HealthWorkout workout) {
  final d = workout.start;
  final day = d.day.toString().padLeft(2, '0');
  final month = d.month.toString().padLeft(2, '0');
  final suffix =
      workout.kind == HealthActivityKind.radfahrenIndoor ? ' (Indoor)' : '';
  return 'Tour $day.$month.${d.year} (Watch)$suffix';
}

/// Nächstgelegene Herzfrequenz zu [time], maximal 30 s entfernt.
int? _nearestHr(List<HealthHeartRateSample> samples, DateTime time) {
  if (samples.isEmpty) {
    return null;
  }

  HealthHeartRateSample? best;
  var bestDelta = const Duration(seconds: 30).inMilliseconds;

  for (final sample in samples) {
    final delta =
        (sample.time.millisecondsSinceEpoch - time.millisecondsSinceEpoch)
            .abs();
    if (delta <= bestDelta) {
      bestDelta = delta;
      best = sample;
    }
  }

  return best?.bpm.round();
}

DateTime _startOfDay(DateTime value) =>
    DateTime(value.year, value.month, value.day);

double _round1(double value) => (value * 10).round() / 10;

List<DailyValue> _dailyAverages(
  Iterable<({DateTime day, double value})> entries,
) {
  final sums = <DateTime, double>{};
  final counts = <DateTime, int>{};
  for (final entry in entries) {
    sums[entry.day] = (sums[entry.day] ?? 0) + entry.value;
    counts[entry.day] = (counts[entry.day] ?? 0) + 1;
  }
  return _sortedDaily(
    sums.map((day, sum) => MapEntry(day, sum / counts[day]!)),
  );
}

List<DailyValue> _dailySums(Iterable<({DateTime day, double value})> entries) {
  final sums = <DateTime, double>{};
  for (final entry in entries) {
    sums[entry.day] = (sums[entry.day] ?? 0) + entry.value;
  }
  return _sortedDaily(sums);
}

List<DailyValue> _sortedDaily(Map<DateTime, double> byDay) {
  final days = byDay.keys.toList()..sort((a, b) => a.compareTo(b));
  return days
      .map((day) => DailyValue(day: day, value: _round1(byDay[day]!)))
      .toList(growable: false);
}

/// Baut aus einer Tagesserie den 7-Tage-Trend relativ zu [now].
VitalsTrend _buildTrend(List<DailyValue> series, DateTime now) {
  if (series.isEmpty) {
    return const VitalsTrend.empty();
  }

  final today = _startOfDay(now);
  final lastWeekStart = today.subtract(const Duration(days: 6));
  final previousWeekStart = today.subtract(const Duration(days: 13));

  final lastWeek = <double>[];
  final previousWeek = <double>[];

  for (final entry in series) {
    if (!entry.day.isBefore(lastWeekStart) && !entry.day.isAfter(today)) {
      lastWeek.add(entry.value);
    } else if (!entry.day.isBefore(previousWeekStart) &&
        entry.day.isBefore(lastWeekStart)) {
      previousWeek.add(entry.value);
    }
  }

  return VitalsTrend(
    series: series,
    lastWeekAvg: _average(lastWeek),
    previousWeekAvg: _average(previousWeek),
  );
}

double? _average(List<double> values) {
  if (values.isEmpty) {
    return null;
  }
  return _round1(values.reduce((a, b) => a + b) / values.length);
}

// ---------------------------------------------------------------------------
// Produktive Implementierung auf Basis des Pakets `health`
// ---------------------------------------------------------------------------

/// Datentypen, für die Trailscape Leserechte anfragt.
///
/// `WORKOUT_ROUTE` zieht im Plugin automatisch `WORKOUT` nach; beide sind hier
/// dennoch explizit gelistet, damit die Rechteprüfung eindeutig bleibt.
const List<hc.HealthDataType> healthReadTypes = [
  hc.HealthDataType.WORKOUT,
  hc.HealthDataType.WORKOUT_ROUTE,
  hc.HealthDataType.HEART_RATE,
  hc.HealthDataType.RESTING_HEART_RATE,
  hc.HealthDataType.SLEEP_SESSION,
  hc.HealthDataType.DISTANCE_DELTA,
  hc.HealthDataType.TOTAL_CALORIES_BURNED,
];

/// [HealthGateway] auf Basis des pub.dev-Pakets `health` (Google Health
/// Connect). Wird nur auf dem Gerät benutzt.
class HealthPluginGateway implements HealthGateway {
  HealthPluginGateway({hc.Health? health}) : _health = health ?? hc.Health();

  final hc.Health _health;
  bool _configured = false;

  Future<hc.Health> _plugin() async {
    if (!_configured) {
      await _health.configure();
      _configured = true;
    }
    return _health;
  }

  @override
  Future<HealthAvailability> availability() async {
    final plugin = await _plugin();
    final status = await plugin.getHealthConnectSdkStatus();
    return switch (status) {
      hc.HealthConnectSdkStatus.sdkAvailable => HealthAvailability.verfuegbar,
      hc.HealthConnectSdkStatus.sdkUnavailableProviderUpdateRequired =>
        HealthAvailability.updateNoetig,
      hc.HealthConnectSdkStatus.sdkUnavailable =>
        HealthAvailability.nichtInstalliert,
      null => HealthAvailability.nichtUnterstuetzt,
    };
  }

  @override
  Future<bool> hasPermissions() async {
    final plugin = await _plugin();
    return await plugin.hasPermissions(healthReadTypes) ?? false;
  }

  @override
  Future<bool> requestPermissions() async {
    final plugin = await _plugin();
    return plugin.requestAuthorization(healthReadTypes);
  }

  /// Öffnet den Play-Store-Eintrag von Health Connect. Nur sinnvoll, wenn
  /// [availability] `nichtInstalliert` liefert.
  Future<void> installHealthConnect() async {
    final plugin = await _plugin();
    await plugin.installHealthConnect();
  }

  /// Fragt das Recht an, auch Daten von vor mehr als 30 Tagen zu lesen.
  Future<bool> requestHistoryAccess() async {
    final plugin = await _plugin();
    if (!await plugin.isHealthDataHistoryAvailable()) {
      return false;
    }
    if (await plugin.isHealthDataHistoryAuthorized()) {
      return true;
    }
    return plugin.requestHealthDataHistoryAuthorization();
  }

  @override
  Future<List<HealthWorkout>> readWorkouts({
    required DateTime from,
    required DateTime to,
  }) async {
    final plugin = await _plugin();
    final points = await plugin.getHealthDataFromTypes(
      types: const [hc.HealthDataType.WORKOUT],
      startTime: from,
      endTime: to,
    );

    final workouts = <HealthWorkout>[];
    for (final point in points) {
      final value = point.value;
      if (value is! hc.WorkoutHealthValue) {
        continue;
      }
      workouts.add(
        HealthWorkout(
          id: point.uuid,
          start: point.dateFrom,
          end: point.dateTo,
          kind: mapActivityKind(value.workoutActivityType),
          // totalDistance liefert Health Connect in Metern.
          distanceM: value.totalDistance?.toDouble(),
          energyKcal: value.totalEnergyBurned,
          sourceName: point.sourceName,
        ),
      );
    }
    return workouts;
  }

  @override
  Future<Map<String, List<HealthRoutePoint>>> readRoutes({
    required DateTime from,
    required DateTime to,
  }) async {
    final plugin = await _plugin();
    final points = await plugin.getHealthDataFromTypes(
      types: const [hc.HealthDataType.WORKOUT_ROUTE],
      startTime: from,
      endTime: to,
    );

    final routes = <String, List<HealthRoutePoint>>{};
    for (final point in points) {
      final value = point.value;
      if (value is! hc.WorkoutRouteHealthValue) {
        continue;
      }
      if (value.locations.isEmpty) {
        // Kommt vor, wenn Health Connect fuer diese Session noch eine
        // ausdrueckliche Freigabe der Route verlangt (ConsentRequired).
        continue;
      }
      final id = value.workoutUuid ?? point.uuid;
      routes[id] = value.locations
          .map((l) => HealthRoutePoint(
                lat: l.latitude,
                lon: l.longitude,
                time: l.timestamp,
                ele: l.altitude,
              ))
          .toList(growable: false);
    }
    return routes;
  }

  @override
  Future<List<HealthHeartRateSample>> readHeartRate({
    required DateTime from,
    required DateTime to,
  }) async {
    final values = await _numeric(hc.HealthDataType.HEART_RATE, from, to);
    return values
        .map((s) => HealthHeartRateSample(time: s.time, bpm: s.value))
        .toList(growable: false);
  }

  @override
  Future<List<HealthNumericSample>> readRestingHeartRate({
    required DateTime from,
    required DateTime to,
  }) =>
      _numeric(hc.HealthDataType.RESTING_HEART_RATE, from, to);

  @override
  Future<List<HealthSleepSession>> readSleepSessions({
    required DateTime from,
    required DateTime to,
  }) async {
    final plugin = await _plugin();
    final points = await plugin.getHealthDataFromTypes(
      types: const [hc.HealthDataType.SLEEP_SESSION],
      startTime: from,
      endTime: to,
    );
    return points
        .map((p) => HealthSleepSession(start: p.dateFrom, end: p.dateTo))
        .toList(growable: false);
  }

  /// VO2max wird vom Paket `health` nicht angeboten (kein passender
  /// [hc.HealthDataType]), obwohl Health Connect einen `Vo2MaxRecord` kennt.
  @override
  Future<List<HealthNumericSample>> readVo2Max({
    required DateTime from,
    required DateTime to,
  }) async {
    throw UnsupportedError(
      'VO2max wird vom health-Paket nicht unterstützt (kein HealthDataType).',
    );
  }

  Future<List<HealthNumericSample>> _numeric(
    hc.HealthDataType type,
    DateTime from,
    DateTime to,
  ) async {
    final plugin = await _plugin();
    final points = await plugin.getHealthDataFromTypes(
      types: [type],
      startTime: from,
      endTime: to,
    );

    final samples = <HealthNumericSample>[];
    for (final point in points) {
      final value = point.value;
      if (value is! hc.NumericHealthValue) {
        continue;
      }
      samples.add(
        HealthNumericSample(
          time: point.dateFrom,
          value: value.numericValue.toDouble(),
        ),
      );
    }
    samples.sort((a, b) => a.time.compareTo(b.time));
    return samples;
  }
}

/// Übersetzt den Workout-Typ des Plugins in [HealthActivityKind].
HealthActivityKind mapActivityKind(hc.HealthWorkoutActivityType type) =>
    switch (type) {
      hc.HealthWorkoutActivityType.BIKING ||
      hc.HealthWorkoutActivityType.HAND_CYCLING =>
        HealthActivityKind.radfahren,
      hc.HealthWorkoutActivityType.BIKING_STATIONARY =>
        HealthActivityKind.radfahrenIndoor,
      _ => HealthActivityKind.sonstiges,
    };
