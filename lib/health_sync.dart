/// Anbindung an Google Health Connect.
///
/// Auf Samsung-Geräten spiegelt Samsung Health die auf der Galaxy Watch
/// aufgezeichneten Trainings sowie Vitaldaten nach Health Connect. Dieses
/// Modul liest von dort
///
///  * Rad-Workouts (ExerciseSession) inklusive GPS-Route und Herzfrequenz und
///    bildet sie auf das bestehende [Ride]-Modell ab, und
///  * Vitaldaten (Ruhepuls, Schlaf, HRV) als Tagesserien mit Wochentrend.
///
/// Alle Plugin-Aufrufe laufen über die Abstraktion [HealthGateway], damit die
/// Ableitungs- und Aggregationslogik ohne Gerät testbar bleibt. Die
/// produktive Implementierung ist [HealthPluginGateway] (Paket `health`).
///
/// Bekannte Grenzen der Plattform (Stand `health` 13.3.x):
///
///  * **VO2max** wird vom Paket gar nicht angeboten (kein `HealthDataType`
///    dafür, weder für Health Connect noch für HealthKit). Health Connect
///    selbst kennt den Datentyp (`Vo2MaxRecord`) sehr wohl — Trailscape liest
///    ihn deshalb über einen eigenen, schmalen Platform-Channel
///    ([healthExtraChannelName], Kotlin-Seite `HealthExtraChannel`).
///    Schlägt der Channel fehl (alte Installation, Health Connect fehlt,
///    Berechtigung verweigert), landet VO2max in
///    [VitalsSummary.unavailable]; die übrigen Vitaldaten bleiben gültig.
///  * **HRV (rMSSD)** kennt das Paket dagegen sehr wohl
///    (`HEART_RATE_VARIABILITY_RMSSD` → Health Connects
///    `HeartRateVariabilityRmssdRecord`) — sie läuft daher über den normalen
///    Plugin-Weg, nur die Berechtigung ist optional ([healthOptionalReadTypes]).
///  * **Trainings** liest das Paket zwar, reichert sie aber intern mit
///    Distanz-, Kalorien- und Schritt-Datensätzen an und fängt jeden Fehler
///    dabei ab — Ergebnis ist dann eine leere Liste ohne Meldung. Trailscape
///    fragt deshalb `STEPS` mit an ([healthOptionalReadTypes]) und hält als
///    Rückfallebene einen nativen Session-Reader bereit
///    ([HealthGateway.readExerciseSessionsNative]).
///  * **GPS-Routen** liefert Health Connect nur, solange die App im
///    Vordergrund läuft, und für fremde Apps (z. B. Samsung Health) nur, wenn
///    die Nutzerin in der Health-Connect-App unter
///    „App-Berechtigungen → Trailscape → Trainingsrouten“ dauerhaft zugestimmt
///    hat. Fehlt die Route, wird die Tour ohne Trackpunkte importiert
///    (Distanz/Dauer/Herzfrequenz bleiben erhalten).
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/services.dart';
import 'package:health/health.dart' as hc;
import 'package:shared_preferences/shared_preferences.dart';

import 'models.dart';
import 'stats.dart';

/// Speicherschlüssel für den Zeitpunkt des letzten Imports (ms seit Epoch).
const String healthSyncStorageKey = 'trailscape.healthsync';

/// Wie weit zurück importiert wird, wenn noch nie synchronisiert wurde.
///
/// Bewusst 30 Tage: Ohne die zusätzliche Historien-Freigabe
/// (`READ_HEALTH_DATA_HISTORY`, siehe
/// [HealthPluginGateway.requestHistoryAccess]) gibt Health Connect
/// grundsätzlich nur Daten der letzten 30 Tage ab Zustimmung heraus. Ein
/// größeres Fenster erzeugt daher nur unnötige Abfragen und weckt in der UI
/// falsche Erwartungen.
const Duration healthSyncInitialWindow = Duration(days: 30);

/// Ab welchem zeitlichen Überlappungsanteil eine Health-Connect-Session als
/// bereits vorhandene Tour gilt und übersprungen wird (strikt größer).
const double healthSyncOverlapThreshold = 0.5;

/// Maximaler zeitlicher Abstand, in dem eine Herzfrequenz-Messung beim
/// Anreichern einer bestehenden Tour noch einem Trackpunkt zugeordnet wird.
const Duration healthSyncHrMergeTolerance = Duration(seconds: 60);

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

/// Eine Trainings-Session, wie sie der native Reader
/// (`HealthExtraChannel.readExerciseSessions`) roh aus Health Connect liefert.
///
/// Absichtlich unangereichert: nur die Felder des `ExerciseSessionRecord`
/// selbst, ohne Distanz-, Kalorien- oder Schrittdaten.
class HealthSessionInfo {
  const HealthSessionInfo({
    required this.uid,
    required this.start,
    required this.end,
    required this.typeCode,
    required this.typeName,
    this.title,
    this.source,
    this.hasRoute = false,
  });

  /// `metadata.id` des Datensatzes — dieselbe ID, die das `health`-Paket als
  /// `uuid` bzw. `workoutUuid` meldet.
  final String uid;
  final DateTime start;
  final DateTime end;

  /// Rohe androidx-Konstante (`ExerciseSessionRecord.EXERCISE_TYPE_*`).
  final int typeCode;

  /// Name der Konstante, z. B. `EXERCISE_TYPE_BIKING`, sonst `TYPE_<int>`.
  final String typeName;

  /// Titel der Session, sofern die Quell-App einen setzt.
  final String? title;

  /// Paketname der Quell-App.
  final String? source;

  /// Ob Health Connect die GPS-Route ohne weitere Zustimmung herausrückt.
  final bool hasRoute;
}

/// Rohdiagnose eines [HealthGateway.readWorkouts]-Aufrufs.
///
/// Beantwortet die Frage, ob das `health`-Paket überhaupt Datenpunkte geliefert
/// hat und ob deren `value` der erwartete `WorkoutHealthValue` war.
class HealthWorkoutReadDiagnostics {
  const HealthWorkoutReadDiagnostics({
    required this.rawPointCount,
    required this.valueTypeCounts,
    required this.activityTypeCounts,
  });

  const HealthWorkoutReadDiagnostics.empty()
      : rawPointCount = 0,
        valueTypeCounts = const {},
        activityTypeCounts = const {};

  /// Wie viele Datenpunkte das Plugin zurückgegeben hat.
  final int rawPointCount;

  /// Laufzeittyp von `HealthDataPoint.value` → Anzahl.
  final Map<String, int> valueTypeCounts;

  /// Aktivitätstyp des Plugins → Anzahl (nur für Workout-Punkte).
  final Map<String, int> activityTypeCounts;

  /// Kompakte deutsche Zusammenfassung für [HealthSyncReport.debugLines].
  String describe() {
    final types = valueTypeCounts.isEmpty
        ? 'keine'
        : valueTypeCounts.entries.map((e) => '${e.key}×${e.value}').join(', ');
    final kinds = activityTypeCounts.isEmpty
        ? 'keine'
        : activityTypeCounts.entries
            .map((e) => '${e.key}×${e.value}')
            .join(', ');
    return 'Plugin: $rawPointCount Rohpunkt(e); Werttypen: $types; '
        'Aktivitätstypen: $kinds';
  }
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

  /// Rohdiagnose des letzten [readWorkouts]-Aufrufs, `null` wenn die
  /// Implementierung keine erhebt.
  HealthWorkoutReadDiagnostics? get lastWorkoutDiagnostics => null;

  /// Trainings-Sessions über den nativen Reader, am `health`-Paket vorbei.
  ///
  /// Rückfallebene für den Fall, dass das Plugin gar nichts oder nichts
  /// Verwertbares liefert. Wirft, wenn der Kanal fehlt (alte Installation) oder
  /// Health Connect den Zugriff verweigert; die Vorgabe meldet „nicht
  /// unterstützt“.
  Future<List<HealthSessionInfo>> readExerciseSessionsNative({
    required DateTime from,
    required DateTime to,
  }) async =>
      throw UnsupportedError('Kein nativer Session-Reader verfügbar.');

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
  /// liest ihn deshalb über einen eigenen Platform-Channel und wirft, wenn
  /// dieser nicht antwortet.
  Future<List<HealthNumericSample>> readVo2Max({
    required DateTime from,
    required DateTime to,
  });

  /// Herzratenvariabilität (rMSSD) im Zeitraum, Werte in Millisekunden.
  ///
  /// Anders als VO2max deckt das `health`-Paket den Typ auf Android ab
  /// (`HEART_RATE_VARIABILITY_RMSSD` → `HeartRateVariabilityRmssdRecord`), es
  /// braucht also keinen eigenen Channel. Die Galaxy Watch schreibt die Werte
  /// im Schlaf; tagsüber kommen meist keine dazu.
  Future<List<HealthNumericSample>> readHrv({
    required DateTime from,
    required DateTime to,
  });
}

/// Ergebnis eines Import-Laufs — für Diagnose und UI-Rückmeldung.
///
/// Wird von [HealthSyncService.importWithReport] geliefert. Weder [imported]
/// noch [mergedRides] sind gespeichert; das übernimmt der Aufrufer.
class HealthSyncReport {
  const HealthSyncReport({
    required this.from,
    required this.to,
    required this.workoutsFound,
    required this.imported,
    required this.mergedRides,
    required this.duplicatesSkipped,
    required this.routesMissing,
    this.debugLines = const [],
  });

  /// Leerer Bericht (kein Fenster betrachtet).
  const HealthSyncReport.empty(this.from, this.to)
      : workoutsFound = 0,
        imported = const [],
        mergedRides = const [],
        duplicatesSkipped = 0,
        routesMissing = 0,
        debugLines = const [];

  /// Betrachteter Zeitraum.
  final DateTime from;
  final DateTime to;

  /// Anzahl der im Fenster gefundenen **Rad**-Sessions (vor jeder Filterung).
  final int workoutsFound;

  /// Neu angelegte Touren.
  final List<Ride> imported;

  /// Bestehende Touren, die um Herzfrequenzdaten aus einer überlappenden
  /// Watch-Session ergänzt wurden (gleiche ID wie das Original).
  final List<Ride> mergedRides;

  /// Sessions, die als Duplikat verworfen wurden (gleiche ID oder
  /// überlappende Tour, die bereits Herzfrequenzdaten hat).
  final int duplicatesSkipped;

  /// Importierte Outdoor-Touren, für die Health Connect keine Route
  /// herausgerückt hat (Trackpunkte fehlen).
  final int routesMissing;

  /// Technische Notizen des Laufs (deutsch, kompakt) für die Fehlersuche auf
  /// dem Gerät: was das Plugin roh geliefert hat, welche Sessions daraus
  /// wurden, was der native Reader sah und ob die Rückfallebene griff.
  final List<String> debugLines;

  /// Wie viele Touren der Aufrufer speichern muss.
  int get changedCount => imported.length + mergedRides.length;

  /// Ob der Lauf nichts verändert hat.
  bool get isEmpty => changedCount == 0;
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
enum VitalsDataKind { ruhepuls, schlaf, vo2max, hrv }

/// Ergebnis von [HealthSyncService.readVitals].
class VitalsSummary {
  const VitalsSummary({
    required this.days,
    required this.from,
    required this.to,
    required this.restingHeartRate,
    required this.sleepHours,
    this.heartRateVariability = const VitalsTrend.empty(),
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

  /// Herzratenvariabilität (rMSSD) in ms je Tag — ein repräsentativer Wert je
  /// Kalendertag, siehe [dailyHrvValues].
  final VitalsTrend heartRateVariability;

  /// Zuletzt gemessener VO2max-Wert, falls die Plattform ihn liefert.
  final double? vo2max;

  /// Zeitpunkt der VO2max-Messung.
  final DateTime? vo2maxAt;

  /// Datentypen, die nicht gelesen werden konnten (fehlende Berechtigung,
  /// Plattform-Grenze, Fehler). Die übrigen Werte bleiben trotzdem gültig.
  final Set<VitalsDataKind> unavailable;

  /// Ob überhaupt Daten vorliegen.
  bool get isEmpty =>
      !restingHeartRate.hasData &&
      !sleepHours.hasData &&
      !heartRateVariability.hasData &&
      vo2max == null;
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
  /// Dünner Wrapper um [importWithReport] — liefert nur die neu angelegten
  /// Touren. Bestehende Touren, die dabei um Herzfrequenzdaten ergänzt wurden
  /// ([HealthSyncReport.mergedRides]), sieht diese Signatur nicht; wer sie
  /// speichern will, ruft [importWithReport] direkt auf.
  Future<List<Ride>> importNewRides({
    DateTime? since,
    required List<Ride> existing,
  }) async {
    final report = await importWithReport(since: since, existing: existing);
    return report.imported;
  }

  /// Importiert neue Rad-Workouts und liefert zusätzlich eine Diagnose.
  ///
  /// [since] begrenzt den betrachteten Zeitraum; ohne Angabe wird der
  /// gespeicherte Zeitstempel des letzten Imports benutzt, andernfalls
  /// [healthSyncInitialWindow].
  ///
  /// [existing] sind die bereits gespeicherten Touren. Eine Session wird
  ///
  ///  * übersprungen, wenn ihre ID schon vorkommt;
  ///  * als **Merge** behandelt, wenn sie sich zu mehr als
  ///    [healthSyncOverlapThreshold] mit einer bestehenden Tour überschneidet
  ///    und diese Tour noch **keine** Herzfrequenzdaten hat — dann wird die
  ///    bestehende Tour aus den Watch-Messwerten angereichert und landet
  ///    (mit unveränderter ID) in [HealthSyncReport.mergedRides];
  ///  * sonst als Duplikat verworfen ([HealthSyncReport.duplicatesSkipped]).
  ///
  /// Die zurückgegebenen Touren sind **noch nicht gespeichert** — das
  /// übernimmt die UI. Nach einem erfolgreichen Durchlauf wird der
  /// Import-Zeitstempel auf „jetzt“ gesetzt.
  ///
  /// Wirft [HealthSyncException], wenn Health Connect nicht verfügbar ist
  /// oder die Berechtigungen fehlen.
  Future<HealthSyncReport> importWithReport({
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

    final debug = <String>[
      'Zeitraum: ${_debugTime(from)} – ${_debugTime(to)}',
    ];

    final List<HealthWorkout> workouts;
    try {
      workouts = await gateway.readWorkouts(from: from, to: to);
    } catch (error) {
      throw HealthSyncException(
        'Die Trainings konnten nicht aus Health Connect gelesen werden: $error',
      );
    }

    final diagnostics = gateway.lastWorkoutDiagnostics;
    debug.add(diagnostics?.describe() ?? 'Plugin: keine Rohdiagnose erhoben');
    debug.add('Plugin: ${workouts.length} Session(s) gemappt');
    for (final workout in workouts.take(_debugSessionLimit)) {
      debug.add(
        '  · ${workout.kind.name} ${_debugTime(workout.start)}'
        '–${_debugTime(workout.end)} · ${workout.sourceName ?? 'ohne Quelle'}',
      );
    }

    var cycling = workouts.where((w) => w.isCycling).toList()
      ..sort((a, b) => a.start.compareTo(b.start));
    debug.add('Plugin: ${cycling.length} Rad-Session(s)');

    var fallbackUsed = false;
    if (cycling.isEmpty) {
      final fallback = await _readNativeSessions(from: from, to: to, log: debug);
      if (fallback.isNotEmpty) {
        fallbackUsed = true;
        cycling = fallback;
      }
    }
    debug.add(fallbackUsed
        ? 'Fallback: aktiv, ${cycling.length} Rad-Session(s) aus dem nativen '
            'Reader'
        : 'Fallback: nicht verwendet');

    // Zeitraum plus (falls bekannt) die dahinterstehende Tour. Innerhalb
    // dieses Laufs importierte Sessions kommen ohne Tour dazu, damit zwei
    // nahezu identische Sessions nicht doppelt landen.
    final ranges = <({DateTime start, DateTime end, Ride? ride})>[];
    for (final ride in existing) {
      final range = rideTimeRange(ride);
      ranges.add((start: range.start, end: range.end, ride: ride));
    }
    final knownIds = existing.map((r) => r.id).toSet();
    final mergeTargets = <String>{};

    final candidates = <HealthWorkout>[];
    final mergeCandidates = <({HealthWorkout workout, Ride ride})>[];
    var duplicates = 0;

    for (final workout in cycling) {
      if (knownIds.contains(healthRideId(workout.id))) {
        duplicates++;
        continue;
      }

      final overlap = _findOverlap(workout, ranges);
      if (overlap == null) {
        candidates.add(workout);
        ranges.add((start: workout.start, end: workout.end, ride: null));
        knownIds.add(healthRideId(workout.id));
        continue;
      }

      final ride = overlap.ride;
      // Nur bestehende Touren ohne Herzfrequenz werden angereichert, und jede
      // höchstens einmal je Lauf.
      if (ride == null || rideHasHeartRate(ride) || !mergeTargets.add(ride.id)) {
        duplicates++;
        continue;
      }
      mergeCandidates.add((workout: workout, ride: ride));
    }

    final imported = <Ride>[];
    var routesMissing = 0;

    if (candidates.isNotEmpty) {
      final windowStart = candidates.first.start;
      final windowEnd =
          candidates.map((w) => w.end).reduce((a, b) => a.isAfter(b) ? a : b);

      // Routen sind pro Session wenige Datensätze — eine Abfrage über das
      // ganze Fenster reicht. Die Herzfrequenz wird dagegen je Workout
      // gelesen: über 30 Tage kämen sonst leicht sechsstellige Messreihen
      // zusammen.
      final routes = await _readOptional(
        () => gateway.readRoutes(from: windowStart, to: windowEnd),
        const <String, List<HealthRoutePoint>>{},
      );

      for (final workout in candidates) {
        final heartRate = await _readOptional(
          () => gateway.readHeartRate(from: workout.start, to: workout.end),
          const <HealthHeartRateSample>[],
        );
        final ride = buildRideFromWorkout(
          workout,
          route: routes[workout.id] ?? const [],
          heartRate: heartRate,
        );
        imported.add(ride);
        if (workout.kind == HealthActivityKind.radfahren &&
            ride.points.isEmpty) {
          routesMissing++;
        }
      }
    }

    final merged = <Ride>[];
    for (final entry in mergeCandidates) {
      final heartRate = await _readOptional(
        () => gateway.readHeartRate(
          from: entry.workout.start,
          to: entry.workout.end,
        ),
        const <HealthHeartRateSample>[],
      );
      final enriched = mergeHeartRateIntoRide(entry.ride, heartRate);
      if (enriched == null) {
        // Ohne verwertbare Messwerte bleibt es beim bisherigen Verhalten:
        // Die Session ist ein Duplikat der bestehenden Tour.
        duplicates++;
        continue;
      }
      merged.add(enriched);
    }

    await setLastImportAt(to);

    debug.add(
      'Ergebnis: ${imported.length} importiert, ${merged.length} angereichert, '
      '$duplicates Duplikat(e), $routesMissing ohne Route',
    );

    return HealthSyncReport(
      from: from,
      to: to,
      workoutsFound: cycling.length,
      imported: List<Ride>.unmodifiable(imported),
      mergedRides: List<Ride>.unmodifiable(merged),
      duplicatesSkipped: duplicates,
      routesMissing: routesMissing,
      debugLines: List<String>.unmodifiable(debug),
    );
  }

  /// Liest die Sessions über den nativen Reader und filtert die Rad-Sessions
  /// heraus. Schlägt der Weg fehl (fehlender Kanal, verweigerter Zugriff),
  /// bleibt es beim Plugin-Ergebnis — der Fehler landet nur in [log].
  Future<List<HealthWorkout>> _readNativeSessions({
    required DateTime from,
    required DateTime to,
    required List<String> log,
  }) async {
    final List<HealthSessionInfo> sessions;
    try {
      sessions = await gateway.readExerciseSessionsNative(from: from, to: to);
    } catch (error) {
      log.add('Nativ: nicht verfügbar ($error)');
      return const [];
    }

    log.add('Nativ: ${sessions.length} Session(s)');
    for (final session in sessions.take(_debugSessionLimit)) {
      log.add(
        '  · ${session.typeName} (${session.typeCode}) '
        '${_debugTime(session.start)}–${_debugTime(session.end)} · '
        '${session.title ?? 'ohne Titel'} · '
        '${session.source ?? 'ohne Quelle'} · '
        'Route ${session.hasRoute ? 'ja' : 'nein'}',
      );
    }

    final cycling = <HealthWorkout>[];
    for (final session in sessions) {
      final kind = mapNativeSessionKind(session);
      if (kind == null) {
        continue;
      }
      // Distanz und Energie bleiben leer: sie stecken in eigenen Datensätzen,
      // die der native Reader bewusst nicht mitliest. buildRideFromWorkout
      // rechnet die Distanz dann aus der Route.
      cycling.add(
        HealthWorkout(
          id: session.uid,
          start: session.start,
          end: session.end,
          kind: kind,
          sourceName: session.source,
        ),
      );
    }
    cycling.sort((a, b) => a.start.compareTo(b.start));
    return cycling;
  }

  /// Liest Ruhepuls, Schlaf, HRV und (falls verfügbar) VO2max der letzten
  /// [days] Tage und verdichtet sie zu Tagesserien mit 7-Tage-Trend.
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
    final hrv = await _readOptional<List<HealthNumericSample>>(
      () => gateway.readHrv(from: from, to: to),
      const [],
      onError: () => unavailable.add(VitalsDataKind.hrv),
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
      heartRateVariability: _buildTrend(dailyHrvValues(hrv), to),
      vo2max: latestVo2 == null ? null : _round1(latestVo2.value),
      vo2maxAt: latestVo2?.time,
      unavailable: unavailable,
    );
  }

  /// Erster Zeitraum, mit dem sich [workout] zu mehr als
  /// [healthSyncOverlapThreshold] überschneidet.
  ({DateTime start, DateTime end, Ride? ride})? _findOverlap(
    HealthWorkout workout,
    List<({DateTime start, DateTime end, Ride? ride})> ranges,
  ) {
    for (final range in ranges) {
      final ratio = overlapRatio(
        aStart: workout.start,
        aEnd: workout.end,
        bStart: range.start,
        bEnd: range.end,
      );
      if (ratio > healthSyncOverlapThreshold) {
        return range;
      }
    }
    return null;
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

/// Wie viele Sessions je Quelle in [HealthSyncReport.debugLines] einzeln
/// aufgeführt werden.
const int _debugSessionLimit = 12;

/// Titel, die auf ein Rad-Workout hindeuten, wenn der Aktivitätstyp nichts
/// hergibt (Samsung Health schreibt manche Sessions als „anderes Training“ mit
/// sprechendem Titel).
final RegExp healthCyclingTitlePattern =
    RegExp(r'(rad|fahrrad|bike|cycl|mtb|gravel)', caseSensitive: false);

/// Rad-Art einer nativ gelesenen Session, `null` wenn es kein Rad-Workout ist.
///
/// Maßgeblich ist der Name der androidx-Konstante (die Kotlin-Seite bildet die
/// Zahl darauf ab); zusätzlich greift die Titel-Heuristik
/// [healthCyclingTitlePattern].
HealthActivityKind? mapNativeSessionKind(HealthSessionInfo session) {
  switch (session.typeName) {
    case 'EXERCISE_TYPE_BIKING':
      return HealthActivityKind.radfahren;
    case 'EXERCISE_TYPE_BIKING_STATIONARY':
      return HealthActivityKind.radfahrenIndoor;
  }

  final title = session.title;
  if (title != null && healthCyclingTitlePattern.hasMatch(title)) {
    return HealthActivityKind.radfahren;
  }
  return null;
}

/// Kompakter Zeitstempel für die Diagnosezeilen: „08.08. 14:30“.
String _debugTime(DateTime value) {
  String two(int v) => v.toString().padLeft(2, '0');
  return '${two(value.day)}.${two(value.month)}. '
      '${two(value.hour)}:${two(value.minute)}';
}

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

/// Ob eine Tour bereits Herzfrequenzdaten mitbringt.
///
/// Geprüft werden sowohl die Kennzahlen ([RideStats.avgHrBpm],
/// [RideStats.maxHrBpm]) als auch die Trackpunkte ([TrackPoint.hr]).
bool rideHasHeartRate(Ride ride) =>
    ride.stats.avgHrBpm != null ||
    ride.stats.maxHrBpm != null ||
    ride.points.any((p) => p.hr != null);

/// Reichert eine bestehende Tour mit den Herzfrequenzen einer überlappenden
/// Watch-Session an.
///
/// Jedem Trackpunkt wird die zeitlich nächstgelegene Messung zugeordnet,
/// sofern sie höchstens [tolerance] entfernt liegt. Beide Listen sind
/// zeitlich sortiert — der Abgleich läuft daher als Zwei-Zeiger-Durchlauf in
/// O(n + m); die Trackpunkte werden dabei **nicht** umsortiert, ihre Reihenfolge
/// ist die Streckenreihenfolge.
///
/// Liefert eine Kopie mit unveränderter [Ride.id] (Name, Zeitpunkt, Distanz und
/// Höhenmeter bleiben ebenfalls, nur `avgHrBpm`/`maxHrBpm` kommen hinzu), oder
/// `null`, wenn sich keine einzige Messung zuordnen ließ — dann bleibt die Tour
/// unangetastet.
Ride? mergeHeartRateIntoRide(
  Ride ride,
  List<HealthHeartRateSample> samples, {
  Duration tolerance = healthSyncHrMergeTolerance,
}) {
  if (samples.isEmpty || ride.points.isEmpty) {
    return null;
  }

  final sorted = List<HealthHeartRateSample>.from(samples)
    ..sort((a, b) => a.time.compareTo(b.time));
  final toleranceMs = tolerance.inMilliseconds;

  final points = <TrackPoint>[];
  var cursor = 0;
  var sum = 0.0;
  var count = 0;
  var peak = 0;

  for (final point in ride.points) {
    final time = point.time;
    if (time == null) {
      points.add(point);
      continue;
    }

    // Der Zeiger wandert nur vorwärts, solange die nächste Messung nicht
    // weiter entfernt ist als die aktuelle.
    while (cursor + 1 < sorted.length &&
        (sorted[cursor + 1].time.millisecondsSinceEpoch - time).abs() <=
            (sorted[cursor].time.millisecondsSinceEpoch - time).abs()) {
      cursor++;
    }

    final best = sorted[cursor];
    final delta = (best.time.millisecondsSinceEpoch - time).abs();
    if (delta > toleranceMs) {
      points.add(point);
      continue;
    }

    final bpm = best.bpm.round();
    sum += best.bpm;
    count++;
    if (bpm > peak) {
      peak = bpm;
    }
    points.add(
      TrackPoint(
        lat: point.lat,
        lon: point.lon,
        ele: point.ele,
        time: point.time,
        hr: bpm,
      ),
    );
  }

  if (count == 0) {
    return null;
  }

  return Ride(
    id: ride.id,
    name: ride.name,
    createdAt: ride.createdAt,
    points: List<TrackPoint>.unmodifiable(points),
    stats: RideStats(
      distanceKm: ride.stats.distanceKm,
      durationS: ride.stats.durationS,
      movingTimeS: ride.stats.movingTimeS,
      avgSpeedKmh: ride.stats.avgSpeedKmh,
      ascentM: ride.stats.ascentM,
      descentM: ride.stats.descentM,
      avgHrBpm: (sum / count).round(),
      maxHrBpm: peak,
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

/// Verdichtet HRV-Messungen (rMSSD in ms) zu einem Wert je Kalendertag.
///
/// Maßgeblich sind die Messungen zwischen 0:00 und 12:00 Uhr lokaler Zeit:
/// Die Galaxy Watch schreibt rMSSD im Schlaf, und nur nächtliche bzw.
/// morgendliche Werte sind untereinander vergleichbar (tagsüber verzerren
/// Belastung, Kaffee und Stress den Wert stark). Gibt es an einem Tag keine
/// Messung in diesem Fenster, gilt ersatzweise das Tagesmittel.
List<DailyValue> dailyHrvValues(Iterable<HealthNumericSample> samples) {
  final morningSums = <DateTime, double>{};
  final morningCounts = <DateTime, int>{};
  final daySums = <DateTime, double>{};
  final dayCounts = <DateTime, int>{};

  for (final sample in samples) {
    if (!sample.value.isFinite || sample.value <= 0) {
      continue;
    }
    final day = _startOfDay(sample.time);
    daySums[day] = (daySums[day] ?? 0) + sample.value;
    dayCounts[day] = (dayCounts[day] ?? 0) + 1;
    if (sample.time.hour < 12) {
      morningSums[day] = (morningSums[day] ?? 0) + sample.value;
      morningCounts[day] = (morningCounts[day] ?? 0) + 1;
    }
  }

  final byDay = <DateTime, double>{};
  for (final day in daySums.keys) {
    final morning = morningCounts[day];
    byDay[day] = morning != null && morning > 0
        ? morningSums[day]! / morning
        : daySums[day]! / dayCounts[day]!;
  }
  return _sortedDaily(byDay);
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
/// Name des Platform-Channels für Health-Connect-Datentypen, die das
/// `health`-Paket nicht abdeckt. Gegenstück ist `HealthExtraChannel` in
/// `android/app/src/main/kotlin/.../HealthExtraChannel.kt`.
const String healthExtraChannelName = 'trailscape/health_extra';

/// Methodenname: liest `Vo2MaxRecord`s (Argumente `startMs`, `endMs`; Ergebnis
/// ist eine Liste aus `{timeMs: int, vo2: double}`).
const String healthExtraReadVo2MaxMethod = 'readVo2Max';

/// Methodenname: fragt die VO2max-Leseberechtigung an (Ergebnis `bool`).
const String healthExtraRequestVo2MaxPermissionMethod =
    'requestVo2MaxPermission';

/// Methodenname: liest rohe `ExerciseSessionRecord`s (Argumente `startMs`,
/// `endMs`; Ergebnis ist eine Liste aus Maps, siehe [HealthSessionInfo]).
const String healthExtraReadExerciseSessionsMethod = 'readExerciseSessions';

/// Produktiver Channel zum nativen VO2max-Zusatz.
const MethodChannel healthExtraChannel = MethodChannel(healthExtraChannelName);

const List<hc.HealthDataType> healthReadTypes = [
  hc.HealthDataType.WORKOUT,
  hc.HealthDataType.WORKOUT_ROUTE,
  hc.HealthDataType.HEART_RATE,
  hc.HealthDataType.RESTING_HEART_RATE,
  hc.HealthDataType.SLEEP_SESSION,
  hc.HealthDataType.DISTANCE_DELTA,
  hc.HealthDataType.TOTAL_CALORIES_BURNED,
];

/// Zusätzlich angefragte, aber **nicht zwingende** Datentypen.
///
/// Die HRV (rMSSD) ist das stärkste Erholungssignal, aber nicht jede Uhr
/// schreibt sie und nicht jede Nutzerin gibt sie frei. Sie steht deshalb
/// bewusst nicht in [healthReadTypes]: Sonst würde eine fehlende
/// HRV-Freigabe die gesamte Health-Connect-Verbindung als „nicht verbunden"
/// erscheinen lassen. Fehlt sie, landet HRV in [VitalsSummary.unavailable] —
/// dasselbe Muster wie bei VO2max.
/// `STEPS` steht hier aus einem anderen Grund: Das `health`-Paket reichert
/// jede gelesene Trainings-Session intern mit Distanz-, Kalorien- **und
/// Schritt**-Datensätzen an. Fehlt die Schritt-Berechtigung, wirft Health
/// Connect beim internen `StepsRecord`-Zugriff — das Plugin fängt das ab und
/// liefert eine **leere** Workout-Liste ohne jede Fehlermeldung. Genau so sah
/// der Feldbefund „0 Workouts gefunden“ aus. Optional bleibt der Typ trotzdem:
/// Wer die Freigabe verweigert, soll nicht als „nicht verbunden“ gelten — für
/// den Fall gibt es den nativen Reader
/// ([HealthGateway.readExerciseSessionsNative]).
const List<hc.HealthDataType> healthOptionalReadTypes = [
  hc.HealthDataType.HEART_RATE_VARIABILITY_RMSSD,
  hc.HealthDataType.STEPS,
];

/// [HealthGateway] auf Basis des pub.dev-Pakets `health` (Google Health
/// Connect). Wird nur auf dem Gerät benutzt.
class HealthPluginGateway implements HealthGateway {
  HealthPluginGateway({hc.Health? health, MethodChannel? extraChannel})
      : _health = health ?? hc.Health(),
        _extra = extraChannel ?? healthExtraChannel;

  final hc.Health _health;

  /// Nativer Zusatzkanal für VO2max. In Tests injizierbar.
  final MethodChannel _extra;

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

  /// Fragt die Leserechte an.
  ///
  /// Pflicht- und Zusatztypen ([healthOptionalReadTypes], u. a. HRV) gehen in
  /// **einem** Dialog raus. Lehnt die Nutzerin nur einen Zusatztyp ab, meldet
  /// das Plugin `false`, obwohl alles Nötige erteilt ist — deshalb wird in dem
  /// Fall noch einmal gezielt der Pflichtsatz geprüft.
  ///
  /// VO2max hängt nicht am `health`-Paket, sondern am nativen Zusatzkanal und
  /// wird deshalb in einem zweiten Schritt angefragt. Ein Fehlschlag dort
  /// (Channel nicht registriert, Health Connect fehlt, Ablehnung nur für
  /// VO2max) darf die übrigen — womöglich erteilten — Rechte nicht entwerten;
  /// er wird daher verschluckt.
  @override
  Future<bool> requestPermissions() async {
    final plugin = await _plugin();
    final granted = await plugin.requestAuthorization(
      const [...healthReadTypes, ...healthOptionalReadTypes],
    );
    try {
      await _extra
          .invokeMethod<bool>(healthExtraRequestVo2MaxPermissionMethod);
    } catch (_) {
      // VO2max bleibt dann schlicht in VitalsSummary.unavailable.
    }
    if (granted) {
      return true;
    }
    return await plugin.hasPermissions(healthReadTypes) ?? false;
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
  HealthWorkoutReadDiagnostics? get lastWorkoutDiagnostics =>
      _lastWorkoutDiagnostics;

  HealthWorkoutReadDiagnostics? _lastWorkoutDiagnostics;

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

    // Rohdiagnose: Das Plugin verschluckt Fehler beim Anreichern der Sessions
    // (Distanz, Kalorien, Schritte) und liefert dann eine leere Liste. Ohne
    // diese Zählung ist auf dem Gerät nicht zu unterscheiden, ob es keine
    // Trainings gibt oder ob die Abfrage gescheitert ist.
    final valueTypes = <String, int>{};
    final activityTypes = <String, int>{};

    final workouts = <HealthWorkout>[];
    for (final point in points) {
      final value = point.value;
      final typeName = value.runtimeType.toString();
      valueTypes[typeName] = (valueTypes[typeName] ?? 0) + 1;
      if (value is! hc.WorkoutHealthValue) {
        continue;
      }
      final activity = value.workoutActivityType.name;
      activityTypes[activity] = (activityTypes[activity] ?? 0) + 1;
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

    _lastWorkoutDiagnostics = HealthWorkoutReadDiagnostics(
      rawPointCount: points.length,
      valueTypeCounts: Map<String, int>.unmodifiable(valueTypes),
      activityTypeCounts: Map<String, int>.unmodifiable(activityTypes),
    );
    return workouts;
  }

  /// Liest die rohen Trainings-Sessions über den nativen Zusatzkanal.
  ///
  /// Wirft bei fehlendem Kanal (alte Installation) oder verweigertem Zugriff;
  /// [HealthSyncService.importWithReport] behandelt das als „kein Fallback“.
  @override
  Future<List<HealthSessionInfo>> readExerciseSessionsNative({
    required DateTime from,
    required DateTime to,
  }) async {
    final raw = await _extra.invokeListMethod<Object?>(
      healthExtraReadExerciseSessionsMethod,
      <String, Object?>{
        'startMs': from.millisecondsSinceEpoch,
        'endMs': to.millisecondsSinceEpoch,
      },
    );
    if (raw == null) {
      return const [];
    }

    final sessions = <HealthSessionInfo>[];
    for (final entry in raw) {
      if (entry is! Map) {
        continue;
      }
      final uid = entry['uid'];
      final startMs = entry['startMs'];
      final endMs = entry['endMs'];
      if (uid is! String || startMs is! num || endMs is! num) {
        continue;
      }
      final typeCode = entry['exerciseType'];
      final typeName = entry['exerciseTypeName'];
      final title = entry['title'];
      final source = entry['source'];
      sessions.add(
        HealthSessionInfo(
          uid: uid,
          start: DateTime.fromMillisecondsSinceEpoch(startMs.toInt()),
          end: DateTime.fromMillisecondsSinceEpoch(endMs.toInt()),
          typeCode: typeCode is num ? typeCode.toInt() : -1,
          typeName: typeName is String ? typeName : 'unbekannt',
          title: title is String ? title : null,
          source: source is String ? source : null,
          hasRoute: entry['hasRoute'] == true,
        ),
      );
    }
    sessions.sort((a, b) => a.start.compareTo(b.start));
    return sessions;
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
  /// Gelesen wird deshalb über den nativen Zusatzkanal
  /// ([healthExtraChannelName]).
  ///
  /// Wirft, wenn der Channel nicht antwortet (z. B. auf einer alten
  /// Installation ohne die native Erweiterung) oder Health Connect den Zugriff
  /// verweigert — [HealthSyncService.readVitals] fängt das ab und meldet
  /// VO2max als nicht verfügbar.
  @override
  Future<List<HealthNumericSample>> readVo2Max({
    required DateTime from,
    required DateTime to,
  }) async {
    final raw = await _extra.invokeListMethod<Object?>(
      healthExtraReadVo2MaxMethod,
      <String, Object?>{
        'startMs': from.millisecondsSinceEpoch,
        'endMs': to.millisecondsSinceEpoch,
      },
    );
    if (raw == null) {
      return const [];
    }

    final samples = <HealthNumericSample>[];
    for (final entry in raw) {
      if (entry is! Map) {
        continue;
      }
      final timeMs = entry['timeMs'];
      final value = entry['vo2'];
      if (timeMs is! num || value is! num) {
        continue;
      }
      samples.add(
        HealthNumericSample(
          time: DateTime.fromMillisecondsSinceEpoch(timeMs.toInt()),
          value: value.toDouble(),
        ),
      );
    }
    samples.sort((a, b) => a.time.compareTo(b.time));
    return samples;
  }

  /// rMSSD in ms. Der Typ ist im `health`-Paket für Android vorhanden
  /// (`HEART_RATE_VARIABILITY_RMSSD`), ein eigener Channel ist nicht nötig.
  /// Ohne erteilte Berechtigung wirft bzw. leert Health Connect die Abfrage —
  /// [HealthSyncService.readVitals] fängt beides ab.
  @override
  Future<List<HealthNumericSample>> readHrv({
    required DateTime from,
    required DateTime to,
  }) =>
      _numeric(hc.HealthDataType.HEART_RATE_VARIABILITY_RMSSD, from, to);

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
