# Indoor – Slice indoor-core

Logik des Indoor-Trainings in `:core` (reines Kotlin, ohne Android):
Strecke, Belagsklassen, Fahrphysik, virtuelle Gänge mit Widerstandsmodell,
ERG-Einheit, Sitzung, Geist, FTMS-Trainer, Demo-Trainer und Schalttaster.
Vertrag: [`docs/indoor.md`](../indoor.md), Abschnitte 2, 3.1, 4, 5, 6, 11.

| Datei (`core/…/indoor/`) | Inhalt |
|---|---|
| `IndoorCoreEntry.kt` | die acht Einstiege; leiten nur weiter |
| `IndoorRouteBuilder.kt` | `buildIndoorRoute`, `surfaceRematchWaypoints` |
| `RouteSurface.kt`, `Routing.kt` (eine Ebene höher) | `waySectionsFromMessages`; `parseBrouterGeoJson` und `concatRouteLegs` füllen `PlannedRoute.waySections` |
| `IndoorPhysics.kt` | Bewegungsgleichung, Gleichgewichtstempo, Gegenwind |
| `VirtualGears.kt` | Gangtabellen, Startgang, Ersatzsteigung für `SetSimulation` |
| `ErgWorkouts.kt` | `ergWorkoutFor` |
| `RiderSetupFrom.kt` | `riderSetupFrom` |
| `RideFeel.kt`, `FeelCalibration.kt`, `RideProtocol.kt` | Fahrgefühl-Regler, geführter Abgleich, Fahrprotokoll — siehe [`kalibrierung.md`](kalibrierung.md) |
| `IndoorGhost.kt` | Geist (Vergleichsleistung, frühere Fahrt) |
| `IndoorSessionImpl.kt` | die Sitzung als reiner Automat |
| `FtmsCodec.kt`, `FtmsTrainer.kt` | FTMS-Bytes und Kontroll-Automat |
| `DemoTrainerImpl.kt` | simulierter Trainer |
| `ZwiftClickProtocol.kt`, `ZwiftClickShifter.kt` | Schalttaster: Parser und Handschlag |

Tests unter `core/src/test/kotlin/de/trailscape/core/indoor/`, darunter
`IndoorDemoRideTest` (Ende-zu-Ende über die öffentlichen Einstiege mit
Demo-Trainer, 10-Hz-Schleife wie in Abschnitt 5.7).

---

## Abweichungen vom Vertrag

1. **`IndoorUuids.ZWIFT_RIDE_SERVICE` ergänzt** (`0000fc82-0000-1000-8000-00805f9b34fb`).
   Click V2 und Zwift Ride melden ihren Dienst unter dieser kurzen UUID
   (BikeControl `constants.dart`, GearUp-README); die Characteristics darin
   sind dieselben (`…0002/0003/0004`). Reine Ergänzung, die bestehenden
   Zwift-UUIDs waren laut Quellen richtig und sind unverändert.
2. **`RiderSetup` um Felder am Ende ergänzt** (mit Vorgabewerten, nur für
   diesen Slice nötig): Wahl der Gangtabelle und Klemmbereich der an den
   Trainer gesendeten Ersatzsteigung (Abschnitt „Virtuelle Gänge“):
   `gearTable: VirtualGearTable = EVEN` (`EVEN` | `BIKE_GRX_2X12`),
   `minSimGradePct: Double = -40.0`, `maxSimGradePct: Double = 40.0`.
   indoor-app kann sie per `copy` setzen, muss aber nicht.
3. **Reale Übersetzung.** `RiderSetup.DEFAULT_REAL_GEAR_RATIO` bleibt
   eingefroren bei 40/14. Nach Angabe des Gründers (Shimano GRX 820, 2 × 12,
   48/31, Zwift Cog mit 14 Zähnen) setzt `riderSetupFrom` aber **48/14**; 31/14
   steht als zweite Konstante bereit. Wer etwas anderes fährt, setzt
   `realGearRatio` per `copy` (Einstellung gehört zu indoor-app).
4. **`adjustErg`** verschiebt um Prozentpunkte der FTP
   (`FTP × (Anteil + adj/100)`), wie es der KDoc des Vertrags sagt.
5. **ERG ohne (oder mit leerer) Einheit** fährt wie SIM; `state.mode` und
   `record.mode` melden dann `SIM`. Eine Fehlkonfiguration soll die Fahrt nicht
   verhindern.

## Entscheidungen

### Strecke (Abschnitt 4)

- Neu abgetastet in gleichen Schritten von höchstens 10 m. Starke Ecken
  (> 45° über je 5 m) werden als Abtastpunkte eingerastet, damit Wenden einer
  Stichstrecke nicht abgeschnitten werden und keine zwei Nachbarpunkte an
  derselben Stelle liegen.
- Höhen: Lücken linear gefüllt, ohne Höhen flach auf 0 m. Geglättet wird mit
  einem Mittel über ±25 m. Auf Runden läuft die Glättung zyklisch über die
  Start-Ziel-Naht; Start- und Zielhöhe sind danach gleich. Danach wird die
  Steigung auf −25..+25 % geklemmt, indem steile Stücke von oben abgetragen
  werden.
- Beläge aus BRouters `WayTags` über `surfaceKindFromTags`, anteilig auf die
  Linienlänge gestreckt, gleichartige Nachbarn zusammengefasst. Beim Abgleich
  (`BROUTER_REMATCH`) muss der Streckfaktor in 0,8..1,25 liegen; sonst ist der
  Router der Spur nicht gefolgt, der Belag wird verworfen (`NONE`, damit der
  Rückfall aus den Vektorkacheln greift).
- `id` = Hash über die neu abgetasteten Lagen (nicht Name, Höhe oder Belag).
- Stützpunkte für den Abgleich: höchstens 150, davon bis zu 100 im
  Regelabstand (~300 m), dann Kurvenpunkte, die schärfsten zuerst, dann
  Halbierung großer Lücken. Eine harte Grenze von BRouter für Wegpunkte ist
  nicht bekannt.

### Fahrphysik (Abschnitt 5.6)

`P·η = v·(m·g·(sin θ + Crr·cos θ) + ½ρCdA·(v+v_w)·|v+v_w|) + m_eff·v·dv/dt`,
m_eff = Gesamtmasse + 1,5 kg (Drehmasse der Laufräder), ρ aus
`airDensity(Höhe)`, Crr über `effectiveCrr`. Teilschritte von höchstens
0,05 s, gesamt höchstens 2 s je Schritt. Die Antriebskraft `P·η/v` ist auf
`4 m/s² · m_eff` begrenzt (statt das Tempo nach unten) und wird halbimplizit
integriert — so kriecht man auch mit wenig Leistung steil bergauf weiter
(100 W an 15 % ≈ 0,73 m/s), statt stehenzubleiben. v wird nie negativ.

### Virtuelle Gänge und Widerstand (Abschnitt 5.3) — Weg (a)

Gewählt ist **(a) Simulation mit Ersatzsteigung**. Begründung: Der Trainer
regelt im SIM-Modus selbst und schnell auf seine Schwungmasse; die Kraft
ändert sich innerhalb der Sekunde mit dem Tempo wie draußen. Weg (b) (ERG
nachführen) hätte die Totzeit der ERG-Regelung und kämpft gegen
Trittfrequenzwechsel.

Herleitung: Bei gleicher Trittfrequenz ist das Kurbelmoment
`T = F · R · Übersetzung`. Soll sich die echte Übersetzung `G_r` wie die
virtuelle `G_v` anfühlen, muss gelten `F_real · G_r = F_virt · G_v`, also
`F_real = k · F_virt(v_virt)` mit `k = G_v / G_r` und `v_virt = k · v_real`.
Daraus für `SetSimulation`:

- `crr_s` = Crr der Stelle (auf den uint8-Bereich 0..0,0254 geklemmt, Rest
  geht in die Steigung),
- `cw_s = cw · k³`, `wind_s = v_w / k`,
- `grade_s = 100 · (k · trainerScale · (sin θ_d + crr · cos θ_d) − crr_s)`,
  θ_d = atan(difficulty · Steigung).

`trainerScale` gleicht aus, dass die vom Trainer angenommene Masse unbekannt
ist (1,0 = Trainer rechnet mit der Masse des Fahrers). Ein Test prüft die
Drehmomentgleichheit numerisch über viele Kombinationen. Die Ersatzsteigung
wird auf einen einstellbaren Bereich geklemmt (Vorgabe −40..+40 %); der
Trainer selbst begrenzt zusätzlich (KICKR v5 laut Wahoo: −10..+20 %).

Gangtabellen (je 24 Gänge):

- **gleichmäßig** (Vorgabe): geometrisch von 0,75 bis 5,5;
- **wie am Rad**: GRX 48/31 × Kassette 11–34 (11-12-13-14-15-17-19-21-24-27-30-34),
  nach Übersetzung sortiert.

Startgang: die Übersetzung, die `realGearRatio` am nächsten liegt (bei
48/14: Gang 19 gleichmäßig, Gang 21 wie am Rad).
Geschaltet wird mit `ShifterEvent.UP/DOWN` durch die sortierte Liste.

### ERG-Einheit (`ergWorkoutFor`)

- Zielevent → `null`.
- `INTERVALS` mit Argumenten: Einfahren in steigenden Stufen, Belastungen
  (95 % bzw. 100 % FTP je nach Härte), Pausen 50 %, Ausfahren 45 %.
- Sonst nach Intensität: HART 4 × (8 min 95 % + 4 min 50 %, höchstens 6
  Wiederholungen), GRUNDLAGE 68 %, LOCKER 55 %, jeweils mit Ein- und
  Ausfahren. Dauer aus `durationMin`, sonst aus `targetKm` bei 25 km/h,
  geklemmt.

### Sitzung (Abschnitt 5.6)

- Telemetrie je Feld: der letzte Nicht-null-Wert gilt 3 s (FTMS-Trainer
  verteilen Indoor Bike Data auf mehrere Pakete).
- Auto-Pause: keine Leistung und < 1 km/h für 3 s, oder Trainer > 5 s nicht
  bereit. Geweckt wird mit Leistung.
- Befehle: beim ersten Schritt `Start` und der erste Modusbefehl; SIM
  höchstens einmal je Sekunde bei merklicher Änderung, sofort nach dem
  Schalten; ERG am Abschnittswechsel und nach `adjustErg`; `Pause` bei
  `pause()` und am Ziel einer offenen Strecke; `Stop` genau einmal im ersten
  Schritt nach `finish()`.
- Erster Tritt und Aufwecken nur mit Leistung > 0: Kadenz allein bewegt die
  Fahrt nicht und ließe die Auto-Pause flattern (je Wechsel 3 s Scheinfahrzeit).
  `resume()` kehrt bis zum ersten Tritt nach `READY` bzw. `AUTO_PAUSED` zurück.
- `elapsedS` ist die echte Zeit seit dem ersten Tritt (Pausen und Hänger
  eingeschlossen); auf 2 s je Schritt gekappt wird nur die gefahrene Zeit.
- Aufzeichnung: Startpunkt beim ersten Tritt (ohne Leistung), dann ein Punkt
  je gefahrener Sekunde (virtuelle Lage, Wanduhrzeit, Sekundenmittel der
  Leistung, Kadenz, Puls), am Ziel und bei `finish()` ein Schlusspunkt für die
  angefangene Sekunde. Distanz, Höhenmeter (auf und ab), Fahrzeit und Schnitt
  der `stats` kommen aus den Summen der Sitzung, damit der Sprung vom Ziel zum
  Start einer Runde nicht mitzählt; Dauer und Puls aus den Punkten.
- Runden: `lap` und `laps` = angefangene Runden (`ceil(distM / Länge)`).
- SIM-Sparsamkeit: neue Vorgabe ab 0,2 Prozentpunkten Steigung, 0,15 m/s Wind
  oder einer Kodierungseinheit bei Crr/cw.
- Geist „frühere Fahrt“: Punkte monoton auf die Strecke projiziert, im Fenster
  `25 m/s · Δt + 50 m` ab der letzten Stelle (gegen Sprünge auf den
  Nachbarast einer Serpentine); Rückfall: anteilig gestreckt. Gespeicherte
  Pausen herausgenommen, auf (0 s, 0 m) verankert, und endet die Fahrt bis
  max(50 m, 2 %) vor dem Ziel, aufs Ziel gestreckt (sonst käme der Geist nie an).
- Endabstand zum Geist: beide im Ziel → Differenz der im Schritt
  interpolierten Ankunftszeiten; Geist vorn mit bekannter Kurve → Zeitdifferenz
  an der Stelle des Fahrers; sonst Abstand durch das Tempo des Hinteren bzw.,
  steht der vor `finish()`, durch dessen Durchschnittstempo.

- Startpunkt (24.11.2026, `IndoorStartPoint.kt`, Einzelheiten in
  `docs/indoor/app.md` „Startpunkt und Fortsetzen“): Die Sitzung erfüllt
  zusätzlich `MovableIndoorSession` — `moveTo` in READY und PAUSED, innerhalb
  der laufenden Runde. Summen und `stats` zählen nur gefahrene Meter
  (`riddenM`); der Geist wird an der neuen Stelle neu angesetzt (frühere Fahrt
  zeitlich verschoben, `PastRideGhost.startingAt`). Die Vertragsdateien sind
  unverändert.
- Außenfahrt als Gegner (25.11.2026, `IndoorGhostSection.kt`, Einzelheiten in
  `docs/indoor/app.md` „Drinnen nachfahren“): Die Zeit-Weg-Kurve trägt, wenn
  mindestens die Hälfte der Punkte `power` hat, die aufsummierte Arbeit
  (`GhostCurve.workJ`, Lücken über 30 s zählen nicht). Daraus die Leistung
  des Geists jetzt (`GhostState.powerW`, gleitend über 10 s; konstanter Geist:
  seine Leistung) und am Ende der Abschnittsvergleich
  (`IndoorRideRecord.ghostSection`: Zeit und Ø Leistung beider ab dem letzten
  Ansetzen bis zur Stelle des Fahrers; die frühere Fahrt kennt ihre Zeit auch,
  wenn sie hinten lag). `ghostResultOf` liefert „vor/hinter/gleichauf“ mit
  Vorrang dieser Zeitdifferenz vor dem Endabstand. Vertragsdateien nur
  ergänzt (neue Felder mit Vorgabewert am Ende).

### FTMS-Trainer (Abschnitt 5.2)

- Einrichtung nach jedem `READY` des Rohrs: Feature und Power Range lesen,
  Indoor Bike Data und Machine Status abonnieren, Control Point mit Indication,
  dann Request Control. Befehle vor `READY` und nach Neuverbinden: je Platz
  (Zustand / Modus) nur der neueste.
- Ein Befehl zur Zeit, Frist 3 s, zwei Wiederholungen. Bestätigt ein Trainer
  über drei Befehle in Folge nichts, wird das gemeldet, auch wenn ständig
  neuere Befehle nachkommen.
- `Stop` = `08 01` + `01` Reset. Der Reset gibt die Kontrolle ab (FTMS 1.0
  §4.16); danach wird sie nur für neue Befehle wieder angefordert.
- Kadenz in Indoor Bike Data: vorhanden bei gesetztem Bit 2 (GSS v9, Erratum
  23224 zur Tabelle 4.10 in FTMS 1.0).

### Schalttaster (Abschnitt 5.4)

- Quellen: BikeControl (OpenBikeControl/bikecontrol, früher SwiftControl),
  ajchellew/zwiftplay, Makinolo-Blog (Zwift Play 2023, Zwift Ride 2024),
  jat255 und thezenox/click2keyboard, Torqa PR #267, GearUp, qdomyos-zwift PR
  #4743.
- Klartext-Handschlag: Async (notify) und SyncTX (indicate) abonnieren, dann
  „RideOn“ (`52 69 64 65 4f 6e`) auf SyncRX; bei Dienst `fc82` (V2)
  `RideOn 02 03`. Erwartet wird ein Echo mit dem Präfix „RideOn“. Eine Antwort
  mit Schlüssel (verschlüsselte Firmware) oder ab `0xFF` (gesperrter V2-Pod)
  führt zu `UNSUPPORTED`. Verschlüsselung (ECDH P-256 + AES-CCM) ist bewusst
  nicht umgesetzt: Die Knöpfe auf dem Bildschirm sind der Rückfall.
- Click V1: Rahmen `0x37`, Protobuf Feld 1 = Plus, Feld 2 = Minus,
  0 = gedrückt. V2/Ride: `0x23`, Feld 1 = Tastenmaske, invertiert; Schaltbits
  nach BikeControl. Batterie `0x19`, Feld 2.
- Gehaltene Taste: erste Wiederholung nach 500 ms, dann höchstens alle
  250 ms. Bleiben 2 s lang Tastenrahmen aus, gilt sie als losgelassen.

## Annahmen

- Die **Testbytes des Click** sind aus der dokumentierten Protobuf-Semantik
  abgeleitet; einen wörtlichen Mitschnitt gibt es in den Quellen nicht.
- Ein **fehlendes Tastenfeld** im Click-Rahmen gilt als losgelassen
  (click2keyboard). Ein proto3-Kodierer würde „0 = gedrückt“ weglassen; solche
  Rahmen landen deshalb in der Diagnose.
- Die **Schaltbits von V2** sind unsicher (BikeControl und JustFabi weichen
  ab).
- Die **Masse, mit der ein Trainer im SIM-Modus rechnet**, ist unbekannt;
  FTMS überträgt keine. Daher `trainerScale`.
- Der Trainer **rechnet die Steigung linear** und meldet das Schwungradtempo
  der realen Übersetzung — Voraussetzung der Herleitung oben.
- **Crr-Tabelle** wie im Vertrag (Abschnitt 6), unverändert.
- Ein Trainer verlangt evtl. `Start` vor Simulationsparametern; deshalb geht
  `Start` immer zuerst.
- Das Rohr arbeitet eingereihte Writes ab, bevor `close()` trennt (sonst kommt
  der letzte Stopp bei `IndoorTrainer.stop()` nicht an).

## Auf dem Gerät zu prüfen

- [ ] KICKR CORE: Einrichtung bis READY; antwortet er auf Request Control,
      Start, SetSimulation, SetTargetPower, Stop/Reset jeweils binnen 3 s?
- [ ] Ist `Start` vor `SetSimulation` nötig? Sendet der KICKR Tempo, Kadenz und
      Leistung in einem Paket oder verteilt?
- [ ] Kontrollverlust: Hersteller-App parallel öffnen → Machine Status `0xFF`
      bzw. Antwort `05`, Verhalten danach; Neuverbinden mit Nachschicken.
- [ ] Virtuelle Gänge: Fühlt sich Schalten bei gleicher Trittfrequenz richtig
      an? `trainerScale` kalibrieren (z. B. bekannte Steigung mit Leistung und
      Kadenz vergleichen). Verzögerung zwischen Schalten und Widerstand.
- [ ] Gangtabelle „wie am Rad“ gegen die gleichmäßige: Welche fühlt sich
      besser an? Stimmt der Startgang mit 48/14?
- [ ] Große Gänge bergauf: Wie stark klemmt der Trainer die Ersatzsteigung
      (KICKR +20 %)? Wirken schwere Gänge bergab noch stimmig?
- [ ] Belag spürbar: Asphalt → Schotter → Waldboden; passt das Tempo zum
      Gefühl?
- [ ] Gibt der Trainer am Ziel nach `Pause` den Widerstand frei?
- [ ] Auto-Pause-Schwellen (3 s, 5 s) im echten Betrieb.
- [ ] Zwift Click V1: Wird er gefunden, kommt das Echo auf SyncTX, wie lang?
      Stimmen die `0x37`-Rahmen und die Zuordnung Plus/Minus? Wiederholt das
      Gerät den Zustand beim Halten in Abständen unter 2 s? Kommt `0x15` auch
      bei gehaltener Taste? Batterie plausibel?
- [ ] Click mit alter, verschlüsselter Firmware → `UNSUPPORTED`?
- [ ] Click V2: Dienst `fc82`, Schaltbits, gesperrter linker Pod (`0xFF`),
      Trennung etwa 61 s nach dem letzten Druck.
- [ ] Gehaltene Taste: 500 ms bis zur ersten, dann etwa 250–300 ms je weitere
      Wiederholung — fühlt sich das richtig an?
- [ ] Trainer + Schalttaster + Pulsgurt gleichzeitig an einem Handy.

## Bitten an andere Slices

- **indoor-ble:** Scan-Filter für den Schalttaster: `ZWIFT_SERVICE` **oder**
  `ZWIFT_RIDE_SERVICE` (fc82) **oder** Herstellerdaten mit `ZWIFT_COMPANY_ID`
  (gesperrte V2-Geräte verbergen den Dienst). Erstes Byte der Herstellerdaten
  = Gerätetyp: Click V1 `0x09`, Click V2 `0x0A/0x0B` (unbestätigt), Play
  `0x02/0x03`, Ride `0x07/0x08`. `BleLink.close()` soll eingereihte Writes
  noch abarbeiten (letzter Stopp). `READY` liefert alle Dienst-UUIDs klein
  geschrieben. MTU-Anfrage ist nicht nötig.
- **indoor-app:** Laufzeit reicht die **letzte** Telemetrie durch; die Sitzung
  hält je Feld den letzten Wert 3 s. Die reale Übersetzung (Vorgabe 48/14),
  Gangtabelle und Steigungsgrenze gehören in die Einstellungen
  (`RiderSetup.copy`). Ein `UNSUPPORTED`-Schalttaster bleibt für seine Instanz
  endgültig — für einen neuen Versuch eine neue Instanz bauen. Der Abgleich
  über `surfaceRematchWaypoints` kann bei Spuren mit sehr großen Sprüngen über
  `planRouteLegs` viele Einzelanfragen erzeugen.
- **indoor-world / indoor-app:** `IndoorRoute.id` hängt nur an der Lage; wer
  Welten unter der id ablegt, nimmt Quelle/Belag getrennt in den Schlüssel.
