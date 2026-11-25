# Indoor – Kalibrierung nach den ersten Fahrten (Idee 6)

Widerstand, virtuelle Gänge und Belag sind bisher **nur gerechnet**
(`docs/indoor/core.md`, `VirtualGears.kt`, `IndoorSurface.kt`). Dieser Teil gibt
dem Inhaber drei Werkzeuge, um nach echten Fahrten nachzujustieren. Nichts davon
ist auf einem Gerät oder Trainer geprüft (siehe „Offene Annahmen“).

Keine Telemetrie, kein Konto, keine Drittdienste: Einstellungen liegen im
`KeyValueStore`, Protokolle als Dateien im privaten Speicher; geteilt wird nur,
was der Nutzer selbst teilt.

## 1 Fahrgefühl (Regler)

Einstellungen → Fahrgefühl → **Feinabstimmung** (`FeelCard`):

| Regler | wirkt auf | Bereich | Vorgabe |
|---|---|---|---|
| Gesamtwiderstand (leichter … schwerer) | `RiderSetup.trainerScale` (ersetzt den Wert; `riderSetupFrom` lässt ihn auf 1,0) | 70–130 % | 100 % |
| Stärke des Belags | `RiderSetup.surfaceStrength` (neu, Vorgabe 1,0) → Spreizung der Crr-Tabelle | 0–200 % | 100 % |

„Zurücksetzen“ stellt beide auf 100 %; die Vorgabe wird nicht gespeichert (kein
Altlast-Schlüssel). Beide Regler ändern **nie** das Tempo der virtuellen Fahrt
bzw. Zeit/Strecke — `trainerScale` skaliert nur den massenabhängigen Anteil der an
den Trainer gesendeten Ersatzsteigung (`simulationCommand`), `surfaceStrength`
nur den Crr (der allerdings sehr wohl ins Tempo eingeht: ein gröberer Belag
bremst die simulierte Geschwindigkeit bei gleicher Leistung).

Belagsspreizung (`effectiveCrr(kind, profileCrr, strength)`):
`crr = max(ref + (crr_Klasse − ref)·Stärke, 0,002) · Profil-Crr/0,008`, `ref` =
`SurfaceKind.UNKNOWN.crr` (0,008). Stärke 1 ist bitgleich die alte Funktion.

Code: `core/…/indoor/RideFeel.kt`, App: `IndoorFeelController` (Speicherung),
`IndoorViewModel.startRide` (`feel.applyTo(rider)`).

## 2 Geführter Abgleich „Fahrgefühl einstellen“

Einstellungen → Fahrgefühl → *Fahrgefühl einstellen* (nur mit echtem, gekoppeltem
Trainer — der Demo-Trainer treibt kein Messgerät an). Es startet eine normale
SIM-Fahrt auf der **ebenen Teststrecke** (`flatCalibrationRoute`: 3 km Rundkurs,
exakt eben, Belag COMPACTED = Mischwert), ohne 3D-Ansicht, im Gang, der bei
85 U/min etwa 55 % der FTP ergibt (`calibrationGearShift`, 120–220 W). Die Seite
zeigt Einlaufen (30 s), dann ein Messfenster von 150 s; ein Gangwechsel oder eine
Pause startet von vorn. Am Ende steht ein Vorschlag oder eine klare Ablehnung.
Gespeichert wird nichts als Tour; ein Fahrprotokoll entsteht.

### Rechnung (`FeelCalibration.kt`, rein und getestet)

Erwartungswert in der Ebene bei Trittfrequenz c im virtuellen Gang G:
`v_g = c/60·G·U` (U = 2,1 m), `F_erw = m·g·Crr + cw·v_g²`, `P_erw = F_erw·v_g/η`.
Der Luftanteil kommt beim Trainer genau so an wie gesendet (cw·k³); nur der
Rollanteil geht über die unbekannte Trainermasse — deshalb
`neu = alt·F_Roll / (F_gemessen − F_Luft)` mit `F_gemessen = P·η/v_g`. Ausgegeben
werden außerdem `powerRatio = P_erw/P_gem`, die Medianwerte und eine grobe
Unsicherheit. Der Vorschlag wird auf 70–130 % geklemmt (und als „begrenzt“
markiert), auf 5 % gerundet; nur ein Unterschied ≥ 2,5 Prozentpunkte gilt als
Änderung.

### Ablehnung statt Schätzung

| Grund | Bedingung |
|---|---|
| zu wenige Werte | < 90 brauchbare Sekunden |
| Leistung zu niedrig | Median < 100 W |
| Trittfrequenz | Median außerhalb 60–110 U/min |
| unruhig | robuste Streuung Leistung > 8 % oder Trittfrequenz > 6 % oder Drift der Hälften > 6 % |
| Geschwindigkeit passt nicht | simulierte v weicht > 15 % vom Gleichgewichtstempo der Median-Leistung ab |
| Gang gewechselt | mehr als ein Gang im Fenster |
| unplausibel | roher Faktor < 0,5 oder > 2 |

### Grenze der Methode

Auf der Ebene macht der Rollwiderstand bei ~30 km/h nur rund ein Drittel des
Widerstands aus: 3 % Leistungsfehler werden zu ~10 % Fehler im Faktor. Die App
zeigt die Genauigkeit und rät, nach dem Annehmen ein paar Fahrten zu beobachten.

## 3 Fahrprotokoll je Fahrt

`RideProtocolRecorder` (`core/…/RideProtocol.kt`) hält alle 5 s eine Zeile fest
(höchstens 4000): Zeit, Streckengefälle, **gesetzte** Ersatzsteigung / Crr / cw
bzw. ERG-Ziel, Belag, Gang und Übersetzung, **gemessene** Leistung und
Trittfrequenz (Mittel des Intervalls), **simulierte** Geschwindigkeit; im Kopf das
Setup (`trainerScale`, Belagsstärke, Massen, Gangtabelle, Klemmbereich). Reiner
Text, CSV mit `;`, Dezimalpunkt. Die App legt die Datei am Fahrtende (auch
beim Verwerfen, Abbruch und Abgleich) unter `<filesDir>/diag/fahrprotokolle/` ab
(`IndoorProtocolStore`, höchstens 12 Dateien à 512 KB). `diag/` ist vom Auto
Backup ausgenommen, das Protokoll bleibt also auf dem Gerät.

Einsehen und Teilen: Einstellungen → Erweitert → **Fahrprotokolle** (Liste,
Textansicht der ersten 120 Zeilen, „Teilen“ als Textdatei über den
System-Auswahldialog wie bei GPX-Exporten, Löschen).

Nicht abgedeckt: Stürzt die App mitten in der Fahrt ab oder wird sie beendet,
bevor die Fahrt endet, geht dieses Protokoll verloren (geschrieben wird am Ende).

## Offene Annahmen (ohne echten Trainer ungeprüft)

* Radumfang 2,1 m und dass die gemeldete Trittfrequenz zur echten Kurbel passt.
* Dass der Trainer die gesendeten Simulationsparameter (cw, Crr, Steigung) so
  umsetzt wie in `VirtualGears.kt` hergeleitet — insbesondere, dass der
  Luftanteil unverfälscht ankommt und nur der Rollanteil von der Trainermasse
  abhängt. Viele Trainer glätten, klemmen oder rechnen mit eigener Luftdichte.
* Dass die Leistungsmessung des Trainers mit dem Wirkungsgrad η des Profils
  zusammenpasst; ein Offset (z. B. 2–3 %) landet im Faktor.
* Dass der Abstand zwischen Schalten und spürbarem Widerstand (Verzögerung,
  `docs/TODO.md`) die 150-s-Messung nicht stört (nur ein Gang; Schalten
  startet neu).
* Die Plausibilitätsgrenzen (8 %/6 %/15 %, 70–130 %) sind begründete Annahmen,
  keine Messungen; die Streuung realer Trainerdaten kennen wir nicht.
* Die Belagsstärke ist kein Messwert: Die Crr-Tabelle selbst bleibt eine Annahme
  (`IndoorSurface.kt`); der Regler macht sie nur einstellbar.
