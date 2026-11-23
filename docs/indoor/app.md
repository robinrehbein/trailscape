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
113 Schrift 130 %, 114 harte Pause, 115 Querformat ERG mit Schrift 130 %. Nur in
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
- Offen: Tageszeit und Qualität der Darstellung sind nicht einstellbar
  (`setSky`/`setSettings` werden nicht gerufen; es gilt `SkySetting.DEFAULT`
  und die Qualität der Welt).
