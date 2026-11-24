# Indoor — Datenstufe „Laser" (DGM1, nDOM, LoD2)

Stand: 24.11.2026, Branch `claude/indoor-lidar`. Gehört zum Slice indoor-world
(`docs/indoor/world.md`); Vertrag: `docs/indoor.md` 7 und 8.

Die Welt soll der echten Strecke ähnlicher werden. Bisher: Gelände aus
Terrarium-Höhen (~30 m, in Deutschland SRTM/EU-DEM — ein **Oberflächen**modell,
im Wald 10–20 m zu hoch), Gebäude als OSM-Grundrisse mit geschätzter Höhe und
Standarddach, Bäume aus Landnutzung und Luftbildfarbe gewürfelt. Neu ist eine
optionale Datenstufe „Laser" aus den amtlichen Laserscan-Produkten der Länder,
zuerst für Nordrhein-Westfalen, seit 24.11.2026 auch für Baden-Württemberg
(Abschnitt 7):

| Was | Quelle | Wirkung in der Welt |
|---|---|---|
| Gelände | DGM1 (1-m-Raster, Boden ohne Bewuchs und Gebäude) | echte Böschungen, Einschnitte, Dämme, Gräben; das Streckenband liegt auf dem DGM1 |
| Bewuchs | nDOM = DOM1 − DGM1 (Höhe über Grund) | Einzelbäume mit echter Lage, Höhe und Kronenbreite; Waldrand und Kronendach gemessen |
| Gebäude | 3D-Gebäudemodell LoD2 (CityGML) | echte Grundrisse, Trauf- und Firsthöhen, Dachformen (Dom, Satteldächer, Walmdächer, Flachdächer) |
| Gebäude ohne LoD2 | nDOM über dem OSM-Grundriss | gemessene Höhe statt geschätzter (Zwischenstufe) |

Vorerst nur im Entwicklerwerkzeug (`indoor-dev build/plan/catalog-build
--laser`). Der Bau auf dem Handy bleibt beim bisherigen Datensatz und ist
unverändert (siehe „Was sich ohne `--laser` ändert").

## 1 Quellen in NRW (geprüft am 24.11.2026)

Alle Angaben aus echten Abrufen aus dieser Entwicklungsumgebung. **Kein Host
war von der Netzwerkrichtlinie blockiert.** Einzige Auffälligkeit:
`https://opengeodata.nrw.de` (ohne `www.`) scheitert an der TLS-Prüfung (das
Zertifikat nennt nur `www.opengeodata.nrw.de`) — das ist kein Proxy-Verbot,
der Weltbau nimmt den `www.`-Host.

| Produkt | Dienst / Download | Format | Raster / Kachel | Größe |
|---|---|---|---|---|
| DGM1 | WCS 2.0.1 `https://www.wcs.nrw.de/geobasis/wcs_nw_dgm`, Coverage `nw_dgm` | GeoTIFF Float32, **LZW** | 1 m, EPSG:25832, beliebiges Rechteck (`SUBSET=x(…)&SUBSET=y(…)`) | 256 m × 256 m ≈ 145 kB |
| DOM1 | WCS `https://www.wcs.nrw.de/geobasis/wcs_nw_dom`, Coverage `nw_dom` | GeoTIFF Float32, unkomprimiert | 1 m | 256² ≈ 263 kB |
| nDOM (bildbasiert) | WCS `https://www.wcs.nrw.de/geobasis/wcs_nw_ndom`, Coverage `nw_ndom` | GeoTIFF Float32 | 0,5 m (mit `SCALESIZE` auf 1 m) | 512² ≈ 1 MB |
| DGM1 (Kacheln) | `https://www.opengeodata.nrw.de/produkte/geobasis/hm/dgm1_tiff/dgm1_tiff/` | GeoTIFF, Name mit Erfassungsjahr (`dgm1_32_<E>_<N>_1_nw_<Jahr>.tif`) | 1 km² | Ø 2,2 MB, 35 861 Kacheln, 78,8 GB |
| DOM1 (Kacheln) | `…/hm/dom1_tiff/dom1_tiff/` | GeoTIFF | 1 km² | Ø 2,4 MB, 85,1 GB |
| nDOM50 (Kacheln) | `…/hm/ndom50_tiff/ndom50_tiff/` | TIFF, 0,5 m | 1 km² | Ø 16 MB, 575 GB |
| LoD2 | `https://www.opengeodata.nrw.de/produkte/geobasis/3dg/lod2_gml/lod2_gml/LoD2_32_<km O>_<km N>_1_NW.gml` | CityGML 1.0 | 1 km², Name **ohne** Jahr | Ø 6,3 MB (Dorf 0,1–1 MB, Stadt 15–30 MB), 219,6 GB |
| LoD2 (Szene) | I3S `https://www.gis.nrw.de/geobasis/LOD2/SceneServer`, SLPK unter `…/3dg/lod2_slpk/` | Esri I3S | — | nicht genutzt |
| 3D-Messdaten, bDOM | `…/hm/3dm_l_las/`, `…/hm/bdom50_las/` | LAS/LAZ-Punktwolken | 1 km² | nicht genutzt |

Gewählt: **WCS für DGM1 und DOM1** (nur der Korridor, keine Jahreszahl im
Namen nötig, kein 3,5-MB-Verzeichnis), **LoD2 als 1-km-Kachel** (einen WFS für
LoD2 gibt es nicht; der I3S-SceneServer wäre ein eigenes Binärformat).
Verhalten der WCS: Ursprung auf ganzen Metern + 0,5 (Pixelmitte), Fehlwert
`-9999` (GDAL_NODATA) außerhalb NRWs, Status 400 außerhalb des
Umgebungsrechtecks; fehlende LoD2-Kachel → 404.

Das nDOM rechnet der Weltbau selbst aus DOM1 − DGM1 (beides Laserscan,
dieselbe Befliegung, eine Rasterung). Das amtliche nDOM (`nw_ndom`) ist aus
dem bildbasierten DOM abgeleitet, doppelt so fein und viermal so groß; es ist
in der Korrelation mit DOM1 − DGM1 übereinstimmend (r = 0,93 an der Probe am
Kahlen Asten), bringt für Baumhöhen aber nichts dazu.

### Lizenz und Quellenvermerk

Laut `GetCapabilities` (Feld `Fees`) und Geobasis NRW: **Datenlizenz
Deutschland – Zero – Version 2.0** (`dl-de/zero-2-0`,
https://www.govdata.de/dl-de/zero-2-0): „Jede Nutzung ist ohne
Einschränkungen oder Bedingungen zulässig." Ein Quellenvermerk wird **nicht
verlangt**. Wir nennen die Quelle trotzdem, wie beim Luftbild:

- Weltmanifest, `attribution` (Art `ELEVATION`, weil `GeoSourceKind` Vertrag
  ist und keine eigene Art kennt):
  „Gelände, Bewuchs und Gebäude: © Geobasis NRW (DGM1, DOM1,
  3D-Gebäudemodell LoD2)", Lizenz `dl-de/zero-2-0`.
- Diese Datei und `tool/indoor-dev/README.md`.
- `PRIVACY.md` bleibt unverändert: Die App ruft diese Hosts nicht ab. Wer die
  Stufe in der App einschaltet, trägt `www.wcs.nrw.de` und
  `www.opengeodata.nrw.de` dort ein (und in die Positivliste, siehe 3).

## 2 Andere Länder (nur Übersicht, nicht eingebaut)

Seit der EU-Durchführungsverordnung zu hochwertigen Datensätzen (HVD, gilt ab
Juni 2024) geben alle Länder Geländemodelle und 3D-Gebäude offen ab. Die
Portale antworteten aus dieser Umgebung alle (Statusabfrage am 24.11.2026);
Formate und Lizenzen sind **nach Kenntnisstand** angegeben und vor einem
Einbau mit echten Abrufen zu prüfen (wie in `tool/indoor-dev/catalog/LUFTBILD.md`).

| Land | Portal | DGM1 | DOM / nDOM | LoD2 | Lizenz (Stand Kenntnis) |
|---|---|---|---|---|---|
| Nordrhein-Westfalen | opengeodata.nrw.de, wcs.nrw.de | ja, WCS + Kacheln | DOM1, nDOM50, bDOM | ja | dl-de/zero-2-0 — **eingebaut** |
| Niedersachsen | opengeodata.lgln.niedersachsen.de | ja | DOM1 | ja | CC BY 4.0 |
| Hessen | gds.hessen.de | ja | DOM1 | ja | dl-de/zero-2-0 (HVGG) |
| Rheinland-Pfalz | geobasis-rlp.de | ja | DOM | ja | dl-de/by-2-0 |
| Baden-Württemberg | opengeodata.lgl-bw.de | ja, ZIP-Kacheln (XYZ) | DOM1, nDOM1 | ja | dl-de/by-2-0 — **eingebaut** (Abschnitt 7) |
| Bayern | geodaten.bayern.de/opengeodata | ja | DOM (bildbasiert) | ja | CC BY 4.0 |
| Thüringen | geoportal-th.de (Offene Geodaten) | ja | DOM1, Laserpunkte | ja | dl-de/by-2-0 |
| Sachsen | geocloud.landesvermessung.sachsen.de | ja | DOM1 | ja | dl-de/by-2-0 |
| Sachsen-Anhalt | geodatenportal.sachsen-anhalt.de | ja | DOM | ja | dl-de/by-2-0 |
| Brandenburg / Berlin | data.geobasis-bb.de, gdi.berlin.de | ja | bDOM | ja | dl-de/by-2-0 (Berlin LoD2 dl-de/zero) |
| Schleswig-Holstein, Hamburg, Bremen, MV, Saarland | Landesportale | ja | teils | ja | meist dl-de/by-2-0 |

Für den Einbau eines weiteren Landes reicht ein Eintrag in `laserSources`
(WCS-Adressen oder Kachelschema, UTM-Zone, Lizenz, Wortlaut), solange es
WCS mit Float-GeoTIFF gibt; Länder nur mit Kachel-Downloads brauchen einen
Kachel-Abruf statt `coverageUrl` (Schnittstelle ist vorbereitet: `fillBlock`
fragt nur ein Rechteck an).

## 3 Aufbau (`:core`, Paket `indoor/world`)

Reines Kotlin, ohne Android-Importe, ohne `ImageIO`.

| Datei | Inhalt |
|---|---|
| `LaserSources.kt` | `LaserSource`, `LaserTiles` (Kachel-Download), Register `laserSources` (NRW, BW), eigene Positivliste `isAllowedLaserUrl`, Abruf-Schnittstelle `LaserFetcher` mit Hülle `createLaserPipeline` (Positivliste, Plattencache 365 Tage, 2 Anfragen je Host, 429/503-Wiederholung), Einstieg `createLaserWorldBuilder(LaserOptions)`, Kennzahlen `laserBuildStats` |
| `LaserUtm.kt` | ETRS89/UTM nach Krüger (Reihe bis n⁴, < 1 mm) und ein linearer ENU↔UTM-Rahmen je 256-m-Block (< 1 cm) |
| `LaserZipTiles.kt` | Kachel-Download: ZIP-Einträge, XYZ-Leser, Füllen der Blöcke je Archiv und Kilometer, Voranschlag in ganzen Archiven |
| `LaserTiff.kt` | GeoTIFF-Leser: Streifen und Kacheln, keine/LZW/Deflate-Kompression, Prädiktor 1/2/3, Float32/Int16/Int32, ModelTiepoint/PixelScale, GDAL_NODATA |
| `LaserData.kt` | `LaserOptions`, `loadLaserData`: Blöcke im Korridor, Überblendung, nDOM, Einzelbaum-Erkennung, Kronenbedeckung/-höhe, Gebäudemaske |
| `LaserLod2.kt` | CityGML-Leser (Text-Scanner, kein DOM), `LaserBuilding` |
| `LaserTerrain.kt` | `LaserTerrainMesh` (RTIN), `LaserTerrainLayer` |
| `LaserParts.kt` | `LaserWorldParts` (Hülle um `FullWorldParts`), `LaserBuildingsLayer`, `Lod2Mesher`, `laserTreeSpec` |

**Eigene Positivliste statt `isAllowedGeoUrl`:** `WorldContract.kt` ist
eingefroren; `GeoSourceKind` kennt nur Höhen, Vektor, Luftbild. Die Laser-Stufe
hat deshalb eine eigene, ebenso strenge Liste (`https`, Host **und** Pfad,
WCS nur mit `SERVICE=WCS&…`, LoD2 nur nach dem Kachelmuster mit Ziffern, kein
Abfrageteil) und einen eigenen Abruf. Die Vertragsdateien sind **nicht
geändert**.

**Schnittstelle für den späteren Handy-Bau:** `createLaserWorldBuilder(LaserOptions(fetcher, …))`
liefert einen gewöhnlichen `WorldBuilder`. Die App bräuchte nur einen
`LaserFetcher` (OkHttp, ohne Weiterleitungen, wie der vorhandene Geo-Abruf),
`createLaserPipeline` darum und einen Schalter in der Oberfläche. Der
Voranschlag (`plan`) rechnet die Laser-Hosts und -Datenmenge schon mit ein.

### 3.1 Korridor und Überblendung

- Ein **Block** = eine Kachel der Stufe 0 (256 m, am ENU-Ursprung
  ausgerichtet), 257² Proben auf ganzen Metern; das UTM-Raster des Dienstes
  wird einmal bilinear ins ENU-Gitter umgetastet. Geladen werden Blöcke bis
  `radiusM` (Vorgabe 200 m) neben der Route — je Block zwei WCS-Anfragen.
- Im Block gilt das DGM1, zum Korridorrand hin über `fadeM` (60 m) weich in
  **Terrarium + Versatz** übergeblendet. Der Versatz ist der Median von
  DGM1 − Terrarium im Korridor (Altenberg −10,5 m, Kahler Asten −8,0 m,
  Balkantrasse −3,1 m — Terrarium ist in Deutschland ein Oberflächenmodell).
  Dieselbe Höhe gilt für alle Stufen; Stufe 1/2 tasten sie gröber ab.
- Das **Streckenband** wird auf das DGM1 eingepasst (sonst wie bisher:
  σ = 12 m geglättet, Brücken überbrückt, Steigung ≤ 24,5 %). Der Widerstand
  kommt weiter aus dem Höhenprofil der `IndoorRoute` (unverändert); das
  DGM1 legt nur die gezeichnete Fahrbahn richtig ins Gelände. Brücken: Das
  DGM1 hat keine Brückendecks — die Strecke auf einer Brücke wird wie bisher
  zwischen den Widerlagern überbrückt; unter Brücken wird nichts eingeebnet,
  Unterführungen sind im DGM1 frei (nichts wird zugeschüttet).
- `CarvedGround` bekommt das DGM1 mit 1 m statt 8 m Dämpfung; Fahrbahn,
  Bankett und Straßen werden wie bisher eingeebnet.

### 3.2 Adaptives Geländenetz (Stufe 0)

`LaserTerrainMesh`: RTIN nach Mapbox „Martini" über dem 257²-Gitter der
Kachel. Die Fehlerschwelle wird je Kachel per Bisektion so gewählt, dass Netz
+ Schürze **ins bisherige Geländebudget** passen (MEDIUM 9 000 Dreiecke) — die
Welt hat nie mehr Geländedreiecke als ohne Laser, nur dort, wo das Gelände
Kanten hat (Böschungen, Dämme, Gräben). Ebene Flächen werden große Dreiecke.

- Rissfrei innerhalb der Kachel (Test prüft jede innere Kante zweimal in
  Gegenrichtung); zwischen Kacheln decken die Schürzen (5 m) wie bisher.
- Fahrbahn: Unter der Strecke zählt eine Abweichung nach oben vierfach, und
  nach dem Vernetzen wird jede Probe unter der Fahrbahn gegen das Netz
  geprüft; liegt das Netz mehr als 8 cm über dem Boden (der 25 cm unter der
  Fahrbahn liegt), wird dort zwangsweise geteilt.
- Wege- und Randschichten (`RoadsLayer`, `RouteLayer`) legen sich über
  `TileContext.meshHeightAt` auf das gezeichnete Netz (vorher fest das
  4-m-Gitter, `terrainMeshHeightAt`).

### 3.3 Bäume aus dem nDOM

- Einzelbäume = lokale Maxima des 3×3-geglätteten Kronenmodells mit
  höhenabhängigem Fenster (2 m bei 3 m Höhe bis 4 m ab 25 m; Popescu & Wynne
  2004). Ebene Flächen (Ausgleichsebene < 12 cm Rest) und Plateaus (≥ 40 %
  der Proben in 7 × 7 m nahe der Spitze: Flachdach, Hecke) zählen nicht.
  Gebäude werden vorher ausgeblendet (OSM- und LoD2-Grundrisse + 1 m).
- Kronenradius aus dem Abfall auf 60 % der Spitze in 8 Richtungen; Art aus
  Höhe und Form (schmale Krone → Fichte im Nadelwald, sonst Pappelform; unter
  7 m kleiner Baum); daraus Breitenfaktor der Modelle (`laserTreeSpec`).
  Kronen, die tief ansetzen, werden nicht über die Fahrbahn gezogen.
- **Kahlschläge:** Das DOM1 ist oft älter als das Luftbild (im Sauerland
  Borkenkäfer-Kahlschläge 2019–2023). Wo das Luftbild keine Krone mehr zeigt
  (`canopyFromAerial` < 0,2), entfallen gemessene Bäume, und die
  Kronenbedeckung sinkt anteilig.
- Wald außerhalb des Bands (Waldkörper) nimmt Bedeckung und mittlere
  Kronenhöhe aus dem nDOM.
- **Schnittstelle zur Baum-Session** (TreeModels, TreesLayer): `LandMap`
  bekommt zwei Funktionen mit Vorgabe (`canopyHeightAt`, `laserTrees`),
  `TreesLayer` ruft sie an zwei Stellen (16 Zeilen: ein Block vor der
  Würfelschleife, eine Zeile in `canopyHeight`). Die Modelle selbst sind
  unberührt.

### 3.4 Gebäude aus LoD2

- Stufe 0: je LoD2-Gebäude Dach- und Wandflächen trianguliert (Ebene der
  Fläche, `Triangulator`), Bodenflächen entfallen; Wände reichen 1,5 m unter
  den Fußpunkt (nie schwebend). Kollineare Zwischenpunkte (LoD2 setzt an jedem
  Kachelrastermeter einen) fallen weg. Innenwände aneinandergebauter Häuser
  (gleiche Fläche, Gegennormale) entfallen beide — sie lägen koplanar.
- Texturen aus dem vorhandenen Atlas (`BuildingTextures`): Wände in
  Geschossstreifen bis zur Traufe mit Fensterband (Putz, Schiefer, Klinker,
  Fachwerk je Gebäude fest; Kleinbauten ohne Fenster), Sockel, Giebel
  schlicht; Dächer in Streifen der Pfannenhöhe in Fallrichtung, Flachdächer
  Bitumen. Dachfarbe aus dem Luftbild über dem Grundriss (Helligkeit begrenzt).
- Nach Abstand zur Strecke bis zum Gebäudebudget der Kachel; was keinen Platz
  hat oder kein LoD2 hat, baut `BuildingsLayer` aus OSM mit der gemessenen
  Höhe (LoD2 `measuredHeight`, sonst 90. Perzentil des nDOM im Grundriss).
  Stufe 1: OSM-Kubaturen mit diesen Höhen.
- LoD2 wird nur für 1-km-Kacheln geladen, die ein 120-m-Streifen um die
  Strecke berührt (`lod2RadiusM`).

## 4 Messungen

Werkzeug mit `JAVA_OPTS=-Xmx2g`, MEDIUM, Luftbild NRW, Cache warm für die
bisherigen Quellen. „Download Laser" = tatsächlich übertragene Bytes beim
ersten Lauf (WCS-Raster + LoD2). Heap = größter Wohnsatz des Prozesses (RSS).

| Strecke | km | Bauzeit ohne → mit (s) | Download bisher (geschätzt) | Download Laser | davon LoD2 | Dreiecke gleichzeitig ohne → mit | Texturen | MB/km fertige Welt ohne → mit | RSS ohne → mit |
|---|---|---|---|---|---|---|---|---|---|
| Altenberg kurz | 3,0 | 13,0 → 16,1 | 9,3 MB | 26,9 MB (8,9 MB/km) | 13,0 MB (4 Kacheln, 119 Geb.) | 462 930 → 424 928 | 153 → 159 MB | 15,7 → 15,3 | 1,39 → 1,34 GB |
| Kahler Asten | 13,2 | 19,7 → 31,2 | 20,3 MB | 51,5 MB (3,9 MB/km) | 8,1 MB (12 Kacheln, 301 Geb.) | 517 544 → 515 545 | 141 → 143 MB | 10,1 → 9,9 | 1,29 → 1,26 GB |
| Balkantrasse | 7,7 | 21,5 → 31,3 | 19,1 MB | 239 MB (31 MB/km) | 198,8 MB (13 Kacheln, 6 764 Geb.) | 477 520 → 457 644 | 155 → 155 MB | 17,9 → 17,1 | 1,34 → 1,61 GB |
| Balkantrasse, LoD2-Korridor (4.1) | 7,7 | 21,5 → 30,2 | 19,1 MB | 41,2 MB (5,3 MB/km) | 0,6 MB (Korridordatei, 2 738 Geb.) | 477 520 → 449 176 | 155 → 155 MB | 17,9 → 16,7 | 1,34 → 1,37 GB |

Bauzeiten mit warmem Cache (auch für die Laserdaten). Beim ersten Lauf
kommen für das Laden der Laserdaten 17 s (Altenberg), 44 s (Kahler Asten)
und 45 s (Balkantrasse) dazu (WCS ≈ 1,5 s je Anfrage, 4 parallel). Der
belegte Heap am Ende des Baus lag bei 330–615 MB (Grenze 2 GB); die Blöcke
brauchen je 330 kB (Kahler Asten: 96 Blöcke = 32 MB), der größte Posten sind
die geparsten LoD2-Gebäude in der Stadt.

Laserdaten je Kilometer: Raster 4,6 MB (Altenberg), 3,3 MB (Kahler Asten),
5,3 MB (Balkantrasse) — rund 0,45 MB je Block, 10 Blöcke je km bei 200 m
Radius. LoD2 schwankt mit der Bebauung von 0,6 MB/km (Wald) bis 26 MB/km
(Wermelskirchen); das ist der größte Posten.

Ohne Laser (`--laser` weggelassen) sind Dreiecke, Texturbudget und Platte
**identisch** zum bisherigen Stand (Altenberg 462 930 Dreiecke, 47,2 MB, beide
Läufe). Die Kachel-Bytes schwanken schon zwischen zwei Läufen des alten
Stands (Balkantrasse: 25 von 118 Kacheln), deshalb kein Byte-Vergleich.

Koplanare Flächen (`indoor-dev overlaps`, deckende Paare ohne weich
auslaufende Ränder, wie in `QualityTest`): Altenberg 40 → 21, Kahler Asten
49 → 29; weich auslaufende Ränder (verge/water) steigen leicht (638 → 672,
1556 → 1694), weil das adaptive Netz große ebene Dreiecke unter die Ränder
legt. Im Test ohne Netz (`LaserWorldTest`) 7 statt 10.

### 4.1 LoD2 auf den Korridor zugeschnitten

LoD2 kommt nur in ganzen Quadratkilometern; in der Stadt ist das der größte
Posten. `LaserLod2Corridor.kt` hält nur die Gebäude, von denen ein Punkt
näher als `lod2RadiusM` (120 m) an der Strecke liegt, als kompaktes Format
`TSL2` (gzip; Zentimeter-Differenzen als Varints, kollineare Zwischenpunkte
und Abschlussflächen weg). Das Werkzeug schneidet es (`indoor-dev lod2-cut
--gpx … --out ….tsl2`), der Weltbau liest es statt der Kacheln (`build
--laser --lod2-corridor ….tsl2`, `LaserOptions.lod2Corridor`). Gedacht als
Vorarbeit für einen Handy-Abruf: Ein Server (oder ein Release-Asset je
Katalogstrecke) liefert die Datei, die App lädt nur sie.

| Strecke | Kacheln | Gebäude Kacheln → Korridor | Bytes Kacheln → Korridor |
|---|---|---|---|
| Altenberg kurz | 4 | 671 → 44 | 12,4 MB → 21 kB |
| Kahler Asten | 13 | 561 → 99 | 10,4 MB → 24 kB |
| Balkantrasse | 13 | 10 741 → 2 738 | 189,6 MB → 0,6 MB (0,08 MB/km) |

Ein Bau mit Korridordatei liefert in der Nähe dieselben Gebäude (Test
`lod2KorridorRundreise`: Punkte auf 1 cm, Boden-/Firsthöhe gleich);
außerhalb von 120 m nimmt er OSM-Gebäude mit der Höhe aus dem nDOM.

### 4.2 Katalog

`catalog-src.json` hat je Eintrag `"laser": true|false` (Vorgabe `false`);
`catalog-build` baut solche Einträge mit der Laser-Stufe, auch im Workflow
`indoor-worlds.yml` ohne neue Eingabe. Gesetzt für `kahler-asten` und die
neuen NRW-Strecken `balkantrasse` (7,7 km) und `altenberg` (12,7 km, 175 Hm,
Runde ab dem Dom). Probelauf `catalog-build --only kahler-asten,balkantrasse,altenberg`:
alle geprüft und gepackt (84,2 / 78,8 / 98,6 MB), 162 s, RSS 2,8 GB bei
`-Xmx6g`. Versionen nicht angehoben; der beigelegte App-Katalog
(`app/src/main/assets/indoor/catalog.json`) ist unverändert, bis Pakete im
Release liegen.

## 5 Bilder (Betrachter, nicht Gerät)

Vorher/Nachher an denselben Laufmetern, Fahrerperspektive `CHASE` bzw.
Drohne, ohne Fahrer. Unter `docs/indoor/bilder/lidar/`:

| Paar | Strecke, Laufmeter, Kamera | Was sich ändert |
|---|---|---|
| `ortsdurchfahrt-dom` | Altenberg kurz, 300 m, Drohne | Altenberger Dom mit Langhaus und steilem Dach aus LoD2 statt Flachkasten; Schieferdächer der Klostergebäude |
| `ortsdurchfahrt-wermelskirchen` | Balkantrasse, 200 m, Fahrer | echte Geschosszahl, Fenster in Geschossen, gestuftes Gelände im Park |
| `einschnitt-balkantrasse` | Balkantrasse, 2 650 m, Fahrer | Trasse im Einschnitt, Bäume auf der Böschungskante (vorher eben) |
| `damm-balkantrasse` | Balkantrasse, 5 750 m, Fahrer | Trasse auf dem Damm, Gelände fällt zu beiden Seiten ab |
| `hang-boeschung-kahler-asten` | Kahler Asten, 5 150 m, Fahrer | Waldweg in der Hangflanke: Böschung bergseits, Abfall talseits |
| `kahlschlag-waldrand-kahler-asten` | Kahler Asten, 4 100 m, Drohne | Kahlschlag bleibt frei, Waldrand und Bestandshöhen gemessen |
| `allee-altenberg` | Altenberg kurz, 2 100 m, Fahrer | Alleebäume an ihren echten Standorten und in echter Höhe |
| `trasse-baumreihen` | Balkantrasse, 4 200 m, Drohne | geschlossenes Gehölzband auf der Trasse wie im Luftbild, dunkle Dächer |

**Unabhängige Bewertung** (Unter-Agent, Luftbild und DGM-Schummerung von
Geobasis NRW als Referenz, ohne Kenntnis der Umsetzung): an 7 von 8 Stellen
ist „nachher" der echten Stelle ähnlicher, bei der Allee etwa gleich.
Am deutlichsten: Geländeform (Dämme, Einschnitte, Hangwege) und markante
Gebäude. Neu gefundene Fehler stehen unten in 6.

## 6 Auf dem Gerät / offen

- [ ] Nur im Betrachter gesehen (three.js); Filament auf dem Gerät nicht.
- [ ] Handy-Bau mit Laser: Schnittstelle steht, App-Abruf, Schalter,
      PRIVACY.md und Positivliste in der App fehlen (bewusst nicht gebaut).
- [ ] LoD2 in Städten groß (bis 30 MB je km²): Zuschnitt auf den Korridor
      gibt es (4.1, 0,08 MB/km statt 25 MB/km); es fehlt der Server bzw.
      das Release-Asset, das die Datei für den Handy-Abruf ausliefert.
- [ ] DOM1-Alter: Kahlschläge erkennt nur das Luftbild; neu gepflanzte oder
      gewachsene Bäume nach der Befliegung fehlen.
- [ ] Fensterbänder auf Kirchen und Hallen (Dom) passen nicht zur Bauart;
      LoD2 kennt keine Öffnungen.
- [ ] RTIN-Fehler ist eine Heuristik (an den Hypotenusen-Mittelpunkten
      gemessen): im Test höchstens das 1,6-Fache der Schwelle.
- [x] Aus der Bewertung behoben (24.11.2026, zweiter Durchgang):
      Lichtraum über der Fahrbahn (Kronen, die unter 6 m über der Fahrbahn
      ansetzen, ragen nicht in Fahrbahn + 0,5 m; höhere höchstens 1 m —
      Einschnitt so hell wie vorher), steile LoD2-Dachflächen bleiben Dach,
      Dachhaut dunkler, Wände gebrochen getönt, Wellblech nur in Gewerbe und
      Landwirtschaft (Dom/Kloster Putz), Innenwand-Regel nur zwischen
      verschiedenen Gebäuden, Gelände unter LoD2-Grundrissen unter dem
      Gebäudeboden (gerade Fußlinie), Holzpolter nur auf ebenem Grund (alle
      Welten), Brücken anderer Straßen geradlinig zwischen ihren Enden (alle
      Welten), nDOM-Gebäudehöhe nur außerhalb von LoD2 und als Median.
- [ ] Offen: Das graue Band links oben im Dom-Bild ist geblieben. Weder
      Brücke noch Gebäude ohne Wände noch steile Wegfläche; Vermutung: eine
      Schürze an der Naht Stufe 0 / Stufe 1, auf die das Luftbild einer
      querenden Straße gestreckt ist (die feinere Stufe 0 macht die Sprünge
      an der Naht größer). Die lose Platte an der Trasse ist vermutlich ein
      echtes Flachdach aus LoD2. Baumart bleibt eine Schätzung (Laub statt
      Fichte im Hochwald am Kahlen Asten).
- [ ] Übergang Korridor → außen: Terrarium bleibt ein Oberflächenmodell; der
      Median-Versatz gleicht die Höhe aus, nicht die Form (Waldkuppen außen).

## 7 Baden-Württemberg (24.11.2026)

Die Stufe ist jetzt regional: Eine Laser-Quelle hat entweder einen WCS (NRW)
oder ein Kachelschema `LaserTiles` (BW). Welche gilt, entscheidet wie bisher
die Lage der Strecke (`LaserSource.covers`, mindestens die Hälfte der
Punkte); die Rechtecke von NRW (ab 50,30° N) und BW (bis 49,80° N)
überlappen nicht. Ein weiteres Land mit Kachel-Download braucht nur einen
Eintrag in `laserSources`.

### 7.1 Quellen (geprüft mit echten Abrufen)

Alle Hosts waren aus dieser Umgebung erreichbar. Das LGL bietet für Höhen
**keinen WCS** an (auf lgl-bw.de/Produkte/Open-Data nur WCS für Sentinel-2
und DOP20 INSPIRE; DGM als WMS nur als Bild). Abgerufen wird deshalb das
Open GeoData Portal; die Produktliste steht in
`https://opengeodata.lgl-bw.de/assets/config/local/odp-products.json`.

| Produkt | Download | Inhalt des ZIP | Größe je ZIP |
|---|---|---|---|
| DGM1 | `https://opengeodata.lgl-bw.de/data/dgm/dgm1_32_<E>_<N>_2_bw.zip` | je km `dgm1_32_<e>_<n>_1_bw_<Jahr>.xyz` (Text `Rechtswert Hochwert Höhe`, Pixelmitten auf ,5 m, 1000 × 1000, 29 MB entpackt) + CSV-Metadaten + Lizenz-PDF | 13–14 MB |
| DOM1 | `…/data/dom1/dom1_32_<E>_<N>_2_bw.zip` | je km `dom1_32_<e>_<n>_1_bw_<Jahr>.tif` (GeoTIFF Float32, LZW, Prädiktor 3, 512er-Kacheln, GDAL_NODATA) + TFW | 13 MB |
| LoD2 | `…/data/lod2/LoD2_32_<E>_<N>_2_bw.zip` | je km `LoD2_32_<e>_<n>_1_BW.gml` (CityGML 1.0, AdV-Profil wie NRW; der Leser braucht keine Änderung) | 1–12 MB (Stuttgart-Rand 11 MB, Dorf/Wald 0,2–3 MB) |
| nDOM1 | `…/data/ndom1/ndom1_32_<E>_<N>_2_bw.zip` | GeoTIFF, **bildbasiert** (bDOM20 aus Luftbildern 2025 − DGM1) | 10 MB — nicht genutzt |
| DOP20 | WMS `https://owsproxy.lgl-bw.de/owsproxy/ows/WMS_LGL-BW_ATKIS_DOP_20_C`, Ebene `IMAGES_DOP_20_RGB` (Download-ZIP 250 MB je 2 km) | — | schon im Werkzeug-Register (`CatalogAerial.kt`) |

- Koordinaten: ETRS89/UTM 32 (EPSG:25832), Höhen NHN (DHHN2016) — wie NRW.
- Kachelschema: ZIP je 2 km × 2 km, Südwestecke bei ungeradem Rechtswert-km
  und geradem Hochwert-km (geprüft: `501_5404`, `503_5404`, `501_5406` → 200;
  `500_5404`, `501_5405` → 404). Im ZIP je Kilometer eine Datei, das
  **Erfassungsjahr** steht im Dateinamen (am Stuttgarter Westrand 2019), im
  Namen des ZIP nicht — der Weltbau sucht den Eintrag nach Anfang und Endung.
- DOM1 ist laut Produktbeschreibung aus Laserscan **in unbelaubtem Zustand**
  (höchste Punkte, 8 Punkte/m²); DGM1 und DOM1 derselben Kachel haben
  dasselbe Jahr. Das nDOM rechnet der Weltbau wie in NRW selbst als
  DOM1 − DGM1.
- DOP20-WMS: `GetCapabilities` + `GetMap` 1.3.0 mit `CRS=EPSG:4326`
  (Stuttgart-West): JPEG Baseline, bis 15 000 px, außerhalb weißes Bild.

**Lizenz und Quellenvermerk:** Datenlizenz Deutschland – Namensnennung –
Version 2.0 (`dl-de/by-2-0`). lgl-bw.de/Produkte/Open-Data verlangt den
Vermerk „Datenquelle: LGL, www.lgl-bw.de, dl-de/by-2-0“; die WMS-Capabilities
nennen zusätzlich das Jahr des Datenbezugs („LGL-BW (2024) …“, so steht es
schon beim Luftbild). Im Manifest (`attribution`, Art `ELEVATION`):
„Gelände, Bewuchs und Gebäude: Datenquelle: LGL, www.lgl-bw.de, dl-de/by-2-0
(DGM1, DOM1, 3D-Gebäudemodell LoD2)“, Lizenz `dl-de/by-2-0`. Anders als in
NRW ist der Vermerk **Pflicht**; er steht in jeder BW-Laserwelt (geprüft im
Manifest beider Strecken).

### 7.2 Umsetzung

- `LaserSource` hat neu `tiles: LaserTiles?` und `lod2Entry` (beide mit
  Vorgabe `null`); die WCS-Felder sind dafür null-bar. Der NRW-Eintrag ist
  unverändert.
- `fillBlocksFromTiles` (`LaserZipTiles.kt`): je Archiv beide Downloads
  einmal, dann je benötigtem Kilometer DGM (XYZ) und DOM (TIFF) dekodieren
  und alle Blöcke füllen, die ihn berühren; jede Probe nimmt den Kilometer,
  in dem ihr UTM-Punkt liegt. Am Kilometerrand (ein halber Meter) gilt der
  nächste Pixel statt der Mitte mit dem Nachbarn. Test
  `kachelnFuellenDieBloeckeWieDerWcs`: dieselben künstlichen Höhen über WCS
  und über Kacheln — mittlere Abweichung 0,2 mm, höchstens 0,12 m (am
  Kilometerrand), nDOM gleich in 99,99 % der Proben.
- LoD2: `lod2Downloads` gruppiert die Kilometer nach Download (NRW: einer je
  km, BW: ein ZIP für bis zu vier km), `fetchLod2` holt einmal und packt die
  GMLs aus. Ebenso in `lod2-cut`.
- Positivliste: Kachel-Vorlagen wie LoD2 (Host und Pfadmuster, nur Ziffern,
  kein Abfrageteil). Cache: ZIP gilt als plausible Antwort.
- Voranschlag: ganze Archive (27 MB DGM+DOM, 8 MB LoD2 je Archiv).
- Werkzeug: `build`/`plan --catalog-aerial` nimmt die Luftbilder des
  Katalogbaus (damals kannte nur das Werkzeug das BW-DOP20; seit
  24.11.2026 steht es im Register der App, `world.md`).

**NRW unverändert:** Der NRW-Pfad ruft dieselben URLs in derselben
Reihenfolge ab und rechnet gleich (Gruppierung mit je einem Kilometer, gleiche
Reihenfolge der LoD2-Gebäude vor dem Sortieren). Nur am Code geprüft und mit
den bestehenden Tests (`LaserTest`, `LaserWorldTest`); einen NRW-Bau
vorher/nachher habe ich nicht gemacht (die Kachel-Bytes schwanken schon
zwischen zwei Läufen, siehe 4). Welten ohne `--laser` und die NRW-Strecken
sind nicht betroffen; Schauinsland liegt in BW, hat aber kein `laser`.

### 7.3 Strecken und Messungen

Katalog (Version 1, `"laser": true`):

- `solitude` — Schloss Solitude und Glemseck, 13,9 km, 128 Hm, Start am
  öffentlichen Parkplatz „Schloss Solitude“ (48,7799° N, 9,0895° O), durch
  den Glemswald zum Glemseck, oberes Glemstal, Mahdental, zurück zum Schloss.
- `maulbronn` — Kloster Maulbronn und Stromberg, 15,4 km, 164 Hm, Start am
  öffentlichen „Parkplatz am alten Friedhof“ beim Kloster (49,0009° N,
  8,8098° O), über Freudenstein und Zaisersweiher zurück.

Werkzeug mit `JAVA_OPTS=-Xmx2g`, MEDIUM, Luftbild BW (`--catalog-aerial`).
Bauzeit „warm“ = alle Daten im Cache. Während der warmen Läufe lief parallel
ein Gradle-Lauf; die Zeiten und RSS-Werte sind dadurch eher zu hoch.

| | Solitude ohne → mit | Maulbronn ohne → mit |
|---|---|---|
| Dreiecke gleichzeitig | 529 343 → 512 898 | 449 576 → 433 296 |
| Texturen (Budget 256 MB) | 179,9 → 182,6 MB | 182,7 → 181,0 MB |
| fertige Welt | 12,8 → 12,3 MB/km | 12,0 → 11,5 MB/km |
| Bauzeit warm | 39,9 → 50,9 s | 47,0 → 68,0 s |
| Laden Laser kalt / warm | 107 s / 14,6 s | 150 s / 26,8 s |
| Download Laser (erster Lauf) | 202,7 MB = 14,6 MB/km (Raster 198,9 MB in 16 ZIP, LoD2 3,8 MB) | 250,2 MB = 16,2 MB/km (Raster 235,5 MB in 18 ZIP, LoD2 14,6 MB) |
| LoD2 | 12 km², 148 Gebäude | 21 km², 3 650 Gebäude |
| Blöcke | 116 | 158 |
| Versatz DGM1 − Terrarium (Median, P05/P95) | −9,6 m (−8,1 / +7,1) | −1,4 m (−10,0 / +3,6) |
| Heap belegt am Ende (kalt) | 605 → 651 MB | 397 → 290 MB |
| RSS höchstens (kalt) | 1,31 → 1,41 GB | 1,30 → 1,80 GB |

Alle vier Welten bestehen `checkWorld`. Budgets (≤ 700 000 Dreiecke,
≤ 256 MB Texturen) eingehalten. Der warme Bau mit Laser braucht 10–20 s mehr,
vor allem für das Entpacken der XYZ-Texte (29 MB je km).

Gegenüber NRW (WCS, nur der Korridor) ist der **erste Download etwa
dreimal so groß** (NRW 3–5 MB/km Raster, BW 13–15 MB/km), weil immer ganze
2-km-Archive kommen, auch wenn der Korridor nur eine Ecke berührt, und DGM1
als Text. Für den Handy-Bau ist das zu viel; dort bräuchte BW einen eigenen
Zuschnitt (wie `lod2-cut` für LoD2) auf einem Server oder als Release-Asset.

**Katalog-Workflow:** `catalog-build` baut die Einträge mit Laser ohne neue
Eingabe. Der Workflow läuft mit `-Xmx6g`; die Messung oben bleibt mit
`-Xmx2g` unter 1,1 GB belegtem Heap — keine Anhebung nötig. (Stand 24.11.2026 mit acht
Laser-Strecken: RSS höchstens 2,9 GB mit `-Xmx6g`, weiter keine Änderung;
Platte und Laufzeit dagegen ja, siehe `catalog.md`, „Workflow-Grenzen“.) Platz im Runner:
rund 450 MB Laser-Cache für beide Strecken. Die App baut ohne Laser
unverändert mit ihrem Heap von 256 MB (die Laser-Stufe ist dort nicht
eingeschaltet).

**Strecken im Großraum Stuttgart (24.11.2026, Version 1, `"laser": true`):**
`strohgaeu`, `marbach-lemberg`, `bottwartal`, `welzheimer-wald`, `enztal`,
`schoenbuch` — Verlauf, Belag, Paketgrößen und Bilder in
`docs/indoor/catalog.md`, „Strecken im Großraum Stuttgart“. Je Strecke
einmal kalt gebaut (`catalog-build --only <id>`, MEDIUM, Luftbild BW,
`JAVA_OPTS=-Xmx6g`, ohne parallelen Gradle-Lauf; in der letzten Minute
rendert der Betrachter die vorige Strecke):

| | km | Bau gesamt | Laser laden (kalt) | Download Raster + LoD2 | je km | Blöcke | LoD2-Kacheln / Gebäude | Versatz DGM1 − Terrarium (Median, P05/P95) | RSS höchstens |
|---|---|---|---|---|---|---|---|---|---|
| `strohgaeu` | 17,8 | 266 s | 165 s | 278 + 31 MB | 17,3 MB | 190 | 27 / 7 976 | −0,8 m (−6,2 / +2,8) | 2,27 GB |
| `marbach-lemberg` | 24,1 | 360 s | 219 s | 335 + 51 MB | 16,0 MB | 247 | 31 / 11 626 | −1,5 m (−7,6 / +4,0) | 2,61 GB |
| `bottwartal` | 33,3 | 536 s | 346 s | 614 + 41 MB | 19,7 MB | 368 | 43 / 10 295 | −3,2 m (−15,7 / +5,1) | 2,31 GB |
| `welzheimer-wald` | 30,3 | 483 s | 308 s | 538 + 36 MB | 18,9 MB | 314 | 41 / 6 922 | −2,0 m (−19,0 / +4,2) | 2,19 GB |
| `enztal` | 24,0 | 430 s | 281 s | 501 + 62 MB | 23,4 MB | 273 | 33 / 11 491 | −1,6 m (−8,5 / +4,5) | 2,93 GB |
| `schoenbuch` | 19,9 | 290 s | 179 s | 328 + 17 MB | 17,3 MB | 189 | 22 / 690 | −8,2 m (−10,4 / +9,0) | 2,05 GB |

- **Laserabdeckung:** alle sechs vollständig in BW (Quelle `bw`); jede
  Anfrage hat ein Archiv geliefert (keine Fehlermeldung, 10–14 MB je
  Anfrage, also DGM1 und DOM1 je 2-km-Kachel). Einen Anteil der Blöcke mit
  Messwerten gibt das Werkzeug nicht aus; „voll abgedeckt“ ist daraus
  geschlossen, nicht gezählt.
- Alle bestehen `checkWorld`; Budgets eingehalten.
- Der Download je km ist mit 16–23 MB höher als bei Solitude/Maulbronn
  (14,6/16,2): Die Strecken laufen schräg durch das 2-km-Raster oder am
  Fluss entlang (Enztal mit Schleifen: 23 MB/km). Zusammen 2,8 GB
  Laser-Cache — der Grund für die Aufteilung des Workflows
  (`catalog.md`, „Workflow-Grenzen“).
- Schönbuch: großer Versatz (Median −8,2 m) wie bei Solitude (−9,6 m) —
  Terrarium liegt im Wald zu hoch; mit Laser maßgeblich das DGM1.

### 7.4 Bilder (Betrachter, nicht Gerät)

Unter `docs/indoor/bilder/lidar-bw/`. Je Stelle zwei Dateien `<name>-vorher.jpg` (ohne Laser) und
`<name>-nachher.jpg` (mit Laser), an denselben Laufmetern, ohne Fahrer.

| Paar | Strecke, Laufmeter, Kamera | Was sich ändert |
|---|---|---|
| `kloster-maulbronn` | Maulbronn, 100 m, Drohne | Klosterkirche mit Dachreiter, Kreuzgang und Walmdächer aus LoD2 statt Kästen mit Standarddach; auf der Wiese keine gewürfelten Fichten mehr, nur die gemessenen Bäume |
| `boeschung-maulbronn` | Maulbronn, 100 m, Fahrer | Weg in der Hangflanke über dem Ort, Böschung bergseits und Häuser tiefer am Hang (vorher flach) |
| `ortseinfahrt-freudenstein` | Maulbronn, 4 500 m, Fahrer | Hohlweg mit Böschung vor dem Ort, Gebäude mit echter Dachform |
| `zaisersweiher` | Maulbronn, 8 500 m, Drohne | Ortslage mit LoD2-Gebäuden (Grundrisse, Firstrichtung, Halle) |
| `klosterhof-maulbronn` | Maulbronn, 15 250 m, Fahrer | Klosterhof: Wirtschaftsgebäude in echter Höhe, Kirchturm im Hintergrund |
| `glemswald-solitude` | Solitude, 1 000 m, Drohne | Kronendach des Glemswalds aus dem nDOM (Lücken, Bestandshöhen) statt gleichmäßig gewürfelt |
| `glemseck` | Solitude, 7 000 m, Drohne | Glemseck: Gelände der Talaue, Gebäude aus LoD2 |

Aufgefallen: Die LoD2-Dächer in Maulbronn wirken grau-rosa statt
ziegelrot wie im Luftbild (vorher, mit OSM-Gebäuden, rot). Die Dachfarbe
kommt aus dem Luftbild über dem Grundriss mit begrenzter Helligkeit
(Abschnitt 3.4); ob die Begrenzung in BW zu stark greift, ist offen (7.5).
Eine unabhängige Bewertung wie in NRW (5) habe ich für BW nicht gemacht.

### 7.5 Grenzen und offen

- [ ] Download groß (ganze ZIP-Archive, DGM als Text): für das Handy einen
      Korridor-Zuschnitt der Raster bauen (Server oder Release-Asset).
- [ ] DOM1 ist eine Laser-Befliegung im unbelaubten Zustand (am Stuttgarter
      Westrand 2019): Laubkronen sind gemessen, aber eher etwas niedriger
      und lichter als im Sommer; Änderungen seit der Befliegung fehlen (wie
      in NRW gleicht das Luftbild Kahlschläge aus). Das nDOM1 des LGL ist
      bildbasiert (2025, belaubt) und neuer — eine Alternative für den
      Bewuchs, nicht eingebaut.
- [ ] Kilometerrand: nächster Pixel statt bilinearer Mitte (≤ 0,5 m breit);
      ein Rand aus dem Nachbarkilometer würde das beheben.
- [ ] Rechteck von BW (47,53–49,80° N, 7,50–10,50° O) reicht nach
      Rheinland-Pfalz, Hessen, Bayern, Frankreich und in die Schweiz; dort
      liefert das LGL 404 und der Weltbau bleibt bei Terrarium. Eine Strecke
      über die Landesgrenze bekommt Laser nur auf der BW-Seite.
- [x] Luftbild BW im Register der App (Entscheidung des Gründers,
      24.11.2026; `world.md`, „Luftbild Baden-Württemberg“; Host
      `owsproxy.lgl-bw.de` in PRIVACY.md).
- [ ] Dachfarbe der LoD2-Gebäude in BW grau statt ziegelrot (7.4) prüfen.
- [ ] Keine unabhängige Bewertung der BW-Bilder gegen Luftbild/Schummerung.
- [ ] Nur im Betrachter gesehen, nicht auf dem Gerät.

## 8 Server-Bau für eigene Strecken (24.11.2026)

Die Laser-Stufe ist jetzt auch für eigene Strecken nutzbar — nicht auf dem
Handy, sondern auf dem eigenen Selfhost-Server (`indoor-dev server-build`,
Bau-Dienst `server/worlds`). Weltbau und Positivlisten unverändert; siehe
[`serverbau.md`](serverbau.md).

## 9 Fehlerbericht „solitude" (Welt-Version 9 → 10, 24.11.2026)

Anlass: Fahrt des Gründers auf der Katalogstrecke `solitude` (Laser BW, Paket
`solitude-v1.zip`, Welt-Version `indoor-world/9`): „teilweise ist das Mapping der
Assets kaputt". Nachgestellt im Betrachter (three.js/SwiftShader, **nicht auf
dem Gerät**) mit dem veröffentlichten Paket und einem lokalen Neubau
(`build --laser --catalog-aerial`, gleiche Welt-ID wie das Paket). Bilder:
`docs/indoor/bilder/laser-fixes/<befund>-vorher.jpg` / `-nachher.jpg`
(vorher = Paket v1, nachher = dieser Stand; gleiche Blickpunkte).

Neue Werkzeuge zur Fehlersuche (`build` mit `--laser`):
`--laser-profile e0,n0,e1,n1[,schritt][;…]` (Höhenprofil: DGM1, DOM1,
Terrarium + Versatz, überblendete Höhe, Gewicht, eingeebnetes Gelände),
`--roads-near e,n[,r]` (Wege der Karte mit Abstand zur Strecke, Netz-Stationen
„auf der Strecke", Ecken an Einmündungen), `--trees-near e,n[,r]` (nDOM-Bäume
mit Höhe, Kronenradius, gewählter Art). Im Betrachter die freie Kamera
`--cameras free --param eye=e,n,u --param at=e,n,u`.

| # | Befund | Ursache (gemessen) | Änderung |
|---|---|---|---|
| 1 | Geländewand 40–50 m hinter dem Start, 6–10 m, glatte Oberkante, Bäume oben, graublaues Luftbild | **Echtes Gelände (Kandidat d).** Profil quer durch die Wand (n = 1095, e 1840 → 1940, Meter über dem Ursprung): DGM1 −3,4 (e 1872) → +1,4 (e 1884) → +1,3…1,5 bis e 1898 → −5,9 (e 1914). DOM1 = DGM1 auf der Krone (e 1882–1898: offen), daneben 12–25 m Bestand. Im Luftbild liegt dort eine asphaltierte Straße (OSM `tertiary`, e ≈ 1890), also ein **Straßendamm**, 5 m hoch, Flanken 22–27°. Überblendung = 1 (voll im Korridor), Terrarium + Versatz liegt 6–8 m tiefer und spielt hier keine Rolle; das fertige Netz (Paket, aus den GLB gelesen) folgt dem DGM1 auf ±5 cm, `CarvedGround` ebenso (±0,1 m). Kein Kachel-/Kilometerrand (Blöcke bei e 1792/2048, UTM-km 434 m östlich), keine DGM/DOM-Vertauschung. Unschön war nur die **Darstellung**: Die Flanke trug das Luftbild der Kronen von oben (graublau, Winter-DOP) — sah aus wie eine Betonwand | über Befund 4: Die Flanke liegt unter dem Bestand und bekommt Waldboden statt Kronen von oben; vom Weg aus jetzt eine erdige Böschung. Höhe und Form unverändert (sind echt) |
| 2 | Kreuzung: Streifen übereinander, grüner Keil in der Fahrbahn, Wegflächen in Stufen | Die Strecke biegt am Knoten (e 1863,4 / n 1090,1) von Ost nach Nord ab, ein dritter Ast (`service`) geht nach Süden. Die geglättete Strecke schneidet die Ecke: Der Knoten liegt **2,0 m** neben ihr und quer zu ihrer Richtung. `RoadNet` stufte deshalb das Endstück **beider eigenen Wege** (je eine Station am Knoten) nicht als „auf der Strecke" ein — `RoadsLayer` zeichnete sie als Straßen über Fahrbahn und Rand, und `JunctionCorners` legte **vier** Eckflächen an (zwei davon an diesen Scheinabzweigen, mit spitzen Bögen in die Fahrbahn) | `RoadNet`: Eine Station neben einer Station auf der Strecke, die noch im Band der Strecke liegt (Abstand < halbe Breite + 1 m), gehört dazu. `JunctionCorners`: Stücke auf der Strecke verlassen sie nicht; Stationen auf der Strecke zählen als „innen". Jetzt zwei Ecken (nur am echten Südast). Test `LaserFixesTest` (alt: 4 Ecken, neu: 0 am Scheinabzweig). Betrifft **alle Welten** (auch ohne Laser) |
| 3 | Laubbäume: lange dünne Stämme, kleine Krone oben, Kronenkarten als lange „Federn" | `--trees-near` (60 m um die Stelle, 78 Bäume): die Bäume sind **nicht** schmal gemessen — 65 × `BROAD`, Median Höhe 18,4 m, Kronenradius 8,0 m (Grenze der Erkennung), Breitenfaktor 0,9–1,5. Ursache ist das **Modell**: Bäume 40–70 m neben der Strecke (die man von ihr aus sieht) bekommen die ferne Stufe — acht Massekarten um den Kronenmittelpunkt mit Kante = Kronenradius (bis 9 m). Von der Strecke: Stamm bis zur Kronenmitte, darüber ein Schopf, schräg gesehene 9-m-Karten als „Federn". Auch nah: Zweigkarten 0,58 × Radius (bis 5 m, mittlere Stufe × 1,35 = 7 m) und Klumpen nur in einem schmalen Band um die Kronenmitte | `TreeModels`: Kartengröße absolut begrenzt (Zweig 3 m, Masse 4,5 m, fern 6,5 m, Kiefer ebenso); Laubklumpen abwechselnd in oberer und unterer Lage (Laubmasse vom Ansatz bis zum Wipfel); ferne Krone über die ganze Kronenhöhe verteilt, bei Kronenradius > 5 m vier Karten mehr (im Baumbudget mitgezählt, `TreeModels.cost(spec, lod)`). Gilt für **alle Welten**; ohne Laser sind die Bäume kleiner (≤ 24 m), dort greifen die Grenzen selten |
| 4 | Waldboden zeigt das Luftbild (unbelaubte Kronen von oben, graublau) — wirkt wie Beton | Das Luftbild ist die Bodentextur, auch unter dem Kronendach | neu `ForestFloor.kt`: In Stufe 0 wird das Luftbild unter dichtem Bewuchs (`canopyAt`: mit Laser das nDOM, Kahlschläge ausgenommen; ohne Messung nur im Wald der Karte) zu Laubstreu/Moos/Erde abgetönt (bis 82 %, Helligkeit des Luftbilds bleibt als Modulation), neu als JPEG kodiert. Die Kronenschätzung aus dem Luftbild nimmt weiter das Original (`TileContext.aerialRaw`). Gilt für alle Welten mit Luftbild |
| 5 | Rechteckige Flächen in abweichendem Grün mit harten Kanten links neben dem Weg | Die Runde fährt auf den ersten und letzten 45 m denselben Weg; der Start (Laufmeter 0) liegt mitten auf dem Weg. Dort endete das Band samt **7–8 m breitem, deckendem Grasstreifen** mit gerader Querkante — von oben ein Rechteck, das heller ist als das Luftbild (Schotterplatz) | `RouteLayer`: An offenen Enden (Strecke ohne Runde, oder eine Runde, die nicht in Fahrtrichtung geschlossen ist) läuft der Übergangsstreifen über 20 m auf den Randstreifen aus. Kein Kachel- oder Flächenfehler |

**Weitere Stellen (Betrachter):** Solitude bei 1,0 / 2,5 / 4,0 / 5,5 / 7,0 /
8,5 / 10,0 / 12,5 km und Maulbronn bei 0,3 / 2,0 / 4,5 / 7,0 / 10,0 / 13,0 km:
Befund 4 (graublauer Boden unter Wald) an fast allen Waldstellen beider
Strecken — jetzt Waldboden (Bild `maulbronn-km10-*`, `befund4-waldboden-km1-*`).
Befund 1 nirgends sonst als unechte Wand gefunden (Böschungen an Hängen und
Hohlwegen sind im DGM1 echt). Befund 2 und 5 an diesen Stellen nicht gesehen.
Ohne Laser (Solitude km 0–1 ohne `--laser`): keine Wand (Terrarium ist
glatt; Befund 1 betrifft nur Laserwelten), Befund 2 und 3 betreffen den Code
aller Welten und sind dort mit behoben.

**Messwerte Solitude (MEDIUM, Laser, Cache warm):** Dreiecke gleichzeitig
513 256 → 514 701, Platte 171,7 → 173,8 MB (12,3 → 12,5 MB/km; neu kodierte
Luftbilder unter Wald), Texturen 182,4 MB (gleich), Bau 38 → 42 s. Maulbronn
436 434 Dreiecke, 11,7 MB/km. Beide bestehen `checkWorld`.

### 9.1 Offen

- [ ] Alles nur im Betrachter (SwiftShader) gesehen, **nicht auf dem Gerät**.
- [ ] Die Grasstreifen neben Wegen über blassen Winterfeldern (Maulbronn km 7
      und 13) sind satter grün als das Feld. Sie folgen der Farbe des
      Luftbilds direkt am Weg (dort echter Grasrain); eine weitere
      Farbspanne brachte im Bild nichts und ist nicht eingebaut.
- [ ] Waldboden-Abtönung kostet je Kachel unter Wald eine JPEG-Neukodierung
      (rund 2 % mehr Platte, wenige 100 ms Bauzeit je Kachel); auf dem Handy
      (ohne Laser, nur im Wald der Karte) nicht gemessen.
- [ ] Ferne Kronen (8–12 Karten) wirken aus der hohen Betrachter-Drohne
      (85 m) weiter kartig; aus der App-Drohne (28 m) und vom Rad aus nicht
      geprüft auf dem Gerät.
- [ ] Am Knoten mit drei Ästen rechnet `JunctionCorners` die Kante der Strecke
      als Gerade in Richtung der Strecke am Scheitel (45°); die Bögen passen
      dort nur ungefähr an die gekrümmte Fahrbahnkante.
- [ ] Weidezäune stehen an der Solitude teils im Wald (Landnutzung der Karte),
      nicht untersucht.
