# Indoor — Katalog bekannter Strecken

Stand: 23.11.2026, Branch `claude/indoor-catalog`.

Zusätzlich zu den selbst gebauten Welten (eigene Planung oder Tour → „Welt
laden“) bietet der Indoor-Ablauf **bekannte Strecken mit vorgebauter
3D-Welt** an: Pässe, Pflaster- und Schotterabschnitte, die man sonst nur aus
dem Fernsehen kennt. Wunsch des Gründers: „zusätzlich, nicht stattdessen“.

## Überblick

```
tool/indoor-dev/catalog/catalog-src.json   (Einträge: Name, Text, Wegpunkte, BRouter-Profil, Version)
        │ indoor-dev catalog-routes   (einmalig, brouter.de)
        ▼
tool/indoor-dev/catalog/routes/<id>.gpx + <id>.surfaces.json   (im Repo, ODbL)
        │ indoor-dev catalog-build   (Workflow indoor-worlds.yml, nur manuell)
        ▼
GitHub-Release indoor-worlds-v1:  <id>-v<version>.zip  …  catalog.json
        │ App: „Laden“ (nur nach Antippen)
        ▼
<filesDir>/indoor/worlds/<worldId>/  (+ app-catalog.json)  → fahrbar wie jede Welt
```

- Die **Liste** liegt der App bei (`app/src/main/assets/indoor/catalog.json`)
  und ist ohne Netz sichtbar.
- Die **Welten** (6–16 MB je km Paket) liegen nicht in der APK, sondern als
  Assets des Releases `indoor-worlds-v1` des öffentlichen Repos.
- Die **Strecke der Fahrt** kommt aus dem Manifest der Welt
  (`worldRouteOf(manifest)`, wie bei der Testwelt): Laufmeter, Höhen der
  gezeichneten Fahrbahn und Beläge passen dadurch zur Welt.

## Strecken

Gebaut am 23.11.2026 lokal mit `catalog-build` (Qualität `MEDIUM`). Die Pakete
des Releases baut der Workflow neu; Größen weichen dann leicht ab (Geodaten
ändern sich), Prüfsummen sowieso.

<!-- TABELLE -->

Namen sind **Ortsnamen**, keine Veranstaltermarken („Wald von Arenberg“ statt
eines Rennnamens); der Text sagt sachlich, wofür der Abschnitt bekannt ist.
Keine Logos.

### Was die Bilder zeigen (`docs/indoor/bilder/catalog/`)

Gerendert mit `tool/indoor-dev/viewer/shots.mjs` (three.js, headless
Chromium, Kamera `chase`), 1280 × 720.

| Bild | Zu sehen | Was nicht stimmt |
|---|---|---|
| `alpe-dhuez.jpg` | km 7,6: Asphalt mit Leitpfosten, Laubwald am Hang, Luftbild IGN | Felsflächen des Luftbilds erscheinen als helle, flache Flecken neben der Straße; die Kehren selbst sind aus dieser Kamera nicht zu sehen |
| `vlaamse-ardennen.jpg` | Koppenberg (km 8,5): Kopfsteinpflaster im Wald, Luftbild Digitaal Vlaanderen | Am Start (Kwaremont) stehen generische Häuser mit deutschem Satteldach; flämische Backsteinreihen kennt der Weltbau nicht |
| `arenberg.jpg` | Trouée d'Arenberg (km 7): schnurgerades Pflaster zwischen Bäumen, Luftbild IGN | Der Wald ist dunkel (bekannt aus `world.md`), die Bäume stehen in zu regelmäßigen Reihen |
| `monte-sante-marie.jpg` | km 3: heller Schotterweg über offene Hügel, Wegweiser | **ohne Luftbild**: Boden aus der Landnutzung erzeugt (Wiese statt der typischen kahlen Crete-Senesi-Hügel) |
| `kahler-asten.jpg` | km 4,4: Forstweg durch Fichten- und Laubwald, Luftbild NRW | Unter dem Kronendach dunkel |
| `rennsteig.jpg` | km 4,4: Schotterweg neben der Straße durch Fichtenwald, Luftbild GDI-Th | — |
<!-- BILDER-REST -->

## Paketformat

`core/…/indoor/catalog/CatalogPackage.kt`. Ein ZIP mit genau:

```
package.json     {format: 1, id, version, worldId, files: [{path, bytes, sha256}]}
manifest.json    WorldManifest
tiles/*.glb      alle Kacheln, sortiert
```

- **Stabil:** feste Zeitstempel (1.1.2020), feste Reihenfolge, Deflate
  Stufe 6 — dieselbe Welt ergibt bytegleiche Pakete (Test
  `paketRundreiseUndStabil`). Zwischenstände des Baus (`build/`) und Bilder
  bleiben draußen.
- **Prüfung beim Entpacken:** SHA-256 des ganzen Pakets gegen den Katalog
  (beim Download), danach jede Datei gegen `package.json`; nur die Pfade
  oben sind erlaubt (kein Zip-Slip, keine fremden Dateien), Kennung, Version
  und `worldId` müssen passen, das Manifest muss lesbar sein und alle Kacheln
  müssen im Paket stehen. Entpackt wird nach
  `<filesDir>/indoor/catalog/tmp/<id>`, erst danach umbenannt.

## Katalogformat

`core/…/indoor/catalog/IndoorCatalog.kt`, JSON von Hand:

```json
{ "format": 1,
  "baseUrl": "https://github.com/robinrehbein/trailscape/releases/download/indoor-worlds-v1/",
  "generatedAtMs": 0,
  "entries": [ { "id": "alpe-dhuez", "version": 1, "worldId": "…",
      "name": {"de": "…", "en": "…"}, "region": {…}, "description": {…},
      "country": "FR", "kind": "ROAD|GRAVEL|COBBLES",
      "lengthM": 15115, "ascentM": 1105, "surfaces": {"ASPHALT": 1.0},
      "packageFile": "alpe-dhuez-v1.zip", "packageBytes": 81079532, "sha256": "…",
      "aerial": true, "aerialSource": "Luftbild: © IGN …", "quality": "MEDIUM", "diskBytes": … } ] }
```

- Unlesbare Einträge werden übersprungen (ein neuerer Katalog leert die
  Liste nicht), Kennungen und Paketnamen sind klein und ohne Trenner.
- Der beigelegte Katalog hat **keine Prüfsummen** (`--app-catalog` lässt sie
  weg): Vor jedem „Laden“ holt die App `catalog.json` aus dem Release und
  nimmt Version, Größe und SHA-256 von dort. Ein Release-Katalog kann die
  Adresse der Pakete nicht umlenken (die beigelegte `baseUrl` gilt).

## App

- **Streckenwahl** (Schritt 2): zwei Bereiche „Eigene Strecken“ / „Bekannte
  Strecken“. Karten mit Name, Region und Land, km, Hm, Belagsanteilen, Art
  (Rennrad/Schotter/Pflaster), Text, Luftbild ja/nein und Zustand:
  nicht geladen („Laden (77 MB)“) · lädt x % mit „Abbrechen“ · angefangen
  („Fortsetzen“/„Verwerfen“) · wird geprüft und entpackt · bereit (wählbar) ·
  Update verfügbar (alte Fassung bleibt wählbar) · Fehler (404 → „Paket ist
  noch nicht verfügbar“, kein Netz, Prüfsumme, Platz). Unten „Katalog
  aktualisieren“ und ein Hinweis, wohin die Anfrage geht. Bilder 116 und
  117 (`docs/indoor/bilder/app/`).
- **Bereit → direkt losfahren:** „Weiter“ mit einer bereiten bekannten
  Strecke öffnet die Welt ohne Voranschlag und ohne Netz und startet die
  Fahrt sofort (`IndoorViewModel.continueFromRoute`, `autoStart`). Die
  Quellenangaben der Welt sind im HUD erreichbar.
- **Weltverwaltung:** Katalogwelten stehen dort mit Kennzeichen „Bekannte
  Strecke“, sind löschbar und nie „verwaist“ (keine Tour dahinter).
- Code: `app/…/indoor/catalog/CatalogDownloader.kt` (OkHttp, eigene
  Weiterleitungsprüfung, `Range`, SHA-256), `CatalogStore.kt` (Dateien,
  Einsortieren), `ui/indoor/IndoorCatalogController.kt` (Zustand, Aktionen),
  `IndoorCatalogSection.kt` (Oberfläche).
- Tests: `IndoorCatalogTest` (`:core`: Format, Zusammenführen, Positivliste,
  Paket stabil/Rundreise, manipulierte Kachel, unsichere Pfade),
  `CatalogDownloaderTest` (gefälschter Server: vollständig, Abbruch +
  Wiederaufnahme mit `Range`, Server ohne Range, 416, Prüfsummenfehler,
  zu lange Antwort, 404, 503, keine Verbindung, Weiterleitungen),
  `IndoorCatalogFlowTest` (ViewModel: Liste ohne Netz, 404 → „noch nicht
  verfügbar“, Laden mit echtem Paket aus der Testwelt → bereit → direkt in
  die Fahrt auf dem Band des Manifests, Prüfsummenfehler, Fortsetzen nach
  Abbruch, Update), Screenshots 116/117 in `IndoorScreenshotTest`.

### Netz (PRIVACY.md, Abschnitt 4)

Nur nach „Laden“, „Fortsetzen“, „Aktualisieren“ oder „Katalog
aktualisieren“: `github.com/robinrehbein/trailscape/releases/download/…`,
das mit `302` auf `release-assets.githubusercontent.com` weiterleitet
(geprüft am 23.11.2026; früher `objects.githubusercontent.com`, ebenfalls
erlaubt). `Range`-Anfragen gehen dort (`206`). Jede Station wird gegen
`isAllowedCatalogUrl` geprüft; keine Cookies, User-Agent
`Trailscape/<Version>`. Die Geodienste fragt die App für Katalogwelten
**nicht** an.

## Eine Strecke hinzufügen

1. Eintrag in `tool/indoor-dev/catalog/catalog-src.json`: `id` (klein,
   Bindestriche), `version: 1`, Name/Region/Text deutsch und englisch,
   `country`, `kind`, BRouter-`profile` (`fastbike` für Straße, `gravel`
   oder `trekking` für Schotter/Pflaster) und `waypoints` `[lat, lon]`
   entlang der bekannten Strecke (Anfang, markante Punkte, Ende; Punkte am
   besten aus Nominatim/OSM). Wo OSM den Belag nicht kennt:
   `surfaceOverrides` (`fromM`/`toM` in Laufmetern, `kind`, `onlyUnknown`).
2. `./gradlew :indoor-dev:run --args="catalog-routes --only <id>"` — schreibt
   GPX und Beläge unter `catalog/routes/`, gibt km/Hm aus. Belagsanteile
   ansehen; die Strecke im Betrachter oder auf einer Karte prüfen.
3. Lokal bauen und ansehen:
   `./gradlew :indoor-dev:run --args="catalog-build --out tool/indoor-dev/out/catalog --only <id>"`,
   dann `cd tool/indoor-dev/viewer && node shots.mjs ../out/catalog/worlds/<id> --count 4 --cameras chase,drone --jpeg --models ../../../app/src/main/assets/indoor/models`.
4. Beigelegten Katalog erneuern (ohne Prüfsummen):
   `… catalog-build --out tool/indoor-dev/out/catalog --app-catalog app/src/main/assets/indoor/catalog.json`
   (baut nur, was noch nicht im Ausgabeverzeichnis liegt, schnell).
5. Nach dem Merge auf `main`: Workflow **„Indoor-Welten (Katalog)“** starten
   (Actions → Run workflow, optional `only: <id>`). Er baut, prüft, packt und
   lädt Pakete und `catalog.json` hoch.
6. Eine veröffentlichte Strecke neu bauen: `version` erhöhen (neuer
   Paketname → „Update verfügbar“ in der App), dann wie 2–5.

Luftbild außerhalb des App-Registers: Dienste, die nur das Vorbauen nutzt,
stehen in `tool/indoor-dev/src/main/kotlin/de/trailscape/tool/indoor/CatalogAerial.kt`
(Prüfung in `tool/indoor-dev/catalog/LUFTBILD.md`). Ein neuer Dienst braucht
eine echte `GetCapabilities`-/`GetMap`-Prüfung, Lizenz und Wortlaut der
Namensnennung — wie im Register der App.

## Lizenzen

| Was | Quelle | Lizenz / Angabe |
|---|---|---|
| Streckenverlauf (GPX, Belag) | OpenStreetMap über BRouter (`brouter.de`) zwischen eigenen Wegpunkten; keine fremden GPX-Dateien | ODbL 1.0, „© OpenStreetMap-Mitwirkende“ (steht in jeder GPX-Datei und in `OpenSourceNotices`) |
| Höhen, Karteninhalt | wie jede Welt (`docs/indoor.md` 8.1) | im Manifest jeder Welt |
| Luftbild DE (NRW, TH, HE, BW) | Register der App bzw. `CatalogAerial.kt` | dl-de/zero-2-0 (NRW, HE), CC BY 4.0 (TH), dl-de/by-2-0 mit Jahr (BW) |
| Luftbild FR | IGN Géoplateforme, BD ORTHO® | Licence Ouverte Etalab 2.0, mit Jahr |
| Luftbild Flandern | Digitaal Vlaanderen | Modellicentie gratis hergebruik v1.0 |
| Luftbild ES | IGN, PNOA | CC BY 4.0, „PNOA CC-BY 4.0 scne.es <Jahr>“ |
| Luftbild US | USGS/USDA NAIP | Public Domain |
| Italien | — | keine sauber offene Quelle (LUFTBILD.md) → Welt ohne Luftbild, in der App als „Ohne Luftbild“ gekennzeichnet |

Die genaue Namensnennung je Welt steht in `manifest.json` → `attribution`
und erscheint im HUD und in der Weltverwaltung; `OpenSourceNotices` nennt
alle Quellen.

## Offene Gründer-Entscheidungen und To-dos

1. **Workflow starten** (nach dem Merge auf `main`): Actions → „Indoor-Welten
   (Katalog)“ → Run workflow. Bis dahin sagt die App bei „Laden“ ehrlich
   „Paket ist noch nicht verfügbar“.
2. **Danach optional** den `catalog.json` des Releases (ohne `sha256`) als
   `app/src/main/assets/indoor/catalog.json` übernehmen, damit die
   beigelegten Größen genau stimmen — nötig ist es nicht.
3. **Ausländische Luftbilder auch in der App?** Heute nur beim Vorbauen. Für
   eigene Auslandsstrecken müssten die Dienste ins Register `aerialSources`
   (`:core`) und damit in die Positivliste und `PRIVACY.md`; Frankreich,
   Spanien und Baden-Württemberg verlangen dabei ein Jahr in der
   Namensnennung (der Weltbau der App hat dafür noch keinen Platzhalter).
4. **Spanien (PNOA):** Ob eine 3D-Textur als „abgeleitetes Werk“ einen
   anderen Wortlaut braucht, ist nicht geklärt (LUFTBILD.md).
5. **OpenFreeMap-Nutzungsbedingungen** (wie `docs/indoor.md` 11.2): Die
   Vektorkacheln der Katalogwelten lädt jetzt einmalig die CI statt jedes
   Handys — eher entlastend, aber vor dem Release mit klären.
6. **Release-Größe und Bandbreite:** rund 70–110 MB je Strecke; GitHub
   erlaubt 2 GB je Datei, ohne Gesamtgrenze für Releases. Über Mobilfunk
   gibt es keine eigene Warnung (wie bei „Welt laden“), die Größe steht auf
   dem Knopf.
7. **Auswahl der Strecken:** zehn Vorschläge, beliebig ersetzbar. Ein Eintrag
   Brocken (Sachsen-Anhalt, Luftbild jetzt belegbar) läge nahe.

## Abweichungen und Annahmen

- Eingefrorene Vertragsdateien (`docs/indoor.md` 2.1) sind **unverändert**.
  `WorldLibrary.WorldInfo` hat ein neues Feld mit Vorgabewert (`catalog`),
  `IndoorWorldEntryUi` ebenso (`fromCatalog`).
- Der Download hält nur einen Vorgang gleichzeitig; er läuft im ViewModel
  des Indoor-Ablaufs (kein Hintergrunddienst): Wer den Ablauf verlässt,
  bricht ab, der Teil bleibt für „Fortsetzen“.
- Hm im Katalog stammen aus dem Streckenband der Welt mit 4-m-Schwelle;
  BRouter nennt etwas andere Werte.
