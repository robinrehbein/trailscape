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
GitHub-Release indoor-worlds-v1:  <id>-v<version>.zip  <id>-v<version>.jpg  …  catalog.json
        │ App: catalog.json + Vorschaubilder von selbst (höchstens täglich), Pakete nur nach „Laden“
        ▼
<filesDir>/indoor/worlds/<worldId>/  (+ app-catalog.json)  → fahrbar wie jede Welt
```

- Die **Liste** liegt der App bei (`app/src/main/assets/indoor/catalog.json`)
  und ist ohne Netz sichtbar.
- Die **Welten** (Paket rund 5–8 MB je km, entpackt 8–13 MB je km) liegen nicht in der APK, sondern als
  Assets des Releases `indoor-worlds-v1` des öffentlichen Repos.
- Die **Strecke der Fahrt** kommt aus dem Manifest der Welt
  (`worldRouteOf(manifest)`, wie bei der Testwelt): Laufmeter, Höhen der
  gezeichneten Fahrbahn und Beläge passen dadurch zur Welt.

## Strecken

Gebaut am 23.11.2026 lokal mit `catalog-build` (Qualität `MEDIUM`). Die Pakete
des Releases baut der Workflow neu; Größen weichen dann leicht ab (Geodaten
ändern sich), Prüfsummen sowieso.

| Kennung | Name | Land | Art | km | Hm | Belag | Paket | Platte | Luftbild |
|---|---|---|---|---|---|---|---|---|---|
| `alpe-dhuez` | Alpe d'Huez | Frankreich | Rennrad | 15.1 | 1253 | Asphalt 100 % | 77 MB | 132 MB | © IGN – BD ORTHO®, Licence Ouverte Etalab (2026) |
| `vlaamse-ardennen` | Oude Kwaremont, Paterberg und Koppenberg | Belgien | Pflaster | 9.9 | 179 | Asphalt 59 %, Pflaster 29 %, Beton 13 % | 71 MB | 117 MB | Bron: Luchtopnamen Digitaal Vlaanderen |
| `arenberg` | Wald von Arenberg | Frankreich | Pflaster | 10.0 | 20 | Asphalt 68 %, Pflaster 23 %, unbekannt 8 % | 82 MB | 148 MB | © IGN – BD ORTHO®, Licence Ouverte Etalab (2026) |
| `monte-sante-marie` | Schotter von Monte Sante Marie | Italien | Schotter | 12.0 | 284 | fester Weg 85 %, Schotter 14 % | 64 MB | 90 MB | **nein** (erzeugter Boden) |
| `sa-calobra` | Sa Calobra | Spanien | Rennrad | 10.2 | 869 | Asphalt 100 % | 50 MB | 81 MB | PNOA CC-BY 4.0 scne.es 2026 |
| `flint-hills` | Schotter der Flint Hills | USA | Schotter | 11.3 | 111 | Schotter 90 %, Asphalt 10 % | 63 MB | 93 MB | USGS, USDA, The National Map: Orthoimagery |
| `grosser-feldberg` | Großer Feldberg | Deutschland | Rennrad | 11.7 | 584 | Asphalt 100 % | 98 MB | 169 MB | Geobasisdaten © Hessische Verwaltung für Bodenmanagement und Geoinformation, bearbeitet |
| `schauinsland` | Schauinsland | Deutschland | Rennrad | 15.7 | 1045 | Asphalt 93 %, fester Weg 6 % | 114 MB | 199 MB | LGL-BW (2026) Datenlizenz Deutschland - Namensnennung - Version 2.0, www.lgl-bw.de |
| `kahler-asten` | Waldwege am Kahlen Asten | Deutschland | Schotter | 13.2 | 248 | fester Weg 60 %, Asphalt 21 %, Schotter 19 % | 81 MB | 136 MB | © Geobasis NRW |
| `rennsteig` | Rennsteig bei Oberhof | Deutschland | Schotter | 13.3 | 365 | Schotter 84 %, Asphalt 9 % | 108 MB | 195 MB | © GDI-Th |

Flint Hills: 113 von 162 Kacheln mit Luftbild — NAIP antwortet langsam (15–30 s je Bild), einzelne Anfragen liefen in die Frist des Werkzeugs; dort erzeugter Boden. Pakete gesamt rund 809 MB.

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
| `sa-calobra.jpg` | km 3,4: Asphaltstraße am Hang, Luftbild PNOA | Der Weltbau setzt Fichten und Laubbäume statt Aleppo-Kiefern; Kalkfels erscheint nur als helle Bodenflecken |
| `grosser-feldberg.jpg` | km 7,8: Straße durch Fichtenwald nahe dem Sandplacken, Luftbild HVBG | — |
| `schauinsland.jpg` | km 5,2: Kurve durch Bergwald, Luftbild LGL-BW | Unter dem Kronendach dunkel |
| `flint-hills.jpg` | km 3,8: breiter Schotterweg durch offenes Grasland mit Weidezaun, Luftbild NAIP | Die Prärie ist zu grün und hat zu viele Bäume (Landnutzung/Kronenschätzung aus dem Luftbild) |

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
      "aerial": true, "aerialSource": "Luftbild: © IGN …", "quality": "MEDIUM", "diskBytes": …,
      "laser": true,
      "profile": {"stepM": 125.96, "elevM": [719.2, 721.0, …]},
      "preview": {"file": "alpe-dhuez-v9.jpg", "bytes": 87654, "sha256": "…", "width": 640, "height": 360} } ] }
```

- **Seit 24.11.2026 optional:** `laser` (Welt in der Datenstufe „Laser“,
  erkannt an der Quellenangabe einer Laser-Quelle im Manifest),
  `profile` (Höhenprofil der gezeichneten Fahrbahn, 121 gleichmäßige
  Proben, 0,1 m), `preview` (Vorschaubild im selben Release). Das **Format
  bleibt 1**: Ältere Apps lesen nur bekannte Felder und überspringen den
  Rest — geprüft mit `CatalogPreviewFormatTest.aelterLeserUeberspringtNeueFelder`
  (Fixture `core/src/test/resources/indoor/catalog/catalog-neue-felder.json`)
  und einmal von Hand gegen den Leser vor der Änderung. Ein kaputtes Profil
  oder ein unzulässiger Bildname kostet nur die Vorschau, nie den Eintrag.
  Ein höheres `format` dürfte es nicht geben: Ältere Apps lehnen dann den
  ganzen Katalog ab.

- Unlesbare Einträge werden übersprungen (ein neuerer Katalog leert die
  Liste nicht), Kennungen und Paketnamen sind klein und ohne Trenner.
- Der beigelegte Katalog hat **keine Prüfsummen** (`--app-catalog` lässt sie
  weg): Vor jedem „Laden“ holt die App `catalog.json` aus dem Release und
  nimmt Version, Größe und SHA-256 von dort. Ein Release-Katalog kann die
  Adresse der Pakete nicht umlenken (die beigelegte `baseUrl` gilt).
- Stand 24.11.2026 (Welt-Version 10): Der beigelegte Katalog
  (`app/src/main/assets/indoor/catalog.json`) führt alle **14** Einträge des
  veröffentlichten Katalogs (Texte, Größen, Welt-IDs aus dem Release), mit den
  Versionen **nach** dem Anheben (`catalog-src.json`, z. B. `solitude` 2,
  `alpe-dhuez` 10). Bis der Katalog-Workflow die neuen Pakete hochlädt, liegen im
  Release noch die alten; das ist unschädlich: Geladen wird immer der Eintrag
  aus dem frisch geholten Release-Katalog (`IndoorCatalogController`:
  `fresh.entry(id)`, ohne `sha256` → „nicht verfügbar"), ein beigelegter Eintrag
  ohne Paket bietet also keinen kaputten Download an. Höchstens zeigt die App
  vor dem ersten Abruf „Aktualisierung" für eine installierte ältere Version
  und lädt dann dieselbe Version wie bisher.

## Vorschau je Strecke (24.11.2026)

Wunsch des Gründers: vor dem Laden sehen, wie die Strecke aussieht.

- **Was der Katalog heute (Release `indoor-worlds-v1`, geprüft am
  24.11.2026, 14 Einträge) schon hat:** Name, Region, Land, Art, Länge,
  Höhenmeter, Belagsanteile, Text, Paketgröße, Platzbedarf, Luftbild ja/nein
  mit Quelle, Qualität. **Was fehlte:** Vorschaubild, Höhenprofil,
  Laser-Kennzeichen (alle Einträge stehen auf `MEDIUM`; ob Laser
  verwendet wurde, stand nirgends).
- **Karte:** oben das Vorschaubild (16 : 9), ohne Bild das Höhenprofil
  mit Spanne („17–31 m“), ohne beides ein ruhiger Platzhalter „Noch keine
  Vorschau“; Kennzeichen „Laser“ und „Neu“ (kam mit dem letzten Abruf
  dazu) oben links. Darunter Name, Region · Land, Art, km · Hm · Belag, mit
  Bild das Profil klein, Text, „Paket 106,0 MB · Mit Luftbild“, bei Laser
  ein Satz dazu, Zustand/Knopf. Bilder:
  `bilder/katalog-vorschau/01-karten.jpg` (deutsch, alle vier Formen),
  `02-karten-en.jpg` (englisch); Test `IndoorScreenshotTest.katalogVorschau*`
  (Vorschaubilder dort aus `bilder/catalog/`, verkleinert; Profile aus den
  GPX-Dateien unter `catalog/routes/`).
- **Profil ohne Katalogprofil:** Ist die Welt geladen, rechnet die App es
  aus dem Manifest (`CatalogProfile.thinned`).
- **Bilder in der App:** nur `baseUrl` des beigelegten Katalogs +
  Dateiname (wie die Pakete, `isAllowedCatalogUrl` je Station), höchstens
  400 kB, Größe, JPEG-Kopf und SHA-256 müssen zum Katalog passen. Cache
  `<cacheDir>/indoor/catalog-previews/<id>-v<version>.jpg`; Bilder alter
  Versionen löscht die App. Je Bild ein Versuch je Sitzung; „Katalog
  aktualisieren“ versucht fehlende erneut.

### Automatisch aktualisieren

Der Gründer fand neue Strecken nicht, weil die App den Release-Katalog nur
auf „Katalog aktualisieren“ holte. Jetzt (`IndoorCatalogController.load`):

1. Liste sofort aus der Platte (beigelegt + zuletzt geholter Katalog).
2. Danach still `catalog.json` aus dem Release, wenn das Gerät ein Netz hat
   (`AppServices.isNetworkAvailable`, `ACCESS_NETWORK_STATE`; im Flugmodus
   kein Versuch) und `CatalogAutoRefresh.isDue` gilt: **höchstens einmal am
   Tag** — anderer Kalendertag und mindestens 6 h seit dem letzten Versuch,
   spätestens nach 24 h, oder die Uhr wurde um mehr als 1 h zurückgestellt.
   Gemerkt in `<filesDir>/indoor/catalog/auto-refresh.txt`; ein
   Verbindungsfehler zählt nicht als Versuch, ein Serverfehler (z. B. 503)
   schon. Keine Meldung, die vorhandene Liste bleibt. Während des Abrufs
   dreht der Kreis am Knopf.
3. Dann die Vorschaubilder.

„Katalog aktualisieren“ holt weiterhin sofort (und zählt als Abruf des
Tages). Tests: `CatalogPreviewFormatTest.abrufHoechstensEinmalAmTag`,
`IndoorCatalogFlowTest.automatischerAbruf…`, `ohneNetzKeinAbruf…`,
`vorschaubildWirdGeprueftGecachtUndProfilGezeigt`.

### Katalogbau

- `catalog-build` schreibt `laser` und `profile` je gebautem Eintrag.
- `catalog-preview --out <dir> --prepare [--only …]` legt je Eintrag die
  Welt bereit (gebaut oder aus `<dir>/packages/<paket>` entpackt) und
  schreibt `<dir>/previews/plan.tsv` (Kennung, Version, Welt, Laufmeter).
  Die Stelle steht in `tool/indoor-dev/catalog/previews.json` (km, wie die
  Bilder in `bilder/catalog/`), sonst 45 % der Strecke.
- Der Betrachter rendert: `node shots.mjs <welt> --dists <m> --cameras chase
  --size 960x540 --jpeg-width 640 --jpeg-max-kb 150` (neue Optionen
  `--jpeg-width`, `--jpeg-max-kb`). Probe mit der Testwelt: 640 × 360,
  43 kB.
- `catalog-preview --out <dir>` trägt Bild (Größe, Maße, SHA-256), Profil
  und Laser ein und kopiert die Bilder nach `<dir>/packages/`.
- Workflow `indoor-worlds.yml`: nach dem Bau Node 22, `npm ci`, Playwright
  1.49.1 mit Chromium, dann die drei Schritte; Fehler dort kosten nur Bilder
  (`continue-on-error`). Neue Eingabe **`preview_only`**: kein Neubau, die
  Pakete kommen aus dem Release, hochgeladen werden nur Bilder und
  `catalog.json`.

**Was beim nächsten Katalogbau neu entsteht** (Workflow nach dem Merge):
je gebauter Strecke ein Release-Asset `<id>-v<version>.jpg` (640 × 360,
Ziel ≤ 150 kB) und in `catalog.json` die Felder `profile`, `laser`
(für Laser-Strecken) und `preview`. Strecken, die ein Lauf mit `only` nicht
baut, behalten ihren Stand; für sie (oder um Bilder ohne den stundenlangen
Neubau nachzuziehen) den Workflow mit `preview_only: true` starten. Die App
zeigt die Vorschau beim nächsten Öffnen der Liste (spätestens am nächsten
Tag, sofort mit „Katalog aktualisieren“). Der beigelegte Katalog der App
bleibt in diesem Branch unverändert (kein Profil, kein Bild); wer ihn mit
`--app-catalog` erneuert, bekommt die Profile mit hinein.

**Nicht geprüft:** echte Katalogwelten im Betrachter auf dem CI-Rechner
(Laufzeit, Bildgröße mit Luftbild, dunkle Waldbilder — `shots.mjs` meldet
sie, das Bild wird trotzdem genommen); die Karten auf einem echten Gerät
(Decodieren, Scrollen mit 14 Bildern, Flugmodus-Erkennung, Abruf über
Mobilfunk).

## App

- **Streckenwahl** (Schritt 2): zwei Bereiche „Eigene Strecken“ / „Bekannte
  Strecken“. Karten mit Vorschau (siehe oben), Name, Region und Land, km, Hm, Belagsanteilen, Art
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

Von selbst nur `catalog.json` (höchstens täglich) und die Vorschaubilder
(je Bild einmal), beides nur mit Netz; Pakete nur nach „Laden“,
„Fortsetzen“ oder „Aktualisieren“; alles von
`github.com/robinrehbein/trailscape/releases/download/…`,
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
   Strecken in NRW und Baden-Württemberg bekommen `"laser": true`: `catalog-build` baut sie dann
   mit der Datenstufe „Laser“ (DGM1, nDOM, LoD2; `docs/indoor/lidar.md`),
   ohne weitere Eingabe — auch im Workflow. Eingetragen für `kahler-asten`,
   `balkantrasse` und `altenberg` (Stand 24.11.2026), in Baden-Württemberg
   für `solitude` und `maulbronn` (neu, Version 1). Außerhalb der
   Laser-Quellen hat das Feld keine Wirkung.
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
(Prüfung in `tool/indoor-dev/catalog/LUFTBILD.md`). Baden-Württemberg steht
seit 24.11.2026 im Register der App (`world.md`). Ein neuer Dienst braucht
eine echte `GetCapabilities`-/`GetMap`-Prüfung, Lizenz und Wortlaut der
Namensnennung — wie im Register der App.

## Lizenzen

| Was | Quelle | Lizenz / Angabe |
|---|---|---|
| Streckenverlauf (GPX, Belag) | OpenStreetMap über BRouter (`brouter.de`) zwischen eigenen Wegpunkten; keine fremden GPX-Dateien | ODbL 1.0, „© OpenStreetMap-Mitwirkende“ (steht in jeder GPX-Datei und in `OpenSourceNotices`) |
| Höhen, Karteninhalt | wie jede Welt (`docs/indoor.md` 8.1) | im Manifest jeder Welt |
| Luftbild DE (NRW, TH, HE, BW) | Register der App (NRW, TH, BW) bzw. `CatalogAerial.kt` (HE) | dl-de/zero-2-0 (NRW, HE), CC BY 4.0 (TH), dl-de/by-2-0 mit Pflichtvermerk und Jahr (BW) |
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
6. **Release-Größe und Bandbreite:** rund 50–115 MB je Strecke, zusammen gut 800 MB; GitHub
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

## Eigene Strecken in Laserqualität

Katalogwelten baut die CI vor; eigene Strecken kann der eigene Server mit
derselben Laser-Stufe bauen. Das Paketformat und der Installationsweg sind
dieselben (`CatalogStore.installPackage`). Siehe [`serverbau.md`](serverbau.md).
