# Motivation auf der Uhr: Machbarkeit

Frage: (a) ein bis zwei Highlight-Zeilen nach der Fahrt, (b) Wochenserie plus Wochenziel auf einen Blick.
Nur gelesen, kein Code geändert.

## Was die Uhr-App heute ist

- Modul `:wear` (`wear/build.gradle.kts`), gleiche `applicationId` und Signatur wie `:app`, ~2100 Zeilen,
  **keine Tests** (`wear/src/test` gibt es nicht). Aus dem Spike (`docs/wear-spike.md`) gewachsen, aber
  nicht mehr nur Spike: Die CI baut APK und `.aab` (`.github/workflows`), das APK liegt bewusst nicht
  bei den Releases; Verteilung für andere nur über Play Internal Testing (siehe Spike-Doku f).
- Sie ist eine Begleiterin (README „Galaxy Watch“): Health Services liefert Puls und GPS
  (`exercise/ExerciseRecorder.kt`), `comm/SensorSender.kt` schickt sie gebündelt ans Handy; die
  Tour entsteht dort. Auf der Uhr bleibt nur ein Journal (`record/RecordingJournal.kt`).
- Nur die Aufzeichnungsansicht (`ui/RecordingScreen.kt`); kein Tile, keine Complication, kein Wochenwissen.
- Datenfluss, alles `MessageClient`/`CapabilityClient` aus `play-services-wearable` (Verträge in
  `core/.../WearProtocol.kt`):
  - Uhr → Handy: `PFAD_SENSOR` (SensorBatch), `PFAD_BEFEHL_AN_TELEFON` (start/pause/weiter/stopp).
  - Handy → Uhr: `PFAD_BEFEHL_AN_UHR`, `PFAD_ZUSTAND` (`AufzeichnungsZustand`: läuft, pausiert, Dauer,
    Distanz, HF), gesendet aus `app/.../record/RecordingService.kt` (`sendeZustandAnUhr`) über
    `app/.../wear/WearBridge.kt`, Empfang `app/.../wear/WearListenerService.kt`.
  - Nachrichten sind flüchtig: ohne erreichbare Uhr gehen sie verloren.

## (a) Highlights nach der Fahrt

Passt ins Protokoll: `rideHighlights(...)` (`core/.../RideHighlights.kt`) läuft auf dem Handy, die
Texte kommen aus Android-Ressourcen (`ui/map/RideHighlights.kt`, `highlightText`).
- Neuer Pfad `PFAD_ABSCHLUSS = "/trailscape/abschluss"` plus Datentyp `AbschlussInfo(zeilen: List<String>)`
  in `WearProtocol.kt` (Hand-JSON wie die anderen, additiv, alte Gegenseite ignoriert unbekannte Pfade:
  `CommandListenerService` hat ein `when` auf Pfade und `runCatching`).
- Handy sendet nach Aufzeichnungsende fertige, bereits lokalisierte Zeilen (max. 2). Die Uhr braucht keine
  Highlight-Logik und keine eigenen Strings; sie zeigt nach Stopp eine Zeile unter der Zusammenfassung.
- Stolperstein: Highlights gibt es erst nach dem Speichern der Tour (`facts` neu gebaut); der Sendepunkt
  liegt danach, nicht im `RecordingService` beim Stopp. Keine neuen Abhängigkeiten, kein Manifest-Eintrag.
- Aufwand: ca. 4 Dateien (`WearProtocol.kt`, `WearBridge.kt`, `CommandListenerService.kt`,
  `RecordingScreen.kt`) + `WearProtocolTest`, ~100 neue Zeilen.

## (b) Serie und Wochenziel auf einen Blick

Das braucht einen Zustand, der **ohne laufende Aufzeichnung** auf der Uhr liegt, also Daten, nicht Nachricht:
- Handy schreibt per `DataClient.putDataItem` (`/trailscape/woche`: `serieWochen`, `wochenKm`, `zielKm`,
  `stand`) – bleibt auf der Uhr erhalten und wird bei Wiederverbindung abgeglichen. `MessageClient` taugt
  dafür nicht. Neu im Protokoll: `WochenStand` mit kodiere/dekodiere.
- Berechnet wird er auf dem Handy aus `weekStreak` (`core/.../Motivation.kt`) und dem Wochenziel von
  „Heute“ (Planwoche oder `suggestWeeklyGoal`, `core/.../WeeklyGoal.kt`); neu anstoßen nach jeder
  gespeicherten Tour und beim Wochenwechsel (ein Mal am Montag, auch ohne Fahrt!).
- Tile: neue Abhängigkeiten `androidx.wear.tiles:tiles` und `androidx.wear.protolayout:protolayout(-material3)`,
  im Manifest ein `<service>` mit `BIND_TILE_PROVIDER`, Action `androidx.wear.tiles.action.BIND_TILE_PROVIDER`
  und Vorschau-Ressource. Ein `TileService` rendert aus dem lokal gespeicherten `WochenStand`.
- Complication: `androidx.wear.watchface:watchface-complications-data-source-ktx`, `<service>` mit
  `BIND_COMPLICATION_PROVIDER`; Typen SHORT_TEXT („3 Wo.“) und RANGED_VALUE (km/Ziel). Welches Zifferblatt
  sie zeigt, wählt die Nutzerin.
- Aufwand: Tile ca. 5 Dateien / 250 Zeilen; Complication ca. 3 Dateien / 150 Zeilen; Datenpfad
  (Protokoll, Handy-Seite, Uhr-Seite) ca. 4 Dateien / 150 Zeilen, gemeinsam für beide.

## Testbarkeit

- JVM (`:core`): Kodierung/Dekodierung, Zeilenwahl, Berechnung von `WochenStand` aus `RideFact`s
  (Muster: `core/src/test/.../WearProtocolTest.kt`, `WeeklyGoalTest.kt`), etwa Montag ohne Fahrt.
- Nur auf der Uhr: `DataClient`-Zustellung, Tile-Rendering und -Aktualisierung, Complication-Größen, Akku.
  `:wear` hat keine Testinfrastruktur; Geräteprüfung wie im Spike nötig (Watch Ultra, Wear OS 5).

## Risiken

- Akku: (a) praktisch null (eine Nachricht je Fahrt). (b) Tile nur bei Datenänderung neu anfordern
  (`requestUpdate`), keine Freshness-Intervalle; das Handy sendet höchstens ein paar Mal am Tag.
- Zustellung: Funk Handy ↔ Uhr reißt öfter ab (siehe `PhoneLink`-KDoc). `putDataItem` gleicht nach;
  Nachrichten (a) tun es nicht, die Anzeige ist dort nur „nice to have“, Verlust ist hinnehmbar.
- Play: Tiles und Complications brauchen keine Sonderfreigabe, aber die Uhr-App muss überhaupt über Play
  (Internal Testing) kommen, mit identischer Signatur (PEPK-Upload), sonst bricht der Data Layer
  (Spike-Doku f). Vorher `isMinifyEnabled = false` (`wear/build.gradle.kts`) als offene Schuld klären.
  Keine neue Berechtigung nötig.

## Empfehlung

Kleinster nützlicher Schritt: **(a) in der Minimalform**: Zeile nach Fahrtende (`PFAD_ABSCHLUSS`),
~100 Zeilen, ohne neue Abhängigkeit oder Manifest, per Protokolltest abgesichert. Danach (b) als
Complication (RANGED_VALUE = Wochenziel) vor dem Tile: kleiner und immer sichtbar, gleicher Datenpfad.
Vorher klären, ob die Uhr-App über Play verteilt wird; sonst erreicht (b) nur das Entwicklungsgerät.
