# indoor-dev — Welten bauen und ansehen

Entwicklerwerkzeug des Indoor-Trainings (`docs/indoor.md`, Abschnitt 9): baut
aus einer GPX-Datei mit echten Geodaten eine Welt (dieselbe Pipeline wie die
App, `createWorldBuilder()` aus `:core`), baut die erfundene Testwelt und prüft
fertige Welten gegen den Vertrag. Läuft nur auf dem Entwicklerrechner.

## Aufrufe

Immer aus dem Projektverzeichnis; relative Pfade gelten ab dort.

```
./gradlew :indoor-dev:run --args="build --gpx tool/indoor-dev/samples/altenberg-kurz.gpx --quality MEDIUM --out tool/indoor-dev/out/altenberg-kurz"
./gradlew :indoor-dev:run --args="build --gpx tool/indoor-dev/samples/tharandt.gpx --out tool/indoor-dev/out/tharandt --from-km 2 --to-km 5"
./gradlew :indoor-dev:run --args="plan --gpx tool/indoor-dev/samples/altenberg.gpx --quality HIGH"
./gradlew :indoor-dev:run --args="testworld --out tool/indoor-dev/out/testwelt --quality MEDIUM"
./gradlew :indoor-dev:run --args="check tool/indoor-dev/out/altenberg-kurz"
./gradlew :indoor-dev:run --args="models --out app/src/main/assets/indoor/models"
./gradlew :indoor-dev:run --args="sky --time EVENING --lat 51.0 --lon 7.25 --out tool/indoor-dev/out/himmel/abend"
```

| Befehl / Option | Bedeutung |
|---|---|
| `build --gpx <datei> --out <dir>` | Welt bauen. Am Ende Zusammenfassung (Kacheln je Stufe, MB, Budget, Dauer, Anfragen Netz/Cache) und `checkWorld`; Verstöße → Exit-Code 1 |
| `--quality LOW\|MEDIUM\|HIGH` | Qualitätsstufe (Vorgabe `MEDIUM`) |
| `--surfaces <json>` | Belagsdatei; ohne Angabe die Beidatei `<name>.surfaces.json` neben der GPX |
| `--from-km a --to-km b` | nur dieser Ausschnitt (Laufmeter der GPX) — für kurze Läufe während der Entwicklung |
| `--no-aerial` | ohne Luftbild (erzeugte Bodentextur aus der Landnutzung) |
| `--offline` | kein Netz: nur, was im Cache liegt (auch Abgelaufenes) |
| `--laser [--laser-radius m]` | Datenstufe „Laser" (NRW und Baden-Württemberg, nach Lage der Strecke): Gelände aus DGM1, Bäume aus nDOM, Gebäude aus LoD2 im Korridor (Vorgabe 200 m); auch für `plan` und `catalog-build`. Am Ende eine Zeile `Laser:` mit Blöcken, Anfragen, Bytes, LoD2, Versatz DGM1 − Terrarium. Siehe `docs/indoor/lidar.md` |
| `--catalog-aerial` | für `build`/`plan`: Luftbild wie beim Katalogbau, also auch die Dienste nur des Werkzeugs (`CatalogAerial.kt`, z. B. Baden-Württemberg) |
| `--lod2-corridor <datei>` | mit `--laser`: LoD2 aus einer zugeschnittenen Korridordatei statt aus den 1-km-Kacheln |
| `lod2-cut --gpx <datei> --out <datei.tsl2> [--lod2-radius m]` | LoD2 auf den Streifen um die Strecke zuschneiden (Vorgabe 120 m), kompakt schreiben; gibt Kachel- und Korridorgröße je km aus |
| `plan --gpx …` | nur den Voranschlag zeigen (Kacheln, Anfragen, Datenmenge, Hosts, Luftbild-Dienste) — ohne Netz |
| `testworld --out <dir>` | die erfundene Testwelt (Runde 3 km, alle Belagsklassen, ohne Netz, deterministisch) |
| `generated --profile <vorlage\|datei.json\|kurz> [--seed n] --out <dir>` | generierte Welt zu einem Zielprofil (Einheit des Tages), ohne Netz; siehe `docs/indoor/generiert.md` |
| `check <dir>` | eine fertige Welt prüfen |
| `overlaps <dir> [--tol-cm c]` | koplanare, überlappende Flächen (Ursache für Flimmern; Höhenabstand unter `c` cm, Vorgabe 0,4) in den Kacheln der Stufe 0 je Materialpaar zählen; dazu je Material die durchsichtigen (BLEND) Eckpunkte mit Vertex-Deckung < 1 — auf dem Gerät helle Nahtlinien, soll 0 sein (`docs/indoor/world.md`, Abschnitt „Kreuzungen und Ortsdurchfahrten“) |
| `catalog-routes [--only a,b]` | Strecken des Katalogs bekannter Strecken über BRouter neu herleiten (`catalog/routes/<id>.gpx` + Belag), siehe `docs/indoor/catalog.md` |
| `catalog-build --out <dir> [--only a,b] [--app-catalog <datei>] [--keep-going] [--laser]` | Katalogwelten bauen, prüfen, packen (`<dir>/packages/*.zip`) und `<dir>/catalog.json` schreiben; `--app-catalog` schreibt den beigelegten Katalog der App (ohne Prüfsummen) |
| `sky --time AUTO\|MORNING\|NOON\|EVENING --out <dir> [--lat --lon --epoch-ms --quality]` | Himmel der Darstellung für den Betrachter (`shots.mjs --sky <dir>`): `sky.json` (Sonne, Licht, Dunst), `sky.f32` (Rundumbild 1024 × 512, szenenlinear) und `preview.png` (durch eine Nachbildung von Filaments `ACES_LEGACY`). Rechnet mit `core/…/indoor/sky`, also genau wie das Gerät; siehe `docs/indoor/render.md`, „Himmel“ |
| `server-info` | JSON mit Welt-Version, Laser-Quellen und Belagsklassen (für den Bau-Dienst) |
| `server-build --job <dir> --cache <dir> [--max-km n] [--max-points n]` | Bau für den Bau-Dienst (`server/worlds`, `docs/indoor/serverbau.md`): liest `<job>/request.json`, baut mit Laser (wo vorhanden) und Katalog-Luftbild, prüft, packt nach `<job>/package.zip`, schreibt `<job>/result.json`; Fortschritt als `@progress {…}` |
| `server-request --gpx <datei> --out <request.json> [--laser] [--from-km a --to-km b]` | Auftrag für den Bau-Dienst aus einer GPX-Datei (Proben) |
| `models --out <dir>` | Fahrer und Geist schreiben (`writeIndoorModels` aus `RiderModel.kt` in `:core`; meldet sauber, wenn es die Funktion noch nicht gibt) |

Exit-Codes: 0 in Ordnung, 1 Bau gescheitert / Verstöße, 2 falscher Aufruf.

Ein abgebrochener Bau setzt beim nächsten Aufruf mit derselben Strecke,
Stufe und Ausgabe fort: Fertige Kacheln liegen unter `tiles/`, ihre
Kennzahlen unter `build/`; `manifest.json` entsteht zuletzt. Zum Neubau mit
anderen Einstellungen (`--no-aerial`) ein neues Ausgabeverzeichnis nehmen.

## Strecke und Belag

Die GPX-Datei liest `:core` (`parseGpx`). Die Strecke baut
`buildIndoorRoute` aus `:core`; solange der noch Platzhalter ist, nimmt das
Werkzeug einen eigenen Rückfall (neu abtasten alle ≤ 10 m, Höhen geglättet
und auf ±25 % begrenzt, Runde bei ≤ 60 m Abstand Start/Ziel, Laufmeter aus
Haversine). Die Ausgabe nennt, welcher Weg genommen wurde.

Belag: `{source, lengthM, sections:[{fromM, toM, kind, highway?, widthM?}]}`
(`kind` = `SurfaceKind`). Die Abschnitte werden anteilig auf die tatsächliche
Länge gestreckt; mit `--from-km/--to-km` ausgeschnitten. Ohne Belagsdatei
schätzt der Weltbau den Belag aus den Vektorkacheln (`VECTOR_TILES`).

## Netz und Cache

Roher Abruf über `java.net.http` (keine Weiterleitungen, Timeouts 15 s / 30 s,
`User-Agent: Trailscape-indoor-dev`), darum die Hülle aus `:core`
(`createGeoPipeline`: Positivliste, Plattencache, höchstens 4 Anfragen
gleichzeitig, 2 je Host). Proxy und Truststore nimmt die JVM aus ihrer
Umgebung (`JAVA_TOOL_OPTIONS`); das Werkzeug setzt nichts davon selbst.

Cache: `tool/indoor-dev/cache/indoor/geo/<art>/…` (git-ignoriert). Höhen
365 Tage, TileJSON 24 h, Vektorkacheln 30 Tage, Luftbilder 180 Tage;
Wiederholungen derselben Strecke sind dadurch schnell. Luftbilder werden je
Kachel angefragt — eine andere Strecke (anderer Ursprung) trifft sie nicht.

Angefragte Hosts: `s3.amazonaws.com` (Höhen), `tiles.openfreemap.org`
(Vektorkacheln), dazu der Luftbild-Dienst des Landes aus `aerialSources`
(NRW: `www.wms.nrw.de`, Sachsen: `geodienste.sachsen.de`, …). Mit `--laser`
zusätzlich `www.wcs.nrw.de` (DGM1/DOM1) und `www.opengeodata.nrw.de` (LoD2),
in Baden-Württemberg `opengeodata.lgl-bw.de` (ZIP-Kacheln DGM1, DOM1, LoD2),
Cache unter `cache/indoor/laser/` (365 Tage).

## Beispielstrecken (`samples/`)

| Datei | Strecke | Luftbild |
|---|---|---|
| `altenberg-kurz.gpx` | 3 km um den Altenberger Dom (NRW) — der schnelle Probelauf | NRW |
| `altenberg.gpx` | 14 km Runde Altenberg – Dhünntal (NRW) | NRW |
| `tharandt.gpx` | 13 km durch den Tharandter Wald (Sachsen) | Sachsen |
| `kreisel-bergisch-gladbach.gpx` | 2,1 km durch einen ovalen Kreisverkehr (Bergisch Gladbach, BRouter `fastbike`, ohne Belagsdatei) — Prüfstrecke für Kreisel und Knoten | NRW |
| `balkantrasse.gpx` | 7,7 km Balkantrasse Wermelskirchen – Hilgen (alte Bahntrasse, BRouter `trekking`, ohne Belagsdatei): Ortsdurchfahrt, Einschnitte und Dämme — Prüfstrecke für `--laser` | NRW |

Je Strecke liegt die Belagsdatei `<name>.surfaces.json` daneben (aus
BRouters Wegmerkmalen).

## Ansehen

Der Betrachter (`viewer/`, three.js) ist die Referenzleserin des Formats:

```
cd tool/indoor-dev/viewer && npm install
node shots.mjs ../out/altenberg-kurz --dists 200,1200,2400 --cameras chase,drone --no-rider --jpeg
node shots.mjs ../out/altenberg-kurz --serve --port 8123
node shots.mjs ../out/testwelt --count 8 --cameras chase --sky ../out/himmel/abend   # Himmel wie auf dem Gerät
```

Bilder landen unter `<welt>/shots/`. Optionen und Abweichungen von Filament
stehen in `viewer/README.md`.

## Hinweis zum Stand

`createWorldBuilder()` baut mit allen Erzeugern (`FullWorldParts`): Gelände
mit Luftbild oder erzeugter Bodentextur, befahrener Weg je Belag, übrige
Straßen, Gebäude, Bäume und Waldkörper, Wasserflächen, Kleinteile.
`SimpleWorldParts` bleibt als schlichte Umsetzung für einzelne Tests. Stand,
Lücken und Bilder: `docs/indoor/world.md`.
