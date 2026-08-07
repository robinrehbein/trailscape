/// Zentraler, in-memory gehaltener App-Zustand.
///
/// Hält die geladenen Touren sowie die aktuell ausgewählte Tour und
/// benachrichtigt Listener (siehe [ChangeNotifier]) bei jeder Änderung, damit
/// die UI (Karte, Tourenliste, ...) synchron bleibt.
library;

import 'package:flutter/foundation.dart';

import 'models.dart';
import 'storage.dart';

class AppState extends ChangeNotifier {
  List<Ride> rides = [];
  Ride? selected;

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
}
