/// Trainingsplan-Generator und -Persistenz.
///
/// Semantisch identisch zur Web-App-Referenz (training.ts): gleiches
/// Wochenraster (Montag–Sonntag lokaler Zeit), gleiche Progression und
/// gleiches JSON-Format, damit Pläne zwischen Web-App und dieser App
/// austauschbar bleiben.
library;

import 'dart:convert';
import 'dart:math' as math;

import 'package:shared_preferences/shared_preferences.dart';

import 'health_sync.dart';
import 'models.dart';

const String _storageKey = 'trailscape.plan';

const List<String> _weekdays = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];

const int _minWeeks = 3;
const int _maxWeeks = 52;

const String errorTooSoon =
    'Das Ziel liegt zu nah in der Zukunft – plane mindestens 3 Wochen ein.';
const String errorTooFar = 'Das Ziel liegt mehr als ein Jahr entfernt.';

/// Basisvolumen pro Woche in km, falls die bisherige Belastung darunter liegt.
const Map<FitnessLevel, double> _levelBaseKm = {
  FitnessLevel.einsteiger: 40,
  FitnessLevel.fortgeschritten: 70,
  FitnessLevel.ambitioniert: 110,
};

const double _recoveryFactor = 0.6;
const double _taperFactor = 0.5;
const double _peakDistanceFactor = 1.3;
const double _peakCapFactor = 2.2;
const int _activationKm = 15;
const String _climbingHint =
    ' Baue dabei bewusst Anstiege ein, um dich an die Höhenmeter des Ziels zu gewöhnen.';
const double _climbHintThresholdM = 1000;

const int _weekMs = 7 * 24 * 60 * 60 * 1000;

/// Montag 00:00 lokaler Zeit der Woche, in der [timestamp] liegt.
int _startOfWeek(int timestamp) {
  final date = DateTime.fromMillisecondsSinceEpoch(timestamp);
  // DateTime.weekday: 1 = Montag … 7 = Sonntag
  final offset = date.weekday - 1;
  // Kalenderarithmetik statt Duration – dadurch DST-sicher auf 00:00 lokal.
  return DateTime(date.year, date.month, date.day - offset)
      .millisecondsSinceEpoch;
}

/// Addiert [weeks] Wochen und bleibt dabei DST-sicher auf 00:00 lokaler Zeit.
int _addWeeks(int timestamp, int weeks) {
  final date = DateTime.fromMillisecondsSinceEpoch(timestamp);
  return DateTime(date.year, date.month, date.day + weeks * 7)
      .millisecondsSinceEpoch;
}

/// Index des Wochentags (0 = Mo … 6 = So).
int _weekdayIndex(int timestamp) =>
    DateTime.fromMillisecondsSinceEpoch(timestamp).weekday - 1;

int _round5(double km) => math.max(5, (km / 5).round() * 5);

double _round1(double value) => (value * 10).round() / 10;

int _sessionKm(int weekKmTarget, double share) =>
    math.max(1, (weekKmTarget * share).round());

String _longTourDescription(Goal goal) {
  const base =
      'Die Schlüsseleinheit der Woche: gleichmäßig im Grundlagentempo fahren und konsequent essen und trinken.';
  final ascentM = goal.ascentM;
  if (ascentM != null && ascentM >= _climbHintThresholdM) {
    return base + _climbingHint;
  }
  return base;
}

List<TrainingSession> _buildSessions(
  WeekKind kind,
  FitnessLevel level,
  int targetKm,
  Goal goal,
) {
  if (kind == WeekKind.zielwoche) {
    return _zielwocheSessions(goal);
  }

  if (kind == WeekKind.erholung) {
    return [
      TrainingSession(
        day: 'Di',
        title: 'Lockere Ausfahrt',
        description:
            'Entspannt rollen, kleine Gänge und hohe Trittfrequenz – diese Woche dient ausschließlich der Erholung.',
        targetKm: _sessionKm(targetKm, 0.5),
      ),
      TrainingSession(
        day: 'Sa',
        title: 'Ruhige Runde',
        description:
            'Gemütliche Ausfahrt ohne Leistungsdruck, halte den Puls durchgehend im niedrigen Bereich.',
        targetKm: _sessionKm(targetKm, 0.5),
      ),
    ];
  }

  if (kind == WeekKind.taper) {
    return [
      TrainingSession(
        day: 'Di',
        title: 'Locker mit Antritten',
        description:
            'Locker rollen und dabei 3 kurze Antritte über je 30 Sekunden einstreuen, um spritzig zu bleiben.',
        targetKm: _sessionKm(targetKm, 0.55),
      ),
      TrainingSession(
        day: 'Do',
        title: 'Kurze lockere Ausfahrt',
        description:
            'Kurz und ruhig fahren, danach Material checken und die Beine bewusst schonen.',
        targetKm: _sessionKm(targetKm, 0.45),
      ),
    ];
  }

  return _aufbauSessions(level, targetKm, goal);
}

List<TrainingSession> _aufbauSessions(
  FitnessLevel level,
  int targetKm,
  Goal goal,
) {
  if (level == FitnessLevel.einsteiger) {
    final withRecovery = targetKm >= 60;
    final sessions = <TrainingSession>[
      TrainingSession(
        day: 'Di',
        title: 'Lockere Ausfahrt GA1',
        description:
            'Ruhiges Grundlagentempo – du solltest dich während der gesamten Fahrt unterhalten können.',
        targetKm: _sessionKm(targetKm, withRecovery ? 0.3 : 0.4),
      ),
      TrainingSession(
        day: 'Sa',
        title: 'Lange Tour',
        description: _longTourDescription(goal),
        targetKm: _sessionKm(targetKm, withRecovery ? 0.5 : 0.6),
      ),
    ];
    if (withRecovery) {
      sessions.add(TrainingSession(
        day: 'So',
        title: 'Regeneration locker',
        description:
            'Kurze Regenerationsrunde im leichten Gang, bewusst niedrige Intensität für frische Beine.',
        targetKm: _sessionKm(targetKm, 0.2),
      ));
    }
    return sessions;
  }

  if (level == FitnessLevel.fortgeschritten) {
    return [
      TrainingSession(
        day: 'Di',
        title: 'GA1',
        description:
            'Lockere Grundlageneinheit zum Auffüllen des Wochenvolumens, Puls konstant im GA1-Bereich halten.',
        targetKm: _sessionKm(targetKm, 0.25),
      ),
      TrainingSession(
        day: 'Do',
        title: 'Intervalle',
        description:
            'Nach 20 Minuten Einfahren 4×8 Minuten zügig im Schwellenbereich, dazwischen 4 Minuten locker rollen.',
        targetKm: _sessionKm(targetKm, 0.2),
      ),
      TrainingSession(
        day: 'Sa',
        title: 'Lange Tour',
        description: _longTourDescription(goal),
        targetKm: _sessionKm(targetKm, 0.55),
      ),
    ];
  }

  return [
    TrainingSession(
      day: 'Di',
      title: 'GA1',
      description:
          'Ruhige Grundlageneinheit, gleichmäßige Belastung ohne Spitzen und ohne Sprints.',
      targetKm: _sessionKm(targetKm, 0.2),
    ),
    TrainingSession(
      day: 'Mi',
      title: 'Intervalle',
      description:
          'Nach dem Einfahren 5×6 Minuten hart an der Schwelle mit je 3 Minuten lockerer Pause dazwischen.',
      targetKm: _sessionKm(targetKm, 0.2),
    ),
    TrainingSession(
      day: 'Sa',
      title: 'Lange Tour',
      description: _longTourDescription(goal),
      targetKm: _sessionKm(targetKm, 0.45),
    ),
    TrainingSession(
      day: 'So',
      title: 'GA1 kompensatorisch',
      description:
          'Kompensationsrunde mit hoher Trittfrequenz, um die Beine nach der langen Tour wieder locker zu fahren.',
      targetKm: _sessionKm(targetKm, 0.15),
    ),
  ];
}

List<TrainingSession> _zielwocheSessions(Goal goal) {
  final eventIndex = _weekdayIndex(goal.date);
  final eventDay = _weekdays[eventIndex];
  final eventKm = math.max(1, goal.distanceKm.round());
  final ascentM = goal.ascentM;

  final eventSession = TrainingSession(
    day: eventDay,
    title: 'Zielevent: ${goal.name}',
    description: ascentM != null && ascentM >= _climbHintThresholdM
        ? 'Dein Zielevent über $eventKm km und rund ${ascentM.round()} Hm – teile dir die Kraft an den Anstiegen ein und trinke von Beginn an regelmäßig.'
        : 'Dein Zielevent über $eventKm km – starte kontrolliert, halte dein Tempo und versorge dich unterwegs konsequent.',
    targetKm: eventKm,
  );

  final activationDay = eventIndex > 1 ? 'Di' : (eventIndex == 1 ? 'Mo' : null);
  if (activationDay == null) {
    return [eventSession];
  }

  return [
    TrainingSession(
      day: activationDay,
      title: 'Aktivierung locker',
      description:
          'Kurze lockere Runde mit ein paar Antritten, danach Rad und Verpflegung für den Zieltag vorbereiten.',
      targetKm: _activationKm,
    ),
    eventSession,
  ];
}

List<WeekKind> _planWeekKinds(int weekCount) {
  final kinds = <WeekKind>[];
  final lastBuildIndex = weekCount - 3;

  for (var i = 0; i < weekCount; i++) {
    if (i == weekCount - 1) {
      kinds.add(WeekKind.zielwoche);
    } else if (i == weekCount - 2) {
      kinds.add(WeekKind.taper);
    } else if (i % 4 == 3 && i != lastBuildIndex) {
      // Jede 4. Woche ist Erholung – außer sie wäre die letzte Aufbauwoche
      // vor dem Taper.
      kinds.add(WeekKind.erholung);
    } else {
      kinds.add(WeekKind.aufbau);
    }
  }

  return kinds;
}

/// Erzeugt einen Trainingsplan vom Montag der aktuellen Woche bis zur
/// Zielwoche.
///
/// Wirft [ArgumentError], wenn das Ziel weniger als 3 oder mehr als 52 Wochen
/// entfernt liegt.
TrainingPlan generatePlan(Goal goal, FitnessAssessment assessment, {int? now}) {
  final nowMs = now ?? DateTime.now().millisecondsSinceEpoch;
  final firstMonday = _startOfWeek(nowMs);
  final goalMonday = _startOfWeek(goal.date);
  final weekCount = ((goalMonday - firstMonday) / _weekMs).round() + 1;

  if (weekCount < _minWeeks) {
    throw ArgumentError(errorTooSoon);
  }
  if (weekCount > _maxWeeks) {
    throw ArgumentError(errorTooFar);
  }

  final level = assessment.level;
  final startKm = math.max(assessment.weeklyKm, _levelBaseKm[level]!);
  final peakKm = math.min(
    math.max(goal.distanceKm * _peakDistanceFactor, startKm),
    startKm * _peakCapFactor,
  );

  final kinds = _planWeekKinds(weekCount);
  final buildCount = kinds.where((kind) => kind == WeekKind.aufbau).length;

  final weeks = <TrainingWeek>[];
  var buildSeen = 0;
  var previousKm = startKm;

  for (var i = 0; i < weekCount; i++) {
    final kind = kinds[i];
    int targetKm;

    if (kind == WeekKind.aufbau) {
      final progress = buildCount > 1 ? buildSeen / (buildCount - 1) : 1.0;
      targetKm = _round5(startKm + (peakKm - startKm) * progress);
      buildSeen += 1;
    } else if (kind == WeekKind.erholung) {
      targetKm = _round5(previousKm * _recoveryFactor);
    } else if (kind == WeekKind.taper) {
      targetKm = _round5(peakKm * _taperFactor);
    } else {
      final sessions = _zielwocheSessions(goal);
      targetKm = sessions.fold(0, (sum, session) => sum + session.targetKm);
    }

    previousKm = targetKm.toDouble();

    weeks.add(TrainingWeek(
      index: i,
      start: _addWeeks(firstMonday, i),
      end: _addWeeks(firstMonday, i + 1),
      kind: kind,
      targetKm: targetKm,
      sessions: _buildSessions(kind, level, targetKm, goal),
    ));
  }

  return TrainingPlan(
    createdAt: nowMs,
    goal: goal,
    level: level,
    weeks: weeks,
  );
}

/// Lädt den gespeicherten Plan; `null`, wenn keiner existiert oder die Daten
/// unbrauchbar sind.
Future<TrainingPlan?> loadPlan() async {
  try {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_storageKey);
    if (raw == null) {
      return null;
    }

    final parsed = jsonDecode(raw);
    if (parsed is! Map<String, dynamic> || parsed['weeks'] is! List) {
      return null;
    }
    return TrainingPlan.fromJson(parsed);
  } catch (_) {
    // Speicher nicht verfügbar oder Daten defekt – kein Plan.
    return null;
  }
}

/// Speichert den Plan; `null` entfernt den gespeicherten Plan.
Future<void> savePlan(TrainingPlan? plan) async {
  try {
    final prefs = await SharedPreferences.getInstance();
    if (plan == null) {
      await prefs.remove(_storageKey);
      return;
    }
    await prefs.setString(_storageKey, jsonEncode(plan.toJson()));
  } catch (_) {
    // Speicher nicht verfügbar – Plan bleibt nur im Arbeitsspeicher.
  }
}

/// Index der aktuellen Planwoche; -1 vor Planbeginn, letzte Woche nach Planende.
int currentWeekIndex(TrainingPlan plan, {int? now}) {
  final nowMs = now ?? DateTime.now().millisecondsSinceEpoch;
  final weeks = plan.weeks;

  if (weeks.isEmpty) {
    return -1;
  }
  if (nowMs < weeks.first.start) {
    return -1;
  }

  for (final week in weeks) {
    if (nowMs >= week.start && nowMs < week.end) {
      return week.index;
    }
  }

  return weeks.length - 1;
}

/// Summiert die tatsächlich gefahrenen Kilometer im Zeitraum [start, end).
double weekKm(TrainingWeek week, List<Ride> rides) {
  var total = 0.0;
  for (final ride in rides) {
    if (ride.createdAt >= week.start && ride.createdAt < week.end) {
      total += ride.stats.distanceKm;
    }
  }
  return _round1(total);
}

// ---------------------------------------------------------------------------
// Erholungs-Empfehlung anhand der Vitaldaten (Zusatzschicht auf dem Plan)
// ---------------------------------------------------------------------------

/// Ab welcher relativen Ruhepuls-Erhöhung gegenüber der Vorwoche eine
/// Erholungswoche empfohlen wird.
const double recoveryHeartRateThresholdPercent = 5.0;

/// Unterhalb welcher durchschnittlichen Schlafdauer (Stunden/Nacht der
/// letzten 7 Tage) eine Erholungswoche empfohlen wird.
const double recoverySleepThresholdHours = 6.0;

/// Faktor, um den das Wochenziel bei einer Erholungsempfehlung reduziert
/// wird.
const double recoveryTargetFactor = 0.7;

/// Empfehlung zur kurzfristigen Trainingsanpassung anhand der zuletzt
/// gelesenen Vitaldaten. Ergänzt den generierten Plan, ersetzt ihn aber
/// nicht: [generatePlan] und die bestehenden Wochenziele bleiben unverändert.
class RecoveryAdvice {
  const RecoveryAdvice({
    required this.reduceIntensity,
    required this.message,
    this.adjustedTargetKm,
  });

  /// Ob eine Erholungswoche empfohlen wird.
  final bool reduceIntensity;

  /// Für die UI geeigneter deutscher Hinweistext.
  final String message;

  /// Auf [recoveryTargetFactor] reduziertes Wochenziel, gerundet auf 5 km
  /// (mindestens 5 km). `null`, wenn kein aktuelles Wochenziel übergeben
  /// wurde oder keine Reduktion empfohlen wird.
  final int? adjustedTargetKm;
}

/// Leitet aus [vitals] eine Trainingsempfehlung ab.
///
/// Eine Erholungswoche wird empfohlen, wenn
///
///  * der Ruhepuls-7-Tage-Schnitt mindestens [recoveryHeartRateThresholdPercent]
///    % über der Vorwoche liegt (nur bewertbar, wenn beide Wochen Daten
///    haben — [VitalsTrend.hasTrend]), **oder**
///  * der Schlaf-7-Tage-Schnitt unter [recoverySleepThresholdHours] Stunden
///    liegt.
///
/// Fehlen beide Datengrundlagen, gilt das nicht als Warnsignal — es wird die
/// normale Empfehlung zurückgegeben (kein Advice, keine Reduktion).
///
/// [currentTargetKm] ist das Ziel der aktuellen Planwoche; wird eine
/// Erholungswoche empfohlen, liefert [RecoveryAdvice.adjustedTargetKm] das um
/// [recoveryTargetFactor] reduzierte, auf 5 km gerundete Ziel.
RecoveryAdvice buildRecoveryAdvice(
  VitalsSummary vitals, {
  int? currentTargetKm,
}) {
  final hr = vitals.restingHeartRate;
  final sleep = vitals.sleepHours;

  final heartRateElevated = hr.hasTrend &&
      hr.deltaPercent != null &&
      hr.deltaPercent! >= recoveryHeartRateThresholdPercent;

  final sleepLow =
      sleep.lastWeekAvg != null && sleep.lastWeekAvg! < recoverySleepThresholdHours;

  if (!heartRateElevated && !sleepLow) {
    return const RecoveryAdvice(
      reduceIntensity: false,
      message:
          'Deine Vitalwerte sehen unauffällig aus – bleib beim geplanten Training.',
    );
  }

  final reasons = [
    if (heartRateElevated) 'erhöhter Ruhepuls',
    if (sleepLow) 'wenig Schlaf',
  ].join(' und ');

  final adjustedTargetKm =
      currentTargetKm == null ? null : _round5(currentTargetKm * recoveryTargetFactor);

  return RecoveryAdvice(
    reduceIntensity: true,
    message: 'Erholungswoche: Intensität reduzieren ($reasons).',
    adjustedTargetKm: adjustedTargetKm,
  );
}
