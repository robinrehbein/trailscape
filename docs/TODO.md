# Ideen-Liste

Festgehaltene Ideen, die bewusst noch nicht umgesetzt sind.

## Bugs

- **Vorschlagswahl „Passende Runde“: Route fehlt auf der Karte, Karte
  lässt sich nicht bewegen.** Gemeldet am 27.09.2026 (Android, Einstieg über
  die Tagesempfehlung, „aus: Erste Runde (Tagesempfehlung)“). Das Blatt zeigt
  drei Kandidaten (SO/N/SW, „Beste“ ist ausgewählt), aber auf der Karte ist
  keine Linie für den ausgewählten Kandidaten zu sehen. Außerdem reagiert die
  Karte nicht auf Wischen oder Zoomen, solange das Blatt offen ist. Erwartet:
  Der gewählte Kandidat wird als Vorschau gezeichnet und die Kamera zeigt ihn,
  die Karte bleibt bedienbar. Einstieg zum Nachsehen:
  `RouteGenerationSheet` (`app/.../ui/map/RouteGenerationSheet.kt`) und der
  Aufruf in `MapScreen.kt` (`DockedSheet.VORSCHLAEGE`). Zu prüfen ist, ob die
  Vorschau-Ebene beim Start über die Tagesempfehlung gesetzt wird und ob das
  Blatt oder eine Ebene darüber die Gesten der Karte abfängt.

## Vor dem öffentlichen Release (Blocker)

- **OpenFreeMap: Offline-Download bestätigen lassen.** Die Nutzungsbedingungen
  (https://openfreemap.org/tos/, Stand 09.09.2026) untersagen „collect data …
  in automated ways without permission“. Ein vom Nutzer angestoßener,
  begrenzter Ausschnitt ist nach unserer Auslegung keine automatisierte
  Datensammlung, eine Erlaubnis ist das aber nicht. Für internes Testing
  vertretbar. Vor dem Release schriftlich bei info@openfreemap.org bestätigen
  lassen und Datum/Wortlaut im KDoc an `mapStyles`
  (`app/.../ui/MapStyles.kt`) vermerken. Ohne Bestätigung: beim Vektor-Stil
  `offlineAllowed = false` setzen und eine eigene Kachelquelle (z. B.
  selbst gehostete PMTiles) aushandeln bzw. aufbauen.

## Ideen

- **Trainingspläne als teilbare Dateien („Plan-Rezepte")** — Trainingsplan und
  strukturierte Einheiten als lesbare JSON-Datei exportieren und importieren,
  damit Trainer, Vereine und Foren Pläne ohne Plattform und ohne Konto
  tauschen können. Baut auf dem vorhandenen formatstabilen JSON
  (`core/.../JsonSupport.kt`) und dem Datei-Ein-/Ausgang der Backup-Karte auf.
