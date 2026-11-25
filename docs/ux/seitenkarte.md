# Seitenkarte der App

Stand 25.11.2026, gelesen aus dem Code auf `main` (nach PR #112) und den elf Branches, die als Nächstes gemergt werden. Nichts davon lief auf einem Gerät. Das klickbare Artefakt mit Karte, Vorschlag und Prototyp liegt als Quelltext in `docs/ux/artefakt/`; diese Datei wird aus denselben Daten erzeugt.

Tiefe = Tipps ab App-Start auf „Heute“ auf dem kürzesten Weg. „neu“ = kommt mit einem der Branches.

## Überblick

- 80 Knoten insgesamt
- 10 Einstiege von außen
- 34 Seiten und Vollbild-Zustände
- 30 Blätter und Dialoge
- 12 kommen mit den Branches
- 47 mit Bedienproblem
- 133 Verbindungen

## Bestand je Bereich

### Von außen

| Tiefe | Art | Name | Zweck | Hauptaktion | Wege weg | Im Code |
|---|---|---|---|---|---|---|
| außen | von außen | App-Symbol | Startet die App auf „Heute“; beim ersten Start davor die Einführung. | App öffnen | Heute; Einführung (5 Seiten) (erster Start) | ui/TrailscapeApp.kt:435 |
| außen | von außen | Öffnen mit / Teilen (GPX, FIT, ZIP) | Eine Datei aus einer anderen App landet als Tour oder Planung in Trailscape. | Datei übernehmen | Dialog „Als Planung geöffnet“; Import-Ergebnis | ImportActivity.kt, AndroidManifest.xml:189 |
| außen | von außen | Plan-Rezept öffnen | Eine Plan-Datei aus einer anderen App öffnet die Import-Vorschau. | Plan übernehmen | Plan-Rezept (teilen, speichern, öffnen) | PlanRecipeImportActivity.kt |
| außen | von außen | Rückkehr von Strava | Der Browser kommt nach der Anmeldung zurück in die App. | Verbindung abschließen | Strava | strava/StravaAuthActivity |
| außen | von außen | Widget „Trailscape Serie“ | Wochenserie und Wochenziel auf dem Startbildschirm. | App öffnen | Heute | widget/TrailscapeWidget.kt:298 |
| außen | von außen | Erinnerung | Tagesplan, Wochenrückblick oder Anstupser als Benachrichtigung. | „Heute“ öffnen | Heute | reminder/ReminderNotifications.kt:114 |
| außen | von außen | Benachrichtigung Aufzeichnung | Läuft während einer Aufzeichnung, mit Pause, Fortsetzen und Beenden. | Zurück in die App | Fahrmodus | record/RecordingService.kt:1536 |
| außen | von außen | Uhr | Aufzeichnung an der Uhr starten, pausieren, beenden; Kachel und Komplikation „Serie & Wochenziel“. | Aufzeichnung starten | Fahrmodus (startet Aufzeichnung) | wear/ui/RecordingScreen.kt, wear/tile |
| außen | von außen, neu | Herausforderung öffnen | Eine Herausforderungs-Datei aus einer anderen App öffnet ein kleines Importfenster. | Speichern | Abschnitt „Herausforderungen“ | Branch spiel-3: challenge/ChallengeImportActivity.kt |
| außen | von außen, neu | Benachrichtigung Indoor-Fahrt | Indoor-Fahrt läuft im Hintergrund weiter, mit Pause, Weiter, Beenden. | Zurück in die Fahrt | Indoor-Fahrt | Branch idee-05: MainActivity.kt |

Probleme:

- **Widget „Trailscape Serie“:** Der Knopf „Fahren“ im Widget öffnet nur die App, er startet nichts.
- **Benachrichtigung Aufzeichnung:** Hier heißt es „Fortsetzen“, in der App „Weiter“.

### Heute und Hülle

| Tiefe | Art | Name | Zweck | Hauptaktion | Wege weg | Im Code |
|---|---|---|---|---|---|---|
| 0 | Zustand | Einführung (5 Seiten) | Erklärt die App, fragt Alter und Gewicht, bietet Health Connect und eine erste Runde an. | Runde bauen / Los geht's | Heute (Los geht's); Blatt „Passende Runde“ (Runde bauen) | ui/onboarding/OnboardingScreen.kt:124 |
| 1 | Dialog | Dialog „Losfahren“ | Fragt nach dem Tipp auf den runden Knopf „Fahren“, was gefahren werden soll. | Aufzeichnung starten / Mit Route starten | Fahrmodus (Aufzeichnung starten); Navigation (Mit Route starten); Blatt „Passende Runde“ (Passende Runde bauen); Indoor-Training (Indoor auf dem Rollentrainer fahren) | ui/ReadyToRideDialog.kt:96 |
| 0 | Hauptbereich | Heute | Beantwortet „Was fahre ich heute?“: Tagesform, Empfehlung, Woche, Ziele. | Runde für heute bauen | Dialog „Losfahren“ (Knopf „Fahren“); Karte (Tab); Verlauf (Tab); Training · Fortschritt (Tab); Einstellungen (Zahnrad); Blatt „Passende Runde“ (Runde für heute bauen); Blatt „Warum diese Empfehlung?“; Dialog „Jahresziel“ (Jahresziel setzen); Karte „Einheit des Tages“; Karte „Wo fährst du heute?“; Training · Fortschritt (Dein Ziel / Fortschritt öffnen); Uhr & Gesundheitsdaten (Uhr verbinden); Import & Backup (Touren importieren) | ui/today/TodayScreen.kt:151 |
| 0 | Abschnitt | Karte „Einheit des Tages“ | Bietet die Einheit des Tages auf einer passenden Indoor-Strecke an; sonst allgemeiner Einstieg „Indoor-Training“. | Fahren | Indoor-Training (Fahren); Blatt „Passende Runde“ (Runde planen) | ui/indoor/IndoorDayToday.kt:40 |
| 0 | Abschnitt, neu | Karte „Wo fährst du heute?“ | Schlägt draußen oder drinnen vor, mit Begründung. | Runde planen | Blatt „Passende Runde“ (Runde planen) | Branch idee-02: ui/today/TodayPlaceCard.kt |
| 1 | Blatt | Blatt „Warum diese Empfehlung?“ | Erklärt die Empfehlung aus Schlaf, Ruhepuls, Erholung und Belastung. | Verstanden | – | ui/today/TodayCards.kt:577 |
| 1 | Dialog | Dialog „Jahresziel“ | Jahresziel in Kilometern setzen oder entfernen. | Speichern | – | ui/progress/ProgressCards.kt:713 |

Probleme:

- **Einführung (5 Seiten):** Die Seite zum Mitbringen alter Touren hat keinen Import-Knopf, nur einen Verweis auf das „+“ im Verlauf.
- **Einführung (5 Seiten):** Ein Ziel oder Trainingsplan wird nicht angeboten.
- **Dialog „Losfahren“:** Indoor steht hier nur als leiser Textknopf, ohne Vorwahl der Einheit des Tages.
- **Dialog „Losfahren“:** „Losfahren“ heißt auch der Knopf in der Planung, auf der Tourkarte und zweimal im Indoor-Ablauf, jedes Mal mit anderer Wirkung.
- **Heute:** Bis zu vier Knöpfe bauen gleichzeitig eine Runde, mit vier verschiedenen Beschriftungen.
- **Heute:** Wochenkarte und Zielkarte führen nicht direkt zum Plan.
- **Heute:** Am Zieltag gibt es keinen Hauptknopf.
- **Karte „Einheit des Tages“:** Derselbe Hauptknopf führt je nach Zustand ins Indoor („Fahren“, „Vorbereiten“) oder auf die Karte („Runde planen“).
- **Karte „Einheit des Tages“:** Der einzige gut sichtbare Einstieg in den ganzen Indoor-Bereich ist diese dritte Karte auf „Heute“.
- **Karte „Einheit des Tages“:** Am Eventtag hat die Karte keine Aktion.
- **Karte „Wo fährst du heute?“:** Bei „drinnen“ gibt es keinen Knopf, nur den Text, dass die Strecke in der Karte darunter steht.
- **Karte „Wo fährst du heute?“:** Noch eine Karte mit „Runde planen“ auf „Heute“.
- **Dialog „Jahresziel“:** Derselbe Dialog hängt auf „Heute“ und im Training.

### Karte

| Tiefe | Art | Name | Zweck | Hauptaktion | Wege weg | Im Code |
|---|---|---|---|---|---|---|
| 1 | Hauptbereich | Karte | Karte mit Blatt „Wohin?“: Ort suchen, Runde bauen, Route planen. | Ort suchen | Ortskarte (suchen oder lange drücken); Blatt „Runde ab hier“ (Runde ab hier); Blatt „Passende Runde“ (Heute N km); Blatt „Karte“ (Ebenen, Kachel-Pille, Offline-Karten); Tourkarte (Gespeicherte Routen) | ui/map/MapScreen.kt, ui/map/ExploreSheet.kt:84 |
| 1 | Blatt | Blatt „Passende Runde“ | Sucht Rundenvorschläge und zeigt Kandidaten. | Übernehmen | Blatt „Route planen“ (Übernehmen) | ui/map/RouteGenerationSheet.kt:149 |
| 2 | Blatt | Blatt „Runde ab hier“ | Länge, Untergrund, „Neu entdecken“ und Wind für eine Rundtour einstellen. | Vorschläge zeigen | Blatt „Passende Runde“ (Vorschläge zeigen) | ui/map/RoundTripSetupSheet.kt:74 |
| 2 | Blatt | Ortskarte | Ein gesuchter oder markierter Punkt als Ziel, Rundenstart oder Wegpunkt. | Route hierher | Blatt „Route planen“ (Route hierher); Blatt „Runde ab hier“ (Runde ab hier) | ui/map/PlaceCard.kt:103 |
| 2 | Blatt | Blatt „Karte“ | Kartenstil, Entdeckt-Kacheln und Offline-Ausschnitt. | Stil wählen | – | ui/map/MapScreen.kt:3929 |
| 2 | Zustand | Fahrmodus | Vollbild mit Tempo, Strecke, Zeit; Pause und Beenden durch Halten. | Pause / Beenden | Navigation (Karte); Karte „Aufzeichnung läuft“ (Zurück); Tourkarte (Beenden (halten)); Hinweise (Akku, Standort, Offline-Routing) (erster Start) | ui/map/RideModeScreen.kt:178 |
| 2 | Zustand | „Wo du überall warst“ | Zeigt alle gefahrenen Spuren auf der Karte. | Zurück zum Verlauf | Verlauf (X) | ui/map/HistorySummarySheet.kt:41 |
| 3 | Blatt | Blatt „Route planen“ | Wegpunkte, Untergrund, Höhenprofil; speichern oder losfahren. | Losfahren | Blatt „Ort suchen“ (Punkt hinzufügen); Dialog „Name der Route“ (Speichern); Navigation (Losfahren); Dialog „Tour teilen“ (Teilen) | ui/map/PlanningPanel.kt:125 |
| 3 | Blatt | Tourkarte | Eine gewählte Tour oder gespeicherte Route auf der Karte. | Nochmal fahren / Losfahren | Navigation (Nochmal fahren / Losfahren); Tourdetail (Auswertung); Dialog „Tour teilen“ (Teilen); Dialog „Tour löschen“ (Löschen) | ui/map/MapPanels.kt:291 |
| 3 | Zustand | Navigation | Abbiegehinweise über der Karte, Leiste mit Pause und Beenden. | Der Route folgen | Fahrmodus (Daten) | ui/map/NavigationHud.kt:103 |
| 3 | Abschnitt | Karte „Aufzeichnung läuft“ | Aufzeichnung außerhalb des Fahrmodus bedienen. | Fahrmodus | Fahrmodus (Fahrmodus) | ui/map/MapPanels.kt:140 |
| 4 | Blatt | Blatt „Ort suchen“ | Wegpunkt per Suche zur Planung hinzufügen. | Treffer wählen | – | ui/map/SearchSheet.kt:117 |
| 4 | Dialog | Dialog „Name der Route“ | Geplante Route benennen und speichern. | Speichern | – | ui/map/MapScreen.kt:4126 |
| 4 | Dialog | Dialog „Tour löschen“ | Rückfrage vor dem Löschen auf der Karte. | Löschen | – | ui/map/MapScreen.kt:3764 |
| 4 | Dialog | Hinweise (Akku, Standort, Offline-Routing) | Erscheinen von selbst: Akku-Ausnahme, Standortfreigabe, Routingdaten laden. | Erlauben / Später | – | ui/map/BatteryNoticeDialog.kt, MapScreen.kt:3728 |

Probleme:

- **Karte:** Kein sichtbarer Einstieg „Route planen“; eine Planung entsteht nur über einen Ort oder einen langen Druck.
- **Karte:** Kein Zahnrad; Einstellungen sind von hier nicht erreichbar.
- **Karte:** Navigationsleiste und Fahren-Knopf verschwinden bei jeder Kartenaufgabe.
- **Blatt „Passende Runde“:** Sieben Einstiege aus der ganzen App führen hierher, mit fünf Beschriftungen.
- **Blatt „Passende Runde“:** Öffnet aus „Heute“ nur das Blatt; die Suche braucht einen weiteren Tipp.
- **Ortskarte:** Einen Punkt auf der Karte setzt nur ein langer Druck; ein einfacher Tipp tut nichts.
- **Ortskarte:** „Als Wegpunkt“ und „+ Als Wegpunkt“ sind dieselbe Funktion.
- **Blatt „Karte“:** Drei Einstiege öffnen dasselbe Blatt; die Zeile „Offline-Karten“ verspricht Gegenden zu laden und zeigt die Stilwahl.
- **Blatt „Karte“:** Offline speichern geht nur mit der Vektorkarte.
- **Fahrmodus:** Pause und Beenden gibt es dreifach: Fahrmodus, Leiste auf der Karte, Karte „Aufzeichnung läuft“.
- **Fahrmodus:** Die Ansicht heißt „Fahrmodus“, „Cockpit“ und „Daten“.
- **Blatt „Route planen“:** „Speichern“ beendet die Planung und räumt die Route von der Karte.
- **Blatt „Route planen“:** Eine liegen gebliebene Route lässt sich nirgends verwerfen (die Funktion hat keinen Knopf).
- **Tourkarte:** Zweiter Ort für Teilen und Löschen; Umbenennen fehlt hier.
- **Tourkarte:** Zeigt nur die fünf neuesten gespeicherten Routen an.
- **Navigation:** Das X beendet nur die Navigation, die Aufzeichnung läuft weiter.
- **Navigation:** Der Lautsprecher schaltet dieselben Sprachansagen wie die Einstellungen.
- **Dialog „Tour löschen“:** Hier mit Rückfrage, im Tourdetail ohne. Heißt auch bei geplanten Routen „Tour löschen“.

### Verlauf

| Tiefe | Art | Name | Zweck | Hauptaktion | Wege weg | Im Code |
|---|---|---|---|---|---|---|
| 1 | Hauptbereich | Verlauf | Alle Touren und Planungen als Liste, mit Suche und Import. | Tour öffnen | Tourdetail (Tour antippen); Menü „Touren importieren“ (+); „Wo du überall warst“ (Alle Touren auf der Karte); Einstellungen (Zahnrad); Fahrmodus (Tour aufzeichnen (leer)) | ui/rides/RidesScreen.kt:103 |
| 2 | Seite | Tourdetail | Karte, Kennzahlen, Auswertung und alle Aktionen einer Tour. | Diese Tour nochmal fahren | Navigation (Diese Tour nochmal fahren); Tourkarte (Karte zeigen); Dialog „Tour umbenennen“ (Umbenennen); Dialog „Tour teilen“ (Teilen); Dialog „In Planung umwandeln?“ (In Planung umwandeln); Strava (Neu verbinden); Indoor-Training (Indoor fahren (neu)); 3D-Vorschau (In 3D ansehen (neu)) | ui/rides/RideDetailScreen.kt:194 |
| 2 | Blatt | Menü „Touren importieren“ | Wahl zwischen GPX-/FIT-Dateien und Archiv. | GPX-/FIT-Dateien | Import-Ergebnis (GPX-/FIT-Dateien); Import & Backup (Archiv) | ui/rides/TourList.kt:437 |
| 3 | Dialog | Dialog „Tour teilen“ | Als Bild (Story, Quadrat) oder GPX teilen. | Teilen | – | ui/rides/ShareRideDialog.kt:89 |
| 3 | Dialog | Dialog „Tour umbenennen“ | Namen der Tour ändern. | Speichern | – | ui/rides/TourList.kt:659 |
| 3 | Dialog | Dialog „In Planung umwandeln?“ | Tour und Planung ineinander umwandeln. | Umwandeln / Übernehmen | – | ui/rides/PlanFlip.kt |
| 3 | Dialog | Dialog „Als Planung geöffnet“ | Sagt nach einem Import, ob die Datei als Planung oder Tour erkannt wurde. | Schließen / umwandeln | Tourdetail | ui/ImportRecognition.kt |
| 3 | Dialog | Import-Ergebnis | Fehler oder Zusammenfassung nach einem Import. | Schließen | – | ui/ActivityImportAction.kt:127 |

Probleme:

- **Verlauf:** Der Tab heißt „Verlauf“, der Inhalt überall „Touren“, an neun Stellen „Fahrt“.
- **Verlauf:** Der Abschnitt „Geplant“ steht über den gefahrenen Touren; die letzte Tour rutscht nach unten.
- **Verlauf:** Vier Aktionen in der Kopfzeile nur als Symbol.
- **Tourdetail:** Bis zu neun Aktionen im scrollenden Inhalt, kein Menü, kein fester Knopf.
- **Tourdetail:** „Löschen“ steht neben „Teilen“ und löscht ohne Rückfrage.
- **Tourdetail:** Zeigt auch bei Planungen „Diese Tour nochmal fahren“.
- **Tourdetail:** Mit den Branches kommen „Indoor fahren“ und „In 3D ansehen“ als weitere Knöpfe dazu.
- **Menü „Touren importieren“:** „Archiv“ springt in die Einstellungen, wo man den Archiv-Knopf noch einmal suchen muss.
- **Menü „Touren importieren“:** Dateiimport gibt es an drei Stellen.
- **Dialog „Tour teilen“:** GPX-Export ist das dritte Segment hinter „Teilen“.
- **Dialog „Tour teilen“:** Strava hat einen eigenen Platz im Tourdetail, sichtbar nur mit verbundenem Konto.

### Training

| Tiefe | Art | Name | Zweck | Hauptaktion | Wege weg | Im Code |
|---|---|---|---|---|---|---|
| 1 | Hauptbereich | Training · Fortschritt | Trend, Challenges, Meilensteine, Jahresziel, Bestwerte, Form, Körperwerte. | keine; reine Auskunft | Training · Plan (Segment „Plan“); Blatt „Deine Form“ (Deine Form); Blatt „Körperwerte“ (Körperwerte); Dialog „Jahresziel“ (Jahresziel); Tourdetail (Bestwert: Tour öffnen); Einstellungen (Zahnrad); Profil (Profil öffnen); Import & Backup (Alte Touren importieren); Karte (Tour aufzeichnen (leer)); Karte „Erfolge“ | ui/training/TrainingScreen.kt:143 |
| 1 | Abschnitt, neu | Karte „Erfolge“ | Zeigt Stufe und Abzeichen. | Erfolge öffnen | Blatt „Erfolge“ | Branch spiel-1: ui/erfolge/ErfolgeScreen.kt:104 |
| 2 | Seite | Training · Plan | Ziel, laufende Woche, alle Wochen, Plan-Rezept. | Ziel festlegen | Blatt „Ziel festlegen“ (Ziel festlegen / Ändern); Blatt „Wie die Prognose entsteht“ (Wie wird das berechnet?); Blatt „Passende Runde“ (Runde); Plan-Rezept (teilen, speichern, öffnen) (Plan-Rezept) | ui/training/PlanSection.kt, GoalCard.kt |
| 2 | Blatt | Blatt „Deine Form“ | Form, Fitness und Belastung im Detail. | Alle Werte | Profil (Zeitbudget) | ui/training/FormCard.kt:202 |
| 2 | Blatt | Blatt „Körperwerte“ | Erklärt Ruhepuls, HRV, Schlaf. | Verstanden | – | ui/training/VitalsTiles.kt:285 |
| 2 | Blatt, neu | Blatt „Erfolge“ | Stufe, Wochenserie mit Pausenwoche, Abzeichen nach Gruppen. | Abzeichen ansehen | – | Branch spiel-1: ui/erfolge/ErfolgeScreen.kt:137 |
| 3 | Blatt | Blatt „Ziel festlegen“ | Event oder Fitnessziel, Trainingstage, Zeitbudget; erzeugt den Plan. | Plan erstellen | Dialog „Trainingsplan löschen“ (Plan löschen) | ui/training/GoalCard.kt:1110 |
| 3 | Blatt | Blatt „Wie die Prognose entsteht“ | Erklärt die Zielzeit-Prognose. | Verstanden | – | ui/training/GoalCard.kt:739 |
| 3 | Dialog | Plan-Rezept (teilen, speichern, öffnen) | Plan oder Einheit als Datei weitergeben; fremde Datei mit Vorschau übernehmen. | Plan teilen / Übernehmen | – | ui/training/PlanRecipeUi.kt |
| 4 | Dialog | Dialog „Trainingsplan löschen“ | Rückfrage. | Löschen | – | ui/training/GoalCard.kt:1538 |

Probleme:

- **Training · Fortschritt:** Öffnet immer auf „Fortschritt“; wer von „Heute“ über „Ziel festlegen“ kommt, sieht kein Ziel.
- **Training · Fortschritt:** „Tour aufzeichnen“ im Leerzustand wechselt nur zur Karte, auf „Heute“ startet derselbe Text die Aufzeichnung.
- **Karte „Erfolge“:** Erfolge liegen im Training, Garage und Herausforderungen im Indoor, obwohl sie zusammengehören.
- **Training · Plan:** Zweiter Tipp nach dem Tab; kein direkter Weg von „Heute“.
- **Training · Plan:** „Runde“ gibt es nur in der heutigen Zeile, unter „Alle Wochen“ nur als Symbol.
- **Blatt „Deine Form“:** Kein Schließen-Knopf; die antippbare Zeitbudget-Zeile sieht nicht wie ein Knopf aus.
- **Blatt „Erfolge“:** Ein- und ausschalten lässt es sich nur in den Einstellungen, Gruppe „Motivation“.
- **Blatt „Ziel festlegen“:** Das Zeitbudget steht auch im Profil.

### Einstellungen (Zahnrad)

| Tiefe | Art | Name | Zweck | Hauptaktion | Wege weg | Im Code |
|---|---|---|---|---|---|---|
| 1 | Seite | Einstellungen | Liste mit 13 Zeilen in drei Gruppen, hinter dem Zahnrad. | Zeile öffnen | Profil; Uhr & Gesundheitsdaten; Sensoren; Aufzeichnung & Ansagen; Erinnerungen; Karten offline; Import & Backup; Strava; Sync mit eigenem Server; Sprache; Über Trailscape; Gruppe „Motivation“; Indoor-Training (Indoor-Training); Indoor-Diagnose (Indoor-Diagnose) | ui/more/MoreScreen.kt:130 |
| 1 | Abschnitt, neu | Gruppe „Motivation“ | Schalter „Erfolge anzeigen“. | Schalter | Karte „Erfolge“ (blendet ein/aus) | Branch spiel-1: ui/more/MoreScreen.kt:414 |
| 2 | Seite | Profil | Alter, Gewicht, Rad, Zeit pro Woche; aufklappbar Puls und FTP. | Felder ausfüllen | – | ui/more/ProfileCard.kt:83 |
| 2 | Seite | Uhr & Gesundheitsdaten | Health Connect verbinden, Touren und Werte holen. | Verbinden / Neue Touren holen | Dialoge der Einstellungen | ui/more/HealthCard.kt:93 |
| 2 | Seite | Sensoren | Puls, Leistung, Trittfrequenz koppeln; darunter derselbe Indoor-Abschnitt für Trainer und Schalttaster. | Nach Sensoren suchen | – | ui/more/SensorsCard.kt:92 |
| 2 | Seite | Aufzeichnung & Ansagen | Auto-Pause, Akku-Ausnahme, Sprachansagen, Vibration. | Schalter | – | ui/more/RecordingCard.kt:53 |
| 2 | Seite | Erinnerungen | Tagesplan, Wochenrückblick, Anstupser mit Uhrzeit. | Schalter | Dialoge der Einstellungen | ui/more/ReminderCard.kt:82 |
| 2 | Seite | Karten offline | Kartenbild verwalten, Routingdaten laden. | keine klare | Dialoge der Einstellungen | ui/more/OfflineMapsCard.kt, OfflineRoutingCard.kt |
| 2 | Seite | Import & Backup | Backup exportieren und importieren, Touren und Archive importieren. | Backup exportieren | Dialoge der Einstellungen | ui/more/BackupCard.kt:119 |
| 2 | Seite | Strava | Verbinden, automatisch hochladen, trennen. | Mit Strava verbinden | Dialoge der Einstellungen | ui/more/StravaCard.kt:58 |
| 2 | Seite | Sync mit eigenem Server | Server-URL und Token, abgleichen. | Mit Server abgleichen | – | ui/more/SyncCard.kt:61 |
| 2 | Seite | Sprache | System, Deutsch, English. | Sprache wählen | – | ui/more/LanguageCard.kt:46 |
| 2 | Seite | Über Trailscape | Grundlage, Einführung, Datenschutz, Updates, Lizenzen. | keine klare | Einführung (5 Seiten) (Einführung erneut ansehen) | ui/more/AboutCard.kt:112 |
| 3 | Dialog | Dialoge der Einstellungen | Uhrzeit, Diagnose-Details, Löschen von Offline-Daten, Strava trennen, Archiv-Import. | Bestätigen | – | ui/more/*Card.kt |

Probleme:

- **Einstellungen:** Die erste Gruppe hat sieben Zeilen ohne Überschrift.
- **Einstellungen:** Die Gruppe „Indoor“ ist keine Einstellung, sondern ein Sprung in einen anderen Bereich.
- **Einstellungen:** Zwei verschiedene Seiten der App heißen „Einstellungen“.
- **Profil:** FTP liegt hinter „Erweitert“, obwohl Training und Indoor damit rechnen und darauf verweisen.
- **Sensoren:** Zwei Suchknöpfe auf einer Seite („Suche beenden“ und „Stopp“).
- **Sensoren:** Trainer koppeln geht hier und im Indoor-Bereich.
- **Aufzeichnung & Ansagen:** Sprachansagen lassen sich auch im Navigations-Kopf schalten.
- **Karten offline:** Kartenbild lässt sich hier nur löschen; laden geht nur auf der Karte, ohne Knopf dorthin.
- **Karten offline:** Rund sechs gleichrangige Bedienelemente.
- **Import & Backup:** Drei Import-Knöpfe nebeneinander.
- **Über Trailscape:** Rund acht gleichrangige Aktionen, darunter die Update-Suche.

### Indoor

| Tiefe | Art | Name | Zweck | Hauptaktion | Wege weg | Im Code |
|---|---|---|---|---|---|---|
| 1 | Seite | Indoor-Training | Trainer prüfen, Strecke wählen, starten. | Fahren | Trainer koppeln (Koppeln / Ändern); Einstellungen (Indoor) (Zahnrad); Streckendetail (bekannte Strecke) (Details); Strecke vorbereiten / Bereit zum Fahren (Fahren); Indoor-Fahrt (Fahren (bekannte Strecke)); Garage (Palette); Abschnitt „Herausforderungen“; Blatt „Passende Runde“ (Runde planen) | ui/indoor/IndoorHomeScreen.kt:99 |
| 1 | Abschnitt, neu | Abschnitt „Herausforderungen“ | Herausforderungs-Dateien wählen und dagegen fahren. | Gegen … fahren | Indoor-Fahrt (Gegen … fahren) | Branch spiel-3: ui/indoor/IndoorChallengeSection.kt:68 |
| 2 | Seite | Trainer koppeln | Trainer und Schalttaster merken, Demo einschalten. | Trainer suchen | – | ui/indoor/IndoorPairScreen.kt:47 |
| 2 | Seite | Einstellungen (Indoor) | Kettenblatt, Gänge, Tageszeit, andere Radfahrer; Erweitert. | Werte einstellen | Strecken auf dem Gerät (Strecken auf dem Gerät); Indoor-Diagnose (Diagnose); Garage (Garage); Fahrgefühl einstellen (Fahrgefühl einstellen); Fahrprotokolle (Fahrprotokolle) | ui/indoor/IndoorSettingsScreen.kt:41 |
| 2 | Seite, neu | Garage | Farben für Fahrer, Kleidung und Rad, Trikotmuster. | Farbe wählen | – | Branch spiel-2: ui/indoor/garage/GarageScreen.kt:65 |
| 2 | Seite | Indoor-Diagnose | 3D-Darstellung prüfen: Testszene, Teststrecke, rund 16 Schalter. | Probefahrt | – | indoor/render/DiagnosticsScreen.kt |
| 3 | Dialog | Streckendetail (bekannte Strecke) | Höhenprofil, Zahlen, Belag, Quellen einer bekannten Strecke. | Diese Strecke fahren / Herunterladen | Indoor-Fahrt (Diese Strecke fahren) | ui/indoor/IndoorCatalogDetail.kt:47 |
| 3 | Seite | Strecke vorbereiten / Bereit zum Fahren | 3D-Ansicht erstellen oder bestätigen; Bau auf eigenem Server. | Vorbereiten / Losfahren | Indoor-Fahrt (Losfahren) | ui/indoor/IndoorWorldScreen.kt:52 |
| 3 | Seite | Strecken auf dem Gerät | Speicher verwalten, Strecken löschen, Quellenangaben. | Löschen | – | ui/indoor/IndoorWorldsScreen.kt:50 |
| 3 | Seite, neu | Fahrgefühl einstellen | Geführter Abgleich von Widerstand und Belag, 2 bis 3 Minuten. | Jetzt auswerten | – | Branch idee-06: ui/indoor/IndoorFeelScreens.kt:142 |
| 3 | Seite, neu | Fahrprotokolle | Protokolldateien je Fahrt ansehen, teilen, löschen. | Ansehen | – | Branch idee-06: ui/indoor/IndoorFeelScreens.kt:282 |
| 4 | Zustand | Indoor-Fahrt | 3D-Ansicht oder Höhenprofil mit Leistung, Tempo, Gang; Karte „Wo startest du?“. | Losfahren, treten | Dialoge der Fahrt; Auswertung (Beenden) | ui/indoor/IndoorRideScreen.kt:131 |
| 4 | Seite, neu | 3D-Vorschau | Strecke als Kamerafahrt ansehen, ohne Trainer. | Wiedergabe | Tourdetail (Zurück) | Branch idee-04: ui/indoor/IndoorPreviewScreen.kt:72 |
| 4 | Dialog | Dialoge der Fahrt | Fahrt angehalten, Geräte, Einheit geschafft, Fahrt verwerfen. | Weiterfahren / Fahrt beenden | – | ui/indoor/IndoorFlowScreen.kt:323, IndoorRideScreen.kt:220 |
| 4 | Seite | Auswertung | Kennzahlen der Indoor-Fahrt, Vergleich; speichern oder verwerfen. | Speichern | Dialog „Herausforderung teilen“ (Herausforderung teilen); Heute (Fertig) | ui/indoor/IndoorSummaryScreen.kt:53 |
| 4 | Dialog, neu | Dialog „Herausforderung teilen“ | Name und Datum wählen, Datei teilen. | Teilen | – | Branch spiel-3: ui/indoor/IndoorChallengeSection.kt:369 |

Probleme:

- **Indoor-Training:** Bis zu neun Ziele, davon drei Startknöpfe: „Fahren“ der Tageskarte, „Fahren“ unten, „Diese Strecke fahren“ im Detail.
- **Indoor-Training:** Wer von „Heute“ mit „Fahren“ kommt, muss hier noch einmal „Fahren“ tippen.
- **Indoor-Training:** Zwei Auswahlmuster in einer Liste; die Aktion zur gewählten Strecke steht weit unten in der Fußleiste.
- **Indoor-Training:** Die Teststrecke steht als erste Zeile in „Deine Strecken“.
- **Abschnitt „Herausforderungen“:** Ein weiterer Abschnitt mit eigenem Startknopf auf der Indoor-Startseite.
- **Trainer koppeln:** Zweiter Ort für dieselbe Kopplung wie Einstellungen → Sensoren.
- **Trainer koppeln:** Kein Verbindungstest; verbunden wird erst in der Fahrt.
- **Einstellungen (Indoor):** Heißt wie die Einstellungen der App, ist aber eine andere Seite und nur von der Indoor-Startseite erreichbar.
- **Einstellungen (Indoor):** Wächst mit den Branches um acht Einträge aus fünf Branches.
- **Einstellungen (Indoor):** „Liste aktualisieren“ sieht aus wie eine Zeile zum Öffnen, löst aber sofort aus.
- **Garage:** Zwei Einstiege: Symbol auf der Indoor-Startseite und Zeile in den Indoor-Einstellungen.
- **Indoor-Diagnose:** Doppelt erreichbar: Einstellungen der App und Indoor-Einstellungen.
- **Streckendetail (bekannte Strecke):** Nur hier hat eine bekannte Strecke einen eigenen Start- und Download-Knopf; die Karte in der Liste nicht.
- **Strecke vorbereiten / Bereit zum Fahren:** Bekannte Strecken überspringen die Seite, eigene und die Teststrecke halten hier an.
- **Strecke vorbereiten / Bereit zum Fahren:** Der Bau auf dem eigenen Server steht neben dem Alltagsweg.
- **Strecken auf dem Gerät:** Nur über Indoor → Zahnrad → Erweitert erreichbar.
- **Fahrgefühl einstellen:** Die Zeile tut ohne gekoppelten Trainer nichts.
- **Indoor-Fahrt:** Vier Wege zum Beenden, zwei Dialoge mit gleichem Titel und verschiedenen Knöpfen.
- **Indoor-Fahrt:** Knöpfe ohne Beschriftung; Beenden nur durch Halten.
- **Indoor-Fahrt:** Nach „Losfahren“ auf „Bereit zum Fahren“ kommt hier noch einmal „Losfahren“.
- **3D-Vorschau:** Einstieg nur aus dem Tourdetail; „Zurück“ verlässt den ganzen Indoor-Ablauf.
- **Auswertung:** Endet nur mit „Fertig“ am Ausgangsort; kein Weg zum Tourdetail, kein Teilen.
- **Auswertung:** Bekommt mit den Branches drei bis vier weitere Blöcke (Anstiege, Vergleich mit Außenfahrt, Herausforderung teilen).

## Befund nach Schwere

1. **Indoor hat keinen festen Platz** (schwer). Der Indoor-Bereich ist nur über die dritte Karte auf „Heute“, einen Textknopf im Dialog „Losfahren“ oder eine Zeile ganz unten in den Einstellungen erreichbar. Zeigt „Heute“ die Einheit des Tages, fehlt sogar ein neutraler Einstieg. Neun der elf kommenden Branches bauen an diesem Bereich. Beleg: ui/today/TodayScreen.kt:385 · ui/ReadyToRideDialog.kt:249 · ui/more/MoreScreen.kt:460. Im Vorschlag: Indoor wird ein Tab.
2. **Indoor-Startseite: neun Ziele, drei Startknöpfe** (schwer). „Fahren“ in der Tageskarte, „Fahren“ in der Fußleiste und „Diese Strecke fahren“ im Detail starten auf drei Arten. Die Aktion zur gewählten Strecke steht weit entfernt unten. Bekannte Strecken sind große Bildkarten, eigene sind Radiozeilen. Mit den Branches kommen Garage-Symbol und ein Abschnitt „Herausforderungen“ dazu. Beleg: ui/indoor/IndoorHomeScreen.kt:133-242 · IndoorDayCard.kt:433 · IndoorCatalogDetail.kt:218. Im Vorschlag: Jede Strecke trägt ihren eigenen Knopf. Drei Segmente trennen bekannte, eigene und Herausforderungen.
3. **Bis zu vier gleich benannte Tipps bis zur Indoor-Fahrt** (schwer). „Fahren“ auf „Heute“ öffnet nur die Indoor-Startseite; dort noch einmal „Fahren“. Eigene Strecken und die Teststrecke halten auf „Bereit zum Fahren“ mit „Losfahren“, danach folgt „Losfahren“ auf der Karte „Wo startest du?“. Beleg: ui/indoor/IndoorFlowScreen.kt:118 · IndoorViewModel.kt:830 · IndoorStartPanel.kt:191. Im Vorschlag: Ein Blatt „Vor der Fahrt“ mit einem „Losfahren“.
4. **Tourdetail ohne Ordnung der Aktionen** (schwer). Hauptknopf, vier gleichrangige Kacheln (Karte zeigen, Umbenennen, Teilen, Löschen), Umwandeln, Strava-Zeile und „Alle Werte“ liegen im scrollenden Inhalt, oft unter dem ersten Bildschirm. „Löschen“ steht neben „Teilen“ und löscht ohne Rückfrage. Die Branches fügen „Indoor fahren“ und „In 3D ansehen“ an derselben Stelle ein. Beleg: ui/rides/RideDetailScreen.kt:347-449 · TourList.kt:633. Im Vorschlag: Ein fester Knopf „Nochmal fahren“, „Teilen“ als Symbol oben, Seltenes im Menü.
5. **Sieben Einstiege, fünf Wörter für dieselbe Runde** (schwer). „Runde für heute bauen“, „Runde planen“ (zweimal, bald dreimal), „Runde mit N km bauen“, „Passende Runde bauen“, „Runde“, dazu ein Symbolknopf. Bis zu vier davon stehen gleichzeitig auf „Heute“. Alle öffnen dasselbe Blatt, das dann meist noch „Routen suchen“ verlangt. Beleg: ui/today/TodayScreen.kt:376 · WeekPlanCard.kt · progress/ProgressCards.kt:290 · training/PlanSection.kt:429 · map/MapScreen.kt:2392. Im Vorschlag: Drei Einstiege mit zwei Wörtern: „Runde für heute bauen“ und „Runde bauen“. Die Suche startet von selbst.
6. **Dieselbe Einstellung an zwei Orten** (mittel). Trainer koppeln (Indoor und Sensoren, mit „Stopp“ und „Suche beenden“), Indoor-Diagnose (zweimal), Sprachansagen (Einstellungen und Navigation), Jahresziel (Heute und Training), Zeitbudget (Zielformular und Profil), Alter und Gewicht (Einführung und Profil). Zwei verschiedene Seiten heißen „Einstellungen“. Beleg: ui/more/SensorsCard.kt:205 · indoor/IndoorPairScreen.kt:47 · more/MoreScreen.kt:467 · indoor/IndoorSettingsScreen.kt:54,103 · map/NavigationHud.kt:174. Im Vorschlag: Eine Einstellungsseite; Koppeln nur unter „Sensoren & Trainer“.
7. **Die Indoor-Einstellungen laufen voll** (mittel). Heute sieben Einträge. Fünf Branches fügen acht weitere in denselben Bereich ein: Hintergrund, Feinabstimmung, Fahrgefühl einstellen, Fahrprotokolle, Einblendungen, Garage, Grafikstufe. Technisches (Liste aktualisieren, Diagnose) steht neben Alltäglichem. Beleg: ui/indoor/IndoorSettingsScreen.kt:57-110 und Branches idee-05, idee-06, idee-11, spiel-2, indoor-renderqualitaet. Im Vorschlag: Einstellungen → Indoor mit drei Gruppen und einem eingeklappten Teil „Speicher & Diagnose“.
8. **Karte: Planen ist versteckt** (mittel). Es gibt keinen Knopf „Route planen“. Eine Planung entsteht nur über einen gesuchten Ort oder einen langen Druck auf die Karte; ein einfacher Tipp tut nichts. Die Suche startet erst nach einem Extra-Tipp. Eine liegen gebliebene Route lässt sich nicht verwerfen, weil die Funktion dafür keinen Knopf hat. Beleg: ui/map/MapScreen.kt:1761,1816,2027,2896 · AppViewModel.kt:665 (kein Aufrufer). Im Vorschlag: Sichtbare Knöpfe „Route planen“ und „Runde bauen“, „Verwerfen“ in Planung und Losfahren-Blatt.
9. **Wege, die nicht ankommen** (mittel). „Ziel festlegen“, „Dein Ziel“ und „Fortschritt öffnen“ auf „Heute“ wechseln nur den Tab; das Training öffnet auf „Fortschritt“, das Ziel liegt einen Tipp weiter. „+ → Archiv“ springt in die Einstellungen, wo drei Import-Knöpfe nebeneinander stehen. Die Indoor-Auswertung endet mit „Fertig“ am Ausgangsort, ohne Weg zur gespeicherten Tour. Beleg: ui/today/TodayScreen.kt:489 · training/TrainingScreen.kt:227 · rides/RidesScreen.kt:142 · indoor/IndoorFlowScreen.kt:365. Im Vorschlag: Jeder Knopf öffnet das Ziel, das er nennt. Die Auswertung führt ins Tourdetail.
10. **Uneinheitliche Wörter** (mittel). Tab „Verlauf“, Inhalt „Touren“, an neun Stellen „Fahrt“, bei Strava „Aktivitäten“. „Losfahren“ ist Dialogtitel, Planungsknopf, Tourkarten-Knopf und zweimal Indoor-Knopf. „Trainer“, „Smart-Trainer“, „Rollentrainer“, „Rolle“. „Fahrmodus“, „Cockpit“, „Daten“. „Wochen-Fahrplan“, „Wochenplan“, „Plan“, „Trainingsplan“. „Vorbereiten“, „Herunterladen“, „Laden“. Beleg: strings_shell.xml:59 · strings_rides.xml · strings_today.xml:200 · strings_indoor_app.xml · strings_indoor_ble.xml. Im Vorschlag: Ein Wort je Sache, siehe Entscheidungsfrage 2.
11. **Pause und Beenden dreifach, Beenden zweideutig** (mittel). Draußen stehen Pause und Beenden im Fahrmodus, in der Leiste auf der Karte und in der Karte „Aufzeichnung läuft“. Das X der Navigation beendet nur die Führung. Indoor gibt es vier Wege zum Beenden und zwei Dialoge „Fahrt angehalten“ mit verschiedenen Knöpfen. Beleg: ui/map/RideModeScreen.kt · RideCompactBar.kt · MapPanels.kt:140 · indoor/IndoorFlowScreen.kt:323 · IndoorRideScreen.kt:220. Im Vorschlag: Ein Bediensatz je Fahrt.
12. **Erfolge, Garage, Herausforderung liegen verstreut** (mittel). Erfolge im Training mit Schalter in den Einstellungen, Garage zweimal im Indoor, Herausforderungen auf der Indoor-Startseite, in der Mitfahrer-Auswahl und in der Auswertung. Kein Branch ändert die Navigation. Beleg: Branches spiel-1, spiel-2, spiel-3. Im Vorschlag: Erfolge unter Verlauf, Garage und Herausforderungen unter Indoor, teilen im Blatt „Teilen“.
13. **Einstellungen: lange erste Gruppe, halbe Sackgassen** (leicht). Sieben Zeilen ohne Überschrift. „Karten offline“ kann Kartenbild nur löschen, ohne Knopf zur Karte. FTP liegt hinter „Erweitert“. Das Zahnrad fehlt auf der Karte, obwohl eine Meldung dort auf „Einstellungen → Aufzeichnung & Ansagen“ verweist. Beleg: ui/more/MoreScreen.kt:360-411 · OfflineMapsCard.kt:100 · ProfileCard.kt:212. Im Vorschlag: Fünf benannte Gruppen, Zahnrad überall, Knopf zur Karte.
14. **Unsichtbare Bedienung** (leicht). Antippbare Zeilen ohne Knopf-Optik (Uhr verbinden, Zeitbudget, Zielzeit). Knöpfe ohne Beschriftung in der Indoor-Fahrt, Beenden nur durch Halten. Strava im Tourdetail ist ohne Verbindung unsichtbar. Der Widget-Knopf „Fahren“ öffnet nur die App. Beleg: ui/today/TodayCards.kt · training/WeekCard.kt:140 · indoor/IndoorRideScreen.kt:697 · rides/StravaRideAction.kt:123 · widget/TrailscapeWidget.kt:298. Im Vorschlag: Beschriftete Knöpfe; Strava als sichtbare Zeile im Blatt „Teilen“.
15. **Die Leiste verschwindet auf der Karte** (leicht). Bei Ort, Tour, Rundenwahl, Vorschlägen und Planung sind Navigationsleiste und Fahren-Knopf ausgeblendet. Ein Tabwechsel geht nur, indem man die Aufgabe beendet. Beleg: ui/TrailscapeApp.kt:371 · map/MapScreen.kt:2443. Im Vorschlag: Im Vorschlag nicht geändert; in der Umsetzung prüfen.

## Häufige Aufgaben in Tipps

| Aufgabe | Heute | Vorschlag | Weg im Vorschlag | Anmerkung |
|---|---|---|---|---|
| Tour draußen aufzeichnen | 2 | 2 | Fahren → Aufzeichnung starten |  |
| Runde für heute bauen und losfahren | 4 | 3 | Runde für heute bauen → Übernehmen → Losfahren | heute zusätzlich „Routen suchen“ |
| Route zu einem Ort planen und fahren | 6 | 6 | Karte → Wohin? → suchen → Treffer → Route hierher → Losfahren | gleiche Zahl; der Gewinn ist der sichtbare Knopf „Route planen“ |
| Indoor: bekannte Strecke fahren | 5 | 3 | Indoor → Fahren → Losfahren | Strecke schon geladen |
| Indoor: eigene Tour fahren | 5 | 4 | Indoor → Eigene → Fahren → Losfahren | 3D-Ansicht schon vorbereitet |
| Indoor: Einheit des Tages fahren | 3 | 2 | Drinnen fahren → Losfahren | wenn „Heute“ drinnen vorschlägt; sonst ein Tipp mehr |
| Letzte Tour ansehen | 2 | 1 | Letzte Tour (auf „Heute“) | heute zusätzlich scrollen, wenn Planungen darüberstehen |
| Letzte Tour als Bild teilen | 4 | 3 | Letzte Tour → Teilen → Teilen |  |
| Trainingsplan ansehen | 2 | 1 | Diese Woche (auf „Heute“) | die laufende Woche steht schon auf „Heute“ |
| Ziel festlegen (bis zum Formular) | 3 | 1 | Ziel festlegen |  |
| Pulsgurt koppeln | 4 | 4 | Zahnrad → Sensoren & Trainer → Geräte suchen → Koppeln |  |
| Trainer koppeln | 5 | 4 | Indoor → Trainer-Status → Geräte suchen → Koppeln |  |
| Backup erstellen | 3 | 3 | Zahnrad → Backup → Backup exportieren |  |
| Erfolge ansehen (kommt neu) | 2 | 2 | Verlauf → Erfolge | heute zusätzlich scrollen |
| Garage öffnen (kommt neu) | 2 | 2 | Indoor → Garage |  |
| Fahrgefühl abgleichen (kommt neu) | 3 | 3 | Zahnrad → Indoor → Fahrgefühl einstellen |  |

## Vorschlag

| Ebene | Heute | Vorschlag |
|---|---|---|
| Leiste unten | Heute · Karte · Verlauf · Training | Heute · Karte · Indoor · Verlauf |
| Runder Knopf „Fahren“ | Dialog „Losfahren“: aufzeichnen, Runde bauen, Indoor (Textknopf) | Blatt „Losfahren“: aufzeichnen, mit Route starten, Runde für heute bauen |
| Zahnrad | auf Heute, Verlauf, Training; nicht auf der Karte | auf allen vier Tabs, immer dieselbe Seite |
| Indoor | gepushter Ablauf, drei verstreute Einstiege, eigene „Einstellungen“ | eigener Tab mit drei Segmenten; Einstellungen unter dem einen Zahnrad |
| Training | eigener Tab mit Fortschritt | Plan | Plan unter „Heute“, Fortschritt und Erfolge unter „Verlauf“ |
| Einstellungen | 13 Zeilen, 3 Gruppen, erste ohne Überschrift; dazu Indoor-Einstellungen mit bald 15 Einträgen | 12 Zeilen in 5 benannten Gruppen: Du, Geräte, Fahren, Daten, App |

### Verschiebungen

- **Indoor: Karte auf „Heute“, Textknopf im Losfahren-Dialog, Zeile in den Einstellungen** → **Tab „Indoor“**. Neun der elf kommenden Branches bauen am Indoor-Bereich. Ein Bereich dieser Größe braucht einen festen, sichtbaren Platz; drei halbe Einstiege sind keiner.
- **Training → Plan (zweites Segment)** → **Heute → Plan**. Der Plan beantwortet dieselbe Frage wie „Heute“: Was steht an? Die Wochenkarte und die Zielkarte auf „Heute“ führen dann dorthin, wo sie hinzeigen.
- **Training → Fortschritt (erstes Segment)** → **Verlauf → Fortschritt**. Fortschritt ist der Blick zurück, wie die Tourenliste. Beides unter einem Tab trennt „was ansteht“ sauber von „was war“.
- **Erfolge: Karte im Training, Schalter in den Einstellungen** → **Verlauf → Erfolge (Segment)**. Abzeichen entstehen aus gefahrenen Touren. Ein eigenes Segment ist vorhersehbar und verschwindet vollständig, wenn Erfolge ausgeschaltet sind.
- **Garage: Symbol auf der Indoor-Startseite und Zeile in den Indoor-Einstellungen** → **Indoor → Garage (ein Symbol in der Kopfzeile)**. Die Garage wirkt nur in der 3D-Ansicht. Ein Einstieg genügt.
- **Herausforderungen: Abschnitt auf der Indoor-Startseite, Pille bei „Mitfahrer“, Block in der Auswertung** → **Indoor → Segment „Herausforderungen“; teilen im Blatt „Teilen“ der Tour**. Empfangen und Fahren an einem Ort, Verschicken dort, wo man auch alles andere verschickt.
- **„Indoor fahren“ und „In 3D ansehen“ als weitere Knöpfe im Tourdetail** → **Blatt „Nochmal fahren“ im Tourdetail**. Das Tourdetail hätte sonst elf Aktionen. So bleibt ein Hauptknopf, der drei Arten des Nachfahrens anbietet.
- **Teilen, GPX, Strava an drei Stellen im Tourdetail und auf der Karte** → **Blatt „Teilen“ im Tourdetail**. Alles, was die App verlässt, liegt hinter einem Symbol. Strava ist auch ohne Verbindung sichtbar und erklärt sich.
- **Umbenennen, Löschen, Umwandeln, Karte zeigen als Kacheln und Zeilen** → **Menü der Tour**. Seltene Aktionen eingeklappt; „Löschen“ steht nicht mehr neben „Teilen“.
- **Einstellungen (Indoor) als eigene Seite** → **Einstellungen → Indoor**. Es gibt nur noch eine Seite namens „Einstellungen“. Kalibrierung, Grafikstufe, Hintergrund und Einblendungen haben dort feste Gruppen.
- **Trainer koppeln im Indoor-Ablauf und unter Sensoren** → **Einstellungen → Sensoren & Trainer**. Ein Ort, eine Suche, ein Wort. Der Trainer-Status im Indoor-Tab springt direkt dorthin.
- **Indoor-Diagnose, Teststrecke, Strecken auf dem Gerät, Fahrprotokolle, Liste aktualisieren** → **Einstellungen → Indoor → Speicher & Diagnose (eingeklappt)**. Technik für den Entwickler und für Störungen, nicht für den Alltag.
- **Karte „Optionen“, Seite „Bereit zum Fahren“, Karte „Wo startest du?“** → **Blatt „Vor der Fahrt“**. Alles, was vor einer Indoor-Fahrt zu wählen ist, in einem Schritt mit einem „Losfahren“.
- **Tour-Import: „+“ im Verlauf, Leerzustand, Einstellungen → Import & Backup** → **Verlauf → „+“**. Touren kommen dort hinein, wo sie danach liegen. Die Einstellungsseite heißt nur noch „Backup“.
- **Jahresziel auf „Heute“ und im Training** → **Verlauf → Fortschritt**. Ein Ort je Sache.
- **Karte: Planung nur über Ort oder langen Druck** → **Knopf „Route planen“ im Blatt „Wohin?“**. Die zweitwichtigste Aufgabe der Karte bekommt einen sichtbaren Einstieg.
- **Pause und Beenden in drei Bausteinen** → **eine Leiste im Fahrmodus**. Ein Bediensatz, eine Beschriftung („Beenden: halten“).

### Seiten des Vorschlags

| Bereich | Tiefe | Art | Name | Änderung gegenüber heute |
|---|---|---|---|---|
| Von außen | außen | von außen | App-Symbol | Unverändert. |
| Von außen | außen | von außen | Öffnen mit / Teilen (GPX, FIT, ZIP) | Unverändert; der Hinweis „als Planung erkannt“ führt ins Tourdetail. |
| Von außen | außen | von außen | Plan-Rezept öffnen | Landet nach dem Übernehmen auf der Seite „Plan“. |
| Von außen | außen | von außen | Rückkehr von Strava | Unverändert. |
| Von außen | außen | von außen | Widget „Trailscape Serie“ | Der Knopf „Fahren“ öffnet das Blatt „Losfahren“ statt nur die App. |
| Von außen | außen | von außen | Erinnerung | Unverändert. |
| Von außen | außen | von außen | Benachrichtigung Aufzeichnung | „Weiter“ statt „Fortsetzen“, wie in der App. |
| Von außen | außen | von außen | Uhr | Unverändert. |
| Von außen | außen | von außen, neu | Herausforderung öffnen | Fester Zielort statt eines Abschnitts auf der Startseite. |
| Von außen | außen | von außen, neu | Benachrichtigung Indoor-Fahrt | Unverändert zum Branch. |
| Heute und Hülle | 0 | Zustand | Einführung | Die Seite „Bring deine Touren mit“ bekommt den Knopf „Touren importieren“. |
| Heute und Hülle | 1 | Blatt | Blatt „Losfahren“ | Indoor-Zeile entfällt (Indoor ist ein Tab). „Route verwerfen“ wird sichtbar. „Losfahren“ heißt nur noch dieser letzte Schritt vor einer Fahrt. |
| Heute und Hülle | 0 | Hauptbereich | Heute | Eine Karte statt drei: Empfehlung, „Wo fährst du heute?“ und „Einheit des Tages“ sind zusammengelegt, mit Umschalter Draußen | Drinnen. Nur noch ein Knopf baut eine Runde. |
| Heute und Hülle | 1 | Blatt | Blatt „Warum diese Empfehlung?“ | Unverändert. |
| Heute und Hülle | 1 | Seite | Plan | War das zweite Segment im Training. Jetzt ein Tipp von „Heute“ über „Diese Woche“ oder „Dein Ziel“. |
| Heute und Hülle | 2 | Blatt | Blatt „Ziel festlegen“ | „Ziel festlegen“ auf „Heute“ öffnet dieses Blatt direkt. Das Zeitbudget steht nur noch hier, das Profil verweist darauf. |
| Heute und Hülle | 2 | Blatt | Blatt „Wie die Prognose entsteht“ | Unverändert. |
| Heute und Hülle | 2 | Dialog | Plan-Rezept | Liegt im Menü der Seite „Plan“ statt als Karte am Ende der Liste. |
| Karte (draußen) | 1 | Hauptbereich | Karte | Sichtbare Knöpfe „Route planen“ und „Runde bauen“. Ein einfacher Tipp auf die Karte setzt einen Punkt. Zahnrad auch hier. |
| Karte (draußen) | 1 | Blatt | Blatt „Passende Runde“ | Die Suche startet immer von selbst. Erreichbar über genau drei Knöpfe: „Runde für heute bauen“ (Heute, Losfahren) und „Runde bauen“ (Karte). |
| Karte (draußen) | 2 | Blatt | Blatt „Runde bauen“ | Heißt überall „Runde bauen“. |
| Karte (draußen) | 2 | Blatt | Blatt „Route planen“ | Direkt über „Route planen“ erreichbar. „Speichern“ lässt die Route stehen; „Verwerfen“ ist ein sichtbarer Knopf. |
| Karte (draußen) | 2 | Blatt | Ortskarte | Zwei Aktionen statt drei: „Route hierher“ und „Runde ab hier“. In der Planung nur „Als Wegpunkt“. |
| Karte (draußen) | 2 | Blatt | Blatt „Kartenansicht“ | Ein Einstieg (Ebenen-Knopf). Die Zeile „Offline-Karten“ im Blatt „Wohin?“ entfällt. |
| Karte (draußen) | 2 | Blatt | Tourkarte | Nur noch „Losfahren“ und „Details“. Teilen, Umbenennen und Löschen liegen allein im Tourdetail. |
| Karte (draußen) | 2 | Zustand | Fahrmodus | Ein Bediensatz statt drei. Heißt überall „Fahrmodus“. Navigation ist die Kartenseite des Fahrmodus. |
| Karte (draußen) | 3 | Dialog | Dialog „Name der Route“ | Unverändert. |
| Karte (draußen) | 3 | Zustand | „Wo du überall warst“ | Unverändert, Einstieg im Verlauf. |
| Indoor (drinnen) | 1 | Hauptbereich | Indoor | Neuer Tab statt Karte auf „Heute“. Jede Strecke trägt ihren eigenen Knopf; die Fußleiste und die drei Startknöpfe entfallen. |
| Indoor (drinnen) | 2 | Seite | Streckendetail | Gleiche Seite für bekannte und eigene Strecken. „In 3D ansehen“ sitzt hier als zweite Aktion. |
| Indoor (drinnen) | 2 | Blatt | Blatt „Vor der Fahrt“ | Ersetzt die Karte „Optionen“, die Seite „Bereit zum Fahren“ und die Karte „Wo startest du?“. Ein „Losfahren“ statt zwei. |
| Indoor (drinnen) | 2 | Seite, neu | Garage | Ein Einstieg: Symbol in der Kopfzeile des Indoor-Tabs. |
| Indoor (drinnen) | 2 | Abschnitt, neu | Segment „Herausforderungen“ | Eigenes Segment statt Abschnitt zwischen den Strecken. |
| Indoor (drinnen) | 3 | Zustand | Indoor-Fahrt | Beschriftete Knöpfe. Beenden über Pause → „Fahrt beenden“ oder Halten; ein Dialog statt zwei. |
| Indoor (drinnen) | 3 | Seite, neu | 3D-Vorschau | Erreichbar aus dem Streckendetail und aus „Nochmal fahren“ im Tourdetail. „Zurück“ führt zum Ausgangspunkt. |
| Indoor (drinnen) | 4 | Seite | Auswertung | Nach „Speichern“ öffnet das Tourdetail der neuen Tour. Anstiege, Vergleich und „Herausforderung teilen“ stehen dort. |
| Verlauf | 1 | Hauptbereich | Verlauf · Touren | „Geplant“ wird ein Filter statt eines Abschnitts über der Liste. Der Tab heißt weiter „Verlauf“; „Touren“ ist sein erstes Segment. |
| Verlauf | 2 | Seite | Verlauf · Fortschritt | War das erste Segment im Training. |
| Verlauf | 2 | Seite, neu | Verlauf · Erfolge | Eigenes Segment statt Karte im Training. Ist „Erfolge anzeigen“ aus, fehlt das Segment. |
| Verlauf | 2 | Seite | Tourdetail | Ein fester Knopf unten, „Teilen“ als Symbol oben, alles Seltene im Menü. Löschen nicht mehr neben Teilen. |
| Verlauf | 2 | Blatt | Menü „Importieren“ | Beide Einträge öffnen direkt die Dateiauswahl. Der einzige Ort für Tour-Import. |
| Verlauf | 3 | Blatt, neu | Blatt „Nochmal fahren“ | Fester Platz für „Nachfahren“ und „3D-Vorschau“ statt zweier weiterer Knöpfe. |
| Verlauf | 3 | Blatt | Blatt „Teilen“ | Alle Wege aus der App an einem Ort; Strava ist auch ohne Verbindung als Zeile sichtbar. |
| Verlauf | 3 | Blatt | Menü der Tour | Vier seltene Aktionen eingeklappt. |
| Verlauf | 3 | Blatt | Blätter „Deine Form“, „Körperwerte“, „Jahresziel“ | Jahresziel nur noch hier, nicht mehr auf „Heute“. |
| Einstellungen (Zahnrad) | 1 | Seite | Einstellungen | Zahnrad auf allen vier Tabs. Die Indoor-Einstellungen ziehen hierher; die Sprungzeilen „Indoor-Training“ und „Indoor-Diagnose“ entfallen. |
| Einstellungen (Zahnrad) | 2 | Seite | Profil | Puls und FTP stehen sichtbar, nicht hinter „Erweitert“. |
| Einstellungen (Zahnrad) | 2 | Seite | Uhr & Gesundheitsdaten | Unverändert. |
| Einstellungen (Zahnrad) | 2 | Seite | Sensoren & Trainer | Der einzige Ort zum Koppeln. Der Trainer-Status im Indoor-Tab springt hierher. „Ohne Trainer ausprobieren“ steht hier. |
| Einstellungen (Zahnrad) | 2 | Seite | Aufzeichnung & Ansagen | Der Lautsprecher in der Navigation bleibt als Schnellschalter für dieselbe Einstellung. |
| Einstellungen (Zahnrad) | 2 | Seite | Indoor | Nimmt die bisherige Seite „Einstellungen (Indoor)“ und alle acht neuen Einträge der Branches auf, in drei Gruppen. |
| Einstellungen (Zahnrad) | 2 | Seite | Erinnerungen & Motivation | Der einzelne Schalter „Erfolge anzeigen“ bekommt keine eigene Gruppe. |
| Einstellungen (Zahnrad) | 2 | Seite | Backup | Nur noch Backup. Der Tour-Import liegt im Verlauf. |
| Einstellungen (Zahnrad) | 2 | Seite | Karten offline | Bekommt den Knopf zur Karte, damit die Seite keine halbe Sackgasse ist. |
| Einstellungen (Zahnrad) | 2 | Seite | Strava | Unverändert. |
| Einstellungen (Zahnrad) | 2 | Seite | Sync mit eigenem Server | Unverändert. |
| Einstellungen (Zahnrad) | 2 | Seite | Sprache | Unverändert. |
| Einstellungen (Zahnrad) | 2 | Seite | Über Trailscape | Unverändert. |
| Einstellungen (Zahnrad) | 3 | Seite, neu | Fahrgefühl einstellen | Unter Einstellungen → Indoor → Fahrgefühl. Ohne Trainer führt die Zeile zu „Sensoren & Trainer“. |
| Einstellungen (Zahnrad) | 3 | Seite | Speicher & Diagnose | Fasst vier technische Seiten zusammen; eingeklappt am Ende der Indoor-Einstellungen. |

## Entscheidungsfragen

1. **Bekommt Indoor einen eigenen Tab?** Der Vorschlag ersetzt den Tab „Training“ durch „Indoor“ und verteilt Plan und Fortschritt auf „Heute“ und „Verlauf“. Das ist die größte Änderung und widerspricht der früheren Studie „Eine Leiste“, in der Fahren ein Zustand und kein Ort ist. In docs/TODO.md steht Indoor noch als Prototyp.
   - A: Tab „Indoor“ wie im Prototyp. Fester Platz für Strecken, Garage, Herausforderungen. Wer keinen Trainer hat, sieht einen Tab, den er selten braucht.
   - B: Leiste bleibt wie heute. Der Knopf „Fahren“ öffnet ein Blatt mit Draußen | Drinnen; die Indoor-Seite bleibt ein gepushter Ablauf. Kleinerer Umbau, Indoor bleibt zwei Tipps entfernt, Training behält seinen Tab.
   - Empfehlung: A, sobald die Indoor-Branches gemergt sind. Dann ist Indoor nach Umfang der größte Bereich der App. Alle anderen Teile des Vorschlags (Tourdetail, eine Einstellungsseite, Blatt „Vor der Fahrt“, Begriffe) gelten auch mit B.
2. **Welche Wörter gelten?** Für dieselben Dinge stehen heute mehrere Wörter. Der Prototyp nutzt eine feste Liste.
   - Tour = alles Gefahrene, draußen oder drinnen. Fahrt = nur die gerade laufende. Planung = gespeicherte Route. Runde = erzeugter Rundkurs. Strecke = was man im Indoor-Tab fährt. Einheit = Eintrag im Trainingsplan.
   - Tab „Verlauf“ mit den Segmenten Touren | Fortschritt | Erfolge. Alternative: Tab „Touren“, dann passt Fortschritt schlechter darunter.
   - „Trainer“ überall, „Rollentrainer“ nur bei der ersten Nennung. „Fahrmodus“ statt „Cockpit“ und „Daten“. „Losfahren“ nur als letzter Knopf vor einer Fahrt. „Herunterladen“ für bekannte, „Vorbereiten“ für eigene Strecken; „Laden“ entfällt.
   - Empfehlung: Die Liste so übernehmen und in docs/i18n.md als Glossar festhalten, bevor die neuen Texte der Branches dazukommen.
3. **Wo wohnen die Erfolge?** Erfolge entstehen aus Touren, schalten aber später Farben in der Garage frei. Der Branch legt sie ins Training, das im Vorschlag kein Tab mehr ist.
   - Verlauf → Erfolge: beim Blick zurück, für alle Fahrer sichtbar, auch ohne Trainer.
   - Indoor → Erfolge neben der Garage: alles Spielerische an einem Ort, aber für reine Draußen-Fahrer versteckt.
   - Empfehlung: Verlauf → Erfolge. Die Garage zeigt bei einer gesperrten Farbe nur den Text „ab Stufe N“, ohne eigenen Weg dorthin.
4. **Setzt ein einfacher Tipp auf die Karte einen Punkt?** Heute setzt nur ein langer Druck einen Punkt; ein einfacher Tipp tut nichts. Das ist der am besten versteckte Handgriff der App.
   - Ja: ein Tipp öffnet die Ortskarte „Markierter Punkt“. Versehentliche Tipps beim Verschieben der Karte sind möglich, die Ortskarte lässt sich aber mit einem Tipp schließen.
   - Nein: langer Druck bleibt, dafür ein dauerhafter Hinweis im Blatt „Wohin?“ und der neue Knopf „Route planen“.
   - Empfehlung: Ja, in der Planung und im Ruhezustand; während Aufzeichnung und Navigation weiterhin nicht.
5. **Aufräumen vor oder nach dem Merge der Branches?** idee-01 und idee-04 fügen an identischer Stelle im Tourdetail ein. Fünf Branches bauen in dieselben Zeilen der Indoor-Einstellungen, drei in die Auswertung. Offen ist PR #113 mit Kalibrierung, Nachfahren und Bergwertung.
   - Erst mergen, dann umbauen: keine Verzögerung der Funktionen, aber die Seiten werden zwischenzeitlich noch voller und der Umbau fasst alles zweimal an.
   - Erst die drei Sammelstellen bauen (Blatt „Nochmal fahren“, Einstellungen → Indoor mit Gruppen, Tourdetail als Ziel der Auswertung), dann die Branches dort einhängen.
   - Empfehlung: Erst die drei Sammelstellen. Das sind kleine Änderungen an bestehenden Seiten und sie lösen die Merge-Konflikte gleich mit. Der Tab-Umbau aus Frage 1 kann danach kommen.

## Unsicher oder nicht geprüft

- Nichts lief auf einem Gerät. Tipp-Zahlen, Scrollwege und Sichtbarkeit sind aus dem Code gelesen.
- Wie häufig welche Aufgabe wirklich ist, ist eine Annahme. Die App hat bewusst keine Telemetrie; das soll so bleiben.
- Ob die Beschriftung „Indoor“ neben „Heute“, „Karte“ und „Verlauf“ auf 320 dp Breite einzeilig passt, ist nicht geprüft. Sie ist kürzer als „Training“.
- Das Blatt „Vor der Fahrt“ fasst drei bestehende Bausteine zusammen. Ob das Vorbereiten einer eigenen Strecke (Minuten) darin gut wartet, muss am Gerät probiert werden.
- Der Umschalter Draußen | Drinnen auf „Heute“ setzt die Empfehlung aus Branch idee-02 voraus.
- Die Vermutung, dass eine liegen gebliebene Route nach dem Aufzeichnen als Linie stehen bleibt, ist aus dem Code abgeleitet und nicht nachgestellt.
- Branch spiel-5-raum hat noch keine Oberfläche. Für „Raum anlegen / beitreten“ wäre ein viertes Segment im Indoor-Tab der naheliegende Platz; im Prototyp ist nichts dazu gezeigt.
- Das Querformat der Indoor-Fahrt, die Uhr-App und die Diagnose-Seite sind nur über ihre Texte erfasst.
- KDoc in MapScreen.kt und MapMode.kt beschreibt drei Blattstufen und eine Aktionszeile „Route planen“, die es nicht mehr gibt. Die Seitenkarte folgt dem Code, nicht diesen Kommentaren.
