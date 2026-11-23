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
```

| Befehl / Option | Bedeutung |
|---|---|
| `build --gpx <datei> --out <dir>` | Welt bauen. Am Ende Zusammenfassung (Kacheln je Stufe, MB, Budget, Dauer, Anfragen Netz/Cache) und `checkWorld`; Verstöße → Exit-Code 1 |
| `--quality LOW\|MEDIUM\|HIGH` | Qualitätsstufe (Vorgabe `MEDIUM`) |
| `--surfaces <json>` | Belagsdatei; ohne Angabe die Beidatei `<name>.surfaces.json` neben der GPX |
| `--from-km a --to-km b` | nur dieser Ausschnitt (Laufmeter der GPX) — für kurze Läufe während der Entwicklung |
| `--no-aerial` | ohne Luftbild (erzeugte Bodentextur aus der Landnutzung) |
| `--offline` | kein Netz: nur, was im Cache liegt (auch Abgelaufenes) |
| `plan --gpx …` | nur den Voranschlag zeigen (Kacheln, Anfragen, Datenmenge, Hosts, Luftbild-Dienste) — ohne Netz |
| `testworld --out <dir>` | die erfundene Testwelt (Runde 3 km, alle Belagsklassen, ohne Netz, deterministisch) |
| `check <dir>` | eine fertige Welt prüfen |
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
(NRW: `www.wms.nrw.de`, Sachsen: `geodienste.sachsen.de`, …).

## Beispielstrecken (`samples/`)

| Datei | Strecke | Luftbild |
|---|---|---|
| `altenberg-kurz.gpx` | 3 km um den Altenberger Dom (NRW) — der schnelle Probelauf | NRW |
| `altenberg.gpx` | 14 km Runde Altenberg – Dhünntal (NRW) | NRW |
| `tharandt.gpx` | 13 km durch den Tharandter Wald (Sachsen) | Sachsen |

Je Strecke liegt die Belagsdatei `<name>.surfaces.json` daneben (aus
BRouters Wegmerkmalen).

## Ansehen

Der Betrachter (`viewer/`, three.js) ist die Referenzleserin des Formats:

```
cd tool/indoor-dev/viewer && npm install
node shots.mjs ../out/altenberg-kurz --dists 200,1200,2400 --cameras chase,drone --no-rider --jpeg
node shots.mjs ../out/altenberg-kurz --serve --port 8123
```

Bilder landen unter `<welt>/shots/`. Optionen und Abweichungen von Filament
stehen in `viewer/README.md`.

## Hinweis zum Stand

`createWorldBuilder()` baut die Kacheln derzeit mit der schlichten
Rückfall-Umsetzung (`SimpleWorldParts`: Gelände mit Luftbild oder erzeugter
Bodentextur, ein einfaches Fahrbahnband). Die echten Erzeuger (Weg, Straßen,
Gebäude, Vegetation, Wasser, Kleinteile) hängen hinter derselben
Schnittstelle (`WorldParts`).
