# Umbau der Bedienstruktur

Stand 25.11.2026. Grundlage ist die Seitenkarte (`docs/ux/seitenkarte.md`, Prototyp unter `docs/ux/artefakt/`). Diese Datei hält fest, was davon umgesetzt ist, wo jede heutige Seite jetzt liegt und wo die vier Funktionen eingehängt werden, die noch auf eigenen Branches liegen.

Nichts davon lief auf einem Gerät, und der Stand ist in der Umbau-Session **nicht gebaut** worden (der Container bekam von Maven Central nur HTTP 429). Siehe „Nicht geprüft“ am Ende.

## Die Entscheidungen des Eigentümers

| Frage der Seitenkarte | Entscheidung | Umsetzung |
|---|---|---|
| 1 Bekommt Indoor einen Tab? | Der Tab „Training“ wird durch „Indoor“ ersetzt; er bündelt Strecken, Trainingspläne und den Start der Indoor-Fahrt. | Leiste Heute · Karte · Indoor · Verlauf. Indoor hat die Segmente Strecken \| Plan. |
| 2 Welche Wörter gelten? | Festes Glossar, Deutsch und Englisch. | Texte umbenannt; Glossar in `docs/i18n.md`. |
| 3 Wo wohnen die Erfolge? | Abschnitt unter „Verlauf“, kein eigener Tab. | Platz `HistorySegment.ACHIEVEMENTS`, unsichtbar bis zum Merge. |
| 4 Setzt ein Tipp einen Punkt? | Ja, mit Rückgängig-Knopf. | `ui/map/MapTapPlanning.kt`, Rückgängig im Planungsblatt. |
| 5 Aufräumen vor oder nach den Branches? | Erst aufräumen. | Sammelstellen gebaut: Blatt „Nochmal fahren“, Einstellungen → Indoor, Tourdetail als Ziel der Auswertung. |

## Die neue Struktur

```
Leiste unten      Heute · Karte · Indoor · Verlauf        runder Knopf „Fahren“ daneben
Zahnrad           auf allen vier Tabs, immer dieselbe Seite „Einstellungen“

Heute             Tagesform, Empfehlung, Woche, Ziel
Karte             Blatt „Wohin?“: Suche · Route planen · Runde bauen · Zahnrad
Indoor            Segmente  Strecken | Plan            [ | Herausforderungen ] [ | Raum ]
Verlauf           Segmente  Touren | Fortschritt       [ | Erfolge ]
Einstellungen     Du · Geräte · Fahren · Daten · App
```

In eckigen Klammern stehen Plätze, die festgelegt, aber unsichtbar sind, bis es die Funktion gibt.

Wo die Regeln stehen:

| Was | Datei |
|---|---|
| Tabs, Routen, Sprungziele, Segmente, „wann tritt die Leiste zurück“ als reine Logik | `app/…/ui/AppNavigation.kt`, getestet in `AppNavigationTest` |
| Hülle: Leiste, Fahren-Knopf, `NavHost` | `app/…/ui/TrailscapeApp.kt` |
| Sprungfunktionen (`requestPlan`, `requestProgress`, `requestIndoor`, …) | `app/…/ui/AppViewModel.kt` |
| Indoor-Tab | `app/…/ui/indoor/IndoorTabScreen.kt`, Ablauf in `IndoorFlowScreen.kt` (`IndoorFlowMode`) |
| Plan, Fortschritt | `app/…/ui/training/PlanScreen.kt`, `ProgressContent.kt` |
| Verlauf mit Segmenten | `app/…/ui/rides/RidesScreen.kt` |
| Tipp auf die Karte, Rückgängig | `app/…/ui/map/MapTapPlanning.kt`, getestet in `MapTapPlanningTest` |
| Blatt „Nochmal fahren“ | `app/…/ui/rides/RideAgain.kt`, getestet in `RideAgainOptionsTest` |

## Was umgesetzt ist

**Hülle**

- Tab-Leiste Heute · Karte · Indoor · Verlauf. Der Tab „Training“ ist entfernt (`ui/training/TrainingScreen.kt` gelöscht).
- Die Leiste tritt auf jeder gepushten Seite zurück, außerdem auf der Karte während einer Aufgabe, im Verlauf bei offenem Tourdetail und im Indoor-Tab ab dem ersten Schritt nach der Startseite (Koppeln, Vorbereiten, Fahrt, Auswertung).
- Zahnrad auf allen vier Tabs; es öffnet immer dieselbe Seite.
- Der Dialog „Losfahren“ des runden Knopfs bietet nur noch die Fahrt draußen an. Die Zeile „Indoor auf dem Rollentrainer fahren“ ist entfallen.

**Indoor**

- Tab mit den Segmenten Strecken | Plan. „Strecken“ ist die bisherige Indoor-Startseite; Koppeln, Vorbereiten, Fahrt und Auswertung laufen im selben Ablauf weiter.
- „Plan“ ist der Trainingsplan (früher Training → Plan), unverändert im Inhalt.
- Die Indoor-Einstellungen liegen unter Einstellungen → Indoor (gepushte Seite), samt Fahrgefühl einstellen, Fahrprotokolle, Strecken auf dem Gerät, Liste aktualisieren und Diagnose. Die Sprungzeilen „Indoor-Training“ und „Indoor-Diagnose“ in den Einstellungen sind entfallen.
- Nach „Speichern“ in der Auswertung führt der Hauptknopf „Tour ansehen“ ins Tourdetail der neuen Tour.
- Einstiege mit Vorwahl (Einheit des Tages von „Heute“, „Indoor fahren“ und „In 3D ansehen“ aus dem Tourdetail) öffnen den Ablauf als gepushte Seite; „Zurück“ führt zum Ausgangspunkt.

**Verlauf**

- Segmente Touren | Fortschritt. „Fortschritt“ ist die frühere erste Sicht des Tabs „Training“ (Trend, Monats-Challenges, Jahresziel, Bestwerte, Meilensteine, Gesamtwerte, Form, Körperwerte).
- Tour-Import nur noch hier: „+“ startet GPX/FIT **und** das ZIP-Archiv direkt (kein Sprung mehr in die Einstellungen).
- Tourdetail: ein fester Knopf unten („Nochmal fahren“, bei Planungen „Planung fahren“) öffnet das Blatt „Nochmal fahren“ mit Draußen mit Navigation, Indoor fahren, In 3D ansehen. „Teilen“ ist ein Symbol oben (Bild, GPX, Strava). Auf der Karte zeigen, Umbenennen, Umwandeln und Löschen liegen im Menü ⋮.

**Karte**

- Im Blatt „Wohin?“ stehen „Route planen“ und „Runde bauen“ als sichtbare Knöpfe, daneben das Zahnrad. Die Zeile „Offline-Karten“ ist entfallen (sie öffnete dasselbe Blatt wie der Ebenen-Knopf).
- Ein einfacher Tipp auf die Karte öffnet im Ruhezustand die Ortskarte „Markierter Punkt“ und hängt in der Planung den nächsten Wegpunkt an. Während Aufzeichnung, Navigation, offenen Vorschlägen, gewählter Tour, Rundenwahl, Verlaufskarte und Suche setzt ein Tipp nichts. Der lange Druck bleibt.
- Rückgängig-Knopf im Kopf des Planungsblatts (letzte Änderung der Wegpunkte, bis zu 30 Schritte). „Verwerfen“ ist ein sichtbarer Knopf mit Rückhol-Meldung.
- „Speichern“ lässt die Route stehen.
- Ortskarte: beim Erkunden „Route hierher“ und „Runde ab hier“, in der Planung „Als Wegpunkt“.

**Heute**

- „Ziel festlegen“ öffnet direkt das Zielformular, „Dein Ziel“ den Plan, die Zeilen unter „Als Nächstes“ den Fortschritt. „Touren importieren“ im Leerzustand führt in den Verlauf.

**Einstellungen**

- Fünf benannte Gruppen: Du (Profil, Uhr & Gesundheitsdaten), Geräte (Sensoren & Trainer), Fahren (Aufzeichnung & Ansagen, Indoor, Erinnerungen), Daten (Backup, Karten offline, Strava, Sync mit eigenem Server), App (Sprache, Über Trailscape).
- „Import & Backup“ heißt „Backup“ und enthält nur noch das Backup.
- Profil: Puls und FTP stehen sichtbar statt hinter „Erweitert“.
- „Karten offline“ hat den Knopf „Neuen Ausschnitt auf der Karte wählen“.

**Wörter**

- Glossar nach der Wortliste der Seitenkarte, Deutsch und Englisch; festgehalten in `docs/i18n.md`, Abschnitt „Glossar“.

## Heute → neu

Jede Seite, jedes Blatt und jeder Dialog aus der Bestandsliste der Seitenkarte, mit dem neuen Weg dorthin. „Unverändert“ heißt: gleicher Ort, gleicher Einstieg.

### Von außen

| Heute | Neu |
|---|---|
| App-Symbol | Unverändert (Start auf „Heute“). |
| Öffnen mit / Teilen (GPX, FIT, ZIP) | Unverändert; landet im Verlauf. |
| Plan-Rezept öffnen | Landet auf Indoor → Plan (vorher Tab „Training“). |
| Rückkehr von Strava | Unverändert. |
| Widget „Trailscape Serie“ | Unverändert (siehe „Nicht umgesetzt“). |
| Erinnerung | Unverändert. |
| Benachrichtigung Aufzeichnung | Unverändert im Weg; Beschriftung nach Glossar. |
| Uhr | Unverändert. |

### Heute und Hülle

| Heute | Neu |
|---|---|
| Einführung (5 Seiten) | Unverändert; erneut über Einstellungen → Über Trailscape. |
| Dialog „Losfahren“ | Runder Knopf „Fahren“, ohne Indoor-Zeile. |
| Heute | Tab „Heute“. |
| Karte „Einheit des Tages“ | Unverändert auf „Heute“; „Fahren“ öffnet den Indoor-Ablauf mit dieser Strecke, der allgemeine Einstieg den Tab Indoor. |
| Karte „Wo fährst du heute?“ | Unverändert auf „Heute“. |
| Blatt „Warum diese Empfehlung?“ | Unverändert. |
| Dialog „Jahresziel“ | Verlauf → Fortschritt → Jahresziel. Auf „Heute“ bleibt nur die Aufforderung „Jahresziel setzen“, solange keines gesetzt ist. |

### Karte

| Heute | Neu |
|---|---|
| Karte mit Blatt „Wohin?“ | Tab „Karte“; im Blatt jetzt „Route planen“, „Runde bauen“, Zahnrad. |
| Knopf „Heute N km“ im Blatt „Wohin?“ | Entfallen; die Runde des Tages baut „Heute“ und der Dialog „Losfahren“. |
| Blatt „Passende Runde“ | Über „Heute“, „Losfahren“ und „Runde bauen“ → „Vorschläge zeigen“. |
| Blatt „Runde ab hier“ | Karte → „Runde bauen“, oder Ortskarte → „Runde ab hier“. |
| Ortskarte | Karte → suchen, antippen oder lange drücken. |
| Blatt „Karte“ (Stil, Kacheln, Offline-Ausschnitt) | Ebenen-Knopf auf der Karte (ein Einstieg). |
| Fahrmodus | Unverändert. |
| „Wo du überall warst“ | Verlauf → Touren → „Alle Touren auf der Karte“. |
| Blatt „Route planen“ | Karte → „Route planen“, Ortskarte → „Route hierher“, Tipp auf die Karte in der Planung. |
| Tourkarte | Verlauf → Tourdetail → ⋮ → „Auf der Karte zeigen“; „Gespeicherte Routen“ auf der Karte. |
| Navigation | Unverändert. |
| Karte „Aufzeichnung läuft“ | Unverändert. |
| Blatt „Ort suchen“ | Planung → Punkt hinzufügen. |
| Dialog „Name der Route“ | Planung → Speichern. |
| Dialog „Tour löschen“ (auf der Karte) | Unverändert an der Tourkarte. |
| Hinweise (Akku, Standort, Offline-Routing) | Unverändert, erscheinen von selbst. |

### Verlauf

| Heute | Neu |
|---|---|
| Verlauf (Tourenliste) | Tab „Verlauf“, Segment „Touren“. |
| Tourdetail | Tour antippen; „Tour ansehen“ nach einer Indoor-Fahrt; „Letzte Tour“ auf „Heute“. |
| Menü „Touren importieren“ | „+“ im Verlauf; beide Einträge öffnen direkt die Dateiauswahl. |
| Dialog „Tour teilen“ | Tourdetail → Teilen-Symbol oben. |
| Strava-Zeile im Tourdetail | Im Blatt „Teilen“; ohne Verbindung „Mit Strava verbinden“. |
| Dialog „Tour umbenennen“ | Tourdetail → ⋮ → Umbenennen. |
| Dialog „In Planung umwandeln?“ | Tourdetail → ⋮. |
| Löschen im Tourdetail | Tourdetail → ⋮ → Löschen (mit „Rückgängig“ wie bisher). |
| Dialog „Als Planung geöffnet“ | Unverändert nach einem Import. |
| Import-Ergebnis | Unverändert. |

### Training

| Heute | Neu |
|---|---|
| Training · Fortschritt | Verlauf → Fortschritt. |
| Training · Plan | Indoor → Plan; von „Heute“ über „Dein Ziel“. |
| Blatt „Deine Form“ | Verlauf → Fortschritt → Deine Form. |
| Blatt „Körperwerte“ | Verlauf → Fortschritt → Körperwerte. |
| Blatt „Ziel festlegen“ | Indoor → Plan → Ziel festlegen / Ändern; von „Heute“ über „Ziel festlegen“ direkt. |
| Blatt „Wie die Prognose entsteht“ | Indoor → Plan → „Wie wird das berechnet?“. |
| Plan-Rezept (teilen, speichern, öffnen) | Indoor → Plan → Abschnitt Plan-Rezept. |
| Dialog „Trainingsplan löschen“ | Indoor → Plan → Ziel ändern → Plan löschen. |

### Einstellungen

| Heute | Neu |
|---|---|
| Einstellungen | Zahnrad auf jedem Tab. |
| Profil | Du → Profil. |
| Uhr & Gesundheitsdaten | Du → Uhr & Gesundheitsdaten. |
| Sensoren | Geräte → Sensoren & Trainer. |
| Aufzeichnung & Ansagen | Fahren → Aufzeichnung & Ansagen. |
| Erinnerungen | Fahren → Erinnerungen. |
| Karten offline | Daten → Karten offline. |
| Import & Backup | Daten → Backup (Backup exportieren und importieren); Tour- und Archiv-Import: Verlauf → „+“. |
| Strava | Daten → Strava. |
| Sync mit eigenem Server | Daten → Sync mit eigenem Server. |
| Sprache | App → Sprache. |
| Über Trailscape | App → Über Trailscape. |
| Zeile „Indoor-Training“ | Entfallen; Indoor ist ein Tab. |
| Zeile „Indoor-Diagnose“ | Fahren → Indoor → Diagnose. |
| Dialoge der Einstellungen | Unverändert an ihren Seiten. |

### Indoor

| Heute | Neu |
|---|---|
| Indoor-Training (Startseite) | Tab „Indoor“, Segment „Strecken“. |
| Trainer koppeln | Indoor → Trainer-Karte → Koppeln / Ändern; außerdem Einstellungen → Sensoren & Trainer. |
| Einstellungen (Indoor) | Zahnrad → Fahren → Indoor. |
| Indoor-Diagnose | Zahnrad → Indoor → Diagnose. |
| Streckendetail (bekannte Strecke) | Indoor → Strecken → Details. |
| Strecke vorbereiten / Bereit zum Fahren | Indoor → Strecke wählen → Fahren. |
| Strecken auf dem Gerät | Zahnrad → Indoor → Strecken auf dem Gerät. |
| Fahrgefühl einstellen | Zahnrad → Indoor → Fahrgefühl einstellen. |
| Fahrprotokolle | Zahnrad → Indoor → Fahrprotokolle. |
| Indoor-Fahrt | Indoor → Fahren. |
| 3D-Vorschau | Tourdetail → Nochmal fahren → In 3D ansehen. |
| Dialoge der Fahrt | Unverändert. |
| Auswertung | Nach der Fahrt; „Tour ansehen“ öffnet das Tourdetail. |

## Wo die vier kommenden Funktionen eingehängt werden

Keiner der vier Branches ist gemergt. Für keinen gibt es in der ausgelieferten App eine leere Seite: Die Plätze sind Aufzählungswerte und Parameter, die erst mit der Funktion sichtbar werden.

### Erfolge (`origin/claude/spiel-1-erfolge`)

Platz: Verlauf → Segment „Erfolge“, rechts von „Fortschritt“.

1. `ui/rides/RidesScreen.kt`: `historySegments(achievementsVisible = false)` an den Schalter binden, also `!ErfolgeStore.shared.state.hidden` (als State gesammelt). Ist der Schalter aus, fehlt das Segment; ist es gerade offen, fällt der Verlauf über `resolveSegment` auf „Touren“ zurück (getestet).
2. Im `when (segment)` den Zweig `HistorySegment.ACHIEVEMENTS` füllen: eine Sicht, die wie `ProgressContent` den Kopf des Tabs als erstes Listenelement nimmt und darunter `ErfolgeContent(...)` zeigt. Die Vorbereitung dafür (Überblick laden, Neues als gesehen merken, Pausenwoche) steht im Branch in `ErfolgeSheet`; sie zieht aus dem Blatt in diese Sicht um. Das Blatt selbst und `ErfolgeEntry` (die Karte im früheren Training) entfallen.
3. `historySegmentLabel` in `RidesScreen.kt` bekommt den Text für `ACHIEVEMENTS` (Schlüssel `rides_screen_segment_achievements`, „Erfolge“ / „Achievements“).
4. Der Schalter „Erfolge anzeigen“: `ui/more/MoreScreen.kt`, `SettingsPageContent`, Zweig `SettingsPage.REMINDERS` – dort `ErfolgeSettingsSection()` als zweiten Abschnitt anfügen und `more_page_reminders_title` auf „Erinnerungen & Motivation“ ändern. Das `item { ErfolgeSettingsSection() }` am Listenende, das der Branch mitbringt, beim Merge verwerfen.
5. Die Änderung des Branches an `ui/training/TrainingScreen.kt` entfällt ersatzlos (die Datei gibt es nicht mehr). Die Abzeichen-Zeile nach der Fahrt (`RideHighlights`) und der Backup-Anteil bleiben, wie der Branch sie bringt.

Kein eigener Tab und kein Platz in der Leiste: `SHELL_TABS` bleibt bei vier.

### Herausforderung (`origin/claude/spiel-3-herausforderung`)

Platz: Indoor → Segment „Herausforderungen“; verschicken im Blatt „Teilen“ der Tour.

1. `ui/indoor/IndoorTabScreen.kt`: `indoorSegments(challengesAvailable = true, …)`; im `when (segment)` den Zweig `IndoorSegment.CHALLENGES` von der Sammelzeile lösen und dort `IndoorChallengeSection(...)` zeigen (mit dem Kopf des Tabs wie bei „Plan“). Zustand und Aktionen kommen aus dem `IndoorViewModel` des Tab-Eintrags (`vm.challenges`, `vm.useChallenge`, `vm.downloadCatalog`, `vm.deleteChallenge`, `vm.importChallengeFile`); dafür reicht `IndoorFlowScreen` im Modus `TAB` einen Slot für das Segment durch, statt den Abschnitt auf der Startseite zu zeichnen.
2. Die Änderung des Branches an `IndoorHomeScreen.kt` (Abschnitt zwischen den Strecken) entfällt. Die Pille bei „Mitfahrer“ für eine gewählte Herausforderung bleibt.
3. „Gegen … fahren“ setzt im ViewModel Strecke und Mitfahrer und wechselt auf das Segment „Strecken“ (`wantedSegment = IndoorSegment.ROUTES`), wo „Fahren“ wie gewohnt startet.
4. Von außen (`ChallengeImportActivity` → `MainActivity`): statt nur die App zu öffnen, `requestTab(AppTab.INDOOR)` und das Segment vormerken – dafür `AppViewModel.requestIndoorRoutes()` als Vorlage nehmen und eine `requestChallenges()` danebenstellen.
5. „Herausforderung teilen“: nicht als Block in der Auswertung, sondern als Zeile im Blatt „Teilen“ der gespeicherten Indoor-Tour. Einhängen über `RideShareDialog(..., extraRows = { … })` in `ui/rides/RideShare.kt` (aufgerufen in `RideDetailHost`, `ui/rides/TourList.kt`, Block `if (shareOpen)`); die Zeile löst `ChallengeShareDialog` bzw. `shareChallengeFile` aus. Die Auswertung führt mit „Tour ansehen“ dorthin. Das Ergebnis gegen eine Herausforderung (`ChallengeResultCard`) bleibt in der Auswertung.

### Garage (`origin/claude/spiel-2-garage`)

Platz: ein Symbol in der Kopfzeile des Indoor-Tabs, Sicht „Strecken“.

1. `ui/AppNavigation.kt`: Route `AppRoutes.INDOOR_GARAGE = "indoor/garage"` ergänzen (jede Route, die kein Tab ist, gilt automatisch als gepushte Seite ohne Leiste).
2. `ui/TrailscapeApp.kt`: `composable(AppRoutes.INDOOR_GARAGE) { GarageDestination(onBack = { navController.popBackStack() }) }` und einen `openGarage`-Rückruf wie `openWorlds`; an `IndoorTabScreen` und von dort an `IndoorFlowScreen` durchreichen.
3. `IndoorHomeScreen.kt`: Der Branch bringt `onOpenGarage` und das Paletten-Symbol in den `actions` der Kopfzeile schon mit – das ist der eine Einstieg. Im gepushten Ablauf (`IndoorFlowMode.PUSHED`) das Symbol weglassen (`onOpenGarage = null` und das Symbol nur bei gesetztem Rückruf zeichnen).
4. Die Zeile „Garage“, die der Branch in `IndoorSettingsScreen.kt` einfügt, beim Merge verwerfen (ein Einstieg genügt).
5. Gesperrte Farben zeigen nur „ab Stufe N“, ohne Weg zu den Erfolgen (Entscheidung 3 der Seitenkarte).

### Raum für Gruppenfahrten (`origin/claude/spiel-5-raum`)

Der Branch hat noch keine Oberfläche (Protokoll, Client und Server-Teil). Platz für die kommende Oberfläche: Indoor → Segment „Raum“, das vierte Segment.

1. `ui/indoor/IndoorTabScreen.kt`: `indoorSegments(…, roomAvailable = true)` und den Zweig `IndoorSegment.ROOM` füllen: „Raum anlegen“ (nur mit eingetragenem eigenen Server, siehe Einstellungen → Sync mit eigenem Server) und „Raum beitreten“ (Einladungslink).
2. Der Raum braucht eine Strecke: Die Wahl im Raum setzt wie eine Herausforderung Strecke und Modus im `IndoorViewModel` des Tabs und startet über das Blatt bzw. den Knopf „Fahren“ der Sicht „Strecken“.
3. Ein Einladungslink von außen landet wie die Herausforderung über eine eigene `requestRoom()`-Funktion auf dem Segment.
4. Ob vier Segmente auf 320 dp nebeneinander passen, ist offen; wenn nicht, werden „Herausforderungen“ und „Raum“ zu einem Segment „Gemeinsam“ mit zwei Abschnitten.

Der Raum bringt die erste Netzverbindung zu einem selbst gewählten Server während der Fahrt mit sich; das ist Sache des Branches, nicht dieses Umbaus (hier ist keine Netzabfrage dazugekommen).

## Abweichungen von der Seitenkarte

| Seitenkarte | Umgesetzt | Grund |
|---|---|---|
| Plan als Seite unter „Heute“ | Plan ist das Segment „Plan“ im Indoor-Tab; „Heute“ springt mit einem Tipp dorthin. | Entscheidung 1 des Eigentümers: Der Indoor-Tab bündelt auch die Trainingspläne. |
| Indoor mit den Segmenten Bekannte \| Eigene \| Herausforderungen | Strecken \| Plan; eigene und bekannte Strecken stehen wie bisher untereinander. | Folge derselben Entscheidung; vier bis fünf Segmente passen nicht in eine Zeile. |
| Jede Strecke trägt ihren eigenen Knopf; Fußleiste entfällt | Die Startseite ist im Aufbau unverändert (Auswahl + „Fahren“ unten). | Nicht umgesetzt, siehe unten. |
| Blatt „Vor der Fahrt“ ersetzt „Optionen“, „Bereit zum Fahren“, „Wo startest du?“ | Unverändert. | Nicht umgesetzt; berührt den Ablauf bis in die Fahransicht, an der parallel gearbeitet wird. |
| Koppeln nur unter Sensoren & Trainer | Koppeln bleibt zusätzlich im Indoor-Ablauf. | Der Schalter „Ohne Trainer ausprobieren“ hängt am Indoor-ViewModel und ließ sich nicht ohne Umbau der Kopplung umziehen. |
| „Heute“: eine Karte statt drei, Umschalter Draußen \| Drinnen | Die drei Karten bleiben. | Nicht umgesetzt; nur die Sprungziele sind berichtigt. |
| Jahresziel nur noch im Fortschritt | Anzeige und Ändern nur im Fortschritt; die Aufforderung „Jahresziel setzen“ bleibt auf „Heute“, solange keines gesetzt ist. | Der Knopf soll öffnen, was er nennt; ein Sprung in eine Liste täte das nicht. |
| „Geplant“ als Filter der Tourenliste | Weiter ein Abschnitt über der Liste. | Nicht umgesetzt. |
| Tourkarte nur noch „Losfahren“ und „Details“ | Unverändert (Teilen und Löschen auch dort). | Nicht umgesetzt; keine Funktion ging verloren. |
| Blatt „Teilen“ als Blatt | Der bestehende Dialog ist erweitert (Strava-Zeile, Platz für weitere Zeilen). | Kleinerer Eingriff, gleiche Wirkung. |
| Strava im Teilen immer sichtbar | Sichtbar, wo etwas hochladbar ist: nicht bei Planungen, Touren ohne Zeitstempel und Builds ohne Strava-Schlüssel. | Eine Zeile ohne mögliche Aktion wäre eine Sackgasse. |
| „Route verwerfen“ im Blatt „Losfahren“ | „Verwerfen“ steht in der Planung, mit Rückhol-Meldung. | Im Dialog gäbe es keinen Rückweg; das war dort bewusst entfernt worden. |
| Widget-Knopf „Fahren“ öffnet „Losfahren“ | Unverändert. | Nicht umgesetzt. |
| Einführung bekommt „Touren importieren“ | Unverändert. | Nicht umgesetzt. |
| Fahrmodus: ein Bediensatz statt drei; Indoor-Fahrt: beschriftete Knöpfe, ein Dialog | Unverändert. | Nicht umgesetzt; die Indoor-Fahransicht gehört zur parallel bearbeiteten Darstellung. |
| Plan-Rezept im Menü der Seite „Plan“ | Weiter als Abschnitt in der Liste. | Nicht umgesetzt. |
| Einstellungen → Indoor mit drei Gruppen und eingeklapptem „Speicher & Diagnose“ | Die bisherige Indoor-Einstellungsseite, unverändert im Aufbau, an neuem Ort. | Die Gruppen kommen sinnvoll erst mit den acht neuen Einträgen der Branches. |

## Screenshot-Tests

Die Screenshot-Tests laufen nur mit `-Pscreenshots` und schreiben nach `build/outputs/roborazzi/`. Angepasst sind die Klickwege; Bilder, die sich bewusst ändern:

| Test | Änderung |
|---|---|
| `ScreenshotTest.tabs` | `03-training` entfällt; neu `02b-verlauf-fortschritt`, `03-indoor`, `03b-indoor-plan`. |
| `ScreenshotTest.tall` | `13-training-lang` → `13-fortschritt-lang`, `13b-training-plan-lang` → `13b-plan-lang`. |
| `ScreenshotTest.trainingHinweise` | Weg über Indoor → Plan; Bildname bleibt `16-training-hinweise`. |
| `ScreenshotTest.grosseSchrift`, `dunkel` | `24-training-gross` → `24-indoor-gross`, `53-training-dunkel` → `53-indoor-dunkel`. |
| `ScreenshotTest` (alle Bilder mit Leiste) | Die Leiste zeigt „Indoor“ statt „Training“. |
| `ScreenshotTest.karte…`, `RouteSurfaceScreenshotTest` | `07-karte`, `08-karte-wohin-offen` (und `-en`), `44-karte-langdruck-tipp`: neue Knöpfe und Zahnrad im Blatt „Wohin?“, neuer Hinweistext, Rückgängig-Symbol im Planungsblatt. |
| `ShellScreenshotTest.huelleEnglisch` | erwartet „Indoor“ statt „Training“ (`81-huelle-en`). |
| `TrainingScreenshotTest` (alle) | Weg: Verlauf → Fortschritt bzw. Indoor → Plan; Bildnamen bleiben. |
| `RidesScreenshotTest` | `tourKopfEnglisch` wartet auf den festen Knopf; neu `73a-tour-menue-en`, `73b-tour-nochmal-en`. Verlaufsbilder zeigen die Segmente. |
| `MoreScreenshotTest` | Gruppen und Titel der Einstellungen; Profil ohne Aufklappen; Backup-Seite ohne Tour-Import. |
| `IndoorOutdoorScreenshotTest` | `01-detail-gefahren`, `02-detail-geplant` zeigen das Blatt „Nochmal fahren“ statt des früheren Knopfs. |

## Nicht geprüft

- **Ungebaut.** Weder `:core:test` noch `:app:testDebugUnitTest` liefen in der Umbau-Session. Der Kotlin-Code ist ohne Compiler geschrieben und von Hand gegengelesen.
- Die Screenshot-Tests sind in den Klickwegen angepasst, aber nicht ausgeführt. `ScreenshotTest` öffnet dabei erstmals den Indoor-Tab in der ganzen App unter Robolectric (echtes `IndoorViewModel`); ob das dort durchläuft, zeigt erst ein Lauf.
- Zwei unabhängige Gegenlesungen des ganzen Diffs („sei der Compiler“) fanden keinen Build-Fehler; drei Logikfehler daraus sind behoben. Das ersetzt keinen Build.

Nur auf einem echten Gerät prüfbar:

- Ob „Indoor“ neben „Heute“, „Karte“ und „Verlauf“ auf 320 dp und mit großer Schrift einzeilig bleibt.
- Ob der Knopf „Fahren“ der Indoor-Startseite sauber über der schwebenden Leiste steht und die Liste darunter vollständig scrollt.
- Der Wechsel der Leiste beim Betreten und Verlassen von Koppeln, Vorbereiten, Fahrt und Auswertung im Indoor-Tab, einschließlich der randlosen Fahransicht.
- Der Indoor-Tab liest bei jedem Wiedereintritt Einstellungen, Strecken und die Einheit des Tages neu (`IndoorViewModel.refreshHome`); ob das einen laufenden Download oder einen schon durchgeblätterten Tagesvorschlag stört, ist offen.
- Ob ein Tabwechsel den Indoor-Zustand erhält (gewählte Strecke, laufender Download) und ob eine von außen ausgelöste Navigation während einer Indoor-Fahrt diese sauber pausiert.
- Ob versehentliche Tipps beim Verschieben oder Zoomen der Karte Punkte setzen, und ob die Ortskarte dabei stört.
- Platz im Blatt „Wohin?“ (Suchfeld, Zahnrad, zwei Knöpfe) und im Kopf des Planungsblatts (Rückgängig neben ⋮ und ✕).
- Der feste Knopf im Tourdetail über der Gestenleiste und der Abstand des Inhalts darunter.
- Der Archiv-Import direkt aus dem Verlauf mit einem großen ZIP (Fortschritts- und Ergebnisdialog).
- Einstellungen → Indoor: Öffnen, „Fahrgefühl einstellen“ mit gekoppeltem Trainer und die Rückkehr in die Einstellungen.
