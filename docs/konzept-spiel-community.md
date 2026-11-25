# Spiel, Avatar, Community, Mehrspieler — Erörterung

Stand: 25.11.2026. Anlass ist die Frage des Inhabers: „Eventuell mehr gamification, anpassen
von avatar Kleidung rad und so weiter, community Funktionen und multiplayer“. Dieses Dokument
ist eine **Erörterung**, kein Umbau: Es beschreibt, was der Code heute hergibt, wägt Wege ab und
schlägt eine Reihenfolge vor. Produktivcode ist nicht geändert.

Aussagen über den Ist-Stand verweisen auf Dateien. Wo etwas eine Schätzung ist (Aufwand, Kosten,
Recht), steht das dabei.

---

## 0 Kurzfassung

- Trailscape hat **schon viel Motivationsmechanik**, aber verstreut und ohne Rahmen: Wochenserie,
  Bestwerte, Meilensteine, Jahresziel, Monats-Challenges mit Bonusstufe, Streckvorgabe,
  Highlights nach der Fahrt, Entdeckt-Kacheln, neue Wege, lokale Anstiegs-Bestzeiten, Geist im
  Indoor-Training. Alles wird **aus den Touren berechnet**, nichts wird eigens gespeichert.
- **Gamification** lässt sich auf diesem Prinzip ausbauen (Abzeichen und Stufen als reine
  Funktionen über die Touren), ohne neues Dateiformat und ohne Server. Sie muss so gebaut sein,
  dass Ruhetage und Entlastungswochen **nicht bestraft** werden: Punkte aus Fahrzeit mit
  Tagesdeckel, keine Tagesserien, Abzeichen auch für eingehaltene Erholung.
- **Avatar und Rad**: Das Fahrermodell wird im Code erzeugt (rund 30 000 Dreiecke, keine
  Texturen, Vertexfarben). Farben sind günstig, Muster in Grenzen auch, neue Radtypen und
  Körperformen bedeuten neue Geometrie. Der wichtigste technische Schritt wäre, einfärbbare Zonen
  als **eigene Materialien** mit grauen Vertexfarben auszugeben; dann genügt zur Laufzeit
  `baseColorFactor`, und es entsteht nicht mehr je Farbe eine Kopie des Modells.
- **Community** ohne Server ist möglich (Dateien, Links, Herausforderungen), mit selbst
  gehostetem Server für Gruppen und Vereine ebenfalls. Eine **zentrale Plattform** mit Konten
  bringt DSGVO-, DSA- und Moderationspflichten und Betriebsaufwand, die ein Einzelentwickler nur
  schwer dauerhaft trägt.
- **Mehrspieler**: Zeitversetzt gegen fremde Fahrten geht fast mit vorhandenem Code (die
  Geist-Kurve ist schon das Format). Eine verabredete Echtzeit-Gruppenfahrt in einem Raum mit
  Einladungslink ist mit einem erweiterten Sync-Server machbar. Eine offene Welt mit vielen
  Fahrern braucht einen zentralen Dienst.
- **Empfehlung**: eine Mischform, „ohne Konto“ als Grundsatz. Lokal, über Dateien und Links und
  über selbst gehostete Räume. Ein offizieller zentraler Dienst erst, wenn es dafür Belege gibt,
  und dann ohne Konto, nur als flüchtiges Relais.

---

## 1 Ist-Stand im Code

### 1.1 Ziele, Bestwerte, Serien, Motivation

| Baustein | Datei | Was er tut |
|---|---|---|
| `RideFact` | `core/.../Motivation.kt` | Grundlage aller Motivationsrechnung: `id`, `date`, `distanceKm`, `ascentM`, `timeS`, `avgSpeedKmh`. Geplante Routen zählen nicht mit. |
| Wochenserie | `Motivation.kt`, `weekStreak` | Zählt **Wochen** (Mo–So) mit mindestens einer Tour, nicht Tage. `atRisk`, wenn die Serie läuft, die laufende Woche aber noch leer ist. Kennt keine Pause- oder Krankheitswochen. |
| Bestwerte | `Motivation.kt`, `RecordKind` | Weiteste Tour, meiste Höhenmeter, längste Fahrzeit, höchster Schnitt (erst ab `fastestMinKm` = 20 km). `recordsSetBy` nennt, was eine Tour neu gesetzt hat. |
| Meilensteine | `Motivation.kt`, `milestoneLadder` | Leitern für Gesamt-km (50 … 100 000, darunter Erdumfang 40 075), Gesamt-Höhenmeter (500 … 250 000, darunter Everest 8849 und 10 × Everest), Tourenzahl (1 … 1000), weiteste Tour (25 … 300 km). |
| Jahresziel | `Motivation.kt`, `YearGoal`, `suggestYearGoalKm` | Vorschlag aus der Historie mit Aufschlag 1,15, Grenzen 100–30 000 km. |
| Streckvorgabe | `Motivation.kt`, `stretchChallenge` | 42-Tage-Fenster, Faktor 1,10. |
| Monats-Challenges | `core/.../Challenges.kt` | Vier Arten (km, Höhenmeter, aktive Tage, längste Tour), Ziel aus der eigenen Historie, danach eine **Bonusstufe**; `challengeHistory` über 12 Monate. Ausdrücklich „kein Server, keine anderen Fahrer“. |
| Wochenziel ohne Plan | `core/.../WeeklyGoal.kt` | Arten `EINSTIEG`, `WIEDEREINSTIEG`, `ENTLASTUNG`, `HALTEN`, `AUFBAU`; nach einer Pause nie ein Steigerungsziel. |
| Wochenrückblick | `core/.../WeeklyRecap.kt` | Woche gegen Vorwoche, aktive Tage, Bestwerte, Serie am Wochenende. |
| Highlights nach der Fahrt | `core/.../RideHighlights.kt` | Geordnete Kurzliste: Bestwerte, Meilensteine, Vergleich auf derselben Runde (Bestzeit nur mit Vorgänger, sonst Rang), Wochenziel. |
| Ziel während der Fahrt | `core/.../LiveZiel.kt` | Restkilometer bis Wochenziel oder Bestweite. |
| Uhr | `docs/wear-motivation.md` | Zwei Highlight-Zeilen nach der Fahrt (`PFAD_ABSCHLUSS`, je höchstens 60 Zeichen), Kachel und Komplikation mit Wochenstand, Zielzeile während der Fahrt. Die Uhr speichert nichts. |
| Entdeckt-Kacheln | `core/.../ExplorerTiles.kt`, `app/.../ui/map/ExplorerTileLayer.kt` | Slippy-Kacheln Stufe 14 (etwa 1,5 × 1,5 km), Nebel, Umriss, größtes Quadrat. |
| Neue Wege | `core/.../RiddenWays.kt` | 20-m-Raster, ob ein Routenpunkt schon gefahren wurde. |
| Anstiegs-Bestzeiten | `core/.../RideSegments.kt` | Automatisch erkannte Anstiege, die mindestens zwei eigene Touren teilen; je Befahrung ein Effort. Kopfkommentar: „Bestenlisten gegen sich selbst — ohne Server, ohne Datenabfluss“. |
| Trainingsplan-Fortschritt | `core/.../TrainingPlanProgress.kt` | Status je Planeinheit `OFFEN`, `ERLEDIGT` (≥ 60 % von km **oder** Last), `TEILWEISE`, `VERPASST`; Toleranz ±1 Tag. Der Kommentar begründet die Toleranz damit, dass ein Plan, der Verschieben als verpasst führt, „zum Ignorieren des Plans“ erzieht. |
| Erholung, Readiness | `core/.../Readiness.kt`, `docs/trainingswissenschaft.md` §5, §9 | Ampel `GRUEN…ROT`, Band `HART/NORMAL/LOCKER/RUHE`; Deload-Empfehlung bei TSB < −30 an 3 Tagen, Rampe > 8 oder schwacher Readiness. Plan mit Erholungswoche nach dem Muster 3:1 auf 55 % Umfang. |

Fazit: Die Bausteine einer Spielmechanik sind vorhanden und alle **deterministisch aus den
Touren abgeleitet**, „heute“ kommt als Parameter herein. Es gibt keinen gespeicherten
Spielstand, also auch keinen, der verloren gehen oder auseinanderlaufen kann.

### 1.2 Mitfahrer im Indoor-Training

- `IndoorGhostOption` (`app/.../ui/indoor/IndoorUiState.kt`): `None`, `PastRide(rideId, label)`,
  `ConstantPower(watts)`. Auswahl in `IndoorHomeScreen.kt` (`GhostChooser`).
- `core/.../indoor/IndoorGhost.kt`: `ConstantPowerGhost` fährt mit derselben Physik wie der
  Fahrer bei konstanter Leistung. `PastRideGhost` fährt eine **eigene frühere Fahrt** als
  Zeit-Weg-Kurve `GhostCurve(timesS, distsM)` auf den Laufmetern derselben Strecke. Die Uhr des
  Geists ist die Fahrzeit des Fahrers, Pausen halten beide an; ein Start mittendrin wird
  zeitlich verschoben.
- Die Darstellung lädt `ghost.glb` als eigenes Modell (`FilamentWorldRenderer.kt`), mit eigenem
  `MotionSmoother` und eigener Spur.
- **Andere Radfahrer** (`app/.../indoor/render/OtherRiders.kt`, `LifeLayer.kt`) sind rein
  dekorativ: höchstens `MAX_RIDERS` = 6, feste Dauerleistung 150–260 W, Fenster 150 m hinter
  bis 340 m vor dem Fahrer, sichtbar bis 260 m, Schatten bis 60 m. **Kein Windschatten**, auch
  nicht für den Geist (`docs/indoor/app.md`, `docs/indoor/render.md`).
- Physik (`core/.../indoor/IndoorPhysics.kt`): Luftwiderstand über `CdA` (`PhysicsRider.cdaM2`).
  Ein Windschatten ließe sich als Abschlag auf `CdA` einbauen.
- Sitzungsschleife 10 Hz (`docs/indoor.md` §5.7), Zustand an die Darstellung mit 10–20 Hz,
  gezeichnet mit 30–60 Hz; `MotionSmoother` schreibt mit dem Tempo fort und holt Abweichungen
  weich ein, springt bei großem Versatz. Das ist schon die Interpolation, die Fremdfahrer
  bräuchten.

### 1.3 Fahrer- und Radmodell

- `app/src/main/assets/indoor/models/rider.glb` (828 kB) und `ghost.glb` (609 kB), **im Code
  erzeugt** (`core/.../indoor/world/RiderModel.kt`, `writeIndoorModels`), ohne fremde
  Bestandteile (`LICENSES.md`); `RiderModelTest` prüft Byte-Gleichheit.
- Nachgezählt aus den Dateien: `rider.glb` **29 972 Dreiecke**, 13 Netze, 14 Knoten, keine
  Texturen, keine Erweiterungen, Animationen `pedal` und `roll`. `ghost.glb` 24 132 Dreiecke, ein
  durchscheinendes Material (`alphaMode BLEND`). Vertrag: zusammen höchstens 60 000 Dreiecke und
  2 MB (`docs/indoor.md` §7.5).
- Materialien (`RiderMat`): `x_mat_skin`, `x_mat_kit`, `x_mat_gear`, `x_mat_bike`, `x_mat_tire`,
  alle `metallic = 0`, `baseColorFactor` weiß. Die eigentlichen Farben stecken als
  **Vertexfarben** (`COLOR_0`) in der Geometrie: Trikot `0xD8432C` mit dunkler und heller
  Variante, Hose, Socken, Schuhe, Handschuhe, Helm, Brille, Rahmen `0x1F6272` mit hellem Band
  auf dem Unterrohr, Felgen, Reifenflanke und weitere (`RiderModel.kt`, Abschnitt „Farben“).
- Ein Radtyp: Gravel-Rad mit Rennlenker. Ein Körper.
- Animation: Kurbel, Pedale und Beine über 48 Schlüsselbilder je Umdrehung
  (`RiderRig.KEYFRAMES`), Beine aus einer Zwei-Gelenk-Rechnung, leichtes Wiegen des Oberkörpers.
  Die Darstellung setzt nur die Phase: Kurbelphase aus der Trittfrequenz, Radphase aus Tempo
  durch Abrollumfang 2,2 m (`MotionSmoother.kt`). Kein Wiegetritt, kein Aufstehen, kein Kopf.
- **Umfärben gibt es schon**: `RiderRecolor.kt` ersetzt in den Teilen mit `x_mat_kit` die
  rot-dominanten Vertexfarben durch eine von sechs Trikotfarben, als reine Byte-Arbeit an der GLB.
  `LifeLayer` lädt **je Trikotfarbe ein eigenes Asset**. Bei unerwartetem Aufbau fällt es auf das
  Original zurück.
- Fallback: `AvatarModel.kt` baut einen einfacheren Fahrer im Code, falls die Datei fehlt.

### 1.4 Renderer-Risiken

- `GlbPatch.kt` (Issue 106): Mit dem eigenen Kotlin-`MaterialProvider` kennt die JNI-Schicht von
  gltfio das Feld `hasSpecular` nicht. Der Ubershader wählt deshalb ein Material ohne
  Spiegel-Parameter, gltfio setzt trotzdem `specularColorFactor`, und Filament **beendet den
  Prozess**. Abhilfe: `KHR_materials_specular` wird vor dem Laden gleich lang umbenannt.
- `WorldMaterialProvider.kt`: Jeder Fehler fällt auf den Ubershader zurück; eine Ausnahme darf
  nicht in den nativen Code zurück.
- Vertrag für jede GLB (`docs/indoor.md` §7.3): nur Kernmaterial und `KHR_materials_unlit`,
  `roughness ≥ 0,6`, keine weiteren Erweiterungen, Texturen JPEG Baseline oder PNG, Zweierpotenzen,
  höchstens 2048.
- Budget gleichzeitig (`docs/indoor.md` §7.4): `LOW` ≤ 300 000 Dreiecke und ≤ 128 MB Texturen,
  `MEDIUM` ≤ 700 000 und ≤ 256 MB, `HIGH` ≤ 1 500 000 und ≤ 400 MB. Die Welt plant gegen diese
  Grenzen, die Fahrer kommen hinzu.

Daraus folgt: Der eigene Fahrer plus sechs andere sind schon **rund 210 000 Dreiecke**, auf
`LOW` also mehr als zwei Drittel des Gesamtbudgets. Für Gruppenfahrten mit zehn oder mehr
Fahrern braucht es eine Detailstufe (LOD) für Fahrer.

### 1.5 Sync-Server

`server/server.mjs` (561 Zeilen, nur Node-Core-Module), `server/README.md`:

- **Ein** `SYNC_TOKEN` je Instanz, mindestens 16 Zeichen, `Authorization: Bearer`; Sperre nach 10
  Fehlversuchen in 15 Minuten (HTTP 429). Mehrere Nutzer bedeuten mehrere Instanzen.
- Nur HTTP, TLS über einen vorgeschalteten Reverse-Proxy (ausdrücklich Pflicht).
- Endpunkte: `GET/PUT/DELETE /api/rides[/<id>]`, Tombstones, Last-Write-Wins über `updatedAt`;
  Körper höchstens 20 MB (`MAX_BODY_BYTES`). Gespeichert werden Touren als JSON-Dateien.
- Optional `/api/worlds/…` als Weiterleitung an den Bau-Dienst (`server/worlds/`).
- **Kein** WebSocket, kein Server-Push, keine Räume, kein Profil, keine Gruppen. Profil und
  Jahresziel gehen nicht über den Sync (`core/.../SyncClient.kt` kennt nur Touren).

### 1.6 Datei- und Backup-Formate, Teilen

- Backup: eine JSON-Datei `trailscape-backup-JJJJ-MM-TT.json` mit `app`, `backupVersion`,
  `exportedAt`, `profile`, optional `yearGoal`, `rides` (`core/.../Export.kt`, auch streamend).
- Touren: JSON im privaten App-Verzeichnis; Indoor-Touren sind normale Touren mit `indoor: true`
  (`core/.../Models.kt`).
- Teilen: Tourbild und GPX (`app/.../ui/rides/RideShare.kt`, `ShareCardRenderer.kt`); Start und
  Ziel werden im Bild gekürzt (`core/.../TrackPrivacy.kt`). Import von GPX über „Teilen“ und
  „Öffnen mit“ (`ImportIntents.kt`, Manifest). Ein eigenes Schema `trailscape://` gibt es nur für
  den Strava-Rücksprung; verifizierte App Links fehlen (`docs/TODO.md`, Strava-Abschnitt).
- Inhalte ohne eigenen Server: Der Katalog „Bekannte Strecken“ kommt als `catalog.json` plus
  Pakete aus einem GitHub-Release, höchstens einmal am Tag (`PRIVACY.md` §4). Das ist ein
  Vorbild für Inhalte wie Saison-Herausforderungen.

### 1.7 Datenschutz-Versprechen

`PRIVACY.md`, Kurzfassung: kein Konto, keine Nutzerkennung, kein Analytics, keine
Crash-Telemetrie, alle Daten auf dem Gerät, „es gibt keinen Trailscape-Server, auf dem etwas von
dir liegt“, der Entwickler hat keinen Zugriff. Netzverkehr entsteht nur aus Nutzeraktionen
(Ausnahmen: Update-Prüfung der GitHub-APK, Katalogliste). Abschnitt 9: Die App richtet sich nicht
an Kinder.

Jede Funktion, die Daten an einen **vom Entwickler** betriebenen Dienst schickt, bricht diesen
Satz und muss in `PRIVACY.md` und im Play-Datensicherheitsformular neu beschrieben werden.

---

## 2 Gamification

### 2.1 Was passt und was schadet

Trailscape ist eine Trainings-App mit Belastungssteuerung (`docs/trainingswissenschaft.md`).
Spielmechanik ist dort gut, wo sie **Regelmäßigkeit** und **Planbefolgung** belohnt, und
schädlich, wo sie **Menge** oder **lückenlose Tage** belohnt.

| Mechanik | Nutzen | Gefahr | Einschätzung |
|---|---|---|---|
| Tagesserie („jeden Tag fahren“) | starker Sog | widerspricht Ruhetagen, 3:1-Blöcken, Readiness `RUHE`; erzieht zum Fahren trotz Müdigkeit oder Krankheit | **nicht bauen** |
| Wochenserie (gibt es) | Regelmäßigkeit, Ruhetage bleiben möglich | eine Krankheitswoche zerstört eine lange Serie, Druck, „irgendwas“ zu fahren | behalten, um **Pausenwochen** ergänzen (siehe unten) |
| Punkte für km oder Höhenmeter | einfach | bevorzugt schnelle Draußenfahrer, belohnt Umfangssprünge über die Rampe (§3.2, §8) | Punkte aus **Fahrzeit mit Deckel** |
| Punkte für Intensität oder Last | wirkt „trainingsnah“ | belohnt genau das, was die Deload-Regeln bremsen (§4, §9) | **nicht bauen** |
| Planeinheit erledigt | stützt den Plan | gering, solange „Teilweise“ auch zählt | bauen |
| Erholungswoche eingehalten | macht Erholung sichtbar | keine | bauen, ist der wichtigste Ausgleich |
| Abzeichen für Einmaliges (erste 100 km, Everest gesamt) | Meilensteine gibt es schon, Abzeichen machen sie sammelbar | gering | bauen |
| Bergwertung, Sprints | Ziel innerhalb einer Fahrt | Maximalbelastung ohne Bezug zum Plan | lokal ja, mit Hinweis an harten Tagen; keine Punkte für Bestzeiten |
| Herausforderungen aus dem Plan | verbindet Spiel und Training | keine, wenn sie aus dem Plan kommen und nicht darüber hinaus | bauen |
| Freischaltungen (Garage) | langer Bogen | „Grind“, wenn Wichtiges hinter Punkten liegt | nur **Kosmetik** freischalten, nie Funktionen oder Leistungsvorteile |
| Ranglisten gegen andere | starker Sog | Betrug, Vergleich ungleicher Voraussetzungen, Datenschutz | erst mit Community-Stufe, nur auf identischen virtuellen Strecken |

Grundsatz für alle Texte, entsprechend §0.2 („Beschreiben, nicht diagnostizieren“): Kein Text
drängt an einem Tag mit Readiness `RUHE`/`LOCKER` oder in einer Erholungswoche zu mehr. Eine
gefährdete Serie (`WeekStreak.atRisk`) wird in einer Erholungswoche nicht angemahnt.

### 2.2 Pausenwochen für die Serie

Vorschlag: Eine Woche **unterbricht die Serie nicht**, wenn sie (a) im Plan eine Erholungs- oder
Taperwoche ist und mindestens eine Tour hat, oder (b) vom Nutzer vorab oder rückwirkend als
„Pause“ markiert wird (Krankheit, Urlaub), höchstens zwei solche Wochen je 12 Wochen. Pausenwochen
verlängern die Serie nicht, sie halten sie nur. Gespeichert wird nur (b), als Liste von Montagen
(siehe 2.6).

### 2.3 Punkte und Stufen (Vorschlag)

**Punkte (P)**, je Kalendertag:

```
P_Tag = min(Fahrzeit_min, 180)                 // 1 P je Minute Bewegungszeit, Deckel 3 h am Tag
      + 40 je Planeinheit ERLEDIGT, 20 je TEILWEISE
P_Woche += 60, wenn das Wochenziel erreicht ist (Plan- oder WeeklyGoal-Ziel)
P_Monat += 100 je erreichter Monats-Challenge, + 50 je Bonusstufe
```

Begründung:
- **Zeit statt km**: Indoor, Gelände, langsame und schnelle Fahrer werden gleich behandelt.
- **Deckel 180 min am Tag**: Eine 7-h-Tour ist ein Erlebnis (dafür gibt es Abzeichen), aber kein
  Punkte-Hebel. Nutzer ohne Plan bekommen über das Wochenziel einen gleichwertigen Bonus.
- **Keine Punkte** für Tempo, Bestwerte, Höhenmeter, Last oder Intensität.
- Fahrten an Ruhetagen bekommen normale Zeitpunkte, aber nichts aus dem Plan. Abzüge gibt es
  nicht; sie würden als Bevormundung empfunden.

**Stufen**: Gesamtpunkte für Stufe L

```
P(L) = 500 × (L − 1)^1.6        für L = 1 … 50
```

| Stufe | nötige Gesamtpunkte | entspricht etwa (bei ~450 P/Woche) |
|---|---|---|
| 2 | 500 | gut 1 Woche |
| 5 | 4 600 | 10 Wochen |
| 10 | 16 800 | 9 Monate |
| 12 | ~23 200 | 1 Jahr |
| 20 | 55 600 | 2,5 Jahre |
| 30 | 109 400 | 5 Jahre |
| 50 | 253 000 | 11 Jahre |

Annahme für die Spalte rechts: drei Fahrten je 90 min, drei erledigte Planeinheiten, Wochenziel
erreicht, also gut 450 P je Woche. Die Kurve ist am Anfang steil (schnelle erste
Erfolgserlebnisse) und flacht ab. Die Zahlen sind ein Ausgangspunkt und sollten mit den eigenen
Touren des Inhabers und einiger Tester nachgerechnet werden, bevor sie festgeschrieben werden.

Da alles abgeleitet ist, ändert eine neue Formel rückwirkend alle Stände. Das ist ein Vorteil
(Fehler lassen sich korrigieren), aber ein Nutzer könnte eine Stufe „verlieren“. Abhilfe: Eine
einmal erreichte Stufe wird gespeichert und nie gesenkt (eine Zahl, siehe 2.6).

### 2.4 Die ersten 30 Abzeichen

Alle Bedingungen lassen sich aus vorhandenen Daten berechnen. „Quelle“ nennt die Funktion, auf
der die Bedingung aufsetzt.

| # | Abzeichen | Bedingung | Quelle |
|---|---|---|---|
| 1 | Losgefahren | erste gespeicherte Tour | `RideFact` |
| 2 | Halbe Sache | eine Tour ≥ 50 km | `RideFact.distanceKm` |
| 3 | Hunderter | eine Tour ≥ 100 km | `RideFact` |
| 4 | Tausend Meter | eine Tour ≥ 1000 Hm | `RideFact.ascentM` |
| 5 | Lange Leitung | eine Tour mit Fahrzeit ≥ 4 h | `RideFact.timeS` |
| 6 | Zehn Touren | 10 Touren | `MilestoneKind.RIDE_COUNT` |
| 7 | Hundert Touren | 100 Touren | `MilestoneKind.RIDE_COUNT` |
| 8 | Tausend Kilometer | 1000 km gesamt | `MilestoneKind.TOTAL_KM` |
| 9 | Einmal um die Erde | 40 075 km gesamt | `earthCircumferenceKm` |
| 10 | Everest | 8849 Hm gesamt | `everestM` |
| 11 | Vier Wochen dabei | Wochenserie 4 | `weekStreak` |
| 12 | Ein Vierteljahr | Wochenserie 12 | `weekStreak` |
| 13 | Ein ganzes Jahr | Wochenserie 52 (Pausenwochen halten, 2.2) | `weekStreak` + Pausen |
| 14 | Monat geschafft | erstmals alle Monats-Challenges eines Monats erreicht | `challengeProgress` |
| 15 | Zugabe | erstmals eine Bonusstufe erreicht | `ChallengeTier.BONUS` |
| 16 | Jahresziel | Jahresziel erreicht | `yearGoalProgress` |
| 17 | Nach Plan | erste Planeinheit `ERLEDIGT` | `TrainingPlanProgress` |
| 18 | Volle Woche | alle Einheiten einer Planwoche `ERLEDIGT` oder `TEILWEISE`, mindestens eine `ERLEDIGT` | `TrainingPlanProgress` |
| 19 | Erholung ist Training | Erholungswoche des Plans mit mindestens einer Tour und Wochensumme unter dem Deckel der Erholungswoche | `PlanWeeks.kt`, `RECOVERY_FACTOR` |
| 20 | Durchgezogen | Plan bis zum Ziel mit ≥ 80 % erledigten Einheiten | `TrainingPlanProgress` |
| 21 | Drinnen | erste Indoor-Fahrt | `Ride.indoor` |
| 22 | Wintertraining | 10 h Indoor-Fahrzeit gesamt | `Ride.indoor`, `timeS` |
| 23 | Geist geschlagen | auf derselben Strecke vor dem Geist einer früheren eigenen Fahrt im Ziel | `PastRideGhost.arrivalS` |
| 24 | Bekannte Strecke | eine Strecke aus dem Katalog vollständig gefahren | Indoor-Katalog, Laufmeter = Länge |
| 25 | Bergkenner | erster erkannter Anstieg mit zwei Befahrungen | `RideSegments.kt` |
| 26 | Schneller oben | eigene Bestzeit an einem Anstieg verbessert | `RideSegments.kt` bzw. die parallel entstehende Bergwertung |
| 27 | Rundenkenner | dieselbe Runde fünfmal gefahren | Rundenvergleich in `RideHighlights.kt` |
| 28 | Kartograf | 100 Entdeckt-Kacheln | `ExplorerTiles.kt` |
| 29 | Quadrat | größtes Quadrat mindestens 5 × 5 Kacheln | `ExplorerTileLayer.kt` (Rechnung nach `:core` ziehen) |
| 30 | Neue Wege | 100 km auf bisher nicht gefahrenen Wegen | `RiddenWays.kt` |

Bewusst **nicht** in der Liste: „sieben Tage am Stück“, „schnellster Schnitt“, „höchste
Wattzahl“, „TSS-Rekord“. Mehrstufige Abzeichen (Bronze, Silber, Gold) können später kommen; die
Meilenstein-Leitern liefern die Stufen bereits.

Nummer 29 hängt an Code in `:app`; die Rechnung des größten Quadrats müsste für ein reines
`:core`-Abzeichen nach `ExplorerTiles.kt` wandern. Nummer 26 sollte auf die parallel entstehende
Bergwertung mit lokalen Bestzeiten aufsetzen statt eine zweite Rechnung zu bauen.

### 2.5 Herausforderungen aus dem Trainingsplan

Statt eigener Herausforderungen, die mit dem Plan konkurrieren, **macht man den Plan selbst zur
Herausforderung**: die Planwoche als „Wochenaufgabe“ mit drei Kästchen, eine Schwelleneinheit
als Indoor-Fahrt auf einer bekannten Strecke mit dem Geist bei Zielleistung
(`ConstantPowerGhost` mit der Zielwattzahl der Einheit), der Test eines Fitnessblocks als
„Prüfung“ mit Abzeichen. Saison-Herausforderungen (etwa „Dezember drinnen: 10 h“) lassen sich
wie der Katalog als Datei aus einem GitHub-Release ausliefern: kein Server, kein Rückkanal.

### 2.6 Datenmodell, Speicherung, Backup, Sync

- **Abgeleitet** (nicht gespeichert): Punkte, Abzeichen, Fortschritt. Neue reine Funktionen in
  `:core`, etwa `Achievements.kt` mit `achievements(facts, plan, indoorFacts, today):
  List<AchievementState>`; `AchievementState(id, reached, rideId?, date?, progress)`. Die
  freischaltende Tour und ihr Datum ergeben sich aus der Rechnung.
- **Gespeichert** (klein): höchste erreichte Stufe (gegen „Herabstufen“ bei Formeländerung),
  markierte Pausenwochen, gesehene Abzeichen (damit „neu“ nur einmal erscheint). Zusammen unter
  1 kB in den SharedPreferences.
- **Backup**: zusätzlicher Schlüssel `progress` im Backup-Kopf neben `profile` und `yearGoal`.
  Ob ältere App-Versionen unbekannte Schlüssel beim Import ignorieren, ist im Import
  (`Export.kt`, Lesepfad ab `backupVersion`) zu prüfen, bevor man das einführt.
- **Sync**: Da Abzeichen aus Touren folgen, synchronisieren sie sich mit den Touren. Die kleinen
  gespeicherten Werte gehen heute nicht über den Sync. Falls nötig: ein zusätzlicher Endpunkt
  `PUT /api/state/progress` am Sync-Server (Last-Write-Wins). Eine Erweiterung des Protokolls, die
  man für den Avatar ohnehin braucht (3.6).
- **Uhr**: Ein neu erreichtes Abzeichen kann als eine der zwei Zeilen von `PFAD_ABSCHLUSS`
  erscheinen. Das Protokoll bleibt unverändert.

### 2.7 Selbstbetrug

Lokal betrügt man nur sich selbst. Ein GPX-Import erzeugt Touren, die mitzählen; das ist bei
Meilensteinen heute auch so. Sinnvoll und billig ist nur: Importierte Touren mit Datum vor der
Installation zählen für Abzeichen, aber nicht für Punkte der Vergangenheit (sonst springt ein
Neuling mit seinem Strava-Export sofort auf Stufe 25). Mehr Schutz lohnt erst, wenn sich Nutzer
vergleichen (Kapitel 4).

---

## 3 Avatar, Kleidung, Rad

### 3.1 Was mit den vorhandenen Modellen günstig ist

| Wunsch | Machbarkeit heute | Aufwand |
|---|---|---|
| Trikotfarbe | gibt es für Fremdfahrer (`RiderRecolor`, 6 Farben) | klein |
| Hose, Socken, Schuhe, Handschuhe, Helm, Brille einfärben | Farben stehen als Vertexfarben im Generator; einfärbbar wie das Trikot, wenn jede Zone erkennbar ist | klein bis mittel |
| Rahmenfarbe, Akzentfarbe (Band am Unterrohr), Reifenflanke (braun/schwarz), Felgen | wie oben, `C_FRAME`, `C_FRAME_LIGHT`, `C_SIDEWALL`, `C_RIM` | klein bis mittel |
| Hautton, Haarfarbe | `C_SKIN`, `C_HAIR` | klein; Auswahl mit Sorgfalt gestalten (Palette, keine Wertung) |
| Muster (Streifen, Schulterpasse, Seitenteile, Rahmen-Fade) | ohne Texturen nur als **Vertexfarben-Grenzen** im Generator, wie das Band auf dem Unterrohr | mittel; je Muster ein Variantensatz im Generator |
| Kurzarm/Langarm, Weste, Überschuhe | andere Geometrie am Körper, aber im Generator machbar | mittel |
| Radtyp Rennrad | andere Reifen, Geometrie kaum anders | mittel |
| Zeitfahrrad, Mountainbike | Aufsatzlenker, andere Sitzhaltung, Federgabel, breite Reifen; neue Pose und Animation | groß |
| Körperform, Größe, Geschlecht | neuer Körper, neue Beinrechnung je Größe | groß; ab hier lohnt ein **Grafiker** oder ein Modell mit offener Lizenz |
| Gesicht, Frisur, Bart | erkennbare Details brauchen Texturen oder deutlich mehr Dreiecke | groß; nicht für den Anfang |
| Logos, eigene Bilder | Texturen; dazu Urheber- und Markenrecht, bei Weitergabe Moderation | **nicht anbieten** |

Wichtig: Der Generator ist Code. Varianten (Radtyp, Ärmel, Muster) sind deshalb **testbar und
reproduzierbar** wie heute (`RiderModelTest`). Für Farben, Muster und zwei, drei Radtypen braucht
es keinen Grafiker. Für Körpervielfalt und Gesichter schon.

### 3.2 Technischer Kern: Zonen als Materialien

Heute entsteht je Farbe eine **neue Kopie** der GLB (`RiderRecolor`), die `LifeLayer` als eigenes
Asset lädt. Das hält bei sechs Farben, skaliert aber nicht auf frei kombinierbare Avatare für
mehrere Mitfahrer: Jede Kombination wäre ein eigenes Asset mit eigenen Puffern.

Vorschlag:

1. Der Generator gibt jede einfärbbare Zone als **eigenes Material** aus (`x_mat_jersey`,
   `x_mat_jersey_accent`, `x_mat_bibs`, `x_mat_helmet`, `x_mat_frame`, `x_mat_frame_accent`,
   `x_mat_skin`, `x_mat_hair` …, etwa 10–12 statt 5).
2. In diesen Zonen sind die Vertexfarben **grau** (Helligkeit für Schattierung und Ränder); die
   Farbe kommt aus `baseColorFactor`. glTF multipliziert `baseColorFactor` mit `COLOR_0`, das ist
   Kernmaterial, keine Erweiterung.
3. Zur Laufzeit setzt die Darstellung je Instanz `baseColorFactor` auf der `MaterialInstance`. Ein
   Asset, beliebig viele Instanzen mit unterschiedlichen Farben.

Mehr Materialien bedeuten mehr Draw Calls je Fahrer (heute 13 Netze mit Teilen je Material). Bei
zehn Fahrern bleibt das im Rahmen, gemessen ist es nicht. Auf `LOW` sollte man es vor der
Einführung messen.

### 3.3 Risiken im Renderer

- **Nur Parameter setzen, die das Material sicher hat.** Issue 106 war genau ein Fall, in dem ein
  gesetzter Parameter im gewählten Material fehlte und Filament den Prozess beendete.
  `baseColorFactor` haben alle Ubershader-Varianten, und laut Kopfkommentar von
  `WorldMaterialProvider.kt` deklarieren auch die eigenen Materialien ihn. Trotzdem: vor jedem
  `setParameter` prüfen, ob das Material den Parameter kennt (Filament bietet dafür eine Abfrage
  am `Material`; Name und Verhalten auf der verwendeten Version prüfen) und bei Fehlen still nichts
  tun.
- **Keine neuen Erweiterungen** in Avatar-GLBs (Vertrag §7.3). Kein `KHR_materials_specular`,
  kein `KHR_texture_transform`, keine Draco-Kompression.
- **Texturen nur, wenn nötig**, dann JPEG Baseline oder PNG, höchstens 512² je Fahrer
  (1,4 MB Grafikspeicher). Zehn Fahrer mit eigener Textur wären 14 MB, auf `LOW` (128 MB gesamt)
  vertretbar, aber nicht frei.
- **Fremde Modelle** (Grafiker, offene Lizenzen) laufen durch dieselbe Prüfung wie die Welt:
  eine Testklasse wie `GlbModelsTest`, die Materialien, Erweiterungen, Dreiecke und Knotennamen
  (`RiderNodes`) gegen den Vertrag prüft, sonst greift der Fallback `AvatarModel`.

### 3.4 Budget fürs Handy

| Rolle | Dreiecke | Bemerkung |
|---|---|---|
| eigener Fahrer, nah | ≤ 30 000 | wie heute `rider.glb` |
| Mitfahrer bis ~30 m | ≤ 30 000 | dasselbe Modell |
| Mitfahrer 30–260 m | 3 000–5 000 | **neue LOD-Stufe**: ohne Kette, Speichen, Finger, mit gröberen Rohren; im Generator ableitbar |
| Mitfahrer > 260 m | — | ausgeblendet wie heute (`VISIBLE_M`) |
| Geist | ≤ 25 000 | wie heute `ghost.glb` |

Mit LOD: eigener Fahrer plus vier nahe und zehn ferne Mitfahrer ≈ 30 000 + 120 000 + 50 000 =
200 000 Dreiecke, etwa so viel wie heute mit sechs dekorativen Fahrern. Ohne LOD wären 15 Fahrer
450 000 Dreiecke, auf `LOW` nicht tragbar. Datei: Je Radtyp ein Modell und eine LOD-Stufe, je
etwa 0,8 MB plus 0,15 MB; drei Radtypen ≈ 3 MB im APK.

### 3.5 Freischaltungen und Bedienung („Garage“)

- **Garage** als eigener Bildschirm im Indoor-Bereich, mit drehbarer 3D-Vorschau. Die parallel
  entstehende 3D-Vorschau sollte so gebaut werden, dass sie auch einen Fahrer allein zeigen kann.
- Drei Reiter: **Fahrer** (Haut, Haar, Helm, Brille), **Kleidung** (Trikot, Muster, Hose,
  Socken, Schuhe), **Rad** (Typ, Rahmen, Akzent, Reifen).
- Von Anfang an verfügbar: alle Grundfarben, ein Muster, das Gravel-Rad. Niemand soll sich
  „hässlich“ fühlen müssen, bis er genug gefahren ist.
- Freischaltbar: weitere Muster, Sonderfarben, Radtypen, Trikots zu Abzeichen (etwa ein
  „Everest“-Trikot zu Abzeichen 10, Bergtrikot-Muster zu Abzeichen 26). Gesperrtes zeigt die
  Bedingung, nicht nur ein Schloss.
- **Rein kosmetisch.** Kein Radtyp ändert die Physik, sonst wäre jeder Vergleich mit anderen
  verfälscht und Freischaltungen würden zu Leistungsvorteilen. Wer die Physik anpassen will,
  bleibt beim vorhandenen `RiderSetup` (Masse, CdA, Rollwiderstand), unabhängig vom Aussehen.

### 3.6 Datenformat

```json
{
  "v": 1,
  "body": { "skin": 3, "hair": 1 },
  "kit":  { "jersey": "#1F6272", "jerseyAccent": "#F1EEE8", "pattern": "shoulder",
            "bibs": "#25262B", "socks": "#EEEEEA", "shoes": "#F2F2EE",
            "helmet": "#F0F0EC", "sleeves": "short" },
  "bike": { "type": "gravel", "frame": "#D8432C", "frameAccent": "#E8E6DF", "tires": "tan" }
}
```

- Etwa 300 Byte. Hautton und Haar als Index in eine Palette, Kleidung und Rad als Farbe (frei
  wählbar, beim Lesen auf gültige Werte geklemmt). Muster, Typ und Ärmel als feste Kennungen;
  unbekannte Kennungen fallen auf die Vorgabe zurück (vorwärtskompatibel).
- **Kein Freitext**, kein Name, kein Bild im Avatar. Das hält ihn moderationsfrei, auch wenn er
  später an andere geht.
- Speicherung in den SharedPreferences, im Backup unter `avatar`, beim Sync am eigenen Server über
  einen Zustands-Endpunkt (siehe 2.6). In Mehrspieler-Räumen wird genau dieses JSON beim Beitritt
  mitgeschickt.

---

## 4 Community

### 4.1 Stufen

| Stufe | Was der Nutzer hat | Technisch nötig | Kennung | Moderation | Laufend |
|---|---|---|---|---|---|
| **C0 Teilen ohne Server** | Tourbild, GPX (gibt es); neu: Herausforderung „fahr meine Zeit nach“ als Datei oder Link, Strecke und Avatar-Karte teilen | Dateiformat, Import-Intent, optional Link-Seite | keine | keine (Inhalte gehen privat von Person zu Person) | keine bis wenige € (Domain) |
| **C1 Gruppe auf eigenem Server** | Verein oder Freundeskreis: gemeinsame Bestenliste an Anstiegen und auf Katalogstrecken, Gruppen-Challenge, gemeinsame Räume (Kapitel 5) | Sync-Server mit Mitgliedern (mehrere Zugangsschlüssel), Gruppen-Endpunkte | Mitgliedsschlüssel vom Betreiber | durch den Betreiber der Gruppe | Betreiber trägt Server (~5 €/Monat) |
| **C2 Zentrale Bestenlisten** | Vergleich mit allen auf identischen Katalogstrecken | zentraler Dienst, pseudonyme Kennung, Plausibilitätsprüfung, Anzeigename | pseudonyme Geräte- oder Kontokennung | Anzeigenamen, Meldeweg, Sperren | Server, Bereitschaft, Rechtstexte |
| **C3 Soziales Netz** | Profile, Folgen, Feed, Kommentare, Vereine, Veranstaltungen | Konten, Datenbank, Bilder, Benachrichtigungen, Moderationswerkzeuge | Konto mit E-Mail | dauerhaft, mit Fristen | erheblich, wächst mit Nutzern |

### 4.2 C0: Teilen ohne Server

- **Herausforderungsdatei** `*.trailscape-challenge` (JSON, gezippt): Strecken-Referenz
  (Katalog-ID und Version **oder** die Strecke selbst bei eigenen Touren), die
  `GhostCurve(timesS, distsM)` der Fahrt, ein frei wählbarer Absendername nur zur Anzeige
  (lokal), optional das Avatar-JSON. Mit einer Probe je 5 s ist eine Stunde rund 720 Wertepaare,
  komprimiert wenige kB. Empfang: Import-Intent wie bei GPX, dann als `IndoorGhostOption` „fremde
  Fahrt“ wählbar.
- **Datenschutz bei eigenen Strecken**: Die Strecke einer Außenfahrt verrät Wohnort und
  Gewohnheiten. Vor dem Teilen gilt dasselbe wie für das Tourbild (`TrackPrivacy.kt`): Start und
  Ziel kürzen, und der Dialog sagt deutlich, dass die Strecke mitgeht. Katalogstrecken sind
  unkritisch.
- **Links**: Ein `trailscape://`-Link ist in vielen Messengern nicht anklickbar. Für Links braucht
  es eine `https`-Adresse und für das direkte Öffnen in der App verifizierte App Links (eigene
  Domain mit `/.well-known/assetlinks.json`, siehe `docs/TODO.md`). Damit der Server die Daten
  nicht sieht, stehen sie im **Fragment** (`https://…/c#<daten>`). Das Fragment schickt der
  Browser nicht an den Server; eine statische Seite (etwa GitHub Pages) genügt. Grenze: Links mit
  vielen kB sind unhandlich, und wie Messenger sehr lange Links behandeln, ist ungetestet. Für
  Katalogstrecken reicht ein kurzer Link mit vergröberter Kurve, für eigene Strecken die Datei.

### 4.3 C1: Gruppen auf dem eigenen Server

Der heutige Server kennt einen Schlüssel für einen Datenbestand. Für eine Gruppe braucht er:

- **Mitglieder**: Der Betreiber legt je Mitglied einen Schlüssel an (eine Datei
  `members.json` mit Hash des Schlüssels, Anzeigename, Rolle). Der bisherige `SYNC_TOKEN` bleibt
  der eigene Tourenbestand des Betreibers. Ein Mitglied sieht nur, was es teilt.
- **Gruppenbereich**: `PUT /api/group/efforts` (eine Befahrung: Strecken-ID, Zeit, Datum,
  Mitglied); `GET /api/group/leaderboard?route=…`. Es werden **keine ganzen Touren** geteilt,
  nur Ergebnisse auf Katalogstrecken oder vom Betreiber festgelegten Anstiegen.
- **Verantwortung**: Der Betreiber (etwa der Verein) ist für die Daten verantwortlich, nicht der
  Entwickler. `PRIVACY.md` bleibt im Kern richtig, braucht aber einen Absatz, was an einen
  fremden Gruppenserver geht.
- Aufwand: Server 300–600 Zeilen mehr, App: Gruppenansicht, Einladung per Link mit Schlüssel.
  Grob 4–8 Wochen.

### 4.4 C2/C3: zentral

Was ein zentraler Dienst zusätzlich verlangt:

- **Kennung**: mindestens ein pseudonymer Schlüssel je Gerät, für Bestenlisten ein Anzeigename,
  für Gruppen und Wiederherstellung ein Konto. Damit gibt es zum ersten Mal personenbezogene
  Daten beim Entwickler.
- **Moderation**: Anzeigenamen, später Texte und Bilder. Ein Meldeweg, Prüfung, Sperren,
  Begründungen.
- **Betrugsschutz**: Leistungswerte kommen vom Trainer über das Handy und lassen sich nicht
  beweisen. Möglich sind Plausibilitätsgrenzen (W/kg über Dauer, Sprünge in Leistung und
  Trittfrequenz, Tempo ohne Trainer), getrennte Listen für „mit Leistungsmesser“ und
  „geschätzt“, und das Ausblenden auffälliger Einträge. Vollständig lösbar ist es nicht.
- **Rechtliches (Einschätzung, keine Rechtsberatung, vor der Umsetzung fachlich prüfen lassen)**:
  - *DSGVO*: Rechtsgrundlage je Zweck (meist Vertrag oder Einwilligung), Auskunft, Löschung,
    Datenübertragbarkeit, Auftragsverarbeitungsvertrag mit dem Hoster (Art. 28), Verzeichnis der
    Verarbeitungstätigkeiten. Herzfrequenz und daraus abgeleitete Werte dürften
    **Gesundheitsdaten** nach Art. 9 sein; zusammen mit Standortdaten liegt eine
    Datenschutz-Folgenabschätzung nahe. Wer nur Zeiten auf virtuellen Strecken speichert, ohne
    Puls und ohne Spur, verringert das deutlich.
  - *Kinder*: Einwilligung in Deutschland erst ab 16 Jahren wirksam (Art. 8 DSGVO; Deutschland hat
    die Öffnungsklausel für eine niedrigere Grenze nicht genutzt). Die App richtet sich heute
    nicht an Kinder (`PRIVACY.md` §9); mit Kommunikationsfunktionen müsste man das durchsetzen
    oder Vorsorge treffen. Das Jugendschutzgesetz verlangt Vorsorgemaßnahmen von Diensten mit
    Kommunikationsfunktionen; nach unserem Verständnis gibt es eine Schwelle nach Nutzerzahl, die
    ein kleiner Dienst nicht erreicht. Das ist zu prüfen.
  - *Digital Services Act*: Wer Inhalte von Nutzern speichert und anderen zeigt, ist
    Hostingdienst. Melde- und Abhilfeverfahren und Begründung von Sperren gelten auch für kleine
    Anbieter; für Kleinst- und Kleinunternehmen entfallen einige weitergehende Pflichten von
    Online-Plattformen. Außerdem eine Kontaktstelle.
  - *Impressum*: Anbieterkennzeichnung nach dem Digitale-Dienste-Gesetz (früher TMG), mit
    ladungsfähiger Anschrift. Für einen Einzelentwickler heißt das meist: Privatanschrift oder
    eine kostenpflichtige Geschäftsanschrift.
  - *Nutzergenerierte Inhalte*: Haftung nach Kenntnis, Urheber- und Markenrecht bei Bildern und
    Logos. Ohne Freitext und Bilder (nur Avatar-Kennungen, ausgewählte Namen) bleibt das klein.
  - *Play-Datensicherheitsformular*: Heute lässt sich angeben, dass der Entwickler keine Daten
    erhebt. Ab C2 werden Kennung, Anzeigename, Leistungs- und ggf. Gesundheitsdaten
    „erhoben“ und „geteilt“; das Formular und die Erklärung müssen das genau abbilden.
- **Laufende Kosten und Aufwand (Größenordnung)**: Ein kleiner Server kostet einstellige bis
  niedrige zweistellige Euro im Monat. Teurer ist die **Zeit**: Ausfälle, Sicherheitsupdates,
  Datensicherung, Meldungen beantworten, Anfragen nach DSGVO, Missbrauch. Für einen
  Einzelentwickler realistisch einige Stunden je Woche, sobald es mehr als ein paar hundert
  aktive Nutzer gibt, und eine Bereitschaft, die mit Urlaub und Krankheit kollidiert.

---

## 5 Mehrspieler

### 5.1 (a) Zeitversetzt gegen Fahrten anderer

- **Architektur**: Datei oder Link nach 4.2, empfangen als dritter Geist-Typ
  (`IndoorGhostOption.SharedRide`). Die Rechnung gibt es schon (`PastRideGhost`,
  `GhostCurve`, Start mittendrin). Neu sind Format, Import, Anzeige von Name und Avatar und die
  Prüfung, dass die Strecke passt (Katalog-ID und Version gleich, sonst Abbruch mit Hinweis).
- **Mehrere Geister** gleichzeitig (etwa „die letzten drei Fahrten der Gruppe“): Die Darstellung
  hat heute **einen** Geist. Für mehrere braucht es Fahrer-Instanzen mit Avatar wie in 3.2 und die
  LOD-Stufe aus 3.4.
- **Windschatten** ist zeitversetzt möglich und deterministisch (der Geist fährt nach Kurve, der
  Fahrer bekommt den Abschlag), aber nur in eine Richtung: Der Geist reagiert nicht.
- **Betrug**: Eine geteilte Kurve kann gefälscht sein. Zwischen Freunden egal; in Bestenlisten
  (C2) nicht.
- **Server**: keiner nötig; mit C1 kommen Geister aus der Gruppe vom eigenen Server.
- **Aufwand**: 2–4 Wochen für Format, Import und einen fremden Geist; plus 2–3 Wochen für mehrere
  Geister mit Avatar.
- **Risiken**: gering. Wichtigste Frage ist, wie man eigene Außenstrecken teilt (Datenschutz,
  4.2).

### 5.2 (b) Verabredete Gruppenfahrt in Echtzeit

Einer eröffnet einen Raum für eine Strecke und eine Uhrzeit, schickt einen Link, 2–10 Leute
fahren gemeinsam.

- **Protokoll**: WebSocket über TLS (derselbe Reverse-Proxy). Nachrichten klein, als JSON oder
  später binär:
  - `join {room, secret, avatar, name}` → `welcome {riders[], startAt, route}`
  - `state {t, distM, speedMps, powerW, cadence, lane}` vom Fahrer, etwa **2–4 Hz**
  - `snapshot {t, riders:[{id, distM, speedMps, cadence, lane}]}` vom Server an alle, 2–4 Hz
  - `leave`, `ping/pong`.
  Ein Zustand sind etwa 40 Byte; zehn Fahrer bei 4 Hz sind unter 2 kB/s je Gerät.
- **Uhr und Interpolation**: Die Welt ist eindimensional (Laufmeter auf einer Strecke). Fremde
  Fahrer werden aus `distM + speedMps × (jetzt − t)` fortgeschrieben, genau was `MotionSmoother`
  schon tut (höchstens begrenzt weit, weiches Einholen, Sprung bei großem Versatz). Zeitbasis:
  Server-Uhr, Versatz über `ping` geschätzt. Mobilfunk-Latenzen von 100–300 ms ergeben bei
  10 m/s 1–3 m Versatz; das Fortschreiben gleicht es weitgehend aus.
- **Windschatten**: Abschlag auf `CdA`, abhängig vom Abstand zum Vordermann in derselben Spur.
  In der Literatur liegen die Ersparnisse für einen Fahrer dicht hinter einem anderen grob bei
  einem Viertel bis einem Drittel der Luftwiderstandsleistung; für die Umsetzung genügt eine
  einfache Kurve über den Abstand (voll bis ~1 m, auslaufend bis ~3–5 m). Jedes Gerät rechnet
  seinen Windschatten **selbst** aus den empfangenen Positionen. Wegen der Latenz flackert das an
  der Grenze; ein Glätten über eine Sekunde hilft. Erst bauen, wenn die Gruppenfahrt ohne
  Windschatten stabil läuft.
- **Zusammenbleiben**: Optional ein „Gruppenmodus“, der wie bei Ausfahrten Langsame leicht
  zieht. Das ist Spieldesign, kein Muss.
- **Verbindungsabbruch**: Der eigene Fahrer fährt lokal weiter (die Sitzung hängt nicht am
  Netz). Fremde Fahrer werden nach etwa 3 s ohne Daten ausgegraut und nach 30 s entfernt;
  Wiederbeitritt mit derselben Raumkennung. Die parallel entstehende „Fahrt im Hintergrund“ ist
  Voraussetzung, damit die Verbindung beim Bildschirmwechsel nicht abreißt.
- **Betrug**: Zwischen Verabredeten nicht relevant. Der Server prüft nur Grenzen (kein Sprung über
  100 m, Tempo plausibel), um Fehler abzufangen.
- **Taugt der Sync-Server?** Grundsätzlich ja, mit drei Erweiterungen:
  1. **WebSocket** in `server.mjs`: Node bringt keinen WebSocket-Server im Core mit; ohne
     Abhängigkeiten heißt das, Handshake und Rahmen von Hand zu schreiben (einige hundert Zeilen,
     gut testbar). Alternative ohne WebSocket: Server-Sent Events für den Weg zum Handy und
     `POST` für den Weg hin. Das ist einfacher, aber gröber.
  2. **Raum-Geheimnis statt Vollzugriff**: Wer eingeladen wird, darf **nicht** den `SYNC_TOKEN`
     bekommen (der öffnet den ganzen Tourenbestand). Der Raum bekommt beim Anlegen ein eigenes
     Zufallsgeheimnis, das nur für diesen Raum und seine Dauer gilt; es steht im Einladungslink.
  3. **Nichts speichern**: Räume leben im Speicher und verschwinden nach dem Ende. Kein Protokoll
     der Positionen.
  Damit kann jeder, der einen Sync-Server betreibt, Freunde einladen. Die meisten Nutzer
  betreiben aber keinen. Das ist die eigentliche Grenze von Stufe (b) ohne zentralen Dienst.
- **Zentraler Raum-Relais als Option**: derselbe Code, vom Entwickler betrieben, ohne Konto,
  ohne Speicherung, Räume nur mit Link. Datenschutzrechtlich trotzdem eine Verarbeitung beim
  Entwickler (IP-Adressen, Positionen auf der Strecke, Avatar, frei gewählter Name) und damit
  ein Bruch des Satzes „es gibt keinen Trailscape-Server“. Kosten für ein kleines Relais:
  einstellige Euro im Monat.
- **Aufwand**: Server-Teil 2–3 Wochen, App (Raum anlegen, beitreten, Fremdfahrer zeigen,
  Abbruch) 4–6 Wochen, Windschatten 1–2 Wochen, Tests mit mehreren echten Geräten und
  Mobilfunk mindestens 1–2 Wochen. Zusammen grob **2–3 Monate**.
- **Risiken**: Akku und Wärme beim Handy (Rendern plus Funk zum Trainer plus Netz), Bluetooth und
  WLAN auf demselben Funkchip bei manchen Geräten, die Uhrzeit der Verabredung. Und vor allem: Es
  braucht mindestens zwei Leute zur selben Zeit. Ohne Gemeinschaft bleibt der Raum leer.

### 5.3 (c) Offene gemeinsame Strecke mit vielen Fahrern

Jederzeit einsteigen, andere sind schon unterwegs.

- **Architektur**: zentraler Dienst. Je Katalogstrecke eine Instanz (Shard), bei vielen Fahrern
  mehrere. **Interest Management**: Jeder bekommt nur Fahrer in seiner Nähe (etwa ±500 m) mit
  2–4 Hz, entferntere seltener oder gar nicht. Darstellung: bis ~10 Fahrer voll, der Rest mit
  LOD, Gedränge als Gruppe zusammengefasst.
- **Leerer-Raum-Problem**: Eine offene Welt wirkt erst ab einer gewissen Zahl gleichzeitiger
  Fahrer lebendig. Die vorhandenen dekorativen Fahrer (`OtherRiders`) füllen das heute schon
  und könnten mit **echten Aufzeichnungen** als Geister ergänzt werden. Das ist ehrlicher als
  vorgetäuschte Live-Fahrer, muss aber klar so beschriftet sein.
- **Betrug** wird relevant (Ranglisten, Vergleich). Siehe 4.4.
- **Pflichten**: alle aus C2, dazu Anzeigenamen in der Welt (Moderation).
- **Kosten**: Server nach Last, bei kleiner Nutzerzahl einstellig bis niedrig zweistellig im
  Monat; der Aufwand für Betrieb und Moderation überwiegt.
- **Aufwand**: auf (b) aufbauend weitere 2–4 Monate (Sharding, Interest Management, Kennung,
  Namen, Missbrauch, Last-Tests). Dazu der laufende Betrieb.
- **Risiken**: Das ist der Punkt, an dem Trailscape mit großen Plattformen verglichen wird, die
  Teams für Welten, Server und Gemeinschaft haben. Ein Einzelentwickler gewinnt diesen Vergleich
  nicht über Größe, nur über andere Stärken (echte Strecken, Datenschutz, Trainingsplan).

### 5.4 Übersicht

| | (a) zeitversetzt | (b) Raum mit Link | (c) offene Strecke |
|---|---|---|---|
| Server | keiner (oder C1) | eigener Sync-Server oder Relais | zentral |
| Kennung | keine | Raum-Geheimnis | pseudonym oder Konto |
| Taktrate | — | 2–4 Hz | 2–4 Hz nah, seltener fern |
| Windschatten | einseitig, deterministisch | lokal je Gerät | lokal je Gerät |
| Betrugsrelevanz | gering | gering | hoch |
| Datenschutzfolge | keine neue (Teilen durch Nutzer) | keine bei Selbsthost; bei Relais neu | erheblich |
| Aufwand | Wochen | 2–3 Monate | + 2–4 Monate, dazu Betrieb |

---

## 6 Die Grundsatzentscheidung

### 6.1 Drei Wege

| | A: zentraler Dienst mit Konten | B: ohne Konto (Dateien, Links, selbst gehostete Räume) | C: Mischform |
|---|---|---|---|
| Datenschutzversprechen | fällt in der heutigen Form; neue Erklärung, neue Pflichten | bleibt vollständig | bleibt für alles außer dem optionalen Relais |
| Play-Formular | Erhebung und Weitergabe angeben | unverändert | unverändert, bis das Relais kommt; dann klein ergänzen |
| Betrieb | dauerhaft, mit Bereitschaft | keiner | klein, abschaltbar |
| Recht | DSGVO, DSA, Impressum, Jugendschutz, ggf. Folgenabschätzung | Teilen durch Nutzer, kaum Pflichten beim Entwickler | beim Relais: Verarbeitung ohne Speicherung, Impressum; kein Hosting von Inhalten |
| Wachstum | Netzwerkeffekte möglich | gering; jeder braucht Freunde mit derselben App, für Echtzeit einen Server | mittel |
| Abhängigkeit | Trailscape wird zum Dienst; fällt er aus, fallen Funktionen aus | keine | Relais ist ersetzbar (jeder Sync-Server kann es) |

### 6.2 Empfehlung: C, mit B als Grundsatz

1. Alles, was lokal geht, bleibt lokal: Abzeichen, Stufen, Garage, Bergwertung, eigene Geister.
2. Alles, was zwischen Menschen geht, geht **zuerst ohne Entwickler-Server**: Dateien und
   Fragment-Links (C0), Gruppen und Räume auf dem eigenen oder einem Vereins-Server (C1, 5.2).
3. Das Protokoll für Räume wird so entworfen, dass **jeder** Sync-Server es kann. Ein offizielles
   Relais ist dann dieselbe Software, vom Entwickler betrieben, **ohne Konto, ohne Speicherung**,
   opt-in je Raum. Es kommt erst, wenn es Belege für Bedarf gibt (6. Fahrplan, Etappe 6).
4. Zentrale Bestenlisten und ein soziales Netz (C2, C3, 5.3) werden **nicht** gebaut, solange
   Trailscape von einer Person betrieben wird.

**Begründung**: Der Datenschutz ist der deutlichste Unterschied zu großen Plattformen und ist im
Code durchgehalten (`PRIVACY.md`). Die vorhandene Architektur (Ableitung statt Spielstand,
eigener Server, Katalog aus Releases) passt genau zu B und C. Der Betriebsaufwand von A ist für
einen Einzelentwickler das größte Risiko, nicht der Code. Und C hält A offen: Ein Raumprotokoll,
das auf dem eigenen Server läuft, kann später auch zentral laufen; umgekehrt geht das nicht.

**Was dagegen spricht**:
- Ohne zentralen Dienst gibt es kaum **Netzwerkeffekte**. Wer niemanden mit Trailscape kennt,
  hat von Räumen nichts. Das begrenzt das Wachstum deutlich.
- **Selbsthosting** ist für die meisten Nutzer zu schwer. Stufe (b) erreicht ohne Relais nur
  Technikaffine und Vereine mit Admin.
- Link-Teilen ohne Server hat Grenzen (Länge, App Links brauchen eine Domain, Verhalten in
  Messengern ungetestet).
- Konkurrenz mit offener Welt und Ranglisten bleibt in dieser Kategorie überlegen. Das muss man
  bewusst in Kauf nehmen.
- Ein Relais ist auch ohne Speicherung eine Verarbeitung personenbezogener Daten (IP-Adresse,
  Name, Positionen) und braucht eine ehrliche Beschreibung. „Kein Trailscape-Server“ stimmt dann
  nicht mehr wörtlich; der Satz müsste lauten: „Kein Trailscape-Server **speichert** etwas von
  dir; für Gruppenfahrten gibt es ein optionales Relais, das Positionen nur weiterreicht.“

---

## 7 Fahrplan

Parallel in Arbeit (nicht Teil dieses Plans, aber Abhängigkeiten): Tour drinnen nachfahren,
Bergwertung mit lokalen Bestzeiten, bessere Streckenkarten, Fahrt im Hintergrund, 3D-Vorschau.

Erfolg wird **ohne Telemetrie** gemessen. Mögliche Signale: eigene Nutzung des Inhabers,
Rückmeldungen der Tester im Play-Internal-Testing und auf GitHub, eine freiwillige kurze Umfrage
(Link in der App, Antwort im Browser), Bewertungen und Installationszahlen in der Play Console
(Googles zusammengefasste Zahlen, keine App-Telemetrie), Abrufzähler der Release-Dateien bei
GitHub (anonyme Gesamtzahlen) für Katalog- und Saisoninhalte, und Meldungen von
Server-Betreibern.

| Etappe | Umfang | hängt ab von | grober Aufwand | Inhaber entscheidet oder liefert | gelohnt, wenn … |
|---|---|---|---|---|---|
| **1 Erfolge** | `Achievements.kt` in `:core` mit den 30 Abzeichen, Punkte und Stufen (2.3), Pausenwochen (2.2), Bildschirm „Erfolge“, Abzeichen in Highlights und auf der Uhr, Backup-Schlüssel `progress` | Bergwertung (für 25/26), Tour drinnen nachfahren (für 23) | 3–5 Wochen | Namen und Texte der Abzeichen, Symbole (einfarbige Vektorsymbole reichen), endgültige Punkteformel nach Nachrechnen | Tester nennen Abzeichen in Rückmeldungen; der Inhaber und Tester fahren Erholungswochen ohne Frust über die Serie |
| **2 Garage v1** | Generator: Zonen als Materialien mit grauen Vertexfarben, LOD-Stufe; Darstellung: `baseColorFactor` je Instanz mit Parameterprüfung; Garage mit Vorschau; Farben, 3–4 Muster, Helm, Ärmel; Freischaltungen an Abzeichen; Avatar-JSON im Backup | 3D-Vorschau, Etappe 1 (Freischaltungen) | 4–6 Wochen | Farbpaletten, Hautton-Palette, welche Muster; ob ein Grafiker beauftragt wird | Tester ändern ihren Fahrer (fragen nach weiteren Mustern oder Radtypen); keine Abstürze in der Renderdiagnose (`DiagnosticsScreen.kt`) |
| **3 Herausforderung teilen** | Format `*.trailscape-challenge`, Import, fremder Geist, Strecken-Prüfung; Teilen-Dialog mit Datenschutz-Hinweis; optional Fragment-Link mit statischer Seite | Etappe 2 (Avatar des Gegners), Tour drinnen nachfahren | 3–5 Wochen | Domain und App Links ja/nein; ob eigene Außenstrecken teilbar sein sollen | Herausforderungen tauchen in Testgruppen auf; Rückfragen nach „mehreren Gegnern“ |
| **4 Saison-Inhalte** | Saison-Herausforderungen und Sondertrikots als Datei aus einem Release, wie der Katalog | Etappe 1, Etappe 2 | 1–2 Wochen, dann je Saison wenige Tage | Inhalte und Rhythmus | GitHub-Abrufzähler der Saisondatei; Rückmeldungen |
| **5 Raum auf eigenem Server** | WebSocket (oder SSE) in `server.mjs`, Raum-Geheimnis, Einladungslink; App: Raum anlegen, beitreten, bis 10 Fahrer mit Avatar und LOD, Abbruchverhalten; danach Windschatten | Fahrt im Hintergrund, Etappe 2 (LOD, Instanzen), Etappe 3 (Link-Infrastruktur) | 2–3 Monate | Testtermine mit mehreren Geräten; ob Windschatten ins erste Release kommt | eine verabredete Gruppenfahrt mit Testern über 60 min ohne Abbruch; Server-Betreiber melden Nutzung |
| **6 Entscheidung Relais** | Nur nach Etappe 5: offizielles Relais ohne Konto und Speicherung, Ergänzung von `PRIVACY.md` und Play-Formular | Etappe 5, rechtliche Prüfung | 2–4 Wochen plus Betrieb | **Grundsatzentscheidung**, Impressum, Hoster, Budget | wiederholte Nachfragen von Nutzern ohne eigenen Server; Gruppenfahrten über das Relais in der Umfrage |
| **7 Gruppen auf eigenem Server** | Mitglieder, Gruppen-Bestenlisten auf Katalogstrecken und festgelegten Anstiegen, Gruppen-Challenge | Etappe 5 (Server-Erweiterung), Bergwertung | 4–8 Wochen | ob Vereine als Zielgruppe gewollt sind; Ansprechpartner in einem Verein für einen Test | ein Verein betreibt einen Server und nutzt die Bestenliste über eine Saison |

Bewusst nicht im Plan: zentrale Bestenlisten, offene Welt, Konten, Feed. Sie sollten erst wieder
auf den Tisch, wenn Etappe 6 läuft, die Signale eindeutig sind und es mehr als eine Person für den
Betrieb gibt.

---

## 8 Offene Punkte und Unsicherheiten

- Punkteformel und Stufenkurve sind nicht an echten Daten geprüft (2.3).
- Draw Calls und Speicher mit Zonenmaterialien und mehreren Fahrern sind nicht gemessen (3.2,
  3.4), besonders auf `LOW`.
- Die Parameterabfrage am Filament-Material ist für die verwendete Version zu bestätigen (3.3).
- Ob ältere App-Versionen unbekannte Backup-Schlüssel beim Import tolerieren, ist ungeprüft
  (2.6).
- Verhalten sehr langer Links in Messengern ist ungetestet (4.2).
- Windschatten-Kurve: grobe Literaturwerte, keine eigene Herleitung (5.2).
- Alle rechtlichen Aussagen sind Einschätzungen und vor einer zentralen Funktion fachlich zu
  prüfen (4.4).
