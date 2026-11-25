# Indoor — Welten auf dem eigenen Server bauen (Laserqualität)

Stand: 24.11.2026, Branch `claude/indoor-serverbau`.

Entscheidung des Gründers: „Server-Bau in Laserqualität: ja.“ Welten mit der
Datenstufe „Laser“ (DGM1, nDOM, LoD2, Luftbild; `docs/indoor/lidar.md`) gab
es bisher nur für vorgebaute Katalogstrecken, weil ihr Bau zu viel Download
(200–250 MB je Strecke) und Speicher (bis ~1,1 GB Heap) für ein Handy braucht.
Jetzt geht es auch für **eigene** Strecken: Die App schickt die Strecke an
den **eigenen** Selfhost-Server, der baut die Welt, die App lädt das fertige
Paket. Die Strecke verlässt dabei das Gerät — an den Server, den der Nutzer
selbst betreibt (kein fremder Dienst, kein Konto).

```
App (Schritt „Welt laden“, eigene Tour)
  │ GET  /api/worlds/info           ← nur: gibt es den Bau-Dienst? welche Welt-Version?
  │ POST /api/worlds/jobs           ← Strecke (Koordinaten, Höhen, Beläge, Name), erwartete Welt-ID
  │ GET  /api/worlds/jobs/<id>      ← alle 3 s: Warteschlange, Bau x %, fertig
  │ GET  /api/worlds/jobs/<id>/package   (Range, SHA-256)
  │ DELETE /api/worlds/jobs/<id>    ← nach dem Installieren bzw. bei „Abbrechen“
  ▼
Sync-Server (server/server.mjs) ── /api/worlds/* nach Anmeldung durchgereicht (WORLDS_URL) ──►
Bau-Dienst (server/worlds/worlds.mjs, Node, eine Warteschlange, ein Bau gleichzeitig)
  │ spawn (ohne Shell): indoor-dev server-build --job <dir> --cache <dir>
  ▼
Werkzeug indoor-dev (JVM): createLaserWorldBuilder + Katalog-Luftbild → checkWorld → package.zip
  └─► Geobasis NRW / LGL BW / Terrarium / OpenFreeMap / Luftbild-WMS (feste Positivlisten)
```

## Architektur (Entscheidungen)

- **Eigener Prozess „trailscape-worlds“** neben dem Sync-Server, wie
  empfohlen. HTTP-Schicht in Node (eine Datei, keine Abhängigkeiten, gleiche
  Token-Prüfung und Sperre nach Fehlversuchen wie `server.mjs`); der Weltbau
  läuft **je Auftrag als eigener JVM-Prozess** (`indoor-dev server-build`).
  Begründung: Der Bau braucht 1–2 GB Speicher; als Kindprozess gibt er ihn
  nach jedem Auftrag frei, ein `OutOfMemoryError` oder ein Hänger reißt den
  HTTP-Dienst nicht mit, „Abbrechen“ ist ein `SIGTERM`, und eine Zeitgrenze
  (`BUILD_TIMEOUT_MIN`) ist einfach. Eine HTTP-Schicht in der JVM
  (`com.sun.net.httpserver`) hätte eine Laufzeit gespart, aber diese Vorteile
  verloren.
- **Werkzeug als Distribution**: Gradle `application` liefert schon
  `:indoor-dev:installDist` (Startskript + Jars) — kein eigenes Fat-Jar
  nötig. Mit `-Ptrailscape.toolOnly=true` lässt `settings.gradle.kts` `:app`
  und `:wear` weg; der Bau braucht dann kein Android-SDK (Dockerfile).
- **Eine Adresse für die App**: Der Sync-Server reicht `/api/worlds/*` nach
  erfolgreicher Anmeldung an `WORLDS_URL` durch (Datenstrom, `Range`). Der
  Bau-Dienst hat keinen Port nach außen und prüft den Token trotzdem selbst.
  Wer lieber den eigenen Reverse-Proxy nimmt, leitet `/api/worlds/` direkt
  auf Port 8081.
- **Gleiche Strecke wie auf dem Gerät**: Die App bereitet die Strecke genau
  wie für den Bau auf dem Gerät auf (Belagsabgleich, `IndoorRoute`) und
  schickt sie mit der dafür geplanten Welt-ID. Die Welt-ID hängt nur an
  Geometrie, Belag, Qualität und Welt-Version — **nicht** an der Datenstufe.
  Deshalb ersetzt das Server-Paket die Gerätewelt derselben Strecke im
  selben Verzeichnis; `findWorldForRide` findet sie wie jede Welt. Der Server
  prüft nach dem Planen, dass seine Welt-ID der erwarteten entspricht
  (`WORLD_ID_MISMATCH`).
- **Welt-Version**: `GET /api/worlds/info` meldet `builderId`
  (`indoor-world/11`); weicht sie von der App ab, zeigt die App statt des
  Angebots „Welt-Version passt nicht“ und sagt anhand der Nummer, ob der
  Server oder die App zu aktualisieren ist. Ein Auftrag mit anderer Version
  bekommt `409 BUILDER_MISMATCH`.
- **Wiederverwenden**: Schlüssel eines Auftrags = SHA-256 über
  Welt-Version, Qualität, Laser-Schalter und Strecke (ohne Name). Gleiche
  Strecke + gleiche Version → derselbe Auftrag bzw. dasselbe fertige Paket
  (`200` statt `201`). Ein gescheiterter Auftrag wird durch einen neuen
  ersetzt.
- **Installation**: über den Installationsweg der Katalogwelten
  (`CatalogStore.installPackage`, Paketformat aus `CatalogPackage.kt`,
  Kennung = Welt-ID, Version 1): Prüfsumme des Pakets, jede Datei gegen
  `package.json`, kein Zip-Slip, erst entpacken, dann einsortieren. Statt
  `app-catalog.json` schreibt der Server-Bau `app-source.json` (Tour) und
  `app-server.json` (Laser-Quelle, Zeitpunkt).
- **Nach dem Installieren löscht die App den Auftrag auf dem Server** —
  die Strecke liegt dort nicht länger als nötig. Wer dieselbe Strecke auf
  einem zweiten Gerät will, baut sie dort neu (mit warmem Zwischenspeicher
  rund 20 s).

## Regionen ehrlich

Laserdaten gibt es nur in **Nordrhein-Westfalen** und **Baden-Württemberg**.
Der Weltbau nimmt höchstens **eine** Quelle: die, in deren Rechteck die
meisten von rund 50 Probepunkten liegen, und nur bei mindestens der Hälfte
(`loadLaserData`, unverändert). Die App rechnet dieselbe Regel vorher ohne
Netz nach (`laserPreviewOfPoints`, `:core`) und sagt im Angebot:

| Fall | Text in der App | Was der Server baut |
|---|---|---|
| ganze Strecke im Rechteck | „Laserdaten: die ganze Strecke liegt in Nordrhein-Westfalen.“ | Laser im ganzen Korridor |
| ≥ 50 % | „etwa 72 % der Strecke liegen in Baden-Württemberg. Außerhalb bleibt das bisherige Gelände.“ + Hinweis auf das grobe Rechteck | Laser, wo der Landesdienst Daten liefert; außerhalb Terrarium + Versatz, Bäume/Gebäude wie ohne Laser |
| < 50 % | „Nur etwa 30 % … – zu wenig. Der Server baut ohne Laserdaten.“ | Bau ohne Laser (wie Gerät, aber mit Katalog-Luftbild und ohne Handy-Speichergrenze) |
| nirgends | „Laserdaten gibt es nur in NRW und BW …“ | Bau ohne Laser |

Über die Landesgrenze (geprüft, nicht nur gelesen):

- Die Rechtecke sind gröber als die Länder (NRW-Rechteck reicht nach NL, BE,
  NDS, HE, RLP; BW nach RLP, HE, BY, FR, CH). Innerhalb des Rechtecks, aber
  außerhalb des Landes antwortet der WCS NRW mit Fehlwert −9999 bzw. das LGL
  mit 404 — der Block bleibt leer und der Weltbau blendet dort wie am
  Korridorrand in Terrarium über. Kein Fehler, kein Abbruch.
- Probe **Aachen → Vaals (NL)**, 6 km, gut 1 km davon in den Niederlanden:
  Bau mit Quelle `nw` in 72 s, Paket 75 MB, `checkWorld` in Ordnung. Ob der
  niederländische Teil sichtbar sauber übergeht, habe ich **nicht** im
  Betrachter angesehen; die Laserkennzahlen zählen nur die angefragten
  Blöcke, nicht die mit Daten. Aachen-Innenstadt kostete 244 MB LoD2-Download
  (11 Kacheln) — die Stadt ist der teuerste Fall.
- Eine Strecke, die zu weniger als der Hälfte in NRW/BW liegt, bekommt auch
  auf der NRW/BW-Seite **keine** Laserdaten. Das ist Verhalten des Weltbaus
  (nicht geändert); die App sagt es vorher.

## Messungen (Probe in dieser Umgebung)

Werkzeug mit `JAVA_OPTS=-Xmx3g`, Qualität MEDIUM, 4 Kerne. „kalt“ = leerer
Geodaten-Zwischenspeicher.

| Strecke | km | Weg | Bau gesamt (Auftrag → fertig) | Paket | entpackt | Speicher | Zwischenspeicher danach |
|---|---|---|---|---|---|---|---|
| Altenberg kurz (NRW), `samples/altenberg-kurz.gpx` | 3,0 | Prozess direkt, kalt | 42 s | 36,3 MB | 53 MB | RSS höchstens 1,71 GB | 37 MB |
| dieselbe, warm | 3,0 | Prozess direkt | 20 s | 36,3 MB | 53 MB | — | — |
| Solitude km 0–3 (BW), `catalog/routes/solitude.gpx` | 3,0 | **Docker-Container**, kalt | 186 s (Laser laden 42 s: 6 ZIP, 76 MB Raster) | 43,2 MB | 62 MB | Container höchstens 1,91 GiB | — |
| Aachen → Vaals (NRW/NL, künstliche Linie) | 6,0 | Docker-Container, kalt | 72 s (LoD2 244 MB) | 75,2 MB | 115 MB | (s. o.) | 361 MB nach beiden Docker-Proben |

Download in der App = Paketgröße (12–15 MB je km); mit `Range`
fortsetzbar, beide Hälften der Probe kamen mit `206` und stimmten in der
SHA-256. Alle drei Pakete bestanden `indoor-dev check`. Der Bau auf dem
Server braucht damit für das Handy **keinen** Laser-Download (bisher 9–31 MB/km
NRW, 15–16 MB/km BW) und keinen Heap über die 256 MB der App hinaus.

## Sicherheit

- Eingaben: `POST` höchstens 4 MB; streng geprüftes JSON (Format, Version,
  Qualität, Punkte als `[distM, lat, lon, eleM]` mit Bereichen und steigenden
  Laufmetern, Beläge aus der festen Liste des Weltbaus, `highway` nur
  `[a-z_]`, Name ohne Steuerzeichen, ≤ 120 Zeichen). Nur bekannte Felder
  landen in `request.json`. Dieselben Regeln prüft das Werkzeug noch einmal
  (`ServerWorldRequest.parse`).
- Limits (Umgebungsvariablen): `MAX_ROUTE_KM` (100), `MAX_POINTS` (40 000),
  `MAX_JOBS` wartend + laufend (20, sonst `429 QUEUE_FULL`),
  `BUILD_TIMEOUT_MIN` (90).
- Keine Pfade von außen: Auftragskennung = 32 Hex-Zeichen vom Server
  (`crypto.randomBytes`), jeder Pfad wird daraus gebildet. Der Weltbau wird
  mit `spawn(cmd, [args])` ohne Shell gestartet; Eingaben gehen nur als Datei.
- Schreiben nur unter `DATA_DIR` (`jobs/<id>/`, `cache/`); das Werkzeug
  schreibt nur in `--job` und `--cache`.
- Container als Nutzer `trailscape` (UID 10001), kein root.
- Ausgehende Abrufe nur über die Positivlisten aus `:core`
  (`isAllowedGeoUrl` mit dem Luftbild-Register des Katalogbaus,
  `isAllowedLaserUrl`), keine Weiterleitungen. Eine Sperre auf
  Netzwerkebene (Firewall) baut der Dienst nicht selbst; `server/README.md`
  nennt die Hosts für eine eigene Egress-Regel.
- Der Token geht in der App nur an dieselbe Adresse (Schema, Host, Port) wie
  der eingerichtete Sync-Server, auch beim Paket (`isSameOrigin`).

## App

- `:core`, Paket `indoor/server`: `ServerWorldRequest` (Auftrag, Prüfung),
  `WorldServerClient` (über `HttpClient`, Ergebnis als `ServerCall`),
  `ServerJobStatus`, `ServerWorldInfo`, `ServerJobRecord` (gemerkter
  Auftrag), `laserPreviewOf`/`laserPreviewOfPoints`, `compareBuilder`.
  Tests: `ServerWorldTest` (Rundreise mit gleicher Welt-ID, Prüfung aller
  Fehlercodes, Vorschau NRW/BW/Berlin/Grenze, Client gegen Attrappe).
- `:app`: `IndoorServerBuildController` + `IndoorServerUi`
  (`ui/indoor/IndoorServerBuild.kt`), Oberfläche `IndoorServerSection.kt`,
  Einbindung in `IndoorViewModel` (`buildOnServer`, `cancelServerBuild`,
  `retryServerBuild`) und `IndoorWorldScreen`. Download über den
  vorhandenen `CatalogDownloader` (neu: zusätzliche Kopfzeilen), Installation
  über `CatalogStore.installPackage`. Test `IndoorServerBuildTest` gegen
  einen gefälschten Bau-Dienst mit dem echten Paket der Testwelt.
- Sichtbar nur, wenn ein Sync-Server eingerichtet ist **und** er den
  Bau-Dienst meldet; sonst bleibt der Schritt wie bisher. Seit dem
  Nutzerfluss (25.11.2026, docs/indoor/app.md) heißt der Schritt „Strecke
  vorbereiten“ und der Server-Bau steht dort **eingeklappt** hinter „Weitere
  Möglichkeit: auf deinem Server bauen“; läuft ein Auftrag oder ist er
  gescheitert, steht er offen (oben bzw. an seiner Stelle). Auch bei schon
  vorhandener Gerätewelt wird „neu in Laserqualität“ angeboten („Das Paket
  ersetzt die auf dem Gerät gebaute Welt“).
- Fortschritt: Senden → Warteschlange (Platz) → Bau (%) → Download (MB) →
  Prüfen/Entpacken. „Server-Bau abbrechen“ löscht den Auftrag auf dem
  Server. Während der Server baut, ist „Welt laden“ (Gerät) ausgeblendet.
- Fortsetzen nach App-Neustart: Der Auftrag steht unter
  `trailscape.indoor.server_job.<Tour-ID>`; beim nächsten Öffnen des
  Schritts für diese Tour geht es beim Stand weiter (der Download mit
  `Range`).
- Fertig: „Gebaut auf deinem Server, mit Laserdaten aus
  Nordrhein-Westfalen. Sie ersetzt die vorher auf dem Gerät gebaute Welt
  dieser Strecke.“ Quellenvermerke wie bei jeder Welt aus `manifest.json`
  (z. B. „Gelände, Bewuchs und Gebäude: Datenquelle: LGL, www.lgl-bw.de,
  dl-de/by-2-0 (DGM1, DOM1, 3D-Gebäudemodell LoD2)“, NRW `dl-de/zero-2-0`) —
  im Schritt, im HUD und in der Weltverwaltung (Kennzeichen „Server · Laser“).
- **Einheit des Tages**: Vorschläge mit echter (eigener) Strecke laufen über
  denselben Schritt — der Server-Bau steht dort ohne Umbau zur Wahl.
  Katalog- und generierte Welten bieten ihn nicht an (die einen sind schon
  gebaut, die anderen haben keine echten Geodaten).

Bilder (Roborazzi, `docs/indoor/bilder/serverbau/`): `01-angebot`,
`02-grenze-teilweise`, `03-ohne-laser`, `04-warteschlange`, `05-bau`,
`06-download`, `07-fertig`, `08-geraetewelt-ersetzen`, `09-version`,
`10-fehler-offline`, `11-weltverwaltung`, `12-angebot-en`.

## Vertrag und Weltbau

- Eingefrorene Vertragsdateien (`docs/indoor.md` 2.1): **unverändert**.
- `WORLD_BUILDER_ID`, Katalogversionen und Weltbau: unverändert. Einzige
  Öffnung: neue Datei `core/…/indoor/world/WorldBuilderVersion.kt` mit
  `val worldBuilderVersion` (liest `WORLD_BUILDER_ID`, das `internal` ist)
  und `worldBuilderGeneration(id)`.
- Ergänzungen mit Vorgabewerten: `IndoorWorldUi.Ready(fromServer,
  serverLaser, replacedDeviceWorld)`, `IndoorWorldEntryUi(fromServer,
  serverLaser)`, `WorldLibrary.WorldInfo.server`,
  `CatalogDownloader.download(headers)`; `CatalogStore.install` nutzt jetzt
  den gemeinsamen `installPackage` (Verhalten gleich, `IndoorCatalogFlowTest`
  grün).
- `server/server.mjs`: optionale Weiterleitung `/api/worlds/*` (`WORLDS_URL`);
  ohne die Variable antwortet der Pfad wie bisher `404`.

## Auf dem Gerät zu prüfen / offen

- [ ] Ganzer Ablauf auf einem Handy gegen einen echten Server hinter HTTPS
      (hier nur Unit-Tests mit gefälschtem Dienst + Ende-zu-Ende ohne App).
- [ ] Docker-Image vollständig mit `docker build` bauen: Hier scheiterte
      die erste Stufe (Gradle im Container) an der TLS-Prüfung des
      Proxys dieser Umgebung. Geprüft: die Laufzeitstufe (gleiches
      Dockerfile ab `FROM eclipse-temurin:21-jre`, mit hier gebauter
      Distribution) baut, läuft als UID 10001 und hat zwei echte Bauten
      geliefert; `docker compose config` ist gültig.
- [ ] Grenzstrecke im Betrachter ansehen (Übergang Laser → Terrarium).
- [ ] Laufender Download wird beim Verlassen des Schritts nicht abgebrochen
      gemeldet, nur angehalten; beim nächsten Öffnen geht er weiter.
- [ ] Kein Hintergrunddienst in der App: Fertig wird ein Server-Bau auch bei
      geschlossener App, geladen wird erst beim nächsten Öffnen.
- [ ] Ein Bau gleichzeitig: mehrere Nutzer eines Servers warten
      nacheinander (Selfhost, ein Token).
