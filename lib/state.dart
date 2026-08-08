/// Zentraler, in-memory gehaltener App-Zustand.
///
/// Hält die geladenen Touren sowie die aktuell ausgewählte Tour und
/// benachrichtigt Listener (siehe [ChangeNotifier]) bei jeder Änderung, damit
/// die UI (Karte, Tourenliste, ...) synchron bleibt.
///
/// Hält außerdem die zuletzt gelesenen Health-Connect-Vitaldaten, den letzten
/// Import-Bericht und das Trainingsprofil vor — und daraus abgeleitet die
/// komplette Trainingsauswertung (Tourlasten, Fitness-Kurve, Erholung,
/// Tagesempfehlung). Diese Auswertung wird **gecacht** und nur bei einer
/// Änderung von Touren, Vitaldaten oder Profil neu berechnet, nicht bei jedem
/// `build`.
library;

import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'health_sync.dart';
import 'models.dart';
import 'storage.dart';
import 'training_load.dart';

/// SharedPreferences-Schlüssel des Trainingsprofils.
const String profileStorageKey = 'trailscape.profile';

/// Profil, solange die Nutzerin noch nichts eingetragen hat.
const TrainingProfile defaultTrainingProfile = TrainingProfile(ageYears: 40);

/// Fenster der Vitaldaten in Tagen.
///
/// Der Rechenkern braucht für die Ruhepuls-Baseline die Tage −8 … −60 (≥ 21
/// Werte) und für die Schlaf-Baseline 28 Nächte — deshalb deutlich mehr als
/// die 14 Tage, die die reine Trendanzeige früher brauchte.
const int vitalsWindowDays = 60;

/// Ziel-Rampenrate (CTL-Punkte pro Woche) für das empfohlene Wochenziel.
const double defaultTargetRampPerWeek = 4;

/// Gebündelte, gecachte Trainingsauswertung.
///
/// Wird von [AppState.insights] geliefert und komplett neu berechnet, sobald
/// sich Touren, Vitaldaten oder Profil ändern.
class TrainingInsights {
  const TrainingInsights({
    required this.profile,
    required this.rideLoads,
    required this.calibration,
    required this.fitness,
    required this.restingHr,
    required this.hrv,
    required this.sleep,
    required this.readiness,
    required this.readinessLast7,
    required this.recommendation,
    required this.deload,
    required this.weeklyTarget,
    required this.vo2max,
    required this.weeklyLoad,
    required this.fourWeekMeanWeeklyLoad,
  });

  /// Effektiv benutztes Profil (Nutzerprofil plus gemessener Ruhepuls, falls
  /// die Nutzerin keinen eigenen Wert hinterlegt hat).
  final TrainingProfile profile;

  /// Tourlast je Tour-ID, bereits mit der Kalibrierung α skaliert.
  final Map<String, RideLoad> rideLoads;
  final LoadCalibration calibration;
  final FitnessSeries fitness;
  final RestingHrAssessment restingHr;
  final HrvAssessment hrv;
  final SleepAssessment sleep;
  final Readiness readiness;

  /// Rückwirkend berechnete Readiness der letzten sieben Tage (nur Tage mit
  /// Gesamtscore) — Grundlage des Deload-Triggers.
  final List<double> readinessLast7;
  final DailyRecommendation recommendation;
  final DeloadRecommendation deload;

  /// Empfohlene Wochenlast für [defaultTargetRampPerWeek]; `null`, solange
  /// keine Tageswerte vorliegen.
  final WeeklyLoadTarget? weeklyTarget;
  final Vo2MaxEstimate vo2max;

  /// Summe der Tageslasten der letzten 7 Tage.
  final double weeklyLoad;

  /// Auf eine Woche hochgerechneter Mittelwert der letzten (bis zu) 4 Wochen;
  /// `null`, solange keine Tageswerte vorliegen.
  final double? fourWeekMeanWeeklyLoad;

  /// Aktuellster Punkt der Fitness-Kurve.
  FitnessPoint? get latest => fitness.latest;
}

class AppState extends ChangeNotifier {
  AppState({HealthSyncService? healthSync})
      : healthSync = healthSync ?? HealthSyncService();

  /// Zugriff auf Health Connect. Wird von der UI (Mehr-Screen) direkt für
  /// Status, Verbindungsaufbau und manuellen Sync benutzt.
  final HealthSyncService healthSync;

  List<Ride> rides = [];
  Ride? selected;

  /// Zuletzt gelesene Vitaldaten (Ruhepuls, Schlaf, ...), `null` solange noch
  /// nie erfolgreich gelesen wurde.
  VitalsSummary? vitals;

  /// Bericht des letzten Health-Connect-Imports — Grundlage der Diagnose im
  /// Mehr-Tab. `null`, solange in dieser Sitzung noch kein Import lief.
  HealthSyncReport? lastSyncReport;

  TrainingProfile _profile = defaultTrainingProfile;

  /// Vom Nutzer gepflegtes Trainingsprofil (Alter, Gewicht, Overrides).
  TrainingProfile get profile => _profile;

  /// Cache der Tourlasten vor Kalibrierung, Schlüssel siehe [_loadKey].
  final Map<String, RideLoad> _baseLoadCache = {};

  TrainingInsights? _insights;

  // -------------------------------------------------------------------------
  // Touren
  // -------------------------------------------------------------------------

  /// Lädt alle gespeicherten Touren neu und benachrichtigt Listener.
  ///
  /// Die Auswahl bleibt erhalten: sie wird anhand der ID auf das neu geladene
  /// Objekt umgehängt (wichtig, wenn eine Tour z. B. durch den HF-Merge
  /// ersetzt wurde) und nur aufgehoben, wenn es die Tour nicht mehr gibt.
  Future<void> loadRides() async {
    rides = await listRides();
    final selectedId = selected?.id;
    if (selectedId != null) {
      Ride? match;
      for (final ride in rides) {
        if (ride.id == selectedId) {
          match = ride;
          break;
        }
      }
      selected = match;
    }
    _invalidateInsights();
    notifyListeners();
  }

  /// Speichert eine neue Tour, lädt die Liste neu und wählt sie aus.
  Future<void> addRide(Ride ride) async {
    await saveRide(ride);
    await loadRides();
    select(ride);
  }

  /// Speichert mehrere Touren, ohne die Auswahl zu ändern (z. B. beim
  /// Health-Connect-Import, wo keine einzelne Tour im Fokus steht). Lädt die
  /// Liste nur neu, wenn tatsächlich etwas gespeichert wurde.
  Future<void> addRides(List<Ride> newRides) async {
    if (newRides.isEmpty) {
      return;
    }
    await saveRides(newRides);
    await loadRides();
  }

  /// Löscht eine Tour, lädt die Liste neu und hebt die Auswahl auf, falls die
  /// gelöschte Tour ausgewählt war.
  Future<void> removeRide(String id) async {
    await deleteRide(id);
    await loadRides();
    if (selected?.id == id) {
      select(null);
    }
  }

  /// Setzt die ausgewählte Tour (oder hebt die Auswahl mit `null` auf).
  void select(Ride? ride) {
    selected = ride;
    notifyListeners();
  }

  // -------------------------------------------------------------------------
  // Trainingsprofil
  // -------------------------------------------------------------------------

  /// Lädt das gespeicherte Profil. Fehlt es oder ist es defekt, bleibt
  /// [defaultTrainingProfile] stehen.
  Future<void> loadProfile() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(profileStorageKey);
      if (raw == null) {
        return;
      }
      final parsed = jsonDecode(raw);
      if (parsed is! Map<String, dynamic>) {
        return;
      }
      _profile = TrainingProfile.fromJson(parsed);
      _invalidateInsights();
      notifyListeners();
    } catch (_) {
      // Speicher nicht verfügbar oder Daten defekt – Default behalten.
    }
  }

  /// Übernimmt ein neues Profil, speichert es und verwirft alle daraus
  /// abgeleiteten Werte.
  Future<void> setProfile(TrainingProfile profile) async {
    _profile = profile;
    _invalidateInsights();
    notifyListeners();
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(profileStorageKey, jsonEncode(profile.toJson()));
    } catch (_) {
      // Speicher nicht verfügbar – Profil bleibt nur im Arbeitsspeicher.
    }
  }

  // -------------------------------------------------------------------------
  // Health Connect
  // -------------------------------------------------------------------------

  /// Einmaliger, stiller Hintergrund-Sync beim App-Start.
  ///
  /// Fragt **nie** Berechtigungen an (kein Dialog beim Start) — importiert
  /// nur, wenn Health Connect bereits verbunden ist. Speichert neu gefundene
  /// und um Herzfrequenz angereicherte Touren und aktualisiert die gecachten
  /// Vitaldaten. Fehler werden verschluckt, damit ein Health-Connect-Problem
  /// den App-Start nie blockiert oder stört.
  Future<void> autoSyncHealth() async {
    try {
      final connection = await healthSync.checkAvailability();
      if (!connection.isReady) {
        return;
      }
      final report = await healthSync.importWithReport(existing: rides);
      await _applyReport(report);
      vitals = await healthSync.readVitals(days: vitalsWindowDays);
      _invalidateInsights();
      notifyListeners();
    } catch (_) {
      // Hintergrund-Sync darf die App nie stören.
    }
  }

  /// Manueller Sync, ausgelöst über den Mehr-Screen.
  ///
  /// Wenn [reimportAll] gesetzt ist, wird der gespeicherte Import-Zeitstempel
  /// zuerst gelöscht (nächster Import betrachtet dann wieder das volle
  /// 30-Tage-Fenster). Liefert die Anzahl neu importierter Touren; der
  /// vollständige Bericht steht danach unter [lastSyncReport]. Wirft
  /// [HealthSyncException] mit einer für die UI geeigneten Meldung, wenn
  /// Health Connect nicht bereit ist oder der Import fehlschlägt — anders als
  /// [autoSyncHealth] wird der Fehler hier nicht verschluckt, damit die UI ihn
  /// anzeigen kann.
  Future<int> syncHealthNow({bool reimportAll = false}) async {
    if (reimportAll) {
      await healthSync.setLastImportAt(null);
    }
    final report = await healthSync.importWithReport(existing: rides);
    await _applyReport(report);
    vitals = await healthSync.readVitals(days: vitalsWindowDays);
    _invalidateInsights();
    notifyListeners();
    return report.imported.length;
  }

  /// Merkt sich den Bericht und persistiert alles, was er verändert hat:
  /// neue Touren **und** bestehende Touren, die um Watch-Herzfrequenz
  /// angereichert wurden (gleiche ID, [saveRide] überschreibt sie).
  Future<void> _applyReport(HealthSyncReport report) async {
    lastSyncReport = report;
    if (report.isEmpty) {
      return;
    }
    await saveRides(report.imported);
    await saveRides(report.mergedRides);
    await loadRides();
  }

  // -------------------------------------------------------------------------
  // Abgeleitete Trainingsauswertung (gecacht)
  // -------------------------------------------------------------------------

  /// Verwirft die abgeleiteten Werte; sie werden beim nächsten Zugriff auf
  /// [insights] neu berechnet.
  void _invalidateInsights() {
    _insights = null;
  }

  /// Effektiv benutztes Profil: fehlt ein eigener Ruhepuls, wird der aus den
  /// Vitaldaten gemessene Median eingesetzt.
  TrainingProfile get effectiveProfile {
    if (_profile.restingHrOverride != null) {
      return _profile;
    }
    final series = vitals?.restingHeartRate.series;
    if (series == null || series.isEmpty) {
      return _profile;
    }
    final baseline = median(series.map((v) => v.value));
    if (baseline == null) {
      return _profile;
    }
    return _profile.copyWith(restingHrOverride: baseline);
  }

  /// Gesamte Trainingsauswertung. Wird beim ersten Zugriff nach einer
  /// Änderung berechnet und danach bis zur nächsten Änderung wiederverwendet.
  TrainingInsights get insights => _insights ??= _computeInsights();

  FitnessSeries get fitnessSeries => insights.fitness;
  Readiness get readiness => insights.readiness;
  DailyRecommendation get todayRecommendation => insights.recommendation;
  DeloadRecommendation get deload => insights.deload;
  LoadCalibration get loadCalibration => insights.calibration;

  /// Trainingslast einer einzelnen Tour; `null`, wenn die Tour nicht (mehr)
  /// im Zustand liegt.
  RideLoad? rideLoad(String rideId) => insights.rideLoads[rideId];

  /// Cache-Schlüssel einer Tourlast: Tour-Identität inklusive der Teile, die
  /// sich nachträglich ändern können (HF-Merge ergänzt Punkte und Ø-Puls),
  /// plus das komplette Profil.
  String _loadKey(Ride ride, String profileSignature) =>
      '${ride.id}|${ride.createdAt}|${ride.points.length}|'
      '${ride.stats.avgHrBpm ?? '-'}|$profileSignature';

  TrainingInsights _computeInsights() {
    final now = DateTime.now();
    final profile = effectiveProfile;
    final profileSignature = jsonEncode(profile.toJson());

    final ordered = [...rides]
      ..sort((a, b) => a.createdAt.compareTo(b.createdAt));

    // Rohlasten je Tour — nur für neue/geänderte Touren wirklich gerechnet.
    final base = <String, RideLoad>{};
    for (final ride in ordered) {
      final key = _loadKey(ride, profileSignature);
      base[key] = _baseLoadCache[key] ?? computeRideLoadForRide(ride, profile);
    }
    _baseLoadCache
      ..clear()
      ..addAll(base);

    // Kalibrierung α aus allen Touren, für die beide Lastpfade tragen.
    final samples = <LoadCalibrationSample>[];
    for (final ride in ordered) {
      final load = base[_loadKey(ride, profileSignature)]!;
      if (load.heartRate.available &&
          load.heartRate.load > 0 &&
          load.physics.available &&
          load.physics.eTss > 0) {
        samples.add(LoadCalibrationSample(
          loadHr: load.heartRate.load,
          loadPhysics: load.physics.eTss,
        ));
      }
    }
    final calibration = computeLoadCalibration(samples);

    final rideLoads = <String, RideLoad>{};
    for (final ride in ordered) {
      rideLoads[ride.id] = _calibrated(
        base[_loadKey(ride, profileSignature)]!,
        calibration,
      );
    }

    final fitness = computeFitnessSeries(
      dailyLoadsFrom(ordered.map((ride) => (
            at: DateTime.fromMillisecondsSinceEpoch(ride.createdAt),
            load: rideLoads[ride.id]!.load,
          ))),
      until: now,
    );

    final restingHrSeries =
        vitals?.restingHeartRate.series ?? const <DailyValue>[];
    final sleepSeries = vitals?.sleepHours.series ?? const <DailyValue>[];
    final hrvSeries =
        vitals?.heartRateVariability.series ?? const <DailyValue>[];

    final restingHr = assessRestingHeartRate(restingHrSeries, today: now);
    // Reihenfolge ist verbindlich: HRV- und Schlafampel kennen die
    // Ruhepuls-Ampel (Sättigungsfall bzw. rote Schlafstufe).
    final hrv = assessHrv(
      hrvSeries,
      today: now,
      restingHrFlag: restingHr.flag,
    );
    final sleep = assessSleep(
      sleepSeries,
      today: now,
      restingHrFlag: restingHr.flag,
    );

    final tsb = fitness.latest?.tsb;
    final readiness = computeReadiness(
      restingHr: restingHr,
      sleep: sleep,
      hrv: hrv,
      tsb: tsb,
      trainingHistoryDays: fitness.historyDays,
    );
    final recommendation = recommendToday(readiness: readiness, tsb: tsb);

    final weeklyLoad = _sumLastDays(fitness, 7);
    final coveredDays = math.min(fitness.historyDays, 28);
    final fourWeekMean =
        coveredDays > 0 ? _sumLastDays(fitness, 28) * 7 / coveredDays : null;

    // Readiness wird nicht persistiert, sondern aus den vorliegenden
    // Vitalserien rückwirkend nachgerechnet — damit greift der Deload-Trigger
    // „Readiness < 40 an ≥ 3 von 7 Tagen" ohne zusätzlichen Speicher.
    final readinessLast7 = availableReadinessScores(computeReadinessSeries(
      restingHrSeries: restingHrSeries,
      sleepSeries: sleepSeries,
      hrvSeries: hrvSeries,
      fitness: fitness,
      today: now,
    ));

    final deload = assessDeload(
      fitness,
      readinessLast7: readinessLast7,
      weeklyLoad: fitness.points.isEmpty ? null : weeklyLoad,
      fourWeekMeanWeeklyLoad: fourWeekMean,
    );

    final latest = fitness.latest;
    final weeklyTarget = latest == null
        ? null
        : weeklyLoadTarget(
            ctl: latest.ctl,
            targetRamp: defaultTargetRampPerWeek,
            recentWeeklyMean: fourWeekMean,
            weeklyHours: profile.weeklyHours,
          );

    return TrainingInsights(
      profile: profile,
      rideLoads: rideLoads,
      calibration: calibration,
      fitness: fitness,
      restingHr: restingHr,
      hrv: hrv,
      sleep: sleep,
      readiness: readiness,
      readinessLast7: readinessLast7,
      recommendation: recommendation,
      deload: deload,
      weeklyTarget: weeklyTarget,
      vo2max: _estimateVo2max(ordered, rideLoads, profile),
      weeklyLoad: weeklyLoad,
      fourWeekMeanWeeklyLoad: fourWeekMean,
    );
  }

  /// Skaliert die Physiklast mit α. Bei geklemmter Kalibrierung ist α = 1,0 —
  /// dann bleibt die Rohlast unverändert.
  RideLoad _calibrated(RideLoad base, LoadCalibration calibration) {
    if (base.source != LoadSource.physik || calibration.alpha == 1.0) {
      return base;
    }
    return RideLoad(
      load: math.min(calibration.alpha * base.physics.eTss, maxLoad),
      source: base.source,
      confidence: base.confidence,
      heartRate: base.heartRate,
      physics: base.physics,
      note: base.note,
    );
  }

  double _sumLastDays(FitnessSeries series, int days) =>
      series.lastDays(days).fold(0.0, (sum, point) => sum + point.load);

  /// VO2max aus den jüngsten Touren mit brauchbarer Leistungsreihe; die
  /// Plattform (Samsung Health) gewinnt, falls sie einen Wert liefert.
  Vo2MaxEstimate _estimateVo2max(
    List<Ride> ordered,
    Map<String, RideLoad> loads,
    TrainingProfile profile,
  ) {
    final segments = <SteadySegment>[];
    if (vitals?.vo2max == null) {
      // Höchstens die 20 jüngsten Touren betrachten, damit die Auswertung
      // auch bei langer Historie schnell bleibt.
      var scanned = 0;
      for (final ride in ordered.reversed) {
        if (scanned >= 20 || segments.length >= 12) {
          break;
        }
        scanned++;
        final physics = loads[ride.id]?.physics;
        if (physics == null || !physics.available) {
          continue;
        }
        segments.addAll(extractSteadySegments(physics.series, profile));
      }
    }
    return estimateVo2Max(
      profile: profile,
      segments: segments,
      platformValue: vitals?.vo2max,
    );
  }
}
