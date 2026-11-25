# Indoor — Straßenausstattung (Fahrbahn und unmittelbarer Rand)

Stand: 25.11.2026, Branch `claude/grafik-strassenausstattung`. Ziel: Was der Fahrer aus
Lenkerhöhe (rund 1,2 m) ständig vor sich sieht, soll weniger künstlich wirken — Markierung,
Fahrbahnrand, Ausstattung, Unregelmäßigkeiten der Fahrbahn. Abgeleitet nur aus Wegklasse,
Breite, Belag, Landnutzung und Geometrie der Strecke; was die Daten nicht hergeben, wird nicht
erfunden (siehe „Bewusst nicht gemacht“).

**Welt-Version:** `WORLD_BUILDER_ID` ist **nicht** angehoben. Beim Zusammenführen **nötig** (jede
Welt mit Kraftverkehrsstraßen ändert sich: Linien, Bankettkante, Flicken, Leitpfosten, Planken),
ebenso die Katalogversionen.

## 1. Bestandsaufnahme (vorher)

| Teil | Wie gebaut | Was aus Lenkerhöhe künstlich wirkte |
|---|---|---|
| Querschnitt | `RouteLayer`: Band aus drei Eckpunkten je Station (links, Mitte, rechts), Dachprofil 2,2 % (Asphalt/Beton), 1,8 % (Pflaster), 1,4 % (ungebunden); Breite aus `SurfaceSection.widthM`, sonst Vorgabe des Belags | — (Dachprofil stimmt) |
| Rand | je Seite `EdgeStyle`: BANKETT (0,6 m Schottertextur, 3,5 cm tiefer) an Kraftverkehrsstraßen ab 3,5 m außerorts, URBAN (Bordstein 12 cm, Gehweg 1,5 m) in Wohn-/Gewerbeflächen, SOFT (Fransen) an Feld-/Waldwegen, PLAIN (Holz); danach deckender Übergangsstreifen mit Auslauf, Mulde aus `CarvedGround` | **Messerkante** Asphalt → Bankett: eine gerade, scharfe Linie über Kilometer |
| Kreuzungen | `JunctionCorners`: ausgerundete Ecken (R 4/5 m), im Ort Bordstein im Bogen; Ränder werden an Straßenbändern geschnitten (`PlanMask`) | Randlinie endet hart an jeder Einmündung |
| Brücken | `PropsLayer`: Brückenbauwerk (Platte, Pfeiler, Brüstung), Geländer auf der Strecke | Landstraßenbrücke ohne Schutzplanke |
| Markierung | nur Vertexfarbe (`x_mat_route_plain`), überall gleiches Grauweiß; Mittellinie **immer unterbrochen 4 m / 8 m** (ab 6 m Breite), Randlinien durchgezogen (ab 5,5 m) | steril; unterbrochene Linie auch in engen Kurven und Kehren; keine Warn-, Warte-, Haltelinien; Raster entspricht keiner Regel (RMS außerorts 6/12, innerorts 3/6) |
| Ausstattung | Leitpfosten **alle 50 m** beidseits, auch in Kurven; Laternen, Ortstafel, Zeichen 306 | Leitpfosten in Kurven zu weit auseinander |
| Fahrbahn | Asphalttextur 4 × 16 m mit eingemalten Flicken und Rissen | **Wiederholung alle 16 m**, sonst völlig gleichförmig; Ortsdurchfahrt ohne Schachtdeckel, Abläufe, Rinne |

## 2. Was jetzt gebaut wird

Neue Dateien (alle in `core/…/indoor/world/`):

| Datei | Inhalt |
|---|---|
| `RoadMarkings.kt` | `MarkingPlan`: welche Mittellinie wo liegt (Leitlinie, Warnlinie, Fahrstreifenbegrenzung), Randlinien, innerorts/außerorts, Sichtweite — je Strecke einmal gerechnet, in jeder Kachel gleich |
| `RoadDecals.kt` | Abziehbilder auf der Fahrbahn (Knoten `route`): Linien, Warte-/Haltelinien, Radfahrstreifen, Bröselkante, Rinne, Abläufe, Schachtdeckel, Flicken, Risse |
| `RoadDecalTextures.kt` | drei kleine erzeugte Texturen (Markierungsfarbe, Bröselkante, Atlas Deckel/Ablauf) |
| `RoadsideLayer.kt` | Leitpfosten (Abstand nach Kurvenradius) und Schutzplanken (Brücken, hohe Böschungen) im Knoten `props` |
| `RealRoadside.kt` | Schnittstelle zu echten Daten (Detailtreue), siehe Abschnitt 4 |

Eingriffe in geteilte Dateien (klein, additiv):

- `RouteLayer.kt`: `build()` hält Budget für die Abziehbilder frei und zeichnet sie nach der Fahrbahn
  (4 Zeilen); die alten Linien (`edgeLine`, `centreDashes`) sind entfernt.
- `PropsLayer.kt`: `guidePosts()` ruft `RoadsideLayer` (die alte Leitpfosten-Funktion ist entfernt).
- `WorldInternals.kt`: `MapData.roadside()` mit Vorgabe `RealRoadside.NONE` (1 Methode).

### 2.1 Markierung

Regeln sinngemäß nach den RMS, nur aus den Daten abgeleitet (`MarkingPlan`):

| Linie | Wo | Maße |
|---|---|---|
| Leitlinie | Kraftverkehrsstraße mit Asphalt/Beton; außerorts ab 5,5 m (ab `tertiary`) bzw. 6,0 m (Gemeindestraße), innerorts nur Durchgangsstraßen (ab `tertiary`) ab 6,0 m | außerorts 6 m / 12 m, innerorts 3 m / 6 m, 12 cm |
| Fahrstreifenbegrenzung | Sichtweite (eine der beiden Richtungen) unter 400 m (ab `secondary`), 300 m (übrige), 70 m innerorts; Lücken unter 110 m (innerorts 40 m) geschlossen, an beiden Enden 10 m länger | 12 cm durchgezogen |
| Warnlinie | vor und hinter jeder Begrenzung | 6 m / 3 m über 27 m (innerorts 3 / 1,5 über 18 m) |
| Randlinie | außerorts ab 6,0 m (ab `tertiary`), Gemeindestraße ab 6,5 m; nie innerorts | 12 cm, 25 cm vom Rand; an Einmündungen **unterbrochen** (1,5 m / 1,5 m, Annahme) |
| Wartelinie (Z 341) | wo die Strecke (Kraftverkehrsstraße, Rang ≥ 2) auf eine Straße höheren Rangs ab `tertiary` trifft — Zufahrt vor dem Knoten (rechte Hälfte) und Gegenrichtung dahinter (linke Hälfte); Linien der Strecke enden an der Kante der kreuzenden Straße | Querstriche 0,5 × 0,5 m, Lücke 0,25 m |
| Haltelinie (Z 294), Radfahr-/Schutzstreifen | **nur aus echten Daten** (`RealRoadside`) | Halt: 0,5 m durchgezogen; Schutzstreifen 1 m / 1 m bei 1,5 m, Radfahrstreifen 25 cm bei 1,85 m |

**Sichtweite:** Augen- und Zielhöhe 1 m; die Sichtlinie darf die Fahrbahn (+ 25 cm) nicht schneiden
(Kuppen) und seitlich nicht weiter ausweichen als der freie Raum neben der Fahrbahn: 3 m, wo an der
Kurveninnenseite Wald, Wohn- oder Gewerbefläche liegt, sonst 20 m. Gebäude, Hecken, einzelne Bäume
kennt die Rechnung nicht. Die Schwellen sind Annahmen in der Größe von Überholsichtweiten, keine
Bemessung.

**Abnutzung:** `x_mat_route_marking` (MASK, Schwelle 0,5) mit erzeugter Textur 32 × 256: gebrochenes
Weiß mit Schmutz und Körnung, längs gestreckte abgefahrene Stellen (Deckung 0) und kleine
Abplatzungen; die Vertexfarbe dunkelt je Strich etwas verschieden ab (linear 0,74–0,84, leicht
gelblich). Kein reines Weiß.

### 2.2 Querschnitt und Rand

- **Bröselkante** (alle Bankett-Seiten): Streifen von 10 cm auf dem Asphalt bis 24 cm auf das Bankett,
  mit Knick an der Fahrbahnkante; Textur `x_mat_route_crumble` (MASK): unregelmäßige Asphaltkante mit
  Ausbrüchen, außen eingestreuter Splitt und Brocken. Die gerade Messerkante ist damit gebrochen.
- **Pflasterrinne** innerorts am Bordstein (32 cm, Pflastertextur, je Ort fest in gut der Hälfte der
  Orte), darin **Straßenabläufe** etwa alle 30 m; **Schachtdeckel** (Ø 62 cm) etwa alle 42 m in der
  Fahrbahnmitte (nicht unter der Mittellinie, nicht auf Flicken).
- Dachprofil, Bankett, Bordstein, Gehweg, Übergangsstreifen und Mulde bleiben wie sie waren.

### 2.3 Unregelmäßigkeiten der Fahrbahn

- **Flickstellen** auf Asphalt, je 55-m-Block mit 25 % (ab `secondary`), 35 % (innerorts), 45 % (sonst):
  Spurrinnenflicken (3–10 m in einer Rollspur), Leitungsgraben (0,8–1,6 m quer über eine oder beide
  Hälften), Flächenflicken (6–18 m); frisch dunkler oder alt grauer, eigene Stelle der Asphalttextur,
  mit **Fugenverguss** (4 cm, dunkel) an allen Rändern. Material `x_mat_route_patch` (dieselbe
  Texturdatei wie die Fahrbahn).
- **Vergossene Risse** außerorts, je 80-m-Block mit 35 %: Längsnaht neben der Mitte oder Randriss
  0,75 m innerhalb des Rands, 6–25 m, leicht geschlängelt, 3 bzw. 6 cm breit.

### 2.4 Leitpfosten und Schutzplanken (`RoadsideLayer`)

- **Leitpfosten** an Kraftverkehrsstraßen außerorts mit Bankett, 0,75 m neben dem Rand. Abstand nach
  Radius (über ±15 m kleinster Radius): 50 m gerade, 25 m unter R 300 m, 16,7 m unter R 150 m,
  12,5 m unter R 60 m (Teiler von 50 m; Annahme in Anlehnung an die Praxis). Nicht an Planken, nicht
  in Einmündungen.
- **Schutzplanke** (Holm W-Profil 0,44–0,75 m, Pfosten alle 4 m, 0,55 m neben dem Rand) an
  Kraftverkehrsstraßen außerorts:
  - **auf Brücken** der Karte mit 20 m Vorlauf, auf der Brücke vor dem Geländer (ohne Pfosten);
  - an **hohen, steilen Böschungen**: Gelände 6 m neben dem Rand > 3 m tiefer oder 10 m daneben
    > 4,5 m (mindestens 15 m, Lücken bis 30 m geschlossen);
  - an beiden Enden 12 m **Absenkung** in den Boden.

## 3. Sichtweiten-Stufen und Dreiecksbudget

Alles liegt in **Stufe 0** (wie Fahrbahn und Kleinteile; ab Stufe 1 gibt es weder Fahrbahn noch
Kleinteile). Budget je Kachel (MEDIUM; LOW ½, HIGH ×2): Fahrbahn 3 000, Kleinteile 3 000 Dreiecke —
**unverändert**. Die Abziehbilder zählen zur Fahrbahn: `RouteLayer` hält für sie bis zu 30 % frei
(geschätzt bei feinster Teilung) und wählt die Vereinfachungsstufe der Fahrbahn dafür; was danach
nicht passt, entfällt gruppenweise in umgekehrter Priorität (Risse → Flicken → Ort → Bröselkante →
Warte-/Haltelinien → Randlinien → Mittellinie). Leitpfosten und Planken zählen zu den Kleinteilen
und werden vor Laternen, Ortstafeln, Zäunen usw. verbraucht (Reihenfolge wie bisher die Leitpfosten).

Zeichenaufrufe: bis zu fünf Primitive mehr je Kachel der Stufe 0 (`x_mat_route_marking`, `…_crumble`,
`…_patch`, `…_decal`, `…_gutter`), typisch außerorts drei, innerorts vier. Leitpfosten und Planken
liegen im bestehenden Primitiv `x_mat_props`.

MESSWERTE: siehe Abschnitt 6.

## 4. Schnittstelle zu echten Daten (Detailtreue)

`claude/grafik-detailtreue` zeichnet im Katalog-/Serverbau Leitplanken, Zäune, Laternen aus echten
OSM-Daten (`OsmDetail` → `OsmDetailMap.osmFeatures`, `OsmDetailLayer`). Damit nichts doppelt steht:

- `MapData.roadside(): RealRoadside` (Vorgabe `RealRoadside.NONE`). Ohne volle Daten gelten die Regeln
  überall.
- `OsmDetailMap` überschreibt nach dem Zusammenführen `roadside()` und reicht `barrier=guard_rail`
  als Linien durch (Skizze, ungebaut):

```kotlin
// in OsmDetailMap (OsmDetail.kt)
override fun roadside(): RealRoadside = object : RealRoadside {
    override fun guardRails(minE: Double, minN: Double, maxE: Double, maxN: Double) =
        osmFeatures(minE, minN, maxE, maxN).filter { it.kind == OsmKind.GUARD_RAIL }.map { it.pts }
}
```

- Wirkung: Regel-Planke **und** Leitpfosten entfallen auf der Seite, wo eine echte Planke höchstens
  4 m neben ihrer Stelle liegt; die echte Planke zeichnet `OsmDetailLayer`. Mit `authoritative = true`
  (Korridor vollständig erfasst) entfallen alle Regel-Planken.
- Vorbereitet, aber von keiner Quelle geliefert: `controls()` (`highway=stop|give_way|traffic_signals`
  → Halte-/Wartelinie an der echten Stelle) und `cycleLanes()` (`cycleway:*=lane` → Schutz-/Radfahrstreifen).
  Die Overpass-Abfrage der Detailtreue müsste dafür um diese Schlüssel erweitert werden.

## 5. Bewusst nicht gemacht

- **Querneigung in Kurven**: würde die Innenkante bis rund 10 cm absenken — dort plant `CarvedGround`
  den Boden knapp unter der Fahrbahn (Nachweis „kein Dreieck sticht durch“), und der Fahrer fährt auf
  der Mittellinie (`track.u`). Braucht Gelände, Fahrerhöhe und Kamera gleichzeitig; eigener Schritt.
- **Haltelinien, Radfahr- und Schutzstreifen ohne Daten**: Die Vektorkacheln kennen weder Stoppschilder
  noch `cycleway=lane`; nach Regel erfunden wäre falsch. Über `RealRoadside` vorbereitet.
- **Entwässerungsgraben**: Gelände/Mulde gehört `CarvedGround` (geteilte Datei, Nachweis gegen
  Durchstechen); nicht angefasst.
- **Leitpfosten- und Plankenregeln wörtlich nach Regelwerk** (RPS, Leitpfosten-Richtlinie): Abstände
  und Schwellen sind Annahmen, siehe oben.

## 6. Messwerte und Bilder

(folgt)

## 7. Auf dem Gerät zu prüfen

(folgt)
