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

- **Bluetooth-Sensoren auf echter Hardware testen.** Die GATT-Anbindung
  (`app/.../sensors/`) ist nur ueber die reine Logik in `:core` getestet.
  Vor dem Release mit mindestens einem Pulsgurt (z. B. Polar H10), einem
  Leistungsmesser (z. B. Favero Assioma) und einem CSC-Sensor pruefen, je auf
  einem Geraet unter und ab Android 12: Koppeln, Verbinden bei
  Aufzeichnungsstart, Neuversuch nach Abbruch (Status 133), doppelte Werte ab
  API 33, Sensoren ohne Dienst-UUID in der Werbung. Dazu klaeren, ob Play fuer
  den RecordingService den Diensttyp `connectedDevice` verlangt, und die
  Angaben zu „Gesundheit und Fitness" im Datensicherheitsformular pruefen.

## Ideen

- **Trainingspläne als teilbare Dateien („Plan-Rezepte")** — Trainingsplan und
  strukturierte Einheiten als lesbare JSON-Datei exportieren und importieren,
  damit Trainer, Vereine und Foren Pläne ohne Plattform und ohne Konto
  tauschen können. Baut auf dem vorhandenen formatstabilen JSON
  (`core/.../JsonSupport.kt`) und dem Datei-Ein-/Ausgang der Backup-Karte auf.
- **eFTP-Text bei Leistungsmesser** — `TrainingInsights` beschriftet die FTP
  weiter als „aus der GPS-Leistungsschätzung (±15–25 %)“, auch wenn das beste
  20-min-Mittel aus gemessener Leistung stammt. Herkunft je Tour mitfuehren
  und den Text dann unterscheiden.
- **Radtempo vom CSC-Sensor** — `radTempoKmh` (`core/.../BleSensorLogik.kt`)
  ist gerechnet und getestet, aber nicht verdrahtet. Mit einem Radumfang im
  Profil koennte es GPS im Tunnel oder auf der Rolle ersetzen.
- **Englische Sensor-Texte mit dem i18n-Umzug** — `values-en/strings_ble_sensors.xml`
  stand im ersten Entwurf (Commit 32cfa9b) und ist wieder entfernt, weil eine
  einzelne englische Datei auf englisch eingestellten Geraeten eine
  Sprachmischung ergab. Mit den uebrigen englischen Texten zurueckholen; dabei
  `ble_ride_hr_label`, `ble_spoken_hr` und `ble_paired` („Paired“) ergaenzen und
  `ble_tile_silent` als „no data for %1$d s“, Auslassungspunkte ohne
  Leerzeichen („Searching…“, „Connecting…“).
- **RR-Intervalle speichern** — der Puls-Parser liest sie schon; als eigenes
  Feld in der Tour gaeben sie HRV waehrend der Fahrt her.
