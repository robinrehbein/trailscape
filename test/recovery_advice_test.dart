import 'package:flutter_test/flutter_test.dart';
import 'package:trailscape/health_sync.dart';
import 'package:trailscape/training.dart';

/// Baut eine [VitalsSummary] aus vereinfachten Trend-Angaben. Die Tagesserien
/// selbst sind für [buildRecoveryAdvice] irrelevant – nur die Wochenmittel
/// (und ob überhaupt Daten vorliegen) fließen in die Empfehlung ein.
VitalsSummary _vitals({
  double? hrLastWeek,
  double? hrPreviousWeek,
  double? sleepLastWeek,
  double? sleepPreviousWeek,
}) {
  VitalsTrend trend(double? lastWeek, double? previousWeek) {
    if (lastWeek == null) {
      return const VitalsTrend.empty();
    }
    return VitalsTrend(
      series: [DailyValue(day: DateTime(2026, 8, 8), value: lastWeek)],
      lastWeekAvg: lastWeek,
      previousWeekAvg: previousWeek,
    );
  }

  return VitalsSummary(
    days: 14,
    from: DateTime(2026, 7, 26),
    to: DateTime(2026, 8, 8),
    restingHeartRate: trend(hrLastWeek, hrPreviousWeek),
    sleepHours: trend(sleepLastWeek, sleepPreviousWeek),
  );
}

void main() {
  group('buildRecoveryAdvice – Ruhepuls', () {
    test('genau 5 % über der Vorwoche löst die Empfehlung aus (Grenzfall)',
        () {
      final vitals = _vitals(
        hrLastWeek: 63,
        hrPreviousWeek: 60,
        sleepLastWeek: 7.5,
        sleepPreviousWeek: 7.5,
      );
      final advice = buildRecoveryAdvice(vitals);
      expect(advice.reduceIntensity, isTrue);
      expect(advice.message, contains('erhöhter Ruhepuls'));
      expect(advice.message, isNot(contains('wenig Schlaf')));
    });

    test('knapp unter 5 % löst nichts aus', () {
      final vitals = _vitals(
        hrLastWeek: 62.9,
        hrPreviousWeek: 60,
        sleepLastWeek: 7.5,
        sleepPreviousWeek: 7.5,
      );
      final advice = buildRecoveryAdvice(vitals);
      expect(advice.reduceIntensity, isFalse);
      expect(advice.adjustedTargetKm, isNull);
    });

    test('ohne Vorwochendaten gibt es keine Empfehlung, egal wie hoch',
        () {
      final vitals = _vitals(
        hrLastWeek: 90,
        hrPreviousWeek: null,
        sleepLastWeek: 7.5,
        sleepPreviousWeek: 7.5,
      );
      final advice = buildRecoveryAdvice(vitals);
      expect(advice.reduceIntensity, isFalse);
    });

    test('ganz ohne Ruhepulsdaten gibt es keine Empfehlung daraus', () {
      final vitals = _vitals(sleepLastWeek: 7.5, sleepPreviousWeek: 7.5);
      final advice = buildRecoveryAdvice(vitals);
      expect(advice.reduceIntensity, isFalse);
    });
  });

  group('buildRecoveryAdvice – Schlaf', () {
    test('genau 6 h lösen noch nichts aus', () {
      final vitals = _vitals(
        hrLastWeek: 55,
        hrPreviousWeek: 55,
        sleepLastWeek: 6.0,
        sleepPreviousWeek: 7,
      );
      final advice = buildRecoveryAdvice(vitals);
      expect(advice.reduceIntensity, isFalse);
    });

    test('unter 6 h löst die Empfehlung aus', () {
      final vitals = _vitals(
        hrLastWeek: 55,
        hrPreviousWeek: 55,
        sleepLastWeek: 5.9,
        sleepPreviousWeek: 7,
      );
      final advice = buildRecoveryAdvice(vitals);
      expect(advice.reduceIntensity, isTrue);
      expect(advice.message, contains('wenig Schlaf'));
      expect(advice.message, isNot(contains('erhöhter Ruhepuls')));
    });

    test('Schlaf ohne Vorwochen-Vergleich zählt trotzdem (kein Trend nötig)',
        () {
      final vitals = _vitals(
        hrLastWeek: 55,
        hrPreviousWeek: 55,
        sleepLastWeek: 5.0,
        sleepPreviousWeek: null,
      );
      final advice = buildRecoveryAdvice(vitals);
      expect(advice.reduceIntensity, isTrue);
    });
  });

  group('buildRecoveryAdvice – kombiniert und Wochenziel', () {
    test('beide Gründe gleichzeitig werden beide genannt', () {
      final vitals = _vitals(
        hrLastWeek: 66,
        hrPreviousWeek: 60,
        sleepLastWeek: 5,
        sleepPreviousWeek: 7,
      );
      final advice = buildRecoveryAdvice(vitals);
      expect(advice.message, contains('erhöhter Ruhepuls'));
      expect(advice.message, contains('wenig Schlaf'));
    });

    test('normale Werte liefern die neutrale Standardmeldung', () {
      final vitals = _vitals(
        hrLastWeek: 55,
        hrPreviousWeek: 56,
        sleepLastWeek: 7.5,
        sleepPreviousWeek: 7.2,
      );
      final advice = buildRecoveryAdvice(vitals);
      expect(advice.reduceIntensity, isFalse);
      expect(advice.message, isNotEmpty);
      expect(advice.adjustedTargetKm, isNull);
    });

    test('reduziert das Wochenziel um Faktor 0,7, auf 5 km gerundet', () {
      final vitals = _vitals(
        hrLastWeek: 66,
        hrPreviousWeek: 60,
        sleepLastWeek: 7,
        sleepPreviousWeek: 7,
      );
      final advice = buildRecoveryAdvice(vitals, currentTargetKm: 100);
      expect(advice.reduceIntensity, isTrue);
      expect(advice.adjustedTargetKm, 70);
    });

    test('reduziertes Ziel ist auf mindestens 5 km gedeckelt', () {
      final vitals = _vitals(
        hrLastWeek: 66,
        hrPreviousWeek: 60,
        sleepLastWeek: 7,
        sleepPreviousWeek: 7,
      );
      final advice = buildRecoveryAdvice(vitals, currentTargetKm: 5);
      expect(advice.adjustedTargetKm, 5);
    });

    test('ohne aktuelles Wochenziel bleibt adjustedTargetKm leer', () {
      final vitals = _vitals(
        hrLastWeek: 66,
        hrPreviousWeek: 60,
        sleepLastWeek: 7,
        sleepPreviousWeek: 7,
      );
      final advice = buildRecoveryAdvice(vitals);
      expect(advice.reduceIntensity, isTrue);
      expect(advice.adjustedTargetKm, isNull);
    });

    test('ganz ohne Vitaldaten gibt es keine Empfehlung', () {
      final advice = buildRecoveryAdvice(_vitals());
      expect(advice.reduceIntensity, isFalse);
    });
  });
}
