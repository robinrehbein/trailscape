# 🚵 Trailscape

**Trailscape sagt dir, was du heute fahren solltest — und baut dir die Runde
dazu.** Native Android-App für Gravel- und Rennrad mit GPS-Aufzeichnung,
Routenplanung und Trainingssteuerung. Kotlin, Jetpack Compose, kein Abo, kein
Account.

## Warum?

Trainingswerkzeuge sind fast alle auf Rennrad und Wettkampf ausgerichtet,
Gravel-Werkzeuge fast alle auf Entdecken und Abenteuer. Wer auf Schotter
strukturiert trainieren will, betreibt heute drei Apps nebeneinander: eine
fürs Planen, eine fürs Aufzeichnen, eine für die Auswertung.

Trailscape schließt die Kette. Ruhepuls, HRV und Schlaf deiner Uhr ergeben
eine Tagesbereitschaft, der Trainingsplan sagt, was heute ansteht, die App
generiert dazu eine passende Rundstrecke vor der Haustür, und die gefahrene
Tour fließt zurück in die Belastungsrechnung. Komoot plant großartige Routen
und weiß nichts über dein Training; Strava kennt deine Form, generiert dazu
aber keine Route; Garmin hat beides, verbindet es aber nicht und verlangt
eigene Hardware.

Dazu **local-first**: Touren, Trainingsprofil und Auswertung liegen
ausschließlich auf dem Gerät. Kein Konto, keine Registrierung, keine
Telemetrie. Wer trotzdem synchronisieren will, stellt seinen eigenen Server
dahinter (siehe [Selfhost-Sync](#selfhost-sync)).

## Features

**Heute** (Startseite)
- Tagesbereitschaft, die heute anstehende Einheit und ein Knopf, der genau
  dafür eine passende Rundstrecke generiert
- Wochenfortschritt gegen das Planziel, darunter die **Wochenserie**: wie
  viele Wochen in Folge du gefahren bist und wie viele Tage bleiben, um sie zu
  halten (bewusst Wochen statt Tage — Ruhetage kosten nichts)
- **Als Nächstes:** die *nächste Bestweite* (ein Stück weiter als deine
  weiteste Tour der letzten sechs Wochen, mit Knopf für genau diese Runde),
  dein **Jahresziel** mit Soll und Ist und der nächstgelegene **Meilenstein**
- Ohne eine einzige Tour eine ruhige erste Runde nach Zeit (1 h / 1½ h / 2 h),
  die „Runde bauen" direkt ab deinem Standort sucht — auch als letzter Schritt
  der Einführung
- Auch ohne Trainingsplan gibt es ein Wochenziel in Kilometern. Es entsteht
  aus deinen letzten Wochen und wächst behutsam: nach drei Aufbauwochen eine
  leichtere Woche, nach einer Pause ein sanfter Wiedereinstieg. Der Balken
  unter dem Wochenstreifen zeigt den Stand
- Montags bis mittwochs ein Rückblick auf die letzte Woche: Kilometer, Touren,
  Vergleich zur Woche davor, neue Bestwerte und die Wochenserie

**Aufzeichnen**
- GPS-Aufzeichnung als Vordergrunddienst — läuft bei gesperrtem Display und
  nach dem Wegwischen der App aus den Recents weiter
- Live-Anzeige von Tempo, Distanz, Fahrzeit und Höhenmetern; Pause/Weiter und
  Beenden auch direkt aus der Benachrichtigung
- Absturzsicherung: Jeder Punkt geht sofort in ein Journal
  (`<filesDir>/recording/active.jsonl`). Bricht der Prozess ab, bietet die App
  beim nächsten Start die Wiederherstellung der Tour an
- Bluetooth-Sensoren ohne Uhr: Pulsgurt, Leistungsmesser und
  Trittfrequenzsensor (Bluetooth LE, Standard-Profile Herzfrequenz, Cycling
  Power, Speed & Cadence) unter Mehr → Sensoren koppeln; während der
  Aufzeichnung verbindet die App sich von selbst, zeigt Puls, Watt und
  Trittfrequenz im Fahrmodus und speichert sie je Punkt. Ein gekoppelter Gurt
  hat Vorrang vor dem Uhr-Puls. Gemessene Leistung ersetzt in der Auswertung
  die GPS-Schätzung

**Touren**
- Tourenliste mit Distanz, Dauer, Höhenmetern, Ø-Puls und Trainingslast
- Detailansicht je Tour: gefahrene Spur auf der Karte, Höhenprofil, Tempo- und
  Pulskurve, Ø Leistung und Ø Trittfrequenz, wo gemessen, dazu Entkopplung
  und VO₂max-Schätzung, wo die Daten es hergeben —
  und oben „Erfolge dieser Tour“ (damals gesetzte Bestwerte, überschrittene
  Meilensteine; nach einer Aufzeichnung auch als Meldung) sowie
  „Was die Tour gebracht hat“: Fitness/Frische, Stand zum Wochenziel,
  neu entdeckte Kacheln und neue Bestzeiten, jeweils nur, wo es Daten gibt.
  Nach dem Beenden einer Aufzeichnung öffnet die Kachel „Auswertung“ auf dem
  Tourblatt der Karte genau diese Ansicht
- „Diese Runde“: Wer eine Strecke wiederholt, sieht im Tour-Detail den Vergleich
  mit den früheren Fahrten (Rang, Abstand zum letzten Mal und zur Bestzeit,
  nach Bewegungszeit). Hin- und Rückweg einer Strecke von A nach B zählen
  getrennt, Runden unabhängig von der Richtung
- Umbenennen, Löschen, als GPX (mit Puls, Trittfrequenz und Leistung) oder als
  Bild teilen (Story 9:16 oder Quadrat, mit Spur, Höhenprofil und Kennzahlen;
  Start und Ziel ab Werk ausgeblendet; auch direkt vom Tourblatt der Karte),
  GPX importieren (z. B. aus Komoot oder
  Strava). Dateien ohne echte Zeitstempel (nur Strecke: `<rte>` oder `<trk>`
  ohne `<time>`, auch mit nur wenigen oder lauter gleichen Stempeln) gelten als
  **Planung**: Sie öffnen auf der Karte, zählen nicht in Statistik,
  Wochenfortschritt, Fitness und Entdeckt-Kacheln und lassen sich abfahren
  (Abbiegehinweise aus der Geometrie, ohne Straßennamen). Beim Teilen/Öffnen
  sagt ein Dialog, als was die Datei erkannt wurde, und dreht es einmalig um;
  in der Tourdetailansicht wandelt „In Planung umwandeln“ bzw. „Als gefahrene
  Tour übernehmen“ in beide Richtungen um (Regeln: `TrackOrigin.kt` in `:core`)
- Optional zu Strava hochladen: von Hand in der Tour („Zu Strava hochladen“,
  danach „Auf Strava ansehen“) oder automatisch nach dem Beenden einer
  Aufzeichnung (Schalter unter Einstellungen → Strava, ab Werk aus). Ohne
  Verbindung geht nichts an Strava. Nur in Builds mit hinterlegten
  Strava-App-Zugangsdaten sichtbar
- Backup: alle Touren, Trainingsprofil und Jahresziel als eine JSON-Datei
  exportieren und wieder importieren

**Karte & Planung**
- MapLibre-Karte mit sechs Kartenstilen zur Auswahl (Straßenkarte, CyclOSM,
  OpenStreetMap, OpenTopoMap, Satellit, Vektorkarte von OpenFreeMap) —
  umschaltbar auf der Karte und im Mehr-Tab, ohne API-Schlüssel
- Routenplanung mit Zielsuche über Nominatim (gesucht wird beim Absenden,
  nicht beim Tippen), Fahrradtyp (Gravel, Rennrad) und
  Wegpräferenz (Gemischt, Asphalt, Radwege, kürzester Weg) — daraus wählt die
  App das passende BRouter-Profil
- Rundkurs-Generator ab Standort oder angetipptem Ort: Länge, Untergrund,
  optional „Neue Gegenden bevorzugen“ und „Wind berücksichtigen“ — dann
  bevorzugt die Suche als empfohlene Runde eine, die gegen den Wind hinaus und
  mit Rückenwind nach Hause führt
  (aktueller Wind von Open-Meteo, nur mit Schalter, Startpunkt auf etwa 1 km
  gerundet)
- Vorgeschlagene Runden ohne Stichstrecken: Führt das Routing zu einem
  Hilfspunkt in eine Sackgasse hinein und denselben Weg zurück, wird das Stück
  herausgeschnitten. Runden, die zu einem guten Teil hin und zurück über
  denselben Weg führen, rutschen in der Liste nach hinten und sagen es dazu
- Der Untergrund zählt bei der Reihenfolge der Vorschläge mit: Im Gravel-Profil
  gewinnt bei ähnlicher Länge die Runde mit mehr unbefestigten Wegen, im
  Asphalt-Profil die mit weniger
- Höhenprofil der geplanten Route
- Navigation auf einer gespeicherten oder geplanten Route mit Restdistanz und
  Vibrationswarnung beim Verlassen des Wegs
- Offline-Karten: Regionen über MapLibres Offline-Manager herunterladen
  (nur mit der Vektorkarte von OpenFreeMap — die übrigen Kachelserver
  verbieten Vorab-Downloads; max. 250 Kacheln pro Vorgang, der sichtbare
  Ausschnitt darf höchstens 150 km Kantenlänge haben) und im Mehr-Tab
  verwalten

**Training**
- Zwei Sichten: **Fortschritt** und **Plan**
- Fortschritt: Kilometer je Woche über zwölf Wochen mit dem Vergleich „letzte
  vier Wochen gegen die vier davor“, Jahresziel (Soll ab dem Tag, an dem du es
  setzt; Hochrechnung aus den letzten acht Wochen), Bestwerte (weiteste Tour,
  meiste Höhenmeter, längste Fahrzeit, schnellster Schnitt ab 20 km — ein Tipp
  öffnet die Tour), Meilensteine und die Summen aller Touren
- Monats-Challenges aus der eigenen Historie (Kilometer, Höhenmeter, Tage auf
  dem Rad, eine lange Tour), jeden Monat neu und etwa 10 % über dem Schnitt der
  letzten drei Monate
- Automatische Fitnesseinschätzung aus den aufgezeichneten Touren
- Trainingsplan mit progressiver Steigerung, Erholungswochen und
  Fortschrittsverfolgung
- Ziel mit optionaler Zielzeit und Prognose der Zielzeit aus den eigenen
  Touren der letzten sechs Wochen, heute und mit dem Plan am Renntag
- Performance-Management-Chart (Fitness, Ermüdung, Form) über die Trainingslast
- Tagesempfehlung („Readiness") aus Ruhepuls, HRV (rMSSD) und Schlaf

**Indoor (Prototyp)**
- Training auf dem Smart-Trainer zu Hause: Du fährst mit einem Avatar durch
  die nachgebaute 3D-Welt deiner eigenen Strecke — einer gespeicherten
  Planung, einer gefahrenen Tour (auf Wunsch mit dem Geist deiner früheren
  Fahrt) oder einer Testwelt. Einstieg über die Karte auf *Heute*, den Dialog
  vor dem Aufzeichnen oder *Mehr*
- Zwei Modi: freie Fahrt (der Trainer folgt der Steigung der Strecke samt
  Belag) oder die Einheit des Tages mit festen Wattvorgaben (ERG). Virtuelle
  Gänge (einstellbares Kettenblatt, Vorgabe 48/14), Gang schalten im HUD,
  Höhenprofil mit deiner Position
- Geräte: Rollentrainer mit Bluetooth-FTMS (z. B. Wahoo KICKR CORE), als
  Schalttaster best effort der Zwift Click, dazu ein Pulsgurt. Ohne Trainer
  geht es mit dem **Demo-Trainer** (Regler für Leistung und Trittfrequenz)
- Die Welt entsteht erst nach „Welt laden“ auf dem Gerät: Höhen aus
  Terrarium-Kacheln, Gebäude und Landnutzung aus OpenFreeMap, Luftbild nur in
  Ländern aus dem Register (derzeit NRW). Vorher zeigt ein Voranschlag Hosts,
  Datenmenge und Platzbedarf; fertige Welten fahren offline und lassen sich
  unter *Mehr* verwalten. „Ohne Welt fahren“ geht immer
- **Bekannte Strecken** (zusätzlich zu den eigenen): zehn bekannte Abschnitte
  mit fertig gebauter Welt, z. B. Alpe d'Huez, Oude Kwaremont/Koppenberg,
  Wald von Arenberg, Schotter bei Siena, Sa Calobra, Flint Hills, Großer
  Feldberg, Schauinsland, Kahler Asten, Rennsteig. Die Liste ist ohne Netz
  sichtbar; eine Welt (rund 50–115 MB) wird erst nach „Laden“ aus dem
  GitHub-Release geladen — mit Fortschritt, Abbruch, Fortsetzen und
  Prüfsumme — und fährt dann wie eine eigene Welt. Details:
  `docs/indoor/catalog.md`
- Die Fahrt wird als Indoor-Tour gespeichert (mit Leistung und
  Trittfrequenz, als GPX/FIT exportierbar, auf Wunsch zu Strava mit
  Trainer-Kennzeichen). Sie zählt für Belastung, Wochenziel und Serie, nicht
  für Neue Wege oder Entdeckt-Kacheln
- Grenzen: Prototyp. Die 3D-Darstellung ist auf einem echten Gerät noch
  ungeprüft; es gibt keinen Hintergrundbetrieb — verlässt du die App,
  pausiert die Fahrt; Luftbild nur in den Registerländern. Details und
  Prüfliste: `docs/indoor.md`, `docs/indoor/app.md`

**Erinnerungen** (optional, standardmäßig alle aus)
- **Tageseinheit** morgens zur eingestellten Uhrzeit (Vorgabe 7:00): was heute
  ansteht — an Ruhetagen der Hinweis darauf, ohne Trainingsplan gar nichts
- **Wochenrückblick** sonntagabends (Vorgabe 18:00): gefahrene gegen geplante
  Kilometer der laufenden Woche. Ohne Trainingsplan stattdessen Kilometer,
  Touren und der Vergleich zur Vorwoche
- **Anstupser** nach fünf Tagen ohne Aufzeichnung, höchstens einmal pro Woche.
  Läuft eine Wochenserie seit mindestens zwei Wochen und fehlt diese Woche
  noch, meldet er sich stattdessen ab Freitag mit den verbleibenden Tagen
- Jeder Anlass einzeln abschaltbar unter **Mehr → Erinnerungen**. Ein einziger
  täglicher Hintergrundlauf (WorkManager) entscheidet beim Auslösen, was fällig
  ist; die Meldungen entstehen vollständig auf dem Gerät — kein Push-Server,
  kein Konto, kein Netzzugriff. Antippen öffnet den „Heute"-Tab

**Startbildschirm-Widget**
- Zeigt Wochenserie und Stand zum Wochenziel; ein Tipp öffnet die App
- Aktualisiert sich nach einer gespeicherten Tour und wenn die App in den
  Hintergrund geht
- Rechnet ausschließlich mit den lokal gespeicherten Touren

**Gesundheitsdaten**
- Anbindung an Health Connect (dorthin spiegelt Samsung Health die
  Watch-Daten): Import von Trainingseinheiten samt Route, Herzfrequenz,
  Ruhepuls, HRV, Schlaf, VO₂max
- Nur Radfahrten werden als Touren übernommen. Mit der optionalen
  Historien-Freigabe holt der erste Import einmalig die Radfahrten der letzten
  12 Monate (abschnittsweise gespeichert, ein Abbruch setzt dort wieder an);
  ohne sie gibt Health Connect nichts heraus, was mehr als 30 Tage vor dem
  Verbinden liegt
- Touren, deren Route Health Connect nur nach einer Einzel-Freigabe
  herausgibt, kommen zunächst ohne Karte; die Einstellungen zeigen, wie viele
  es sind, und holen die Routen per Knopf nach
- Ausschließlich lesend — Trailscape schreibt nichts nach Health Connect zurück

**Galaxy Watch**
- Die Trailscape-Uhr-App ist eine Begleiterin: Sie schickt Puls und GPS live
  ans Handy, aufgezeichnet wird dort. Ohne Handy zeichnet sie keine Tour auf
- Ohne Handy unterwegs? Die Fahrt mit Samsung Health auf der Uhr aufzeichnen —
  Trailscape importiert sie danach über Health Connect
- Kachel und Zifferblatt-Komplikation zeigen Wochenserie und Fortschritt zum
  Wochenziel auf einen Blick (rechnet das Handy, lokal, ohne Netz)

**Sprache**
- Deutsch und Englisch – folgt der Systemsprache (Deutsch, wenn das Gerät auf
  Deutsch steht, sonst Englisch), umstellbar unter **Mehr → Sprache**;
  Sprachansagen in der gewählten Sprache. Regeln für Übersetzungen:
  `docs/i18n.md`

**Selfhost-Sync (optional)**
- Bidirektionale Synchronisierung der Touren mit einem eigenen Server

## Architektur

Ein Gradle-Projekt mit zwei Modulen:

| Modul | Was | Warum getrennt |
|---|---|---|
| `:core` | Reines Kotlin/JVM: Domänenmodell, GPX/Export, Statistik, Routing- und Geocoding-Clients, Navigation, komplettes Trainings- und Readiness-Modell, Health-Sync-Logik | Kein einziger Android-Import — dadurch in Sekunden und ohne Emulator testbar. 1338 Unit-Tests hängen hier |
| `:app` | Android: Compose/Material-3-Oberfläche (vier Tabs — Heute, Karte, Indoor, Verlauf; Einstellungen hinterm Zahnrad, siehe `docs/ux/umbau.md`), Aufzeichnungs-Service, MapLibre-Einbettung, Health Connect, Speicherung | Alles, was ein Gerät braucht |

Weitere Bausteine:

- **HTTP** — `:core` definiert nur das schmale Interface
  `de.trailscape.core.HttpClient`; `:app` implementiert es mit OkHttp
  (`data/OkHttpClientAdapter.kt`). So bleibt `:core` frei von Netzwerk-Stacks.
- **JSON** — von Hand über `kotlinx.serialization`s `JsonObject`-Baukasten
  (`core/.../JsonSupport.kt`), bewusst ohne `@Serializable`-Codegen: Das
  Dateiformat muss byteweise zu dem passen, das Version 1.x geschrieben hat,
  inklusive der Unterscheidung „fehlender Schlüssel" vs. „explizites `null`".
- **Speicherung** — eine JSON-Datei pro Tour unter `<filesDir>/rides/`,
  Einstellungen und Trainingsprofil in den SharedPreferences.
- **Zustand** — ein ViewModel im Activity-Scope (`ui/AppViewModel.kt`), das
  alle Tabs teilen; der Aufzeichnungszustand kommt aus dem
  `RecordingRepository` direkt vom Service.
- `server/` enthält den optionalen Sync-Server, `tool/` das Skript zum
  Erzeugen des App-Icons aus einer Quellgrafik.

## Bauen

Voraussetzungen: **JDK 21** und ein **Android SDK** mit API 36 (compileSdk 36,
minSdk 26). Der Pfad zum SDK gehört in `local.properties`:

```properties
sdk.dir=/pfad/zum/android-sdk
```

```bash
./gradlew :app:assembleRelease   # Release-APK -> app/build/outputs/apk/release/
./gradlew :app:bundleRelease     # Play-Bundle -> app/build/outputs/bundle/release/
./gradlew :app:assembleDebug     # Debug-APK
./gradlew :app:installDebug      # auf ein angeschlossenes Gerät
```

Dieselben beiden Aufgaben gibt es für den Wear-OS-Spike (`:wear:assembleRelease`,
`:wear:bundleRelease`, siehe [docs/wear-spike.md](docs/wear-spike.md)). APK und
Bundle einer Variante entstehen aus **demselben** R8-Lauf — sie tragen denselben
Code und dieselbe `mapping.txt`, nur eine andere Verpackung.

Der Release-Build läuft durch R8 (`minifyEnabled` + `shrinkResources`); die
Regeln stehen in [`app/proguard-rules.pro`](app/proguard-rules.pro), die
Rückübersetzungstabelle landet unter
`app/build/outputs/mapping/release/mapping.txt`.

**Signierung:** Der Release-Schlüssel liegt nicht im Repository. Ohne die
Umgebungsvariablen `RELEASE_KEYSTORE_PATH` und `RELEASE_KEYSTORE_PASSWORD`
fällt der Build bewusst auf den Debug-Schlüssel zurück und bleibt grün — ein so
gebautes APK lässt sich aber nicht als Update über die verteilte Installation
legen. In der CI kommt der Schlüssel aus dem Secret
`RELEASE_KEYSTORE_BASE64`.

**Strava (optional):** Die Zugangsdaten der Strava-API-App stehen ebenfalls
nicht im Repository. Der Build liest sie aus den Umgebungsvariablen
`STRAVA_CLIENT_ID`/`STRAVA_CLIENT_SECRET` oder aus `strava.clientId`/
`strava.clientSecret` in `~/.gradle/gradle.properties` — niemals aus einer
Datei im Repo. Fehlen sie, ist die Strava-Funktion unsichtbar und der Build
bleibt grün. In der CI kommen sie aus den gleichnamigen Secrets, nur bei
Pushes auf `main`.

## Testen

```bash
./gradlew :core:test              # 1338 Tests des Domänenmodells
./gradlew :app:testDebugUnitTest  # 656 Tests in :app, davon 82 Screenshot-Tests
./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*Screenshot*'
                                  # nur die Screenshots, PNGs unter app/build/outputs/roborazzi/
```

Was die CI vor jedem Release ausführt:

```bash
./gradlew :core:test :app:testDebugUnitTest -Pscreenshots \
          :app:assembleRelease :app:bundleRelease \
          :wear:assembleRelease :wear:bundleRelease
```

In `:app` laufen die meisten Tests ohne Android-Framework (Aufzeichnungs-Journal,
GPX-Import, Share-Dateinamen, Trainingsauswertung, Berichtsformat der
Fehlermeldung, Update-Prüfung, Deckungsgleichheit der deutschen und englischen
Texte). Robolectric kommt nur für die Screenshot-Tests zum Einsatz; sie laufen
nur mit `-Pscreenshots`, in der CI also mit, auf Deutsch und — mit „-en“ im
Dateinamen — auf Englisch. Alles Rechnende liegt ohnehin in `:core`.

Was auf einem echten Gerät geprüft werden muss, steht in der
[Prüfliste fürs Gerät](docs/geraete-pruefliste.md).

## Installation und Updates

Der neue Play-Eintrag verwendet `de.robinrehbein.trailscape`. Er wird zunächst
intern getestet. Diese App installiert sich wegen der neuen Paketkennung neben
der bisherigen `io.github.robinrehbein.trailscape`-App. Touren und Profil können
per Backup exportiert und in der neuen App importiert werden; Android übernimmt
die Daten nicht automatisch.

Jeder Push auf `main` baut die App und hängt sie an das GitHub-Release
`latest`:

**<https://github.com/robinrehbein/trailscape/releases/download/latest/trailscape.apk>**

1. APK auf dem Android-Gerät herunterladen und öffnen
2. Installation aus unbekannten Quellen für den Browser erlauben
3. Beim ersten Start die Berechtigungen erteilen:
   - **Standort** — „Während der Nutzung erlauben" genügt: Die Aufzeichnung
     läuft als Vordergrunddienst und damit auch bei gesperrtem Display weiter;
     eine Hintergrund-Standortberechtigung fragt die App gar nicht an
   - **Benachrichtigungen** — für die Aufzeichnungs-Anzeige und, falls
     eingeschaltet, für die Erinnerungen
   - **Health Connect** — optional, nur für die Gesundheitsdaten

Ein Update ist derselbe Weg: APK laden, öffnen, drüber installieren.

**Versionen und Changelog.** Zusätzlich zum `latest`-Alias legt jeder
main-Build ein unveränderliches Release `v2.0.<Lauf-Nummer>` an, dessen Notizen
die Commit-Betreffs seit dem vorigen solchen Release auflisten — die Übersicht
steht auf der [Releases-Seite](https://github.com/robinrehbein/trailscape/releases).
Dieselbe Nummer trägt die installierte App unter **Mehr → Über**.

**Die `.aab`-Dateien am versionierten Release.** Neben der APK hängen dort
`trailscape-phone.aab` und `trailscape-wear.aab` — Android App Bundles mit
dem GitHub-Release-Schlüssel. Auf einem Gerät sind sie nicht installierbar.
Die bestehende Play-App erwartet einen anderen Upload-Schlüssel; diese
Release-Dateien können dort deshalb **nicht hochgeladen werden**.

**Bundles für Google Play.** Der Job `play_internal` baut bei jedem Push auf
`main` Handy- und Wear-Bundle mit dem vorhandenen Play-Upload-Schlüssel.
Unter **Actions → Build → erfolgreicher main-Lauf → Artifacts** liegt
`trailscape-play-bundles-<Lauf-Nummer>` mit `trailscape-phone-play.aab` und
`trailscape-wear-play.aab`. Diese Dateien sind für die Play Console bestimmt
und bleiben 90 Tage verfügbar. Das Handy-Bundle wird weiterhin automatisch
im internen Test veröffentlicht; das Wear-Bundle kann im separaten
Wear-OS-Test-Track manuell hochgeladen werden.

> **Vor dem ersten Play-Upload lesen:** Play signiert die ausgelieferten APKs
> standardmäßig mit einem eigenen Schlüssel und degradiert den Projektschlüssel
> zum bloßen Upload-Schlüssel. Play-Installation und GitHub-APK wären dann
> unterschiedlich signiert und ließen sich nicht übereinander aktualisieren.
> Wer das vermeiden will, muss **bei der Erstanlage der Play-App** genau diesen
> Keystore als App-Signaturschlüssel hochladen — später ist das nicht mehr
> möglich.

**Update-Hinweis in der App.** Die App fragt höchstens einmal am Tag still im
Hintergrund die veröffentlichten Releases ab. Gibt es eine neuere Nummer als
die eigene, erscheint einmalig eine Snackbar und im Mehr-Tab eine schließbare
Karte mit dem Knopf „Herunterladen"; unter **Mehr → Über → Nach Updates
suchen** lässt sich die Prüfung jederzeit von Hand auslösen. Der stille Check
lässt sich an derselben Stelle abschalten („Täglich still nach Updates
suchen") — dann geht beim Start keine Anfrage an GitHub hinaus. Ohne Netz
passiert schlicht nichts — die Prüfung meldet nie einen Fehler und blockiert
nie. Bei einer Installation über Google Play entfällt all das: Die App fragt
dann nie bei GitHub nach, und unter **Mehr → Über** steht nur „Updates kommen
über Google Play.“

## Umstieg von Version 1.x

Version 2.x ist die neu geschriebene native App. Sie trägt dieselbe
Paketkennung wie Version 1.x (`io.github.robinrehbein.trailscape`), aber einen
**neuen Signierschlüssel** — Android verweigert deshalb die Installation über
die alte Version. Einmalig ist eine Neuinstallation nötig:

1. In der alten App: **Mehr → Daten & Backup → Backup exportieren**, Datei
   sicher ablegen (Drive, Mail, Dateien-App)
2. Alte App deinstallieren
3. `trailscape.apk` aus dem `latest`-Release installieren
4. **Mehr → Daten & Backup → Backup importieren**, die Datei auswählen

Damit sind alle Touren, das Trainingsprofil und das Jahresziel übernommen. Nicht im Backup
enthalten sind heruntergeladene Offline-Karten — die lädt man neu.

Ab Version 2.x sind Updates wieder normale Installationen über die
bestehende App; der Schlüssel bleibt jetzt stabil.

## Selfhost-Sync

Optional lassen sich Touren mit einem selbst gehosteten Server abgleichen. Ein
leichtgewichtiger Node.js-Server (eine Datei, keine externen Abhängigkeiten)
legt sie JSON-basiert im Dateisystem ab, authentifiziert wird per Token, ein
Docker-Image liegt bei. Setup: [`server/README.md`](server/README.md).
Konfiguriert wird die Verbindung in der App unter **Mehr → Sync**.

## Datenschutz

Alles bleibt lokal. Die App spricht mit den Diensten, die für eine konkrete
Aktion nötig sind: Kachelserver für die Karte, BRouter für Routenberechnung
und Kachel-Downloads, die Geodatenquellen für Indoor-Welten (nur nach „Welt
laden“), GitHub für vorgebaute Indoor-Welten bekannter Strecken (nur nach
„Laden“), Nominatim für die Zielsuche und — wenn eingerichtet —
dem eigenen Sync-Server bzw. Strava (nur nach dem Verbinden des eigenen
Strava-Kontos). Dazu kommt genau eine Anfrage ohne Nutzeraktion: die
tägliche stille Update-Prüfung gegen die GitHub-Releases, abschaltbar unter
**Mehr → Über** (nur bei der APK von GitHub; bei Installation über Google Play
entfällt sie). Kein Analytics, keine Telemetrie, keine Werbung, kein
Konto.

Was genau wann an wen geht, steht ausführlich und nachprüfbar in
[`PRIVACY.md`](PRIVACY.md) — in der App erreichbar über **Mehr → Über →
Datenschutz**.

## Fehler melden

Trailscape hat kein Crashlytics und kein Sentry — ohne eine Meldung erfährt
niemand von einem Problem. Stattdessen zwei Wege, beide freiwillig und beide
mit vorheriger Ansicht des kompletten Berichts:

- **Absturz**: Stürzt die App ab, landet ein rein technischer Bericht
  (Stacktrace, App-/Android-Version, Gerät, Speicherstand) in
  `<filesDir>/crash/last-crash.txt` — und sonst nirgends. Beim nächsten Start
  fragt die App einmal nach: ansehen, auf GitHub melden, teilen oder
  verwerfen.
- **Problem melden**: **Mehr → Über → Problem melden** öffnet denselben Dialog
  ohne Absturz, wahlweise mit der Diagnose des letzten Health-Syncs als
  Anhang.

„Auf GitHub melden" öffnet nur ein vorbefülltes
[Issue-Formular](https://github.com/robinrehbein/trailscape/issues/new) im
Browser; abgeschickt wird es dort von dir. Wer kein GitHub-Konto hat, nimmt
„Teilen". Die App selbst sendet nichts.

## Lizenz

Copyright © 2026 Robin Rehbein

Trailscape ist freie Software: Du darfst sie unter den Bedingungen der **GNU
General Public License, Version 3** oder (nach deiner Wahl) einer späteren
Version weitergeben und verändern. Der vollständige Lizenztext liegt in
[`LICENSE`](LICENSE).

Copyleft statt einer freizügigen Lizenz, weil es hier um Datenhoheit geht: Die
GPL verhindert, dass jemand Trailscape als Closed-Source-Fork mit Tracking und
Abo weiterverkauft, und sie ist die üblichste Lizenz für eine spätere Aufnahme
bei F-Droid.

Die Lizenzen der verwendeten Bibliotheken und Datenquellen (MapLibre,
AndroidX/Compose, Kotlin, OkHttp, OpenStreetMap, FOSSGIS, CyclOSM, OpenTopoMap,
Esri, OpenFreeMap/OpenMapTiles, BRouter, Nominatim) stehen in der App unter
**Mehr → Über → Open-Source-Lizenzen** und im Quelltext in
[`app/src/main/kotlin/de/trailscape/app/ui/more/OpenSourceNotices.kt`](app/src/main/kotlin/de/trailscape/app/ui/more/OpenSourceNotices.kt).
Bluetooth über die Android-Plattform-API, keine zusätzliche Bibliothek.

**Eine proprietäre Abhängigkeit — mit Absicht.** Trailscape kam früher mit
`com.google.android.gms:play-services-location`; diese Abhängigkeit bleibt
entfernt (Aufzeichnung und Karten-Screen sitzen direkt auf Androids eigenem
`android.location.LocationManager`). Für die Anbindung der Wear-OS-Uhr
(Live-Puls und GPS während der Fahrt) gibt es dagegen keinen freien Ersatz:
Die Wear-OS-Datenschicht (`MessageClient`/`CapabilityClient`) existiert
ausschließlich in `com.google.android.gms:play-services-wearable`. Diese eine
Abhängigkeit ist deshalb bewusst an Bord — proprietär, unter Googles
SDK-Lizenz. Wer die App ohne Uhr nutzt, berührt diesen Code nicht; alles
Übrige steht weiterhin unter freien Lizenzen (siehe Open-Source-Lizenzen in
der App).
