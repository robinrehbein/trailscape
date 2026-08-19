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
