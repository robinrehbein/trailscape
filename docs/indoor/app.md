# Indoor — Notizen des Slices indoor-app

Ablauf, Laufzeit, Speicherung und Doku des Features Indoor (Vertrag:
`docs/indoor.md`, besonders Abschnitte 2, 3.5, 5.7, 8, 10, 11). Die Fakten zu
Einzelheiten der Oberfläche trägt die Integration nach; was hier steht, gilt
als Soll-Zustand des Prototyps.

## Abweichungen vom Vertrag

- **Strava (10.3):** Der vorhandene Upload kann Indoor-Fahrten über den
  Parameter `trainer=1` als „auf dem Rollentrainer gefahren“ markieren
  (`core/…/StravaClient.kt`). Eine virtuelle Strecke (`VirtualRide`) wird nicht
  gesetzt; die Sportart bleibt Radfahrt mit Trainer-Kennzeichen. Ob Strava das
  im Konto als virtuelle Fahrt zeigt, steht auf der Prüfliste.
- **Rohabruf der Geodaten:** wie in 8.3 vorgesehen ein eigener OkHttp-Adapter
  statt des `HttpClient`-Interface (Kacheln sind Bytes, das Interface liefert
  Text). Keine Weiterleitungen, User-Agent `Trailscape/<Version>`.
- **Nach Prozesstod beginnt der Ablauf neu bei „Koppeln“.** Es wird weder die
  laufende Fahrt noch die Auswahl wiederhergestellt (nur der Demo-Schalter,
  das Kettenblatt und die Gangtabelle bleiben in den SharedPreferences). Der Vertrag verlangt mehr nicht.
- **Zurück während der Fahrt** verlässt den Ablauf nie: Die Fahrt hält an, ein
  Dialog bietet „Weiterfahren“ oder „Fahrt beenden“ (dann Auswertung, dort
  speichern oder verwerfen). In der Auswertung fragt Zurück bei einer
  ungespeicherten Fahrt, ob sie verworfen werden soll.
- **Einstieg beim Aufzeichnen-Knopf:** ein leiser Textknopf „Indoor auf dem
  Rollentrainer fahren“ in beiden Fassungen des Bereit-Dialogs (freie Fahrt und
  geplante Route).
- **Übersetzung (11.2):** `RiderSetup.DEFAULT_REAL_GEAR_RATIO` (40/14) bleibt
  unverändert; der Slice setzt `realGearRatio` beim Start der Fahrt aus dem
  Kettenblatt (Vorgabe 48 → 48/14) und lässt den Kern unberührt.
- **Mehr:** eigene Gruppe „Indoor“ mit „Indoor-Welten“ und „Indoor-Diagnose“;
  beide sind Ziele im `NavHost`, keine Seiten des Mehr-Bereichs.
- Die Weltverwaltung bietet verwaiste Welten einzeln zum Löschen an; ein
  Sammel-Löschen (`IndoorWorldsViewModel.deleteOrphaned`) ist vorhanden, aber
  nicht in der Oberfläche.

## Annahmen

- Der Trainer meldet Telemetrie mit höchstens wenigen Hz; die 10-Hz-Schleife
  hält jeweils den letzten Wert vor.
- Das Verlassen des Vordergrunds (`ON_STOP`) pausiert die Fahrt. Hat erst der
  Hintergrund sie angehalten, läuft sie beim Zurückkehren (`ON_START`) von
  selbst weiter; eine Pause des Nutzers bleibt bestehen.
- Ein Ausfall eines fremden Teils (Kern, Weltbau, Funk, Darstellung) ist ein
  erwarteter Fall: Er erscheint als verständliche Meldung (`IndoorNotice`), die
  Fahrt läuft mit HUD und Höhenprofil weiter. Seit der Integration sind alle
  vier Einstiege echt; der Fall bleibt für Programm- und Treiberfehler.
- Der Belagsabgleich über den vorhandenen Router darf scheitern; dann gilt die
  grobe Schätzung aus den Vektorkacheln.
- Die Testwelt (`WorldBuilder.buildTestWorld`) braucht kein Netz.

## Entscheidungen

**Ablauf und Technik**

- Navigation: eigene Route `indoor` im `NavHost` ohne Navigationsleiste, dazu
  `indoor/welten` (Weltverwaltung) und `indoor/diagnose`
  (`IndoorDiagnosticsScreen`). Einstieg: Karte auf *Heute* (mit der Einheit
  des Tages, falls ERG-tauglich) und ein Eintrag im Dialog vor dem Aufzeichnen;
  Weltverwaltung und Diagnose zusätzlich unter *Mehr*.
- `IndoorViewModel` hängt am Backstack-Eintrag `indoor` und überlebt eine
  Drehung. Der Demo-Schalter und das Kettenblatt am Trainer werden in den
  SharedPreferences gemerkt.
- Kettenblatt am Trainer: Zeile im Schritt *Koppeln* (Stepper, ein Zahn je
  Schritt, 28 bis 60, Vorgabe 48 laut Gründer, also die Übersetzung 48/14; Schlüssel
  `trailscape.indoor.chainring_teeth`). Ritzel fest der Zwift Cog mit 14 Zähnen
  (`IndoorViewModel.ZWIFT_COG_TEETH`). Beim Start der Fahrt bekommt die
  Sitzung `IndoorParts.riderSetup(...).copy(realGearRatio = Kettenblatt / 14)`
  — auch auf dem Ersatzweg, wenn der Kern `riderSetup` nicht liefert.
  `RiderSetup` selbst bleibt unverändert (Vertrag).
- Gangtabelle (seit der Integration): Karte „Virtuelle Gänge“ im Schritt
  *Koppeln* mit „Gleichmäßig (24 Gänge)“ (`VirtualGearTable.EVEN`, Vorgabe)
  und „Wie am Rad (2 × 12)“ (`BIKE_GRX_2X12`); Schlüssel
  `trailscape.indoor.gear_table` (Enum-Name, Unbekanntes → Vorgabe). Die
  Sitzung bekommt `riderSetup(...).copy(realGearRatio = …, gearTable = …)`.
  Die Grenzen der Ersatzsteigung (`minSimGradePct`/`maxSimGradePct`) bleiben
  bei der Vorgabe des Kerns (−40..+40 %); der Trainer klemmt ohnehin selbst
  (KICKR −10..+20 %). Eine Einstellung dafür lohnt erst nach der Kalibrierung
  auf dem Gerät.
- Fähigkeiten des Trainers (seit der Integration): Die Sitzung startet mit der
  Annahme `TrainerCapabilities(simulation = true, targetPower = true)`
  (`IndoorViewModel.ASSUMED_CAPABILITIES`), weil `IndoorSessionConfig` sie beim
  Anlegen verlangt und der Trainer sie erst mit `READY` meldet. Meldet er
  andere, solange die Sitzung noch in `READY` auf den ersten Tritt wartet,
  tauscht die Laufzeit die Sitzung **einmal** gegen eine neue mit den
  gemeldeten Fähigkeiten (`IndoorRideRuntime.rebuildSession`); Schalten und
  ERG-Verschiebungen bis dahin werden übertragen. Kommen sie erst nach dem
  ersten Tritt (z. B. weil ein FTMS-Trainer schon vor „Request Control“
  Leistung meldet) oder nach einem Neuverbinden mitten in der Fahrt, bleibt es
  bei der Annahme: Eine laufende Sitzung lässt sich ohne Vertragsänderung
  (`IndoorSession` hat keinen Setter) nicht umstellen. Wirkung heute nur die
  Klemmung der ERG-Zielleistung auf `min/maxPowerW`.
- Rohre: Jedes `openLink`-Rohr gehört dem Trainer bzw. Schalttaster, der es
  bekommt; dessen `stop()` schließt es (die Laufzeit ruft `stop()` beim
  Beenden und Verwerfen der Fahrt, `onCleared` verwirft). Scheitert der Start,
  bevor ein Trainer/Taster das Rohr übernommen oder gestartet hat, schließt
  `startRide` es selbst. `IndoorDevices.forget()` schließt kein Rohr — muss es
  auch nicht: Während einer Fahrt ist der Abschnitt zum Koppeln nicht
  erreichbar, und die Fahrt gibt ihr Rohr am Ende selbst frei.
- Schalttaster: je Fahrt eine neue Instanz. Meldet er `UNSUPPORTED`, bleibt er
  es für diese Fahrt (er schließt sein Rohr dann selbst); der nächste Versuch
  ist die nächste Fahrt.
- Laufzeit (`IndoorRideRuntime`): 10-Hz-Schleife auf einem eigenen seriellen
  Dispatcher, Uhr injizierbar (Vorgabe `SystemClock.elapsedRealtime`).
  Telemetrie und Schaltereignisse vom BLE-Thread werden nur abgelegt. Jeder
  Aufruf fremder Teile steht in `try/catch` und meldet `PART_FAILED`; die
  Schleife läuft weiter. Puls über `BleSensors` mit `BleNutzer.INDOOR`.
- Die App im Hintergrund pausiert die Fahrt; es gibt keinen Vordergrunddienst.
  Der Bildschirm bleibt während der Fahrt an.
- Demo-Trainer: Regler Leistung (Vorgabe 180 W, 0 bis 600) und Trittfrequenz
  (Vorgabe 85, 0 bis 130) im HUD.
- Speichern: `IndoorRideRecord` wird zu `Ride(indoor = true, planned = false)`.
  Name aus einer Ressource („Indoor · <Strecke>“, englisch „Indoor · <route>“).
  Fahrten unter 60 Sekunden werden nicht gespeichert.
- Welt: Standardqualität `MEDIUM`. Der Voranschlag zeigt Hosts, Datenmenge und
  Platzbedarf; Netzverkehr gibt es erst nach „Welt laden“. Der Bau läuft auf
  `Dispatchers.IO` mit Fortschritt und Abbruch; „Ohne Welt fahren“ geht immer.
- Verzeichnisse: Welten unter `<filesDir>/indoor/worlds/<worldId>/`, Geo-Cache
  unter `<cacheDir>/indoor/geo/` (die Hülle legt ihn an; `createGeoPipeline`
  bekommt `context.cacheDir`).
- Weltaufträge (Aufbereiten, Bau): jeder Auftrag hat ein eigenes Token und
  sammelt seine Ergebnisse darin; übernommen werden sie erst auf dem
  Hauptthread und nur, solange er der aktuelle ist. Ein neuer Auftrag wartet
  auf das Ende des abgelösten (`join`), damit nie zwei Bauten gleichzeitig
  laufen — ein blockierender Bau endet erst bei der nächsten Abfrage von
  `isCancelled()`.
- Fehler fremder Teile während der Fahrt: Liefert die Sitzung nie einen
  Zustand (wirft bei jedem Takt), zeigt die Startseite den Fehler, und
  „Zurück“ gibt Laufzeit, Trainer und Sensoren frei und führt zu „Welt laden“.
  Fehlt die Aufzeichnung beim Beenden, zeigt die Auswertung „Aufzeichnung nicht
  verfügbar“ mit dem Grund statt „zu kurz“.
- Fehlertexte: ein ganzer Satz je Teil (`indoor_app_problem_failed_*`), keine
  zusammengesetzten Bruchstücke. Ist ein Teil nur noch nicht fertig, gibt es
  keinen Streckentipp, sondern den Hinweis, dass sich das Indoor-Training in
  dieser Version noch nicht vollständig nutzen lässt. `RouteUnavailable` hat
  nie eine Strecke, daher dort kein „Ohne Welt fahren“.
- HUD (neu gestaltet 23.11.2026, Bilder 97, 98, 112–115): Die 3D-Welt füllt
  den ganzen Bildschirm, auch unter Status- und Navigationsleiste. Der
  `NavHost` löst die System-Insets dafür nicht mehr für alle Ziele auf,
  sondern jedes Ziel selbst (`InsetPadded` in `TrailscapeApp.kt`); der
  Indoor-Ablauf tut es in `IndoorPage` bzw. im HUD. Während der Fahrt sind
  die Symbole der Systemleisten hell (`LightSystemBarIcons`).
  - Oben ein dunkler, nach unten auslaufender Schleier mit weißer Schrift
    (mit Schatten, Ziffern gleich breit): Leistung groß, daneben Tempo,
    Trittfrequenz, Puls kompakt; die Zahlen passen sich dem Platz an
    (`TextAutoSize`), so bricht auch Schrift 130 % nichts (Bild 113).
    Darunter klein die Steigung mit Pfeil, gefärbt nach Vorzeichen und
    Stärke (flach weiß, bergab blau, bergauf grün < 4 %, gelb < 7 %,
    orange < 10 %, sonst rot), und der Belag; der Abstand zum Geist als Chip
    mit blauem Punkt (wie im Profil).
  - Unten auf einem zweiten Schleier der Gang als Pille zwischen zwei
    runden −/+-Tasten mit 64 dp am Rand (Ersatz für die Schalthebel), im
    ERG-Modus stattdessen Zielleistung, Abschnitt und Restzeit (die Tasten
    verschieben das Ziel um 5 % FTP); darunter das 56 dp hohe Höhenprofil,
    gefahrener Teil grün abgesetzt, mit „x von y km“ und der Fahrzeit.
  - Geräte, Kamera, Beenden, Pause als runde Symbolknöpfe (52 dp) in einer
    Spalte rechts über den Schalt-Tasten, quer als Reihe oben rechts.
    Beenden: Finger 1,1 s halten, ein Ring läuft um den Knopf; ein kurzer
    Tipp zeigt „Zum Beenden gedrückt halten“. Der Geräteknopf ist orange mit
    Warnsymbol, wenn der Trainer nicht bereit ist oder der Schalttaster ein
    Problem meldet; ein Tipp öffnet Trainer- und Tasterstatus samt
    Quellenangaben der Welt (vorher eigene Statuszeile und „ⓘ“).
  - Ein Tipp auf die Welt blendet Geist, Profil, Knöpfe und Demo-Regler
    aus und wieder ein (Bild 112); Kernwerte, Steigung, Schalten und
    Hinweise bleiben. Für TalkBack ist die Fläche ein Knopf
    „Nebenanzeigen aus-/einblenden“.
  - Pause, Auto-Pause, „Bereit“, „Ziel erreicht“ als Karte oben in der
    Weltmitte, bei harter Pause mit „Weiter“ (Bild 114); der Pause-Knopf
    zeigt „Weiter“ ebenfalls nur bei harter Pause.
  - Ohne Welt oder bei Ausfall der Darstellung liegt ein dunkler Grund
    hinter demselben HUD; das große Höhenprofil füllt den Platz, die Knöpfe
    stehen rechts daneben. Wird es eng (kleines Gerät, Hinweis,
    Demo-Regler), entfällt die Überschrift und das Profil schrumpft, bleibt
    aber sichtbar (Bild 108).
- Testwelt als Streckenoption: `WorldBuilder.buildTestWorld` schreibt nach
  `<filesDir>/indoor/worlds/testwelt/`, sofern dort noch nichts liegt; die
  Strecke ist `manifest.track.toIndoorRoute(...)`.

**Vorgaben für die Gründer-Entscheidungen (11.2) im Prototyp**

1. **OpenFreeMap:** für den Prototyp in Ordnung; vor dem Release klären
   (siehe `docs/TODO.md`).
2. **Luftbild:** nur Länder aus dem Register (`aerialSources`); sonst erzeugte
   Bodentextur.
3. **APK-Größe:** alle ABIs hinnehmen, keine Beschränkung im Prototyp.
4. **Hintergrund:** keiner; das Verlassen der App pausiert die Fahrt.
5. **Übersetzung:** einstellbares Kettenblatt (Vorgabe 48 Zähne statt der
   40 aus `RiderSetup.DEFAULT_REAL_GEAR_RATIO`) auf dem 14er-Zwift-Cog.
6. **Crr-Tabelle:** fest, nicht einstellbar.
7. **Strava:** als Indoor-Fahrt (`trainer`) hochladen, sofern der Upload es
   kann; sonst TODO.
8. **Qualität und Daten:** `MEDIUM`, keine eigene Mobilfunk-Warnung; der
   Voranschlag zeigt die Datenmenge.

## Auf dem Gerät zu prüfen

- [ ] Demo-Durchlauf von *Heute* über Koppeln (Demo-Trainer), Strecke, Fahren
      und Auswertung bis zur gespeicherten Indoor-Tour
- [ ] Ablauf mit echtem Trainer (KICKR CORE): Koppeln, Fahren in SIM und ERG,
      Beenden, Speichern
- [ ] Zwift Click und Pulsgurt gleichzeitig mit dem Trainer
- [ ] Drehung während Koppeln, Welt laden, Fahren und Auswertung: Zustand und
      Fahrt bleiben erhalten
- [ ] App in den Hintergrund und zurück: die Fahrt pausiert und setzt auf
      Wunsch fort; der Trainer fällt nicht dauerhaft aus
- [ ] Bildschirm bleibt während der Fahrt an, danach nicht mehr
- [ ] Fehleranzeigen: Trainer weg, Welt fehlt oder defekt, Darstellung
      meldet `onFailure`, kein Netz beim Laden; jeweils verständliche Meldung,
      HUD und Höhenprofil laufen weiter
- [ ] Weltbau: Datenmenge und Dauer einer 50-km-Runde über WLAN und
      Mobilfunk, Abbruch, Fortsetzen, danach offline fahren
- [ ] Voranschlag stimmt mit der tatsächlichen Datenmenge grob überein; vor
      „Welt laden“ fließt kein Netzverkehr
- [ ] Weltverwaltung: Größen, Quellenangaben, Löschen, verwaiste Welten,
      „Zwischenspeicher leeren“
- [ ] Quellenangaben sind während der Fahrt erreichbar und unter *Mehr → Über*
- [ ] Speichern: Indoor-Tour erscheint in Liste und Detail mit Kennzeichen;
      Fahrt unter 60 Sekunden wird nicht gespeichert
- [ ] Exportieren als GPX/FIT mit Leistung und Trittfrequenz; Strava-Upload
      mit `trainer`-Kennzeichen (zeigt Strava sie als Trainer-Fahrt?)
- [ ] Indoor-Fahrt zählt für Belastung, Wochenziel und Serie, aber nicht für
      Neue Wege, Entdeckt-Kacheln, Rundenvergleich und Segmente
- [ ] Gleichzeitig von der Uhr aufgezeichnete Einheit wird beim Health-Import
      nicht zur zweiten Tour
- [ ] Prozesstod (App im Hintergrund beenden): der Ablauf beginnt wieder bei
      „Koppeln“, nichts hängt, Demo-Schalter und Kettenblatt sind gemerkt
- [ ] Kettenblatt 48 (Übersetzung 48/14, bzw. geänderter Wert): virtuelle Gänge fühlen sich auf
      dem KICKR mit Zwift Cog wie die echten Gänge an
- [ ] Diagnoseseite aus dem Ablauf und aus *Mehr* erreichbar
- [ ] Englisch: der gesamte Ablauf mit Systemsprache Englisch
- [ ] Kleines Handy bzw. große Schrift mit Demo-Reglern und Hinweis: Höhenprofil
      bleibt sichtbar, nichts ist abgeschnitten
- [ ] Fahransicht randlos: Welt unter Status- und Navigationsleiste, Symbole
      der Leisten hell und lesbar, Kamera-Ausschnitt (Notch) und Gestenleiste
      verdecken keine Werte und keine Taste; nach der Fahrt sind die Symbole
      wieder wie vorher, die übrigen Seiten (Tabs, Mehr, Indoor-Schritte)
      sitzen unverändert unter der Statusleiste
- [ ] HUD über der echten 3D-Welt bei hellem Himmel und Mittagslicht gut
      lesbar; Tipp auf die Welt blendet Nebensachen aus und ein, ohne die
      Kamera der Darstellung zu stören
- [ ] Runder Beenden-Knopf: 1,1 s halten beendet, kurzer Tipp zeigt den
      Hinweis; −/+ mit dem Daumen ohne Umgreifen erreichbar
- [ ] Welt laden, Zurück, andere Strecke, Welt laden: es läuft nur ein Bau,
      gefahren wird die zuletzt gewählte Strecke

## Bilder

Alle unter `docs/indoor/bilder/app/` (JPEG, Breite 540, quer 900), erzeugt mit
`./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*IndoorScreenshotTest*'`
aus `IndoorScreenshotTest`. Die Geräteliste des Slices indoor-ble ist darin ein
blauer Platzhalter; statt der 3D-Fläche liegt eine gezeichnete Landschaft
(heller Himmel bis zum Horizont, Wiese, Straße) unter dem HUD, damit sich die
Lesbarkeit beurteilen lässt. Echte Darstellung und Geräteliste zeigen erst die
Bilder aus dem Gerätetest.

90 Karte auf *Heute*, 91 Koppeln (Kettenblatt 48, Gangtabelle), 92 Strecke (freie Fahrt,
Frühere Fahrt), 93 Strecke (Einheit des Tages, feste Leistung), 94 Voranschlag
der Welt, 95 Bau mit Fortschritt, 96 Fehler beim Bau, 97 Fahren mit
Mitfahrer, 98 Fahren im ERG-Modus, 99 Fahren ohne Darstellung, 100 Fahren
ohne Welt, 101 Hinweis bei Trainerverlust (Auto-Pause), 102 Querformat,
103 Auswertung gespeichert, 104 Auswertung unter 60 s,
105 Weltverwaltung, 106 und 107 Englisch, 108 kleines Gerät (360 × 640 dp) mit
Demo-Reglern, 109 Koppeln mit Demo-Trainer, 112 Nebenanzeigen ausgeblendet,
113 Schrift 130 %, 114 harte Pause, 115 Querformat ERG mit Schrift 130 %,
118 Startpunkt mit „Weiter bei km 7,4“ vor dem Losfahren, 119 Startpunkt in der
Pause ohne Welt, 120 und 122 dasselbe quer (vor dem Losfahren bzw. in der
Pause), 121 englisch, 123 Startpunkt ohne gemerkte Stelle (km 0, echter
Trainer), 124 Auswertung „ab km 7,4“. Nur in
`app/build/outputs/roborazzi/` (nicht unter `docs/`): 110 und 111 Koppeln mit
dem echten Abschnitt von indoor-ble (ohne Berechtigung bzw. gekoppelt mit
laufender Suche), als hohes Bild der ganzen Seite.

Integration: Im Schritt *Koppeln* stand zuerst unter dem Abschnitt von
indoor-ble eine zweite Liste der gemerkten Geräte samt Hinweis „Schalttaster
optional“ — doppelt zum Abschnitt selbst. Sie ist entfernt; der Abschnitt
steht jetzt in einer Karte wie die übrigen.

## Bitte an andere Slices

- **indoor-core:** ~~`IndoorParts`-Einstiege liefern; Gangtabelle anbieten~~ —
  erledigt mit dem Merge (Einstiege echt, `RiderSetup.gearTable` angebunden).
  Offen, falls gewünscht: `IndoorSession` könnte die Fähigkeiten des Trainers
  auch nach dem ersten Tritt übernehmen (heute nur über eine neue Sitzung vor
  dem Start, siehe Entscheidungen).
- **indoor-world:** ~~Weltbau, `isCancelled()` zügig abfragen, Weltverzeichnis
  und Manifest~~ — erledigt mit dem Merge (Abfrage alle 50 ms; eine laufende
  Kachel wird noch fertig, deshalb zeigt der Ablauf „Wird abgebrochen …“).
- **indoor-ble:** die Geräteliste im Schritt *Koppeln* (`devicesSection`) und die
  Diagnoseseite; Trainer- und Schalttaster-Namen für die Statuszeile.
- **indoor-render:** ~~`onFailure` bei jedem Ausfall; Flächen-Slot mit Kamera~~ —
  erledigt mit dem Merge; ein Ausfall unter Robolectric (`NO_GRAPHICS`) ist im
  Demo-Durchlauf getestet.

## Integration mit Weltbau und Darstellung (23.11.2026)

- `DefaultIndoorParts` ruft die echten Einstiege: `createWorldBuilder`,
  `createGeoPipeline`, `createWorldRenderer`, `WorldSurface`,
  `IndoorDiagnosticsScreen`; in keiner der vier Einstiegsdateien steht noch ein
  Platzhalter.
- `IndoorViewModelDemoFlowTest.demoDurchlaufMitEchterTestweltUndEchterDarstellung`:
  echter Weltbau (Testwelt ohne Netz, GLB-Kacheln aller drei Stufen unter
  `indoor/worlds/testwelt/`), die Sitzung fährt auf `worldRouteOf(manifest)`,
  die echte Filament-Darstellung bekommt eine `SurfaceView`, meldet ohne
  native Bibliothek `NO_GRAPHICS`, das HUD zeigt die Meldung, die Fahrt läuft
  weiter und wird als Indoor-Tour mit Punkten in der Testwelt gespeichert.
  Ein echtes 3D-Bild kann nur das Gerät zeigen.
- „Abbrechen“ während „Welt laden“ zeigt sofort „Wird abgebrochen …“
  (`IndoorWorldUi.Building.cancelling`, Knopf aus), bis der Weltbau seine
  laufenden Kacheln fertig hat; danach der Voranschlag mit „Fortsetzen“
  (Test `abbruchDesWeltbausIstSofortSichtbar`).
- `PRIVACY.md` nennt alle sechs Hosts der Positivliste; `OpenSourceNotices`
  die Luftbild-Lizenzen aller vier Länder.
- `filament-utils-android` ist entfallen (die Darstellung braucht es nicht).
- Tageszeit (seit 24.11.2026, Branch `claude/indoor-himmel`): Karte
  „Tageszeit“ im Schritt Koppeln (Automatisch / Morgen / Mittag / Abend,
  Vorgabe Automatisch, gemerkt unter `trailscape.indoor.time_of_day`). Beim
  Start der Fahrt und bei jeder Änderung `setSky(IndoorDaylight.skySetting(…))`
  mit dem Ursprung der Welt; „Automatisch“ wird während der Fahrt alle
  10 Minuten der Uhr nachgeführt. Nachts helle Dämmerung. Rechnung und
  Bilder: `docs/indoor/render.md`, „Himmel, Wolken, Tageszeit“.
- Offen: Die Qualität der Darstellung ist nicht einstellbar (`setSettings`
  wird nicht gerufen; es gilt die Qualität der Welt).

## Bekannte Strecken (23.11.2026)

Zweiter Bereich der Streckenwahl mit vorgebauten Welten aus einem
GitHub-Release; alles dazu in `docs/indoor/catalog.md`. Für den Ablauf
wichtig: Eine bereite bekannte Strecke führt mit „Weiter“ ohne Voranschlag
direkt in die Fahrt (`continueFromRoute` → `prepareCatalog` → `startRide`),
gefahren wird auf `worldRouteOf(manifest)`. Bilder 116 (deutsch, alle
Zustände) und 117 (englisch).

- [ ] Bekannte Strecke laden (nach dem ersten Workflow-Lauf): Größe,
      Fortschritt, Abbruch und Fortsetzen über Mobilfunk/WLAN, danach offline
      fahren und in der Weltverwaltung löschen
- [ ] Vor dem ersten Workflow-Lauf: „Laden“ zeigt „Paket ist noch nicht
      verfügbar“

## Startpunkt und Fortsetzen (24.11.2026)

Wunsch des Gründers: auf dem Höhenprofil unten auswählen können, wo die Fahrt
beginnt — etwa in der Mitte, wenn der Anfang schon gefahren ist —, statt
immer von vorn zu starten.

**Bedienung**

- Solange sich der Fahrer versetzen lässt — vor dem ersten Tritt und in der
  Pause —, steht unten statt Gang und schmalem Profil die
  **Startpunkt-Karte** (`IndoorStartPanel.kt`): ein großes Höhenprofil zum
  Ziehen oder Antippen, der Punkt ist die Startstelle, links davon (der
  übersprungene Teil) blass; darüber „km 7,4 · 588 m“ (Stelle und Höhe),
  daneben runde −/+-Knöpfe für 100 m genau (gleiche Größe wie die übrigen
  HUD-Knöpfe, mit dem Daumen ohne Umgreifen). Für die Bedienungshilfe ist das
  Profil ein Regler (`setProgress`). Hochformat: Karte über die volle Breite,
  darunter der Knopf; Querformat: flacheres Profil, Auswahl und Knopf in einer
  Reihe. Die mittige Phasenkarte entfällt, solange die Startpunkt-Karte steht.
- **Vor jeder Fahrt** steht die Karte, bis **„Losfahren“** getippt wird —
  auch ohne gemerkte Stelle (Vorauswahl dann km 0) und auch mit dem
  Demo-Trainer. So lässt sich jederzeit „erst in der Mitte“ starten.
- **Echter Trainer:** Der erste Tritt gilt ebenfalls als „Losfahren“, solange
  der Startpunkt nicht angefasst wurde (Hinweis „… oder tritt einfach los“).
  Wer einfach lostritt, will an der Vorauswahl starten — eine normale Fahrt
  braucht so keinen zusätzlichen Tipp. Sobald jemand zieht, tippt, −/+ oder
  eine Auswahl drückt, startet nur noch „Losfahren“; sonst rollte man beim
  Wählen schon los. Der Demo-Trainer „tritt“ sofort und wartet deshalb immer
  auf den Knopf.
- **Fortsetzen:** Wird eine Fahrt mittendrin beendet, merkt sich die App die
  Stelle je Strecke. Beim nächsten Start derselben Strecke steht der Fahrer
  schon dort („Weiter bei km x“ ist vorausgewählt), daneben „Von vorn“.
- In der Pause trägt die Karte „Weiter“.
- **Auswertung und Tour:** Begann die Fahrt nicht am Anfang (ab 50 m), zeigt
  die Auswertung ein Kennzeichen „ab km 7,4“, und die gespeicherte Tour heißt
  „Indoor · <Strecke> (ab km 7,4)“ — so steht es in Liste und Detail, ohne
  neues Feld im Tour-JSON. Gemeint ist die Stelle des ersten Tritts; ein
  späterer Versatz in der Pause ändert sie nicht.
- Nach dem Beenden wird die Stelle neu gemerkt; nach dem Ziel gelöscht.

**Umsetzung**

- `:core` (`IndoorStartPoint.kt`): Die Sitzung erfüllt zusätzlich
  `MovableIndoorSession` (`canMove`, `moveTo`). Der Vertrag
  `IndoorSession.kt` bleibt unverändert — das Versetzen ist eine eigene
  Schnittstelle, `IndoorSession.moveStartTo(d)` liefert für jede andere
  Sitzung (Attrappe) `false`. `createIndoorSessionAt(config, d)` baut eine
  Sitzung, die schon an `d` steht. `IndoorStartPoints` hält die Regeln
  (Begrenzung, 10-m-Raster, 100-m-Schritt, Merken, Angebot),
  `IndoorResumePoint` die gemerkte Stelle samt JSON.
- Die Laufzeit versetzt mit `moveStart(d)` im nächsten Takt (wird beim Tausch
  der Sitzung gegen die echten Trainer-Fähigkeiten nachgespielt) und hält mit
  `holdStart` (jetzt immer) die Messwerte zurück, bis `releaseStart()` kommt
  oder — mit echtem Trainer (`releaseOnFirstStroke`) und unberührtem
  Startpunkt — der erste Tritt. Versetzt wird
  vor dem ersten Schritt: Darstellung (erster `setState`), Kacheln um die
  Stelle, Kamera, die erste SIM-Vorgabe an den Trainer und „x von y km“ gelten
  ab dem ersten Bild der Startstelle (Test
  `gemerkteStelleIstVorauswahlUndWartetAufLosfahren`).
- Gemerkt wird im `KeyValueStore` unter
  `trailscape.indoor.resume.<Schlüssel der Streckenwahl>` (`testwelt`,
  `ride:<id>`, `catalog:<id>`) — also je gewählter Strecke, gleich ob mit oder
  ohne Welt gefahren. Inhalt: Stelle in der Runde, Länge der Strecke, Zeit.

**Entscheidungen**

- **Statistik:** `IndoorTotals.distanceM`, die Höhenmeter (auf und ab), die
  Fahrzeit und der Schnitt zählen nur wirklich gefahrene Meter
  (`riddenM` in der Sitzung), nie den übersprungenen Anfang und nie einen
  Versatz in der Pause. Gespeicherte Tour, Auswertung, Belastung, Wochenziel
  und Statistik lesen diese Zahlen (`stats` des Records). Die Punkte der Tour
  beginnen an der Startstelle; ein Versatz in der Pause ist in der Spur ein
  Sprung (wie der Rundenwechsel; Indoor-Touren zählen ohnehin nicht für
  Wege, Kacheln und Segmente).
- **Runden:** `distM` bleibt der Laufmeter auf der Strecke. Wer auf einer
  Runde in der Mitte startet, ist in Runde 1, bis er die Ziellinie quert;
  `laps` = angefangene Runden wie bisher. Versetzt wird immer innerhalb der
  laufenden Runde. Auf einer Strecke ohne Rundschluss bleiben bis zum Ziel
  mindestens 100 m (`GOAL_RESERVE_M`).
- **Geist: mitnehmen statt ausblenden.** Er startet an derselben Stelle wie
  der Fahrer: die Vergleichsleistung aus dem Stand (wie am Anfang), die
  frühere Fahrt zeitlich so verschoben, dass sie die Stelle zur selben
  Fahrzeit erreicht — verglichen wird genau der gefahrene Abschnitt, und das
  ist auch die Frage, die man sich bei „Weiter bei km 12“ stellt („bin ich
  heute auf der zweiten Hälfte schneller?“). Hat die frühere Fahrt die Stelle
  nie erreicht, fährt kein Geist mit (Anzeige und Endabstand leer). Wird in der
  Pause versetzt, beginnt der Vergleich an der neuen Stelle neu; der
  Endabstand in der Auswertung gilt für den Abschnitt seit dem letzten
  Versetzen. Ausblenden wäre einfacher gewesen, nähme aber gerade beim
  Fortsetzen den Vergleich weg.
- **Merken:** nach jedem Beenden (auch wenn die Fahrt unter 60 s bleibt oder
  verworfen wird — es geht um die Stelle auf der Strecke, nicht um die Tour).
  Gelöscht wird am Ziel einer Strecke ohne Rundschluss, wenn die Stelle
  weniger als 200 m hinter dem Start liegt (das wäre „Von vorn“) oder weniger
  als 100 m vor dem Ziel; auf einer Runde, wenn sie weniger als 200 m vor der
  Ziellinie liegt. Wer eine Runde ganz fährt und in der nächsten aufhört,
  setzt dort fort — eine Runde hat kein endgültiges Ziel.
- **Angebot:** nur, wenn die Strecke heute höchstens 5 % anders lang ist als
  beim Merken (Streckenband der Welt gegen die ursprüngliche Tour: Stelle
  anteilig umgerechnet); sonst ist es eine andere Strecke und es gibt kein
  Angebot. Kaputter Inhalt im Speicher wird ignoriert.
- „Weiter bei …“ / „Von vorn“ gibt es nur vor dem ersten Tritt; in der Pause
  wird frei auf dem Profil versetzt.

**Tests**

- `IndoorStartPointTest` (`:core`): Stelle, Steigung und erste Vorgabe ab dem
  ersten Schritt; nur Gefahrenes zählt (Strecke, Höhe, Schnitt, Record);
  Ziel nach dem Rest; Runde 1 bis zur Ziellinie; Begrenzung und Raster;
  Versetzen nur in READY/PAUSED, zählt nicht; Versetzen auf der Runde;
  Geist (beide Arten) an der Startstelle, neu nach dem Versetzen, kein Geist
  ohne Abdeckung; Merk- und Angebotsregeln; JSON.
- `IndoorStartFlowTest` (`:app`, ganzer Ablauf mit echter Sitzung): ohne
  gemerkte Stelle am Anfang, Versetzen in der Pause samt −/+ und Merken;
  Vorauswahl der gemerkten Stelle, Warten auf „Losfahren“, „Von vorn“ und
  zurück, erster Zustand der Darstellung an der Startstelle, gespeicherte
  Tour zählt nur das Gefahrene; über die Ziellinie gefahren löscht;
  kaputter Speicherinhalt.
- `IndoorStartHoldTest` (Laufzeit): Demo wartet trotz Treten auf
  „Losfahren“; echter Trainer startet mit dem ersten Tritt, nach dem
  Verschieben nur mit „Losfahren“. Im Ablauftest: „ab km 1,2“ in Auswertung
  und Tourname.
- Bilder 118–124 (siehe „Bilder“).

**Auf dem Gerät zu prüfen**

- [ ] Profil mit dem Daumen ziehen und antippen (hoch und quer): Punkt folgt
      dem Finger ohne Ruckeln, −/+ treffen, nichts löst „Welt ausblenden“ aus
- [ ] Mit Welt: nach dem Versetzen springt der Fahrer an die Stelle, die
      Kamera folgt sofort (bei mehr als 80 m springt sie, darunter gleitet sie
      0,35 s), fehlende Kacheln laden nach, keine Löcher beim Losfahren
- [ ] Echter Trainer: der Widerstand passt vom ersten Tritt an zur Steigung
      der Startstelle
- [ ] Fahrt mittendrin beenden, dieselbe Strecke neu wählen: „Weiter bei km x“
      steht; mit Demo-Trainer bewegt sich vor „Losfahren“ nichts
- [ ] Echter Trainer: ohne Anfassen startet der erste Tritt an der
      Vorauswahl; nach dem Verschieben bewegt Treten nichts bis „Losfahren“
- [ ] Gespeicherte Tour einer fortgesetzten Fahrt: Distanz und Höhenmeter nur
      des gefahrenen Teils in Liste, Detail und Wochenziel

## Einheit des Tages (24.11.2026)

Wunsch des Gründers: „Nicht nur basierend auf meinen Routen eine 3D-Welt
generieren, sondern auch eine Welt für mein jeweiliges Training, wie der
Trainer es empfiehlt – die Einheit des Tages.“ Bisher wählte man im
Indoor-Ablauf eine Strecke und konnte als Modus „Einheit des Tages“ (ERG)
dazunehmen — die Strecke hatte mit der Einheit nichts zu tun. Jetzt schlägt
eine Karte **ohne weitere Auswahl** eine echte Strecke vor, die zur Einheit
passt, und fährt sie im ERG-Modus.

**Wo**

- Auf *Heute* ersetzt die Karte „Einheit des Tages“ die Indoor-Einstiegskarte,
  sobald es eine Einheit für die Rolle gibt (`IndoorTodayEntry`); „Fahren“
  öffnet den Indoor-Ablauf mit diesem Vorschlag vorausgewählt
  (`AppViewModel.requestIndoorDay`), „Runde planen“ die Routensuche.
  Am Ruhetag steht dort ehrlich „Heute ist Ruhetag“ mit „Trotzdem frei
  fahren“; am Zieltag, solange die Suche läuft oder wenn der Kern nichts
  liefert, die bisherige Einstiegskarte.
- Im Schritt *Strecke* steht dieselbe Karte oben (`IndoorRouteScreen`,
  Parameter `day`). „Fahren“ wählt Strecke, ERG-Modus und Startpunkt und geht
  direkt weiter (`IndoorViewModel.rideDaySuggestion`).

**Die Karte** (`IndoorDayCard.kt`, Bilder unten): Titel der Einheit, Dauer
(„81 statt 90 min · an deine Tagesform angepasst“ bzw. „Heute steht keine
Einheit im Plan – 45 min locker und flach“), die vorgeschlagene Strecke, ein
Satz Begründung („24,6 km · 3 Anstiege von 6–8 min für deine 3 × 8 min · Start
bei km 12,4“; bei Runden „7 Runden“; „Strecke endet 9 min vor der Einheit“;
„Profil geschätzt“ bei Katalogstrecken vor dem Laden), ob die Welt bereitliegt
(„Welt liegt bereit“, „Welt laden: 114 MB“, „Welt bauen: etwa 14 min“,
„Runde planen, dann Welt bauen: etwa 8 min“) und „Andere Strecke (1/4)“ zum
Durchblättern der nächsten Kandidaten (höchstens sechs).

**Welche Einheit** (`daySessionFor` in `core/…/indoor/DayRoute.kt`): aus der
Tagesentscheidung `decideTodayRoute` — dieselbe, die „Heute“ und die Karte
rechnen (`decideToday`), die Tagesform-Skalierung wird also wiederverwendet,
nicht nachgebaut.

| Tagesentscheidung | Einheit auf der Rolle |
|---|---|
| Zieltag | keine (`EVENT`) |
| kein Routenziel (Ruhetag nach Tagesform), auch mit Planeinheit | keine (`REST`), Karte sagt es |
| keine Planeinheit (kein Plan oder Ruhetag im Plan) | locker, höchstens 45 min, mindestens 20 (`EASY`) |
| Planeinheit ungedämpft | die Planeinheit (`PLANNED`) |
| Planeinheit gedämpft (`downgraded` oder Intensität gekappt) | Dauer × Faktor, Intensität des Routenziels; wird sie zurückgenommen, entfällt die Intervallstruktur (`PLANNED_REDUCED`) |

Die ERG-Einheit entsteht wie bisher mit `ergWorkoutFor`. **Abweichung vom
bisherigen Verhalten:** Auch der Modus „Einheit des Tages“ im Schritt Strecke
fährt jetzt diese gedämpfte Einheit (vorher die rohe Planeinheit, auch am
Ruhetag); an einem Tag ohne Planeinheit heißt er „Lockere Runde“. Ohne
Tagesentscheidung (Tests, `today = null`) bleibt es beim alten Weg.

**Passung** (`DayRoute.kt`, reines Kotlin, `DayRouteTest`):

- *Ziel* (`dayRouteGoal`): Belastungsabschnitte ab 85 % FTP → Anstiege
  (`CLIMBS`); sonst ein Abschnitt ab 62 % (Grundlage) → flach bis wellig
  (`ROLLING`); sonst flach (`FLAT`).
- *Nachrechnung* (`simulateDayRide`): Die Einheit wird auf dem Höhenprofil der
  Strecke (50-m-Stücke, geglättet) nachgefahren — je Abschnitt das
  Gleichgewichtstempo bei der Sollleistung aus der Indoor-Physik
  (`steadySpeedMps`). Ergebnis: gefahrene Distanz, wo die Intervalle liegen,
  ob eine Strecke ohne Rundschluss vor der Einheit endet; Runden laufen weiter
  wie in der Sitzung. Im Test gegen die echte Sitzung im ERG-Modus: unter 3 %
  Abweichung in der Distanz.
- *Bewertung* 0..1 (`scoreDayRoute`): 45 % Distanz (Strecke endet zu früh:
  Anteil^1,5; Rest einer langen Strecke bis −0,2; viele Runden bis −0,3),
  55 % Gelände (Intervalle: Anteil der Intervallzeit am Anstieg ≥ 3 %;
  Grundlage: Abzug für Stücke ≥ 6 %; locker: Abzug für Anstiege). Geschätztes
  Profil × 0,92.
- *Startpunkt* (`bestDayStart`): bis zu 48 Startstellen (Raster und
  Begrenzung wie `IndoorStartPoints`); ein späterer Start nur, wenn er
  mindestens 0,03 besser ist und die Einheit nicht kürzt. So fallen die
  Intervalle einer langen Strecke in die Anstiege. Der Ablauf setzt den
  Fahrer vor dem ersten Tritt dorthin (anteilig auf die Strecke der Fahrt
  umgerechnet); „Weiter bei …“ einer gemerkten Stelle bleibt auf der
  Startpunkt-Karte wählbar.
- *Reihenfolge* (`rankDayRoutes`): sortiert nach Bewertung minus Aufschlag der
  Verfügbarkeit — fertige Welt 0, Download 0,02, Welt bauen 0,04, neue Runde
  0,06. Bei annähernd gleicher Passung gewinnt so die Strecke ohne Bauzeit.

**Kandidaten** (`IndoorDayRouteFinder`, ohne Netz, auf `Dispatchers.IO`, Profile
gemerkt): (a) Welten auf dem Gerät — Testwelt, gebaute Welten eigener
Strecken, geladene Katalogwelten (Profil aus dem Streckenband); (b)
Katalogstrecken zum Laden — **geschätztes Profil** aus Länge und Höhenmetern
des mitgelieferten Katalogs (über 4 % im Mittel ein durchgehender Anstieg,
sonst 1-km-Wellen); (c) die 30 jüngsten eigenen Planungen und Touren ohne
Welt (Profil aus den Punkten; ohne Höhen kein Kandidat); (d) „Neue Runde ab
deinem Startpunkt“: Bewertung fest 0,75, Ziel aus `dayPlanTarget` (Distanz der
Einheit auf flacher Strecke, Höhenprofil nach Gelände, höchstens so bergig wie
das Routenziel des Tages erlaubt). „Runde planen“ verlässt den Indoor-Ablauf
und öffnet die vorhandene Routensuche (`requestRouteGeneration`) mit diesem
Ziel ab dem dort üblichen Startpunkt; die gespeicherte Runde erscheint danach
als Kandidat (c).

**Ablauf nach „Fahren“**

- fertige Welt (Katalog, Testwelt, gebaute Welt) → direkt in die Fahrt bzw.
  „Welt laden“ zeigt „bereit“; eigene Strecke ohne Welt → Voranschlag „Welt
  laden“ wie bisher (Netz erst nach dem Tipp dort).
- Katalogstrecke nicht geladen → Bereich „Bekannte Strecken“, der Download
  startet (Nutzeraktion „Laden und fahren“); ist die Welt bereit und der
  Schritt Strecke noch offen, geht es von selbst weiter.
- neue Runde → Routensuche auf der Karte.

**Wenn Einheit und Strecke nicht gleichzeitig enden** (`dayRideEnd`, jede
ERG-Fahrt):

- *Einheit zu Ende, Strecke nicht:* Karte „Einheit geschafft“ in der Weltmitte
  mit „Ausfahren“ (Karte weg, der Trainer hält die Ausfahrleistung des letzten
  Abschnitts, man fährt weiter) und „Beenden“ (Auswertung).
- *Strecke zu Ende, Einheit nicht:* Auf einer **Runde** passiert das nie — die
  Sitzung beginnt die nächste Runde. Auf einer Strecke **ohne Rundschluss**
  hält die Sitzung am Ziel an (FINISHED); die Karte „Strecke zu Ende“ nennt
  die Restzeit der Einheit und bietet „Beenden“. Die Karte „Einheit des
  Tages“ warnt schon vorher („Strecke endet 9 min vor der Einheit“), und die
  Bewertung straft solche Strecken ab.

**Zweiter Weg: „Generierte Welt“** (Ergänzung des Orchestrators, vom Gründer
bestätigt). Auf der Karte stehen zwei Wege zur Wahl: „Echte Strecke“ (die
Vorschläge oben, durchblätterbar) und „Generierte Welt“ — eine erfundene
Landschaft genau zur Einheit, auf dem Gerät ohne Netz gebaut.

- *Zielprofil* (`core/…/indoor/DayGenerated.kt`, `dayGeneratedProfile`,
  `DayGeneratedTest`): je Abschnitt der ERG-Einheit ein Abschnitt des
  `GeneratedProfile` (Schnittstelle der Session „generierte Welt“, Branch
  `claude/indoor-generiert`, hier hineingemergt): Intervalle → `CLIMB` mit
  6 % bei 95 % FTP, 7 % an der Schwelle (4–8 %); Pausen → `DESCENT` −2 %;
  Grundlage → `ROLLING` um 0 %; Ein- und Ausfahren, locker → `FLAT`.
  Länge = Dauer × Gleichgewichtstempo bei Sollleistung und Steigung (dieselbe
  Physik wie die Nachrechnung): Jeder Schritt dauert bei Sollleistung seine
  Solldauer, die Einheit endet am Ziel (im Test: Fahrzeit auf unter 1 % gleich
  der Einheitsdauer). Gleichartige Nachbarn werden zusammengelegt.
  **Abweichung von der Vorgabe** „flach in Pausen“: leicht bergab (−2 %),
  damit ein Teil der Höhe zurückkommt; der Text auf der Karte sagt
  „Pausen leicht bergab“.
- *Seed* (`dayGeneratedSeed`): FNV-1a über Art, Dauer und Anteil jedes
  Abschnitts — dieselbe Einheit ergibt denselben Seed und (bei gleichem
  Fahrer) dasselbe Profil, also dieselbe `worldId` und dieselbe Welt. Eine
  einmal gebaute Welt ist beim nächsten Mal „Welt liegt bereit“.
- *Vorschlag:* Passt keine echte Strecke ordentlich (keine fertige, ladbare
  oder eigene Strecke mit `rank ≥ 0,69`, also nicht besser als „Neue Runde“),
  steht die Karte auf „Generierte Welt“; sonst auf „Echte Strecke“. Umschalten
  geht jederzeit.
- *Ablauf:* „Welt bauen“ → neue Streckenoption `IndoorRouteOption.Generated`
  (nicht in der Liste) → Voranschlag ohne Hosts und ohne Download → Bau über
  die Abstraktion `IndoorGeneratedWorlds` (in der App
  `DefaultIndoorGeneratedWorlds` → `generatedWorldPlan`/`buildGeneratedWorld`).
  Liegt die Welt schon vor, geht es direkt in die Fahrt (ERG). Die Welt
  bekommt die Zuordnung `generated:<seed>` und gilt in der Weltverwaltung nie
  als verwaist.
- *Bauzeit* grob 10 s je km (ohne Netz; `GENERATED_BUILD_SECONDS_PER_KM`),
  auf dem Gerät zu prüfen.
- Die Einstiege der Nachbar-Session sind in diesem Branch noch Rümpfe
  (`TODO`): Ablauftest und Bilder laufen mit einer Attrappe, in der App
  meldet „Welt bauen“ bis zum Merge dieser Umsetzung „Strecke nicht
  verfügbar“ (der Fehler wird wie jeder Ausfall eines fremden Teils
  angezeigt).

**Modus „Gelände“ (nur Vorschlag, nicht umgesetzt)**

Ein zweiter Fahrmodus wäre: Simulation wie bei freier Fahrt (die Steigung der
Strecke ist der Widerstand), die Sollleistung der Einheit nur als Anzeige.
Geprüft: Die Sitzung liefert im SIM-Modus keinen `ErgState` (`ergState()`
gibt dort `null`), die Anzeige müsste die Abschnittsuhr also in der App aus der
Fahrzeit nachrechnen, das HUD bräuchte im SIM-Modus unten Gang *und*
Sollleistung, und die Auswertung müsste „Soll getroffen?“ beantworten. Das
ist mehr als eine kleine Ergänzung und bleibt deshalb offen. Sinnvoll wird
es mit der Passung: Wenn die Intervalle schon in den Anstiegen liegen, ist
der Anstieg selbst der Widerstand — das wäre der natürliche nächste Schritt.
Besonders gut passt er zur generierten Welt: Dort liegt jeder Anstieg genau
unter einem Intervall und ist so steil, dass die Sollleistung bei Sollzeit
herauskommt — im Geländemodus fährt man die Einheit also „von selbst“, ohne
dass der Trainer eine Leistung erzwingt. Die nötige Erweiterung bleibt
dieselbe wie oben (Sollanzeige im SIM-Modus).

**Entscheidungen und Grenzen**

- Ruhetag: kein Angebot auf der Rolle (wie beim Routenangebot); „Trotzdem
  frei fahren“ öffnet den Ablauf ohne Einheit.
- Katalogstrecken werden alle als Strecken ohne Rundschluss bewertet (der
  Katalog sagt es nicht); die zehn Katalogstrecken sind 10–16 km lang und
  passen deshalb nur zu kurzen Einheiten oder als Abschnitt.
- Die Bauzeit ist grob geschätzt: 20 s je km auf dem Handy
  (`IndoorDayRouteFinder.BUILD_SECONDS_PER_KM`); gemessen sind nur 3–5 s/km auf
  dem Entwicklerrechner mit warmem Cache.
- Kein Netzverkehr durch die Suche; geladen oder gebaut wird erst nach dem
  Tipp auf „Laden und fahren“ bzw. „Welt laden“.
- Weltbau, `WORLD_BUILDER_ID` und Katalogversionen sind unberührt; keine
  Vertragsdatei geändert. Aus `core/…/indoor/generated/` nur die Schnittstelle
  der Nachbar-Session übernommen (Merge), nichts daran geändert.

**Tests:** `DayRouteTest` (`:core`, 15 Tests: Einheit je Tagesentscheidung,
Gelände, Profil aus Stichproben/Track/Schätzung, Nachrechnung gegen die echte
Sitzung, Strecke zu kurz und Runde, Intervalle am Berg, Startpunkt, Grundlage
und Erholung, Reihenfolge mit Bauzeit, neue Runde, beide Enden mit echter
Sitzung), `IndoorDayFlowTest` (`:app`, ganzer Ablauf: Karte wählt die
Strecke mit Anstiegen samt Startpunkt, blättern, „Fahren“ → ERG und
Startstelle in der Sitzung; Ruhetag ohne ERG; Tag ohne Plan; Strecke endet
vor der Einheit; Einheit endet vor der Strecke mit „Ausfahren“; ohne
Tagesentscheidung keine Karte; ohne passende Strecke ist die generierte Welt
der Vorschlag, Bau ohne Netz, beim zweiten Mal bereit und direkt in die Fahrt;
passende echte Strecke bleibt Vorschlag), `DayGeneratedTest` (`:core`:
Abschnitte je Art, Fahrzeit = Einheitsdauer für vier Einheiten, stabiler Seed).

**Bilder** (`docs/indoor/bilder/tageseinheit/`, aus `IndoorScreenshotTest.tageseinheit*`):
01 Schritt Strecke mit der Karte (Intervalle, Welt bauen, Start bei km 12,4),
02 die Karte mit fertiger Welt, Download (Profil geschätzt) und neuer Runde,
03 gekürzt durch die Tagesform, lockere Runde ohne Plan, Ruhetag, Suche läuft,
04 und 06 „Einheit geschafft“ hoch und quer, 05 „Strecke zu Ende“,
07 Schritt Strecke englisch, 08 beide Wege (generierte Welt gewählt, echte
Strecke gewählt), 09 generierte Welt englisch. Die Heute-Karte ist dieselbe
Komponente.

**Auf dem Gerät zu prüfen**

- [ ] *Heute* mit Planeinheit: Karte erscheint nach kurzer Suche, Vorschlag
      und Begründung stimmen mit der Strecke überein; „Fahren“ führt über
      Koppeln zum Schritt Strecke mit demselben Vorschlag
- [ ] Intervalle auf einer eigenen langen Strecke mit Anstiegen: Start an der
      vorgeschlagenen Stelle, die Belastungen fallen tatsächlich in die
      Anstiege (KICKR im ERG-Modus hält die Leistung, das Tempo folgt dem Berg)
- [ ] Grundlage gekürzt durch die Tagesform: Dauer auf der Karte und im HUD
      passt zur Ansage auf *Heute*
- [ ] Katalogstrecke „Laden und fahren“: Download läuft, danach geht es ohne
      weiteren Tipp in die Fahrt; Abbruch des Downloads lässt den Schritt
      Strecke stehen
- [ ] „Runde planen“: Routensuche öffnet mit sinnvoller Distanz und
      Höhenprofil; gespeicherte Runde erscheint danach als Vorschlag
- [ ] Einheit endet vor der Strecke: „Ausfahren“ lässt weiterfahren, der
      Trainer hält die Ausfahrleistung; „Beenden“ führt zur Auswertung
- [ ] Strecke ohne Rundschluss endet vor der Einheit: Karte „Strecke zu
      Ende“ mit plausibler Restzeit; Runde: nächste Runde ohne Halt
- [ ] Suchdauer auf dem Handy mit vielen gespeicherten Touren (30 Profile,
      bis zu 48 Startpunkte je Strecke) — die Karte darf „Heute“ nicht
      spürbar bremsen
- [ ] Geschätzte Bauzeit gegen die echte Bauzeit (WLAN und Mobilfunk)
- [ ] Englisch: Karte auf *Heute* und im Schritt Strecke
- [ ] Generierte Welt (nach dem Merge der Session „generierte Welt“):
      Intervalle fallen in die Anstiege, die Einheit endet am Ziel, Bauzeit
      gegen die Schätzung; zweiter Aufruf derselben Einheit ohne Neubau
