/// Export, Backup und Import von Nutzerdaten.
///
/// Baut auf dem bestehenden GPX-Code ([gpx.dart]) und den vorhandenen
/// JSON-Serialisierungen aus [models.dart]/[training_load.dart] auf, damit
/// Sicherungen und Einzeltour-Exporte jederzeit zum Speicherformat der App
/// kompatibel bleiben. Enthält keine Plattform-Zugriffe (kein Dateisystem,
/// kein Share-Sheet) — das übernehmen die aufrufenden Screens.
library;

import 'dart:convert';
import 'dart:math' as math;

import 'gpx.dart';
import 'models.dart';
import 'stats.dart';
import 'training_load.dart';

/// Aktuelle Version des Backup-JSON-Formats (siehe [buildBackupJson]).
///
/// Wird erhöht, sobald sich das Format inkompatibel ändert — ältere
/// App-Versionen können dann anhand der Nummer erkennen, dass sie eine
/// Sicherung nicht lesen können, statt sie fehlerhaft zu interpretieren.
const int backupFormatVersion = 1;

/// Name der App, wie er im Backup-JSON unter `"app"` steht — dient als
/// einfache Signatur, um fremde JSON-Dateien früh zurückzuweisen.
const String backupAppName = 'trailscape';

// ---------------------------------------------------------------------------
// GPX-Export einer einzelnen Tour
// ---------------------------------------------------------------------------

/// Erzeugt eine valide GPX-1.1-Datei für eine einzelne Tour: Metadaten (Name,
/// Aufnahmezeitpunkt), ein Track mit einem Segment sowie — falls vorhanden —
/// Herzfrequenz je Trackpunkt als Garmin-TrackPointExtension.
String rideToGpx(Ride ride) => buildGpx(
      ride.name,
      ride.points,
      time: DateTime.fromMillisecondsSinceEpoch(ride.createdAt),
    );

/// Macht einen Tournamen dateisystemtauglich (nur Buchstaben, Ziffern, `-`
/// und `_`), z. B. für den Dateinamen eines GPX- oder Backup-Exports.
String safeFileName(String name) {
  final cleaned = name
      .trim()
      .replaceAll(RegExp(r'[^a-zA-Z0-9\-_]+'), '_')
      .replaceAll(RegExp(r'^_+|_+$'), '');
  return cleaned.isEmpty ? 'tour' : cleaned;
}

/// Dateiname für einen Backup-Export, z. B. `trailscape-backup-2026-08-08.json`.
String backupFileName(DateTime at) {
  String pad2(int v) => v.toString().padLeft(2, '0');
  return 'trailscape-backup-${at.year}-${pad2(at.month)}-${pad2(at.day)}.json';
}

// ---------------------------------------------------------------------------
// GPX-Import (einzelne Datei, z. B. von Komoot/Strava)
// ---------------------------------------------------------------------------

/// Baut aus dem Inhalt einer GPX-Datei eine vollständige Tour, inklusive
/// berechneter Statistiken (Distanz, Höhenmeter, Ø-/Max-Puls aus den
/// Trackpunkten). Wirft [FormatException], falls die Datei kein gültiges GPX
/// mit Trackpunkten ist (siehe [parseGpx]).
///
/// [fallbackName] wird verwendet, wenn die GPX-Datei selbst keinen Tracknamen
/// trägt (üblicherweise der Dateiname ohne Endung). [id] überschreibt die
/// sonst aus der aktuellen Uhrzeit generierte Tour-ID — für Tests gedacht.
Ride rideFromGpx(String xml, {required String fallbackName, String? id}) {
  final parsed = parseGpx(xml);
  final points = parsed.points;
  final baseStats = computeStats(points);

  final parsedName = parsed.name?.trim();
  final name =
      (parsedName != null && parsedName.isNotEmpty) ? parsedName : fallbackName;
  final createdAt =
      points.first.time ?? DateTime.now().millisecondsSinceEpoch;

  final hrValues = points.map((p) => p.hr).whereType<int>().toList();
  int? avgHr;
  int? maxHr;
  if (hrValues.isNotEmpty) {
    final sum = hrValues.fold<int>(0, (a, b) => a + b);
    avgHr = (sum / hrValues.length).round();
    maxHr = hrValues.reduce(math.max);
  }

  return Ride(
    id: id ?? DateTime.now().millisecondsSinceEpoch.toString(),
    name: name,
    createdAt: createdAt,
    points: points,
    stats: RideStats(
      distanceKm: baseStats.distanceKm,
      durationS: baseStats.durationS,
      movingTimeS: baseStats.movingTimeS,
      avgSpeedKmh: baseStats.avgSpeedKmh,
      ascentM: baseStats.ascentM,
      descentM: baseStats.descentM,
      avgHrBpm: avgHr,
      maxHrBpm: maxHr,
    ),
  );
}

// ---------------------------------------------------------------------------
// Vollständiges Backup (alle Touren + Trainingsprofil)
// ---------------------------------------------------------------------------

/// Baut eine vollständige Sicherung aus allen Touren und optional dem
/// Trainingsprofil als eingerücktes JSON.
String buildBackupJson(List<Ride> rides, TrainingProfile? profile) {
  final json = <String, dynamic>{
    'app': backupAppName,
    'backupVersion': backupFormatVersion,
    'exportedAt': DateTime.now().toUtc().toIso8601String(),
    'profile': profile?.toJson(),
    'rides': rides.map((r) => r.toJson()).toList(),
  };
  return const JsonEncoder.withIndent('  ').convert(json);
}

/// Ergebnis von [parseBackupJson]: die enthaltenen Touren sowie ein
/// optionales Trainingsprofil.
class BackupData {
  const BackupData({required this.rides, this.profile});

  final List<Ride> rides;
  final TrainingProfile? profile;
}

/// Liest eine Trailscape-Sicherung (siehe [buildBackupJson]) und liefert
/// Touren sowie optionales Profil. Wirft [FormatException] mit deutscher
/// Meldung bei kaputtem JSON, fremdem Format oder einer neueren, hier noch
/// unbekannten Backup-Version.
BackupData parseBackupJson(String raw) {
  final dynamic decoded;
  try {
    decoded = jsonDecode(raw);
  } on FormatException {
    throw const FormatException(
      'Die Datei enthält kein gültiges JSON und kann nicht importiert werden.',
    );
  }

  if (decoded is! Map<String, dynamic>) {
    throw const FormatException(
      'Die Datei ist keine gültige Trailscape-Sicherung.',
    );
  }

  if (decoded['app'] != backupAppName) {
    throw const FormatException(
      'Die Datei ist keine gültige Trailscape-Sicherung.',
    );
  }

  final version = decoded['backupVersion'];
  if (version is! int) {
    throw const FormatException(
      'Die Sicherung enthält keine gültige Versionsangabe.',
    );
  }
  if (version > backupFormatVersion) {
    throw FormatException(
      'Diese Sicherung wurde mit einer neueren Trailscape-Version erstellt '
      '(Format $version, unterstützt wird bis $backupFormatVersion) und kann '
      'von dieser App-Version nicht gelesen werden. Bitte Trailscape '
      'aktualisieren.',
    );
  }

  final ridesRaw = decoded['rides'];
  if (ridesRaw is! List) {
    throw const FormatException(
      'Die Sicherung enthält keine gültige Touren-Liste.',
    );
  }

  final rides = <Ride>[];
  for (final entry in ridesRaw) {
    if (entry is! Map<String, dynamic>) {
      throw const FormatException(
        'Die Sicherung enthält eine ungültige Tour.',
      );
    }
    try {
      rides.add(Ride.fromJson(entry));
    } catch (_) {
      throw const FormatException(
        'Die Sicherung enthält eine ungültige Tour.',
      );
    }
  }

  TrainingProfile? profile;
  final profileRaw = decoded['profile'];
  if (profileRaw is Map<String, dynamic>) {
    try {
      profile = TrainingProfile.fromJson(profileRaw);
    } catch (_) {
      throw const FormatException(
        'Die Sicherung enthält ein ungültiges Trainingsprofil.',
      );
    }
  }

  return BackupData(rides: rides, profile: profile);
}
