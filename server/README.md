# Trailscape Sync-Server

Ein optionaler Selfhost-Sync-Server für [Trailscape](../README.md). Er besteht
aus **einer einzigen Datei** (`server.mjs`), hat **keine Abhängigkeiten**
(nur Node-Core-Module) und speichert Touren als einfache JSON-Dateien auf
der Festplatte.

## Start ohne Docker

Voraussetzung: Node.js ≥ 18.

```bash
SYNC_TOKEN=geheim node server.mjs
```

Wichtige Umgebungsvariablen:

| Variable     | Pflicht | Default   | Bedeutung                                   |
| ------------ | ------- | --------- | -------------------------------------------- |
| `SYNC_TOKEN` | ja      | –         | Geheimer Token für die Authentifizierung     |
| `PORT`       | nein    | `8080`    | Port, auf dem der Server lauscht             |
| `DATA_DIR`   | nein    | `./data`  | Verzeichnis, in dem Touren gespeichert werden |

Ohne gesetzten `SYNC_TOKEN` startet der Server nicht.

## Start per Docker

```bash
docker build -t trailscape-sync .
docker run -d \
  -p 8080:8080 \
  -v trailscape-data:/app/data \
  -e SYNC_TOKEN=geheim \
  --name trailscape-sync \
  trailscape-sync
```

Das Volume sorgt dafür, dass die Touren einen Container-Neustart überleben.

## Wichtig: HTTPS-Reverse-Proxy

Der Server selbst spricht nur HTTP. Wenn die Trailscape-App über HTTPS
ausgeliefert wird (z. B. als gehostete PWA), blockiert der Browser
Anfragen an einen unverschlüsselten Sync-Server als Mixed Content.
Deshalb sollte der Server im Produktivbetrieb hinter einem
HTTPS-Reverse-Proxy (z. B. Caddy, Nginx, Traefik) betrieben werden, der
TLS terminiert und die Anfragen an Port 8080 weiterreicht.

## API-Übersicht

Alle Endpunkte erfordern den Header `Authorization: Bearer <SYNC_TOKEN>`
(außer `OPTIONS`).

| Methode  | Pfad               | Beschreibung                                         |
| -------- | ------------------ | ----------------------------------------------------- |
| `GET`    | `/api/rides`        | Liste aller Touren (nur `id`, `name`, `createdAt`)    |
| `GET`    | `/api/rides/<id>`   | Vollständige Tour als JSON                            |
| `PUT`    | `/api/rides/<id>`   | Tour anlegen oder überschreiben (Body: vollständiges Ride-Objekt) |
| `DELETE` | `/api/rides/<id>`   | Tour löschen                                          |

Touren-IDs sind auf `A–Z`, `a–z`, `0–9` und `-` (max. 64 Zeichen)
beschränkt.

## Hinweis zu Mehrbenutzerbetrieb

Es gibt genau einen `SYNC_TOKEN` pro Server-Instanz. Ein Token entspricht
also einem Nutzer bzw. einem gemeinsamen Datenbestand – für mehrere
getrennte Nutzer werden mehrere Server-Instanzen (mit jeweils eigenem
`DATA_DIR` und `SYNC_TOKEN`) benötigt.
