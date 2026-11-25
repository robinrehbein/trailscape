# Trailscape Sync-Server

Ein optionaler Selfhost-Sync-Server für [Trailscape](../README.md). Er besteht
aus **einer einzigen Datei** (`server.mjs`), hat **keine Abhängigkeiten**
(nur Node-Core-Module) und speichert Touren als einfache JSON-Dateien auf
der Festplatte.

## Start ohne Docker

Voraussetzung: Node.js ≥ 18.

Zuerst einen langen, zufälligen Token erzeugen — er ist das einzige
Zugangsgeheimnis zum kompletten Tourenbestand:

```bash
SYNC_TOKEN=$(openssl rand -hex 32)
echo "$SYNC_TOKEN"   # in der App als Token eintragen
SYNC_TOKEN="$SYNC_TOKEN" node server.mjs
```

Wichtige Umgebungsvariablen:

| Variable     | Pflicht | Default   | Bedeutung                                   |
| ------------ | ------- | --------- | -------------------------------------------- |
| `SYNC_TOKEN` | ja      | –         | Geheimer Token für die Authentifizierung (mindestens 16 Zeichen) |
| `PORT`       | nein    | `8080`    | Port, auf dem der Server lauscht             |
| `DATA_DIR`   | nein    | `./data`  | Verzeichnis, in dem Touren gespeichert werden |
| `TRUST_PROXY` | nein   | aus       | `1` hinter einem Reverse-Proxy: Die Sperre nach Fehlversuchen nimmt dann die Client-Adresse aus `X-Forwarded-For` statt der Proxy-Adresse. Nur setzen, wenn Port 8080 nicht direkt erreichbar ist |

Ohne gesetzten `SYNC_TOKEN` startet der Server nicht; ebenso verweigert er
den Start bei einem Token unter 16 Zeichen — kurze, erratbare Tokens (etwa
Wörterbuch-Wörter) sind bei einem im Internet erreichbaren Server in
Minuten durchprobiert.

## Start per Docker

```bash
docker build -t trailscape-sync .
docker run -d \
  -p 8080:8080 \
  -v trailscape-data:/app/data \
  -e SYNC_TOKEN=$(openssl rand -hex 32) \
  --name trailscape-sync \
  trailscape-sync
```

Das Volume sorgt dafür, dass die Touren einen Container-Neustart überleben.
Der Container läuft als unprivilegierter Nutzer `node` (kein root).

Den erzeugten Token danach anzeigen (für die Eingabe in der App):

```bash
docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' trailscape-sync | grep SYNC_TOKEN
```

## Wichtig: TLS ist eine Sicherheitsanforderung

Der Server selbst spricht nur unverschlüsseltes HTTP. Über eine
unverschlüsselte Verbindung wandern bei **jeder** Anfrage der `SYNC_TOKEN`
(als `Authorization`-Header) und die kompletten GPS-Spuren — also
Bewegungsprofile inklusive Wohnort — im Klartext durchs Netz. Jeder, der den
Verkehr mitlesen kann (offenes WLAN, kompromittierter Router, Provider),
erhält damit dauerhaften Vollzugriff auf den Tourenbestand.

Deshalb gehört der Server im Produktivbetrieb **immer** hinter einen
HTTPS-Reverse-Proxy (z. B. Caddy, Nginx, Traefik), der TLS terminiert und
die Anfragen an Port 8080 weiterreicht. Port 8080 selbst sollte nicht
öffentlich erreichbar sein. In der App wird dann die `https://`-URL des
Proxys eingetragen.

## API-Übersicht

Alle Endpunkte erfordern den Header `Authorization: Bearer <SYNC_TOKEN>`.
Nach 10 fehlgeschlagenen Anmeldeversuchen innerhalb von 15 Minuten blockt
der Server die IP vorübergehend (HTTP 429 mit `Retry-After`); jeder
Fehlversuch wird mit Zeitstempel und IP (ohne Token) protokolliert.

| Methode  | Pfad               | Beschreibung                                         |
| -------- | ------------------ | ----------------------------------------------------- |
| `GET`    | `/api/rides`        | Liste aller Touren und Lösch-Merkzettel (siehe unten) |
| `GET`    | `/api/rides/<id>`   | Vollständige Tour als JSON                            |
| `PUT`    | `/api/rides/<id>`   | Tour anlegen oder überschreiben (Body: vollständiges Ride-Objekt) |
| `DELETE` | `/api/rides/<id>`   | Tour löschen; der Server merkt sich die Löschung als Tombstone |

Touren-IDs sind auf `A–Z`, `a–z`, `0–9` und `-` (max. 64 Zeichen)
beschränkt.

### Tombstones (Lösch-Merkzettel)

Damit eine auf einem Gerät gelöschte Tour nicht von einem zweiten Gerät
wieder hochgeladen wird, merkt sich der Server jede Löschung dauerhaft in
`DATA_DIR/tombstones.json`:

* `GET /api/rides` liefert lebende Touren als
  `{id, name, createdAt, updatedAt}` (fehlt `updatedAt` in einer alten
  Datei, wird `createdAt` geliefert) und gelöschte als
  `{id, name, deleted: true, deletedAt}` (Zeiten in ms seit Epoch).
* `PUT` einer Tour, deren `updatedAt` (Fallback: `createdAt`) **neuer** als
  `deletedAt` ist, belebt die Tour wieder und entfernt den Tombstone. Eine
  ältere Fassung wird mit HTTP 409 abgewiesen — die Löschung gewinnt.
* Die Trailscape-App gleicht mit demselben Prinzip ab
  (Last-Write-Wins über `updatedAt`, Löschungen über Tombstones in beide
  Richtungen).

## Hinweis zu Mehrbenutzerbetrieb

Es gibt genau einen `SYNC_TOKEN` pro Server-Instanz. Ein Token entspricht
also einem Nutzer bzw. einem gemeinsamen Datenbestand – für mehrere
getrennte Nutzer werden mehrere Server-Instanzen (mit jeweils eigenem
`DATA_DIR` und `SYNC_TOKEN`) benötigt.

## Bau-Dienst für Indoor-Welten (optional)

Neben dem Sync kann derselbe Server **Indoor-Welten in Laserqualität** für
deine eigenen Strecken bauen (Gelände DGM1, Bäume aus dem Oberflächenmodell,
LoD2-Gebäude, Luftbild — in Nordrhein-Westfalen und Baden-Württemberg; sonst
ohne Laserdaten). Die App schickt die Strecke, der Server baut, die App lädt
das fertige Paket. Hintergrund, Architektur und Messungen:
[`docs/indoor/serverbau.md`](../docs/indoor/serverbau.md).

Der Bau-Dienst (`worlds/worlds.mjs`) ist ein eigener Container mit Java
(Weltbau-Werkzeug `indoor-dev`) und Node (HTTP-Schicht, keine
Abhängigkeiten). Er nutzt **denselben `SYNC_TOKEN`**. Die App spricht nur
den Sync-Server an; der reicht `/api/worlds/…` an den Bau-Dienst weiter,
wenn `WORLDS_URL` gesetzt ist.

### Start mit Docker Compose (Sync + Bau-Dienst)

```bash
git submodule update --init third_party/brouter   # im Wurzelverzeichnis des Repos
cd server
echo "SYNC_TOKEN=$(openssl rand -hex 32)" > .env
docker compose up -d --build
docker compose logs -f worlds    # „Trailscape-Bau-Dienst auf Port 8081 – Welt-Version indoor-world/11 …“
```

`compose.yaml` baut den Sync-Server aus `server/` und den Bau-Dienst aus dem
ganzen Repo (`server/worlds/Dockerfile`; die erste Stufe baut das Werkzeug
mit dem Gradle-Wrapper, ohne Android-SDK). Port 8080 gehört wie immer hinter
einen HTTPS-Reverse-Proxy; der Bau-Dienst hat keinen Port nach außen.

In der App ist nichts weiter einzustellen: Ist unter *Mehr → Sync mit
eigenem Server* Adresse und Token eingetragen, erscheint im Schritt „Welt
laden“ einer eigenen Tour die zweite Wahl „Auf dem Server in Laserqualität
bauen“.

Ohne Compose: Bau-Dienst-Image bauen (`docker build -f server/worlds/Dockerfile
-t trailscape-worlds .` im Wurzelverzeichnis), mit `-e SYNC_TOKEN=… -v
trailscape-worlds:/data` starten und dem Sync-Server
`WORLDS_URL=http://<host-des-bau-dienstes>:8081` geben (nur interne
http-Adresse ohne Pfad). Ohne Docker: `./gradlew -Ptrailscape.toolOnly=true
:indoor-dev:installDist`, dann
`SYNC_TOKEN=… BUILDER_CMD=$PWD/tool/indoor-dev/build/install/indoor-dev/bin/indoor-dev
JAVA_OPTS=-Xmx3g node server/worlds/worlds.mjs` (Java 21, Node ≥ 18).

### Bedarf (gemessen am 24.11.2026, Qualität MEDIUM)

| | Wert |
|---|---|
| RAM | Container **4 GB** (`mem_limit`), Java `-Xmx3g`. Gemessen: Prozess höchstens 1,7 GB (3 km NRW), Container höchstens 1,9 GiB (3 km BW + 6 km Aachen-Stadt) |
| CPU | 4 Kerne gemessen; mit weniger Kernen entsprechend länger |
| Bauzeit | 3 km NRW: 42 s mit leerem Zwischenspeicher, 20 s warm. 3 km BW: 186 s (BW liefert ganze 2-km-Archive). 6 km durch Aachen: 72 s. Grob 15–60 s je km, wenn alles neu geladen wird |
| Paket | 12–15 MB je km (das lädt das Handy) |
| Platte | Pakete wie oben, bis zum Abruf; Geodaten-Zwischenspeicher 10–40 MB je km auf dem Land, in Städten mehr (LoD2 Aachen: 244 MB für 6 km); BW rund 25 MB je km. Für den Anfang **20 GB** für `/data` einplanen |

### Grenzen und Aufräumen (Umgebungsvariablen des Bau-Dienstes)

| Variable | Vorgabe | Bedeutung |
|---|---|---|
| `SYNC_TOKEN` | – (Pflicht) | derselbe Token wie beim Sync-Server |
| `MAX_ROUTE_KM` | `100` | längste Strecke |
| `MAX_POINTS` | `40000` | meiste Punkte einer Strecke |
| `MAX_JOBS` | `20` | Aufträge wartend + laufend; darüber `429 QUEUE_FULL` |
| `BUILD_TIMEOUT_MIN` | `90` | ein Bau darüber wird beendet (`TIMEOUT`) |
| `KEEP_DAYS` | `30` | fertige/gescheiterte Aufträge ohne Abruf so lange aufheben |
| `MAX_PACKAGES_GB` | `20` | darüber werden die am längsten nicht abgerufenen Pakete gelöscht |
| `CACHE_DAYS` | `180` | Geodaten im Zwischenspeicher älter als das werden gelöscht |
| `CACHE_MAX_GB` | `20` | darüber werden die ältesten Geodaten gelöscht (bis 90 %) |
| `AERIAL_BAND` | `aus` | Paketqualität des Luftbilds: `band` = Streckenband ±20 m mit 12,5 cm, `band-breit` = ±30 m (`docs/indoor/luftbild-band.md`); geht in den Auftragsschlüssel ein |
| `JAVA_OPTS` | `-Xmx3g -XX:+ExitOnOutOfMemoryError` | Speicher des Weltbaus |
| `PORT`, `DATA_DIR`, `TRUST_PROXY` | `8081`, `/data`, aus | wie beim Sync-Server |

Aufgeräumt wird stündlich und nach jedem Bau; den Zwischenspeicher nur, wenn
gerade nichts gebaut wird. Die App löscht ihren Auftrag selbst, sobald die
Welt installiert ist. Aufträge laufen **nacheinander** (ein Bau
gleichzeitig) und überleben einen Neustart: Ein unterbrochener Bau kommt
zurück in die Warteschlange und setzt bei den fertigen Kacheln fort. Das
Protokoll eines Baus liegt unter `/data/jobs/<id>/build.log`.

### Bau-Dienst-API

Alle Aufrufe mit `Authorization: Bearer <SYNC_TOKEN>`, über den Sync-Server
unter derselben Adresse:

| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/worlds/info` | `{service:"trailscape-worlds", builderId, laserSources, maxLengthM, maxPoints, queueLength}` |
| `POST` | `/api/worlds/jobs` | Auftrag (Format in `core/…/indoor/server/ServerWorldRequest.kt`); `201` neu, `200` gleicher Auftrag schon da, `409 BUILDER_MISMATCH`, `413 TOO_LONG/TOO_MANY_POINTS/TOO_LARGE`, `429 QUEUE_FULL`, `400 INVALID` |
| `GET` | `/api/worlds/jobs/<id>` | Stand: `QUEUED` (mit `queuePosition`), `RUNNING` (`phase`, `fraction`), `DONE` (`worldId`, `packageBytes`, `sha256`, `laserSource`), `FAILED` (`error.code`) |
| `GET`/`HEAD` | `/api/worlds/jobs/<id>/package` | Paket (ZIP), `Range` und `ETag` (SHA-256) |
| `DELETE` | `/api/worlds/jobs/<id>` | abbrechen (laufender Bau wird beendet) bzw. Paket löschen |

Probe ohne App: `SYNC_TOKEN=… node server/worlds/probe.mjs https://dein-server request.json paket.zip`
(einen Auftrag aus einer GPX-Datei schreibt `indoor-dev server-request --gpx … --out request.json --laser`).
Tests: `node --test server/worlds/test/` (mit einer Attrappe des Weltbaus).

### Welche Hosts der Bau-Dienst abruft

Nur über feste Positivlisten, ohne Weiterleitungen:
`elevation-tiles-prod.s3.amazonaws.com` (Höhen), `tiles.openfreemap.org`
(Karteninhalt), Luftbild-WMS der Länder (z. B. `www.wms.nrw.de`,
`owsproxy.lgl-bw.de`, Liste in `core/…/indoor/world/IndoorWorldEntry.kt` und
`tool/indoor-dev/…/CatalogAerial.kt`), Laserdaten bei `www.wcs.nrw.de`,
`www.opengeodata.nrw.de` und `opengeodata.lgl-bw.de`. Wer den Container auf
Netzwerkebene einschränken will, erlaubt genau diese Hosts (HTTPS).

### Update bei neuer Welt-Version

Steigt die Welt-Version in der App (`indoor-world/N`), meldet die App „Welt-
Version passt nicht – aktualisiere den Bau-Dienst“. Dann:

```bash
git pull && git submodule update --init third_party/brouter
cd server && docker compose up -d --build worlds
```

Fertige Pakete der alten Version passen nicht mehr zur App; der Dienst
räumt sie nach `KEEP_DAYS` weg (oder `docker compose exec worlds sh -c 'rm -rf /data/jobs/*'`
bei gestopptem Bau). Der Geodaten-Zwischenspeicher bleibt gültig.
