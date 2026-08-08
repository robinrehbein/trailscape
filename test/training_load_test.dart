import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:trailscape/health_sync.dart' show DailyValue;
import 'package:trailscape/models.dart';
import 'package:trailscape/training_load.dart';

// ---------------------------------------------------------------------------
// Testhelfer
// ---------------------------------------------------------------------------

/// Meter pro Breitengrad — passend zum Erdradius in `stats.dart`.
const double _metersPerDegLat = 6371000 * math.pi / 180;

const int _t0 = 1700000000000;

/// Referenzprofil mit gemessenen Ankerwerten, damit alle Erwartungswerte
/// von Hand nachrechenbar sind: HFmax 190, HFruhe 50, LTHR 170, männlich.
const TrainingProfile refProfile = TrainingProfile(
  ageYears: 40,
  sex: Sex.maennlich,
  weightKg: 75,
  hrMaxOverride: 190,
  lthrOverride: 170,
  restingHrOverride: 50,
);

/// Baut einen synthetischen Track: konstante Geschwindigkeit, konstante
/// Steigung, optional Höhe und Herzfrequenz.
List<TrackPoint> track({
  required int pointCount,
  double speedMs = 5,
  int stepS = 10,
  double gradeTan = 0,
  double startEle = 100,
  bool withElevation = true,
  int? Function(int index)? hr,
  int startMs = _t0,
}) {
  final points = <TrackPoint>[];
  final stepM = speedMs * stepS;
  for (var i = 0; i < pointCount; i++) {
    final along = i * stepM;
    points.add(TrackPoint(
      lat: 47 + along / _metersPerDegLat,
      lon: 11,
      ele: withElevation ? startEle + gradeTan * along : null,
      time: startMs + i * stepS * 1000,
      hr: hr?.call(i),
    ));
  }
  return points;
}

/// Tagesserie aus Werten, die auf [end] enden (ein Wert pro Kalendertag).
List<DailyValue> daily(List<double> values, {DateTime? end}) {
  final last = end ?? DateTime(2026, 8, 8);
  final out = <DailyValue>[];
  for (var i = 0; i < values.length; i++) {
    final offset = values.length - 1 - i;
    out.add(DailyValue(
      day: DateTime(last.year, last.month, last.day - offset),
      value: values[i],
    ));
  }
  return out;
}

List<DailyLoad> constantLoads(int days, double load, {DateTime? end}) {
  final last = end ?? DateTime(2026, 8, 8);
  return List<DailyLoad>.generate(days, (i) {
    final offset = days - 1 - i;
    return DailyLoad(
      day: DateTime(last.year, last.month, last.day - offset),
      load: load,
    );
  });
}

void main() {
  // -------------------------------------------------------------------------
  group('TrainingProfile', () {
    test('leitet HFmax nach Tanaka ab (208 − 0,7 × Alter)', () {
      const p = TrainingProfile(ageYears: 40);
      expect(p.tanakaHrMax, closeTo(180, 1e-9));
      expect(p.hrMax, closeTo(180, 1e-9));
      expect(p.hrMaxSource, ValueSource.geschaetzt);
    });

    test('leitet LTHR als 0,89 × HFmax ab', () {
      const p = TrainingProfile(ageYears: 40);
      expect(p.lthr, closeTo(0.89 * 180, 1e-9));
      expect(p.lthrSource, ValueSource.geschaetzt);
    });

    test('nutzt Ruhepuls-Default 60 ohne Angabe', () {
      const p = TrainingProfile(ageYears: 40);
      expect(p.restingHr, 60);
      expect(p.hrReserve, closeTo(120, 1e-9));
    });

    test('Overrides gewinnen und heben die Confidence an', () {
      expect(refProfile.hrMax, 190);
      expect(refProfile.lthr, 170);
      expect(refProfile.restingHr, 50);
      expect(refProfile.hrMaxSource, ValueSource.test);
      expect(refProfile.anchorConfidence, Confidence.high);
      expect(const TrainingProfile(ageYears: 40).anchorConfidence,
          Confidence.low);
    });

    test('klemmt eine unplausible LTHR ins Fenster 0,80–0,95 × HFmax', () {
      const low =
          TrainingProfile(ageYears: 40, hrMaxOverride: 190, lthrOverride: 100);
      const high =
          TrainingProfile(ageYears: 40, hrMaxOverride: 190, lthrOverride: 200);
      expect(low.lthr, closeTo(152, 1e-9));
      expect(high.lthr, closeTo(180.5, 1e-9));
    });

    test('Gravel-Physik-Defaults und Massen', () {
      const p = TrainingProfile(ageYears: 40, weightKg: 75);
      expect(p.cda, 0.38);
      expect(p.crr, 0.008);
      expect(p.driveEfficiency, 0.97);
      expect(p.setupMassKg, 12);
      expect(p.totalMassKg, closeTo(87, 1e-9));
    });

    test('eFTP-Default 2,4 W/kg, geklemmt auf 100–400 W', () {
      expect(const TrainingProfile(ageYears: 40, weightKg: 75).eftpW,
          closeTo(180, 1e-9));
      expect(const TrainingProfile(ageYears: 40, weightKg: 30).eftpW, 100);
      expect(const TrainingProfile(ageYears: 40, weightKg: 200).eftpW, 400);
    });

    test('TRIMP-Koeffizienten je Geschlecht; unbekannt → männlicher Satz', () {
      expect(const TrainingProfile(ageYears: 40, sex: Sex.maennlich).trimpA,
          0.64);
      expect(const TrainingProfile(ageYears: 40, sex: Sex.maennlich).trimpB,
          1.92);
      expect(
          const TrainingProfile(ageYears: 40, sex: Sex.weiblich).trimpA, 0.86);
      expect(
          const TrainingProfile(ageYears: 40, sex: Sex.weiblich).trimpB, 1.67);
      expect(const TrainingProfile(ageYears: 40).trimpA, 0.64);
    });

    test('JSON-Roundtrip erhält alle Felder', () {
      final json = jsonDecode(jsonEncode(refProfile.toJson()))
          as Map<String, dynamic>;
      final back = TrainingProfile.fromJson(json);
      expect(back.ageYears, refProfile.ageYears);
      expect(back.sex, Sex.maennlich);
      expect(back.hrMax, refProfile.hrMax);
      expect(back.lthr, refProfile.lthr);
      expect(back.restingHr, refProfile.restingHr);
      expect(back.cda, refProfile.cda);
      expect(back.crr, refProfile.crr);
      expect(back.setupMassKg, refProfile.setupMassKg);
    });

    test('fromJson verkraftet leeres/kaputtes JSON', () {
      final p = TrainingProfile.fromJson(const {});
      expect(p.ageYears, 40);
      expect(p.sex, Sex.unbekannt);
      expect(p.cda, defaultCda);
      final q = TrainingProfile.fromJson(const {'sex': 'quatsch'});
      expect(q.sex, Sex.unbekannt);
    });

    test('copyWith ändert nur die angegebenen Felder', () {
      final p = refProfile.copyWith(weightKg: 80);
      expect(p.weightKg, 80);
      expect(p.hrMax, 190);
    });

    test('Wochen-Zeitbudget: optional, JSON abwärtskompatibel', () {
      expect(refProfile.weeklyHours, isNull);
      // Altes Profil ohne das Feld bleibt gültig.
      expect(TrainingProfile.fromJson(const {'ageYears': 35}).weeklyHours,
          isNull);
      expect(refProfile.toJson().containsKey('weeklyHours'), isFalse);

      final withBudget = refProfile.copyWith(weeklyHours: 6.5);
      final json = jsonDecode(jsonEncode(withBudget.toJson()))
          as Map<String, dynamic>;
      expect(TrainingProfile.fromJson(json).weeklyHours, 6.5);
    });
  });

  // -------------------------------------------------------------------------
  group('Zonenmodell', () {
    final zones = refProfile.zones;

    test('Friel-Grenzen bei LTHR 170', () {
      expect(zones.frielBoundsBpm[0], closeTo(137.7, 1e-9));
      expect(zones.frielBoundsBpm[1], closeTo(153.0, 1e-9));
      expect(zones.frielBoundsBpm[2], closeTo(159.8, 1e-9));
      expect(zones.frielBoundsBpm[3], closeTo(170.0, 1e-9));
    });

    test('Friel-Zuordnung inklusive Grenzfälle', () {
      expect(zones.frielZoneIndex(130), 0);
      expect(zones.frielZoneIndex(137.69), 0);
      expect(zones.frielZoneIndex(137.71), 1);
      expect(zones.frielZoneIndex(152.9), 1);
      expect(zones.frielZoneIndex(153), 2);
      expect(zones.frielZoneIndex(159.8), 3);
      expect(zones.frielZoneIndex(169.9), 3);
      expect(zones.frielZoneIndex(170), 4);
      expect(zones.frielZoneIndex(200), 4);
    });

    test('Lucia LIT/MIT/HIT an den Schwellen', () {
      expect(zones.luciaZoneIndex(144.4), 0);
      expect(zones.luciaZoneIndex(144.5), 1);
      expect(zones.luciaZoneIndex(170), 1);
      expect(zones.luciaZoneIndex(170.1), 2);
    });

    test('Edwards-Zonen in %HFmax, unter 50 % kein Beitrag', () {
      expect(zones.edwardsZoneIndex(90), isNull);
      expect(zones.edwardsZoneIndex(95), 0);
      expect(zones.edwardsZoneIndex(130), 1);
      expect(zones.edwardsZoneIndex(150), 2);
      expect(zones.edwardsZoneIndex(160), 3);
      expect(zones.edwardsZoneIndex(180), 4);
    });

    test('Karvonen als Fallback-Anker', () {
      expect(zones.karvonenHr(0.7), closeTo(50 + 0.7 * 140, 1e-9));
    });

    test('Zonen-Snapshot serialisiert', () {
      final back = TrainingZones.fromJson(zones.toJson());
      expect(back.hrMax, 190);
      expect(back.lthr, 170);
    });
  });

  // -------------------------------------------------------------------------
  group('Banister-TRIMP und hrTSS', () {
    test('Einzel-Sample entspricht der Formel x·a·e^(b·x)', () {
      // x = (130−50)/140 = 0,571428…; k = 0,64·e^(1,92·x)
      final v = trimpSampleContribution(
        hr: 130,
        dtS: 3600,
        profile: refProfile,
      );
      expect(v, closeTo(65.73191188513462, 1e-9));
    });

    test('x wird bei 1,05 geklemmt (HF über HFmax)', () {
      final v =
          trimpSampleContribution(hr: 210, dtS: 600, profile: refProfile);
      expect(v, closeTo(50.4553181005325, 1e-9));
      // Deckelung greift: 300 bpm ergibt denselben Wert.
      expect(trimpSampleContribution(hr: 300, dtS: 600, profile: refProfile),
          closeTo(v, 1e-9));
    });

    test('HF unter Ruhepuls liefert Beitrag 0, nie negativ', () {
      expect(trimpSampleContribution(hr: 40, dtS: 600, profile: refProfile), 0);
      expect(trimpSampleContribution(hr: 50, dtS: 600, profile: refProfile), 0);
    });

    test('dt ≤ 0 liefert 0', () {
      expect(trimpSampleContribution(hr: 150, dtS: 0, profile: refProfile), 0);
      expect(trimpSampleContribution(hr: 150, dtS: -5, profile: refProfile), 0);
    });

    test('TRIMP_ref ist die Stunde an der Schwelle', () {
      expect(trimpReference(refProfile), closeTo(170.651090478826, 1e-9));
    });

    test('1 h an der Schwelle ergibt exakt 100 Punkte', () {
      final trimp =
          trimpSampleContribution(hr: 170, dtS: 3600, profile: refProfile);
      expect(normalizeTrimp(trimp, refProfile), closeTo(100, 1e-9));
    });

    test('60 min bei 130 bpm ergeben 38,5 Punkte', () {
      final points = track(pointCount: 361, hr: (_) => 130);
      final series = buildRideSeries(points, refProfile);
      expect(series.movingTimeS, closeTo(3600, 1e-9));
      final load = computeHeartRateLoad(series, refProfile);
      expect(load.available, isTrue);
      expect(load.trimpBanister, closeTo(65.73191188513462, 1e-6));
      expect(load.load, closeTo(38.518307560003834, 1e-6));
      expect(load.hrCoverage, closeTo(1.0, 1e-9));
      expect(load.avgHr, closeTo(130, 1e-9));
      expect(load.maxHr, 130);
    });

    test('sample-weise Integration liegt über der Ø-HF-Variante (Jensen)', () {
      // 30 min @110 + 30 min @150 vs. 60 min @130 (gleiche Ø-HF).
      final points = track(pointCount: 361, hr: (i) => i <= 180 ? 110 : 150);
      final split = computeHeartRateLoad(
        buildRideSeries(points, refProfile),
        refProfile,
      );
      expect(split.trimpBanister, closeTo(72.78410600605302, 1e-6));
      expect(split.load, closeTo(42.65082971449509, 1e-6));
      expect(split.load, greaterThan(38.52));
    });

    test('Geschlechtskoeffizienten verändern den TRIMP', () {
      final w = refProfile.copyWith(sex: Sex.weiblich);
      final m = trimpSampleContribution(hr: 130, dtS: 3600, profile: refProfile);
      final f = trimpSampleContribution(hr: 130, dtS: 3600, profile: w);
      expect(f, isNot(closeTo(m, 0.5)));
      // 0,86 · e^(1,67·0,571428…) · 0,571428… · 60
      expect(f, closeTo(76.56894668833213, 1e-6));
    });

    test('Edwards-TRIMP summiert Zonenminuten mit 1…5', () {
      // 130/190 = 68,4 % HFmax → Zone 2 (Gewicht 2), 60 min → 120.
      final load = computeHeartRateLoad(
        buildRideSeries(track(pointCount: 361, hr: (_) => 130), refProfile),
        refProfile,
      );
      expect(load.trimpEdwards, closeTo(120, 1e-6));
    });

    test('Intensitätsverteilung in 5 Friel- und 3 Lucia-Zonen', () {
      // 5 Blöcke à 600 s: 130 (Z1/LIT), 150 (Z2/MIT), 155 (Z3/MIT),
      // 165 (Z4/MIT), 175 (Z5/HIT).
      const blockHr = [130, 150, 155, 165, 175];
      final points = track(
        pointCount: 301,
        hr: (i) => blockHr[math.min(4, math.max(0, (i - 1) ~/ 60))],
      );
      final load = computeHeartRateLoad(
        buildRideSeries(points, refProfile),
        refProfile,
      );
      expect(load.frielZones.seconds,
          [closeTo(600, 1), closeTo(600, 1), closeTo(600, 1), closeTo(600, 1), closeTo(600, 1)]);
      expect(load.luciaZones.seconds[0], closeTo(600, 1));
      expect(load.luciaZones.seconds[1], closeTo(1800, 1));
      expect(load.luciaZones.seconds[2], closeTo(600, 1));
      expect(load.luciaZones.fractions[0], closeTo(0.2, 0.01));
      expect(load.secondsAboveLthr, closeTo(600, 1));
      expect(load.trimpLucia, closeTo(1 * 10 + 2 * 30 + 3 * 10, 0.1));
    });

    test('Confidence sinkt ohne Feldtest und ohne Geschlecht', () {
      final points = track(pointCount: 361, hr: (_) => 130);
      final full = computeHeartRateLoad(
        buildRideSeries(points, refProfile),
        refProfile,
      );
      expect(full.confidence, Confidence.high);

      const anon = TrainingProfile(ageYears: 40);
      final weak =
          computeHeartRateLoad(buildRideSeries(points, anon), anon);
      expect(weak.confidence, Confidence.low);
    });

    test('unter 80 % HF-Abdeckung fällt die Tour aus dem HF-Pfad', () {
      final points =
          track(pointCount: 361, hr: (i) => i < 150 ? 130 : null);
      final series = buildRideSeries(points, refProfile);
      final load = computeHeartRateLoad(series, refProfile);
      expect(load.hrCoverage, lessThan(0.8));
      expect(load.available, isFalse);
      expect(load.unavailableReason, contains('Herzfrequenz'));
      expect(load.confidence, Confidence.none);
      // Der TRIMP wird trotzdem berechnet (Transparenz), nur nicht benutzt.
      expect(load.trimpBanister, greaterThan(0));
    });

    test('Lücke > 30 s wird nicht interpoliert', () {
      // 60-s-Abtastung: die HF darf nur noch vom Endpunkt kommen.
      final points = track(
        pointCount: 40,
        stepS: 60,
        hr: (i) => i.isEven ? 150 : null,
      );
      final series = buildRideSeries(points, refProfile);
      final withHr =
          series.segments.where((s) => s.hr != null).length;
      expect(withHr, lessThan(series.segments.length));
      expect(series.hrCoverage, lessThan(0.8));
    });

    test('leere und zu kurze Tracks werfen nicht', () {
      for (final points in [
        <TrackPoint>[],
        [const TrackPoint(lat: 47, lon: 11)],
        [const TrackPoint(lat: 47, lon: 11, time: _t0)],
      ]) {
        final series = buildRideSeries(points, refProfile);
        expect(series.isEmpty, isTrue);
        final load = computeHeartRateLoad(series, refProfile);
        expect(load.available, isFalse);
        expect(load.load, 0);
        expect(load.unavailableReason, isNotNull);
      }
    });

    test('Tour ohne Herzfrequenz meldet den Fehlgrund', () {
      final load = computeHeartRateLoad(
        buildRideSeries(track(pointCount: 100), refProfile),
        refProfile,
      );
      expect(load.available, isFalse);
      expect(load.unavailableReason, contains('keine Herzfrequenz'));
    });

    test('Punkte ohne Zeitstempel ergeben eine leere Serie', () {
      final points = [
        const TrackPoint(lat: 47, lon: 11, ele: 100),
        const TrackPoint(lat: 47.001, lon: 11, ele: 105),
      ];
      expect(buildRideSeries(points, refProfile).isEmpty, isTrue);
    });

    test('unsortierte Punkte werden sortiert', () {
      final points = track(pointCount: 61, hr: (_) => 130).reversed.toList();
      final series = buildRideSeries(points, refProfile);
      expect(series.movingTimeS, closeTo(600, 1e-9));
    });
  });

  // -------------------------------------------------------------------------
  group('Physikmodell', () {
    test('Luftdichte fällt mit der Höhe', () {
      expect(airDensity(0), closeTo(1.225, 1e-9));
      expect(airDensity(1000), lessThan(1.225));
      expect(airDensity(100), closeTo(1.2105629259049062, 1e-9));
    });

    test('Kraftbilanz an einer 5-%-Steigung', () {
      // m = 87 kg, v = 4 m/s, tanθ = 0,05, h = 100 m
      final p = estimateSamplePowerW(
        speedMs: 4,
        accelMs2: 0,
        gradeTan: 0.05,
        elevationM: 100,
        profile: refProfile,
      );
      expect(p, closeTo(218.98031953707047, 1e-6));
    });

    test('flache Fahrt: nur Roll- und Luftwiderstand', () {
      final p = estimateSamplePowerW(
        speedMs: 8,
        accelMs2: 0,
        gradeTan: 0,
        elevationM: 0,
        profile: refProfile,
      );
      expect(p, closeTo(179.1458012371134, 1e-6));
    });

    test('Beschleunigungsterm geht mit m × dv/dt ein', () {
      final p = estimateSamplePowerW(
        speedMs: 5,
        accelMs2: 1,
        gradeTan: 0,
        elevationM: 0,
        profile: refProfile,
      );
      expect(p, closeTo(513.6297855670103, 1e-6));
    });

    test('Bergabfahrt liefert nie negative Leistung', () {
      final p = estimateSamplePowerW(
        speedMs: 10,
        accelMs2: 0,
        gradeTan: -0.20,
        elevationM: 500,
        profile: refProfile,
      );
      expect(p, 0);
    });

    test('Stillstand liefert 0 W', () {
      expect(
        estimateSamplePowerW(
          speedMs: 0,
          accelMs2: 0,
          gradeTan: 0.05,
          elevationM: 0,
          profile: refProfile,
        ),
        0,
      );
    });

    test('gleichmäßige Steigungsfahrt: plausible Leistung, VI ≈ 1', () {
      // 20 min, 4 m/s, 5 % — 1 Hz, damit Glättung und Steigungsfenster greifen.
      final points = track(
        pointCount: 1201,
        speedMs: 4,
        stepS: 1,
        gradeTan: 0.05,
        startEle: 0,
      );
      final series = buildRideSeries(points, refProfile);
      expect(series.hasElevation, isTrue);
      // 1200 s × 4 m/s × 5 % = 240 m Anstieg (Hysterese 3 m).
      expect(series.ascentM, closeTo(240, 6));

      final physics = computePhysicsEstimate(series, refProfile);
      expect(physics.available, isTrue);
      expect(physics.movingTimeS, closeTo(1200, 1));
      // Referenzrechnung bei h = 120 m ≈ 219 W; Randeffekte der Glättung
      // erlauben eine kleine Abweichung.
      expect(physics.avgPowerW, closeTo(219, 12));
      expect(physics.variabilityIndex, closeTo(1.0, 0.05));
      expect(physics.eTss, greaterThan(0));
      expect(physics.kcal, greaterThan(0));
      expect(physics.confidence, Confidence.medium);
      expect(physics.powerText, contains('±15–25 %'));
    });

    test('eTSS folgt Dauer × IF² × 100', () {
      final points = track(
        pointCount: 1201,
        speedMs: 4,
        stepS: 1,
        gradeTan: 0.05,
        startEle: 0,
      );
      final physics = computePhysicsEstimate(
        buildRideSeries(points, refProfile),
        refProfile,
      );
      final hours = physics.movingTimeS / 3600;
      final expected = hours *
          physics.intensityFactor *
          physics.intensityFactor *
          100;
      expect(physics.eTss, closeTo(expected, 1e-6));
    });

    test('flache Fahrt hat weniger Last als dieselbe Zeit bergauf', () {
      final flat = computePhysicsEstimate(
        buildRideSeries(
          track(pointCount: 1201, speedMs: 4, stepS: 1, startEle: 0),
          refProfile,
        ),
        refProfile,
      );
      final climb = computePhysicsEstimate(
        buildRideSeries(
          track(
            pointCount: 1201,
            speedMs: 4,
            stepS: 1,
            gradeTan: 0.05,
            startEle: 0,
          ),
          refProfile,
        ),
        refProfile,
      );
      expect(flat.eTss, lessThan(climb.eTss));
      expect(flat.avgPowerW, lessThan(climb.avgPowerW));
    });

    test('Steigung wird auf ±25 % geklemmt', () {
      final points = track(
        pointCount: 300,
        speedMs: 4,
        stepS: 1,
        gradeTan: 0.60,
        startEle: 0,
      );
      final series = buildRideSeries(points, refProfile);
      for (final s in series.segments) {
        expect(s.gradeTan.abs(), lessThanOrEqualTo(0.2500001));
      }
    });

    test('ohne Höhenprofil ist das Physikmodell nicht berechenbar', () {
      final series = buildRideSeries(
        track(pointCount: 400, stepS: 1, withElevation: false),
        refProfile,
      );
      final physics = computePhysicsEstimate(series, refProfile);
      expect(physics.available, isFalse);
      expect(physics.unavailableReason, contains('Höhenprofil'));
      expect(physics.eTss, 0);
    });

    test('zu kurze Tour ist nicht berechenbar', () {
      final physics = computePhysicsEstimate(
        buildRideSeries(track(pointCount: 5, stepS: 1), refProfile),
        refProfile,
      );
      expect(physics.available, isFalse);
      expect(physics.unavailableReason, isNotNull);
    });

    test('leere Serie wirft nicht', () {
      final physics =
          computePhysicsEstimate(const RideSeries.empty(), refProfile);
      expect(physics.available, isFalse);
      expect(physics.series.isEmpty, isTrue);
    });

    test('eFTP = 0,95 × bestes 20-min-Mittel, geklemmt', () {
      final power = buildPowerSeries(
        buildRideSeries(
          track(
            pointCount: 1501,
            speedMs: 4,
            stepS: 1,
            gradeTan: 0.05,
            startEle: 0,
          ),
          refProfile,
        ),
        refProfile,
      );
      final best = bestRollingMeanPowerW(power);
      expect(best, isNotNull);
      expect(estimateEftpW([power], refProfile), closeTo(0.95 * best!, 1e-6));
      // Ohne 20-min-Material bleibt der Profil-Default.
      expect(estimateEftpW(const [], refProfile), closeTo(180, 1e-9));
      expect(bestRollingMeanPowerW(const PowerSeries.empty()), isNull);
    });
  });

  // -------------------------------------------------------------------------
  group('Kalibrierung α (HF ↔ Physik)', () {
    test('zu wenige Paare → α = 1,0 mit niedriger Confidence', () {
      final c = computeLoadCalibration(const [
        LoadCalibrationSample(loadHr: 100, loadPhysics: 80),
        LoadCalibrationSample(loadHr: 100, loadPhysics: 80),
      ]);
      expect(c.alpha, 1.0);
      expect(c.confidence, Confidence.low);
      expect(c.clamped, isFalse);
    });

    test('Median der Verhältnisse', () {
      final c = computeLoadCalibration(const [
        LoadCalibrationSample(loadHr: 110, loadPhysics: 100), // 1,10
        LoadCalibrationSample(loadHr: 120, loadPhysics: 100), // 1,20
        LoadCalibrationSample(loadHr: 130, loadPhysics: 100), // 1,30
        LoadCalibrationSample(loadHr: 140, loadPhysics: 100), // 1,40
        LoadCalibrationSample(loadHr: 90, loadPhysics: 100), // 0,90
      ]);
      expect(c.alpha, closeTo(1.20, 1e-9));
      expect(c.sampleCount, 5);
      expect(c.clamped, isFalse);
    });

    test('α außerhalb [0,6; 1,6] wird auf 1,0 geklemmt', () {
      final c = computeLoadCalibration(List.generate(
        6,
        (_) => const LoadCalibrationSample(loadHr: 200, loadPhysics: 100),
      ));
      expect(c.alpha, 1.0);
      expect(c.clamped, isTrue);
      expect(c.confidence, Confidence.low);
    });

    test('nur die letzten 20 Paare zählen', () {
      final samples = <LoadCalibrationSample>[
        ...List.generate(
            10, (_) => const LoadCalibrationSample(loadHr: 300, loadPhysics: 100)),
        ...List.generate(
            20, (_) => const LoadCalibrationSample(loadHr: 110, loadPhysics: 100)),
      ];
      final c = computeLoadCalibration(samples);
      expect(c.alpha, closeTo(1.1, 1e-9));
      expect(c.sampleCount, 20);
      expect(c.confidence, Confidence.medium);
    });

    test('unbrauchbare Paare werden verworfen; leere Liste ist neutral', () {
      final c = computeLoadCalibration(const [
        LoadCalibrationSample(loadHr: 0, loadPhysics: 100),
        LoadCalibrationSample(loadHr: 100, loadPhysics: 0),
        LoadCalibrationSample(loadHr: double.nan, loadPhysics: 100),
      ]);
      expect(c.alpha, 1.0);
      expect(c.sampleCount, 0);
      expect(computeLoadCalibration(const []).alpha, 1.0);
    });

    test('JSON-Roundtrip', () {
      final c = computeLoadCalibration(List.generate(
        6,
        (_) => const LoadCalibrationSample(loadHr: 110, loadPhysics: 100),
      ));
      final back = LoadCalibration.fromJson(
        jsonDecode(jsonEncode(c.toJson())) as Map<String, dynamic>,
      );
      expect(back.alpha, closeTo(c.alpha, 1e-9));
      expect(back.confidence, c.confidence);
      expect(const LoadCalibration.neutral().alpha, 1.0);
    });
  });

  // -------------------------------------------------------------------------
  group('Fallback-Kaskade der Tourlast', () {
    test('Stufe A: mit Herzfrequenz gewinnt der HF-Pfad', () {
      final result = computeRideLoad(
        points: track(pointCount: 361, hr: (_) => 130),
        profile: refProfile,
      );
      expect(result.source, LoadSource.herzfrequenz);
      expect(result.load, closeTo(38.518307560003834, 1e-6));
      expect(result.confidence, Confidence.high);
      expect(result.note, contains('Herzfrequenz'));
    });

    test('Stufe B: ohne HF greift das Physikmodell inklusive α', () {
      final points = track(
        pointCount: 1201,
        speedMs: 4,
        stepS: 1,
        gradeTan: 0.05,
        startEle: 0,
      );
      final plain = computeRideLoad(points: points, profile: refProfile);
      expect(plain.source, LoadSource.physik);
      expect(plain.load, greaterThan(0));

      final scaled = computeRideLoad(
        points: points,
        profile: refProfile,
        calibration: const LoadCalibration(
          alpha: 1.25,
          sampleCount: 12,
          clamped: false,
          confidence: Confidence.medium,
        ),
      );
      expect(scaled.load, closeTo(1.25 * plain.load, 1e-6));
    });

    test('Stufe C: ohne HF und ohne Höhe rettet RPE die Tour', () {
      final points = track(pointCount: 400, stepS: 1, withElevation: false);
      final result =
          computeRideLoad(points: points, profile: refProfile, rpe: 6);
      expect(result.source, LoadSource.rpe);
      // 399 s Bewegungszeit ≈ 6,65 min × 6 × 1/6
      expect(result.load, closeTo(399 / 60 * 6 / 6, 1e-6));
      expect(result.confidence, Confidence.low);
    });

    test('Stufe D: reine Distanz-/Höhen-Heuristik', () {
      final result = computeRideLoad(
        points: const [],
        profile: refProfile,
        stats: const RideStats(
          distanceKm: 44,
          durationS: 7200,
          movingTimeS: 7200,
          ascentM: 0,
          descentM: 0,
        ),
      );
      expect(result.source, LoadSource.heuristik);
      // 2 h × 55 × clamp(44/(2×22)) = 110 × 1,0
      expect(result.load, closeTo(110, 1e-9));
      expect(result.confidence, Confidence.low);
      expect(result.note, contains('Schätzung'));
    });

    test('Heuristik-Korrekturterm ist auf 0,7…1,5 geklemmt', () {
      expect(
        heuristicLoad(distanceKm: 200, durationH: 2, ascentM: 0),
        closeTo(2 * 55 * 1.5, 1e-9),
      );
      expect(
        heuristicLoad(distanceKm: 5, durationH: 2, ascentM: 0),
        closeTo(2 * 55 * 0.7, 1e-9),
      );
      // Höhenmeter zählen als 10 km Flachäquivalent je 100 hm.
      expect(
        heuristicLoad(distanceKm: 34, durationH: 2, ascentM: 100),
        closeTo(110, 1e-9),
      );
      expect(
        heuristicLoad(distanceKm: 34, durationH: 2, ascentM: 1000),
        closeTo(2 * 55 * 1.5, 1e-9),
      );
      expect(heuristicLoad(distanceKm: 10, durationH: 0, ascentM: 0), 0);
    });

    test('ohne jede Datengrundlage: Quelle „keine", Last 0', () {
      final result = computeRideLoad(points: const [], profile: refProfile);
      expect(result.source, LoadSource.keine);
      expect(result.available, isFalse);
      expect(result.load, 0);
      expect(result.note, isNotEmpty);
    });

    test('computeRideLoadForRide nutzt Punkte und Stats des Rides', () {
      final points = track(pointCount: 361, hr: (_) => 130);
      final ride = Ride(
        id: 'x',
        name: 'Test',
        createdAt: _t0,
        points: points,
        stats: const RideStats(distanceKm: 18, ascentM: 0, descentM: 0),
      );
      final result = computeRideLoadForRide(ride, refProfile);
      expect(result.source, LoadSource.herzfrequenz);
      expect(result.load, closeTo(38.518307560003834, 1e-6));
    });

    test('Last ist auf 500 gedeckelt', () {
      expect(normalizeTrimp(100000, refProfile), maxLoad);
    });
  });

  // -------------------------------------------------------------------------
  group('CTL / ATL / TSB', () {
    test('λ-Werte entsprechen 42 und 7 Tagen', () {
      expect(lambdaCtl, closeTo(0.023528313347756735, 1e-12));
      expect(lambdaAtl, closeTo(0.1331221002498184, 1e-12));
      expect(ctlWeeklyResponse, closeTo(0.15351827510938576, 1e-12));
    });

    test('drei Tage à 100 Punkte ohne Historie: exakte Rekursion', () {
      final series = computeFitnessSeries(constantLoads(3, 100));
      expect(series.seedLoad, 0);
      expect(series.displayReady, isFalse);
      expect(series.daysUntilDisplayReady, 25);

      final p = series.points;
      expect(p.length, 3);
      expect(p[0].ctl, closeTo(2.3528313347756735, 1e-9));
      expect(p[0].atl, closeTo(13.312210024981841, 1e-9));
      expect(p[0].tsb, closeTo(0, 1e-12));
      expect(p[1].ctl, closeTo(4.650304516652325, 1e-9));
      expect(p[1].atl, closeTo(24.852270692471414, 1e-9));
      expect(p[1].tsb, closeTo(-10.959378690206167, 1e-9));
      expect(p[2].tsb, closeTo(-20.201966175819088, 1e-9));
    });

    test('TSB nutzt die Vortagswerte (TrainingPeaks-Konvention)', () {
      final series = computeFitnessSeries(constantLoads(5, 80));
      final p = series.points;
      for (var i = 1; i < p.length; i++) {
        expect(p[i].tsb, closeTo(p[i - 1].ctl - p[i - 1].atl, 1e-12));
      }
    });

    test('Seeding ab 42 Tagen: CTL startet auf dem Mittel', () {
      final series = computeFitnessSeries(constantLoads(50, 50));
      expect(series.seedLoad, closeTo(50, 1e-9));
      expect(series.latest!.ctl, closeTo(50, 1e-9));
      expect(series.latest!.atl, closeTo(50, 1e-9));
      expect(series.latest!.tsb, closeTo(0, 1e-9));
      expect(series.displayReady, isTrue);
    });

    test('Seeding bei 14–41 Tagen nutzt den verfügbaren Zeitraum', () {
      final series = computeFitnessSeries(constantLoads(20, 30));
      expect(series.seedLoad, closeTo(30, 1e-9));
      expect(series.latest!.ctl, closeTo(30, 1e-9));
      expect(series.historyDays, 20);
    });

    test('unter 14 Tagen wird nicht geseedet und nicht angezeigt', () {
      final series = computeFitnessSeries(constantLoads(13, 90));
      expect(series.seedLoad, 0);
      expect(series.displayReady, isFalse);
      expect(series.daysUntilDisplayReady, 15);
    });

    test('Ruhetage werden als 0 aufgefüllt', () {
      final series = computeFitnessSeries([
        DailyLoad(day: DateTime(2026, 8, 1), load: 100),
        DailyLoad(day: DateTime(2026, 8, 6), load: 50),
      ]);
      expect(series.points.length, 6);
      expect(series.points[1].load, 0);
      expect(series.points[4].load, 0);
      expect(series.points[5].load, 50);
    });

    test('mehrere Touren am selben Tag werden summiert', () {
      final series = computeFitnessSeries([
        DailyLoad(day: DateTime(2026, 8, 1, 9), load: 60),
        DailyLoad(day: DateTime(2026, 8, 1, 17), load: 40),
      ]);
      expect(series.points.single.load, closeTo(100, 1e-9));
    });

    test('`until` verlängert die Serie mit Ruhetagen', () {
      final series = computeFitnessSeries(
        [DailyLoad(day: DateTime(2026, 8, 1), load: 100)],
        until: DateTime(2026, 8, 5),
      );
      expect(series.points.length, 5);
      expect(series.latest!.load, 0);
      expect(series.latest!.atl, lessThan(series.points.first.atl));
    });

    test('Rampenrate erst ab Tag 8, dann CTL_t − CTL_{t−7}', () {
      final series = computeFitnessSeries(constantLoads(30, 100));
      expect(series.points[6].rampRate7d, isNull);
      expect(series.points[7].rampRate7d, isNotNull);
      final p = series.points;
      expect(p[20].rampRate7d, closeTo(p[20].ctl - p[13].ctl, 1e-9));
    });

    test('Rampenrate-Bänder', () {
      expect(classifyRampRate(-1), RampBand.formverlust);
      expect(classifyRampRate(0), RampBand.erhaltung);
      expect(classifyRampRate(2.9), RampBand.erhaltung);
      expect(classifyRampRate(3), RampBand.aufbau);
      expect(classifyRampRate(6), RampBand.aufbau);
      expect(classifyRampRate(7), RampBand.aggressiv);
      expect(classifyRampRate(8), RampBand.aggressiv);
      expect(classifyRampRate(8.1), RampBand.zuSchnell);
    });

    test('TSB-Bänder an den Grenzen', () {
      expect(classifyTsb(26), TsbBand.sehrFrisch);
      expect(classifyTsb(25), TsbBand.formspitze);
      expect(classifyTsb(5), TsbBand.formspitze);
      expect(classifyTsb(4.9), TsbBand.neutral);
      expect(classifyTsb(-10), TsbBand.neutral);
      expect(classifyTsb(-10.1), TsbBand.produktiv);
      expect(classifyTsb(-30), TsbBand.produktiv);
      expect(classifyTsb(-30.1), TsbBand.ueberlastung);
      expect(tsbBandMessages[TsbBand.formspitze], contains('viele Fahrer'));
    });

    test('Belastungsverhältnis: gleichmäßige Last ergibt 1,0', () {
      final series = computeFitnessSeries(constantLoads(60, 50));
      expect(series.latest!.loadRatio, closeTo(1.0, 1e-9));
      expect(classifyLoadRatio(series.latest!.loadRatio), LoadRatioBand.imBand);
    });

    test('Belastungsverhältnis wird bei kleiner chronischer Last unterdrückt',
        () {
      final series = computeFitnessSeries(constantLoads(60, 1));
      expect(series.latest!.loadRatio, isNull);
      expect(classifyLoadRatio(null), LoadRatioBand.unbekannt);
    });

    test('Belastungssprung wird neutral benannt', () {
      final loads = [
        ...constantLoads(60, 40, end: DateTime(2026, 7, 30)),
        ...constantLoads(9, 250, end: DateTime(2026, 8, 8)),
      ];
      final series = computeFitnessSeries(loads);
      expect(series.latest!.loadRatio, greaterThan(loadRatioBandHigh));
      expect(classifyLoadRatio(series.latest!.loadRatio),
          LoadRatioBand.belastungssprung);
      expect(loadRatioLabels[LoadRatioBand.belastungssprung],
          'Belastungssprung');
      expect(
        loadRatioLabels.values.join(' ').toLowerCase(),
        isNot(contains('verletzung')),
      );
    });

    test('Band 0,8–1,5', () {
      expect(classifyLoadRatio(0.79), LoadRatioBand.niedrig);
      expect(classifyLoadRatio(0.8), LoadRatioBand.imBand);
      expect(classifyLoadRatio(1.5), LoadRatioBand.imBand);
      expect(classifyLoadRatio(1.51), LoadRatioBand.belastungssprung);
    });

    test('leere und unsinnige Eingaben ergeben eine leere Serie', () {
      expect(computeFitnessSeries(const []).points, isEmpty);
      expect(
        computeFitnessSeries([
          DailyLoad(day: DateTime(2026, 8, 1), load: double.nan),
          DailyLoad(day: DateTime(2026, 8, 2), load: -5),
        ]).points,
        isEmpty,
      );
      expect(const FitnessSeries.empty().latest, isNull);
    });

    test('at() und lastDays() greifen zu', () {
      final series = computeFitnessSeries(constantLoads(10, 40));
      expect(series.at(DateTime(2026, 8, 8)), isNotNull);
      expect(series.at(DateTime(2020, 1, 1)), isNull);
      expect(series.lastDays(3).length, 3);
      expect(series.lastDays(0), isEmpty);
      expect(series.lastDays(99).length, 10);
    });

    test('dailyLoadsFrom fasst Touren zu Tagessummen zusammen', () {
      final loads = dailyLoadsFrom([
        (at: DateTime(2026, 8, 1, 8), load: 40.0),
        (at: DateTime(2026, 8, 1, 18), load: 30.0),
        (at: DateTime(2026, 8, 3, 12), load: 20.0),
        (at: DateTime(2026, 8, 4, 12), load: double.nan),
      ]);
      expect(loads.length, 2);
      expect(loads.first.load, closeTo(70, 1e-9));
    });
  });

  // -------------------------------------------------------------------------
  group('Wochenziel', () {
    test('Zielrampe → Wochenlast über die EWMA-Rekursion', () {
      final target = weeklyLoadTarget(ctl: 50, targetRamp: 5);
      expect(target.dailyLoad, closeTo(82.56941231548733, 1e-6));
      expect(target.weeklyLoad, closeTo(577.9858862084113, 1e-6));
      expect(target.caps, isEmpty);
    });

    test('130-%-Deckel und Zeitdeckel greifen', () {
      final capped = weeklyLoadTarget(
        ctl: 50,
        targetRamp: 5,
        recentWeeklyMean: 300,
      );
      expect(capped.weeklyLoad, closeTo(390, 1e-9));
      expect(capped.caps.length, 1);

      final both = weeklyLoadTarget(
        ctl: 50,
        targetRamp: 5,
        recentWeeklyMean: 300,
        weeklyHours: 4,
      );
      // 4 h × 58 Last/h = 232 — schärfer als der 130-%-Deckel (390).
      expect(both.weeklyLoad, closeTo(232, 1e-9));
      expect(both.caps.length, 2);
      expect(both.caps.last, contains('Zeitbudget'));
      expect(both.caps.last, contains('4 h'));
    });

    test('Zeitbudget deckelt auf weeklyHours × 58 Last/h', () {
      final target = weeklyLoadTarget(
        ctl: 50,
        targetRamp: 5,
        weeklyHours: 6,
      );
      expect(target.weeklyLoad, closeTo(6 * weeklyLoadPerHour, 1e-9));
      expect(target.weeklyHours, 6);
      expect(target.estimatedHours, closeTo(6, 1e-9));
      expect(target.dailyLoad, closeTo(6 * weeklyLoadPerHour / 7, 1e-9));
    });

    test('großzügiges Zeitbudget greift nicht ein', () {
      final target = weeklyLoadTarget(
        ctl: 50,
        targetRamp: 5,
        weeklyHours: 20,
      );
      expect(target.weeklyLoad, closeTo(577.9858862084113, 1e-6));
      expect(target.caps, isEmpty);
      expect(target.estimatedHours, closeTo(577.9858862084113 / 58, 1e-6));
    });

    test('ohne Zeitbudget bleibt der Zielwert unverändert', () {
      final target = weeklyLoadTarget(ctl: 50, targetRamp: 5);
      expect(target.weeklyHours, isNull);
      expect(target.caps, isEmpty);
    });

    test('unplausibles Zeitbudget (0 oder negativ) wird ignoriert', () {
      expect(
        weeklyLoadTarget(ctl: 50, targetRamp: 5, weeklyHours: 0).caps,
        isEmpty,
      );
      expect(
        weeklyLoadTarget(ctl: 50, targetRamp: 5, weeklyHours: -3).caps,
        isEmpty,
      );
    });

    test('Stundenformat deutsch: ganze Zahl ohne Komma', () {
      expect(formatHours(5), '5');
      expect(formatHours(4.5), '4,5');
      expect(formatHours(4.47), '4,5');
    });

    test('negative Zielrampe erzeugt keine negative Last', () {
      final target = weeklyLoadTarget(ctl: 5, targetRamp: -15);
      expect(target.weeklyLoad, 0);
    });

    test('Ziel-Intensitätsverteilung', () {
      expect(intensityDistributionTarget(), [80, 5, 15]);
      expect(intensityDistributionTarget(polarized: false), [75, 15, 10]);
    });
  });

  // -------------------------------------------------------------------------
  group('Ruhepuls-Bewertung', () {
    // Baseline-Fenster = Tage −60 … −8. 60 Werte reichen dafür sicher.
    List<DailyValue> rhrSeries(List<double> recent5, {double base = 50}) =>
        daily([...List<double>.filled(55, base), ...recent5]);

    test('unter 21 Werten im Baseline-Fenster: deaktiviert', () {
      final a = assessRestingHeartRate(daily(List<double>.filled(20, 50)));
      expect(a.available, isFalse);
      expect(a.flag, RecoveryFlag.unbekannt);
      expect(a.unavailableReason, contains('Baseline wird aufgebaut'));
    });

    test('leere Serie wirft nicht', () {
      final a = assessRestingHeartRate(const []);
      expect(a.available, isFalse);
      expect(a.baselineDays, 0);
    });

    test('stabiler Ruhepuls ist grün', () {
      final a = assessRestingHeartRate(rhrSeries([50, 50, 50, 50, 50]));
      expect(a.available, isTrue);
      expect(a.baseline, closeTo(50, 1e-9));
      expect(a.sigma, closeTo(1.5, 1e-9)); // MAD = 0 → Floor greift
      expect(a.deltaBpm, closeTo(0, 1e-9));
      expect(a.flag, RecoveryFlag.gruen);
    });

    test('exakt an der Gelb-Schwelle: Δ = 3 bpm an zwei Tagen', () {
      final a = assessRestingHeartRate(rhrSeries([50, 50, 50, 53, 53]));
      expect(a.deltaBpm, closeTo(3, 1e-9));
      expect(a.z, closeTo(2, 1e-9));
      expect(a.flag, RecoveryFlag.gelb);
      expect(a.streakDays, greaterThanOrEqualTo(2));
    });

    test('knapp unter der Gelb-Schwelle: Δ = 2,9 bpm bleibt grün', () {
      final a = assessRestingHeartRate(rhrSeries([50, 50, 50, 52.9, 52.9]));
      expect(a.deltaBpm, closeTo(2.9, 1e-9));
      expect(a.flag, RecoveryFlag.gruen);
    });

    test('ein einzelner Ausreißer löst nichts aus (3-Tages-Median)', () {
      final a = assessRestingHeartRate(rhrSeries([50, 50, 50, 50, 58]));
      expect(a.current, closeTo(50, 1e-9));
      expect(a.flag, RecoveryFlag.gruen);
    });

    test('Δ ≥ 3 ohne z ≥ 1 bleibt grün (beide Bedingungen nötig)', () {
      // Streuende Baseline → σ = 1,4826 × 4 = 5,93 → z(Δ=3) ≈ 0,51
      final spread = <double>[];
      for (var i = 0; i < 55; i++) {
        spread.add([44.0, 46.0, 48.0, 50.0, 52.0, 54.0, 56.0][i % 7]);
      }
      final a = assessRestingHeartRate(daily([...spread, 53, 53, 53]));
      expect(a.sigma, closeTo(1.4826 * 4, 1e-6));
      expect(a.deltaBpm, closeTo(3, 1e-9));
      expect(a.z!, lessThan(1.0));
      expect(a.flag, RecoveryFlag.gruen);
    });

    test('exakt an der Orange-Schwelle: Δ = 5 bpm und z ≥ 1,5', () {
      final a = assessRestingHeartRate(rhrSeries([50, 50, 55, 55, 55]));
      expect(a.deltaBpm, closeTo(5, 1e-9));
      expect(a.z, closeTo(5 / 1.5, 1e-9));
      // Drei aufeinanderfolgende Tage ≥ 5 bpm → laut Dokument bereits rot.
      expect(a.flag, RecoveryFlag.rot);
    });

    test('Δ = 5 an nur zwei Tagen bleibt orange', () {
      final a = assessRestingHeartRate(rhrSeries([50, 50, 50, 55, 55]));
      expect(a.deltaBpm, closeTo(5, 1e-9));
      expect(a.flag, RecoveryFlag.orange);
    });

    test('Δ = 4,9 an zwei Tagen ist gelb, nicht orange', () {
      final a = assessRestingHeartRate(rhrSeries([50, 50, 50, 54.9, 54.9]));
      expect(a.deltaBpm, closeTo(4.9, 1e-9));
      expect(a.flag, RecoveryFlag.gelb);
    });

    test('Δ ≥ 8 bpm ist sofort rot', () {
      final a = assessRestingHeartRate(rhrSeries([50, 50, 50, 50, 58]));
      expect(a.flag, RecoveryFlag.gruen); // Einzeltag zählt nicht
      final b = assessRestingHeartRate(rhrSeries([50, 50, 50, 58, 58]));
      expect(b.deltaBpm, closeTo(8, 1e-9));
      expect(b.flag, RecoveryFlag.rot);
    });

    test('Text nennt mögliche Ursachen statt einer Diagnose', () {
      final a = assessRestingHeartRate(rhrSeries([50, 50, 50, 58, 58]));
      expect(a.message, contains('Infekt'));
      expect(a.message.toLowerCase(), isNot(contains('übertrainiert')));
    });

    test('nach einer harten Tour wird die Formulierung angepasst', () {
      final a = assessRestingHeartRate(
        rhrSeries([50, 50, 50, 53, 53]),
        afterHardDay: true,
      );
      expect(a.flag, RecoveryFlag.gelb);
      expect(a.message, contains('erwartbar'));
    });

    test('ohne aktuellen Wert wird nichts behauptet', () {
      final a = assessRestingHeartRate(
        daily(List<double>.filled(60, 50), end: DateTime(2026, 8, 1)),
        today: DateTime(2026, 8, 8),
      );
      expect(a.available, isFalse);
      expect(a.unavailableReason, contains('aktueller'));
    });

    test('unplausible Werte werden ignoriert', () {
      final a = assessRestingHeartRate(
        daily([...List<double>.filled(55, 50), 200, 5, 50, 50, 50]),
      );
      expect(a.available, isTrue);
      expect(a.baseline, closeTo(50, 1e-9));
    });
  });

  // -------------------------------------------------------------------------
  group('HRV-Bewertung', () {
    test('leere Serie wirft nicht und meldet den Grund', () {
      final h = assessHrv(const []);
      expect(h.available, isFalse);
      expect(h.flag, RecoveryFlag.unbekannt);
      expect(h.status, HrvStatus.unbekannt);
      expect(h.unavailableReason, contains('Noch keine HRV-Werte'));
      expect(h.currentRmssd, isNull);
    });

    test('unter 14 Tagen Historie: Aufbauhinweis mit Restzahl', () {
      final h = assessHrv(daily(List<double>.filled(10, 50)));
      expect(h.available, isFalse);
      expect(h.historyDays, 10);
      expect(h.unavailableReason, contains('Braucht noch 4 Tage HRV-Daten'));
    });

    test('genau 14 Tage reichen für die volle Wertung', () {
      final h = assessHrv(daily(List<double>.filled(14, 50)));
      expect(h.available, isTrue);
      expect(h.historyDays, 14);
      expect(h.recentDays, 7);
    });

    test('stabile Serie liegt im Band; σ hat einen Boden', () {
      final h = assessHrv(daily(List<double>.filled(28, 50)));
      expect(h.available, isTrue);
      expect(h.status, HrvStatus.imBand);
      expect(h.flag, RecoveryFlag.gruen);
      expect(h.z, closeTo(0, 1e-9));
      expect(h.sigmaLn, hrvMinSigmaLn);
      expect(h.currentRmssd, closeTo(50, 1e-9));
      expect(h.baselineRmssd, closeTo(50, 1e-9));
      expect(h.bandLowRmssd! < 50, isTrue);
      expect(h.bandHighRmssd! > 50, isTrue);
      expect(h.message, contains('Normalband'));
    });

    test('Einbruch unter das Band: niedrig und mindestens orange', () {
      final h = assessHrv(daily([
        ...List<double>.filled(21, 50),
        ...List<double>.filled(7, 35),
      ]));
      expect(h.available, isTrue);
      expect(h.status, HrvStatus.niedrig);
      expect(h.flag, RecoveryFlag.orange);
      expect(h.z!, lessThan(-hrvBandFactor));
      expect(h.deviationPercent!, lessThan(-10));
      expect(h.message, contains('unter deinem Normalband'));
      // Nüchterne Sprache: keine Diagnose.
      expect(h.message.toLowerCase(), isNot(contains('übertrain')));
    });

    test('leichter Rückgang bleibt bei gelb', () {
      final h = assessHrv(daily([
        ...List<double>.filled(21, 50),
        ...List<double>.filled(7, 46),
      ]));
      expect(h.status, HrvStatus.niedrig);
      expect(h.flag, RecoveryFlag.gelb);
      expect(h.message, contains('knapp unter'));
    });

    test('über dem Band ist ohne Ruhepuls-Auffälligkeit ein gutes Zeichen', () {
      final h = assessHrv(daily([
        ...List<double>.filled(21, 50),
        ...List<double>.filled(7, 62),
      ]));
      expect(h.status, HrvStatus.ueberBand);
      expect(h.flag, RecoveryFlag.gruen);
      expect(h.z!, greaterThan(hrvBandFactor));
      expect(h.message, contains('gut erholt'));
    });

    test('über dem Band bei erhöhtem Ruhepuls: Sättigung als Warnzeichen', () {
      final h = assessHrv(
        daily([
          ...List<double>.filled(21, 50),
          ...List<double>.filled(7, 62),
        ]),
        restingHrFlag: RecoveryFlag.gelb,
      );
      expect(h.status, HrvStatus.saettigung);
      expect(h.flag, RecoveryFlag.orange);
      expect(h.message, contains('Ruhepuls'));
      expect(h.message, contains('beobachte'));
    });

    test('ohne aktuelle Messungen im Rollfenster keine Aussage', () {
      final h = assessHrv(
        daily(List<double>.filled(20, 50), end: DateTime(2026, 7, 25)),
        today: DateTime(2026, 8, 8),
      );
      expect(h.available, isFalse);
      expect(h.historyDays, greaterThanOrEqualTo(hrvMinBaselineDays));
      expect(h.unavailableReason, contains('letzten sieben Tagen'));
    });

    test('unplausible Werte fallen raus', () {
      final h = assessHrv(daily(List<double>.filled(28, 900)));
      expect(h.available, isFalse);
      expect(h.historyDays, 0);

      final mixed = assessHrv(daily([
        ...List<double>.filled(21, 50),
        // Aussetzer der Uhr: 0 ms und ein absurd hoher Wert.
        0,
        900,
        50,
        50,
        50,
        50,
        50,
      ]));
      expect(mixed.available, isTrue);
      expect(mixed.historyDays, 26);
      expect(mixed.recentDays, 5);
      expect(mixed.status, HrvStatus.imBand);
    });

    test('nur Tage der letzten 28 zählen zur Baseline', () {
      final h = assessHrv(daily(List<double>.filled(60, 50)));
      expect(h.historyDays, hrvBaselineDays);
    });
  });

  // -------------------------------------------------------------------------
  group('Schlaf-Bewertung', () {
    test('unter 14 Nächten: deaktiviert', () {
      final a = assessSleep(daily(List<double>.filled(10, 7)));
      expect(a.available, isFalse);
      expect(a.flag, RecoveryFlag.unbekannt);
      expect(a.unavailableReason, contains('14'));
    });

    test('leere Serie wirft nicht', () {
      expect(assessSleep(const []).available, isFalse);
    });

    test('Kurzschläfer mit 5,8 h Normalwert ist grün', () {
      final a = assessSleep(daily(List<double>.filled(28, 5.8)));
      expect(a.available, isTrue);
      expect(a.baselineH, closeTo(5.8, 1e-9));
      expect(a.deviationH, closeTo(0, 1e-9));
      expect(a.flag, RecoveryFlag.gruen);
      expect(a.shortSleeper, isTrue);
      expect(a.message, contains('Normalwert'));
    });

    test('Kurzschläfer mit akutem Einbruch bekommt eine Warnung', () {
      final a = assessSleep(daily([...List<double>.filled(27, 5.8), 4.0]));
      expect(a.baselineH, closeTo(5.8, 1e-9));
      expect(a.deviationH, closeTo(-1.8, 1e-9));
      expect(a.flag, RecoveryFlag.orange);
    });

    test('leichter Einbruch (−1,0 h) ist gelb', () {
      final a = assessSleep(daily([...List<double>.filled(27, 7.0), 6.0]));
      expect(a.deviationH, closeTo(-1.0, 1e-9));
      expect(a.flag, RecoveryFlag.gelb);
    });

    test('z-Regel greift auch ohne −1,0-h-Abweichung', () {
      // σ-Floor 0,5 h → dev −0,8 h ergibt z = −1,6
      final a = assessSleep(daily([...List<double>.filled(27, 7.0), 6.2]));
      expect(a.deviationH, closeTo(-0.8, 1e-9));
      expect(a.z, closeTo(-1.6, 1e-9));
      expect(a.flag, RecoveryFlag.gelb);
    });

    test('−0,5 h bei schwankendem Schlaf bleibt grün', () {
      // Median 7,0 h, σ = 1,4826 × 0,5 = 0,741 → z(−0,5 h) = −0,67
      final nights = <double>[
        for (var i = 0; i < 9; i++) ...[6.5, 7.0, 7.5],
        6.5,
      ];
      final a = assessSleep(daily(nights));
      expect(a.baselineH, closeTo(7.0, 1e-9));
      expect(a.sigmaH, closeTo(1.4826 * 0.5, 1e-6));
      expect(a.deviationH, closeTo(-0.5, 1e-9));
      expect(a.z!, greaterThan(-1.0));
      expect(a.flag, RecoveryFlag.gruen);
    });

    test('derselbe Ausfall trifft den sehr regelmäßigen Schläfer härter', () {
      // Konstanter Schlaf → σ-Floor 0,5 h → z(−0,5 h) = −1,0 → gelb.
      final a = assessSleep(daily([...List<double>.filled(27, 7.0), 6.5]));
      expect(a.sigmaH, closeTo(0.5, 1e-9));
      expect(a.deviationH, closeTo(-0.5, 1e-9));
      expect(a.z, closeTo(-1.0, 1e-9));
      expect(a.flag, RecoveryFlag.gelb);
    });

    test('7-Tage-Defizit ≤ −4 h wird orange', () {
      final a = assessSleep(daily([
        ...List<double>.filled(21, 7.0),
        ...List<double>.filled(7, 6.0),
      ]));
      // Baseline bleibt 7,0 h (Median von 21×7 und 7×6)
      expect(a.baselineH, closeTo(7.0, 1e-9));
      expect(a.debt7dH, closeTo(-7, 1e-9));
      expect(a.flag, RecoveryFlag.orange);
    });

    test('rot nur zusammen mit auffälligem Ruhepuls', () {
      final nights = daily([...List<double>.filled(27, 7.0), 4.4]);
      final withGreen = assessSleep(nights);
      expect(withGreen.flag, RecoveryFlag.orange);
      final withYellow =
          assessSleep(nights, restingHrFlag: RecoveryFlag.gelb);
      expect(withYellow.deviationH!, lessThanOrEqualTo(-2.5));
      expect(withYellow.flag, RecoveryFlag.rot);
    });

    test('Baseline wird auf 4,5–9,5 h geklemmt', () {
      final low = assessSleep(daily(List<double>.filled(28, 4.0)));
      expect(low.baselineH, closeTo(4.5, 1e-9));
      expect(low.deviationH, closeTo(-0.5, 1e-9));

      final high = assessSleep(daily(List<double>.filled(28, 11.0)));
      expect(high.baselineH, closeTo(9.5, 1e-9));
    });

    test('Sensorartefakte (< 2 h, > 14 h) fallen raus', () {
      final a = assessSleep(daily([
        ...List<double>.filled(20, 7.0),
        0,
        0,
        20,
        7.0,
        7.0,
        7.0,
        7.0,
        7.0,
      ]));
      expect(a.available, isTrue);
      expect(a.baselineH, closeTo(7.0, 1e-9));
      expect(a.validNights, 25);
    });

    test('Kurzschläfer-Hinweis ist entkoppelt und selten', () {
      expect(shortSleeperHint, contains('7–9'));
      expect(shortSleeperHint, contains('ändert das'));
      expect(shouldShowShortSleeperHint(null, DateTime(2026, 8, 8)), isTrue);
      expect(
        shouldShowShortSleeperHint(DateTime(2026, 8, 1), DateTime(2026, 8, 8)),
        isFalse,
      );
      expect(
        shouldShowShortSleeperHint(DateTime(2026, 7, 1), DateTime(2026, 8, 8)),
        isTrue,
      );
    });

    test('ohne aktuelle Nacht wird nichts behauptet', () {
      final a = assessSleep(
        daily(List<double>.filled(20, 7), end: DateTime(2026, 8, 1)),
        today: DateTime(2026, 8, 8),
      );
      expect(a.available, isFalse);
    });
  });

  // -------------------------------------------------------------------------
  group('Readiness', () {
    const goodRhr = RestingHrAssessment(
      available: true,
      unavailableReason: null,
      baseline: 50,
      sigma: 1.5,
      current: 50,
      deltaBpm: 0,
      z: 0,
      flag: RecoveryFlag.gruen,
      baselineDays: 40,
      streakDays: 0,
      message: 'ok',
    );
    const goodSleep = SleepAssessment(
      available: true,
      unavailableReason: null,
      baselineH: 7,
      sigmaH: 0.5,
      lastNightH: 7,
      deviationH: 0,
      z: 0,
      debt7dH: 0,
      flag: RecoveryFlag.gruen,
      validNights: 28,
      shortSleeper: false,
      message: 'ok',
    );

    test('alles unauffällig → 100 Punkte', () {
      final r = computeReadiness(
        restingHr: goodRhr,
        sleep: goodSleep,
        tsb: 0,
        trainingHistoryDays: 40,
      );
      expect(r.available, isTrue);
      expect(r.score, closeTo(100, 1e-9));
      expect(r.band, ReadinessBand.hart);
      expect(r.headline, contains('100'));
      expect(r.detail, contains('ohne HRV'));
    });

    test('Strafterme folgen exakt den Formeln aus §5.4', () {
      const rhr = RestingHrAssessment(
        available: true,
        unavailableReason: null,
        baseline: 50,
        sigma: 1.5,
        current: 53,
        deltaBpm: 3,
        z: 2.0,
        flag: RecoveryFlag.gelb,
        baselineDays: 40,
        streakDays: 2,
        message: 'x',
      );
      const sleep = SleepAssessment(
        available: true,
        unavailableReason: null,
        baselineH: 7,
        sigmaH: 0.5,
        lastNightH: 6,
        deviationH: -1,
        z: -2.0,
        debt7dH: -6.0,
        flag: RecoveryFlag.orange,
        validNights: 28,
        shortSleeper: false,
        message: 'x',
      );
      final r = computeReadiness(
        restingHr: rhr,
        sleep: sleep,
        tsb: -35,
        trainingHistoryDays: 40,
      );
      expect(r.penaltyRhr, closeTo(27, 1e-9)); // (2,0 − 0,5) × 18
      expect(r.penaltySleep, closeTo(33, 1e-9)); // 18 + 15 (gedeckelt)
      expect(r.penaltyLoad, closeTo(18, 1e-9)); // (35 − 20) × 1,2
      expect(r.score, closeTo(22, 1e-9));
      expect(r.band, ReadinessBand.ruhe);
    });

    test('Strafterme sind gedeckelt', () {
      const rhr = RestingHrAssessment(
        available: true,
        unavailableReason: null,
        baseline: 50,
        sigma: 1.5,
        current: 70,
        deltaBpm: 20,
        z: 20,
        flag: RecoveryFlag.rot,
        baselineDays: 40,
        streakDays: 5,
        message: 'x',
      );
      final r = computeReadiness(
        restingHr: rhr,
        sleep: goodSleep,
        tsb: -200,
        trainingHistoryDays: 40,
      );
      expect(r.penaltyRhr, 45);
      expect(r.penaltyLoad, 30);
      expect(r.score, closeTo(25, 1e-9));
    });

    test('Confidence-Gate: ohne Historie kein Gesamtscore', () {
      final r = computeReadiness(
        restingHr: goodRhr,
        sleep: goodSleep,
        tsb: 0,
        trainingHistoryDays: 10,
      );
      expect(r.available, isFalse);
      expect(r.unavailableReason, contains('Trainingshistorie'));
      expect(r.confidence, Confidence.none);
    });

    test('Confidence-Gate nennt alle fehlenden Signale', () {
      final r = computeReadiness(
        restingHr: RestingHrAssessment.unavailable('x', 3),
        sleep: SleepAssessment.unavailable('y', 2),
        trainingHistoryDays: 5,
      );
      expect(r.available, isFalse);
      expect(r.unavailableReason, contains('Ruhepuls'));
      expect(r.unavailableReason, contains('Schlaf'));
    });

    test('fehlende Einzelsignale erzeugen keinen Strafterm', () {
      final r = computeReadiness(
        restingHr: RestingHrAssessment.unavailable('x', 3),
        sleep: SleepAssessment.unavailable('y', 2),
      );
      expect(r.penaltyRhr, 0);
      expect(r.penaltySleep, 0);
      expect(r.penaltyLoad, 0);
      expect(r.score, 100);
    });

    test('Bänder 80 / 60 / 40', () {
      expect(classifyReadiness(100), ReadinessBand.hart);
      expect(classifyReadiness(80), ReadinessBand.hart);
      expect(classifyReadiness(79.9), ReadinessBand.normal);
      expect(classifyReadiness(60), ReadinessBand.normal);
      expect(classifyReadiness(59.9), ReadinessBand.locker);
      expect(classifyReadiness(40), ReadinessBand.locker);
      expect(classifyReadiness(39.9), ReadinessBand.ruhe);
    });

    HrvAssessment hrvWith({
      required double z,
      required HrvStatus status,
      required RecoveryFlag flag,
    }) =>
        HrvAssessment(
          available: true,
          unavailableReason: null,
          baselineLn: math.log(50),
          sigmaLn: 0.12,
          currentLn: math.log(50) + z * 0.12,
          lastRmssd: 50,
          z: z,
          status: status,
          flag: flag,
          historyDays: 28,
          recentDays: 7,
          message: 'hrv',
        );

    test('mit HRV gilt die Gewichtung 40/25/20/15', () {
      final r = computeReadiness(
        restingHr: goodRhr,
        sleep: goodSleep,
        hrv: hrvWith(
          z: -2.0,
          status: HrvStatus.niedrig,
          flag: RecoveryFlag.orange,
        ),
        tsb: 0,
        trainingHistoryDays: 40,
      );
      expect(r.usesHrv, isTrue);
      // (2,0 − 0,75) × 50 = 62,5 Strafpunkte, davon 40 %.
      expect(r.penaltyHrv, closeTo(62.5, 1e-9));
      expect(r.score, closeTo(75, 1e-9));
      expect(r.confidence, Confidence.high);
      expect(r.detail, contains('HRV, Ruhepuls'));
      expect(r.detail, isNot(contains('ohne HRV')));
    });

    test('HRV im Band kostet nichts, Ruhepuls wirkt nur noch mit 25 %', () {
      const rhr = RestingHrAssessment(
        available: true,
        unavailableReason: null,
        baseline: 50,
        sigma: 1.5,
        current: 53,
        deltaBpm: 3,
        z: 2.0,
        flag: RecoveryFlag.gelb,
        baselineDays: 40,
        streakDays: 2,
        message: 'x',
      );
      final withHrv = computeReadiness(
        restingHr: rhr,
        sleep: goodSleep,
        hrv: hrvWith(
          z: 0,
          status: HrvStatus.imBand,
          flag: RecoveryFlag.gruen,
        ),
        tsb: 0,
        trainingHistoryDays: 40,
      );
      // 27 von 45 möglichen Ruhepuls-Strafpunkten, gewichtet mit 25 %.
      expect(withHrv.penaltyHrv, 0);
      expect(withHrv.score, closeTo(85, 1e-9));

      final withoutHrv = computeReadiness(
        restingHr: rhr,
        sleep: goodSleep,
        tsb: 0,
        trainingHistoryDays: 40,
      );
      expect(withoutHrv.usesHrv, isFalse);
      expect(withoutHrv.score, closeTo(73, 1e-9));
      expect(withoutHrv.confidence, Confidence.medium);
    });

    test('parasympathische Sättigung kostet den halben HRV-Strafterm', () {
      final r = computeReadiness(
        restingHr: goodRhr,
        sleep: goodSleep,
        hrv: hrvWith(
          z: 1.4,
          status: HrvStatus.saettigung,
          flag: RecoveryFlag.orange,
        ),
        tsb: 0,
        trainingHistoryDays: 40,
      );
      expect(r.penaltyHrv, 50);
      expect(r.score, closeTo(80, 1e-9));
    });

    test('alle vier Signale am Anschlag ergeben 0', () {
      const rhr = RestingHrAssessment(
        available: true,
        unavailableReason: null,
        baseline: 50,
        sigma: 1.5,
        current: 70,
        deltaBpm: 20,
        z: 20,
        flag: RecoveryFlag.rot,
        baselineDays: 40,
        streakDays: 5,
        message: 'x',
      );
      const sleep = SleepAssessment(
        available: true,
        unavailableReason: null,
        baselineH: 7,
        sigmaH: 0.5,
        lastNightH: 3,
        deviationH: -4,
        z: -8,
        debt7dH: -20,
        flag: RecoveryFlag.rot,
        validNights: 28,
        shortSleeper: false,
        message: 'x',
      );
      final r = computeReadiness(
        restingHr: rhr,
        sleep: sleep,
        hrv: hrvWith(
          z: -10,
          status: HrvStatus.niedrig,
          flag: RecoveryFlag.rot,
        ),
        tsb: -200,
        trainingHistoryDays: 40,
      );
      expect(r.penaltyHrv, 100);
      expect(r.score, 0);
      expect(r.band, ReadinessBand.ruhe);
    });

    test('HRV allein öffnet kein Gate: Ruhepuls fehlt weiterhin', () {
      final r = computeReadiness(
        restingHr: RestingHrAssessment.unavailable('x', 3),
        sleep: goodSleep,
        hrv: hrvWith(
          z: 0,
          status: HrvStatus.imBand,
          flag: RecoveryFlag.gruen,
        ),
        trainingHistoryDays: 40,
      );
      expect(r.available, isFalse);
      expect(r.unavailableReason, contains('Ruhepuls'));
    });

    test('nicht berechenbare HRV fällt auf die Formel ohne HRV zurück', () {
      final r = computeReadiness(
        restingHr: goodRhr,
        sleep: goodSleep,
        hrv: HrvAssessment.unavailable('Braucht noch 6 Tage HRV-Daten.', 8),
        tsb: 0,
        trainingHistoryDays: 40,
      );
      expect(r.usesHrv, isFalse);
      expect(r.penaltyHrv, 0);
      expect(r.score, closeTo(100, 1e-9));
      expect(r.detail, contains('ohne HRV'));
      expect(r.hrv.unavailableReason, contains('Braucht noch 6 Tage'));
    });

    test('End-to-End über echte Serien: Kurzschläfer bleibt bei 100', () {
      final rhr = assessRestingHeartRate(daily(List<double>.filled(60, 50)));
      final sleep = assessSleep(daily(List<double>.filled(28, 5.8)));
      final r = computeReadiness(
        restingHr: rhr,
        sleep: sleep,
        tsb: -5,
        trainingHistoryDays: 60,
      );
      expect(r.available, isTrue);
      expect(r.score, closeTo(100, 1e-9));
      expect(r.band, ReadinessBand.hart);
      expect(r.sleep.shortSleeper, isTrue);
    });
  });

  // -------------------------------------------------------------------------
  group('Empfehlungen', () {
    Readiness readinessWith({
      required double score,
      RecoveryFlag rhrFlag = RecoveryFlag.gruen,
      RecoveryFlag sleepFlag = RecoveryFlag.gruen,
      RecoveryFlag? hrvFlag,
      double? tsb,
    }) {
      final rhr = RestingHrAssessment(
        available: true,
        unavailableReason: null,
        baseline: 50,
        sigma: 1.5,
        current: 50,
        deltaBpm: 0,
        z: 0,
        flag: rhrFlag,
        baselineDays: 40,
        streakDays: 0,
        message: 'rhr',
      );
      final sleep = SleepAssessment(
        available: true,
        unavailableReason: null,
        baselineH: 7,
        sigmaH: 0.5,
        lastNightH: 7,
        deviationH: 0,
        z: 0,
        debt7dH: 0,
        flag: sleepFlag,
        validNights: 28,
        shortSleeper: false,
        message: 'schlaf',
      );
      final hrv = hrvFlag == null
          ? const HrvAssessment.missing()
          : HrvAssessment(
              available: true,
              unavailableReason: null,
              baselineLn: math.log(50),
              sigmaLn: 0.12,
              currentLn: math.log(50),
              lastRmssd: 50,
              z: hrvFlag == RecoveryFlag.gruen ? 0.0 : -2.0,
              status: hrvFlag == RecoveryFlag.gruen
                  ? HrvStatus.imBand
                  : HrvStatus.niedrig,
              flag: hrvFlag,
              historyDays: 28,
              recentDays: 7,
              message: 'hrv',
            );
      return Readiness(
        available: true,
        unavailableReason: null,
        score: score,
        band: classifyReadiness(score),
        penaltyRhr: 0,
        penaltySleep: 0,
        penaltyLoad: 0,
        restingHr: rhr,
        sleep: sleep,
        hrv: hrv,
        usesHrv: hrvFlag != null,
        tsb: tsb,
        confidence: Confidence.medium,
        headline: '',
        detail: '',
      );
    }

    test('Readiness < 40 → Ruhetag', () {
      final r = recommendToday(readiness: readinessWith(score: 30), tsb: 0);
      expect(r.kind, DailyRecommendationKind.ruhetag);
      expect(r.reasons, isNotEmpty);
    });

    test('roter Ruhepuls → Ruhetag, auch bei gutem Score', () {
      final r = recommendToday(
        readiness: readinessWith(score: 90, rhrFlag: RecoveryFlag.rot),
        tsb: 0,
      );
      expect(r.kind, DailyRecommendationKind.ruhetag);
    });

    test('Readiness < 60 → locker Z2', () {
      final r = recommendToday(readiness: readinessWith(score: 55), tsb: 0);
      expect(r.kind, DailyRecommendationKind.lockerZ2);
      expect(r.detail, contains('Intervalle'));
    });

    test('orange Schlaf-Stufe → locker Z2', () {
      final r = recommendToday(
        readiness: readinessWith(score: 85, sleepFlag: RecoveryFlag.orange),
        tsb: 0,
      );
      expect(r.kind, DailyRecommendationKind.lockerZ2);
    });

    test('TSB < −25 → Regenerationsfahrt', () {
      final r = recommendToday(readiness: readinessWith(score: 70), tsb: -28);
      expect(r.kind, DailyRecommendationKind.recovery);
    });

    test('Readiness ≥ 80 mit Budget → harte Einheit freigegeben', () {
      final r = recommendToday(readiness: readinessWith(score: 85), tsb: -10);
      expect(r.kind, DailyRecommendationKind.harteEinheit);
    });

    test('ohne HIT-Budget bleibt es bei der Grundlageneinheit', () {
      final r = recommendToday(
        readiness: readinessWith(score: 85),
        tsb: -10,
        hitBudgetLeft: false,
      );
      expect(r.kind, DailyRecommendationKind.grundlage);
    });

    test('ohne Gesamtscore steuern nur die Einzelsignale', () {
      final r = recommendToday(
        readiness: computeReadiness(
          restingHr: RestingHrAssessment.unavailable('x', 0),
          sleep: SleepAssessment.unavailable('y', 0),
        ),
      );
      expect(r.kind, DailyRecommendationKind.grundlage);
    });

    test('rote HRV → Ruhetag, auch bei gutem Score', () {
      final r = recommendToday(
        readiness: readinessWith(score: 90, hrvFlag: RecoveryFlag.rot),
        tsb: 0,
      );
      expect(r.kind, DailyRecommendationKind.ruhetag);
      expect(r.reasons.first, 'hrv');
    });

    test('orange HRV → locker Z2', () {
      final r = recommendToday(
        readiness: readinessWith(score: 85, hrvFlag: RecoveryFlag.orange),
        tsb: 0,
      );
      expect(r.kind, DailyRecommendationKind.lockerZ2);
    });

    test('gelbe HRV vertagt die harte Einheit', () {
      final r = recommendToday(
        readiness: readinessWith(score: 85, hrvFlag: RecoveryFlag.gelb),
        tsb: -10,
      );
      expect(r.kind, DailyRecommendationKind.grundlage);

      final green = recommendToday(
        readiness: readinessWith(score: 85, hrvFlag: RecoveryFlag.gruen),
        tsb: -10,
      );
      expect(green.kind, DailyRecommendationKind.harteEinheit);
    });
  });

  // -------------------------------------------------------------------------
  group('Readiness-Reihe (rückwirkend)', () {
    final today = DateTime(2026, 8, 8);
    final fitness = computeFitnessSeries(
      constantLoads(40, 60, end: today),
      until: today,
    );

    test('liefert sieben aufsteigende Tage bis heute', () {
      final series = computeReadinessSeries(
        restingHrSeries: daily(List<double>.filled(60, 50), end: today),
        sleepSeries: daily(List<double>.filled(40, 7), end: today),
        fitness: fitness,
        today: today,
      );
      expect(series.length, 7);
      expect(series.first.day, DateTime(2026, 8, 2));
      expect(series.last.day, today);
      expect(series.every((p) => p.readiness.available), isTrue);
      expect(availableReadinessScores(series).length, 7);
      expect(series.last.readiness.score, closeTo(100, 1e-9));
    });

    test('jeder Tag sieht nur die bis dahin vorhandenen Daten', () {
      // Ruhepuls und Schlaf kippen erst in den letzten Tagen.
      final series = computeReadinessSeries(
        restingHrSeries: daily(
          [...List<double>.filled(56, 50), 62, 62, 62, 62],
          end: today,
        ),
        sleepSeries: daily(
          [...List<double>.filled(36, 7), 2.5, 2.5, 2.5, 2.5],
          end: today,
        ),
        fitness: fitness,
        today: today,
      );
      final scores = availableReadinessScores(series);
      expect(scores.length, 7);
      // Die frühen Tage der Woche sind unauffällig, die späten brechen ein.
      expect(scores.first, closeTo(100, 1e-9));
      expect(scores.where((s) => s < 40).length, greaterThanOrEqualTo(3));
    });

    test('schließt den Deload-Trigger „3 von 7 Tagen"', () {
      final series = computeReadinessSeries(
        restingHrSeries: daily(
          [...List<double>.filled(56, 50), 62, 62, 62, 62],
          end: today,
        ),
        sleepSeries: daily(
          [...List<double>.filled(36, 7), 2.5, 2.5, 2.5, 2.5],
          end: today,
        ),
        fitness: fitness,
        today: today,
      );
      final deload = assessDeload(
        fitness,
        readinessLast7: availableReadinessScores(series),
      );
      expect(deload.recommended, isTrue);
      expect(deload.triggers.single, contains('Erholung'));
    });

    test('HRV geht in die Reihe ein und senkt die Scores', () {
      final restingHr = daily(List<double>.filled(60, 50), end: today);
      final sleep = daily(List<double>.filled(40, 7), end: today);
      final hrv = daily(
        [...List<double>.filled(21, 50), ...List<double>.filled(7, 33)],
        end: today,
      );
      final withHrv = computeReadinessSeries(
        restingHrSeries: restingHr,
        sleepSeries: sleep,
        hrvSeries: hrv,
        fitness: fitness,
        today: today,
      );
      expect(withHrv.last.readiness.usesHrv, isTrue);
      expect(withHrv.last.readiness.score, lessThan(100));

      final withoutHrv = computeReadinessSeries(
        restingHrSeries: restingHr,
        sleepSeries: sleep,
        fitness: fitness,
        today: today,
      );
      expect(withoutHrv.last.readiness.usesHrv, isFalse);
      expect(withoutHrv.last.readiness.score, closeTo(100, 1e-9));
    });

    test('ohne Daten entstehen Tage ohne Gesamtscore, nichts wirft', () {
      final series = computeReadinessSeries(today: today);
      expect(series.length, 7);
      expect(series.every((p) => !p.readiness.available), isTrue);
      expect(availableReadinessScores(series), isEmpty);
      expect(assessDeload(
        const FitnessSeries.empty(),
        readinessLast7: availableReadinessScores(series),
      ).recommended, isFalse);
    });

    test('days ≤ 0 liefert eine leere Reihe', () {
      expect(computeReadinessSeries(today: today, days: 0), isEmpty);
    });
  });

  // -------------------------------------------------------------------------
  group('Deload', () {
    FitnessSeries seriesWithTsb(List<double> tsbs) => FitnessSeries(
          points: List<FitnessPoint>.generate(
            tsbs.length,
            (i) => FitnessPoint(
              day: DateTime(2026, 8, 8 - (tsbs.length - 1 - i)),
              load: 0,
              ctl: 50,
              atl: 50,
              tsb: tsbs[i],
              rampRate7d: 0,
              loadRatio: 1.0,
            ),
          ),
          historyDays: tsbs.length,
          seedLoad: 50,
          displayReady: true,
        );

    test('TSB < −30 über drei Tage löst Deload aus', () {
      final d = assessDeload(seriesWithTsb([-10, -31, -32, -33]));
      expect(d.recommended, isTrue);
      expect(d.triggers.length, 1);
      expect(d.detail, contains('40–50 %'));
      expect(d.detail, contains('Intensität'));
      expect(d.volumeReductionLow, 0.40);
      expect(d.volumeReductionHigh, 0.50);
    });

    test('nur zwei Tage unter −30 lösen nichts aus', () {
      final d = assessDeload(seriesWithTsb([-10, -20, -31, -32]));
      expect(d.recommended, isFalse);
      expect(d.title, 'Kein Deload nötig');
    });

    test('Rampenrate > 8 über drei Wochen löst Deload aus', () {
      final points = List<FitnessPoint>.generate(
        21,
        (i) => FitnessPoint(
          day: DateTime(2026, 8, 8 - (20 - i)),
          load: 100,
          ctl: 50,
          atl: 50,
          tsb: 0,
          rampRate7d: 9,
          loadRatio: 1.0,
        ),
      );
      final d = assessDeload(FitnessSeries(
        points: points,
        historyDays: 21,
        seedLoad: 0,
        displayReady: true,
      ));
      expect(d.recommended, isTrue);
      expect(d.triggers.first, contains('drei Wochen'));
    });

    test('Readiness < 40 an drei von sieben Tagen löst Deload aus', () {
      final d = assessDeload(
        seriesWithTsb([0, 0, 0]),
        readinessLast7: const [80, 35, 30, 70, 39, 60, 65],
      );
      expect(d.recommended, isTrue);
      expect(d.triggers.single, contains('Erholung'));
    });

    test('Wochenlastsprung ist nur eine Warnung, kein Deload', () {
      final d = assessDeload(
        seriesWithTsb([0, 0, 0]),
        weeklyLoad: 500,
        fourWeekMeanWeeklyLoad: 300,
      );
      expect(d.recommended, isFalse);
      expect(d.warnings.first, contains('deutlich gestiegen'));
      expect(d.warnings.join(' ').toLowerCase(),
          isNot(contains('verletzung')));
    });

    test('leere Serie wirft nicht', () {
      final d = assessDeload(const FitnessSeries.empty());
      expect(d.recommended, isFalse);
      expect(d.triggers, isEmpty);
    });
  });

  // -------------------------------------------------------------------------
  group('Pe:Hr-Entkopplung', () {
    /// Flache, gleichmäßige Tour über [seconds] Sekunden mit HF-Drift.
    PhysicsEstimate flatRide({
      required int seconds,
      required int hrFirst,
      required int hrSecond,
      double gradeTan = 0,
    }) {
      final points = track(
        pointCount: seconds + 1,
        speedMs: 5,
        stepS: 1,
        gradeTan: gradeTan,
        startEle: 100,
        hr: (i) => i <= seconds ~/ 2 ? hrFirst : hrSecond,
      );
      return computePhysicsEstimate(
        buildRideSeries(points, refProfile),
        refProfile,
      );
    }

    test('zu kurze Tour: Gate greift', () {
      final d = computeDecoupling(
        flatRide(seconds: 1200, hrFirst: 130, hrSecond: 140),
        refProfile,
      );
      expect(d.available, isFalse);
      expect(d.unavailableReason, contains('60 Minuten'));
      expect(d.decouplingPercent, isNull);
    });

    test('zu hohe Intensität: Gate greift', () {
      final d = computeDecoupling(
        flatRide(seconds: 3700, hrFirst: 175, hrSecond: 180),
        refProfile,
      );
      expect(d.available, isFalse);
      expect(d.unavailableReason, contains('aeroben'));
    });

    test('zu niedrige Intensität: Gate greift', () {
      final d = computeDecoupling(
        flatRide(seconds: 3700, hrFirst: 100, hrSecond: 105),
        refProfile,
      );
      expect(d.available, isFalse);
    });

    test('ohne Leistungsmodell keine Entkopplung', () {
      final d = computeDecoupling(
        PhysicsEstimate.unavailable('kein Höhenprofil'),
        refProfile,
      );
      expect(d.available, isFalse);
      expect(d.unavailableReason, 'kein Höhenprofil');
    });

    test('qualifizierende Tour: Entkopplung entspricht der HF-Drift', () {
      final d = computeDecoupling(
        flatRide(seconds: 3700, hrFirst: 130, hrSecond: 140),
        refProfile,
      );
      expect(d.available, isTrue);
      // Gleiche Leistung, HF +10 bpm → ≈ (1/130 − 1/140)/(1/130) = 7,14 %
      expect(d.decouplingPercent, closeTo(7.14, 0.6));
      expect(d.rating, 'aerobe Ausdauer im Aufbau');
      expect(d.efFirst, greaterThan(d.efSecond!));
      expect(d.confidence, Confidence.medium);
    });

    test('konstante HF: nahezu keine Entkopplung', () {
      final d = computeDecoupling(
        flatRide(seconds: 3700, hrFirst: 135, hrSecond: 135),
        refProfile,
      );
      expect(d.available, isTrue);
      expect(d.decouplingPercent!.abs(), lessThan(2));
      expect(d.rating, 'gute aerobe Ausdauer');
    });

    test('fehlende HF in der zweiten Hälfte: Abdeckungs-Gate greift', () {
      final points = track(
        pointCount: 3701,
        speedMs: 5,
        stepS: 1,
        startEle: 100,
        hr: (i) => i < 3000 ? 135 : null,
      );
      final d = computeDecoupling(
        computePhysicsEstimate(buildRideSeries(points, refProfile), refProfile),
        refProfile,
      );
      expect(d.available, isFalse);
      expect(d.unavailableReason, contains('90 %'));
    });

    test('Trend ist der Median der letzten fünf Werte', () {
      expect(decouplingTrend(const []), isNull);
      expect(decouplingTrend(const [3, 4, 5]), closeTo(4, 1e-9));
      expect(
        decouplingTrend(const [100, 100, 1, 2, 3, 4, 5]),
        closeTo(3, 1e-9),
      );
    });

    test('Bewertungstext nach Friel-Schwellen', () {
      // über die öffentliche API: Werte werden über das Rating abgebildet
      final good = computeDecoupling(
        flatRide(seconds: 3700, hrFirst: 135, hrSecond: 135),
        refProfile,
      );
      expect(good.rating, 'gute aerobe Ausdauer');
      final drifting = computeDecoupling(
        flatRide(seconds: 3700, hrFirst: 125, hrSecond: 145),
        refProfile,
      );
      expect(drifting.rating, 'mehr Grundlagenarbeit sinnvoll');
    });
  });

  // -------------------------------------------------------------------------
  group('VO2max', () {
    test('Uth-Formel mit ±15-%-Band', () {
      final e = estimateVo2MaxFromHrRatio(refProfile);
      expect(e.available, isTrue);
      expect(e.value, closeTo(58.14, 1e-9));
      expect(e.lower, closeTo(58.14 * 0.85, 1e-9));
      expect(e.upper, closeTo(58.14 * 1.15, 1e-9));
      expect(e.method, Vo2MaxMethod.uthRatio);
      expect(e.confidence, Confidence.low);
      expect(e.text, contains('geschätzt'));
      expect(e.text, contains('–'));
    });

    test('ACSM-Regression bei perfektem Zusammenhang', () {
      // P = 2·(HF − 100) + 60, VO2 = 10,8·P/75 + 7 = 0,144·P + 7
      // → Steigung 0,288 ml/kg/min pro bpm, Achsenabschnitt −13,16
      final segments = <SteadySegment>[
        for (var hr = 110; hr <= 160; hr += 10)
          SteadySegment(
            avgPowerW: 2 * (hr - 100) + 60,
            avgHr: hr.toDouble(),
            durationS: 360,
          ),
      ];
      final e = estimateVo2MaxFromSegments(segments, refProfile);
      expect(e.available, isTrue);
      expect(e.r2, closeTo(1.0, 1e-9));
      expect(e.segmentCount, 6);
      expect(e.hrSpanBpm, closeTo(50, 1e-9));
      expect(e.value, closeTo(41.56, 1e-6));
      expect(e.lower, closeTo(41.56 * 0.9, 1e-6));
      expect(e.method, Vo2MaxMethod.regression);
      expect(e.confidence, Confidence.medium);
    });

    test('Gate: weniger als 6 Segmente', () {
      final segments = <SteadySegment>[
        for (var hr = 110; hr <= 150; hr += 10)
          SteadySegment(
            avgPowerW: 2 * (hr - 100) + 60,
            avgHr: hr.toDouble(),
            durationS: 360,
          ),
      ];
      final e = estimateVo2MaxFromSegments(segments, refProfile);
      expect(e.available, isFalse);
      expect(e.unavailableReason, contains('6'));
    });

    test('Gate: HF-Spanne unter 25 bpm', () {
      final segments = <SteadySegment>[
        for (var i = 0; i < 8; i++)
          SteadySegment(
            avgPowerW: 100.0 + i,
            avgHr: 140.0 + i,
            durationS: 360,
          ),
      ];
      final e = estimateVo2MaxFromSegments(segments, refProfile);
      expect(e.available, isFalse);
      expect(e.unavailableReason, contains('Spanne'));
    });

    test('Gate: r² unter 0,80', () {
      final noise = [0.0, 60.0, -50.0, 55.0, -45.0, 40.0, -60.0, 50.0];
      final segments = <SteadySegment>[
        for (var i = 0; i < 8; i++)
          SteadySegment(
            avgPowerW: (110.0 + noise[i]).clamp(50, 200),
            avgHr: 120.0 + i * 5,
            durationS: 360,
          ),
      ];
      final e = estimateVo2MaxFromSegments(segments, refProfile);
      expect(e.available, isFalse);
      expect(e.unavailableReason, contains('r²'));
    });

    test('Priorität: Plattform > Regression > Uth', () {
      final segments = <SteadySegment>[
        for (var hr = 110; hr <= 160; hr += 10)
          SteadySegment(
            avgPowerW: 2 * (hr - 100) + 60,
            avgHr: hr.toDouble(),
            durationS: 360,
          ),
      ];
      expect(
        estimateVo2Max(profile: refProfile, platformValue: 52).method,
        Vo2MaxMethod.plattform,
      );
      expect(
        estimateVo2Max(profile: refProfile, segments: segments).method,
        Vo2MaxMethod.regression,
      );
      expect(estimateVo2Max(profile: refProfile).method, Vo2MaxMethod.uthRatio);
    });

    test('ohne Gewicht keine Regression', () {
      final e = estimateVo2MaxFromSegments(
        const [],
        refProfile.copyWith(weightKg: 0),
      );
      expect(e.available, isFalse);
      expect(e.text, contains('Gewicht'));
    });

    test('Segmente außerhalb 50–200 W werden verworfen', () {
      final segments = <SteadySegment>[
        for (var hr = 110; hr <= 160; hr += 10)
          SteadySegment(avgPowerW: 400, avgHr: hr.toDouble(), durationS: 360),
      ];
      expect(estimateVo2MaxFromSegments(segments, refProfile).available,
          isFalse);
    });

    test('stabile Segmente werden aus der Leistungsreihe extrahiert', () {
      final points = track(
        pointCount: 1801,
        speedMs: 5,
        stepS: 1,
        startEle: 100,
        hr: (i) => 140,
      );
      final power = buildPowerSeries(
        buildRideSeries(points, refProfile),
        refProfile,
      );
      final segments = extractSteadySegments(power, refProfile);
      expect(segments.length, greaterThanOrEqualTo(5));
      expect(segments.first.avgHr, closeTo(140, 1e-6));
      expect(segments.first.durationS, greaterThanOrEqualTo(300));
      // Ohne HF gibt es keine Segmente.
      final noHr = buildPowerSeries(
        buildRideSeries(
          track(pointCount: 1801, speedMs: 5, stepS: 1, startEle: 100),
          refProfile,
        ),
        refProfile,
      );
      expect(extractSteadySegments(noHr, refProfile), isEmpty);
      expect(extractSteadySegments(const PowerSeries.empty(), refProfile),
          isEmpty);
    });

    test('rollierender 28-Tage-Median und 2-Punkte-Regel', () {
      expect(vo2MaxRollingMedian(const []), isNull);
      expect(
        vo2MaxRollingMedian(daily(const [48, 50, 52])),
        closeTo(50, 1e-9),
      );
      expect(vo2MaxChangeWorthShowing(50, 51), isFalse);
      expect(vo2MaxChangeWorthShowing(50, 52), isTrue);
      expect(vo2MaxChangeWorthShowing(null, 52), isTrue);
      expect(vo2MaxChangeWorthShowing(50, null), isFalse);
    });
  });

  // -------------------------------------------------------------------------
  group('Formulierungen ohne Overclaim', () {
    test('Lastquellen sind als Schätzung gekennzeichnet', () {
      expect(loadSourceLabels[LoadSource.physik], contains('schätzung'));
      expect(loadSourceLabels[LoadSource.heuristik], contains('geschätzt'));
    });

    test('Confidence-Labels sind sprechend', () {
      expect(confidenceLabels[Confidence.none], 'nicht berechenbar');
      expect(confidenceLabels.length, Confidence.values.length);
    });

    test('Erholungs-Ampel spricht nicht von Krankheit als Diagnose', () {
      final all = recoveryFlagLabels.values.join(' ').toLowerCase();
      expect(all, isNot(contains('krank')));
      expect(all, isNot(contains('übertraining')));
    });

    test('VO2max wird immer als Band ausgegeben', () {
      final e = estimateVo2MaxFromHrRatio(refProfile);
      expect(e.text, matches(RegExp(r'\d+–\d+ ml/kg/min')));
    });
  });
}
