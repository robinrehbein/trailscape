# Raum-Protokoll v1 — Gruppenfahrt in Echtzeit auf eigenem Server

Stand: 25.11.2026. Grundlage: `docs/konzept-spiel-community.md`, Abschnitte 5.2 und 6.2
(Etappe 5 „Raum auf eigenem Server“). Umgesetzt in `server/rooms.mjs` (Raumlogik),
`server/websocket.mjs` (WebSocket nach RFC 6455), `server/server.mjs` (Einbindung),
`core/src/main/kotlin/de/trailscape/core/room/` (Codec, Raumzustand, Windschatten),
`app/src/main/kotlin/de/trailscape/app/room/` (Netz-Client) und — seit der Oberfläche —
`app/src/main/kotlin/de/trailscape/app/ui/indoor/IndoorRoom*.kt` (Abschnitt 14). Das Protokoll
selbst ist dabei **unverändert** geblieben: Die Oberfläche benutzt nur, was v1 schon überträgt.

Ziel: **Jeder** Trailscape-Server kann Räume anbieten. Derselbe Code soll später unverändert als
offizielles Relais laufen können — ohne Konto und ohne Speicherung (Etappe 6, nicht Teil dieser
Umsetzung; siehe Abschnitt 12).

---

## 1 Überblick

1. Wer den `SYNC_TOKEN` des Servers hat (der Betreiber), **legt einen Raum an** (REST). Er bekommt
   eine Raumkennung, ein **Raum-Geheimnis** für Gäste und ein **Gastgeber-Geheimnis**.
2. Er teilt einen **Einladungslink** mit Server-Adresse, Raumkennung und Raum-Geheimnis.
3. Alle (auch der Gastgeber) verbinden sich per **WebSocket** und treten mit der ersten Nachricht
   (`join`) bei. Gäste bekommen **nie** den `SYNC_TOKEN` und damit nie Zugriff auf Touren oder den
   Bau-Dienst; das Raum-Geheimnis gilt nur für diesen einen Raum, nur über den WebSocket und nur,
   solange der Raum lebt.
4. Jedes Gerät meldet 2–4-mal je Sekunde seinen Zustand; der Server sammelt und schickt im Takt
   von 250 ms die neuen Zustände an alle im Raum.
5. Der Raum lebt nur im Arbeitsspeicher und verschwindet nach Ende, Ablauf oder Leerlauf.

Die Welt ist eindimensional: Position = **Laufmeter entlang der Strecke** (`distM`), bei
Rundkursen über die Runden fortlaufend. Kein GPS, keine Koordinaten.

## 2 Transport: WebSocket statt Server-Sent Events

Gewählt ist **WebSocket** (RFC 6455), auf dem Server ohne Abhängigkeit von Hand umgesetzt
(`server/websocket.mjs`, rund 360 Zeilen).

| | WebSocket | Server-Sent Events + POST |
|---|---|---|
| Richtung | beide über eine Verbindung | Server→Gerät als Strom, Gerät→Server je Zustand ein POST |
| Aufwand je Zustand | 6 Byte Rahmenkopf | eine HTTP-Anfrage mit Kopfzeilen (einige hundert Byte), 4-mal je Sekunde und Fahrer, jede über den Reverse-Proxy |
| Zuordnung | Verbindung = Fahrer | Strom und POSTs müssen über eine Kennung verknüpft werden (eine Kennung mehr, die geheim bleiben muss) |
| App | OkHttp (schon im APK) kann WebSocket | OkHttp kann POST; SSE bräuchte eigenen Parser oder Bibliothek |
| Abbruch erkennen | Ping/Pong im Protokoll, Schließcodes | Strom reißt ab, POSTs laufen ins Leere; keine Gründe |
| Server ohne Abhängigkeit | Handshake und Rahmen von Hand (gut testbar) | einfacher |

Den Ausschlag geben die Taktrate (4 Hz × Fahrer) und dass OkHttp WebSocket ohnehin kann. Was
von Hand gebaut ist, ist bewusst klein: nur Text, keine Erweiterungen (kein
`permessage-deflate`), keine Unterprotokolle, Nachrichtengröße vor dem Puffern geprüft.

**Reverse-Proxy**: Caddy und Traefik reichen WebSocket ohne Zusatz durch. Für nginx im
`location`-Block der App:

```nginx
proxy_http_version 1.1;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
proxy_read_timeout 120s;   # Server pingt alle 15 s
```

## 3 Sicherheit und Berechtigung

| Was | Wie |
|---|---|
| Raum anlegen, auflisten, beenden | nur mit `Authorization: Bearer <SYNC_TOKEN>` (wie Touren); Fehlversuche zählen in dieselbe Sperre (10 in 15 min → 429) |
| Beitreten | WebSocket **ohne** Token; Raumkennung und Geheimnis stehen in der **ersten Nachricht**, nie in der URL. Grund: URLs (auch Query-Strings) landen in den Zugriffsprotokollen von Reverse-Proxys |
| Raum-Geheimnis | 32 Zufallsbytes (base64url, 43 Zeichen). Der Server hält **nur den SHA-256** im Speicher und vergleicht konstantzeitig (`crypto.timingSafeEqual` über die Hashes, Länge des Klartexts egal) |
| Unbekannter Raum vs. falsches Geheimnis | identische Antwort (`JOIN_DENIED`, 4001); auch ohne Raum wird gegen einen Platzhalter-Hash verglichen, damit die Laufzeit nichts verrät |
| Fehlversuche beim Beitritt | je Adresse 20 in 15 min, dann 429 beim Handshake bzw. 4008. Protokolliert wird Zeit und Adresse, nie Raumkennung oder Geheimnis (gleiche Praxis wie beim Sync) |
| Gastgeber | Wer mit dem Gastgeber-Geheimnis beitritt, darf Countdown starten und den Raum beenden. Das Gastgeber-Geheimnis steht nie im Einladungslink |
| Wiederbeitritt | eigener Schlüssel je Fahrer (`rejoinKey`, 18 Zufallsbytes), nur im `welcome` an diesen Fahrer, bei jedem Wiederbeitritt erneuert, nur als Hash gehalten |
| Gäste und andere Endpunkte | Raum- und Gastgeber-Geheimnis werden nirgends als Bearer akzeptiert: `/api/rides`, `/api/worlds`, `/api/rooms` antworten mit 401. WebSocket gibt es nur unter `/api/rooms/ws` |
| TLS | wie beim Sync Pflicht (Reverse-Proxy). Das Raum-Geheimnis im Klartext öffnet zwar „nur“ einen Raum, die Positionen sind aber personenbezogen |

Der Einladungslink:

```
trailscape://room?server=https%3A%2F%2Fsync.example.org&room=<Raumkennung>#<Raum-Geheimnis>
```

Das Geheimnis steht im **Fragment**: Wird der Link später über eine Web-Adresse verteilt
(App Links, Etappe 3), schickt ein Browser das Fragment nie an einen Server. `http://` ist nur
für `localhost`, `127.0.0.1` und den Emulator (`10.0.2.2`) zulässig (`RoomInvite.kt`).

## 4 REST (mit `SYNC_TOKEN`)

| Methode | Pfad | Antwort |
|---|---|---|
| `POST` | `/api/rooms` | `201 {v, room, secret, hostSecret, serverTime}`; `400 INVALID`; `413 TOO_LARGE` (> 4 KB); `429 ROOMS_FULL`; `404`, wenn Räume aus sind |
| `GET` | `/api/rooms` | `{versions:[1], limits, serverTime, rooms:[{id, route, maxRiders, createdAt, expiresAt, startAt, riders, connected}]}` — ohne Geheimnisse, Namen, Positionen |
| `DELETE` | `/api/rooms/<id>` | `204`; alle im Raum bekommen `ended {reason:"host"}`; `404`, wenn unbekannt |
| `GET` | `/api/rooms/ws` ohne Upgrade | `426` (ohne Token, zählt nicht als Fehlversuch) |

Antworten auf `POST` und `GET` tragen `Cache-Control: no-store`.

Körper von `POST /api/rooms`:

```json
{ "route": { "id": "katalog/eifel", "lengthM": 12345.6, "loop": true, "version": "3", "name": "Eifel-Runde" },
  "maxRiders": 6, "ttlMin": 120, "startAt": 1764072030000 }
```

`route.id` (1–128 Zeichen) und `route.lengthM` (> 0) sind Pflicht; die App muss Strecke **und**
Version bei sich wiederfinden, sonst Abbruch mit Hinweis. `maxRiders` 2..`ROOM_MAX_RIDERS`,
`ttlMin` 5..`ROOM_TTL_MIN`, `startAt` (Server-Uhr) zwischen jetzt und Ablauf. Alles andere wird
ignoriert.

## 5 Nachrichten über den WebSocket

Jede Nachricht ist ein JSON-Objekt in einem Textrahmen mit dem Feld `type`. Zeiten sind
**Server-Uhr in ms seit Epoch** (außer `ping.c`). Beispiele für jede Nachricht, auch in alter
und künftiger Form: `server/test/fixtures/raum-v1.json` — dieselbe Datei prüfen Server
(`protocol-fixtures.test.mjs`) und App-Codec (`RoomCodecTest.kt`).

### 5.1 Gerät → Server

| `type` | Felder | Wann |
|---|---|---|
| `join` | `v` (Protokollversion, 1), `room`, `secret` (Raum- oder Gastgeber-Geheimnis), `name` (1–32 Zeichen nach Bereinigung), `avatar` (beliebiges JSON, höchstens 512 Byte, **undurchsichtig**: der Server reicht es nur weiter), `sharePower` (Vorgabe `true`), `rejoin {id, key}` (optional) | als **erste** Nachricht, binnen 5 s |
| `state` | `distM` (≥ 0, fortlaufend), `speedMps` (0..30), optional `powerW` (0..3000), `cadenceRpm` (0..250), `lane` (ganzzahlig −3..3) | 2–4 Hz |
| `ping` | `c` (Geräte-Uhr, monoton, ms) | alle 5 s (Uhrenabgleich, Lebenszeichen) |
| `settings` | `sharePower`, `name` (beide optional) | jederzeit |
| `leave` | — | Raum verlassen; Platz sofort frei |
| `start` | `inS` (3..600) | nur Gastgeber, vor dem Start; erneut senden setzt neu an |
| `end` | — | nur Gastgeber; Raum endet für alle |

**Leistung abschaltbar**: Mit `sharePower:false` (beim Beitritt oder per `settings`) verwirft der
Server `powerW` schon beim Empfang; der Wert verlässt den Server nie. Die App schickt `powerW`
dann gar nicht erst (`ClientMessage.State(powerW = null)`).

### 5.2 Server → Gerät

| `type` | Felder | Wann |
|---|---|---|
| `welcome` | `v`, `serverTime`, `you {id, rejoinKey, host}`, `room {id, route, maxRiders, createdAt, expiresAt, startAt}`, `riders [{id, name, host, sharePower, avatar?}]`, `states [Zustand]` (letzte bekannte), `limits {…}` | nach gültigem `join` |
| `rider` | `rider {id, name, host, sharePower, avatar?}` | Fahrer neu, wieder da oder geändert |
| `left` | `id`, `reason` (`leave` \| `timeout`) | Fahrer weg |
| `snapshot` | `serverTime`, `states [{id, at, distM, speedMps, powerW?, cadenceRpm?, lane?}]` | alle 250 ms, **nur** Fahrer mit neuem Zustand seit dem letzten Rundruf; nichts Neues → kein Rundruf |
| `countdown` | `startAt`, `serverTime` | Gastgeber hat gestartet |
| `ended` | `reason` (`host` \| `expired` \| `idle` \| `server`), `serverTime` | Raum endet; danach Schließen mit 4004 |
| `pong` | `c` (gespiegelt), `s` (Server-Uhr) | Antwort auf `ping` |
| `error` | `code`, `message`, ggf. `supported` | siehe 5.3 |

`at` in einem Zustand ist die Server-Uhr **beim Empfang**, nicht die des Geräts. Damit braucht
der Server keinem Gerät die Uhr zu glauben, und alle schreiben von derselben Zeitbasis fort.

### 5.3 Fehler und Schließcodes

Fehler nach dem Beitritt werden **gemeldet, nicht getrennt**: `BAD_MESSAGE` (kein JSON-Objekt),
`INVALID_STATE` (Feld fehlt oder außerhalb der Grenzen), `IMPLAUSIBLE` (Sprung, siehe 7),
`RATE` (höchstens eine Meldung je Sekunde), `NOT_HOST`, `ALREADY_STARTED`, `ALREADY_JOINED`,
`INVALID`. Vor dem Beitritt und bei Grenzverletzungen wird geschlossen:

| Code | Bedeutung | App versucht es wieder? |
|---|---|---|
| 1002 / 1003 / 1007 | Protokollfehler, Binärrahmen, kein UTF-8 | nein (Fehler in der App) |
| 1009 | Nachricht zu groß | nein |
| 4001 | Raum unbekannt oder Geheimnis falsch | nein |
| 4002 | Raum voll | nein |
| 4003 | Protokollversion nicht unterstützt (`error.supported` nennt die Versionen) | nein |
| 4004 | Raum beendet | nein |
| 4005 | zu viele Nachrichten | ja, mit Backoff |
| 4006 | kein `join` binnen 5 s | ja |
| 4007 | ersetzt: derselbe Fahrer ist über eine neue Verbindung wieder da | nein |
| 4008 | zu viele Fehlversuche von dieser Adresse | nein |
| 4009 | nach `leave` | nein |
| 4010 | 20 s keine Nachricht | ja |
| 4011 | erste Nachricht kein gültiges `join` | nein |
| 1001 / 1006 / 1011 / Netzfehler | Server fährt herunter, Abriss | ja |

## 6 Versionierung

- `join.v` ist die **Hauptversion**. Der Server nennt in `GET /api/rooms` und in `error.supported`
  die Versionen, die er kann (heute `[1]`). Passt sie nicht: `error VERSION` und 4003. Eine
  inkompatible Änderung bekommt eine neue Hauptversion; ein Server kann mehrere gleichzeitig.
- **Innerhalb einer Hauptversion** wird nur ergänzt: neue optionale Felder, neue Nachrichtentypen.
  Beide Seiten **ignorieren unbekannte Felder**; unbekannte Typen ignoriert der Server still, die
  App reicht sie als `ServerMessage.Unknown` durch. Ein alter Client funktioniert so mit einem
  neuen Server und umgekehrt (getestet mit den Formen `alt` und `neu` in der Beispieldatei).
- Fehlt ein Pflichtfeld oder hat es den falschen Typ (`"12"` ist keine Zahl), ist die Nachricht
  ungültig. In Listen (`riders`, `states`) fällt nur der kaputte Eintrag weg.

## 7 Grenzen

Vorgaben in Klammern; mit Umgebungsvariable änderbar, wo angegeben.

| Grenze | Wert | Variable |
|---|---|---|
| Räume je Server | 20 | `ROOMS_MAX` (1..1000) |
| Fahrer je Raum | 10 | `ROOM_MAX_RIDERS` (2..50); je Raum beim Anlegen kleiner wählbar |
| Lebensdauer eines Raums | 6 h ab Anlegen, auch wenn noch gefahren wird | `ROOM_TTL_MIN` (5..1440) |
| Leerlauf-Abbau | 15 min ohne verbundenen Fahrer | `ROOM_IDLE_MIN` |
| Platz nach Abbruch reserviert | 30 s | `ROOM_REJOIN_GRACE_S` (1..600) |
| WebSocket-Verbindungen gesamt | Räume × Fahrer + 20 | — |
| Frist für `join` | 5 s | — |
| Stille Verbindung | 20 s ohne Nachricht → 4010 | — |
| Nachrichtengröße | 1024 Byte (Rahmen größer angekündigt → 1009, bevor gepuffert wird) | — |
| Avatar | 512 Byte JSON | — |
| Anzeigename | 32 Zeichen; Steuer- und Richtungszeichen entfernt | — |
| Nachrichtenrate | Eimer mit 16 Marken, 8 je Sekunde nachgefüllt; leer → verworfen und `RATE`; 32 in Folge verworfen → 4005 | — |
| Ausgangspuffer je Verbindung | 256 KB; liest ein Gerät nicht mehr mit → getrennt | — |
| Plausibilität `state` | `distM` höchstens 5 m zurück; vorwärts höchstens 100 m + 30 m/s × Zeit seit letzter Meldung; erste Meldung nach (Wieder-)Beitritt frei | — |
| Körper `POST /api/rooms` | 4 KB | — |

Die Plausibilität fängt **Fehler** ab (Sprung durch einen Fehler in der App), sie ist keine
Betrugsabwehr — zwischen Verabredeten nicht nötig (Konzept 5.2).

## 8 Abbruch und Wiederbeitritt

- **Eigener Fahrer**: fährt lokal weiter; die Sitzung hängt nicht am Netz.
- **Verbindung reißt ab** (Funkloch, Bildschirmwechsel ohne Hintergrunddienst): Der Server hält
  den Platz 30 s frei, schickt in der Zeit **nichts** an die anderen. Die anderen sehen am Alter
  der letzten Meldung, dass etwas fehlt: nach 3 s ausgegraut (`stale`, kein Windschatten), nach
  30 s kommt `left {reason:"timeout"}`. Hört ein Gerät selbst 30 s nichts mehr von einem Fahrer,
  nimmt es ihn auch ohne `left` aus der Liste (`RoomState.positions`).
- **Wiederbeitritt**: `join` mit `rejoin {id, key}` aus dem letzten `welcome` → dieselbe Kennung,
  derselbe Platz (auch wenn der Raum inzwischen voll ist), neuer `rejoinKey`; die erste Meldung
  danach wird nicht auf Sprünge geprüft. Nach Ablauf der Frist gibt es einen neuen Platz unter
  neuer Kennung. Kommt derselbe Fahrer über eine zweite Verbindung, wird die alte mit 4007
  geschlossen.
- **App** (`ReconnectingRoomClient`): neuer Versuch nach 0,5–1 s, dann wachsend bis höchstens
  15–30 s (zufällig innerhalb der oberen Hälfte, damit nach einem Server-Neustart nicht alle im
  selben Takt kommen); nach 30 erfolglosen Versuchen Schluss; bei den Codes mit „nein“ in 5.3 sofort
  Schluss. Zustände werden während des Abbruchs **nicht** gepuffert (sie sind durch den nächsten
  ersetzt).
- **Server-Neustart**: Alle Räume sind weg (nichts auf der Platte). Geräte bekommen 1001 bzw.
  einen Abriss, versuchen es wieder und erhalten 4001 → Hinweis „Raum beendet“. Gewollt: lieber
  ein neuer Raum als ein halb wiederhergestellter.

## 9 Uhrenabgleich

Alle Zeiten im Raum sind Server-Uhr. Das Gerät rechnet mit einer monotonen eigenen Uhr
(`SystemClock.elapsedRealtime`) und einem Versatz (`RoomClock.kt`):

- grobe erste Schätzung aus `welcome.serverTime` (Laufzeit unbekannt);
- dann alle 5 s `ping {c}` → `pong {c, s}`; Laufzeit `rtt = jetzt − c`, Versatz
  `s − (c + rtt/2)`; genommen wird die Probe mit der **kleinsten Laufzeit** der letzten acht
  (Fehler höchstens `rtt/2`; Proben über 10 s Laufzeit fallen weg).

Mobilfunk-Laufzeiten von 100–300 ms ergeben so einen Versatzfehler von höchstens 50–150 ms, bei
10 m/s also 0,5–1,5 m. Das reicht für Rangfolge und Abstände; der Countdown (`startAt`) läuft auf
allen Geräten bis auf wenige Zehntelsekunden gleich.

Fremde Stellen werden für den Zeitpunkt „jetzt“ aus der letzten Meldung fortgeschrieben
(`distM + speedMps × (jetzt − at)`), höchstens 2 s weit; zwischen zwei Meldungen wird linear
interpoliert, wenn ein Renderer mit Verzögerung zeichnet (`estimateDistance`, `PositionParams`).

## 10 Was der Server NICHT speichert und nicht protokolliert

- **Nichts auf der Platte**: Räume, Fahrer, Zustände, Namen, Avatare und Geheimnisse leben nur im
  Arbeitsspeicher des Prozesses. Das Datenverzeichnis wird von den Räumen nicht berührt (Test:
  `server-rooms.test.mjs` prüft, dass es leer bleibt).
- **Kein Verlauf**: Von jedem Fahrer wird nur der **letzte** Zustand gehalten (für Spätankömmlinge
  im `welcome`), nie eine Spur. Mit dem Raum verschwindet alles.
- **Geheimnisse nur als Hash**; Klartext steht genau einmal in der Antwort auf `POST /api/rooms`
  (mit `no-store`) bzw. im `welcome` an den Fahrer selbst.
- **Protokoll (Log)**: nur „Raum angelegt (n aktiv)“, „Raum beendet: Grund (n aktiv)“ und bei
  Fehlversuchen Zeit und Adresse. **Nie** Raumkennungen, Geheimnisse, Namen, Avatare, Positionen,
  Leistung (Test prüft das an der echten Ausgabe des Servers).
- Was der Server zwangsläufig **sieht**: IP-Adressen der Verbindungen, Namen, Avatare, Positionen
  auf der Strecke, Tempo, ggf. Leistung und Trittfrequenz — für die Dauer des Raums, im Speicher.
  Bei einem selbst betriebenen Server ist das der Betreiber selbst bzw. der Verein; bei einem
  Relais wäre es der Entwickler (Abschnitt 12).
- Der Reverse-Proxy protokolliert typischerweise `GET /api/rooms/ws` mit IP und Zeit — ohne
  Raumkennung oder Geheimnis, weil beides nicht in der URL steht.

## 11 Last und Kosten

Gemessen am 25.11.2026 mit `node server/test/raum-last.mjs <Räume> <Fahrer je Raum> <s>` auf einem
Linux-Container mit 4 Kernen; Lastgerät und Server auf demselben Rechner, **ohne TLS und ohne
Reverse-Proxy**. Jeder Fahrer schickt 4 Zustände je Sekunde mit Leistung, Trittfrequenz und Spur
(≈ 90 Byte), Räume voll mit 10 Fahrern.

| gleichzeitige Fahrer | Räume | CPU Server (Anteil eines Kerns) | Speicher Server (RSS) | Nutzdaten vom Server | je Fahrer | Nachrichten vom Server |
|---|---|---|---|---|---|---|
| 10 | 1 | 0,6 % | 55 MB | 42 kB/s | 4,3 kB/s | 40/s |
| 100 | 10 | 2,4 % | 61 MB | 432 kB/s (3,5 Mbit/s) | 4,3 kB/s | 410/s |
| 1000 | 100 | 9,1 % | 110 MB | 4,3 MB/s (35 Mbit/s) | 4,3 kB/s | 4100/s |

Einordnung:

- Der Datenstrom vom Server wächst mit **Fahrer × Raumgröße** (jeder bekommt die Zustände der
  anderen), nicht mit der Gesamtzahl im Quadrat. Je Fahrer im vollen Zehnerraum rund 4,3 kB/s
  Nutzdaten, mit TLS, TCP und IP grob 5 kB/s; hin zum Server rund 0,4 kB/s Nutzdaten.
- **Handy**: rund 18 MB je Stunde herunter und 2 MB hoch im vollen Zehnerraum — neben Rendern
  und Bluetooth unerheblich; der Akku hängt am Funkmodul, das ohnehin wach bleibt.
- **10 Fahrer**: jeder vorhandene Sync-Server, auch ein Raspberry Pi, nebenbei. Mehrkosten 0 €.
- **100 Fahrer**: ein kleiner VPS reicht, CPU ist kein Thema; 3,5 Mbit/s ausgehend. Bei
  zehn Gruppenfahrten zu je einer Stunde am Tag rund 50 GB im Monat — in den Inklusivvolumen
  üblicher Kleinst-VPS enthalten. Mehrkosten 0 € auf vorhandener Maschine, sonst einstellige Euro.
- **1000 Fahrer**: weiterhin **ein** Node-Prozess (ein Kern zu rund 10 %, dazu TLS im
  Reverse-Proxy, geschätzt in derselben Größenordnung). Engpass ist die **Bandbreite**: rund
  40 Mbit/s dauerhaft ausgehend, bei zwei Stunden Spitzenlast am Tag grob 1 TB im Monat. Das
  passt auf einen VPS mit 2 Kernen und 20 TB Inklusivverkehr, wie ihn europäische Hoster für
  5–15 € im Monat anbieten (Preise geschätzt, nicht verglichen). Dafür `ROOMS_MAX` auf 100+
  setzen; die Vorgabe (20 × 10 = 200 Fahrer) ist bewusst für den Eigengebrauch.
- Nicht gemessen: Verhalten über Mobilfunk, Reverse-Proxy und TLS-Kosten, lange Läufe
  (Speicher über Stunden), Lastgerät auf getrennter Maschine. Die Zahlen sind eine Untergrenze
  für den Aufwand, keine Zusage.
- Was **nicht** skaliert, ist nicht die Technik, sondern der Betrieb: Ab „fremde Leute auf meinem
  Server“ ist es ein Relais mit Pflichten (Abschnitt 12, Konzept 6).

## 12 Gleiches Protokoll als offizielles Relais (nicht umgesetzt)

Was ein Relais ohne Konto und ohne Speicherung anders bräuchte — bewusst **nicht** gebaut:

- **Anlegen ohne `SYNC_TOKEN`**: Ein Relais hat keinen Betreiber-Token, den man Nutzern geben
  könnte. Denkbar: Anlegen offen, aber je Adresse streng begrenzt (etwa 3 Räume je Stunde), kurze
  Lebensdauer, kleine Räume. Der Rest des Protokolls (Geheimnis im Link, `join`, Nachrichten)
  bleibt gleich — deshalb sind Gastgeber-Rechte an das Gastgeber-Geheimnis gebunden und nicht an
  den `SYNC_TOKEN`.
- Ein eigener Dienst **ohne** Touren-Endpunkte (`ROOMS_ONLY`), damit ein Relais nie einen
  Datenbestand hat.
- `PRIVACY.md`, Play-Datensicherheitsformular und Impressum (Konzept 1.7, 6.2), weil dann der
  Entwickler IP-Adressen, Namen und Positionen verarbeitet.
- Missbrauchsschutz für Anzeigenamen (im Raum mit Link vertretbar ohne Moderation, weil nur
  Eingeladene sie sehen).

## 13 Dateien

| Datei | Inhalt |
|---|---|
| `server/websocket.mjs` | Handshake, Rahmen lesen/schreiben, Ping/Pong, Close, Grenzen |
| `server/rooms.mjs` | Raumlogik ohne Sockets, einspeisbare Uhr; Grenzen, Rate, Zeitüberschreitungen |
| `server/server.mjs` | `ROOMS`-Schalter, REST unter `/api/rooms`, Upgrade unter `/api/rooms/ws`, Takt, Keepalive |
| `server/test/rooms.test.mjs` | Raumlogik mit gestellter Uhr |
| `server/test/server-rooms.test.mjs` | echter Prozess: Berechtigung, kaputte Rahmen, Rate, Abbruch, Log |
| `server/test/protocol-fixtures.test.mjs`, `fixtures/raum-v1.json` | Vertrag Server ↔ App |
| `server/test/raum-last.mjs` | Lastprobe (Abschnitt 11) |
| `core/.../room/RoomMessages.kt` | Nachrichten und Codec |
| `core/.../room/RoomInvite.kt` | Einladungslink, WebSocket-Adresse |
| `core/.../room/RoomApi.kt` | Raum anlegen/beenden über `HttpClient` |
| `core/.../room/RoomClock.kt` | Uhrenabgleich |
| `core/.../room/RoomState.kt` | Raumzustand als reine Funktion, Fortschreiben, Rangfolge, Abstände |
| `core/.../room/Drafting.kt` | Windschatten-Näherung (nur gerechnet) |
| `app/.../room/RoomTransport.kt`, `OkHttpRoomTransport.kt` | WebSocket-Schicht |
| `app/.../room/RoomClient.kt` | Verbinden, Beitreten, Senden, Wiederverbinden mit Backoff |
| `app/.../room/RoomInviteActivity.kt` | Trampolin für `trailscape://room…` von außen (prüft nur, verbindet nicht) |
| `app/.../ui/indoor/IndoorRoomController.kt` | Zustandslogik der Oberfläche: Anlegen, Beitreten, Takt, „Bereit", Start, Schließgründe |
| `app/.../ui/indoor/IndoorRoomUi.kt` | Zustände für Abschnitt und Fahransicht (reine Daten) |
| `app/.../ui/indoor/IndoorRoomSection.kt` | Abschnitt „Raum" in Indoor → Gemeinsam, Dialoge „Raum anlegen" / „Raum beitreten" |
| `app/.../ui/indoor/IndoorRoomHud.kt` | Fahransicht: „Warten auf den Start", die anderen mit Abstand |
| `app/src/test/.../IndoorRoomControllerTest.kt`, `IndoorRoomFlowTest.kt`, `RoomScreenshotTest.kt` | Zustandslogik gegen Fake-Transport, ganzer Ablauf im ViewModel, Bilder |

## 14 Oberfläche in der App (Stand 25.11.2026)

Wo sie hängt und warum, steht in `docs/ux/umbau.md` („Raum für Gruppenfahrten"); die Prüfung mit
zwei Geräten in `docs/raum-pruefliste.md`. Hier steht, **wie die Oberfläche das Protokoll benutzt**.

### 14.1 Datenschutz — verbindlich

- **Kein Konto, keine Telemetrie.** Es gibt keine Abfrage „gibt es Räume?" und keinen Abgleich im
  Hintergrund. Ohne offenen Raum existiert kein Client, kein Socket und kein Thread
  (`IndoorRoomDeps.of` baut Transport und Zeitgeber erst mit dem ersten Raum).
- **Verbunden wird nur mit einem Server, den der Nutzer selbst angegeben hat, und nur, solange
  ein Raum offen ist.** Das sind genau zwei Fälle:
  1. „Raum anlegen": `POST /api/rooms` und der WebSocket gehen an den Server aus Einstellungen →
     Sync mit eigenem Server.
  2. „Raum beitreten": der WebSocket geht an den Server aus dem Einladungslink, den der Nutzer
     eingefügt oder geöffnet hat. Der Dialog zeigt die Server-Adresse, **bevor** verbunden wird;
     erst „Beitreten" verbindet. Ein von außen geöffneter Link (`RoomInviteActivity`) wird nur
     vorgemerkt. Ein Gast hat den Server also nicht in den Einstellungen eingetragen, sondern mit
     dem Link bestätigt — anders kann niemand einem fremden Raum beitreten, weil Gäste den
     `SYNC_TOKEN` nie bekommen (Abschnitt 3).
- **Was die App sendet** — weniger, als das Protokoll erlaubt:

  | Feld | Gesendet? |
  |---|---|
  | `join.name` | ja, vom Nutzer im Dialog eingegeben (bereinigt, höchstens 32 Zeichen) |
  | `join.sharePower` / `settings.sharePower` | ja; Vorgabe in der App **aus** („Leistung zeigen") |
  | `join.avatar` | **nein** (die anderen werden nicht als Figuren gezeichnet, also braucht es niemand) |
  | `state.distM`, `state.speedMps` | ja, gut 3-mal je Sekunde, **nur während einer Fahrt im Raum** |
  | `state.powerW` | nur mit „Leistung zeigen" |
  | `state.cadenceRpm`, `state.lane` | **nein** |
  | `settings.name` | nein (der Name steht beim Beitritt fest) |
  | Puls, Standort, Gerätekennung, App-Version, Sprache | **nein** — das Protokoll kennt dafür kein Feld |

  Der `SYNC_TOKEN` geht nur an `POST /api/rooms` des eigenen Servers, nie über den WebSocket und
  nie in den Einladungslink (Test: `IndoorRoomControllerTest`).
- **Was der Nutzer vorher sieht**: Beide Dialoge tragen denselben Satz (`raum_privacy`): Andere
  im Raum sehen Name, Stelle auf der Strecke und Tempo, die Leistung nur auf Wunsch; der Server
  sieht außerdem die IP-Adresse; verbunden ist man nur mit diesem Server und nur, solange der
  Raum offen ist.
- **Was die App speichert**: den eigenen Anzeigenamen und die Wahl „Leistung zeigen"
  (`indoor.room.name`, `indoor.room.share_power`). Einladung, Raum-Geheimnis und
  Gastgeber-Geheimnis leben nur im Speicher; nach einem Neustart der App ist der Raum für dieses
  Gerät weg (wieder beitreten geht mit dem Link, ein Gastgeber ist dann Gast).
- Der Einladungslink enthält das Raum-Geheimnis. „Einladung teilen" reicht ihn als Text an das
  Teilen-Blatt des Systems; die App verschickt selbst nichts.
- **Offen für den Eigentümer**: `PRIVACY.md` nennt den Raum noch nicht. Mit der Oberfläche
  verarbeitet erstmals ein *fremder* selbst betriebener Server (der des Gastgebers) Name,
  Stelle, Tempo und IP-Adresse eines Gastes. Der Absatz dazu ist nicht geschrieben, weil der Text
  dem Eigentümer vorbehalten ist.

### 14.2 „Bereit" ohne eigenes Feld

Das Protokoll kennt keinen Bereit-Schalter. „Bereit" ist in der Oberfläche, **wer am Start
steht**: Sobald jemand „Zum Start" gewählt hat und seine Fahransicht mit geladener Strecke offen
ist, meldet sein Gerät die Stelle 0 (`state` mit `distM: 0`). Wer eine Meldung hat, die nicht
älter als 5 s ist, gilt als bereit; nach dem Start heißt derselbe Zustand „Fährt · x km". Das ist
ehrlicher als ein Schalter (bereit ist, wer wirklich losfahren kann) und braucht keine
Protokolländerung. Grenze: Wer am Start steht, aber die Verbindung verloren hat, erscheint nach
5 s als „Noch nicht am Start".

### 14.3 Gemeinsamer Start

- Eine Fahrt gehört zum Raum, wenn ihre Strecke die des Raums ist und der Raum offen ist. Sie
  beginnt immer bei 0 (keine Startpunkt-Wahl, kein „Weiter bei km …"), ohne Mitfahrer aus einer
  Herausforderung und als freie Fahrt (kein ERG).
- Vor dem Start wird sie **gehalten** (`holdStart` der Laufzeit, ohne „erster Tritt gilt"). Der
  Gastgeber sendet `start {inS: 10}`; alle rechnen `startAt` über den Uhrenabgleich (Abschnitt 9)
  in Geräte-Zeit um und geben die Fahrt frei, sobald `startAt` erreicht ist. Danach beginnt sie
  wie jede Fahrt mit dem ersten Tritt.
- Wer nach dem Start an den Start kommt, fährt sofort los (bei 0).
- Endet der Raum oder verlässt man ihn, während man wartet, wird die Fahrt freigegeben: Niemand
  hängt am Start fest. Die Fahransicht sagt „Raum beendet · du fährst allein weiter".
- Reißt die Verbindung **vor** einem `countdown` ab, weiß das Gerät nichts vom Start und wartet,
  bis es wieder verbunden ist (das `welcome` trägt `startAt`) — oder der Nutzer wählt „Raum
  verlassen und allein fahren".

### 14.4 Strecke

`route.id` ist der Schlüssel der Streckenwahl in der App (`catalog:<Kennung>` für bekannte
Strecken, `testwelt` für die Teststrecke), `route.version` die Welt-Version des Katalogs als
Zahl im String — dieselbe Kennung wie bei den Herausforderungen. Die App vergleicht beides mit
dem, was auf dem Gerät liegt: bereit → „Zum Start"; fehlt oder älter → „Strecke herunterladen";
hier neuer → nicht fahrbar, mit Erklärung; unbekannte Form → „Aktualisiere Trailscape". Eigene
Touren und Planungen kann kein Raum fahren: Ihre Strecke kennt nur ein Gerät.

### 14.5 Was die Oberfläche (noch) nicht kann

- **Kein Kurzcode.** Beitreten geht nur mit dem Einladungslink (einfügen oder antippen). Ein
  kurzer, tippbarer Code bräuchte auf dem Server eine Zuordnung Code → Raum und wäre mit wenigen
  Zeichen erratbar; das Raum-Geheimnis hat mit Absicht 256 Bit (Abschnitt 3).
- **Keine Figuren der anderen in der 3D-Ansicht.** Die anderen stehen als Zeilen mit Abstand in
  Metern in der Fahransicht (die drei nächsten). Der Renderer ist dafür nicht angefasst.
- **Kein Windschatten** (`Drafting.kt` bleibt nur gerechnet).
- **Kein Hintergrunddienst.** Geht die App in den Hintergrund, pausiert die Fahrt wie immer; der
  WebSocket kann dabei abreißen. Der Server hält den Platz 30 s; danach tritt die App unter neuer
  Kennung wieder bei.
- **Keine Rangliste, kein Ergebnis am Ende**, keine Zeitabstände (nur Meter).
- Der Gastgeber kann die Strecke eines Raums nicht ändern (das Protokoll legt sie beim Anlegen
  fest); `maxRiders`, `ttlMin` und ein geplanter `startAt` beim Anlegen werden nicht angeboten —
  es gelten die Vorgaben des Servers.
- Kein QR-Code für die Einladung.
