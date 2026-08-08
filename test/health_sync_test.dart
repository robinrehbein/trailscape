import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:trailscape/health_sync.dart';
import 'package:trailscape/models.dart';

/// Attrappe der Health-Plattform. Jeder Lesevorgang kann einzeln auf Fehler
/// gestellt werden, um Teilausfälle zu simulieren.
class FakeHealthGateway implements HealthGateway {
  FakeHealthGateway({
    this.availabilityValue = HealthAvailability.verfuegbar,
    this.permissionsGranted = true,
    this.grantOnRequest = true,
    this.workouts = const [],
    this.routes = const {},
    this.heartRate = const [],
    this.restingHeartRate = const [],
    this.sleep = const [],
    this.vo2max = const [],
    this.failWorkouts = false,
    this.failRoutes = false,
    this.failHeartRate = false,
    this.failRestingHeartRate = false,
    this.failSleep = false,
    this.failVo2max = true,
  });

  HealthAvailability availabilityValue;
  bool permissionsGranted;
  bool grantOnRequest;

  List<HealthWorkout> workouts;
  Map<String, List<HealthRoutePoint>> routes;
  List<HealthHeartRateSample> heartRate;
  List<HealthNumericSample> restingHeartRate;
  List<HealthSleepSession> sleep;
  List<HealthNumericSample> vo2max;

  bool failWorkouts;
  bool failRoutes;
  bool failHeartRate;
  bool failRestingHeartRate;
  bool failSleep;
  bool failVo2max;

  int requestCount = 0;
  DateTime? lastWorkoutFrom;
  DateTime? lastWorkoutTo;
  final List<({DateTime from, DateTime to})> heartRateWindows = [];

  @override
  Future<HealthAvailability> availability() async => availabilityValue;

  @override
  Future<bool> hasPermissions() async => permissionsGranted;

  @override
  Future<bool> requestPermissions() async {
    requestCount++;
    permissionsGranted = grantOnRequest;
    return grantOnRequest;
  }

  @override
  Future<List<HealthWorkout>> readWorkouts({
    required DateTime from,
    required DateTime to,
  }) async {
    lastWorkoutFrom = from;
    lastWorkoutTo = to;
    if (failWorkouts) throw StateError('workouts kaputt');
    return workouts;
  }

  @override
  Future<Map<String, List<HealthRoutePoint>>> readRoutes({
    required DateTime from,
    required DateTime to,
  }) async {
    if (failRoutes) throw StateError('routen kaputt');
    return routes;
  }

  @override
  Future<List<HealthHeartRateSample>> readHeartRate({
    required DateTime from,
    required DateTime to,
  }) async {
    heartRateWindows.add((from: from, to: to));
    if (failHeartRate) throw StateError('hf kaputt');
    return heartRate;
  }

  @override
  Future<List<HealthNumericSample>> readRestingHeartRate({
    required DateTime from,
    required DateTime to,
  }) async {
    if (failRestingHeartRate) throw StateError('ruhepuls kaputt');
    return restingHeartRate;
  }

  @override
  Future<List<HealthSleepSession>> readSleepSessions({
    required DateTime from,
    required DateTime to,
  }) async {
    if (failSleep) throw StateError('schlaf kaputt');
    return sleep;
  }

  @override
  Future<List<HealthNumericSample>> readVo2Max({
    required DateTime from,
    required DateTime to,
  }) async {
    if (failVo2max) {
      throw UnsupportedError('VO2max nicht unterstützt');
    }
    return vo2max;
  }
}

DateTime _at(int year, int month, int day, [int hour = 0, int minute = 0]) =>
    DateTime(year, month, day, hour, minute);

HealthWorkout _cycling({
  String id = 'w1',
  required DateTime start,
  required DateTime end,
  double? distanceM = 20000,
  HealthActivityKind kind = HealthActivityKind.radfahren,
}) =>
    HealthWorkout(
      id: id,
      start: start,
      end: end,
      kind: kind,
      distanceM: distanceM,
      energyKcal: 500,
      sourceName: 'com.sec.android.app.shealth',
    );

Ride _ride({
  required String id,
  required DateTime start,
  required Duration duration,
  double distanceKm = 20,
  int? avgHrBpm,
  int? maxHrBpm,
}) =>
    Ride(
      id: id,
      name: 'Bestehende Tour',
      createdAt: start.millisecondsSinceEpoch,
      points: const [],
      stats: RideStats(
        distanceKm: distanceKm,
        durationS: duration.inSeconds,
        ascentM: 0,
        descentM: 0,
        avgHrBpm: avgHrBpm,
        maxHrBpm: maxHrBpm,
      ),
    );

/// Bestehende Tour mit Trackpunkten (wie vom Handy aufgezeichnet).
Ride _rideWithPoints({
  required String id,
  required DateTime start,
  int pointCount = 3,
  Duration step = const Duration(minutes: 10),
  int? avgHrBpm,
  int? pointHr,
}) =>
    Ride(
      id: id,
      name: 'Handy-Tour',
      createdAt: start.millisecondsSinceEpoch,
      points: [
        for (var i = 0; i < pointCount; i++)
          TrackPoint(
            lat: 48 + i * 0.001,
            lon: 11,
            ele: 500 + i * 5,
            time: start.add(step * i).millisecondsSinceEpoch,
            hr: pointHr,
          ),
      ],
      stats: RideStats(
        distanceKm: 30,
        durationS: (step * (pointCount - 1)).inSeconds,
        movingTimeS: 1500,
        avgSpeedKmh: 25,
        ascentM: 120,
        descentM: 110,
        avgHrBpm: avgHrBpm,
      ),
    );

/// Gateway, das VO2max an eine echte [HealthPluginGateway] (mit gemocktem
/// Platform-Channel) weiterreicht — alles andere bleibt Attrappe.
class ChannelVo2MaxGateway extends FakeHealthGateway {
  ChannelVo2MaxGateway(this.inner);

  final HealthPluginGateway inner;

  @override
  Future<List<HealthNumericSample>> readVo2Max({
    required DateTime from,
    required DateTime to,
  }) =>
      inner.readVo2Max(from: from, to: to);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  group('overlapRatio', () {
    test('kein Überlappen ergibt 0', () {
      expect(
        overlapRatio(
          aStart: _at(2026, 8, 1, 10),
          aEnd: _at(2026, 8, 1, 11),
          bStart: _at(2026, 8, 1, 12),
          bEnd: _at(2026, 8, 1, 13),
        ),
        0,
      );
    });

    test('vollständige Überdeckung ergibt 1', () {
      expect(
        overlapRatio(
          aStart: _at(2026, 8, 1, 10),
          aEnd: _at(2026, 8, 1, 11),
          bStart: _at(2026, 8, 1, 9),
          bEnd: _at(2026, 8, 1, 12),
        ),
        1,
      );
    });

    test('halbe Überdeckung ergibt exakt 0.5', () {
      expect(
        overlapRatio(
          aStart: _at(2026, 8, 1, 10),
          aEnd: _at(2026, 8, 1, 12),
          bStart: _at(2026, 8, 1, 11),
          bEnd: _at(2026, 8, 1, 14),
        ),
        0.5,
      );
    });

    test('direkt aneinandergrenzende Zeiträume überlappen nicht', () {
      expect(
        overlapRatio(
          aStart: _at(2026, 8, 1, 10),
          aEnd: _at(2026, 8, 1, 11),
          bStart: _at(2026, 8, 1, 11),
          bEnd: _at(2026, 8, 1, 12),
        ),
        0,
      );
    });

    test('punktförmiger Zeitraum zählt nur bei Treffer', () {
      final punkt = _at(2026, 8, 1, 10, 30);
      expect(
        overlapRatio(
          aStart: punkt,
          aEnd: punkt,
          bStart: _at(2026, 8, 1, 10),
          bEnd: _at(2026, 8, 1, 11),
        ),
        1,
      );
      expect(
        overlapRatio(
          aStart: punkt,
          aEnd: punkt,
          bStart: _at(2026, 8, 1, 11),
          bEnd: _at(2026, 8, 1, 12),
        ),
        0,
      );
    });
  });

  group('rideTimeRange', () {
    test('nutzt Trackpunkt-Zeitstempel, wenn vorhanden', () {
      final start = _at(2026, 8, 1, 10);
      final ride = Ride(
        id: 'a',
        name: 'a',
        createdAt: start.millisecondsSinceEpoch,
        points: [
          TrackPoint(lat: 1, lon: 2, time: start.millisecondsSinceEpoch),
          TrackPoint(
            lat: 1,
            lon: 2,
            time: start.add(const Duration(hours: 2)).millisecondsSinceEpoch,
          ),
        ],
        stats: const RideStats(distanceKm: 1, ascentM: 0, descentM: 0),
      );

      final range = rideTimeRange(ride);
      expect(range.start, start);
      expect(range.end, start.add(const Duration(hours: 2)));
    });

    test('fällt auf createdAt plus Dauer zurück', () {
      final start = _at(2026, 8, 1, 10);
      final range = rideTimeRange(
        _ride(id: 'a', start: start, duration: const Duration(minutes: 90)),
      );
      expect(range.start, start);
      expect(range.end, start.add(const Duration(minutes: 90)));
    });

    test('ohne Dauer und ohne Punkte ist der Zeitraum punktförmig', () {
      final start = _at(2026, 8, 1, 10);
      final ride = Ride(
        id: 'a',
        name: 'a',
        createdAt: start.millisecondsSinceEpoch,
        points: const [],
        stats: const RideStats(distanceKm: 0, ascentM: 0, descentM: 0),
      );
      final range = rideTimeRange(ride);
      expect(range.start, start);
      expect(range.end, start);
    });
  });

  group('buildRideFromWorkout', () {
    test('bildet Route, Höhen und Herzfrequenz auf das Ride-Modell ab', () {
      final start = _at(2026, 8, 1, 10);
      final workout = _cycling(
        id: 'abc-123',
        start: start,
        end: start.add(const Duration(hours: 1)),
        distanceM: 25000,
      );

      final ride = buildRideFromWorkout(
        workout,
        route: [
          HealthRoutePoint(lat: 48.0, lon: 11.0, ele: 500, time: start),
          HealthRoutePoint(
            lat: 48.01,
            lon: 11.0,
            ele: 520,
            time: start.add(const Duration(minutes: 30)),
          ),
          HealthRoutePoint(
            lat: 48.02,
            lon: 11.0,
            ele: 540,
            time: start.add(const Duration(hours: 1)),
          ),
        ],
        heartRate: [
          HealthHeartRateSample(time: start, bpm: 120),
          HealthHeartRateSample(
            time: start.add(const Duration(minutes: 30)),
            bpm: 150,
          ),
          HealthHeartRateSample(
            time: start.add(const Duration(hours: 1)),
            bpm: 180,
          ),
        ],
      );

      expect(ride.id, 'hc-abc-123');
      expect(ride.name, 'Tour 01.08.2026 (Watch)');
      expect(ride.createdAt, start.millisecondsSinceEpoch);
      expect(ride.points, hasLength(3));

      // Distanz kommt vom Geraet, nicht aus der Route.
      expect(ride.stats.distanceKm, closeTo(25, 0.001));
      expect(ride.stats.durationS, 3600);
      expect(ride.stats.ascentM, closeTo(40, 0.001));
      expect(ride.stats.avgHrBpm, 150);
      expect(ride.stats.maxHrBpm, 180);

      // Jeder Trackpunkt bekommt die zeitlich passende Herzfrequenz.
      expect(ride.points.map((p) => p.hr).toList(), [120, 150, 180]);
      expect(ride.points.first.ele, 500);
      expect(ride.points.first.time, start.millisecondsSinceEpoch);
    });

    test('importiert ohne Route nur Distanz, Dauer und Herzfrequenz', () {
      final start = _at(2026, 8, 2, 9);
      final ride = buildRideFromWorkout(
        _cycling(
          id: 'ohne-route',
          start: start,
          end: start.add(const Duration(minutes: 90)),
          distanceM: 42000,
        ),
        heartRate: [
          HealthHeartRateSample(
            time: start.add(const Duration(minutes: 10)),
            bpm: 130,
          ),
          HealthHeartRateSample(
            time: start.add(const Duration(minutes: 20)),
            bpm: 140,
          ),
        ],
      );

      expect(ride.points, isEmpty);
      expect(ride.stats.distanceKm, closeTo(42, 0.001));
      expect(ride.stats.durationS, 5400);
      expect(ride.stats.movingTimeS, isNull);
      expect(ride.stats.ascentM, 0);
      expect(ride.stats.descentM, 0);
      expect(ride.stats.avgSpeedKmh, closeTo(28, 0.001));
      expect(ride.stats.avgHrBpm, 135);
      expect(ride.stats.maxHrBpm, 140);
    });

    test('berechnet die Distanz aus der Route, wenn das Gerät keine liefert',
        () {
      final start = _at(2026, 8, 3, 8);
      final ride = buildRideFromWorkout(
        _cycling(
          id: 'keine-distanz',
          start: start,
          end: start.add(const Duration(minutes: 10)),
          distanceM: null,
        ),
        route: [
          HealthRoutePoint(lat: 0, lon: 0, time: start),
          HealthRoutePoint(
            lat: 0,
            lon: 0.1,
            time: start.add(const Duration(minutes: 10)),
          ),
        ],
      );

      expect(ride.stats.distanceKm, greaterThan(10));
      expect(ride.stats.distanceKm, lessThan(12));
    });

    test('ignoriert Herzfrequenzen außerhalb des Workout-Zeitraums', () {
      final start = _at(2026, 8, 4, 7);
      final ride = buildRideFromWorkout(
        _cycling(
          id: 'hf-fenster',
          start: start,
          end: start.add(const Duration(minutes: 30)),
        ),
        heartRate: [
          HealthHeartRateSample(
            time: start.subtract(const Duration(hours: 2)),
            bpm: 60,
          ),
          HealthHeartRateSample(
            time: start.add(const Duration(minutes: 10)),
            bpm: 150,
          ),
          HealthHeartRateSample(
            time: start.add(const Duration(hours: 5)),
            bpm: 200,
          ),
        ],
      );

      expect(ride.stats.avgHrBpm, 150);
      expect(ride.stats.maxHrBpm, 150);
    });

    test('ohne Herzfrequenz bleiben die HF-Felder leer', () {
      final start = _at(2026, 8, 5, 7);
      final ride = buildRideFromWorkout(
        _cycling(
          id: 'ohne-hf',
          start: start,
          end: start.add(const Duration(minutes: 30)),
        ),
      );
      expect(ride.stats.avgHrBpm, isNull);
      expect(ride.stats.maxHrBpm, isNull);
      // Rueckwaertskompatibel: ohne HF bleibt das JSON unveraendert.
      expect(ride.stats.toJson().containsKey('avgHrBpm'), isFalse);
      expect(ride.toJson()['points'], isEmpty);
    });

    test('markiert Indoor-Fahrten im Namen', () {
      final start = _at(2026, 8, 6, 18);
      final ride = buildRideFromWorkout(
        _cycling(
          id: 'rolle',
          start: start,
          end: start.add(const Duration(minutes: 45)),
          kind: HealthActivityKind.radfahrenIndoor,
        ),
      );
      expect(ride.name, 'Tour 06.08.2026 (Watch) (Indoor)');
    });

    test('erzeugt eine dateisystemtaugliche ID', () {
      expect(healthRideId('a/b c:d'), 'hc-a-b-c-d');
    });
  });

  group('importNewRides', () {
    test('importiert nur Rad-Workouts', () async {
      final start = _at(2026, 8, 1, 10);
      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(id: 'rad', start: start, end: start.add(const Duration(hours: 1))),
          HealthWorkout(
            id: 'lauf',
            start: start.add(const Duration(days: 1)),
            end: start.add(const Duration(days: 1, hours: 1)),
            kind: HealthActivityKind.sonstiges,
            distanceM: 10000,
          ),
        ],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final rides = await service.importNewRides(existing: const []);
      expect(rides, hasLength(1));
      expect(rides.single.id, 'hc-rad');
    });

    test('überspringt Sessions mit mehr als 50 % Überlappung', () async {
      final start = _at(2026, 8, 1, 10);
      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(
            id: 'doppelt',
            start: start,
            end: start.add(const Duration(hours: 2)),
          ),
        ],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      // Bestehende Tour deckt 90 Minuten von 120 ab -> 75 %.
      final rides = await service.importNewRides(
        existing: [
          _ride(
            id: 'lokal',
            start: start,
            duration: const Duration(minutes: 90),
          ),
        ],
      );

      expect(rides, isEmpty);
    });

    test('importiert bei exakt 50 % Überlappung (Grenzfall)', () async {
      final start = _at(2026, 8, 1, 10);
      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(
            id: 'grenzfall',
            start: start,
            end: start.add(const Duration(hours: 2)),
          ),
        ],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final rides = await service.importNewRides(
        existing: [
          _ride(
            id: 'lokal',
            start: start.add(const Duration(hours: 1)),
            duration: const Duration(hours: 3),
          ),
        ],
      );

      expect(rides, hasLength(1));
      expect(rides.single.id, 'hc-grenzfall');
    });

    test('importiert bei geringer Überlappung', () async {
      final start = _at(2026, 8, 1, 10);
      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(
            id: 'knapp',
            start: start,
            end: start.add(const Duration(hours: 2)),
          ),
        ],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final rides = await service.importNewRides(
        existing: [
          _ride(
            id: 'lokal',
            // Nur die letzten 15 min der Session -> 12,5 %.
            start: start.add(const Duration(minutes: 105)),
            duration: const Duration(hours: 2),
          ),
        ],
      );

      expect(rides, hasLength(1));
    });

    test('überspringt bereits importierte Sessions anhand der ID', () async {
      final start = _at(2026, 8, 1, 10);
      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(
            id: 'schon-da',
            start: start,
            end: start.add(const Duration(hours: 1)),
          ),
        ],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      // Zeitlich verschobene, aber identisch benannte Tour: nur die ID greift.
      final rides = await service.importNewRides(
        existing: [
          _ride(
            id: 'hc-schon-da',
            start: _at(2026, 7, 1, 10),
            duration: const Duration(hours: 1),
          ),
        ],
      );

      expect(rides, isEmpty);
    });

    test('entdoppelt auch innerhalb eines Laufs', () async {
      final start = _at(2026, 8, 1, 10);
      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(
            id: 'a',
            start: start,
            end: start.add(const Duration(hours: 2)),
          ),
          // Nahezu identische Session einer zweiten Quell-App.
          _cycling(
            id: 'b',
            start: start.add(const Duration(minutes: 2)),
            end: start.add(const Duration(hours: 2)),
          ),
        ],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final rides = await service.importNewRides(existing: const []);
      expect(rides, hasLength(1));
      expect(rides.single.id, 'hc-a');
    });

    test('eine bestehende Tour ohne Dauer blockiert nichts', () async {
      final start = _at(2026, 8, 1, 10);
      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(
            id: 'neu',
            start: start,
            end: start.add(const Duration(hours: 2)),
          ),
        ],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final rides = await service.importNewRides(
        existing: [
          Ride(
            id: 'punkt',
            name: 'Punkt',
            createdAt: start.millisecondsSinceEpoch,
            points: const [],
            stats: const RideStats(distanceKm: 0, ascentM: 0, descentM: 0),
          ),
        ],
      );

      expect(rides, hasLength(1));
    });

    test('reicht die Route durch, wenn sie verfügbar ist', () async {
      final start = _at(2026, 8, 1, 10);
      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(
            id: 'mit-route',
            start: start,
            end: start.add(const Duration(hours: 1)),
          ),
        ],
        routes: {
          'mit-route': [
            HealthRoutePoint(lat: 48, lon: 11, time: start),
            HealthRoutePoint(
              lat: 48.01,
              lon: 11,
              time: start.add(const Duration(minutes: 30)),
            ),
          ],
        },
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final rides = await service.importNewRides(existing: const []);
      expect(rides.single.points, hasLength(2));
    });

    test('importiert ohne Route weiter, wenn der Routen-Abruf scheitert',
        () async {
      final start = _at(2026, 8, 1, 10);
      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(
            id: 'route-kaputt',
            start: start,
            end: start.add(const Duration(hours: 1)),
          ),
        ],
        failRoutes: true,
        failHeartRate: true,
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final rides = await service.importNewRides(existing: const []);
      expect(rides, hasLength(1));
      expect(rides.single.points, isEmpty);
      expect(rides.single.stats.distanceKm, closeTo(20, 0.001));
    });

    test('liest die Herzfrequenz je Workout, nicht über das ganze Fenster',
        () async {
      final start = _at(2026, 8, 1, 10);
      final zweite = _at(2026, 8, 5, 10);
      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(
            id: 'a',
            start: start,
            end: start.add(const Duration(hours: 1)),
          ),
          _cycling(
            id: 'b',
            start: zweite,
            end: zweite.add(const Duration(hours: 2)),
          ),
        ],
        heartRate: [HealthHeartRateSample(time: start, bpm: 140)],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final rides = await service.importNewRides(existing: const []);

      expect(rides, hasLength(2));
      expect(gateway.heartRateWindows, hasLength(2));
      expect(gateway.heartRateWindows.first.from, start);
      expect(gateway.heartRateWindows.first.to,
          start.add(const Duration(hours: 1)));
      expect(gateway.heartRateWindows.last.from, zweite);
      // Nur die Messung im ersten Workout-Fenster wird zugeordnet.
      expect(rides.first.stats.avgHrBpm, 140);
      expect(rides.last.stats.avgHrBpm, isNull);
    });

    test('nutzt den gespeicherten Zeitstempel als Startpunkt', () async {
      final letzterImport = _at(2026, 8, 5);
      SharedPreferences.setMockInitialValues({
        healthSyncStorageKey: letzterImport.millisecondsSinceEpoch,
      });

      final gateway = FakeHealthGateway();
      final now = _at(2026, 8, 10, 12);
      final service = HealthSyncService(gateway: gateway, now: () => now);

      await service.importNewRides(existing: const []);

      expect(gateway.lastWorkoutFrom, letzterImport);
      expect(gateway.lastWorkoutTo, now);
    });

    test('ohne Zeitstempel wird das Startfenster verwendet', () async {
      final gateway = FakeHealthGateway();
      final now = _at(2026, 8, 10, 12);
      final service = HealthSyncService(gateway: gateway, now: () => now);

      await service.importNewRides(existing: const []);

      expect(gateway.lastWorkoutFrom, now.subtract(healthSyncInitialWindow));
    });

    test('schreibt den Zeitstempel nach dem Import fort', () async {
      final gateway = FakeHealthGateway();
      final now = _at(2026, 8, 10, 12);
      final service = HealthSyncService(gateway: gateway, now: () => now);

      expect(await service.lastImportAt(), isNull);
      await service.importNewRides(existing: const []);
      expect(await service.lastImportAt(), now);
    });

    test('scheitert verständlich, wenn Health Connect fehlt', () async {
      final gateway = FakeHealthGateway(
        availabilityValue: HealthAvailability.nichtInstalliert,
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      expect(
        () => service.importNewRides(existing: const []),
        throwsA(isA<HealthSyncException>()),
      );
    });

    test('scheitert, wenn die Berechtigungen fehlen', () async {
      final gateway = FakeHealthGateway(permissionsGranted: false);
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      expect(
        () => service.importNewRides(existing: const []),
        throwsA(isA<HealthSyncException>()),
      );
    });

    test('meldet einen Fehler beim Lesen der Workouts', () async {
      final gateway = FakeHealthGateway(failWorkouts: true);
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      expect(
        () => service.importNewRides(existing: const []),
        throwsA(isA<HealthSyncException>()),
      );
    });
  });

  group('checkAvailability / requestPermissions', () {
    test('meldet Bereitschaft bei erteilten Rechten', () async {
      final service = HealthSyncService(gateway: FakeHealthGateway());
      final connection = await service.checkAvailability();
      expect(connection.isReady, isTrue);
      expect(connection.needsPermissions, isFalse);
    });

    test('meldet fehlende Rechte mit Hinweistext', () async {
      final service = HealthSyncService(
        gateway: FakeHealthGateway(permissionsGranted: false),
      );
      final connection = await service.checkAvailability();
      expect(connection.isReady, isFalse);
      expect(connection.needsPermissions, isTrue);
      expect(connection.message, contains('Zustimmung'));
    });

    test('fragt Rechte nur an, wenn sie fehlen', () async {
      final gateway = FakeHealthGateway(permissionsGranted: true);
      final service = HealthSyncService(gateway: gateway);

      expect(await service.requestPermissions(), isTrue);
      expect(gateway.requestCount, 0);

      gateway.permissionsGranted = false;
      expect(await service.requestPermissions(), isTrue);
      expect(gateway.requestCount, 1);
    });

    test('fragt nichts an, wenn Health Connect fehlt', () async {
      final gateway = FakeHealthGateway(
        availabilityValue: HealthAvailability.nichtInstalliert,
        permissionsGranted: false,
      );
      final service = HealthSyncService(gateway: gateway);

      expect(await service.requestPermissions(), isFalse);
      expect(gateway.requestCount, 0);
    });

    test('gibt Update-Bedarf verständlich zurück', () async {
      final service = HealthSyncService(
        gateway: FakeHealthGateway(
          availabilityValue: HealthAvailability.updateNoetig,
        ),
      );
      final connection = await service.checkAvailability();
      expect(connection.message, contains('aktualisiert'));
    });
  });

  group('readVitals', () {
    // 2026-08-10 ist der "heutige" Tag; letzte Woche = 04.08.-10.08.,
    // Vorwoche = 28.07.-03.08.
    final now = _at(2026, 8, 10, 12);

    HealthSyncService serviceWith(FakeHealthGateway gateway) =>
        HealthSyncService(gateway: gateway, now: () => now);

    test('mittelt den Ruhepuls je Tag und bildet den Wochentrend', () async {
      final gateway = FakeHealthGateway(
        restingHeartRate: [
          // Vorwoche: Mittel 60
          HealthNumericSample(time: _at(2026, 7, 29, 6), value: 58),
          HealthNumericSample(time: _at(2026, 7, 31, 6), value: 62),
          // Letzte Woche: 55 (Tagesmittel aus 54/56) und 57 -> Mittel 56
          HealthNumericSample(time: _at(2026, 8, 5, 6), value: 54),
          HealthNumericSample(time: _at(2026, 8, 5, 7), value: 56),
          HealthNumericSample(time: _at(2026, 8, 9, 6), value: 57),
        ],
      );

      final vitals = await serviceWith(gateway).readVitals();

      final hr = vitals.restingHeartRate;
      expect(hr.hasData, isTrue);
      // Zwei Messungen am 05.08. werden zu einem Tageswert gemittelt.
      expect(hr.series, hasLength(4));
      expect(hr.series[2].value, 55);
      expect(hr.lastWeekAvg, 56);
      expect(hr.previousWeekAvg, 60);
      expect(hr.delta, -4);
      expect(hr.deltaPercent, closeTo(-6.7, 0.05));
      expect(hr.min, 55);
      expect(hr.max, 62);
      expect(hr.latest, 57);
    });

    test('summiert Schlaf je Aufwachtag und rechnet in Stunden', () async {
      final gateway = FakeHealthGateway(
        sleep: [
          // Nacht auf den 09.08.: 6 h + 1 h Nickerchen -> 7 h
          HealthSleepSession(
            start: _at(2026, 8, 8, 23),
            end: _at(2026, 8, 9, 5),
          ),
          HealthSleepSession(
            start: _at(2026, 8, 9, 14),
            end: _at(2026, 8, 9, 15),
          ),
          // Vorwoche
          HealthSleepSession(
            start: _at(2026, 7, 29, 23),
            end: _at(2026, 7, 30, 7),
          ),
        ],
      );

      final vitals = await serviceWith(gateway).readVitals();

      final sleep = vitals.sleepHours;
      expect(sleep.series, hasLength(2));
      expect(sleep.series.first.day, _at(2026, 7, 30));
      expect(sleep.series.first.value, 8);
      expect(sleep.series.last.day, _at(2026, 8, 9));
      expect(sleep.series.last.value, 7);
      expect(sleep.lastWeekAvg, 7);
      expect(sleep.previousWeekAvg, 8);
      expect(sleep.delta, -1);
    });

    test('kein Trend, wenn die Vorwoche keine Daten hat', () async {
      final gateway = FakeHealthGateway(
        restingHeartRate: [
          HealthNumericSample(time: _at(2026, 8, 9, 6), value: 57),
        ],
      );

      final hr = (await serviceWith(gateway).readVitals()).restingHeartRate;
      expect(hr.hasData, isTrue);
      expect(hr.lastWeekAvg, 57);
      expect(hr.previousWeekAvg, isNull);
      expect(hr.hasTrend, isFalse);
      expect(hr.delta, isNull);
      expect(hr.deltaPercent, isNull);
    });

    test('leere Daten ergeben eine leere Zusammenfassung', () async {
      final vitals = await serviceWith(FakeHealthGateway()).readVitals();

      expect(vitals.isEmpty, isTrue);
      expect(vitals.restingHeartRate.hasData, isFalse);
      expect(vitals.restingHeartRate.series, isEmpty);
      expect(vitals.restingHeartRate.lastWeekAvg, isNull);
      expect(vitals.restingHeartRate.min, isNull);
      expect(vitals.sleepHours.hasData, isFalse);
      expect(vitals.vo2max, isNull);
    });

    test('ein Ausfall beim Schlaf verhindert den Ruhepuls nicht', () async {
      final gateway = FakeHealthGateway(
        restingHeartRate: [
          HealthNumericSample(time: _at(2026, 8, 9, 6), value: 57),
        ],
        failSleep: true,
      );

      final vitals = await serviceWith(gateway).readVitals();

      expect(vitals.restingHeartRate.hasData, isTrue);
      expect(vitals.sleepHours.hasData, isFalse);
      expect(vitals.unavailable, contains(VitalsDataKind.schlaf));
      expect(vitals.unavailable, isNot(contains(VitalsDataKind.ruhepuls)));
    });

    test('ein Ausfall beim Ruhepuls verhindert den Schlaf nicht', () async {
      final gateway = FakeHealthGateway(
        sleep: [
          HealthSleepSession(
            start: _at(2026, 8, 8, 23),
            end: _at(2026, 8, 9, 7),
          ),
        ],
        failRestingHeartRate: true,
      );

      final vitals = await serviceWith(gateway).readVitals();

      expect(vitals.sleepHours.hasData, isTrue);
      expect(vitals.restingHeartRate.hasData, isFalse);
      expect(vitals.unavailable, contains(VitalsDataKind.ruhepuls));
    });

    test('VO2max wird als nicht verfügbar gemeldet', () async {
      final vitals = await serviceWith(FakeHealthGateway()).readVitals();
      expect(vitals.vo2max, isNull);
      expect(vitals.unavailable, contains(VitalsDataKind.vo2max));
    });

    test('nimmt den neuesten VO2max-Wert, wenn die Plattform ihn liefert',
        () async {
      final gateway = FakeHealthGateway(
        failVo2max: false,
        vo2max: [
          HealthNumericSample(time: _at(2026, 8, 1), value: 47.5),
          HealthNumericSample(time: _at(2026, 8, 8), value: 48.26),
          HealthNumericSample(time: _at(2026, 8, 4), value: 46),
        ],
      );

      final vitals = await serviceWith(gateway).readVitals();
      expect(vitals.vo2max, 48.3);
      expect(vitals.vo2maxAt, _at(2026, 8, 8));
      expect(vitals.unavailable, isEmpty);
    });

    test('das Fenster richtet sich nach days', () async {
      final vitals = await serviceWith(FakeHealthGateway()).readVitals(days: 7);
      expect(vitals.days, 7);
      expect(vitals.from, _at(2026, 8, 4));
      expect(vitals.to, now);
    });
  });

  group('healthSyncInitialWindow', () {
    test('umfasst 30 Tage (Health Connect gibt ohne Historien-Freigabe nicht '
        'mehr her)', () {
      expect(healthSyncInitialWindow, const Duration(days: 30));
    });

    test('ohne Zeitstempel wird genau 30 Tage zurückgeschaut', () async {
      final gateway = FakeHealthGateway();
      final now = _at(2026, 8, 10, 12);
      final service = HealthSyncService(gateway: gateway, now: () => now);

      await service.importWithReport(existing: const []);

      expect(gateway.lastWorkoutFrom, _at(2026, 7, 11, 12));
      expect(gateway.lastWorkoutTo, now);
    });
  });

  group('rideHasHeartRate', () {
    test('erkennt Kennzahlen und Trackpunkt-Werte', () {
      final start = _at(2026, 8, 1, 10);
      expect(
        rideHasHeartRate(_rideWithPoints(id: 'ohne', start: start)),
        isFalse,
      );
      expect(
        rideHasHeartRate(
          _rideWithPoints(id: 'avg', start: start, avgHrBpm: 140),
        ),
        isTrue,
      );
      expect(
        rideHasHeartRate(
          _rideWithPoints(id: 'punkte', start: start, pointHr: 132),
        ),
        isTrue,
      );
      expect(
        rideHasHeartRate(
          _ride(
            id: 'max',
            start: start,
            duration: const Duration(hours: 1),
            maxHrBpm: 180,
          ),
        ),
        isTrue,
      );
    });
  });

  group('mergeHeartRateIntoRide', () {
    final start = _at(2026, 8, 1, 10);

    test('ordnet die zeitlich nächste Messung innerhalb ±60 s zu', () {
      final ride = _rideWithPoints(id: 'lokal', start: start);
      final merged = mergeHeartRateIntoRide(ride, [
        // 30 s nach dem ersten Punkt -> zugeordnet.
        HealthHeartRateSample(
          time: start.add(const Duration(seconds: 30)),
          bpm: 120,
        ),
        // 70 s nach dem zweiten Punkt -> außerhalb der Toleranz.
        HealthHeartRateSample(
          time: start.add(const Duration(minutes: 11, seconds: 10)),
          bpm: 200,
        ),
        // Exakt auf dem dritten Punkt.
        HealthHeartRateSample(
          time: start.add(const Duration(minutes: 20)),
          bpm: 180,
        ),
      ]);

      expect(merged, isNotNull);
      expect(merged!.points.map((p) => p.hr).toList(), [120, null, 180]);
      expect(merged.stats.avgHrBpm, 150);
      expect(merged.stats.maxHrBpm, 180);
    });

    test('nimmt bei zwei Messungen die näher liegende', () {
      final ride = _rideWithPoints(id: 'lokal', start: start, pointCount: 1);
      final merged = mergeHeartRateIntoRide(ride, [
        HealthHeartRateSample(
          time: start.subtract(const Duration(seconds: 50)),
          bpm: 100,
        ),
        HealthHeartRateSample(
          time: start.add(const Duration(seconds: 5)),
          bpm: 155,
        ),
      ]);

      expect(merged!.points.single.hr, 155);
      expect(merged.stats.avgHrBpm, 155);
    });

    test('behält ID, Name, Zeitpunkt, Punkte und Kennzahlen', () {
      final ride = _rideWithPoints(id: 'lokal', start: start);
      final merged = mergeHeartRateIntoRide(ride, [
        HealthHeartRateSample(time: start, bpm: 130),
      ])!;

      expect(merged.id, ride.id);
      expect(merged.name, ride.name);
      expect(merged.createdAt, ride.createdAt);
      expect(merged.points, hasLength(ride.points.length));
      expect(merged.points.first.lat, ride.points.first.lat);
      expect(merged.points.first.lon, ride.points.first.lon);
      expect(merged.points.first.ele, ride.points.first.ele);
      expect(merged.points.last.time, ride.points.last.time);
      expect(merged.stats.distanceKm, ride.stats.distanceKm);
      expect(merged.stats.ascentM, ride.stats.ascentM);
      expect(merged.stats.descentM, ride.stats.descentM);
      expect(merged.stats.durationS, ride.stats.durationS);
      expect(merged.stats.movingTimeS, ride.stats.movingTimeS);
      expect(merged.stats.avgSpeedKmh, ride.stats.avgSpeedKmh);
    });

    test('ohne Messwerte passiert nichts', () {
      expect(
        mergeHeartRateIntoRide(_rideWithPoints(id: 'lokal', start: start), []),
        isNull,
      );
    });

    test('ohne Trackpunkte passiert nichts', () {
      expect(
        mergeHeartRateIntoRide(
          _ride(id: 'lokal', start: start, duration: const Duration(hours: 1)),
          [HealthHeartRateSample(time: start, bpm: 130)],
        ),
        isNull,
      );
    });

    test('liegt alles außerhalb der Toleranz, bleibt die Tour unangetastet',
        () {
      final merged = mergeHeartRateIntoRide(
        _rideWithPoints(id: 'lokal', start: start),
        [
          HealthHeartRateSample(
            time: start.subtract(const Duration(hours: 3)),
            bpm: 130,
          ),
        ],
      );
      expect(merged, isNull);
    });

    test('viele Punkte und Messungen laufen in einem Durchlauf', () {
      final ride = _rideWithPoints(
        id: 'lang',
        start: start,
        pointCount: 500,
        step: const Duration(seconds: 10),
      );
      final samples = [
        for (var i = 0; i < 2000; i++)
          HealthHeartRateSample(
            time: start.add(Duration(milliseconds: i * 2500)),
            bpm: 100 + (i % 60).toDouble(),
          ),
      ];

      final merged = mergeHeartRateIntoRide(ride, samples)!;
      expect(merged.points.where((p) => p.hr != null), hasLength(500));
      expect(merged.stats.maxHrBpm, isNotNull);
    });
  });

  group('importWithReport', () {
    test('zählt gefundene, importierte, zusammengeführte und doppelte '
        'Sessions', () async {
      final tag1 = _at(2026, 8, 1, 10);
      final tag2 = _at(2026, 8, 2, 10);
      final tag3 = _at(2026, 8, 3, 10);
      final tag4 = _at(2026, 8, 4, 10);
      final tag5 = _at(2026, 8, 5, 10);

      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(id: 'mit-route', start: tag1, end: tag1.add(const Duration(hours: 1))),
          _cycling(id: 'ohne-route', start: tag2, end: tag2.add(const Duration(hours: 1))),
          _cycling(id: 'bekannt', start: tag3, end: tag3.add(const Duration(hours: 1))),
          _cycling(id: 'hf-schon-da', start: tag4, end: tag4.add(const Duration(hours: 1))),
          _cycling(
            id: 'merge',
            start: tag5,
            end: tag5.add(const Duration(minutes: 25)),
          ),
          HealthWorkout(
            id: 'lauf',
            start: tag1.add(const Duration(days: 6)),
            end: tag1.add(const Duration(days: 6, hours: 1)),
            kind: HealthActivityKind.sonstiges,
            distanceM: 10000,
          ),
        ],
        routes: {
          'mit-route': [
            HealthRoutePoint(lat: 48, lon: 11, time: tag1),
            HealthRoutePoint(
              lat: 48.01,
              lon: 11,
              time: tag1.add(const Duration(minutes: 30)),
            ),
          ],
        },
        heartRate: [
          HealthHeartRateSample(time: tag5, bpm: 120),
          HealthHeartRateSample(
            time: tag5.add(const Duration(minutes: 10)),
            bpm: 160,
          ),
          HealthHeartRateSample(
            time: tag5.add(const Duration(minutes: 20)),
            bpm: 180,
          ),
        ],
      );

      final now = _at(2026, 8, 10, 12);
      final service = HealthSyncService(gateway: gateway, now: () => now);

      final report = await service.importWithReport(
        existing: [
          _ride(id: 'hc-bekannt', start: _at(2026, 7, 1), duration: const Duration(hours: 1)),
          _ride(
            id: 'mit-hf',
            start: tag4,
            duration: const Duration(hours: 1),
            avgHrBpm: 142,
          ),
          _rideWithPoints(id: 'ohne-hf', start: tag5),
        ],
      );

      expect(report.from, now.subtract(const Duration(days: 30)));
      expect(report.to, now);
      // Nur Rad-Sessions zählen, das Laufen nicht.
      expect(report.workoutsFound, 5);
      expect(report.imported.map((r) => r.id).toList(),
          ['hc-mit-route', 'hc-ohne-route']);
      expect(report.mergedRides.map((r) => r.id).toList(), ['ohne-hf']);
      // 'bekannt' (gleiche ID) und 'hf-schon-da' (Tour hat bereits HF).
      expect(report.duplicatesSkipped, 2);
      // Outdoor-Tour ohne Trackpunkte.
      expect(report.routesMissing, 1);
      expect(report.changedCount, 3);
      expect(report.isEmpty, isFalse);
    });

    test('Indoor-Touren ohne Route zählen nicht als fehlende Route', () async {
      final start = _at(2026, 8, 1, 18);
      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(
            id: 'rolle',
            start: start,
            end: start.add(const Duration(minutes: 45)),
            kind: HealthActivityKind.radfahrenIndoor,
          ),
        ],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final report = await service.importWithReport(existing: const []);
      expect(report.imported, hasLength(1));
      expect(report.routesMissing, 0);
    });

    test('ohne Sessions bleibt der Bericht leer und schreibt den Zeitstempel '
        'fort', () async {
      final now = _at(2026, 8, 10, 12);
      final service = HealthSyncService(
        gateway: FakeHealthGateway(),
        now: () => now,
      );

      final report = await service.importWithReport(existing: const []);
      expect(report.workoutsFound, 0);
      expect(report.imported, isEmpty);
      expect(report.mergedRides, isEmpty);
      expect(report.duplicatesSkipped, 0);
      expect(report.routesMissing, 0);
      expect(report.isEmpty, isTrue);
      expect(await service.lastImportAt(), now);
    });

    test('importNewRides bleibt ein dünner Wrapper über importWithReport',
        () async {
      final start = _at(2026, 8, 1, 10);
      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(
            id: 'neu',
            start: start,
            end: start.add(const Duration(hours: 1)),
          ),
        ],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final rides = await service.importNewRides(existing: const []);
      expect(rides.map((r) => r.id).toList(), ['hc-neu']);
    });
  });

  group('HF-Merge beim Import', () {
    final start = _at(2026, 8, 1, 10);

    FakeHealthGateway gatewayWith({
      List<HealthHeartRateSample> heartRate = const [],
    }) =>
        FakeHealthGateway(
          workouts: [
            _cycling(
              id: 'watch',
              start: start,
              end: start.add(const Duration(minutes: 25)),
            ),
          ],
          heartRate: heartRate,
        );

    test('reichert eine überlappende Tour ohne HF an, statt sie zu verwerfen',
        () async {
      final gateway = gatewayWith(
        heartRate: [
          HealthHeartRateSample(
            time: start.add(const Duration(seconds: 20)),
            bpm: 120,
          ),
          HealthHeartRateSample(
            time: start.add(const Duration(minutes: 10)),
            bpm: 150,
          ),
          HealthHeartRateSample(
            time: start.add(const Duration(minutes: 20)),
            bpm: 180,
          ),
        ],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final bestehend = _rideWithPoints(id: 'lokal', start: start);
      final report = await service.importWithReport(existing: [bestehend]);

      expect(report.imported, isEmpty);
      expect(report.duplicatesSkipped, 0);
      expect(report.mergedRides, hasLength(1));

      final merged = report.mergedRides.single;
      expect(merged.id, 'lokal');
      expect(merged.points, hasLength(bestehend.points.length));
      expect(merged.points.map((p) => p.hr).toList(), [120, 150, 180]);
      expect(merged.stats.avgHrBpm, 150);
      expect(merged.stats.maxHrBpm, 180);
      expect(merged.stats.distanceKm, bestehend.stats.distanceKm);
      // Die HF wird nur für das Session-Fenster gelesen.
      expect(gateway.heartRateWindows.single.from, start);
      expect(gateway.heartRateWindows.single.to,
          start.add(const Duration(minutes: 25)));
    });

    test('eine Tour mit vorhandener HF wird nicht angefasst', () async {
      final gateway = gatewayWith(
        heartRate: [HealthHeartRateSample(time: start, bpm: 120)],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final report = await service.importWithReport(
        existing: [_rideWithPoints(id: 'lokal', start: start, avgHrBpm: 138)],
      );

      expect(report.mergedRides, isEmpty);
      expect(report.imported, isEmpty);
      expect(report.duplicatesSkipped, 1);
      // Ohne Merge-Kandidat wird die HF gar nicht erst gelesen.
      expect(gateway.heartRateWindows, isEmpty);
    });

    test('auch Trackpunkt-HF schützt die bestehende Tour', () async {
      final service = HealthSyncService(
        gateway: gatewayWith(
          heartRate: [HealthHeartRateSample(time: start, bpm: 120)],
        ),
        now: () => _at(2026, 8, 10),
      );

      final report = await service.importWithReport(
        existing: [_rideWithPoints(id: 'lokal', start: start, pointHr: 131)],
      );

      expect(report.mergedRides, isEmpty);
      expect(report.duplicatesSkipped, 1);
    });

    test('ohne HF-Samples bleibt die Session ein Duplikat', () async {
      final service = HealthSyncService(
        gateway: gatewayWith(),
        now: () => _at(2026, 8, 10),
      );

      final report = await service.importWithReport(
        existing: [_rideWithPoints(id: 'lokal', start: start)],
      );

      expect(report.mergedRides, isEmpty);
      expect(report.imported, isEmpty);
      expect(report.duplicatesSkipped, 1);
    });

    test('ein Fehler beim HF-Lesen macht die Session zum Duplikat', () async {
      final gateway = gatewayWith(
        heartRate: [HealthHeartRateSample(time: start, bpm: 120)],
      )..failHeartRate = true;
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final report = await service.importWithReport(
        existing: [_rideWithPoints(id: 'lokal', start: start)],
      );

      expect(report.mergedRides, isEmpty);
      expect(report.duplicatesSkipped, 1);
    });

    test('dieselbe Tour wird höchstens einmal je Lauf angereichert', () async {
      final gateway = FakeHealthGateway(
        workouts: [
          _cycling(
            id: 'watch-a',
            start: start,
            end: start.add(const Duration(minutes: 25)),
          ),
          // Nahezu identische Session einer zweiten Quell-App.
          _cycling(
            id: 'watch-b',
            start: start.add(const Duration(minutes: 1)),
            end: start.add(const Duration(minutes: 25)),
          ),
        ],
        heartRate: [
          HealthHeartRateSample(time: start, bpm: 120),
          HealthHeartRateSample(
            time: start.add(const Duration(minutes: 20)),
            bpm: 160,
          ),
        ],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );

      final report = await service.importWithReport(
        existing: [_rideWithPoints(id: 'lokal', start: start)],
      );

      expect(report.mergedRides, hasLength(1));
      expect(report.duplicatesSkipped, 1);
    });

    test('importNewRides liefert Merges nicht mit zurück', () async {
      final service = HealthSyncService(
        gateway: gatewayWith(
          heartRate: [
            HealthHeartRateSample(time: start, bpm: 120),
            HealthHeartRateSample(
              time: start.add(const Duration(minutes: 20)),
              bpm: 160,
            ),
          ],
        ),
        now: () => _at(2026, 8, 10),
      );

      final rides = await service.importNewRides(
        existing: [_rideWithPoints(id: 'lokal', start: start)],
      );
      expect(rides, isEmpty);
    });
  });

  group('VO2max über den Platform-Channel', () {
    const channel = MethodChannel('trailscape/health_extra.test');
    final calls = <MethodCall>[];
    late TestDefaultBinaryMessengerBinding binding;

    void mock(Future<Object?> Function(MethodCall call) handler) {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel,
          (call) async {
        calls.add(call);
        return handler(call);
      });
    }

    setUp(() {
      binding = TestDefaultBinaryMessengerBinding.instance;
      calls.clear();
    });

    tearDown(() {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    });

    test('liest VO2max über den Channel statt UnsupportedError zu werfen',
        () async {
      mock((call) async => [
            {'timeMs': _at(2026, 8, 8).millisecondsSinceEpoch, 'vo2': 48.26},
            {'timeMs': _at(2026, 8, 1).millisecondsSinceEpoch, 'vo2': 47.5},
          ]);

      final gateway = HealthPluginGateway(extraChannel: channel);
      final from = _at(2026, 8, 1);
      final to = _at(2026, 8, 10);
      final samples = await gateway.readVo2Max(from: from, to: to);

      expect(calls.single.method, 'readVo2Max');
      expect(calls.single.arguments, {
        'startMs': from.millisecondsSinceEpoch,
        'endMs': to.millisecondsSinceEpoch,
      });
      // Aufsteigend sortiert.
      expect(samples.map((s) => s.value).toList(), [47.5, 48.26]);
      expect(samples.first.time, _at(2026, 8, 1));
    });

    test('leere oder unbrauchbare Antworten ergeben keine Messwerte', () async {
      mock((call) async => null);
      final leer = await HealthPluginGateway(extraChannel: channel)
          .readVo2Max(from: _at(2026, 8, 1), to: _at(2026, 8, 10));
      expect(leer, isEmpty);

      mock((call) async => [
            {'timeMs': 'kaputt', 'vo2': 48.0},
            {'vo2': 48.0},
            'unsinn',
            {'timeMs': _at(2026, 8, 5).millisecondsSinceEpoch, 'vo2': 44},
          ]);
      final gefiltert = await HealthPluginGateway(extraChannel: channel)
          .readVo2Max(from: _at(2026, 8, 1), to: _at(2026, 8, 10));
      expect(gefiltert, hasLength(1));
      expect(gefiltert.single.value, 44.0);
    });

    test('ein Channel-Fehler schlägt bis zum Aufrufer durch', () async {
      mock((call) async => throw PlatformException(code: 'unavailable'));

      expect(
        () => HealthPluginGateway(extraChannel: channel)
            .readVo2Max(from: _at(2026, 8, 1), to: _at(2026, 8, 10)),
        throwsA(isA<PlatformException>()),
      );
    });

    test('readVitals übernimmt die Channel-Werte', () async {
      mock((call) async => [
            {'timeMs': _at(2026, 8, 8).millisecondsSinceEpoch, 'vo2': 48.26},
          ]);

      final service = HealthSyncService(
        gateway: ChannelVo2MaxGateway(
          HealthPluginGateway(extraChannel: channel),
        ),
        now: () => _at(2026, 8, 10, 12),
      );

      final vitals = await service.readVitals();
      expect(vitals.vo2max, 48.3);
      expect(vitals.vo2maxAt, _at(2026, 8, 8));
      expect(vitals.unavailable, isNot(contains(VitalsDataKind.vo2max)));
    });

    test('wirft der Channel, bleibt VO2max in unavailable', () async {
      mock((call) async =>
          throw PlatformException(code: 'permission_denied', message: 'nope'));

      final service = HealthSyncService(
        gateway: ChannelVo2MaxGateway(
          HealthPluginGateway(extraChannel: channel),
        ),
        now: () => _at(2026, 8, 10, 12),
      );

      final vitals = await service.readVitals();
      expect(vitals.vo2max, isNull);
      expect(vitals.unavailable, contains(VitalsDataKind.vo2max));
    });

    test('ein fehlender Channel (alte Installation) meldet nur VO2max ab',
        () async {
      // Kein Mock registriert -> MissingPluginException.
      final service = HealthSyncService(
        gateway: ChannelVo2MaxGateway(
          HealthPluginGateway(extraChannel: channel),
        ),
        now: () => _at(2026, 8, 10, 12),
      );

      final vitals = await service.readVitals();
      expect(vitals.unavailable, contains(VitalsDataKind.vo2max));
      expect(vitals.unavailable, isNot(contains(VitalsDataKind.ruhepuls)));
    });
  });
}
