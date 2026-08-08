import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:trailscape/health_sync.dart';
import 'package:trailscape/models.dart';
import 'package:trailscape/state.dart';
import 'package:trailscape/storage.dart';
import 'package:trailscape/training_load.dart';

/// Schlanke Attrappe der Health-Plattform für die AppState-Integration.
/// Deckt nur ab, was [AppState] tatsächlich benutzt (siehe health_sync_test.dart
/// für eine vollständigere Attrappe, die die Sync-Logik selbst testet).
class _FakeGateway implements HealthGateway {
  _FakeGateway({
    this.availabilityValue = HealthAvailability.verfuegbar,
    this.permissionsGranted = true,
    this.workouts = const [],
    this.failWorkouts = false,
    this.heartRate = const [],
    this.restingHeartRate = const [],
    this.hrv = const [],
  });

  HealthAvailability availabilityValue;
  bool permissionsGranted;
  List<HealthWorkout> workouts;
  bool failWorkouts;
  List<HealthHeartRateSample> heartRate;
  List<HealthNumericSample> restingHeartRate;
  List<HealthNumericSample> hrv;

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
  HealthWorkoutReadDiagnostics? get lastWorkoutDiagnostics => null;

  @override
  Future<List<HealthSessionInfo>> readExerciseSessionsNative({
    required DateTime from,
    required DateTime to,
  }) async =>
      const [];

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
      heartRate;

  @override
  Future<List<HealthNumericSample>> readRestingHeartRate({
    required DateTime from,
    required DateTime to,
  }) async =>
      restingHeartRate;

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

  @override
  Future<List<HealthNumericSample>> readHrv({
    required DateTime from,
    required DateTime to,
  }) async =>
      hrv;
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

/// Tour ohne Trackpunkte, aber mit Distanz und Dauer — für die Lastberechnung
/// reicht das für die heuristische Stufe.
Ride _heuristicRide(String id, DateTime start) => Ride(
      id: id,
      name: 'Tour ohne Punkte',
      createdAt: start.millisecondsSinceEpoch,
      points: const [],
      stats: const RideStats(
        distanceKm: 42,
        durationS: 5400,
        movingTimeS: 5400,
        ascentM: 350,
        descentM: 350,
      ),
    );

/// Tour mit Zeitstempeln (nötig, damit der Import eine Überlappung erkennt)
/// und ohne Herzfrequenz — Kandidat für den HF-Merge.
Ride _ridewithPoints(String id, DateTime start) => Ride(
      id: id,
      name: 'Handy-Tour',
      createdAt: start.millisecondsSinceEpoch,
      points: [
        for (var i = 0; i < 3; i++)
          TrackPoint(
            lat: 48 + i * 0.001,
            lon: 11,
            ele: 500 + i * 5,
            time: start.add(Duration(minutes: 10 * i)).millisecondsSinceEpoch,
          ),
      ],
      stats: const RideStats(
        distanceKm: 30,
        durationS: 1200,
        movingTimeS: 1200,
        ascentM: 120,
        descentM: 110,
      ),
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

    test('persistiert angereicherte Touren (mergedRides) unter gleicher ID',
        () async {
      final start = _at(2026, 8, 1, 10);
      final gateway = _FakeGateway(
        workouts: [
          _cycling(
            id: 'watch',
            start: start,
            end: start.add(const Duration(minutes: 25)),
          ),
        ],
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
      final state = AppState(
        healthSync: HealthSyncService(
          gateway: gateway,
          now: () => _at(2026, 8, 10),
        ),
      );
      await state.addRide(_ridewithPoints('lokal', start));
      expect(state.rides.single.stats.avgHrBpm, isNull);

      final imported = await state.syncHealthNow();

      // Keine neue Tour, sondern die bestehende angereichert.
      expect(imported, 0);
      expect(state.rides, hasLength(1));
      expect(state.rides.single.id, 'lokal');
      expect(state.rides.single.stats.avgHrBpm, 150);
      expect(state.rides.single.points.map((p) => p.hr), [120, 150, 180]);

      // Und zwar dauerhaft: frisch von der Platte gelesen.
      final reloaded = await listRides();
      expect(reloaded.single.stats.avgHrBpm, 150);

      // Die Auswahl überlebt das Überschreiben und zeigt auf die neue Fassung.
      expect(state.selected?.id, 'lokal');
      expect(state.selected?.stats.avgHrBpm, 150);
    });

    test('cached den letzten Import-Bericht für die Diagnose-UI', () async {
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
      expect(state.lastSyncReport, isNull);

      await state.syncHealthNow();

      final report = state.lastSyncReport;
      expect(report, isNotNull);
      expect(report!.workoutsFound, 1);
      expect(report.imported, hasLength(1));
      expect(report.mergedRides, isEmpty);
      expect(report.duplicatesSkipped, 0);
      // Ohne Route liefert Health Connect keine Punkte.
      expect(report.routesMissing, 1);

      // Zweiter Lauf überschreibt den Bericht.
      await state.syncHealthNow();
      expect(state.lastSyncReport!.imported, isEmpty);
      expect(state.lastSyncReport!.duplicatesSkipped, 1);
    });
  });

  group('Trainingsprofil', () {
    test('Default gilt, solange nichts gespeichert ist', () async {
      final state = AppState();
      await state.loadProfile();
      expect(state.profile.ageYears, defaultTrainingProfile.ageYears);
      expect(state.profile.setupMassKg, defaultSetupMassKg);
    });

    test('Roundtrip über SharedPreferences', () async {
      final state = AppState();
      var notified = 0;
      state.addListener(() => notified++);

      await state.setProfile(const TrainingProfile(
        ageYears: 38,
        sex: Sex.weiblich,
        weightKg: 64.5,
        setupMassKg: 14,
        hrMaxOverride: 189,
        lthrOverride: 168,
        restingHrOverride: 48,
      ));

      expect(notified, greaterThan(0));

      final wieder = AppState();
      await wieder.loadProfile();
      expect(wieder.profile.ageYears, 38);
      expect(wieder.profile.sex, Sex.weiblich);
      expect(wieder.profile.weightKg, 64.5);
      expect(wieder.profile.setupMassKg, 14);
      expect(wieder.profile.hrMaxOverride, 189);
      expect(wieder.profile.lthrOverride, 168);
      expect(wieder.profile.restingHrOverride, 48);
    });

    test('defekte Daten lassen das Default-Profil stehen', () async {
      SharedPreferences.setMockInitialValues({
        profileStorageKey: 'kein json',
      });
      final state = AppState();
      await state.loadProfile();
      expect(state.profile.ageYears, defaultTrainingProfile.ageYears);
    });
  });

  group('abgeleitete Trainingsauswertung', () {
    test('liefert je Tour eine Last und cached sie zwischen Zugriffen',
        () async {
      final state = AppState();
      await state.addRide(_heuristicRide('a', _at(2026, 8, 1)));

      final first = state.insights;
      expect(first.rideLoads.keys, contains('a'));
      expect(state.rideLoad('a')!.available, isTrue);
      // Heuristik: ohne Punkte bleiben nur Distanz, Dauer und Höhenmeter.
      expect(state.rideLoad('a')!.source, LoadSource.heuristik);

      // Zweiter Zugriff liefert exakt dasselbe Objekt (nicht neu gerechnet).
      expect(identical(state.insights, first), isTrue);
    });

    test('Profiländerung verwirft die gecachte Auswertung', () async {
      final state = AppState();
      await state.addRide(_heuristicRide('a', _at(2026, 8, 1)));
      final first = state.insights;

      await state.setProfile(const TrainingProfile(ageYears: 25, weightKg: 90));

      expect(identical(state.insights, first), isFalse);
      expect(state.insights.profile.ageYears, 25);
    });

    test('neue Tour verwirft die gecachte Auswertung', () async {
      final state = AppState();
      await state.addRide(_heuristicRide('a', _at(2026, 8, 1)));
      final first = state.insights;

      await state.addRide(_heuristicRide('b', _at(2026, 8, 2)));

      expect(identical(state.insights, first), isFalse);
      expect(state.insights.rideLoads.keys, containsAll(['a', 'b']));
    });

    test('ohne Daten bleibt alles unauffällig, nichts wirft', () async {
      final state = AppState();
      await state.loadRides();

      final insights = state.insights;
      expect(insights.fitness.points, isEmpty);
      expect(insights.fitness.displayReady, isFalse);
      expect(insights.readiness.available, isFalse);
      expect(insights.readiness.unavailableReason, isNotNull);
      expect(insights.restingHr.available, isFalse);
      expect(insights.sleep.available, isFalse);
      expect(insights.hrv.available, isFalse);
      expect(insights.readinessLast7, isEmpty);
      expect(insights.deload.recommended, isFalse);
      expect(insights.weeklyTarget, isNull);
      expect(insights.calibration.alpha, 1.0);
      expect(state.rideLoad('gibtsnicht'), isNull);
    });

    test('HRV aus Health Connect landet in der Auswertung', () async {
      final now = DateTime.now();
      DateTime dayAgo(int days) =>
          DateTime(now.year, now.month, now.day - days, 3);

      final state = AppState(
        healthSync: HealthSyncService(
          gateway: _FakeGateway(
            restingHeartRate: [
              for (var i = 0; i < 60; i++)
                HealthNumericSample(time: dayAgo(59 - i), value: 50),
            ],
            hrv: [
              for (var i = 0; i < 21; i++)
                HealthNumericSample(time: dayAgo(27 - i), value: 50),
              // Die letzten sieben Nächte brechen ein.
              for (var i = 0; i < 7; i++)
                HealthNumericSample(time: dayAgo(6 - i), value: 35),
            ],
          ),
        ),
      );
      await state.loadRides();
      await state.autoSyncHealth();

      final hrv = state.insights.hrv;
      expect(hrv.available, isTrue);
      expect(hrv.status, HrvStatus.niedrig);
      expect(hrv.currentRmssd, closeTo(35, 0.5));
      expect(state.insights.restingHr.available, isTrue);
      // Ohne Schlafhistorie bleibt der Gesamtscore gesperrt, das Einzelsignal
      // ist trotzdem da.
      expect(state.insights.readiness.available, isFalse);
      expect(state.insights.readiness.hrv.available, isTrue);
    });

    test('Zeitbudget deckelt das Wochenziel', () async {
      final state = AppState();
      await state.addRide(_heuristicRide('a', DateTime.now()));
      await state.setProfile(
        const TrainingProfile(ageYears: 40, weeklyHours: 3),
      );

      final target = state.insights.weeklyTarget!;
      expect(target.weeklyHours, 3);
      expect(target.weeklyLoad, lessThanOrEqualTo(3 * weeklyLoadPerHour));
    });
  });
}
