# Lückenliste: Was fehlt, damit Trailscape vier Apps ersetzt

Stand 25.11.2026, erhoben an `origin/main`. Reine Analyse am Code und an der Doku; nichts davon lief auf einem Gerät.

**Vision.** Eine App für Rennrad und Gravel, die komoot (Planen, Navigieren), Strava (Aufzeichnen, Auswerten, Motivation), Zwift (drinnen fahren) und eine Trainingsapp (Plan, Steuerung) ersetzt – ohne Konto, ohne Telemetrie, Daten auf dem Gerät. Diese drei Grenzen sind gewollt. Was sie zwingend verletzt, steht unter „Bewusst nicht“ und zählt nicht als Lücke.

**Vorbilder.** komoot, Strava, Zwift; als Trainingsapp TrainerRoad (strukturierter Plan mit Smarttrainer-Steuerung) und JOIN (adaptive Planung). Für Auswertungskennzahlen ergänzend intervals.icu und TrainingPeaks. Funktionsumfang und Bezahlschranken sind am 25.11.2026 im Netz nachgeschlagen; Quellen am Ende.

**Lesehilfe.**

- Pfade: `app/…` = `app/src/main/kotlin/de/trailscape/app/`, `core/…` = `core/src/main/kotlin/de/trailscape/core/`.
- Stand: **da** / **teilweise** / **fehlt**. „Da“ heißt: im Code vorhanden, nicht: auf dem Gerät bewährt.
- Alltag: wie oft die Lücke einen Fahrer trifft (täglich / wöchentlich / selten).
- Fremdgehen: Ist die Lücke ein Grund, die andere App weiter zu öffnen?
- Aufwand: klein (Tage) / mittel (ein bis drei Wochen) / groß (mehr, oder mit fremder Abhängigkeit). Grobe Schätzung.

## Zusammenfassung: die zehn wichtigsten Lücken

1. **Navigation plant bei Abweichung nicht um.** Die App erkennt „abseits der Route“ und vibriert, rechnet aber keinen Weg zurück; wer eine Sperrung umfährt, navigiert ab da ohne Hilfe – das Kernversprechen von komoot.
2. **Kein Weg zum Radcomputer und kein FIT-Export.** Viele Rennradfahrer fahren mit Garmin, Wahoo oder Hammerhead; Trailscape kann Routen und Einheiten nur als GPX von Hand herausgeben, und der im README genannte FIT-Export existiert im Code nicht.
3. **Indoor ist ein ungeprüfter Prototyp.** Darstellung, Trainersteuerung und Schalttaster liefen nie auf Hardware, die Fahrt pausiert beim Verlassen der App, und angebunden ist nur Bluetooth-FTMS – solange das so ist, bleibt Zwift installiert.
4. **Keine Workout-Bibliothek, kein Editor, kein Import.** Im ERG-Modus gibt es nur die Einheit des Tages aus dem Plan in vier Intervallformen; wer „heute 2×20 Sweet Spot“ oder ein fremdes Workout fahren will, braucht die Trainingsapp.
5. **Offline-Karten sind eng begrenzt und rechtlich offen.** 250 Kacheln je Vorgang, nur ein Kartenstil, und die Erlaubnis des Kachelanbieters steht aus; für eine Tagestour im Funkloch reicht das knapp, für eine Reise nicht.
6. **Entdecken fehlt.** Keine Orte entlang der Route (Wasser, Café, Radladen), keine Beliebtheit von Wegen, keine fertigen Touren; für eine unbekannte Gegend öffnet man weiter komoot oder die Heatmap von Strava.
7. **Leistungsauswertung bleibt flach.** Keine Leistungskurve über Dauern, keine Zeit in Zonen je Tour, kein Trainingskalender; wer mit Leistungsmesser fährt, schaut dafür weiter in Strava oder intervals.icu.
8. **Gemeinsam fahren hat keine Oberfläche.** Das Raum-Protokoll für Gruppenfahrten ist gemergt, aber unsichtbar; drinnen fährt man allein gegen einen Geist, und genau die Gesellschaft ist der Hauptgrund für Zwift.
9. **Der Plan führt draußen nicht durch die Einheit und lässt sich nicht verschieben.** Keine Intervallansage im Fahrmodus oder auf der Uhr, kein Tausch von Tagen, keine Abfrage von Befinden oder Anstrengung; das ist der tägliche Kern von JOIN.
10. **Planen ist nur am Handy und ohne Feinarbeit möglich.** Wegpunkte lassen sich nicht verschieben, Belag und Wegtyp stehen nicht am Höhenprofil, es gibt kein Wetter entlang der Route und keinen Mehrtagesplaner.

Knapp dahinter: Uhr zeichnet nicht ohne Handy auf, keine Verwaltung von Rädern und Verschleiß, kein FTP-Stufentest, keine Fotos an Touren.

## Wo Trailscape heute schon vorn liegt

- **Die Kette ist geschlossen.** Tagesform → Einheit des Tages → dazu gebaute Runde ab Haustür → Rückfluss in die Belastung (`core/…/Readiness.kt`, `TodayRoute.kt`, `RouteGenerator.kt`, `TrainingLoad.kt`). Keines der Vorbilder verbindet das.
- **Rundkurs-Generator mit Wind, Belag und „neue Gegenden“** (`core/…/RouteWindPreference.kt`, `RouteQuality.kt`, `RiddenWays.kt`). Bei komoot ist ein automatischer Rundtour-Vorschlag nicht belegt, bei Strava kosten Routenvorschläge ein Abo.
- **Bluetooth-Leistungsmesser und Trittfrequenz in der Handy-Aufzeichnung** (`app/…/sensors/`, `core/…/BleMessformate.kt`). Die Strava-App koppelt nur Pulsgurte.
- **Routing auf dem Gerät** (`core/…/OfflineRouting.kt`, `app/…/routing/`). komoot und Strava rechnen auf dem Server.
- **Die eigene Strecke als 3D-Welt** samt Geist der eigenen Außenfahrt (`core/…/indoor/world/`, `IndoorGhost.kt`). Zwift kennt nur seine eigenen Welten.
- **Fitness, Ermüdung, Form und Tagesbereitschaft ohne Abo**, mit offengelegter Herleitung (`docs/trainingswissenschaft.md`, `core/…/PerformanceManagement.kt`). Bei Strava und TrainingPeaks liegt das hinter der Bezahlschranke.
- **Kein Konto, keine Regionskäufe, keine Bezahlschranke.** Bei komoot sind Navigation, Offline und GPX-Export an freigeschaltete Regionen gebunden.

## Säule 1: Planen und Navigieren (Vorbild komoot)

| Funktion | Vorbild | Stand in Trailscape (Beleg) | Alltag | Fremdgehen | Aufwand |
|---|---|---|---|---|---|
| Route von A nach B mit Rad-Profilen | Sportprofile, Wegpräferenzen | **da**: Gravel/Rennrad × Gemischt/Asphalt/Radwege/kürzester Weg (`core/…/BrouterProfiles.kt`, `Routing.kt`) | wöchentlich | nein | – |
| Rundtour ab Standort | nur Umschalter Einweg/Rundtour belegt | **da, stärker**: Länge, Belag, Wind, neue Gegenden, Stichstrecken-Schnitt (`core/…/RouteGenerator.kt`, `RouteQuality.kt`) | wöchentlich | nein | – |
| Wegpunkte setzen, löschen, rückgängig | Klick, Linie ziehen, Undo/Redo | **teilweise**: Tipp hängt an, Löschen, Rückgängig (`app/…/ui/map/MapTapPlanning.kt`, `PlanningPanel.kt`); Verschieben eines Punkts oder Ziehen der Linie fehlt (kein Zieh-Code in `MapScreen.kt`/`MapViewHost.kt`) | wöchentlich | ja | mittel |
| Belag und Wegtyp entlang der Route | Höhenprofil mit Ansicht je Untergrund/Wegtyp, Warnungen (Treppen, Fähre) | **teilweise**: nur Anteil unbefestigt als Zahl (`core/…/RouteSurface.kt`, `unpavedLabel`); die Abschnitte liegen vor (`waySectionsFromMessages`), werden in der Karte aber nicht gezeichnet (kein `RouteWaySection` unter `app/…/ui/map/`) | wöchentlich | ja (Gravel) | klein bis mittel |
| Höhenprofil, Anstiege voraus | Profil; Anstiegserkennung in Planung und Navigation (2026 neu) | **teilweise**: Profil da (`app/…/ui/map/ElevationProfile.kt`); Anstiegserkennung gibt es nur für gefahrene Touren und Indoor (`core/…/RideSegments.kt`, `indoor/climb/`), nicht als Vorschau in der Navigation | wöchentlich | nein | mittel |
| Abbiegehinweise mit Sprache | Sprachansagen mit Straßenbezug | **teilweise**: Ansagen und Vibration (`core/…/TurnHints.kt`, `app/…/voice/VoiceAnnouncer.kt`); Hinweise entstehen aus der Geometrie, ohne Straßennamen, ohne Kreisverkehr-Ausfahrten | wöchentlich | ja | mittel (Hinweise des Routers übernehmen) |
| Umplanen bei Abweichung | automatisch, auch auf der Uhr | **fehlt**: nur Erkennung und Meldung „abseits“/„zurück auf der Route“ (`core/…/Navigation.kt` `updateOffRoute`, `core/…/i18n/SpeechTexts.kt`) | wöchentlich | ja | mittel (Routing auf dem Gerät ist vorhanden) |
| Offline-Karten | Regionen und Routen laden | **teilweise**: nur Vektorstil, 250 Kacheln je Vorgang, 150 km Kante (`app/…/ui/map/OfflineRegions.kt`, `OfflineTileMath.kt`); Erlaubnis des Anbieters offen (`docs/TODO.md`) | wöchentlich bis selten | ja (Reise) | groß (eigene Kachelquelle oder Zusage) |
| Offline-Routing | nicht vorhanden (Server) | **da** (`core/…/OfflineRouting.kt`, `app/…/routing/SegmentDownloader.kt`) | selten | nein | – |
| Karte „entlang der Route laden“ | Route samt Karte offline speichern | **fehlt**: Ausschnitt wird von Hand gewählt | wöchentlich | ja | klein bis mittel (hängt an der Kachelfrage) |
| Orte entlang der Route | Highlights, Orte, Fotos der Community | **fehlt**: Suche nur über Ortsnamen (`core/…/Geocoding.kt`); keine Wasserstellen, Cafés, Radläden, Bahnhöfe | wöchentlich | ja | mittel (offene Kartendaten; ohne Bewertungen) |
| Beliebte Wege, Heatmap | globale und persönliche Heatmap (Abo) | **teilweise**: nur die eigene – gefahrene Wege und Entdeckt-Kacheln (`core/…/RiddenWays.kt`, `ExplorerTiles.kt`); globale siehe „Bewusst nicht“ | wöchentlich | ja | – |
| Fertige Touren, Sammlungen | kuratierte Touren, Collections | **fehlt**; eigene gespeicherte Routen sind da (`app/…/ui/map/MapScreen.kt`) | selten | ja (fremde Gegend) | groß (Daten) |
| Mehrtagesplaner | Etappen (Abo) | **fehlt** | selten | ja | mittel |
| Wetter entlang der Route | Wind, Regen, Sonne je Abschnitt (Abo) | **teilweise**: aktueller Wind für die Rundenwahl, Tagesvorhersage für den Wochenplan, Sonnenuntergang (`core/…/WeatherClient.kt`, `WeekPlanWeather.kt`, `SunTimes.kt`); keine Vorhersage je Streckenabschnitt und Uhrzeit | wöchentlich | nein | klein bis mittel |
| Planen am großen Bildschirm | Web-Planer | **fehlt**: nur Handy; kein Layout für Tablet oder Querformat gefunden | wöchentlich | ja | groß |
| Route zum Radcomputer / zur Uhr | Sync zu Garmin, Wahoo, Hammerhead u. a.; Navigation auf Wear OS | **fehlt**: nur GPX teilen (`MapScreen.kt` `shareRoute`); Uhr zeigt keine Navigation (`wear/…/ui/RecordingScreen.kt`) | täglich für Nutzer eines Radcomputers | ja | klein (FIT-/TCX-Kurs als Datei) bis groß (direkter Sync, siehe „Bewusst nicht“) |
| Import von Routen | GPX/TCX/FIT | **teilweise**: GPX und FIT; GPX ohne Zeit wird als Planung erkannt (`core/…/TrackOrigin.kt`, `Fit.kt`); TCX fehlt (`core/…/BulkImport.kt`) | selten | nein | klein |
| Kartenstile | Standard, Sportkarten, 3D, Topo (teils Abo) | **da**: sechs Stile (`app/…/ui/MapStyles.kt`); kein 3D-Gelände | selten | nein | – |
| Live-Standort teilen | an Sicherheitskontakte (Abo) | **fehlt**; siehe „Bewusst nicht“ | wöchentlich | ja (für manche) | mittel (über eigenen Server) |

## Säule 2: Aufzeichnen, Auswerten, Motivation (Vorbild Strava)

| Funktion | Vorbild | Stand in Trailscape (Beleg) | Alltag | Fremdgehen | Aufwand |
|---|---|---|---|---|---|
| GPS-Aufzeichnung, Auto-Pause, Absturzsicherung | Aufzeichnung, Auto-Pause | **da** (`app/…/record/RecordingService.kt`, `AutoPauseLogic.kt`, `RecordingJournal.kt`) | täglich | nein | – |
| Sensoren am Handy | nur Pulsgurt | **da, stärker**: Puls, Leistung, Trittfrequenz (`app/…/sensors/`); auf Hardware ungeprüft (`docs/TODO.md`); kein ANT+ | täglich | nein | – |
| Import vom Radcomputer | automatisch von Garmin, Wahoo | **teilweise**: FIT/GPX/ZIP von Hand (`core/…/ActivityFiles.kt`, `BulkImport.kt`), dazu Health Connect (`app/…/health/`); kein selbsttätiger Abgleich | täglich für Nutzer eines Radcomputers | ja | groß (Hersteller-Schnittstellen brauchen Konto; Variante: überwachter Ordner) |
| Uhr | Wear-OS-App zeichnet ohne Handy auf | **teilweise**: Begleiterin, schickt Puls und GPS ans Handy (`wear/…/comm/SensorSender.kt`); ohne Handy keine Tour | wöchentlich | ja | mittel bis groß |
| Export | Originaldatei, GPX, TCX, Massenexport | **teilweise**: GPX mit Messwerten und Backup (`core/…/Export.kt`). **FIT-Export fehlt** – `core/…/Fit.kt` enthält nur den Leser („Kein Encoder“), README und `docs/auswertung.md` nennen ihn trotzdem | selten | nein | klein bis mittel |
| Upload zu Strava | – | **teilweise**: Code da (`app/…/strava/`, `core/…/StravaClient.kt`), nur in Builds mit Schlüssel; Freischaltung offen (`docs/TODO.md`) | täglich für Umsteiger | – | Aufgabe des Eigentümers |
| Segmente | fremde Segmente, Bestenlisten, Live-Segmente | **teilweise**: eigene Anstiege werden erkannt, mit Bestzeit, Rang und Abstand (`core/…/RideSegments.kt`, `app/…/data/SegmentStore.kt`); „Diese Runde“ vergleicht ganze Strecken (`core/…/RouteMatch.kt`). Keine frei angelegten Segmente, keine Anzeige während der Fahrt; Bestenlisten siehe „Bewusst nicht“ | wöchentlich | ja | mittel |
| Bestleistungen über Dauer, Leistungskurve | Kurve 1 s bis Stunden, FTP-Schätzung (Abo) | **teilweise**: nur bestes 20-Minuten-Mittel für die FTP (`core/…/TrainingLoad.kt` `bestTwentyMinuteMeanW`); Tempo- und Pulsverlauf je Tour (`core/…/RideCurves.kt`); keine Kurve über Dauern | wöchentlich mit Leistungsmesser | ja | mittel |
| Zeit in Zonen je Tour | Puls- und Leistungszonen (Abo) | **teilweise**: Zonenmodelle gerechnet (`core/…/TrainingLoad.kt` `TrainingZones`), in der Tour-Ansicht nicht gezeigt (keine Zonen-Texte in `strings_rides.xml`) | wöchentlich | ja | klein |
| Fitness, Ermüdung, Form | Abo | **da** (`core/…/PerformanceManagement.kt`, `app/…/ui/training/PmcChart.kt`) | wöchentlich | nein | – |
| Tagesbereitschaft | nicht vorhanden | **da** (`core/…/Readiness.kt`) | täglich | nein | – |
| Entkopplung, VO₂max-Schätzung | – | **da** (`app/…/ui/rides/RideDetailScreen.kt`) | selten | nein | – |
| Trainingslog, Kalender | Trainingslog (Abo) | **teilweise**: Wochenbalken über zwölf Wochen und Tourenliste (`app/…/ui/training/ProgressContent.kt`); keine Kalendersicht | wöchentlich | nein | klein bis mittel |
| Wochen-, Jahresziel, Bestwerte, Serie | eigene Ziele (Abo), Streaks | **da** (`core/…/WeeklyGoal.kt`, `Stats.kt`, `Motivation.kt`) | täglich | nein | – |
| Monats-Challenges, Abzeichen | Challenges, Trophäen | **da**, aus der eigenen Historie: Challenges (`core/…/Challenges.kt`), 30 Abzeichen mit Stufen (`core/…/Achievements.kt`, `docs/erfolge.md`) | wöchentlich | nein | – |
| Jahresrückblick | Abo | **fehlt**; Wochenrückblick da (`core/…/WeeklyRecap.kt`) | selten | nein | klein |
| Räder und Verschleiß | Räder mit Kilometerstand | **fehlt**: kein Rad an der Tour (kein Modell in `core/…/Models.kt`; „Garage“ ist nur das Aussehen des Avatars) | wöchentlich | ja | klein bis mittel |
| Fotos, Notizen an der Tour | Fotos, Beschreibung | **fehlt** (nur Name) | wöchentlich | nein | klein |
| Teilen | Feed | **teilweise**: Bild im Hoch- und Quadratformat, Start und Ziel ausgeblendet (`app/…/ui/rides/ShareCard.kt`, `core/…/TrackPrivacy.kt`); GPX | wöchentlich | – | – |
| Privatzone beim Export | Start/Ziel ausblenden | **teilweise**: nur im Bild; GPX und Strava-Upload gehen ungekürzt hinaus (`trimTrackEnds` wird nur in `ShareCard.kt` benutzt) | wöchentlich | nein | klein |
| Feed, Kudos, Kommentare, Clubs | Kern von Strava | **fehlt**; siehe „Bewusst nicht“ | täglich | ja | – |
| Wetter in der Tour | Abo | **fehlt** | selten | nein | klein |
| Zusammenfassung in Worten | KI-Text (Abo) | **teilweise**: „Was die Tour gebracht hat“, regelbasiert auf dem Gerät (`core/…/RideImpact.kt`) | wöchentlich | nein | – |
| Unfallerkennung | bei Strava nicht belegt | **fehlt** | selten | nein | mittel |

## Säule 3: Drinnen fahren (Vorbild Zwift)

Alles in dieser Säule ist laut README und `docs/indoor.md` Prototyp und auf keinem Gerät gelaufen.

| Funktion | Vorbild | Stand in Trailscape (Beleg) | Alltag | Fremdgehen | Aufwand |
|---|---|---|---|---|---|
| Trainer verbinden | Bluetooth, ANT+, WLAN/Kabel, Brücke über Zweitgerät | **teilweise**: nur Bluetooth-FTMS (`core/…/indoor/FtmsCodec.kt`, `app/…/sensors/indoor/IndoorGeraeteLogik.kt`); kein ANT+, kein WLAN, keine herstellereigenen Protokolle | täglich | ja, wenn der eigene Trainer kein FTMS spricht | mittel je Protokoll |
| Trainer ohne Steuerung, Leistungsmesser als Quelle | Leistungs- oder Tempoquelle genügt | **fehlt**: Trainer-Rolle setzt den FTMS-Dienst voraus (`indoorRollenAusWerbung`) | täglich für Betroffene | ja | klein bis mittel (Parser für Leistung ist da) |
| Steigungssimulation, Fahrgefühl | Trainer-Schwierigkeit einstellbar | **da**: Steigung samt Belag, Fahrgefühl einstellbar (`core/…/indoor/IndoorPhysics.kt`, `RideFeel.kt`, `FeelCalibration.kt`) | täglich | nein | – |
| Virtuelles Schalten | eigene Taster und Lenker | **teilweise**: virtuelle Gänge im HUD, ein Schalttaster „best effort“ (`core/…/indoor/VirtualGears.kt`, `ZwiftClickShifter.kt`) | täglich | nein | – |
| ERG-Modus | ja | **da** (`core/…/indoor/ErgWorkouts.kt`, `IndoorSessionImpl.kt`) | wöchentlich | nein | – |
| Welten und Strecken | mehrere Welten, Anstiege berühmter Berge | **da, anders**: eigene Strecken, zehn bekannte Abschnitte, erzeugte Welten (`core/…/indoor/world/`, `indoor/catalog/`, `indoor/generated/`); Luftbild nur in einem Bundesland | täglich | nein, wenn die Darstellung trägt | – |
| Fahrt läuft im Hintergrund weiter | ja | **fehlt**: Verlassen der App pausiert (`docs/TODO.md`, `docs/indoor/app.md`) | täglich | ja (Musik, Video, Nachricht) | mittel |
| Großer Bildschirm | PC, Tablet, TV-Box; Handy als Fernbedienung | **fehlt**: nur Handy; kein Tablet-Layout, keine Ausgabe auf den Fernseher gefunden | täglich | ja | mittel bis groß |
| Mitfahrer, Tempomacher | Tempogruppen rund um die Uhr, Geist | **teilweise**: Geist mit fester Leistung, eigene frühere Fahrt, geteilte Herausforderung (`core/…/indoor/IndoorSession.kt` `GhostSpec`); keine Gruppe von Tempomachern | täglich | ja | mittel (lokal simulierbar, ohne Server) |
| Gruppenfahrt in Echtzeit | Events, Verabredungen | **teilweise**: Protokoll, Client und Server da, **ohne Oberfläche** (`core/…/room/`, `app/…/room/`, `server/rooms.mjs`, `INDOOR_ROOM_AVAILABLE = false` in `app/…/ui/AppNavigation.kt`) | wöchentlich | ja | mittel (Oberfläche) |
| Windschatten | ja | **teilweise**: gerechnet für den Raum (`core/…/room/Drafting.kt`), ohne Oberfläche wirkungslos | wöchentlich | nein | hängt am Raum |
| Rennen, Kategorien, Wertung | Rennen rund um die Uhr, Punktzahl | **fehlt**; siehe „Bewusst nicht“ | wöchentlich für Rennfahrer | ja | – |
| Zeitversetzt gegeneinander | Geist anderer Fahrer | **da, ohne Server**: Herausforderung als Datei (`core/…/challenge/Challenge.kt`, `docs/herausforderung.md`) | selten | nein | – |
| Bergwertung, Bestzeiten an Anstiegen | Segment-Wertungen | **da**, nur eigene (`core/…/indoor/climb/ClimbBests.kt`, `docs/indoor/bergwertung.md`) | wöchentlich | nein | – |
| Workout-Bibliothek | Tausende Workouts | **fehlt**: nur die Einheit des Tages, vier Arten (Tempo, Sweet Spot, Schwelle, VO₂max) plus Grundlage (`core/…/PlanWorkouts.kt` `WorkoutKind`, `app/…/ui/indoor/IndoorDayToday.kt`) | wöchentlich | ja | klein bis mittel (Vorlagen sind lokale Daten) |
| Workout-Editor, Import | Editor, Datei-Import | **fehlt**: Umweg nur über ein Plan-Rezept (`core/…/PlanRecipe.kt`); kein Leser für verbreitete Workout-Formate | wöchentlich | ja | mittel |
| FTP-Test | 20-Minuten-Test, Stufentest, Erkennung | **teilweise**: 20-Minuten-Test als Planeinheit, im ERG-Modus nur Bestätigung (`core/…/indoor/ErgWorkouts.kt`); FTP-Schätzung aus Fahrten (`resolveEftp`); kein Stufentest | selten | nein | klein bis mittel |
| Stufen, Freischaltungen, Avatar | Punkte, Stufen, Ausrüstung | **teilweise**: Abzeichen und Stufen (`core/…/Achievements.kt`), Avatar-Farben (`core/…/garage/`); Freischaltungen nicht verdrahtet (`docs/ux/umbau.md`) | wöchentlich | nein | klein |
| Speichern, Export | Upload zu vielen Diensten, FIT | **teilweise**: zählt in Belastung und Zielen (`docs/auswertung.md`), GPX, Strava mit Trainer-Kennzeichen; FIT fehlt (siehe Säule 2) | täglich | nein | – |
| Ohne Netz fahren | praktisch nein | **da**: geladene Welten fahren offline | selten | nein | – |

## Säule 4: Plan und Steuerung (Vorbilder TrainerRoad, JOIN)

| Funktion | Vorbild | Stand in Trailscape (Beleg) | Alltag | Fremdgehen | Aufwand |
|---|---|---|---|---|---|
| Plan aus Ziel | Ereignis oder Fitnessziel, Phasen, Volumen | **da**: Ereignis (mit Zielzeit) oder Fitness, Steigerung, Entlastungswochen, Taper (`core/…/Training.kt`, `PlanWeeks.kt`) | wöchentlich | nein | – |
| Mehrere Ziele mit Rang | A-/B-Ereignisse | **fehlt**: ein Ziel | selten | nein | mittel |
| Verfügbarkeit | Tage und Stunden; bei JOIN täglich änderbar | **teilweise**: Trainingstage, Tag der langen Tour, Wochenstunden als feste Vorgabe (`core/…/PlanPreferences.kt`); keine Änderung für einzelne Tage oder Wochen | wöchentlich | ja | mittel |
| Einheit verschieben, tauschen, ersetzen | Kalender mit Verschieben; Alternativen leichter/kürzer | **fehlt**: Toleranz von einem Tag bei der Zuordnung (`core/…/TrainingPlanProgress.kt`), sonst fester Tag | wöchentlich | ja | mittel |
| Plan passt sich an | Neuberechnung nach verpassten oder anderen Einheiten | **teilweise**: Status je Einheit wird berechnet, Restwochen werden skaliert, wenn man deutlich unter Soll lag (`weekSessionProgress`, `adaptPlan`); die Tagesform dämpft Distanz und Höhenmeter (`core/…/TodayRoute.kt`); kein Fortschritt je Intervallart | wöchentlich | ja (teils) | mittel bis groß |
| Befinden, gefühlte Anstrengung | Abfrage nach jeder Einheit | **teilweise**: Last aus Anstrengung ist gerechnet (`LoadSource.RPE` in `core/…/TrainingLoad.kt`); eine Abfrage nach der Fahrt habe ich nicht gefunden | täglich | nein | klein |
| Tagesbereitschaft aus Körperwerten | bei TrainerRoad Ermüdungswarnung; bei JOIN Selbstauskunft | **da, stärker**: Ruhepuls, HRV, Schlaf aus Health Connect (`core/…/Readiness.kt`, `VitalsHistory.kt`) | täglich | nein | – |
| Einheit drinnen fahren | Workout-Player mit ERG | **da** (Prototyp): Einheit des Tages im ERG-Modus (`core/…/indoor/ErgWorkouts.kt`) | wöchentlich | nein, wenn Indoor trägt | – |
| Einheit draußen fahren | Workout an den Radcomputer; Ansagen | **teilweise**: passende Runde zur Einheit, Zielbereich in der Planansicht (`core/…/SessionTarget.kt`, `app/…/ui/training/SessionTargets.kt`); im Fahrmodus und auf der Uhr nur Wochenziel und Bestweite (`core/…/LiveZiel.kt`), keine Intervallführung, kein Zielbereich | wöchentlich | ja | mittel |
| Schnelle Einheit ohne Plan | Vorschlag nach verfügbarer Zeit | **teilweise**: ohne Plan ein Wochenziel und eine Runde nach Zeit (`core/…/WeeklyGoal.kt`, `FirstRound.kt`); kein Workout-Vorschlag | wöchentlich | ja | klein (mit Bibliothek) |
| FTP | Erkennung, Vorhersage | **teilweise**: Schätzung aus bestem 20-Minuten-Mittel, Herkunft wird benannt (`docs/auswertung.md`) | selten | nein | – |
| Kalender mit Wochenlast | Saisonkalender | **teilweise**: Wochenkarten und Fortschritt (`app/…/ui/training/WeekCard.kt`, `PlanSection.kt`); keine Monats- oder Saisonsicht | wöchentlich | nein | klein bis mittel |
| Wetter im Wochenplan | nicht vorhanden | **da**: Tage werden nach Vorhersage gelegt (`core/…/WeekPlanWeather.kt`) | wöchentlich | nein | – |
| Pläne tauschen | Bibliothek im Konto | **da, ohne Server**: Plan-Rezept als Datei (`core/…/PlanRecipe.kt`, `docs/plan-rezepte.md`) | selten | nein | – |
| Kraft, Laufen, Ernährung | bei JOIN Laufen; Kraft und Ernährung nicht belegt | **fehlt** | selten | nein | – |
| Erinnerungen | Mail oder Push vom Server | **da**, lokal (`app/…/reminder/`) | täglich | nein | – |

## Bewusst nicht

Diese Funktionen brauchen ein Konto oder fremde Server. Wo es eine Variante ohne gibt, steht sie dabei.

| Funktion | Warum nicht | Variante ohne Konto |
|---|---|---|
| Feed, Folgen, Kudos, Kommentare, Clubs | zentrale Plattform mit Profilen | Bild oder Datei teilen (vorhanden); Gruppe auf eigenem Server (`docs/konzept-spiel-community.md`, Stufe C1) |
| Öffentliche Segmente mit Bestenlisten, Kronen | Daten aller Fahrer auf einem Server | eigene Segmente und Rangliste gegen sich selbst (vorhanden); Herausforderung als Datei; Rangliste einer Gruppe auf eigenem Server |
| Globale Heatmap, Beliebtheit von Wegen | entsteht aus den Spuren aller Nutzer | eigene gefahrene Wege (vorhanden); offene Kartendaten zu Radrouten und Belag als Ersatzsignal |
| Highlights, Fotos und Tipps der Community | nutzererzeugte Inhalte | Orte aus offenen Kartendaten; eigene Merkpunkte lokal |
| Kuratierte Touren und Sammlungen | redaktioneller Bestand auf einem Server | Routen als Datei tauschen; ausgeschilderte Radrouten aus offenen Kartendaten |
| Offene Welt mit vielen Fahrern, Rennen, Wertungspunkte | Dauerbetrieb zentraler Server | verabredeter Raum auf eigenem Server (Protokoll vorhanden); lokal simulierte Mitfahrer |
| Live-Standort an Kontakte | Vermittlung über einen Server | Standort über den eigenen Sync-Server; oder die Standortfreigabe des Telefons |
| Direkter Abgleich mit Hersteller-Clouds (Routen hin, Fahrten zurück) | Konto beim Hersteller, Schlüssel und Vertrag als App-Anbieter | Datei: Kurs und Workout als FIT exportieren, Fahrten aus einem Ordner einlesen; Health Connect |
| Plan-Berechnung und FTP-Vorhersage auf dem Server | Modell aus den Daten vieler Sportler | Regeln auf dem Gerät (vorhanden), mit offengelegter Herleitung |
| Web-Planer mit Abgleich | Konto oder Server | Planer als Seite des eigenen Sync-Servers; oder Tablet-Layout der App |
| Push-Mitteilungen vom Server | Push-Dienst | lokale Erinnerungen (vorhanden) |

Der Strava-Upload ist ein Grenzfall: Er braucht ein fremdes Konto, ist aber abgeschaltet, bis der Nutzer ihn einschaltet. Seit Juni 2026 verlangt Strava vom Entwickler ein eigenes Abo, und neue Apps sind ohne Prüfung auf zehn Sportler begrenzt.

## Unsicher / nicht prüfbar

**Am Code nicht abschließend geklärt**

- Ob sich die Datenfelder im Fahrmodus anpassen lassen. Die Kacheln für Puls, Leistung und Trittfrequenz gibt es (`app/…/ui/map/LiveSensorTiles.kt`); eine Einstellung dafür habe ich nicht gefunden, aber nicht jede Seite gelesen.
- Ob es irgendwo eine Eingabe für die gefühlte Anstrengung gibt. Gefunden habe ich nur die Rechnung und einen Erklärtext.
- Ob die Intensitätsverteilung über mehrere Wochen in „Deine Form“ gezeigt wird. Die Rechnung ist in `core/…/TrainingLoad.kt`; die Anzeige habe ich nicht verfolgt.
- Wie gut der öffentliche Routing-Server und das Routing auf dem Gerät im Alltag zusammenspielen (`core/…/OfflineFirstRouting.kt`).
- `docs/ux/umbau.md` sagt selbst: Umbau und Einhängen sind ohne Compiler entstanden. Ob `origin/main` baut, habe ich nicht geprüft.

**Widerspruch zwischen Doku und Code**

- README („als GPX/FIT exportierbar“) und `docs/auswertung.md` („GPX/FIT-Export“) nennen einen FIT-Export. `core/…/Fit.kt` enthält nur einen Leser und sagt ausdrücklich „Kein Encoder“.

**In der Netzrecherche nicht belegt**

- komoot: eigenes Gravel-Profil nur indirekt belegt; automatische Rundtour-Vorschläge, Bluetooth-Sensoren in der Handy-App und Unfallerkennung nicht belegt; aktueller Preis des Weltpakets offen. Zur Frage, ob der Geräte-Sync für Neukunden ein Abo braucht, widersprechen sich Hilfecenter (gestaffelt nach freigeschalteter Region) und ein Fachbericht vom März 2025 (Abo-Pflicht).
- Strava: Unfallerkennung, Gruppenaktivitäten und strukturierte Workouts in der Handy-Aufzeichnung nicht belegt. Was bei Segmenten, Clubs und Challenges gratis bleibt, stammt nur aus Drittquellen. Sprach-Abbiegehinweise sind angekündigt, aber nicht fürs Rad. Den Text der API-Vereinbarung konnte die Recherche nicht auslesen.
- Zwift: ANT+ unter Android, die Namen der unterstützten Trainerprotokolle, Verabredungen und Club-Events sowie Details der Windschatten-Rechnung sind nicht aus offizieller Quelle belegt. Auf dem Android-Handy fehlt laut Hilfe der Workout-Editor.
- TrainerRoad: Leistungskurve, Stufentest und Workout-Bibliothek nur aus Suchtreffern; Offline-Betrieb nicht belegt. JOIN: Kraft und Ernährung nicht belegt; die Dauer der Testphase ist widersprüchlich angegeben.

## Was nur der Eigentümer am Gerät beurteilen kann

1. **Trägt die Navigation eine echte Ausfahrt?** Kommen die Ansagen aus der Geometrie rechtzeitig und an den richtigen Stellen (Kreisverkehr, Gabelung, Serpentine)? Wie oft verlässt man die Route ungewollt, und wie sehr fehlt dann das Umplanen?
2. **Sind die gebauten Runden gut?** Ein Dutzend Runden in bekanntem Gelände: Belag wie versprochen, keine Stichstrecken, Wind-Logik spürbar. Das entscheidet, ob Lücke 6 (Entdecken) überhaupt schmerzt.
3. **Hält Indoor eine Stunde durch?** Bildrate, Wärme, Speicher; Trainer koppeln, Steigung und ERG fühlen sich richtig an; Schalttaster; Trainer, Taster und Pulsgurt gleichzeitig (Prüfliste in `docs/TODO.md` und `docs/geraete-pruefliste.md`).
4. **Reicht das Handy als Bildschirm auf der Rolle?** Oder ist ohne Tablet oder Fernseher nach zwei Fahrten Schluss.
5. **Wie stört die Pause beim Verlassen der App?** Musik wechseln, Nachricht lesen – im Alltag auf der Rolle ständig.
6. **Bluetooth-Sensoren im Freien.** Wiederverbinden nach Abriss, doppelte Werte, Akku über vier Stunden mit Karte und Sensoren.
7. **Stimmt die Tagesbereitschaft mit dem eigenen Gefühl überein?** Nach zwei bis drei Wochen: Empfiehlt die App Ruhe, wenn die Beine schwer sind?
8. **Fühlt sich der Plan im Alltag starr an?** Wie oft möchte man eine Einheit verschieben oder tauschen – das bestimmt den Rang von Lücke 9.
9. **Offline-Karte auf einer echten Tour ohne Netz.** Reichen 250 Kacheln und der eine Stil für die übliche Runde?
10. **Fehlt das Publikum?** Nach vier Wochen ohne Feed und Bestenlisten: Genügen Serie, Abzeichen und Bestwerte, oder zieht es zurück zu Strava? Das ist die eine Frage, die keine Funktion beantwortet.
11. **Welche Hardware ist gesetzt?** Wer mit Radcomputer fährt, für den ist Lücke 2 die größte; wer nur mit Handy und Uhr fährt, für den zählt die eigenständige Uhr mehr.
12. **Lesbarkeit im Fahrmodus** bei Sonne, mit Handschuhen, bei Regen auf dem Bildschirm.

## Quellen

Abruf jeweils 25.11.2026.

**komoot**

- Tarife, Karten und Abo: https://support.komoot.com/hc/en-us/articles/10163258809626-komoot-plans-Maps-and-Premium
- Planen im Web: https://support.komoot.com/hc/en-us/articles/10194270667034-Plan-routes-on-the-website
- Neuerungen 2026: https://support.komoot.com/hc/en-us/articles/10621431252250-All-komoot-Feature-Releases-2026
- Heatmaps: https://support.komoot.com/hc/en-us/articles/9468742939802-Heatmaps-Frequently-Asked-Questions-FAQ
- Offline: https://support.komoot.com/hc/en-us/articles/10356476920986-Download-routes-and-maps-for-offline-use
- Geräte: https://support.komoot.com/hc/en-us/articles/10346515564058-Use-komoot-on-connected-devices
- Direkte Übergabe an Geräte: https://support.komoot.com/hc/en-us/articles/10346633239834-Live-Sync-Sending-routes-directly-to-your-connected-device
- Import und Export: https://support.komoot.com/hc/en-us/articles/10115477099674-Export-and-import-Routes-and-Activities
- Gruppen: https://support.komoot.com/hc/en-us/articles/10374965887130-Organize-and-manage-group-activities
- Abo-Umfang, Live-Standort: https://www.komoot.com/premium , https://www.komoot.com/premium/live
- Drittquellen zu Preisen und Bezahlschranken: https://www.bikeradar.com/news/komoot-redesign-2025 , https://www.dcrainmaker.com/2025/03/komoots-expanded-paywalls-trying-to-make-sense-of-it.html

**Strava**

- Abo-Umfang: https://support.strava.com/en-us/articles/15402044-what-features-are-included-in-a-strava-subscription
- Sensoren: https://support.strava.com/en-us/articles/15401744-pairing-sensors-with-the-strava-app
- Auto-Pause: https://support.strava.com/en-us/articles/15402141-auto-pause
- Live-Standort: https://support.strava.com/en-us/articles/15401829-strava-beacon
- Wear OS: https://support.strava.com/en-us/articles/17828383-how-do-i-use-the-strava-app-on-my-wearos-watch
- Import: https://support.strava.com/en-us/articles/15402066-how-do-i-get-my-activities-to-strava
- Leistungskurve, Zonen: https://support.strava.com/en-us/articles/15402048-how-do-i-use-my-best-efforts-power-curve-on-strava , https://support.strava.com/en-us/articles/15401569-training-zones-on-strava
- KI-Zusammenfassung: https://support.strava.com/en-us/articles/15401629-athlete-intelligence-on-strava
- Ausrüstung: https://support.strava.com/en-us/articles/15402143-how-do-i-add-gear-to-my-activities-on-strava
- Heatmaps: https://support.strava.com/en-us/articles/16046277-a-guide-to-strava-heatmaps
- Routen, Offline-Modus, angekündigte Ansagen: https://press.strava.com/articles/strava-makes-route-discovery-easier-with-improved-intuitive-search-expanded-route-inventory-turn-by-turn-audio-coming-soon
- Route folgen: https://support.strava.com/en-us/articles/15401749-how-do-i-follow-a-route-on-strava
- Rad-Trainingspläne: https://support.strava.com/en-us/articles/15401867-training-plans-for-cyclists
- Export: https://support.strava.com/en-us/articles/15401919-how-do-i-export-my-strava-data
- Kartensichtbarkeit: https://support.strava.com/en-us/articles/15402012-how-do-i-edit-my-map-visibility-on-strava
- API: https://communityhub.strava.com/developers-knowledge-base-14/strava-api-faq-12906 , https://developers.strava.com/docs/rate-limits/

**Zwift**

- Preis und Umfang: https://www.zwift.com/news/33635-how-much-does-zwift-cost-and-whats-included-everything-you-need-to-know
- Höhere Abo-Stufe (Drittquelle): https://zwiftinsider.com/zwift-plus-launch/
- Plattformen: https://support.zwift.com/en_us/supported-devices-to-run-zwift-H1Cj9QbeB
- Geräte verbinden: https://support.zwift.com/en_us/connecting-your-devices-for-cycling-HJIFDrw5S
- Brücke über die Begleit-App: https://support.zwift.com/using-the-zwift-companion-app-as-a-bridge-ByAnUzlLj
- Eigene Workouts: https://support.zwift.com/en_us/custom-workouts-ryGOTVEPs
- FTP-Tests: https://support.zwift.com/ftp-test-HypJnI_NH
- Fremdplattformen, Export: https://support.zwift.com/en_us/zwift-and-third-party-platforms-SypU0LdVr
- Geist: https://support.zwift.com/de/holoreplay-SJUi7Xqzi
- Rennwertung: https://support.zwift.com/en_us/racing-score-faq-BkG9_Rqrh
- Bestenlisten: https://support.zwift.com/en_us/leaderboards-S11PywdNB
- Fitness-Verlauf: https://www.zwift.com/fitness-tracking
- Drittquellen: https://zwiftinsider.com/robopacers/ , https://zwiftinsider.com/virtual-shifting/ , https://zwiftinsider.com/using-the-trainer-difficulty-setting-in-zwift/ , https://zwiftinsider.com/zwift-alone/

**Trainingsapps**

- TrainerRoad, Preis: https://www.trainerroad.com/pricing/
- Planaufbau: https://support.trainerroad.com/hc/trainerroad-support/articles/360037923191-plan-builder-overview
- Neue Planungsfunktionen 2026: https://www.trainerroad.com/blog/introducing-trainerroad-ai/
- Tagesvorschlag: https://support.trainerroad.com/hc/trainerroad-support/articles/360057075531-trainnow-overview
- Trainer-Modi: https://support.trainerroad.com/hc/trainerroad-support/articles/360024069532-smart-trainer-modes-explained
- Geräte: https://support.trainerroad.com/hc/trainerroad-support/articles/201682804-compatible-devices-and-sensors
- Einheiten draußen: https://support.trainerroad.com/hc/trainerroad-support/articles/360024352332-outside-workouts
- JOIN, Preis und Umfang: https://join.cc/pricing , https://join.cc/why-join , https://join.cc/training-plans
- JOIN, Geräte: https://help.join.cc/hc/en-150/articles/14181003186449-Compatible-devices-Workout-Player
- JOIN, Anbindungen: https://help.join.cc/hc/en-150/articles/4404775688081-Integrations
- intervals.icu: https://www.intervals.icu/pricing/ , https://www.intervals.icu/features/fitness-chart/ , https://www.intervals.icu/features/wellness/
- TrainingPeaks: https://www.trainingpeaks.com/blog/what-you-get-with-trainingpeaks-premium/
