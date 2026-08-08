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
      ),
    );

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
}
