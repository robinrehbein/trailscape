# Eine Auswertung für alles — Regeln für Indoor-Fahrten

Eine Fahrt auf dem Rollentrainer (`Ride.indoor = true`, Vertrag
`docs/indoor.md`) ist **gefahren** (`planned = false`), aber nicht „draußen
gewesen". Daraus folgt eine feste Regel je Stelle. Die Filter stehen an genau
zwei Stellen in `:core/Models.kt`: `riddenRides` (alles Gefahrene) und
`outdoorRides` (gefahren **und** draußen). Wer „Ort" meint, nimmt
`outdoorRides`, wer „Leistung/Umfang" meint, `riddenRides`.

## Regeltabelle

| Stelle | Indoor zählt? | Begründung / Umsetzung |
|---|---|---|
| Statistik, Summen, Wochenverlauf | ja | Distanz, Zeit, Höhenmeter sind geleistet (`rideFacts` → `riddenRides`) |
| Wochenziel, Jahresziel, Serie, Meilensteine | ja | zählen Kilometer/Wochen; gleiche `RideFact`-Basis |
| Bestwerte Distanz / Höhenmeter / Fahrzeit | ja | geleistete Arbeit |
| Bestwert **Tempo** | **nein** | simuliertes Tempo (kein Wind, Windschatten, echter Belag) ist mit draußen nicht vergleichbar; `RideFact.indoor`, `valueOf(SPEED)` |
| Trainingsbelastung, Fitness/Form, Wochenlast | ja | `computeInsights` nimmt `riddenRides`; Leistung ist sogar gemessen |
| FTP (eFTP, bestes 20-min-Mittel) | ja | Herkunft wird mitgeführt (s. u.) |
| „Neue Wege", gefahrene Wege | **nein** | virtuelle Koordinaten (`outdoorRides`) |
| Entdeckt-Kacheln / Karten-Heatmap | **nein** | `outdoorRides`, `historyTracks` filtert `indoor` |
| Rundenvergleich, Streckensignaturen, Segmente | **nein** | `outdoorRides` / `RideSegments` |
| Tempo-Historie für Planung/Prognose | **nein** | `outdoorRides` (`SessionTarget`, `GoalPrognosis`) |
| „Erste Runde" | ja | zählt als gefahren (`FirstRound`) |
| Widget, Uhr-Abschluss | ja | beide rechnen über `rideFacts` |
| Backup (ZIP), Selfhost-Sync | ja | Tour-JSON trägt `indoor`/`powerMeasured`; der Server reicht das JSON unverändert durch |
| Strava-Upload | ja, als `trainer=1` | `StravaClient`: Indoor-Fahrten werden als Rollentrainer markiert |
| GPX/FIT-Export | ja | wie jede Tour (Leistung/Trittfrequenz an den Punkten) |

## Kennzeichnung und Filter

* Tourenliste: Pille „Indoor" an jeder Indoor-Fahrt (`indoor_app_tourlist_pill`)
  und eine Auswahl **Alle · Draußen · Indoor** über der Liste. Sie erscheint
  erst, wenn es eine Indoor-Fahrt gibt; Planungen stehen nur unter „Alle"
  (`RideKindFilter`, `filterRidesByKind`).
* Tourdetail und Teilen-Karte kennzeichnen die Fahrt als Indoor.

## Herkunft der Leistung

`Ride.powerMeasured` (auch in `RideSummary`/`RideInfo`) sagt, ob die Leistung
**gemessen** ist: Leistungsmesser, Rollentrainer oder importierte Datei, in der
mindestens 80 % der Punkte eine Leistung tragen (`derivePowerMeasured`, gleiche
Schwelle `MEASURED_POWER_MIN_COVERAGE` wie die Lastrechnung). `false` heißt
„aus GPS und Profil geschätzt **oder unbekannt**" — beides wird gleich
behandelt.

* Gesetzt wird das Feld an der einen Stelle, durch die jede Tour beim
  Speichern geht: `RideStorage.saveRideInternal` (Aufzeichnung, Indoor, Import,
  Sync).
* **Format:** wie `planned`/`indoor` nur geschrieben, wenn `true`, im
  Tour-JSON vor `updatedAt`, im Index-Eintrag angehängt. Jede Tour ohne
  Messung bleibt byteweise im alten Format; alte Dateien, alte Indizes und alte
  Sicherungen lesen sich als `false`; ältere App-Versionen und der Selfhost-
  Server ignorieren den Schlüssel (Test `AuswertungIndoorTest`).
* **FTP-Beschriftung:** `resolveEftp(..., bestTwentyMinMeasured)` liefert
  `EftpSource.ZWANZIG_MINUTEN_GEMESSEN`, wenn die Tour mit dem besten
  20-min-Mittel gemessene Leistung hat (`RideLoadFacts.physicsMeasured`, das
  Mittel kommt aus derselben Reihe). Der Hinweis lautet dann „…aus gemessener
  Leistung (Leistungsmesser oder Rollentrainer)" statt „aus der
  GPS-Leistungsschätzung (±15–25 %)". Die Rechnung selbst (`0,95 × Mittel`)
  ist unverändert.

## Offen / nicht auf dem Gerät geprüft

Nichts davon ist auf einem Gerät gelaufen; geprüft sind nur JVM-Tests.
Vorschläge für `docs/TODO.md`: den Punkt „eFTP-Text bei Leistungsmesser"
streichen; ein „Leistung gemessen"-Kennzeichen im Tourdetail erwägen.
