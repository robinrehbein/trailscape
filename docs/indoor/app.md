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
  laufende Fahrt noch die Auswahl wiederhergestellt (nur der Demo-Schalter
  bleibt in den SharedPreferences). Der Vertrag verlangt mehr nicht.
- **Zurück während der Fahrt** verlässt den Ablauf nie: Die Fahrt hält an, ein
  Dialog bietet „Weiterfahren“ oder „Fahrt beenden“ (dann Auswertung, dort
  speichern oder verwerfen). In der Auswertung fragt Zurück bei einer
  ungespeicherten Fahrt, ob sie verworfen werden soll.
- **Einstieg beim Aufzeichnen-Knopf:** ein leiser Textknopf „Indoor auf dem
  Rollentrainer fahren“ in beiden Fassungen des Bereit-Dialogs (freie Fahrt und
  geplante Route).
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
  Fahrt läuft mit HUD und Höhenprofil weiter. Auf diesem Branch werfen die
  Kern- und Welt-Einstiege bis zur Integration `NotImplementedError`.
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
  Drehung. Der Demo-Schalter wird in den SharedPreferences gemerkt.
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
5. **Übersetzung:** 40 Zähne vorn, 14er-Cog als Vorgabe.
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
      „Koppeln“, nichts hängt, der Demo-Schalter ist gemerkt
- [ ] Diagnoseseite aus dem Ablauf und aus *Mehr* erreichbar
- [ ] Englisch: der gesamte Ablauf mit Systemsprache Englisch
