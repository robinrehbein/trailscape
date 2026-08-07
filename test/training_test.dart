import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:trailscape/models.dart';
import 'package:trailscape/training.dart';

/// Feste Zeitpunkte statt DateTime.now(), damit die Tests reproduzierbar sind.
/// Mittwoch, 7. Januar 2026, 12:00 lokaler Zeit.
final int now = DateTime(2026, 1, 7, 12).millisecondsSinceEpoch;

/// Montag der Woche von [now].
final DateTime firstMonday = DateTime(2026, 1, 5);

int ms(DateTime date) => date.millisecondsSinceEpoch;

/// Zeitstempel am Tag [dayOffset] nach dem ersten Montag, 09:00 lokal.
int dayAfterFirstMonday(int dayOffset, {int hour = 9}) => ms(DateTime(
      firstMonday.year,
      firstMonday.month,
      firstMonday.day + dayOffset,
      hour,
    ));

Goal goalAt(int timestamp, {double distanceKm = 160, double? ascentM = 2200}) =>
    Goal(
      name: 'Gravel Grinder',
      distanceKm: distanceKm,
      ascentM: ascentM,
      date: timestamp,
    );

const advanced = FitnessAssessment(
  level: FitnessLevel.fortgeschritten,
  weeklyKm: 95,
  weeklyHm: 900,
  weeklyRides: 2,
  longestRideKm: 80,
  rideCount: 16,
);

const beginner = FitnessAssessment(
  level: FitnessLevel.einsteiger,
  weeklyKm: 12,
  weeklyHm: 120,
  weeklyRides: 1,
  longestRideKm: 25,
  rideCount: 8,
);

Ride ride(int createdAt, double distanceKm) => Ride(
      id: 'r$createdAt',
      name: 'Fahrt',
      createdAt: createdAt,
      points: const [],
      stats: RideStats(distanceKm: distanceKm, ascentM: 0, descentM: 0),
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('generatePlan – 12-Wochen-Plan (Fortgeschritten)', () {
    // Zielwoche ist Index 11, Event am Samstag dieser Woche.
    final goal = goalAt(dayAfterFirstMonday(11 * 7 + 5));
    final plan = generatePlan(goal, advanced, now: now);

    test('Plan-Rahmendaten stimmen', () {
      expect(plan.weeks, hasLength(12));
      expect(plan.level, FitnessLevel.fortgeschritten);
      expect(plan.createdAt, now);
      expect(plan.goal.distanceKm, 160);
    });

    test('Wochenraster liegt Montag–Sonntag lokal', () {
      expect(plan.weeks.first.start, ms(firstMonday));
      for (var i = 0; i < plan.weeks.length; i++) {
        final week = plan.weeks[i];
        expect(week.index, i);
        final start = DateTime.fromMillisecondsSinceEpoch(week.start);
        final end = DateTime.fromMillisecondsSinceEpoch(week.end);
        expect(start.weekday, DateTime.monday);
        expect(start.hour, 0);
        expect(end.weekday, DateTime.monday);
        expect(end.hour, 0);
        if (i > 0) {
          expect(week.start, plan.weeks[i - 1].end);
        }
      }
      // Der Zieltermin liegt in der letzten Woche.
      expect(goal.date, greaterThanOrEqualTo(plan.weeks.last.start));
      expect(goal.date, lessThan(plan.weeks.last.end));
    });

    test('Wochenfolge: Aufbau×3, Erholung, Aufbau×3, Erholung, Aufbau×2, '
        'Taper, Zielwoche', () {
      expect(plan.weeks.map((w) => w.kind).toList(), [
        WeekKind.aufbau,
        WeekKind.aufbau,
        WeekKind.aufbau,
        WeekKind.erholung,
        WeekKind.aufbau,
        WeekKind.aufbau,
        WeekKind.aufbau,
        WeekKind.erholung,
        WeekKind.aufbau,
        WeekKind.aufbau,
        WeekKind.taper,
        WeekKind.zielwoche,
      ]);
    });

    test('lineare Progression von startKm zum Peak, alles auf 5 km gerundet',
        () {
      // startKm = max(95, 70) = 95; peak = min(max(160*1.3, 95), 95*2.2) = 208
      final builds = plan.weeks
          .where((w) => w.kind == WeekKind.aufbau)
          .map((w) => w.targetKm)
          .toList();
      expect(builds, [95, 110, 125, 145, 160, 175, 190, 210]);

      for (final week in plan.weeks) {
        expect(week.targetKm % 5 == 0 || week.kind == WeekKind.zielwoche, isTrue,
            reason: 'Woche ${week.index} nicht auf 5 km gerundet');
        expect(week.targetKm, greaterThanOrEqualTo(5));
      }
    });

    test('Erholungswochen liegen bei 60 % der Vorwoche', () {
      for (final week in plan.weeks) {
        if (week.kind != WeekKind.erholung) continue;
        final previous = plan.weeks[week.index - 1].targetKm;
        expect(week.targetKm, (previous * 0.6 / 5).round() * 5,
            reason: 'Erholungswoche ${week.index}');
      }
      expect(plan.weeks[3].targetKm, 75); // 60 % von 125
      expect(plan.weeks[7].targetKm, 105); // 60 % von 175
    });

    test('Taper liegt bei 50 % des Peaks', () {
      // Peak = 208 → 104 → auf 5 km gerundet 105
      expect(plan.weeks[10].kind, WeekKind.taper);
      expect(plan.weeks[10].targetKm, 105);
    });

    test('Zielwoche enthält Aktivierung und Zielevent', () {
      final zielwoche = plan.weeks.last;
      expect(zielwoche.kind, WeekKind.zielwoche);
      expect(zielwoche.sessions, hasLength(2));

      final activation = zielwoche.sessions.first;
      expect(activation.day, 'Di'); // Samstags-Event → Aktivierung Dienstag
      expect(activation.targetKm, 15);

      final event = zielwoche.sessions.last;
      expect(event.day, 'Sa');
      expect(event.title, 'Zielevent: Gravel Grinder');
      // targetKm der Zieleinheit entspricht der Zieldistanz.
      expect(event.targetKm, 160);
      // 2200 Hm ≥ 1000 → Höhenmeter-Hinweis in der Beschreibung.
      expect(event.description, contains('2200 Hm'));

      expect(zielwoche.targetKm,
          zielwoche.sessions.fold<int>(0, (sum, s) => sum + s.targetKm));
    });

    test('Session-Summen treffen das Wochenziel auf ±10 %', () {
      for (final week in plan.weeks) {
        if (week.kind == WeekKind.zielwoche) continue;
        final sum = week.sessions.fold<int>(0, (a, s) => a + s.targetKm);
        expect(sum, greaterThanOrEqualTo((week.targetKm * 0.9).floor()),
            reason: 'Woche ${week.index}');
        expect(sum, lessThanOrEqualTo((week.targetKm * 1.1).ceil()),
            reason: 'Woche ${week.index}');
      }
    });

    test('Aufbauwochen (Fortgeschritten) haben drei Einheiten mit '
        'Höhenmeter-Hinweis', () {
      final build = plan.weeks.first;
      expect(build.sessions.map((s) => s.day).toList(), ['Di', 'Do', 'Sa']);
      expect(build.sessions.map((s) => s.title).toList(),
          ['GA1', 'Intervalle', 'Lange Tour']);
      expect(build.sessions.last.description,
          contains('Baue dabei bewusst Anstiege ein'));
    });

    test('ohne nennenswerte Höhenmeter fehlt der Anstiegs-Hinweis', () {
      final flat = generatePlan(
        goalAt(dayAfterFirstMonday(11 * 7 + 5), ascentM: 400),
        advanced,
        now: now,
      );
      expect(flat.weeks.first.sessions.last.description,
          isNot(contains('Baue dabei bewusst Anstiege ein')));
      expect(flat.weeks.last.sessions.last.description, isNot(contains('Hm')));
    });
  });

  group('generatePlan – 3-Wochen-Minimalplan (Einsteiger)', () {
    // Zielwoche ist Index 2, Event am Samstag.
    final goal = goalAt(dayAfterFirstMonday(2 * 7 + 5),
        distanceKm: 30, ascentM: null);
    final plan = generatePlan(goal, beginner, now: now);

    test('drei Wochen: Aufbau, Taper, Zielwoche', () {
      expect(plan.weeks, hasLength(3));
      expect(plan.weeks.map((w) => w.kind).toList(),
          [WeekKind.aufbau, WeekKind.taper, WeekKind.zielwoche]);
    });

    test('Basisvolumen Einsteiger ist 40 km', () {
      // weeklyKm 12 < 40 → startKm = 40; peak = min(max(39, 40), 88) = 40
      expect(plan.weeks[0].targetKm, 40);
      expect(plan.weeks[1].targetKm, 20); // 50 % vom Peak
      expect(plan.level, FitnessLevel.einsteiger);
    });

    test('Einsteiger-Aufbau unter 60 km hat nur zwei Einheiten (40/60)', () {
      final sessions = plan.weeks[0].sessions;
      expect(sessions, hasLength(2));
      expect(sessions.map((s) => s.day).toList(), ['Di', 'Sa']);
      expect(sessions[0].targetKm, 16); // 40 % von 40
      expect(sessions[1].targetKm, 24); // 60 % von 40
    });

    test('Einsteiger-Aufbau ab 60 km bekommt zusätzlich Regeneration', () {
      final big = generatePlan(
        goalAt(dayAfterFirstMonday(2 * 7 + 5), distanceKm: 90, ascentM: null),
        beginner,
        now: now,
      );
      // peak = min(max(117, 40), 88) = 88 → round5 = 90
      final sessions = big.weeks[0].sessions;
      expect(big.weeks[0].targetKm, 90);
      expect(sessions, hasLength(3));
      expect(sessions.map((s) => s.day).toList(), ['Di', 'Sa', 'So']);
      expect(sessions.map((s) => s.targetKm).toList(), [27, 45, 18]);
    });

    test('Montags-Event bekommt keine Aktivierung', () {
      final mondayGoal = generatePlan(
        goalAt(dayAfterFirstMonday(2 * 7), distanceKm: 30, ascentM: null),
        beginner,
        now: now,
      );
      final zielwoche = mondayGoal.weeks.last;
      expect(zielwoche.sessions, hasLength(1));
      expect(zielwoche.sessions.single.day, 'Mo');
      expect(zielwoche.targetKm, 30);
    });

    test('Dienstags-Event bekommt Aktivierung am Montag', () {
      final tuesdayGoal = generatePlan(
        goalAt(dayAfterFirstMonday(2 * 7 + 1), distanceKm: 30, ascentM: null),
        beginner,
        now: now,
      );
      final zielwoche = tuesdayGoal.weeks.last;
      expect(zielwoche.sessions, hasLength(2));
      expect(zielwoche.sessions.first.day, 'Mo');
      expect(zielwoche.sessions.last.day, 'Di');
      expect(zielwoche.targetKm, 45);
    });
  });

  group('generatePlan – Fehlerfälle und Grenzen', () {
    Matcher throwsGerman(String message) => throwsA(isA<ArgumentError>()
        .having((e) => e.message, 'message', message));

    test('Ziel in dieser Woche ist zu nah', () {
      expect(
        () => generatePlan(goalAt(dayAfterFirstMonday(4)), advanced, now: now),
        throwsGerman(
            'Das Ziel liegt zu nah in der Zukunft – plane mindestens 3 Wochen ein.'),
      );
    });

    test('Ziel in 2 Wochen ist zu nah', () {
      expect(
        () => generatePlan(
            goalAt(dayAfterFirstMonday(7 + 3)), advanced, now: now),
        throwsGerman(
            'Das Ziel liegt zu nah in der Zukunft – plane mindestens 3 Wochen ein.'),
      );
    });

    test('Ziel in der Vergangenheit ist zu nah', () {
      expect(
        () => generatePlan(goalAt(dayAfterFirstMonday(-5)), advanced, now: now),
        throwsGerman(
            'Das Ziel liegt zu nah in der Zukunft – plane mindestens 3 Wochen ein.'),
      );
    });

    test('Ziel über 52 Wochen entfernt ist zu weit', () {
      expect(
        () => generatePlan(
            goalAt(dayAfterFirstMonday(52 * 7 + 3)), advanced, now: now),
        throwsGerman('Das Ziel liegt mehr als ein Jahr entfernt.'),
      );
    });

    test('Grenzen 3 und 52 Wochen sind gültig', () {
      final min = generatePlan(
          goalAt(dayAfterFirstMonday(2 * 7 + 3)), advanced, now: now);
      expect(min.weeks, hasLength(3));

      final max = generatePlan(
          goalAt(dayAfterFirstMonday(51 * 7 + 3)), advanced, now: now);
      expect(max.weeks, hasLength(52));
    });
  });

  group('currentWeekIndex', () {
    final plan = generatePlan(
        goalAt(dayAfterFirstMonday(11 * 7 + 5)), advanced, now: now);

    test('-1 vor Planbeginn', () {
      expect(currentWeekIndex(plan, now: plan.weeks.first.start - 1), -1);
      expect(currentWeekIndex(plan, now: dayAfterFirstMonday(-3)), -1);
    });

    test('liefert die laufende Woche', () {
      expect(currentWeekIndex(plan, now: now), 0);
      expect(currentWeekIndex(plan, now: plan.weeks.first.start), 0);
      expect(currentWeekIndex(plan, now: plan.weeks[5].start), 5);
      expect(currentWeekIndex(plan, now: plan.weeks[5].end - 1), 5);
      expect(currentWeekIndex(plan, now: plan.weeks[5].end), 6);
    });

    test('klemmt nach Planende auf die letzte Woche', () {
      expect(currentWeekIndex(plan, now: plan.weeks.last.end), 11);
      expect(
          currentWeekIndex(plan, now: plan.weeks.last.end + 90 * 86400000), 11);
    });
  });

  group('weekKm', () {
    final plan = generatePlan(
        goalAt(dayAfterFirstMonday(11 * 7 + 5)), advanced, now: now);

    test('summiert halboffen [start, end) auf eine Nachkommastelle', () {
      final week = plan.weeks[1];
      final rides = [
        ride(week.start - 1, 100), // davor
        ride(week.start, 12.34), // exakt am Start → zählt
        ride(week.start + 3 * 86400000, 20.01), // mitten drin
        ride(week.end - 1, 7.6), // letzte Millisekunde → zählt
        ride(week.end, 500), // exakt am Ende → zählt nicht
      ];
      expect(weekKm(week, rides), 40.0);
    });

    test('ohne passende Fahrten 0', () {
      expect(weekKm(plan.weeks[2], const <Ride>[]), 0.0);
      expect(weekKm(plan.weeks[2], [ride(plan.weeks[2].end, 50)]), 0.0);
    });
  });

  group('savePlan / loadPlan', () {
    setUp(() {
      SharedPreferences.setMockInitialValues({});
    });

    test('ohne gespeicherten Plan kommt null zurück', () async {
      expect(await loadPlan(), isNull);
    });

    test('JSON-Roundtrip erhält den vollständigen Plan', () async {
      final plan = generatePlan(
          goalAt(dayAfterFirstMonday(11 * 7 + 5)), advanced, now: now);
      await savePlan(plan);

      final loaded = await loadPlan();
      expect(loaded, isNotNull);
      expect(loaded!.createdAt, plan.createdAt);
      expect(loaded.level, plan.level);
      expect(loaded.goal.name, plan.goal.name);
      expect(loaded.goal.distanceKm, plan.goal.distanceKm);
      expect(loaded.goal.ascentM, plan.goal.ascentM);
      expect(loaded.goal.date, plan.goal.date);
      expect(loaded.weeks, hasLength(plan.weeks.length));

      for (var i = 0; i < plan.weeks.length; i++) {
        final a = plan.weeks[i];
        final b = loaded.weeks[i];
        expect(b.index, a.index);
        expect(b.start, a.start);
        expect(b.end, a.end);
        expect(b.kind, a.kind);
        expect(b.targetKm, a.targetKm);
        expect(b.sessions.map((s) => s.day).toList(),
            a.sessions.map((s) => s.day).toList());
        expect(b.sessions.map((s) => s.title).toList(),
            a.sessions.map((s) => s.title).toList());
        expect(b.sessions.map((s) => s.description).toList(),
            a.sessions.map((s) => s.description).toList());
        expect(b.sessions.map((s) => s.targetKm).toList(),
            a.sessions.map((s) => s.targetKm).toList());
      }
    });

    test('savePlan(null) entfernt den gespeicherten Plan', () async {
      final plan = generatePlan(
          goalAt(dayAfterFirstMonday(11 * 7 + 5)), advanced, now: now);
      await savePlan(plan);
      expect(await loadPlan(), isNotNull);

      await savePlan(null);
      expect(await loadPlan(), isNull);
    });
  });
}
