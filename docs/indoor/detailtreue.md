# Indoor — Detailtreue: sieht die Strecke aus wie in echt?

Stand: 25.11.2026, Branch `claude/grafik-detailtreue` (auf `main` + `claude/indoor-weltqualitaet`;
Welt-Version `indoor-world/13`).

Auftrag des Inhabers: „Die Welten sollen nicht nur fotorealistisch aussehen, sondern wenn ich eine
Strecke von mir virtuell fahre, soll sie aussehen wie in der echten Welt.“ Gemeint ist
**Wiedererkennbarkeit** — das Haus an der Ecke, die Allee, der Waldrand, die Brücke, der Kreisverkehr.
Das ist eine Frage der **Datentreue**: was aus echten Daten übernommen und was erfunden oder
weggelassen wird. Schönheit (Licht, Material, Laub) machen andere Sitzungen.

## 1 Bestandsaufnahme: echte Welt → was wir daraus machen

Datenquellen des Weltbaus (alle schon genutzt, keine neue):

| Kürzel | Quelle | Inhalt | Wo |
|---|---|---|---|
| **OMT** | OpenFreeMap-Vektorkacheln (OpenMapTiles-Schema 3.16, aus OpenStreetMap), z14 / z12 | Straßen/Wege (`class`, `subclass`, `brunnel`, `layer`, nur `paved/unpaved`), Gebäudegrundrisse (`render_height`, selten `colour`), Landbedeckung/-nutzung, Wasser, Orte, **POI-Punkte** (u. a. Kirche, Bushaltestelle, Schranke, Tor, Poller, Umlaufsperre, Wegweiser, Schutzhütte, Aussichtspunkt). **Nicht** enthalten: Einzelbäume, Baumreihen, Hecken, Zäune, Mauern, Leitplanken, Lärmschutz, Strommasten/-leitungen, Windräder, Gebäudeart, Dachform, Verkehrszeichen | `VectorData.kt`, `WorldSources.kt` |
| **BR** | BRouter-Abschnitte der Strecke | Belag, Wegart, Breite (falls getaggt) der befahrenen Strecke | `IndoorRoute`, `TrackFit.kt` |
| **DEM** | Terrarium (AWS), z13 innen / z11 außen | Oberflächenmodell ~30 m (SRTM/EU-DEM), im Wald 5–15 m zu hoch | `Elevation.kt` |
| **DOP** | amtliche Luftbilder (WMS): App-Register NRW, Sachsen, Thüringen, Brandenburg/Berlin, BW; im Katalogbau zusätzlich Hessen, Frankreich (IGN), Flandern, Spanien (PNOA), USA (NAIP) | Bodentextur; Stufe 0 **1024 px je 256 m = 25 cm/px**, Stufe 1 512 px/1 km (2 m/px), Stufe 2 512 px/4 km (8 m/px). Außerhalb dieser Länder erzeugter Boden aus der Landnutzung | `Aerial.kt`, `IndoorWorldEntry.kt`, `CatalogAerial.kt` |
| **DGM1/DOM1/LoD2** | Laserdaten der Länder (nur NRW, BW; nur Werkzeug/Katalog/Server-Bau) | Gelände 1 m, Bewuchshöhe (nDOM = DOM1 − DGM1), Gebäude als LoD2 (Grundriss, Trauf-/Firsthöhe, Dachform) im Korridor 200 m (LoD2 120 m) | `Laser*.kt` |

Stufen: **ohne Laser** (Handy-Bau, Katalog außerhalb NRW/BW), **mit Laser** (Katalog NRW/BW,
Server-Bau), **Katalog** (wie „mit/ohne Laser“, dazu Luftbild auch aus Ländern außerhalb des
App-Registers), **Handy-Bau** (immer ohne Laser, Luftbild nur aus den fünf Ländern, 256 MB Heap).

Legende Spalte „genutzt“: **ja** = aus Daten übernommen, **teilw.** = Daten + Heuristik,
**erfunden** = gewürfelt/heuristisch ohne Beleg in den Daten, **nein** = fehlt.

| Was | ohne Laser (Handy, Katalog) | mit Laser (NRW/BW) | Fundstelle |
|---|---|---|---|
| **Gelände** | teilw.: Terrarium ~30 m, geglättet; Böschungen/Dämme/Einschnitte fehlen, Wald zu hoch | **ja**: DGM1 1 m im Korridor 200 m, außen Terrarium + Versatz | `Ground.kt` (`CarvedGround`), `LaserTerrain.kt` |
| **Luftbild** | ja in NRW, SN, TH, BB/BE, BW (App), weitere Länder nur Katalog; sonst erzeugt. 25 cm/px bis ~200–400 m neben der Strecke (Stufe 0), dahinter 2 m bzw. 8 m. Jahreszeit = Befliegung (BW teils Winter, NRW Sommer) | wie links | `Aerial.kt`, `ForestFloor.kt` (unter Kronen abgetönt) |
| **Fahrbahn: Breite** | teilw.: `width` aus OSM über BRouter, sonst Vorgabe je Belag | wie links | `IndoorRoute.effectiveWidthM` |
| **Belag** | ja (BRouter `surface`), sonst aus OMT `paved/unpaved` | wie links | `TrackFit.kt`, `RouteTextures.kt` |
| **Spuren/Markierung** | **erfunden**: Rand-/Mittellinie nach Wegart und Breite (≥ 5,5 / 6 m); echte Markierung (Haifischzähne, Radstreifen, Zebrastreifen) nein | wie links | `RouteLayer.hasEdgeLines/hasCentreLine` |
| **Randstreifen, Bordstein** | erfunden aus Landnutzung (Bankett außerorts, Bordstein + Gehweg in Wohngebiet); Farbe nach Luftbild | wie links | `RouteLayer`, `edgeStyleOf` |
| **Radweg** | ja als eigener Weg (`cycleway`, 2,2 m), wenn in OSM getrennt; Radfahrstreifen auf der Fahrbahn nein | wie links | `RouteGeometry.roadSpec` |
| **Querstraßen, Einmündungen** | ja (OMT-Linien, Breite je Klasse geschätzt), flach aufs Gelände, Eckbögen | ja, auf DGM1 | `RoadsLayer.kt`, `JunctionCorners.kt` |
| **Kreisverkehre** | ja (geschlossene Ringe erkannt, Insel als Grasfläche); Mini-Kreisel (nur Punkt) nein | wie links | `Roundabouts.kt` |
| **Brücken** | ja (`brunnel=bridge`): Überbau, Pfeiler, Geländer; Form/Material erfunden | ja; Geländeform darunter aus DGM1 | `PropsLayer.bridgeStructures` |
| **Tunnel/Unterführung** | teilw.: Tunnelstraßen werden weggelassen; Unterführung nur durch die Brücke darüber | Unterführungen im DGM1 frei | `roadSpec` (`tunnel` → null) |
| **Bahnlinien** | ja (`rail`): Schotterbett + Schienen; Oberleitung, Bahnübergang nein | wie links | `RoadsLayer.rails` |
| **Gebäude: Grundriss** | ja (OSM über OMT) | **ja** (LoD2 bis ~120 m, sonst OSM) | `BuildingsLayer.kt`, `LaserParts.kt` |
| **Höhe** | teilw.: `render_height` (OSM `height`/`building:levels`), sonst geschätzt 6,6–9,6 m Traufe | **ja** (LoD2; sonst nDOM-Median im Grundriss) | `BuildingDesign.estimateEave` |
| **Dachform** | **erfunden** (Sattel/Walm/Flach nach Größe und Zufall; OMT hat keine Dachform) | **ja** (LoD2) | `BuildingsLayer.assignRoof`, `Lod2Mesher` |
| **Dachfarbe** | teilw.: Luftbild-Mittel um die Mitte → **eine von 5 Klassen** (Ziegelrot, Rotbraun, Braun, Anthrazit, Grau), Grau oft zu Rot umgedeutet, sonst Zufall | **meist erfunden**: Luftbild (2 m um die Mitte) nur bei eindeutig rot/sehr dunkel, sonst regionale Zufallsmischung | `BuildingDesign.roofTint`, `Lod2Mesher.roofColor` |
| **Dachbild** (Solaranlagen, Gauben, Dachfenster, Gründach) | nein (eine Pfannen-/Blech-/Bitumentextur) | nein | `BuildingTextures.kt` |
| **Fassade** | erfunden (Atlas Putz/Klinker/Fachwerk/Schiefer je Gebäude, Fenster in Geschossen); OSM `colour` selten | erfunden (Putztöne gewürfelt, Fensterband); LoD2 kennt keine Öffnungen | `wallTint`, `Lod2Mesher.wallColor` |
| **Kirche, markante Bauten** | nein (OMT nennt keine Gebäudeart; POI `place_of_worship` ungenutzt) | ja als Form aus LoD2 (Turm, Langhaus), Farbe Schiefer/Kupfer nach Größe | `Lod2Mesher.roofColor` |
| **Bäume: Lage** | **erfunden**: Würfelgitter (5 / 10 m), gefiltert mit der Kronenschätzung aus Luftbildfarben bzw. Landnutzung | **ja** (nDOM-Maxima), aber nur so viele, wie das Baumbudget je Kachel erlaubt (nach Abstand) | `TreesLayer.singleTrees`, `LaserData.kt` |
| **Höhe, Krone** | erfunden (je Art/Region) | ja (nDOM-Höhe, Kronenradius) | `laserTreeSpec` |
| **Art** | erfunden (Region, Landnutzung, Zufall) | erfunden (aus Höhe/Form) | `TreesLayer.specFor` |
| **Alleen** | teilw.: aus Baumreihen im Luftbild 3–6 m neben der Straße, gleichmäßig 9,5 m | ja (gemessen) | `TreesLayer.alleeTrees` |
| **Hecken** | nein (OMT hat keine) | nein (nDOM-Plateaus werden als „keine Bäume“ verworfen) | `LaserData.kt` |
| **Wald** | teilw.: Waldkörper ab ~70 m, davor Einzelbäume nach Budget | teilw.: Kronendach aus nDOM für den Waldkörper; **im Korridor nur Einzelbäume nach Budget** (siehe 2) | `TreesLayer.forestVolume` |
| **Obstwiese** | erfunden: Reihen 10 × 8,5 m in OSM-`orchard` | ja (gemessen, als Obstbäume) | `orchardTrees` |
| **Feld, Wiese** | ja (Luftbild als Boden; Landnutzung für Randstreifen, Zäune) | wie links | `GroundTexture.kt` |
| **Weinberg** | teilw.: OSM-`vineyard` → „Gebüsch“ (Kronenanteil 0,3: gewürfelte Sträucher/Bäume), Rebzeilen nur im Luftbild | Rebzeilen < 3 m werden nicht als Bäume erkannt; nur Luftbild | `landClassOf` |
| **Siedlung** | ja (Landnutzung, Luftbild) | wie links | |
| **Wasser** | ja: Flächen (OMT) als Wasserspiegel; Bäche nur in der Bodentextur | wie links | `WorldParts` (Wasser), `GroundTexture.paintWaterways` |
| **Mauern, Zäune, Leitplanken, Lärmschutz** | **nein** in den Daten; **Weidezäune erfunden** (je 120-m-Block 34 % Wahrscheinlichkeit, nach Landnutzung) | wie links | `PropsLayer.fences` |
| **Masten, Leitungen** | nein (keine Daten); Laternen erfunden (innerorts alle 36 m) | wie links | `PropsLayer.streetLamps` |
| **Schilder** | Ortstafel **mit echtem Ortsnamen** (OMT `place`, Ortsbeginn heuristisch am Wohngebiet); Leitpfosten, Vorfahrtszeichen, Wegweiser an Einmündungen (45 % Zufall) **erfunden**; Ampeln nein | wie links | `PropsLayer.townSigns/guidePosts/prioritySigns/signposts` |
| **Bänke, Holzpolter** | erfunden (Zufall je 300 m / 150 m) | wie links | `PropsLayer.benches/logPiles` |
| **Bushaltestelle, Schranke, Tor, Umlaufsperre, Wegweiser, Schutzhütte** | **nein** — in OMT als POI vorhanden, ungenutzt | wie links | — |
| **Windrad, Funkmast, Silo, Kirchturm** | nein (Windrad/Mast nicht in OMT; Silo/Turm nur als OSM-Grundriss mit Höhe) | ja als LoD2-Körper (Silo, Turm), Farbe erfunden | |
| **Ortsliste** | nein (Ortsnamen nur auf den Tafeln) | wie links | — |

Zusammengefasst: **Was am Boden liegt, stimmt** (Luftbild als Textur: Felder, Wege, Parkplätze,
Wiesen). **Was aufragt, ist nur teilweise echt:** Gebäude stehen an der richtigen Stelle mit
richtigem Grundriss (mit Laser auch richtiger Höhe und Dachform), aber mit gewürfelter Dachfarbe und
Fassade; Bäume stehen ohne Laser gewürfelt, mit Laser an echter Stelle — aber im Wald nur ein
Bruchteil; Objekte am Straßenrand sind bis auf Ortstafel und Brücke erfunden.

## 2 Prüfung an veröffentlichten Strecken

Pakete aus dem Release `indoor-worlds-v1` (Stand 25.11.2026: `solitude-v3`, `marbach-lemberg-v2`,
`balkantrasse-v8`, alle `indoor-world/11`, Laser), gerendert im Betrachter (three.js,
SwiftShader): senkrecht von oben (240 m × 240 m, ohne Dunst) und aus der Verfolgerkamera,
daneben das Luftbild derselben Stelle direkt vom WMS, den auch der Weltbau nutzt (BW DOP20 bzw.
NRW DOP; die heutige Antwort kann eine neuere Befliegung sein als die im Paket). Bilder:
`docs/indoor/bilder/detailtreue/<stelle>-bestand.jpg` (Luftbild | Szene | Fahrersicht).
Luftbilder: Datenquelle LGL, www.lgl-bw.de, dl-de/by-2-0 bzw. © Geobasis NRW (dl-de/zero-2-0).

| Stelle | gesehen |
|---|---|
| `sol300` Solitude km 0,3 (Glemswald, Parkplatz) | echter Buchenwald geschlossen bis an den Weg; Szene: im Korridor ±70–100 m lichter „Park“ aus Einzelbäumen mit langen Stämmen über braunem Waldboden, erst dahinter Waldkörper (eckige Lücken). Fahrersicht: Stangenholz mit freiem Boden |
| `sol7000` Solitude km 7 (Glemseck) | wie oben im Wald; Gebäude am Parkplatz: echt dunkles Flachdach unter Bäumen → Szene **Ziegelrot**; Kreisverkehr, Halle, Parkplätze stimmen (Luftbild + Geometrie) |
| `sol10000`, `sol12500` | Wald wie oben; Lichtung mit Jungwuchs bleibt richtig frei; Fahrbahn teils gestrichelt hell (Weg unter dem Gelände) |
| `mar500` Marbach Bahnhof | großes **orangefarbenes** Dach → Szene hellgrau; **weiße Silotürme** (Landmarke) → schwarzer Block; Solar- und Gründächer → anthrazit/rot gewürfelt; Bäume und Grundrisse stimmen |
| `mar3000` Murrtal, Kreisverkehr/Obstwiese | gut wiedererkennbar: Lage der Bäume, Felder, Wege, Kreisel |
| `mar6000` Obstwiesen, Ortsrand | Baumreihen dünner als echt; Dachfarben teils falsch |
| `mar9000` Ortsrand | Haus mit dunklem Solardach → **rot**; Halle grau → rot; **Weidezaun am Feld erfunden** (echt keiner) |
| `mar12000` Waldstraße | Wald im Korridor licht wie bei der Solitude |
| `bal200` Wermelskirchen Innenstadt | Bergisches Land: viele echte **Schieferdächer (dunkelgrau)** → Szene häufig **ziegelrot**; große helle Hallendächer mit Oberlichtern → dunkelgrau; Grundrisse, Höhen, Lage stimmen |
| `bal2650` Trasse im Einschnitt | Böschung und Gehölzband stimmen grob; Hallen-/Flachdächer hellgrau/blau → dunkel |

### Mängelliste der Detailtreue (nach Wirkung auf die Wiedererkennung)

| # | Mangel | Wirkung | Ursache | Aufwand |
|---|---|---|---|---|
| 1 | **Wald im Korridor wird lichter Park** (Laser- und Nicht-Laser-Welten) | sehr hoch auf Waldstrecken: „Wald“ wird nicht erkannt; Waldrand-Linien verschoben | `TreesLayer.singleTrees`: nDOM-Bäume nach Abstand bis `SINGLE_TREE_MAX` (≈150–250 Bäume je Kachel, echter Wald ≈1 000+); Waldkörper der Stufe 0 erst jenseits ~70 m | mittel; **Baustelle der Sitzung Streckenqualität (Waldkörper)** — abgestimmt, siehe 3 |
| 2 | **Dachfarbe je Gebäude meist gewürfelt** (orange → grau, Schiefer → rot, Solar → rot, weiße Silos → schwarz) | hoch in Orten: das „Haus an der Ecke“ hat die falsche Farbe; aus der Drohne sofort falsch | Laser: `Lod2Mesher.roofColor` nimmt das Luftbild (2 m um die Mitte) nur bei eindeutig Rot, sonst Zufall; ohne Laser 5-Klassen-Abbildung mit Umdeutung Grau → Rot | mittel: Dachbild aus dem Luftbild der Kachel (gleiche Textur wie der Boden, kein Speicher extra), Farbe aus dem ganzen Grundriss als Rückfall |
| 3 | **Dachbild fehlt** (Solaranlagen, Oberlichter, Gründächer, Gauben) | mittel–hoch aus der Drohne, mittel vom Rad | eine Atlastextur für alle Dächer | mit 2 |
| 4 | **Erfundene Objekte** (Weidezäune, Bänke, Holzpolter, Wegweiser an 45 % der Einmündungen, Leitpfosten) | mittel: stören, wenn man die Stelle kennt („da ist kein Zaun“) | `PropsLayer` würfelt nach Landnutzung | klein: echte POI-Lagen bevorzugen; Zäune nur, wo plausibel |
| 5 | **Echte Punktobjekte fehlen**: Bushaltestellen, Schranken/Tore, Umlaufsperren, Wanderwegweiser, Schutzhütten, Kirchen ohne Laser | mittel: genau solche Dinge merkt man sich am Weg | OMT-POI-Ebene ungenutzt | mittel |
| 6 | **Weinberg = Gebüsch** | mittel auf den Stuttgarter Strecken (Lemberg, Bottwar-, Enztal) | `vineyard` → `SCRUB` | mittel (Rebzeilen nach Hangfallinie) |
| 7 | Ortsnamen nur auf Tafeln, keine **Ortsliste** | für Einblendungen (Bergwertung) | kein Manifestfeld | klein |
| 8 | Hecken, Mauern, Zäune, Leitplanken, Lärmschutz, Strommasten, Windräder fehlen | mittel | **nicht in den Vektorkacheln** (OpenMapTiles-Schema); Laser: Hecken wären im nDOM erkennbar | Hecken aus nDOM: mittel; Rest nur mit neuer Quelle (nicht erlaubt) |
| 9 | Fassaden erfunden | mittel vom Rad | weder OMT noch LoD2 noch Luftbild zeigen Fassaden | nur mit Straßenfotos (Option, siehe unten) |
| 10 | Markierungen erfunden (nach Breite) | gering–mittel | keine Daten | — |
| 11 | Ohne Laser: Bäume gewürfelt, Dachform erfunden, Gelände 30 m | hoch für Handy-Bauten außerhalb NRW/BW | Datenlage | Luftbild-Kronen genauer (eigene Untersuchung) |

Grenzen der Daten (ehrlich): Fassaden, Schilder, Ampeln, parkende Autos und Jahreszeit stehen in
keiner der genutzten Quellen; das Luftbild ist ein Schnappschuss einer Befliegung (BW teils
Winter, unbelaubt), das DOM1 ist älter als das Luftbild (Kahlschläge werden über das Luftbild
ausgeglichen, Neupflanzungen fehlen); außerhalb von NRW und BW gibt es weder Gelände 1 m noch
Bäume an echter Stelle noch LoD2, außerhalb der Luftbild-Länder nicht einmal den echten Boden.

## 3 Umsetzung (Welt-Version `indoor-world/13`)

Alles in neuen Dateien, mit wenigen Zeilen eingehängt (Abstimmung mit der Sitzung Streckenqualität,
deren Stand `indoor-world/12` eingemergt ist). Keine neuen Materialerweiterungen (Issue 106): neue
Materialien sind gltfio-Standard (`x_mat_roof_aerial`, `x_mat_vines`), die übrigen Objekte nutzen das
Kleinteil-Material `x_mat_props`.

| Mangel | Änderung | Datei / Einhänger | Daten | Stufen |
|---|---|---|---|---|
| 2, 3 Dachfarbe/-bild | Dach in Stufe 0 mit dem **Luftbild der eigenen Kachel** (dieselbe Bildtextur wie der Boden, kein Texturspeicher extra, +1 Zeichenaufruf je Kachel); Sonnenseite auf die mittlere Dachhelligkeit abgedunkelt, Grundabschlag 18 % (die Sonne steckt schon im Bild). Rückfall auf das alte Dach bei Kronen über dem Dach (> 35 % grüne Proben), Schlagschatten, Gebäuden über 22 m (Kippen im Orthofoto) und Dachpunkten > 1 m außerhalb der Kachel | `AerialRoofs.kt`; 2 Zeilen in `BuildingsLayer`, 3 in `LaserParts` | Luftbild | **alle mit Luftbild, auch Handy-Bau** |
| 7 Ortsliste | Manifestfeld `places: [{name, distM}]` (optional, nur wenn nicht leer; ältere Leser überspringen es): Orte der Ebene `place` mit Einzugsradius je Klasse, jede Annäherung einzeln, Laufmeter der nächsten Stelle | `WorldPlaces.kt`; Feld in `WorldManifest`, 1 Zeile in `WorldBuilderImpl` | Vektorkacheln | alle |
| 5 echte Punktobjekte | Bushaltestellen (H-Schild am Mast), Schranken (Baum hochgeklappt), Tore (offen am Rand), Umlaufsperren (Mitte frei), Poller (nur abseits der Strecke), Wanderwegweiser, Infotafeln, Schutzhütten **an der echten Stelle**, von der Fahrbahn an deren Rand geschoben; erfundene Wegweiser entfallen, wo die Karte im Umkreis von 1,5 km echte führt | `PoiLayer.kt`; Ebene in `FullWorldParts`, `MapData.pois` (`WorldSources`), 2 Stellen in `PropsLayer` | POI-Ebene der Vektorkacheln | alle |
| 6 Weinberg | Rebzeilen (0,45–1,4 m, 2 m Abstand) bis 70 m neben der Strecke; **Zeilenrichtung aus dem Luftbild** (Strukturtensor), sonst Fallrichtung; nur was die Kachel an Dreiecken frei hat | `VineyardLayer.kt`; Ebene in `FullWorldParts` | Vektorkacheln (`vineyard`) + Luftbild | alle |
| 4, 8 Hecken, Zäune, Mauern, Leitplanken, Lärmschutz, Strom, Windräder, Masten, Laternen | aus vollen OSM-Daten (Overpass) an der echten Stelle; erfundene Laternen/Weidezäune entfallen, wo die Daten welche führen; ODbL-Vermerk im Manifest | `OsmDetail.kt`, `OsmDetailLayer.kt`, Werkzeug `OsmDetailFetch.kt`; Ebene in allen drei Stufen, `MapData.osmFeatures`, Parameter in `WorldBuilderImpl`, `createWorldBuilder(laser, osm)` | OpenStreetMap (Overpass) | **nur Katalog** (Standard) und `build --osm-detail`; Server-Bau und Handy nie |
| 1 Wald im Korridor | **nicht geändert** — Baustelle der Sitzung Streckenqualität (Waldkörper), gemeldet | – | – | – |

### Belege (Betrachter three.js/SwiftShader, nicht Gerät)

Ausschnitte der Katalogstrecken, je einmal mit dem Stand vor dieser Runde (`6df29a9`, „vorher“) und
mit diesem Branch („nachher“) gebaut (`build --from-km … --to-km … --laser --catalog-aerial`, MEDIUM),
gleiche Blickpunkte; Luftbild direkt vom WMS. Bilder unter `docs/indoor/bilder/detailtreue/`:

| Bild | Stelle | gesehen |
|---|---|---|
| `dach-marbach-bahnhof-oben.jpg`, `-fahrer.jpg` | Marbach km 0,5 (Laser) | oranges Dach jetzt orange, Solar- und Gründächer sichtbar, Dachfarben je Haus wie im Luftbild; Silotürme (> 22 m) unverändert dunkel |
| `dach-marbach-ohne-laser-oben.jpg` | dieselbe Stelle **ohne Laser** (wie der Handy-Bau) | größter Gewinn: vorher anthrazit/grau gewürfelte Großdächer, jetzt Ziegelrot, Orange, Blech mit Oberlichtern wie echt |
| `dach-marbach-km9-oben.jpg` | Marbach km 9 | dunkles Solardach statt Rot, Halle grau statt Rot; der erfundene Weidezaun bleibt (ohne OSM-Zusatzdaten, siehe unten) |
| `dach-wermelskirchen-oben.jpg`, `-fahrer.jpg` | Balkantrasse km 0,2 (NRW) | bergische Schieferdächer dunkelgrau statt ziegelrot, helle Hallendächer mit Oberlichtern |
| `glemseck-oben.jpg` | Solitude km 7 | rotes Dach am Parkplatz jetzt dunkel wie echt; Wald im Korridor unverändert licht (Mangel 1) |
| `weinberg-lemberg.jpg` | Marbach km 15,3 (Lemberg) | Rebzeilen hangabwärts wie im Luftbild; das Winterluftbild darunter bleibt kahl (Jahreszeit) |

Messwerte je Ausschnitt (Dreiecke gleichzeitig, Platte der Kacheln, Texturen; vorher → nachher):

| Ausschnitt | Dreiecke | Platte | Texturen |
|---|---|---|---|
| Marbach km 0–1,5 (Laser) | 361 998 → 362 308 | 36,9 → 36,9 MB | 146,9 MB gleich |
| Marbach km 8–10 | 323 972 → 324 078 | 42,4 → 42,3 MB | 162,7 MB gleich |
| Marbach km 14–16 (Weinberg) | 389 104 → 396 748 (+2 %) | 40,1 → 40,6 MB | 174,3 MB gleich |
| Balkantrasse km 0–1,2 | 418 157 → 418 529 | 36,1 → 36,1 MB | 184,7 MB gleich |
| Solitude km 6,3–7,7 | 434 487 → 434 797 | 43,1 → 43,1 MB | 169,9 MB gleich |
| Marbach km 0–1,5 ohne Laser | 367 418 → 367 728 | 35,6 → 35,6 MB | 146,9 MB gleich |

Zeichenaufrufe: +1 je Kachel der Stufe 0 mit Luftbild-Dächern (im Ausschnitt Marbach km 14–16 12 von
23 Kacheln), +1 je Kachel mit Rebzeilen. Alle Welten bestehen `checkWorld`.

**Nicht belegt:** Die OSM-Zusatzdaten (Overpass) konnten in dieser Umgebung nicht abgerufen werden
(`overpass-api.de` ist hier nicht erreichbar, die Verbindung wird beim Aufbau zurückgesetzt); der Bau
läuft dann ohne sie. Geprüft sind sie nur im Test mit erfundenen Objekten. POI-Objekte sind gebaut,
aber in den Bildern nicht gezielt aufgesucht. `:core:test`/`:indoor-dev:test` liefen hier wegen der
Maven-Drosselung (429 auf Testabhängigkeiten) nicht — Vollcheck bei der Orchestrierung. Gerät: nicht gesehen.

## 4 Weitere Datenquellen (Bewertung, abgerufen am 25.11.2026)

Erweiterung des Auftrags durch den Inhaber („können wir noch mehr Daten als Luftdaten nutzen …
z. B. Street View“). Regel der Orchestrierung: **eingebaut** wird nur, was eindeutig offen lizenziert ist,
ohne Konto/Token auskommt und im Katalog-/Server-Bau abgerufen wird (nie vom Handy); alles andere ist
Entscheidungsvorlage. Alle Angaben aus den genannten Seiten, gelesen am 25.11.2026 (Recherche mit
Unter-Agenten, Zitate dort nachgelesen); „nicht geprüft“ ist so markiert.

**Datenschutz-Grundsatz:** Jede Abfrage mit dem Rechteck oder Korridor einer *eigenen* Strecke verrät dem
Dienst die Hausrunde (und die IP). Deshalb fragt das Handy keinen der neuen Dienste ab; Katalogstrecken
sind öffentlich, der Server-Bau läuft auf dem eigenen Server (dort sieht der Datendienst die Anfrage
des Servers, nicht des Nutzers — der Verlauf ist ihm trotzdem bekannt).

| Quelle | Nutzen für die Wiedererkennung | Abdeckung (DE) | Lizenz / Bedingungen (Fundstelle) | Konto/Token | Menge | Handy / Katalog-Server | Aufwand | Urteil |
|---|---|---|---|---|---|---|---|---|
| **OSM voll über Overpass API** | Hecken, Zäune, Mauern, Leitplanken, Lärmschutz, Strommasten/-leitungen, Windräder, Masten/Türme, Laternen, Einzelbäume mit Art, Baumreihen, Dachform/-farbe, Geschosse, Breite/Spuren | taginfo DE (Stand 24.11.2026, taginfo.geofabrik.de/europe:germany): 39,6 Mio. Gebäude, davon `building:levels` 4,0 Mio. (~10 %), `roof:shape` 3,7 Mio. (~9 %), `roof:colour` 0,74 Mio. (~2 %); `barrier=fence` 583 k, `hedge` 538 k, `wall` 244 k, `guard_rail` 48 k; `natural=tree` 4,24 Mio., `tree_row` 376 k, `genus` 381 k; `power=tower` 212 k, `pole` 712 k, `line` 52 k; `generator:source=wind` 33 k; `man_made=mast` 40 k, `tower` 32 k, `silo` 31 k, `chimney` 9 k; `highway=street_lamp` 1,13 Mio.; `traffic_sign` 690 k | ODbL 1.0, „© OpenStreetMap contributors“ (openstreetmap.org/copyright); abgeleitete Datenbanken bleiben ODbL. Öffentliche Instanz: „maximum of about 10000 requests per day … below about 1 GB per day“, ausdrücklich **kein App-Backend** („relying on the public instances as backend … only running your own instance sustainably serves your mission“, dev.overpass-api.de/overpass-doc/en/preface/commons.html) | nein | eine Anfrage je Strecke, einige 100 kB–MB | **nur Katalog/Server** (für das Handy nur eigener Dienst) | gering | **eingebaut** (`--osm-detail`, siehe 6) |
| Geofabrik-Auszüge (`.osm.pbf`) | wie Overpass, ganze Länder | Deutschland 4,6 GB, BW 624 MB, NRW 876 MB, Bayern 819 MB; täglich aktualisiert | ODbL 1.0 („All OpenStreetMap derived data on the download server is licensed under the Open Database License 1.0“, download.geofabrik.de) | nein | GB | Katalog/Server; **für das Handy der datenschutzfreundliche Weg**: eigene Kacheln mit Zusatzattributen je Region bauen und ausliefern (verrät nur die Region) | mittel (eigene Kachel-Pipeline, Hosting) | Vorschlag für später |
| Overture Maps (Gebäude u. a.) | Gebäude mit `height`, `num_floors`, `roof_shape`, `roof_color`, `facade_color` | in DE stammen die 3D-Attribute fast nur aus OSM („primary source is OpenStreetMap … highest conflation priority“, docs.overturemaps.org/guides/buildings/) | Gebäude/Verkehr/Basis ODbL, Places CDLA Permissive 2.0 (docs.overturemaps.org/attribution/) | nein (S3/Azure `--no-sign-request`) | 2,5 Mrd. Gebäude weltweit (GB-Summe nicht geprüft) | Katalog/Server (GeoParquet) | mittel | in DE kaum Mehrwert gegenüber OSM |
| Wikidata / Wikimedia Commons | Name, Art, ggf. Höhe markanter Bauwerke (über OSM `wikidata=*`), Fotos als Vorlage | gut für Kirchen/Türme (Höhe P2048 nicht geprüft) | Wikidata CC0; Commons-Bilder je Bild verschieden (oft CC BY-SA); Query-Service 60 s Rechenzeit je 60 s, User-Agent mit Kontakt Pflicht (mediawiki.org/wiki/Wikidata_Query_Service/User_Manual, foundation.wikimedia.org/wiki/Policy:User-Agent_policy) | nein | klein | Katalog/Server | gering–mittel | optional (Landmarken-Höhen) |
| **Panoramax** (Straßenfotos) | Fassadenfarbe, Schilder (Bilder) | lückenhaft: Stichprobe Odenwald/Eifel/München je ≥ 500 Bilder, Feldberg (Schwarzwald) und Brocken 0; keine deutsche Instanz (panoramax.openstreetmap.de existiert nicht), Bilder über api.panoramax.xyz | Bilder CC BY-SA 4.0; **abgeleitete Daten** ausdrücklich „under Licence Ouverte 2.0, CC-BY 4.0 or ODbL 1.0“ (panoramax.openstreetmap.fr, Nutzungsbedingungen 02.02.2026); Gesichter/Kennzeichen automatisch verwischt | nein („without any account“) | Bilder 500 px–HD | nur Katalog/Server | hoch (Fassaden), mittel (Farben) | **Entscheidungsvorlage** (Personenbilder — nicht gebaut) |
| Mapillary | beste Abdeckung (nicht gemessen, Token nötig), erkannte Verkehrszeichen/Punkte | – | Bilder CC BY-SA; **erkannte Objekte gehören Mapillary**: „limited, non-exclusive, non-transferable, non-sublicensable, revocable license“; Ausnahme nur für Beiträge zu OSM/Overture (mapillary.com/terms, 15.02.2024); Kacheln 50 000/Tag | **ja** (Client-Token je App) | – | nur Server | mittel | nur mit Konto; Erkennungen nicht in Pakete (Lizenz) — besser in OSM eintragen |
| KartaView | Fotos | nicht messbar (API antwortete nicht) | CC BY-SA 4.0, „© Grab and KartaView Contributors“ (kartaview.org/terms) | für viele Endpunkte nein | – | – | – | unzuverlässig |
| Google Street View | Fassaden, Schilder | sehr gut | **ausgeschlossen**: „will not export, extract, or otherwise scrape … pre-fetch, index, store … bulk download“, kein Caching, keine abgeleiteten Inhalte (Beispiel „construct an index of tree locations … from Street View imagery“), im EWR keine Nutzung zusammen mit einer Karte (cloud.google.com/maps-platform/terms, 26.08.2026, und EWR-Fassung) | ja + Abrechnung | – | – | – | ausgeschlossen |
| Google Photorealistic 3D Tiles | fertiges 3D-Netz | – | **im EWR technisch gesperrt** („Photorealistic 3D tiles are not available … 403 … from EEA projects“, developers.google.com/maps/comms/eea/map-tiles); außerdem kein Speichern/Offline, keine Geodaten-Extraktion (developers.google.com/maps/documentation/tile/policies) | ja + Abrechnung | – | – | – | ausgeschlossen |
| Apple Look Around / Bing Streetside | – | – | nur interaktiv im eigenen Kartenwerkzeug mit Token (MapKit) bzw. Bing Maps for Enterprise abgekündigt | ja | – | – | – | ausgeschlossen |

**Wo die OSM-Zusatzdaten (Overpass) abgerufen werden — und wo nicht** (Vorgabe der Orchestrierung,
Wohngegend des Inhabers ist privat): Die Abfrage verrät dem Overpass-Betreiber den Streckenkorridor.
- `catalog-build`: **an** (Katalogstrecken sind öffentlich); `--no-osm-detail` schaltet ab.
- `build` (Entwicklerwerkzeug): **aus**, nur mit `--osm-detail`.
- `server-build` (eigene Strecken, `server/worlds`): **aus**, es gibt dort keinen Schalter — der Pfad ruft
  `createWorldBuilder()` bzw. `createLaserWorldBuilder()` ohne Zusatzdaten auf (`ServerBuild.kt`). Ein späterer
  Schalter müsste ausdrücklich gesetzt werden und dokumentieren, dass die Streckenlage dann an einen Dritten geht.
- Handy-Bau: **nie** (die App kennt `OsmDetail` nicht; `createWorldBuilder()` ohne Argumente).
Für eigene Strecken bleibt der Geofabrik-Weg (eigene Kacheln mit Zusatzattributen je Region) die Empfehlung.

**Amtliche und weitere offene Daten** ([sek] = nur aus der Sekundärquelle technicalgis.com/aerial-imagery-atlas/germany, Stand 19.11.2026, an der Quelle noch zu prüfen):

| Quelle | Nutzen | Abdeckung | Lizenz (Fundstelle) | Konto | Menge | Handy / Katalog-Server | Aufwand | Urteil |
|---|---|---|---|---|---|---|---|---|
| **Laserdaten weiterer Länder** (DGM1, DOM1, LoD2) | was heute NRW/BW haben: Gelände, Bäume an echter Stelle, echte Dachformen | Bayern (geodaten.bayern.de/opengeodata: DGM1, LoD2 CityGML 2×2 km, LAZ 1×1 km, CC BY 4.0); Hessen (hvbg.hessen.de: DGM1/DOM1 per WCS, LoD2 per WFS, „Jede Nutzung … ohne Einschränkung oder Bedingung erlaubt“); Niedersachsen (DGM1 als COG mit STAC-API, DOM1, LoD2; Lizenz CC BY 4.0 [sek]); Rheinland-Pfalz (DGM1, DOM1, LoD2, Laserpunkte, dl-de/by-2.0); Sachsen (DGM1/DOM1 2×2 km, LoD2; dl-de/by-2.0 [sek]); Brandenburg (DGM, bDOM, nDOM-LAZ, LoD2, dl-de/by-2.0); Berlin (LoD2, ALS, dl-de/zero-2.0); Sachsen-Anhalt, Hamburg (LoD2 und LoD3.0-HH, dl-de/by-2.0), Schleswig-Holstein (DGM1, CC BY 4.0); Thüringen nicht geprüft (Captcha) | je Land, meist dl-de/zero, dl-de/by-2.0 oder CC BY 4.0 | nein | wie BW/NRW (10–25 MB je km) | Katalog/Server (Handy wegen Menge nein) | klein je Land (Eintrag in `laserSources`; Kachelschema wie BW) | **nächster Schritt**, eingebaut nur NRW/BW (bestehend) |
| **TrueDOP** | Dächer liegen genau auf dem Grundriss (Dachbild aus dem Luftbild ohne Kippen) | belegt Sachsen-Anhalt (seit Bildflug 2019); NRW fliegt seit 2023 jährlich die halbe Landesfläche als 10-cm-TrueDOP (wms.nrw.de/rssfeeds/content/geobasis/html/302.html); Brandenburg/Berlin [sek] | Landeslizenzen wie oben | nein | wie DOP | auch Handy (WMS) | klein | bei Gelegenheit je Land prüfen |
| **ALKIS tatsächliche Nutzung, ATKIS Basis-DLM** | genauere Landnutzung (Gehölz, Grünland, Acker); Basis-DLM mit `AX_Vegetationsmerkmal` (Hecken, Baumreihen als Linien) | NRW WFS `wfs_nw_alkis_vereinfacht` (Layer `ave:Nutzung`, dl-de/zero); Bayern, Niedersachsen, Sachsen, Hessen, RLP, SH offen; Schleswig-Holstein zusätzlich Knicks/Feldhecken (CC BY 4.0) | je Land | nein | Kreis 45–180 MB | Katalog/Server | mittel (Werte von `bewuchs` nicht geprüft) | Vorschlag |
| **Baumkataster der Städte** | Straßenbäume mit Art, Höhe, Kronendurchmesser | Berlin WFS `baumbestand` (434 765 Straßenbäume, dl-de/zero-2.0, Felder `art_dtsch`, `baumhoehe`, `kronedurch`), Köln, Bonn (CC0), Leipzig, Frankfurt, Hamburg; Stuttgart/Düsseldorf nicht gefunden; Bayern landesweit „Einzelbäume“ (Höhe, ohne Art, CC BY 4.0) | je Stadt | nein | klein | Katalog/Server | klein je Stadt | Vorschlag (Städte) |
| **3D-Meshes** | fotorealistische Gebäude/Bäume | NRW 3D-Mesh (SLPK, 3–181 GB je Gebiet, dl-de/zero), Bayern DOM-Mesh (CC BY 4.0); Berlin-Mesh nicht offen | | nein | sehr groß | nur Server, I3S-Wandlung | hoch | später |
| **BKG bundesweit** | Landbedeckung LBM-DE (1 ha, CC BY 4.0), DGM200 | ganz DE; **Hausumringe und LoD2-DE nicht offen** (nur Bundesbehörden), kein offenes bundesweites DOP (gdz.bkg.bund.de) | | nein | | | | kaum Mehrwert |
| **Kronenhöhen global** | Bäume dort, wo es keinen Laser gibt (Handy-Bau, Ausland) | Meta/WRI (Tolan et al. 2024): ~1 m, Maxar 2017–2020, MAE 2,8 m (Kalifornien/São Paulo), öffentlicher S3-Bucket `dataforgood-fb-data/forests/v1/…/chm/` ohne Konto, CC BY 4.0 (AWS Open Data Registry); ETH (Lang et al. 2023): 10 m, CC BY 4.0 | CC BY 4.0 | nein | 20–70 MB je Kachel | Katalog/Server (Range-Abruf ginge auch vom Handy, verriete aber die Gegend an AWS) | mittel | **Vorschlag mit hoher Wirkung außerhalb NRW/BW** (Baumlage statt Würfel) |
| **Sentinel-2** | Jahreszeit, Feldzustand (gepflügt/grün/abgeerntet), Vegetationsfarbe | weltweit, 10 m, alle 2–5 Tage; Earth Search STAC (earth-search.aws.element84.com/v1, ohne Konto, Szene vom 24.11.2026 gefunden) | „free, full and open access“, Vermerk „Contains modified Copernicus Sentinel data [Jahr]“ (Sentinel Data Legal Notice) | nein | COG | Katalog/Server | mittel | Vorschlag (Farbabgleich Winterluftbild ↔ Jahreszeit) |


## 5 Option (nicht gebaut): Straßenfotos für Fassaden und Schilder

Was weder Vektorkacheln noch Luftbild noch Laser zeigen — Fassaden, Schaufenster, Verkehrszeichen,
Ampeln, Wegweiser-Inhalte —, steht nur in Bildern von der Straße aus. Offene Sammlungen:

| | Mapillary | KartaView | Panoramax |
|---|---|---|---|
| Lizenz der Bilder | CC BY-SA 4.0 | CC BY-SA 4.0 | je nach Instanz CC BY-SA 4.0 / etalab (Frankreich), offene Instanz des OSM-Umfelds |
| Zugang | API v4 mit **Zugangsschlüssel** (kostenloses Entwicklerkonto bei Meta), Abfragegrenzen | API ohne verlässliche Pflege | offene API je Instanz, ohne Konto lesbar |
| Abgeleitete Daten | **„Map features“**: erkannte Verkehrszeichen, Ampeln, Masten, Hydranten mit Lage — als Punktliste nutzbar wie die POI-Ebene | – | Bilder; Erkennung nicht zentral |
| Abdeckung | Städte und Hauptstraßen gut, Feld- und Waldwege lückenhaft, Alter der Bilder sehr gemischt | gering | wachsend, in Deutschland noch dünn |

Was es bringen würde:

- **Schilder und Ampeln** (einfach): die erkannten Verkehrszeichen (Art + Lage) aus Mapillary als
  zusätzliche Punktobjekte, Modelle wie in `PoiLayer` — Ortstafeln, Vorfahrt, Tempo 30, Ampeln an der
  richtigen Stelle. Aufwand mittel; Daten sind Erkennungen mit Fehlern.
- **Fassaden** (schwer): Bildausschnitte je Wand bräuchten Kamerapose, Entzerrung, Auswahl unverdeckter
  Bilder, Belichtungsausgleich und viel Speicher; realistisch nur Fassaden**farbe** je Gebäude (Median
  der Wandpixel) als Ersatz für den gewürfelten Putzton. Aufwand hoch.

Folgen: neues Konto/Token (Mapillary) und ein neuer Host in `PRIVACY.md` und der Positivliste; Abruf
**nur im Katalog- oder Server-Bau**, nie vom Handy mit der Lage einer eigenen Strecke (die Anfrage
verriete Wohnort und Hausrunde). Datenschutz: Gesichter und Kennzeichen sind bei Mapillary
automatisch unkenntlich gemacht, aber nicht fehlerfrei — Bildausschnitte in einer veröffentlichten
Welt sind eine Weitergabe; Farbwerte oder Schildarten (keine Bilder) sind unkritisch. Lizenz: CC BY-SA
verlangt Namensnennung und für abgeleitete Bilder „Weitergabe unter gleichen Bedingungen“ — Texturen
aus Fotos würden das Paket teilweise unter CC BY-SA stellen; reine Fakten (Lage und Art eines
Schilds) sind weniger heikel. Kosten: keine Gebühren, aber Abfragegrenzen und Rechenzeit im Bau.
