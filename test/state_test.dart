import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:trailscape/health_sync.dart';
import 'package:trailscape/models.dart';
import 'package:trailscape/state.dart';
import 'package:trailscape/storage.dart';

/// Schlanke Attrappe der Health-Plattform für die AppState-Integration.
/// Deckt nur ab, was [AppState] tatsächlich benutzt (siehe health_sync_test.dart
/// für eine vollständigere Attrappe, die die Sync-Logik selbst testet).
class _FakeGateway implements HealthGateway {
  _FakeGateway({
    this.availabilityValue = HealthAvailability.verfuegbar,
    this.permissionsGranted = true,
    this.workouts = const [],
    this.failWorkouts = false,
  });

  HealthAvailability availabilityValue;
  bool permissionsGranted;
  List<HealthWorkout> workouts;
  bool failWorkouts;

  @override
  Future<HealthAvailability> availability() async => availabilityValue;

  @override
  Future<bool> hasPermissions() async => permissionsGranted;

  @override
  Future<bool> requestPermissions() async {
    permissionsGranted = true;
    return true;
  }

  @override
  Future<List<HealthWorkout>> readWorkouts({
    required DateTime from,
    required DateTime to,
  }) async {
    if (failWorkouts) throw StateError('workouts kaputt');
    return workouts;
  }

  @override
  Future<Map<String, List<HealthRoutePoint>>> readRoutes({
    required DateTime from,
    required DateTime to,
  }) async =>
      const {};

  @override
  Future<List<HealthHeartRateSample>> readHeartRate({
    required DateTime from,
    required DateTime to,
  }) async =>
      const [];

  @override
  Future<List<HealthNumericSample>> readRestingHeartRate({
    required DateTime from,
    required DateTime to,
  }) async =>
      const [];

  @override
  Future<List<HealthSleepSession>> readSleepSessions({
    required DateTime from,
    required DateTime to,
  }) async =>
      const [];

  @override
  Future<List<HealthNumericSample>> readVo2Max({
    required DateTime from,
    required DateTime to,
  }) async =>
      const [];
}

DateTime _at(int year, int month, int day, [int hour = 0]) =>
    DateTime(year, month, day, hour);

HealthWorkout _cycling({
  required String id,
  required DateTime start,
  required DateTime end,
}) =>
    HealthWorkout(
      id: id,
      start: start,
      end: end,
      kind: HealthActivityKind.radfahren,
      distanceM: 15000,
      energyKcal: 300,
      sourceName: 'com.sec.android.app.shealth',
    );

Ride _localRide(String id, int createdAt) => Ride(
      id: id,
      name: 'Lokale Tour',
      createdAt: createdAt,
      points: const [],
      stats: const RideStats(distanceKm: 10, ascentM: 0, descentM: 0),
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUp(() async {
    tempDir = await Directory.systemTemp.createTemp('trailscape_state_test_');
    setStorageDirForTesting(tempDir);
    SharedPreferences.setMockInitialValues({});
  });

  tearDown(() async {
    if (await tempDir.exists()) {
      await tempDir.delete(recursive: true);
    }
  });

  group('addRides', () {
    test('speichert mehrere Touren, ohne die Auswahl zu ändern', () async {
      final state = AppState();
      await state.addRide(_localRide('vorhanden', 1000));
      expect(state.selected?.id, 'vorhanden');

      await state.addRides([
        _localRide('a', 2000),
        _localRide('b', 3000),
      ]);

      expect(state.rides.map((r) => r.id).toSet(), {'vorhanden', 'a', 'b'});
      // Die Auswahl bleibt unverändert - Bulk-Import wählt nichts aus.
      expect(state.selected?.id, 'vorhanden');
    });

    test('leere Liste tut nichts und lädt nicht neu', () async {
      final state = AppState();
      await state.loadRides();
      expect(state.rides, isEmpty);

      await state.addRides(const []);
      expect(state.rides, isEmpty);
    });
  });

  group('autoSyncHealth', () {
    test('importiert und cached Vitals, wenn Health Connect bereit ist',
        () async {
      final start = _at(2026, 8, 1, 9);
      final gateway = _FakeGateway(
        workouts: [
          _cycling(
            id: 'watch1',
            start: start,
            end: start.add(const Duration(hours: 1)),
          ),
        ],
      );
      final state = AppState(
        healthSync: HealthSyncService(
          gateway: gateway,
          now: () => _at(2026, 8, 10),
        ),
      );
      await state.loadRides();

      var notified = 0;
      state.addListener(() => notified++);

      await state.autoSyncHealth();

      expect(state.rides.map((r) => r.id), contains('hc-watch1'));
      expect(state.vitals, isNotNull);
      expect(notified, greaterThan(0));
      // Bulk-Import waehlt keine Tour aus.
      expect(state.selected, isNull);
    });

    test('fragt keine Berechtigungen an und importiert nichts ohne sie',
        () async {
      final gateway = _FakeGateway(permissionsGranted: false);
      final state = AppState(
        healthSync: HealthSyncService(gateway: gateway),
      );
      await state.loadRides();

      await state.autoSyncHealth();

      expect(state.rides, isEmpty);
      expect(state.vitals, isNull);
    });

    test('schluckt Fehler beim Import still, ohne zu werfen', () async {
      final gateway = _FakeGateway(failWorkouts: true);
      final state = AppState(
        healthSync: HealthSyncService(gateway: gateway),
      );
      await state.loadRides();

      await expectLater(state.autoSyncHealth(), completes);
      expect(state.rides, isEmpty);
      expect(state.vitals, isNull);
    });

    test('nicht installiertes Health Connect bleibt folgenlos', () async {
      final gateway = _FakeGateway(
        availabilityValue: HealthAvailability.nichtInstalliert,
      );
      final state = AppState(
        healthSync: HealthSyncService(gateway: gateway),
      );

      await expectLater(state.autoSyncHealth(), completes);
      expect(state.vitals, isNull);
    });
  });

  group('syncHealthNow', () {
    test('liefert die Anzahl importierter Touren und aktualisiert Vitals',
        () async {
      final start = _at(2026, 8, 1, 9);
      final gateway = _FakeGateway(
        workouts: [
          _cycling(
            id: 'w1',
            start: start,
            end: start.add(const Duration(hours: 1)),
          ),
        ],
      );
      final state = AppState(
        healthSync: HealthSyncService(
          gateway: gateway,
          now: () => _at(2026, 8, 10),
        ),
      );
      await state.loadRides();

      final count = await state.syncHealthNow();

      expect(count, 1);
      expect(state.rides, hasLength(1));
      expect(state.vitals, isNotNull);
    });

    test('wirft HealthSyncException weiter, statt sie zu verschlucken',
        () async {
      final gateway = _FakeGateway(
        availabilityValue: HealthAvailability.nichtInstalliert,
      );
      final state = AppState(
        healthSync: HealthSyncService(gateway: gateway),
      );

      expect(
        () => state.syncHealthNow(),
        throwsA(isA<HealthSyncException>()),
      );
    });

    test('reimportAll setzt den Zeitstempel zurück und importiert erneut',
        () async {
      final start = _at(2026, 8, 1, 9);
      final gateway = _FakeGateway(
        workouts: [
          _cycling(
            id: 'w1',
            start: start,
            end: start.add(const Duration(hours: 1)),
          ),
        ],
      );
      final service = HealthSyncService(
        gateway: gateway,
        now: () => _at(2026, 8, 10),
      );
      final state = AppState(healthSync: service);
      await state.loadRides();

      await state.syncHealthNow();
      expect(await service.lastImportAt(), isNotNull);

      // Zweiter Lauf ohne reimportAll findet nichts Neues mehr (Workout
      // bereits als bestehende Tour vorhanden).
      final second = await state.syncHealthNow();
      expect(second, 0);

      // Mit reimportAll wird wieder ab dem 90-Tage-Fenster gesucht - die
      // Tour ist aber jetzt schon lokal vorhanden (ID-Dedupe) und wird daher
      // wieder übersprungen.
      final third = await state.syncHealthNow(reimportAll: true);
      expect(third, 0);
      expect(state.rides, hasLength(1));
    });
  });
}
