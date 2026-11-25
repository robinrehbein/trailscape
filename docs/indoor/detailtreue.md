# Indoor — Detailtreue: sieht die Strecke aus wie in echt?

Stand: 25.11.2026, Branch `claude/grafik-detailtreue` (auf `main` + `claude/indoor-weltqualitaet`,
Welt-Version `indoor-world/12`).

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

## 4 Option (nicht gebaut): Straßenfotos für Fassaden und Schilder

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
