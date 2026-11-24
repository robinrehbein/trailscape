# Betrachter für Indoor-Welten

Zeigt eine gebaute Welt (`manifest.json` + `tiles/*.glb`, siehe
`docs/indoor.md` Abschnitt 7) mit three.js in headless Chromium und macht
Screenshots. Der Betrachter ist die **Referenzleserin** des Formats: Er prüft
jede GLB beim Laden gegen 7.3 und soll zeigen, wie die Welt später in Filament
aussieht.

## Aufrufe

```
cd tool/indoor-dev/viewer
npm install                                  # nur three; Playwright ist global vorhanden
node mockworld.mjs                           # erfundene Mini-Welt nach ../out/mock (ca. 40 s)
node shots.mjs ../out/mock                   # 6 Stellen × 3 Kameras nach ../out/mock/shots/
node shots.mjs ../out/<name> --dists 0,500,1200 --cameras chase,drone --jpeg
node shots.mjs ../out/mock --serve --port 8123   # nur Server, Seite im Browser öffnen
```

`shots.mjs <weltverzeichnis>` kennt:

| Option | Bedeutung |
|---|---|
| `--dists 0,500,…` | Laufmeter der Bilder; sonst `--count N` (Vorgabe 6) gleichmäßig über die Strecke |
| `--cameras chase,handlebar,drone,overview` | Kameras je Stelle (`overview`: Übersicht aus ~650 m Höhe) |
| `--size 1600x900` | Bildgröße (deviceScaleFactor 1) |
| `--jpeg` | zusätzlich JPEG, 1280 px breit, unter 300 kB |
| `--out <dir>` | Zielverzeichnis, sonst `<welt>/shots/` |
| `--sun-az`, `--sun-el` | Sonnenazimut (0 = Nord, 90 = Ost) und -höhe in Grad; Vorgabe 235/40 |
| `--sky <dir>` | Himmel der Darstellung aus `indoor-dev sky --out <dir>` (Seite: `skyDir`): Rundumbild mit Wolken, Sonnenstand, Sonnenfarbe, Lichtstärken und Dunst wie auf dem Gerät; `--sun-*` gehen vor |
| `--models <dir>` | Verzeichnis mit `rider.glb`/`ghost.glb`; Vorgabe `app/src/main/assets/indoor/models`. Fehlt `rider.glb`, zeichnet der Betrachter Platzhalter-Quader |
| `--no-rider`, `--no-attribution` | Fahrer bzw. Quellenangaben weglassen |
| `--param k=v` | beliebiger URL-Parameter der Seite (mehrfach) |
| `--serve [--port N]` | keine Bilder, nur der Server (127.0.0.1) |

Dateien heißen `<laufmeter fünfstellig>_<kamera>.png`. Je Bild stehen Zeit und
`stats()` auf der Konsole. Exit-Code 1 bei Konsolenfehlern, Formatverstößen,
nicht ladbaren Kacheln, einer Stelle ohne Kachel unter dem Fahrer oder einem
schwarzen/leeren Bild (Mittelwert und Streuung der Helligkeit); 2 bei
falschem Aufruf.

Der Server legt drei Pfade an: `/viewer/` (dieses Verzeichnis), `/world/` (die
Welt), `/models/` (die Modelle).

## URL-Parameter der Seite

| Parameter | Vorgabe | Bedeutung |
|---|---|---|
| `world` | `../out/mock` | URL des Weltverzeichnisses |
| `models` | `../../../app/src/main/assets/indoor/models` | URL des Modellverzeichnisses; leer = Platzhalter ohne Ladeversuch |
| `dist`, `camera` | `0`, `chase` | Stelle und Kamera beim Start |
| `sunAz`, `sunEl` | `235`, `40` | Sonnenstand |
| `skyDir` | – | URL eines Verzeichnisses mit `sky.json` + `sky.f32` (siehe `--sky`). Der Himmel geht dann durch eine GLSL-Nachbildung von Filaments `ACES_LEGACY` statt durch three.js' ACES; die Welt bleibt bei der Abbildung des Betrachters, die Dunstfarbe wird so umgerechnet, dass sie nach dieser Abbildung dem Horizont der Darstellung gleicht |
| `rider` | `1` | `0` lässt den Fahrer weg |
| `pedal`, `roll` | `0.15`, `0` | Phase 0..1 der Animationen `pedal` und `roll` |
| `ghost` | – | Laufmeter des Geists (fährt links der Mitte); ohne Angabe kein Geist |
| `attribution`, `ui` | `1`, `1` | Quellenangaben unten links, Infozeilen oben links |
| `check` | `1` | Formatprüfung |
| `shadows`, `fog`, `sky` | `1` | einzeln abschaltbar wie `RenderSettings` |
| `shadowRange`, `shadowMap` | `300`, `4096` | Schattenreichweite in Metern, Kantenlänge je Kaskade |
| `shadowRadius` | `2` | Weichheit der Schattenkanten (PCF-Radius), wie DPCF in Filament |
| `aniso` | `1` | anisotrope Filterung; 1 wie Filament/gltfio |
| `haze` | `0.3` | Dunst 0..1 wie `SkySetting.haze` |
| `lookahead` | `60` | Vorlauf in Metern, für den zusätzlich geladen wird |
| `exposure`, `near` | `0.62`, je Kamera | Belichtung, nahe Schnittebene |
| `auto` | `1` | `0`: nichts zeichnen, bis `setView` gerufen wird |
| `hdr` | `0` | `1`: Szene wie Filament in einen Float-Puffer (RGBA16F) mit 4× MSAA, Tonemapping danach — dort wird vor dem Mischen nicht auf 0..1 geklemmt |
| `clampColor` | `0` | `1`: Vertexfarben im Shader auf 0..1 klemmen (so sähe es mit `centroid`-Interpolation aus); mit `hdr=1` zum Eingrenzen heller Nahtlinien. SwiftShader zeigt den Effekt nur an einzelnen Pixeln — maßgeblich ist `indoor-dev overlaps` (Zähler „durchsichtige Eckpunkte") |

Tasten: ←/→ Strecke (mit Umschalt weiter), 1/2/3 Kamera, Q/E Sonnenazimut,
R/F Sonnenhöhe, G Fahrer; Maus dreht die Kamera um ihren Blickpunkt.
Gezeichnet wird nur auf Anforderung, nicht in einer Schleife.

## `window.__viewer`

- `ready` – Promise auf das Manifest (gelesen, Modelle geladen).
- `setView({ dist, camera, sunAzimuthDeg?, sunElevationDeg?, rider?, pedalPhase?, rollPhase?, ghostDist? })`
  → Promise. Erfüllt erst, wenn alle Kacheln der Stelle geladen (oder
  gescheitert), die Texturen im Grafikspeicher und ein Bild gezeichnet sind.
  Ergebnis: `{ ok, dist, camera, neededTiles, failedTiles, hiddenSubs, loadMs }`.
  Was fehlt, bleibt wie zuletzt eingestellt.
- `stats()` → `{ loadedTiles, tilesByLevel, failedTiles, triangles, visibleTriangles,
  renderedTriangles, drawCalls, textures, textureBytes, manifestTextureBytes,
  manifestTriangles, budget, glMemory, errors, warnings }`. `triangles` ist die
  Summe der geladenen Kacheln, `visibleTriangles` ohne ausgeblendete
  Teilflächen, `renderedTriangles`/`drawCalls` das letzte Bild samt
  Schattendurchgang; `textureBytes` ist `Breite × Höhe × 4 × 4/3` je Textur.
- `pixelStats()` → Mittelwert/Streuung der Helligkeit des letzten Bilds.
- `errors`, `warnings` – Texte der Format- und Ladefehler.
- `poseAt(d)`, `neededAt(d)`, `view`, `manifest`, `render()`, `three` (Szene,
  Kamera, Renderer zum Nachsehen).

## Was der Betrachter liest und prüft

- Manifest wie `WorldManifest.parse` (Format 1, Pflichtfelder), dazu: Kachel-ID
  passt zu Stufe/ix/iy, Anker ist die Kachelmitte, `cellM` passt zur Stufe.
- Kacheln nach `show`: geladen ist, was `neededAt(dist)` und `neededAt` im
  Vorlauf melden; alles andere wird entladen und freigegeben.
- Jede Kachel steht bei `(anchorE, anchorU, −anchorN)`; die Welt wird waagerecht
  gegen die Kamera verschoben (Ursprung = Stelle des Fahrers).
- Teilflächen-Regel: `terrain_sub_i_j` einer Stufe-L-Kachel ist ausgeblendet,
  solange die Kachel `(ix·4+i, iy·4+j)` der Stufe L−1 geladen und sichtbar ist.
- Schatten nach Knotennamen: `buildings` wirft und empfängt, `trees` wirft,
  `terrain`/`route`/`roads`/`water` empfangen, `props` und `x_…` nichts.
- Formatprüfung je GLB (**Fehler**): andere Erweiterungen als
  `KHR_materials_unlit` und `KHR_materials_specular` (Vegetation ohne Glanz); Knoten, die weder in `WorldNames` stehen noch
  `terrain_sub_i_j` oder `x_…` heißen; doppelte Knoten; Kameras, Lichter,
  Animationen, Skins in Kacheln; ab Stufe 1 nicht genau 16 Teilflächen oder ein
  Netz direkt an `terrain`; `metallicFactor ≠ 0`, `roughnessFactor < 0,6`;
  Texturen, die nicht PNG/Baseline-JPEG, keine Zweierpotenz oder größer als
  2048 sind; nicht eingebettete Puffer/Bilder; andere Attribute als
  `POSITION`/`NORMAL`/`TEXCOORD_0`/`COLOR_0`; fehlende Normalen.
  **Warnungen**: Materialnamen außerhalb von `WorldNames` ohne `x_`, Knoten mit
  eigener Transformation, Dreiecke/Texturbytes weichen vom Manifest ab.
  Für Modelle gelten dieselben Material- und Texturregeln; Animationen, Skins
  und freie Knotennamen sind dort erlaubt.

## Was Filament nachgebildet wird

Die Materialien der Kacheln bleiben, wie der `GLTFLoader` sie liefert
(Metallic-Roughness, `alphaMode`, `doubleSided`, Sampler). Die Darstellung
stellt daran nur ein, was Filament als Ganzes auch tut: Dithering. **Keine**
anisotrope Filterung (gltfio 1.75.1 setzt sie nicht; `--param aniso=16` zum
Vergleich).

| Filament (7.6) | hier |
|---|---|
| Sonne als Richtungslicht, drei Kaskaden à 2048 px bis rund 300 m, weiche Kanten (DPCF) | `SunLight` (three-Addon) mit zwei Kaskaden am Sichtkegel, 4096 px je Kaskade, PCF mit Radius 2 (`shadowRadius`) |
| `IndirectLight` aus Kugelflächenfunktionen | `HemisphereLight`: bläulich von oben, warm-grün von unten |
| Himmel als erzeugte Cubemap, Verlauf Horizont → Zenit, Sonnenscheibe | Himmelskugel mit demselben Verlauf (eigener Shader, nur für den Himmel) |
| Dunst über `fogOptions`, Horizontfarbe = Dunstfarbe | exponentieller Dunst mit Höhenabnahme, linear vor dem Tonemapping gemischt; Sichtweite aus `manifest.quality` (6/12/20 km), Farbe = Horizontfarbe |
| ACES, sRGB, 4× MSAA (FXAA nur ohne MSAA) | ACES-Tonemapping, sRGB-Ausgabe, MSAA der Zeichenfläche |
| Kameras `CHASE`, `HANDLEBAR`, `DRONE` | wie `CameraRig` (ohne Dämpfung): 6 m auf dem Band zurück, 2,5 m über dem höheren von Fahrer und Kamerapunkt, Blick 6 m voraus; Auge 1,45 m, Blick 25 m voraus; Drohne abweichend 85 m hoch, 125 m zurück, 55 m seitlich |

**Maßstab für das Aussehen** ist seit dem 24.11.2026 der Filament-Referenzrenderer
(`filashots.mjs`, siehe `../README.md`): echtes Filament mit Licht, Farbweg,
Himmels- und Wassermaterial und Cockpit der App. Dieser Betrachter übernimmt
davon nur die Lenkerkamera (`camerarig.mjs`, samt Verschiebung der Bildmitte).

## Wo er abweicht

- **Kein Umgebungs-Spiegelanteil, aber auch kein Filament-BRDF.** three.js und
  Filament rechnen dasselbe Modell mit leicht anderen Näherungen; Helligkeit
  und Sättigung stimmen im Charakter, nicht aufs Prozent. Belichtung,
  Lichtstärken und Himmelsfarben sind hier nach Augenmaß gewählt und müssen in
  Filament gegen diese Bilder abgeglichen werden, nicht umgekehrt abgeschrieben.
- **Sonnenstand** kommt als Azimut/Höhe, nicht aus `SkySetting` (Uhrzeit, Tag,
  Breite). Unter 8° Höhe bleibt die Sonne auf 8°.
- **Dunst:** Der Nebel-Baustein von three.js ist ersetzt (für alle Materialien
  gleich). Filaments `fogOptions` haben mehr Stellschrauben
  (`inScatteringStart`, `maximumOpacity` …) — die Formel hier ist die einfache:
  Dichte auf Kamerahöhe, Skalenhöhe 1400 m.
- **Schatten:** zwei Kaskaden statt bis zu vier, der Filter rauscht an den
  Rändern leicht. Bäume empfangen laut Tabelle 7.3 keine Schatten — ein Baum im
  Hausschatten bleibt hell; das ist Vertrag, nicht Fehler des Betrachters.
- **Tiefenpuffer:** gewöhnlich (24 Bit, nah 0,3–5 m, fern = 1,6 × Sichtweite),
  kein umgekehrtes Z. Liegt die Fahrbahn nur Zentimeter über dem Gelände,
  flimmert sie hier in der Ferne — auf dem Gerät dann voraussichtlich auch.
- **Bewegung:** Es gibt keine. Keine gedämpfte Kamera, kein Fortschreiben,
  kein asynchrones Nachladen mit Hysterese — `setView` lädt genau die Menge für
  die Stelle (plus Vorlauf) und entlädt den Rest sofort.
- **Am Lenker** ist der Fahrer ausgeblendet (die Kamera säße in seinem Kopf).
- **Software-Rendering** (SwiftShader): ein Bild 1600×900 braucht 2–5 s.
  Zeiten und Draw Calls sagen nichts über das Gerät.
- `RenderSettings` (AO, Bloom, dynamische Auflösung) sind nicht nachgebildet.

## Mock-Welt

`mockworld.mjs` schreibt ohne Netz eine erfundene Runde von 3 km: 44 Kacheln
der Stufe 0 (Korridor 330 m), je 36 der Stufen 1 und 2 (bis ±12 km), Gelände
mit Schürzen und `terrain_sub_i_j`, Fahrbahn aus Asphalt und Schotter mit
Alpha-Rändern, Häuser, Bäume, Teich, Leitpfosten, dazu Testmodelle
`models/rider.glb`/`ghost.glb` aus Quadern mit `pedal`/`roll`
(`--models ../out/mock/models`). Sie dient nur dazu, den Betrachter zu prüfen —
der Maßstab für das Format ist der Kotlin-Weltbau.
