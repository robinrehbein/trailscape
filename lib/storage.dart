/// Persistenz für aufgezeichnete Touren.
///
/// Touren werden als einzelne JSON-Dateien unter `<AppDocuments>/rides/<id>.json`
/// abgelegt. Das JSON-Format entspricht [Ride.toJson]/[Ride.fromJson] und ist
/// damit kompatibel zum Selfhost-Sync-Server und zur Web-App-Referenz
/// (storage.ts, dort als IndexedDB-Store realisiert).
library;

import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

import 'models.dart';

Directory? _storageDirOverride;

/// Ersetzt das Verzeichnis, in dem Touren abgelegt werden. Für Tests gedacht,
/// um [getApplicationDocumentsDirectory] nicht tatsächlich aufzurufen.
void setStorageDirForTesting(Directory dir) {
  _storageDirOverride = dir;
}

Future<Directory> _ridesDir() async {
  final base = _storageDirOverride ?? await getApplicationDocumentsDirectory();
  final dir = Directory('${base.path}/rides');
  if (!await dir.exists()) {
    await dir.create(recursive: true);
  }
  return dir;
}

File _rideFile(Directory dir, String id) => File('${dir.path}/$id.json');

/// Liefert alle gespeicherten Touren, neueste zuerst (nach `createdAt`
/// absteigend sortiert). Dateien, die nicht als gültige Tour gelesen werden
/// können (kaputtes JSON, falsches Format), werden übersprungen.
Future<List<Ride>> listRides() async {
  final dir = await _ridesDir();
  final entries = await dir.list().toList();

  final rides = <Ride>[];
  for (final entry in entries) {
    if (entry is! File || !entry.path.endsWith('.json')) {
      continue;
    }
    if (entry.path.endsWith('.tmp')) {
      continue;
    }
    try {
      final raw = await entry.readAsString();
      final json = jsonDecode(raw) as Map<String, dynamic>;
      rides.add(Ride.fromJson(json));
    } catch (_) {
      // Defekte Datei überspringen.
      continue;
    }
  }

  rides.sort((a, b) => b.createdAt.compareTo(a.createdAt));
  return rides;
}

/// Liefert eine einzelne Tour anhand ihrer ID, oder `null` falls sie nicht
/// existiert oder nicht gelesen werden kann.
Future<Ride?> getRide(String id) async {
  final dir = await _ridesDir();
  final file = _rideFile(dir, id);
  if (!await file.exists()) {
    return null;
  }
  try {
    final raw = await file.readAsString();
    final json = jsonDecode(raw) as Map<String, dynamic>;
    return Ride.fromJson(json);
  } catch (_) {
    return null;
  }
}

/// Speichert eine Tour atomar: es wird zunächst in eine `.tmp`-Datei
/// geschrieben, die anschließend auf den endgültigen Dateinamen umbenannt
/// wird. Dadurch bleibt bei einem Absturz während des Schreibens niemals
/// eine halb geschriebene Tour-Datei zurück.
Future<void> saveRide(Ride ride) async {
  final dir = await _ridesDir();
  final file = _rideFile(dir, ride.id);
  final tmpFile = File('${file.path}.tmp');

  final json = jsonEncode(ride.toJson());
  await tmpFile.writeAsString(json, flush: true);
  await tmpFile.rename(file.path);
}

/// Löscht eine Tour. Existiert sie nicht, passiert nichts.
Future<void> deleteRide(String id) async {
  final dir = await _ridesDir();
  final file = _rideFile(dir, id);
  if (await file.exists()) {
    await file.delete();
  }
}
