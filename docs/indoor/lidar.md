# Indoor — Datenstufe „Laser" (DGM1, nDOM, LoD2)

Stand: 24.11.2026, Branch `claude/indoor-lidar`. Gehört zum Slice indoor-world
(`docs/indoor/world.md`); Vertrag: `docs/indoor.md` 7 und 8.

Die Welt soll der echten Strecke ähnlicher werden. Bisher: Gelände aus
Terrarium-Höhen (~30 m, in Deutschland SRTM/EU-DEM — ein **Oberflächen**modell,
im Wald 10–20 m zu hoch), Gebäude als OSM-Grundrisse mit geschätzter Höhe und
Standarddach, Bäume aus Landnutzung und Luftbildfarbe gewürfelt. Neu ist eine
optionale Datenstufe „Laser" aus den amtlichen Laserscan-Produkten der Länder,
zuerst für Nordrhein-Westfalen:

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
| Baden-Württemberg | opengeodata.lgl-bw.de | ja | DOM1 | ja | dl-de/by-2-0 |
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
| `LaserSources.kt` | `LaserSource`, Register `laserSources` (NRW), eigene Positivliste `isAllowedLaserUrl`, Abruf-Schnittstelle `LaserFetcher` mit Hülle `createLaserPipeline` (Positivliste, Plattencache 365 Tage, 2 Anfragen je Host, 429/503-Wiederholung), Einstieg `createLaserWorldBuilder(LaserOptions)`, Kennzahlen `laserBuildStats` |
| `LaserUtm.kt` | ETRS89/UTM nach Krüger (Reihe bis n⁴, < 1 mm) und ein linearer ENU↔UTM-Rahmen je 256-m-Block (< 1 cm) |
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
