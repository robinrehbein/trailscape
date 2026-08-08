/// Zentraler, in-memory gehaltener App-Zustand.
///
/// Hält die geladenen Touren sowie die aktuell ausgewählte Tour und
/// benachrichtigt Listener (siehe [ChangeNotifier]) bei jeder Änderung, damit
/// die UI (Karte, Tourenliste, ...) synchron bleibt.
///
/// Hält außerdem die zuletzt gelesenen Health-Connect-Vitaldaten vor, damit
/// der Training-Tab sie sofort anzeigen kann, ohne selbst nachzulesen.
library;

import 'package:flutter/foundation.dart';

import 'health_sync.dart';
import 'models.dart';
import 'storage.dart';

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

  /// Lädt alle gespeicherten Touren neu und benachrichtigt Listener.
  Future<void> loadRides() async {
    rides = await listRides();
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
    for (final ride in newRides) {
      await saveRide(ride);
    }
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

  /// Einmaliger, stiller Hintergrund-Sync beim App-Start.
  ///
  /// Fragt **nie** Berechtigungen an (kein Dialog beim Start) — importiert
  /// nur, wenn Health Connect bereits verbunden ist. Speichert neu gefundene
  /// Touren und aktualisiert die gecachten Vitaldaten. Fehler werden
  /// verschluckt, damit ein Health-Connect-Problem den App-Start nie blockiert
  /// oder stört.
  Future<void> autoSyncHealth() async {
    try {
      final connection = await healthSync.checkAvailability();
      if (!connection.isReady) {
        return;
      }
      final newRides = await healthSync.importNewRides(existing: rides);
      await addRides(newRides);
      vitals = await healthSync.readVitals();
      notifyListeners();
    } catch (_) {
      // Hintergrund-Sync darf die App nie stören.
    }
  }

  /// Manueller Sync, ausgelöst über den Mehr-Screen.
  ///
  /// Wenn [reimportAll] gesetzt ist, wird der gespeicherte Import-Zeitstempel
  /// zuerst gelöscht (nächster Import betrachtet dann wieder die vollen
  /// 90 Tage). Liefert die Anzahl neu importierter Touren. Wirft
  /// [HealthSyncException] mit einer für die UI geeigneten Meldung, wenn
  /// Health Connect nicht bereit ist oder der Import fehlschlägt — anders als
  /// [autoSyncHealth] wird der Fehler hier nicht verschluckt, damit die UI ihn
  /// anzeigen kann.
  Future<int> syncHealthNow({bool reimportAll = false}) async {
    if (reimportAll) {
      await healthSync.setLastImportAt(null);
    }
    final newRides = await healthSync.importNewRides(existing: rides);
    await addRides(newRides);
    vitals = await healthSync.readVitals();
    notifyListeners();
    return newRides.length;
  }
}
