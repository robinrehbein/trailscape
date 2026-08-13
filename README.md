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
- Wochenfortschritt gegen das Planziel und die zuletzt gefahrene Tour

**Aufzeichnen**
- GPS-Aufzeichnung als Vordergrunddienst — läuft bei gesperrtem Display und
  nach dem Wegwischen der App aus den Recents weiter
- Live-Anzeige von Tempo, Distanz, Fahrzeit und Höhenmetern; Pause/Weiter und
  Beenden auch direkt aus der Benachrichtigung
- Absturzsicherung: Jeder Punkt geht sofort in ein Journal
  (`<filesDir>/recording/active.jsonl`). Bricht der Prozess ab, bietet die App
  beim nächsten Start die Wiederherstellung der Tour an

**Touren**
- Tourenliste mit Distanz, Dauer, Höhenmetern, Ø-Puls und Trainingslast
- Detailansicht je Tour: gefahrene Spur auf der Karte, Höhenprofil, Tempo- und
  Pulskurve, dazu Entkopplung und VO₂max-Schätzung, wo die Daten es hergeben
- Umbenennen, Löschen, als GPX teilen, GPX importieren (z. B. aus Komoot oder
  Strava)
- Backup: alle Touren plus Trainingsprofil als eine JSON-Datei exportieren und
  wieder importieren

**Karte & Planung**
- MapLibre-Karte mit fünf Kachelstilen zur Auswahl (Straßenkarte, CyclOSM,
  OpenStreetMap, OpenTopoMap, Satellit) — umschaltbar auf der Karte und im
  Mehr-Tab, ohne API-Schlüssel
- Routenplanung mit Zielsuche über Nominatim, Fahrradtyp (Gravel, Rennrad) und
  Wegpräferenz (Gemischt, Asphalt, Radwege, kürzester Weg) — daraus wählt die
  App das passende BRouter-Profil
- Höhenprofil der geplanten Route
- Navigation auf einer gespeicherten oder geplanten Route mit Restdistanz und
  Vibrationswarnung beim Verlassen des Wegs
- Offline-Karten: Regionen über MapLibres Offline-Manager herunterladen
  (max. 250 Kacheln pro Vorgang, der sichtbare Ausschnitt darf höchstens
  150 km Kantenlänge haben) und im Mehr-Tab verwalten

**Training**
- Automatische Fitnesseinschätzung aus den aufgezeichneten Touren
- Trainingsplan mit progressiver Steigerung, Erholungswochen und
  Fortschrittsverfolgung
- Performance-Management-Chart (Fitness, Ermüdung, Form) über die Trainingslast
- Tagesempfehlung („Readiness") aus Ruhepuls, HRV (rMSSD) und Schlaf

**Gesundheitsdaten**
- Anbindung an Health Connect (dorthin spiegelt Samsung Health die
  Watch-Daten): Import von Trainingseinheiten samt Route, Herzfrequenz,
  Ruhepuls, HRV, Schlaf, VO₂max
- Ausschließlich lesend — Trailscape schreibt nichts nach Health Connect zurück

**Selfhost-Sync (optional)**
- Bidirektionale Synchronisierung der Touren mit einem eigenen Server

## Architektur

Ein Gradle-Projekt mit zwei Modulen:

| Modul | Was | Warum getrennt |
|---|---|---|
| `:core` | Reines Kotlin/JVM: Domänenmodell, GPX/Export, Statistik, Routing- und Geocoding-Clients, Navigation, komplettes Trainings- und Readiness-Modell, Health-Sync-Logik | Kein einziger Android-Import — dadurch in Sekunden und ohne Emulator testbar. 586 Unit-Tests hängen hier |
| `:app` | Android: Compose/Material-3-Oberfläche (fünf Tabs — Heute, Karte, Touren, Training, Mehr), Aufzeichnungs-Service, MapLibre-Einbettung, Health Connect, Speicherung | Alles, was ein Gerät braucht |

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
./gradlew :app:assembleDebug     # Debug-APK
./gradlew :app:installDebug      # auf ein angeschlossenes Gerät
```

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

## Testen

```bash
./gradlew :core:test              # 586 Tests des Domänenmodells
./gradlew :app:testDebugUnitTest  # Tests der plattformfreien :app-Teile
```

Was die CI vor jedem Release ausführt:

```bash
./gradlew clean :core:test :app:testDebugUnitTest :app:assembleRelease
```

`:app` hat bewusst kein Robolectric — getestet wird dort nur, was ohne
Android-Framework auskommt (Aufzeichnungs-Journal, GPX-Import,
Share-Dateinamen, Trainingsauswertung, Berichtsformat der Fehlermeldung,
Update-Prüfung).
Alles Rechnende liegt ohnehin in `:core`.

## Installation und Updates

Jeder Push auf `main` baut die App und hängt sie an das GitHub-Release
`latest`:

**<https://github.com/robinrehbein/trailscape/releases/download/latest/trailscape.apk>**

1. APK auf dem Android-Gerät herunterladen und öffnen
2. Installation aus unbekannten Quellen für den Browser erlauben
3. Beim ersten Start die Berechtigungen erteilen:
   - **Standort** — „Immer erlauben" für die Aufzeichnung im Hintergrund
   - **Benachrichtigungen** — für die Aufzeichnungs-Anzeige
   - **Health Connect** — optional, nur für die Gesundheitsdaten

Ein Update ist derselbe Weg: APK laden, öffnen, drüber installieren.

**Versionen und Changelog.** Zusätzlich zum `latest`-Alias legt jeder
main-Build ein unveränderliches Release `v2.0.<Lauf-Nummer>` an, dessen Notizen
die Commit-Betreffs seit dem vorigen solchen Release auflisten — die Übersicht
steht auf der [Releases-Seite](https://github.com/robinrehbein/trailscape/releases).
Dieselbe Nummer trägt die installierte App unter **Mehr → Über**.

**Update-Hinweis in der App.** Die App fragt höchstens einmal am Tag still im
Hintergrund die veröffentlichten Releases ab. Gibt es eine neuere Nummer als
die eigene, erscheint einmalig eine Snackbar und im Mehr-Tab eine schließbare
Karte mit dem Knopf „Herunterladen"; unter **Mehr → Über → Nach Updates
suchen** lässt sich die Prüfung jederzeit von Hand auslösen. Ohne Netz passiert
schlicht nichts — die Prüfung meldet nie einen Fehler und blockiert nie.

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

Damit sind alle Touren und das Trainingsprofil übernommen. Nicht im Backup
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

Alles bleibt lokal. Die App spricht nur mit Diensten, die für eine konkrete
Aktion nötig sind: Kachelserver für die Karte, BRouter für die
Routenberechnung, Nominatim für die Zielsuche und — wenn eingerichtet — dem
eigenen Sync-Server. Kein Analytics, keine Telemetrie, keine Werbung, kein
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
AndroidX/Compose, Kotlin, OkHttp, Google Play services, OpenStreetMap, CARTO,
CyclOSM, OpenTopoMap, Esri, BRouter, Nominatim) stehen in der App unter
**Mehr → Über → Open-Source-Lizenzen** und im Quelltext in
[`app/src/main/kotlin/de/trailscape/app/ui/more/OpenSourceNotices.kt`](app/src/main/kotlin/de/trailscape/app/ui/more/OpenSourceNotices.kt).

> **Hinweis zur Kombination mit Google Play services:** `play-services-location`
> ist proprietär und steht unter Googles SDK-Lizenz. Für eine Weitergabe durch
> Dritte unter der GPL wäre entweder eine Ausnahmeklausel dafür nötig oder ein
> Umstieg auf Androids `LocationManager` — siehe
> [Issue-Tracker](https://github.com/robinrehbein/trailscape/issues).
