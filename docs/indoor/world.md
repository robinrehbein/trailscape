# Indoor — Slice indoor-world (Weltbau und Entwicklerwerkzeug)

Stand: 23.11.2026, Branch `claude/indoor-world`. Vertrag: `docs/indoor.md`
(Abschnitte 7.1–7.5, 8, 9).

Datenstufe „Laser" (DGM1, nDOM, LoD2 aus NRW; nur Entwicklerwerkzeug):
`docs/indoor/lidar.md`.

## Stand

| Teil | Stand |
|---|---|
| Einstieg `IndoorWorldEntry.kt` | **ohne Platzhalter.** `createWorldBuilder()` → `WorldBuilderImpl(FullWorldParts)`, `createGeoPipeline` → Positivliste, Plattencache, Drosselung; Register `aerialSources` mit NRW, Sachsen, Thüringen, Brandenburg/Berlin, Baden-Württemberg (seit 24.11.2026, Abschnitt „Luftbild Baden-Württemberg“) |
| Voranschlag `plan` | ohne Netz: Kacheln, Anfragen je Art, Download, Platz, Hosts, Luftbild-Dienste |
| Bau `build` | Höhen (Terrarium z13/z11) → Ursprung → Vektorkacheln (TileJSON, z14/z12) → Brücken, Belag aus Vektorkacheln, falls die Strecke keinen hat → Streckenband → endgültige Planung → Kacheln parallel (bis 4 Threads, nach `Runtime.maxMemory()` begrenzt: bei 256 MB Heap 2), atomar geschrieben, `manifest.json` zuletzt. Fortsetzen nach Abbruch (fertige Kacheln bleiben). `isCancelled()` wird mindestens alle 50 ms gefragt |
| Testwelt `buildTestWorld` | ohne Netz, 3 km Runde mit allen Belagsklassen, Dorf, Laub- und Nadelwald, Felder, Teich, Bach mit Holzbrücke; deterministisch (Byte-gleich) |
| Streckenband | Lage geglättet (≤ 1,2 m), Höhe vom Gelände (σ 12 m), Brücken überbrückt, Steigung ≤ 24,5 %, Laufmeter unverändert |
| Gelände | `CarvedGround`: gedämpftes Höhenmodell, Straßen quer eben, Strecke mit Bankett und Böschung (Nachweis im KDoc: kein Dreieck sticht durch die Fahrbahn), Wasser abgesenkt; Stufe 0 Gitter 4 m (MEDIUM), ab Stufe 1 4×4 Teilflächen, Schürzen |
| Bodentextur | Luftbild unverändert (JPEG Baseline, sonst PNG neu angefragt) oder aus Landnutzung erzeugt; Leerbild-Erkennung |
| Fahrbahn `route` | je Belag kachelnde Textur, weicher Belagswechsel, Ränder nach Belag und Umgebung (Bankett, Bordstein mit Gehweg, Graskante), Böschungsstreifen, Markierung auf Landstraßen |
| Straßen `roads` | übrige Straßen und Wege der Karte, flach auf dem Gelände |
| Gebäude `buildings` | Grundriss × Höhe, Satteldächer/Walmdächer, Anbauten, Fassadenatlas, Stufe 1 grob. Dachfarbe je Gebäude (deterministisch): Ziegelrot, Rotbraun, Braun, Anthrazit, Grau, wo möglich aus der Luftbildfarbe; Grau aus dem Luftbild wird bei Ziegeldächern teils als Rotbraun/Braun gedeutet. Die Vektorkacheln nennen keine Gebäudeart (kein church/warehouse); große Grundrisse (> 800 m²) werden nur in Gewerbe- und Landwirtschaftsflächen zur Wellblechhalle, sonst zu Putz-/Ziegelbauten mit Fenstern und Ziegeldach (Traufe 6,6–9,6 m, wenn die Daten keine Höhe nennen) |
| Bäume `trees` | **Blattkarten** (seit Abschnitt „Vegetation“): sechs Arten (breiter/schlanker Laubbaum, Feldbaum, Fichte aus Astquirl-Schürzen, Kiefer, Busch) bis 70 m neben der Strecke, Kronen aus verdrehten Alpha-Karten (prozeduraler Atlas) mit gebogenen Normalen, drei Stufen vom Weg aus vergeben; Gras-/Farnbüschel am Wegrand; dahinter und in Stufe 1 Waldkörper mit Kronenmosaik und Randbäumen als Kreuzbild |
| Wasser `water` | **neu:** Wasserspiegel auf Uferhöhe über den Wasserflächen der Karte |
| Kleinteile `props` | Leitpfosten, Laternen, Ortstafel, Weidezäune, Holzpolter, Bänke, Wegweiser |
| Detailstufen | 3 Stufen (256 m / 1 km / 4 km), `show` nach Laufmetern, Budget je Qualität nachgerechnet (steht im Manifest, `checkWorld` meldet Überschreitungen; der Bau bricht deswegen nicht mehr ab) |
| Prüfung | `checkWorld` (Namen, Erweiterungen, Texturformate, Budget, lückenloses `show`) im Werkzeug und als `:core`-Test |
| Modelle | `rider.glb`, `ghost.glb` (selbst erzeugt, `pedal`/`roll`) unter `app/src/main/assets/indoor/models/` |
| Werkzeug | `:indoor-dev:run … build|plan|testworld|check|models`; Betrachter `viewer/` (three.js) mit `shots.mjs` |

Tests: `./gradlew :core:test --tests 'de.trailscape.core.indoor.world.*'`
(184 Tests, u. a. `FullWorldPartsTest`: Testwelt über den Einstieg mit allen
Schichten, deterministisch; Bau mit (falschen) Geodaten besteht die Prüfung;
Abbruch während des Kachelbaus).

### Gesehene Bilder (`docs/indoor/bilder/world/`)

Gebaut mit `:indoor-dev:run … build` (MEDIUM, echte Geodaten) bzw.
`testworld`, gerendert mit `shots.mjs` (three.js, headless Chromium,
SwiftShader), 1600 × 900.

| Bild | Was zu sehen ist |
|---|---|
| `dorf-altenberg-fachwerk.jpg` | Altenberg (NRW), Start: Fachwerkhaus direkt am Weg, Laubbäume, großer Putzbau mit Fensterreihen dahinter |
| `dorf-altenberg-pflaster.jpg` | Altenberg, Kopfsteinpflaster am Ortsrand, Wegweiser, bewaldeter Hang im Hintergrund |
| `wald-tharandt.jpg` | Tharandter Wald (Sachsen), Schotterweg durch Mischwald mit gestuften Fichten, Luftbild von GeoSN (Sonne 55°) |
| `wald-tharandt-luft.jpg` | dieselbe Stelle aus der Drohne: Einzelbäume entlang des Wegs, dahinter die Waldkörper |
| `offen-altenberg.jpg` | Dhünntal, offene Wiese mit Weidezaun, Laubbaumreihe, Hügel mit Waldkörpern |
| `uebersicht-altenberg.jpg` | Drohne über dem Kloster Altenberg: Klostergebäude, Bäume aus dem Luftbild, Waldkörper an den Hängen |
| `testwelt-dorf.jpg` | Testwelt: Pflasterstraße mit Gehweg, Häuser, Laternen |
| `testwelt-uebersicht.jpg` | Testwelt aus der Luft: Felder, Wald an der Strecke, Waldkörper |

Was noch nicht stimmt (gesehen und bewusst offen gelassen):

- **Waldkörper:** Die Ränder fallen jetzt über eine Zelle bis zum Boden ab
  (keine Wände mehr), folgen im Grundriss aber dem Zellenraster (8 m in
  Stufe 0, 32 m in Stufe 1). In Stufe 1 ist der Abfall über 32 m aus der
  Drohne noch als steile Kante zu sehen; das Kronendach ist eine Hügeldecke,
  keine Einzelkronen.
- **Wald dunkel:** Unter geschlossenem Kronendach ist der Weg im Betrachter
  sehr dunkel (dort gibt es nur ein schwaches Umgebungslicht); in Filament
  hängt das am `IndirectLight` von indoor-render.
- **Bäume:** (überholt, siehe Abschnitt „Vegetation“) vier Arten mit runden
  Kronen, Körper ohne Laubkarten; keine Sträucher, kein Unterholz.
- **Dächer:** Große Gebäude mit grauem Luftbild (z. B. Schieferdächer des
  Klosters Altenberg) bleiben grau — das ist dort echt.
- Wo das Luftbild dunkle Flächen hat (Schatten, nasse Wiesen), setzt die
  Kronenschätzung gelegentlich Bäume in offene Flächen.

## Sichtbare Qualität (24.11.2026, Branch `claude/indoor-quality`)

Anlass: Rückmeldung des Gründers nach der ersten Fahrt — „an manchen Stellen
ruppig und kantig“, Maßstab „wie Zwift, gern besser“. Vorgehen: drei echte
Welten gebaut (Altenberg kurz, Tharandt km 2–6, Katalog `alpe-dhuez`), aus
Verfolger-, Lenker- und Drohnenkamera an Kurven, Kuppen, Waldrand, Ort,
Kreuzungen und Kachelgrenzen gerendert, jedes Bild angesehen, Ursache im
Code gesucht, behoben, dieselben Stellen neu gerendert. Bildpaare:
`docs/indoor/bilder/quality/` (`…-vorher.jpg` / `…-nachher.jpg`, gleicher
Betrachter und Blickpunkt): `altenberg-pflaster-laubwald`,
`altenberg-fichten-weide`, `altenberg-strasse-hang`, `altenberg-wegrand-lenker`,
`altenberg-waldkoerper-drohne`, `tharandt-forststrasse`, `tharandt-kreuzung`,
`alpe-dhuez-kehre-drohne`, `kreisverkehr-fahrer`, `kreisverkehr-drohne`.
Ein unabhängiger Unter-Agent hat die Bilder gegen „Zwift oder besser“
beurteilt; seine Hauptpunkte (dunkle Fichtenwände, flache Waldscherben) sind
als Nr. 11/12 eingearbeitet, die übrigen (Detailtextur über dem Luftbild,
Laubkarten) brauchten eigene Shader bzw. eine Vertragsänderung.

**Welt-Version:** `WORLD_BUILDER_ID` = `indoor-world/10` (vorher `/9`, `/8`, `/7`, `/6`, `/5`, `/4`, `/3`, `/2`, `/1`). Sie geht
in die Welt-ID ein — selbst gebaute Welten der alten Fassung werden nicht mehr
gefunden und neu gebaut (die alten bleiben in der Weltverwaltung löschbar).
Katalog: `version` aller Strecken in `catalog-src.json` auf 2. Version 10
(24.11.2026, Branch `claude/indoor-laser-fixes`): Einmündungen im Kurvenscheitel,
Baumkronen, Waldboden unter dem Kronendach, Grasstreifen an offenen Streckenenden
(`docs/indoor/lidar.md`, Abschnitt 9); alle 14 Katalogeinträge um eins angehoben.

### Mängel, Ursache, Stand

| # | Mangel (gesehen) | Ursache im Code | Stand |
|---|---|---|---|
| 1 | Laubkronen facettiert, „Polyeder“ mit harten Licht/Schatten-Flächen | `TreeModels.blob`: 10×6 Segmente, starke Verbeulung (±0,32 hochfrequent), Normalen nur geglättet; Knick der Abflachung bei `uy = −0,2` | **behoben:** 14×8 + 6 Nebenkörper (nah), 11×6 + 1 (mittel), 8×5 (fern); Verbeulung ±0,26/0,06; Normalen zu 60 % vom Kronenmittelpunkt aus (`fluffNormals`) — die Krone wird wie ein weicher Körper beleuchtet; Abflachung stetig |
| 2 | Fichten aus gestapelten Kegeln mit Sägezahnrand: helle Streifen, schwarze Bodenscheiben | `TreeModels.cone`: abwechselnde Randradien, flache Unterseite je Kegel | **behoben:** `spruce` — ein Drehkörper, Astetagen als Profil (Unterseite dunkel, Rand hängend, eingezogen), Rand stetig verbeult, Normalen nach außen gemischt; heller Grundton |
| 3 | Waldkörper als Kästen mit Steilwand, Rand im Zellenraster, spitze „Zelte“ und dunkle Rauten in offenen Flächen | `TreesLayer.emitVolume`: Abfall über eine Zelle, Zufall je Gitterpunkt (±2,5 m), Einzelzellen aus der Kronenschätzung | **behoben:** Anstieg über zwei Zellen (Anteil mit Nachbarn geglättet), Gitterpunkte aus Welt-Gitterzahlen verschoben (kein Rasterrand), stetige Wellen, flachere Normalen; Waldzellen brauchen ≥ 3 Waldnachbarn |
| 4 | Wegrand als gezackter Sägezahn (Fransenkante) | `RouteTextures.fringe`: Deckung springt über ±0,035 der Breite, Halmspitzen mit Amplitude 1,3 | **behoben:** Übergang −0,16…+0,12, Halmspitzen 0,45 |
| 5 | Heller, harter Saum neben der Fahrbahn (Straßenpixel des Luftbilds, Versatz OSM ↔ Luftbild) | Randstreifen deckt in Siedlung/Acker nur 1,0/1,2 m | **behoben:** 1,8/2,0 m deckend, Auslauf 4,4/5,0 m |
| 6 | Eckige Löcher im Rand an Einmündungen und wo die Strecke sich selbst nahe kommt | Rand wird je Eckpunkt ausgeblendet, Stationen dort nur dreifach geteilt (bzw. zusammengelegt) | **teilweise:** dort jetzt mit der feinsten Teilung (`maxSub`), nie zusammengelegt. An spitzen Einmündungen (Altenberg km 1,875) liegt weiterhin ein Stück Luftbild zwischen Strecke und Nebenstraße |
| 7 | Gelände fern (Stufe 2: 146-m-Zellen) mit Facetten | Budget `TileBudget.terrain` | **offen, bewusst:** Mehr Gelände in Stufe 1/2 (8 000/6 000 Dreiecke) verkleinerte über die Planung den Korridor der Stufe 0 (21 → 16 Kacheln) — schlechter Tausch. Die meisten „kantigen Hügel“ waren Waldkörper (Punkt 3) |
| 8 | Böschungen am Steilhang (Alpe d'Huez, Kehren): gestrecktes Luftbild, graue Flächen | Planare Projektion des Luftbilds; ohne eigenen Shader (Vertrag 7.7) kein Triplanar/Detail | **offen** |
| 9 | Wald dunkel | Luftbild unter Kronen dunkel, Umgebungslicht | **offen** (Darstellung, siehe render.md); Fichten heller getönt |
| 10 | Weißes Dreieck neben dem Weg (Altenberg km 0,1, Tharandt-Kreuzung) | Luftbild-Pixel (weiße Fläche) unter einer Randlücke | **offen** (mit Punkt 5 kleiner, an der Tharandter Kreuzung laut Review eher größer) |
| 11 | Nahe Fichten als dunkle Wand vor der Kamera (Review) | Fichten bis 21 m direkt am Weg, Krone ragt über die Fahrbahn; Etagen durch weiche Normalen verwischt | **teilweise:** Fichten am Weg so klein, dass die Krone nicht über die Fahrbahn ragt (sonst weg), Etagen mit eigener Schattierung, helle Spitzen. Im Tharandter Wald steht weiterhin eine dunkle Fichtenreihe rechts |
| 12 | Flache dunkle Scherben in lichtem Wald (Alpe d'Huez, Drohne) | Waldkörper der Stufe 0 mit 32–64-m-Zellen, wenn das Budget knapp ist | **teilweise:** in Stufe 0 nur noch bis 16-m-Zellen, sonst kein Waldkörper; in Stufe 1 bleiben einzelne flache Flächen |
| 13 | **Kreisverkehr „komisch“ (Gerät)**: Mittelinsel = Luftbild mit weißem Grasauslauf, darauf ein Weidezaun | Kein Kreisverkehr-Wissen: Ring nur als Straße, Insel nicht gezeichnet, Zäune nach Landnutzung „Wiese“ | **behoben:** `Roundabouts` erkennt geschlossene, runde bis ovale Ringe (1 : 2,5) aus den Vektorkacheln, auch aus mehreren Stücken; `RoadsLayer` zeichnet die Insel als Grasfläche 14 cm über dem Boden (bzw. 10 cm über der Fahrbahn), leicht gewölbt, mit Bordstein bis unter das Gelände; keine Zäune auf der Insel. Mini-Kreisel (nur Punkt in OSM) fehlen in den Vektorkacheln |
| 14 | **Flackern (Gerät)** | (a) Straßen 4 cm über dem *wahren* Boden — das 4-m-Geländenetz spannt über schmale Wege und sticht durch; (b) gleichrangige Straßen an Knoten in derselben Ebene; (c) weiche Kantenstreifen koplanar auf der Nachbarfahrbahn; (d) einmündende/querende Wege nur 1,4–3,5 cm über/unter der Strecke; (e) Kamera `near` 0,3 m | **behoben (weitgehend):** Straßen und Wegränder über dem *gezeichneten* Netz (`terrainMeshHeightAt`, inkl. Wölbung zwischen Stationen), Grundabstand 5 cm; Versatz nach Richtung (0–8 mm) unter dem Rangschritt (12 mm); Kantenstreifen 2 cm unter der Fahrbahn; über/unter der Strecke 3/5 cm; `near` 0,5 m. Gemessen mit `indoor-dev overlaps` (s. u.) |

**Flackern messbar machen:** `coplanarOverlaps` (`GlbOverlap.kt`) sucht in einer
Kachel-GLB flache Dreiecke verschiedener Primitive, die sich im Grundriss
überdecken und in derselben Höhe (< 4 mm) liegen; `indoor-dev overlaps <welt>`
listet sie je Materialpaar für alle Kacheln der Stufe 0. Der Test
`QualityTest` verlangt für die Testwelt **null** deckende Treffer (weiche
Ränder mit Deckung null am Außenrand, Ufer und Belagsübergänge ausgenommen).

| Welt | koplanar gesamt vorher → nachher | davon deckend vorher → nachher |
|---|---|---|
| Altenberg kurz | 1 441 → 638 | 330 → 40 |
| Tharandt km 2–6 | 761 → 234 | 119 → 13 |
| Alpe d'Huez | – | 469 → 98 |
| Kreisel Bergisch Gladbach (2,1 km, BRouter) | 495 → 401 | 71 → 60 |
| Testwelt | – | 0 |

Rest an der Kreisel-Strecke: Gelände unter der Fahrbahn an einer Brücke
(dort wird nicht eingeebnet) und Ränder, deren Deckung dort null ist. Nicht in
Filament geprüft — der Nachweis ist geometrisch und im Betrachter.

Nicht geändert: Gebäude (kantige Grundrisse sind richtig; Dachkanten und
Fassaden waren auf den Bildern unauffällig), Höhenmodell (bereits bikubisch
und gedämpft, keine Treppen gesehen), Fahrbahn-Mittellinie (Catmull-Rom,
Kehren rund — auf den Drohnenbildern der Alpe d'Huez sauber).

### Messwerte

Schauinsland (Katalog, 15,7 km, 157 Kacheln), `-Xmx256m`, 2 Threads, Geodaten
aus dem Cache (ein Aufwärmlauf vorher):

| | Bauzeit | Heap nach GC (max) | Dreiecke gleichzeitig | Texturen | Platte |
|---|---|---|---|---|---|
| vorher | 55 s | 227 MB | 555 981 | 160 MB | 12,7 MB/km |
| nachher | 38 s | 230 MB | 559 775 | 160 MB | 12,2 MB/km |

Kein Full-GC, kein OOM; die kürzere Zeit ist eher Plattencache als Gewinn.
Altenberg kurz: 460 k Dreiecke (vorher 448 k), 15,7 MB/km (vorher 16,0);
Tharandt km 2–6: 490 k (vorher 452 k), 12,5 MB/km. Budgets eingehalten.
(Die Messung lief vor den letzten Änderungen an Fichten und Straßenhöhen; die
wirken auf Dreiecke und Speicher nur am Rand.)

### Auf dem Gerät zu beurteilen

- Kronen und Fichten unter Filaments Licht (Umgebungslicht aus SH, ACES): sind
  die weichen Normalen dort genauso ruhig?
- 4× MSAA und DPCF-Schatten: Bildrate auf dem Handy des Gründers (MEDIUM),
  Speicher der Schattenkarten (3 × 2048²).
- Fernsicht ohne anisotrope Filterung (gltfio setzt sie nicht): Fahrbahn in
  20–50 m Entfernung unscharf? Der Betrachter rendert jetzt ebenfalls ohne.

## Vegetation (24.11.2026, Branch `claude/indoor-foliage`)

Anlass: Die Welt wirkte weiter „polygonmäßig"; größter Mangel waren Bäume und
Bewuchs (Laubkronen als Klumpen weniger Polygone, Fichten als gestapelte
Drehkörper, Wald fern als glatte Hügelkörper, kein Unterholz, nichts am
Wegrand). Maßstab „Zwift oder besser". Geändert: `TreeModels.kt`,
`TreesLayer.kt`, neu `FoliageAtlas.kt` (+ `FoliageAtlasTest`), im Betrachter
die MASK-Darstellung (`viewer.js`). Keine Vertragsdatei geändert,
`WORLD_BUILDER_ID` und Katalogversionen **nicht** angehoben (beim
Zusammenführen nötig: die Bäume ändern sich in jeder Welt).

Bildpaare (gleicher Blickpunkt, Betrachter, 1600 × 900 → JPEG):
`docs/indoor/bilder/foliage/<name>-vorher.jpg` / `-nachher.jpg` —
`altenberg-park-lenker`, `altenberg-waldrand-fichten`,
`altenberg-offen-einzelbaeume`, `altenberg-laubwald-lenker`,
`altenberg-hang-drohne`, `tharandt-forstweg`, `schauinsland-bergwald`,
`schauinsland-tal-fernsicht`, seit der zweiten Runde auch `balkantrasse-laser-lenker`. Die Dateien zeigen den Stand der zweiten Runde
(vorher = Stand nach dem Merge mit der Laser-Stufe, siehe unten); beide mit demselben
Betrachter gerendert.

### Wie es gebaut ist

- **Blattkarten.** Kronen sind viele kleine, verdrehte Vierecke mit einem Motiv
  aus dem Atlas und Alpha-Ausschnitt (`x_mat_tree_cards`: `MASK`, Schwelle 0,5,
  **einseitig**, jede Karte mit beiden Wicklungen — siehe „Zweite Runde“). Stamm und
  (nah) die Hauptäste zu den Teilkronen sind Geometrie. Laubbaum nah: 104
  Zweigkarten auf 5 Teilkronen + 16 innere Laubmasse-Karten (≈ 300 Dreiecke,
  vorher ≈ 970 für den Kugelkörper).
- **Licht ohne eigenen Shader.** Eckpunkt-Normalen sind zu 70–90 % die Richtung
  vom Kronenmittelpunkt aus („gebogene Normalen“, unten etwas abgeflacht): Die
  Krone schattiert wie ein weicher Körper, Karte für Karte flimmert nichts.
  Verschattung innen/unten steckt in den Vertexfarben (Bäume empfangen keine
  Schatten). Weil beide Wicklungen dieselben Eckpunkte und damit dieselbe
  Normale haben, ist die Beleuchtung von beiden Seiten gleich — unabhängig von
  `gl_FrontFacing`.
- **Arten:** breiter Laubbaum, schlanker Laubbaum (Pappel/Birke, halb mit
  hellem Stamm), Feld-/Obstbaum, **Fichte** (Astquirle als „Schürzen": je Quirl
  ein Kranz schräger Trapezkarten mit hängendem Nadelrand, Quirle überlappen),
  **Kiefer** (neu: hoher kahler Stamm, oben rötlich, Schirmkrone; regional
  gehäuft nach Rauschen), **Busch** (neu; im Offenland bei lockerer
  Kronenbedeckung 10–20 %, als Unterholz am Waldweg 14 %; dicht gesetzt ergibt
  das Hecken). Größe, Breite, Helligkeit und Farbton (regional + je Baum) streuen.
- **Stufen** (gebacken, also fest nach Abstand zur Strecke — die Kamera ist
  immer auf der Strecke, darum springt dazwischen nichts): nah ≤ 16 m volle
  Karten, mittel ≤ 40 m 28–44 große Zweigkarten, fern Stamm + 6–8 große
  Laubmasse-Karten; Fichte fern als Sternbild (drei senkrechte Karten mit dem
  ganzen Baum). Vergabe vom Weg nach außen (nächste Bäume zuerst voll).
- **Waldkörper mit Kronen-Silhouette:** Kronendach mit eigenem, kachelndem
  Kronenmosaik (`mat_tree_leaves`, MASK, doppelseitig wie im Vertrag, deckende
  JPEG 256², 30 m je Kachelung) statt Sprenkeltextur; am Waldrand je Randzelle
  (Stufe 0: zwei) ein Kreuzbild eines ganzen Baums
  (Vorderseite nach draußen, verdeckt die Rampe des Kronendachs), in Stufe 0
  ragen aus 30 % der Innenzellen Kronenspitzen. Stufe 1 bekommt nur die
  Randbäume, aus einem kleinen Fern-Atlas (256²) statt des großen.
- **Bodenbewuchs** (nur Stufe 0): Gras-/Kraut-Büschel (zwei gekreuzte Karten),
  im Wald zur Hälfte Farn, in Gruppen zu 1–3 auf Plätzen alle 2,2 m je Seite entlang der Strecke
  (aus dem Laufmeter gewürfelt, über Kachelgrenzen gleich), 0,5–4,3 m neben dem
  Fahrbahnrand (Wald bis 7,5 m), fleckig nach Landnutzung, nie auf Straßen,
  Wasser, in Gebäuden. Vorderseite zur Strecke, Normalen fast senkrecht, unter Kronen um bis zu 45 % dunkler (Bewuchs empfängt keine Schatten). Höchstens
  1 600 Dreiecke je Kachel.
- **Texturen** (alles im Code gezeichnet, keine fremden Bilder): Atlas 512²
  mit Laubzweig, Kiefernbüschel, Fichten-Schürze, Kreuzbild Laub-/Nadelbaum,
  Gras, Laubmasse, Farn — als **PNG Grau+Alpha** (Farbtyp 4; die Farbe kommt
  aus den Eckpunkten; 58 KB statt 240 KB als RGBA), einmal je Prozess kodiert.

### Flimmern und Mip-Stufen

- **MASK in Filament (belegt an den Quellen):** gltfio bildet `alphaMode MASK`
  auf die Ubershader-Variante `masked` ab; Filament schaltet für `MASKED`
  automatisch **Alpha-to-Coverage** ein (`MaterialDefinition.cpp`:
  `rasterState.alphaToCoverage = blendingMode == BlendingMode::MASKED`; Doku
  `Materials.md`, „Blending and transparency: masked … ALPHA_TO_COVERAGE is
  enabled"), wirksam mit dem 4×-MSAA der Darstellung. Vorher wird das Alpha über
  die Ableitung geschärft (PR #7215 „Fix masked materials":
  `(a − Schwelle) / fwidth(a) + 0,5`). Mip-Stufen erzeugt gltfio selbst
  (`StbProvider`: `texture->generateMipmaps(engine)` immer; Sampler
  `LINEAR_MIPMAP_LINEAR` aus unserer GLB).
- **Betrachter = Filament:** `viewer.js` schaltet für MASK-Materialien
  `alphaToCoverage` ein und ersetzt three.js' Schärfung (smoothstep, eine halbe
  Ableitung dünner) durch Filaments Formel. three.js dreht bei doppelseitigen
  Materialien die Normale der Rückseite ebenso um.
- **Deckung in den Mip-Stufen:** Eigene Mip-Stufen lassen sich in einer GLB mit
  PNG nicht mitgeben. Deshalb sind die Motive so gezeichnet, dass ihr Umriss
  dicht gedeckt ist (überlappende Blätter/Nadeln); durchsichtige Pixel tragen
  die Mittelfarbe des Motivs (kein dunkler Saum), zwischen Motiven ≥ 6 px Rand.
  `FoliageAtlasTest` filtert wie `generateMipmaps` (2×2-Kasten) und verlangt in
  den Stufen 1–3 mindestens 80 % der Deckung (Gras/Farn 55 %); gemessen
  0,97–1,05.
- **Koplanar:** Karten sind zufällig verdreht, Büschel senkrecht.
  Die beiden Wicklungen einer Karte sind zwar koplanar, aber nie gleichzeitig
  sichtbar (einseitiges Material, Rückseiten verworfen) — kein Z-Kampf.
  `indoor-dev overlaps`: Altenberg kurz 638 → 638, Tharandt km 2–6 234 → 234,
  kein Paar mit Vegetation; `QualityTest` grün.

### Messwerte (vorher → nachher)

`-Xmx256m`, 2 Threads, Geodaten aus dem Cache. Vorher = `main` (6cd9387).

| Welt | Dreiecke gleichzeitig | Texturen | Platte | Heap nach GC (max) | Bauzeit |
|---|---|---|---|---|---|
| Schauinsland 15,7 km (157 Kacheln) | 559 699 → 403 301 | 160,0 → 183,7 MB | 11,8 → 12,5 MB/km | 118 → 120 MB | (70 → 74 s) |
| Altenberg kurz 3 km | 462 942 → 372 340 | 153,3 → 177,3 MB | 15,7 → 17,3 MB/km | 124 → 125 MB | (37 → 17 s) |
| Tharandt km 2–6 | 489 503 → 369 626 | 137,2 → 160,5 MB | 12,5 → 13,2 MB/km | 109 → 121 MB | (38 → 19 s) |

Budgets (≤ 700 000 Dreiecke, ≤ 256 MB Texturen, 256 MB Heap) eingehalten. Die
Bauzeiten „vorher" enthalten das Laden aus dem Netz (erster Lauf), sind also
nicht vergleichbar. **Platte:** Karten haben vier eigene Eckpunkte (je 36 Byte),
geschlossene Körper teilen sie — je Dreieck etwa dreimal so viele Bytes. Damit
der Plattenbedarf nur mäßig steigt (+6 bis +10 %), sind die Einzelbäume auf
13 000 Dreiecke je Kachel gedeckelt (vorher nahmen sie bis ≈ 25 000), der
Bodenbewuchs auf 1 600, und die Atlanten sind Grau+Alpha. Mehr Dichte ginge mit
normierten `UNSIGNED_SHORT`-Texturkoordinaten (Kern-glTF, −4 Byte je Eckpunkt)
in `Glb.kt` — in der zweiten Runde gemacht (siehe dort). Texturen +23 MB: Atlas 1,4 MB GPU je Kachel der Stufe 0, Kronendach
0,35 MB je Kachel.

### Unabhängiges Gutachten (Unter-Agent, opus) und was daraus wurde

Urteil zu den Bildpaaren: in allen acht Paaren besser als vorher, am nächsten
an Zwift `altenberg-waldrand-fichten` und `altenberg-laubwald-lenker`
(„fast auf Zwift-Niveau"); insgesamt **noch nicht Zwift-Niveau** (geschätzt
60–65 %), vor allem in der Totalen und von oben. Eingearbeitet:

| Kritik | Stand |
|---|---|
| schwarze Kartenrückseiten (Kronenoberseiten gegen den Himmel, Drohne) | **behoben** (gestauchte Normalen, Vorderseite nach Normale) |
| Bodenbewuchs als verstreute Einzelstempel | **behoben** (Gruppen zu 1–3, kleiner) |
| Gras und Bewuchs leuchten im Schatten | **teilweise:** Bewuchs unter Kronen abgedunkelt; Stämme nicht |
| grell orange Kiefernstämme, kalkweiße Birken | **behoben** (gedämpft) |
| Fichten-„Segel" von unten | **teilweise:** 12 kleinere Quirle statt 10, am Waldweg höherer kahler Stamm |
| eigene Mip-Stufen mit erhaltener Deckung | **nicht möglich** (PNG in GLB hat eine Stufe, gltfio erzeugt sie); stattdessen dicht gezeichnete Motive, Test |
| Kronen zu licht / Stangenwald, wenig Mittelschicht | **verbessert** in der zweiten Runde (Deckel, `UNSIGNED_SHORT`-UV, Unterwuchs) |
| Karte schneidet die Nahebene am Wegrand | **offen** (selten; Kronen am Weg kürzen) |

### Zweite Runde (Rückmeldung der Integration)

Nach dem Zusammenführen mit der Laser-Stufe (`main` 5406928). Vorher = dieser
Stand nach dem Merge, nachher = jetzt; dieselben Blickpunkte, die Bildpaare in
`docs/indoor/bilder/foliage/` sind ersetzt. Neu ist das Paar
`balkantrasse-laser-lenker` (`--laser`, Bäume mit gemessener Lage und Höhe aus
dem nDOM).

| Rückmeldung | Ursache | Lösung |
|---|---|---|
| schwarze Kartenflächen und dunkle „Fahnen“ an Laubkronen in offener Landschaft, Fichten am Hang fast schwarz | Doppelseitiges Material: Filament (und three.js) drehen auf der Rückseite die Normale um; gebogene Normalen zeigen dann vom Licht weg. Fast kantenparallele, von hinten gesehene Karten werden zu dunklen Strichen | Karten in **`x_mat_tree_cards` einseitig**, jede mit **beiden Wicklungen auf denselben Eckpunkten** (kostet nur 6 Indizes je Karte); je Blickrichtung wird genau eine gezeichnet, beide mit derselben Normale. Fichten-Schürzen haben für die Unterseite eigene, dunklere Eckpunkte mit Normale nach außen-unten. Gras und Kreuzbilder bleiben einseitig mit Vorderseite zur Strecke bzw. nach draußen. `mat_tree_leaves` (MASK, doppelseitig, wie im Vertrag) trägt jetzt das deckende Kronendach |
| Stangenwald, Kronen licht | Deckel 13 000 Dreiecke je Kachel, Kronen setzen bei 30 % der Höhe an | `Glb.kt`: **`TEXCOORD_0` als normierte `UNSIGNED_SHORT`**, wenn alle UV eines Primitivs in [0, 1] liegen (Kern-glTF 2.0; gltfio übernimmt das Flag: `AssetLoader.cpp` `vbb.normalized(semantic, accessor->normalized)`, `GltfEnums.h` `getElementType` kennt `r_16u`; three.js ebenso, im Betrachter geprüft). Spart 4 Byte je Eckpunkt in **allen** Kacheln (Gelände, Fahrbahn, Gebäude …). Damit Deckel 30 000 (beide Wicklungen gezählt). Laubbäume im Bestand setzen die Krone bei 20–26 % an (breiter, Kronendach schließt), mehr Unterholz (Büsche 24 % am Weg, 14 % dahinter) und junge Bäume (12 %) verdecken die Stämme im Mittelgrund |
| Stämme hell und glatt wie Rohre | nur Vertexfarbe | **Rindentextur** (prozedural, JPEG 64 × 128, kachelnd, v 0,5 je Meter) auf `mat_tree_trunk`, Stammfuß auf 55 % abgedunkelt |
| Fichten-„Segel“ von unten | Unterseite = beleuchtete Oberseite | eigene dunkle Unterseite (s. o.), am Waldweg höherer kahler Stamm |
| Kronendach-Rampen am Steilhang | ein Randbaum je 8-m-Zelle reichte nicht | zwei Randbäume je Randzelle in Stufe 0, weiter nach außen gesetzt (am Schauinsland-Hang rechts weiterhin teils zu sehen) |

Die Laser-Bäume (`laserTreeSpec`) kennen jetzt Kiefer und Busch und übernehmen
kahlen Stammanteil und Farbton; `specFor` bekommt den Abstand zur Strecke.

**Messwerte** (`-Xmx256m`, Cache; vorher = Stand nach dem Merge):

| Welt | Dreiecke gleichzeitig | Texturen | Platte | Heap nach GC (max) |
|---|---|---|---|---|
| Schauinsland 15,7 km | 403 301 → 560 785 | 183,7 → 184,1 MB | 12,5 → 13,2 MB/km (+6 %) | 122 → 118 MB |
| Altenberg kurz | 372 340 → 468 670 | 177,3 → 177,7 MB | 17,3 → 17,8 MB/km (+3 %) | 125 → 135 MB |
| Tharandt km 2–6 | 369 626 → 466 472 | 160,5 → 160,9 MB | 13,2 → 13,8 MB/km (+5 %) | 110 → 125 MB |
| Balkantrasse 7,7 km, `--laser` (mit `-Xmx2g`) | 375 483 → 453 111 | 178,5 → 178,8 MB | 18,6 → 18,6 MB/km | – |

Gegenüber `main` vor der Vegetation (6cd9387): Schauinsland 11,8 → 13,2 MB/km.
**Laser-Stufe und Heap:** Die Balkantrasse mit `--laser` bricht bei
`-Xmx256m` schon im Stand nach dem Merge (ohne diese Runde) mit
`OutOfMemoryError` ab (vor den Kacheln, beim Laden; mit 2 GB Heap nach GC bis
343 MB). Das liegt in der Laser-Stufe, nicht in der Vegetation.

### Dritte Runde (Kombi-Render Balkantrasse, `--laser`)

Nach dem Zusammenführen mit `main` a80395a (Welt-Version 5). Vorher = dieser
Stand nach dem Merge, nachher = jetzt; alle Bildpaare ersetzt, neu
`balkantrasse-laser-chase` (km 4,5, Verfolger).

| Befund | Ursache | Lösung |
|---|---|---|
| Stämme fast schwarz und sehr dick | Radius 2,4 % der Höhe (Laserbäume bis 42 m → Durchmesser um 2 m, Wurzelanlauf ×1,9), dunkle Rindenfarbe × Textur, Fuß auf 55 % | Radius aus der Höhe mit Deckel (`trunkRadius`: Laubbaum 0,05 m + 1,2 % · h, höchstens 0,42 m; Fichte 0,9 %, höchstens 0,32 m; Kiefer 1 %, 0,34 m), Wurzelanlauf ×1,45, Rinde heller, Fuß 78 % |
| Kronen „ausgebleicht“ (weißlich-hellgrün), Schatten fast schwarz | **Spiegelanteil der Sonne bei streifendem Blick:** Die nach oben/außen gebogenen Normalen zeigen für die tief stehende Kamera oft vom Betrachter weg (N·V ≈ 0); dann wird der GGX-/Fresnel-Term groß, und er hängt nicht von der Albedo ab. Die Albedo der Karten war mit linear 0,02–0,05 zudem viel dunkler als echtes Laub, deshalb überdeckte der graue Glanz das Grün. Nachgewiesen im Betrachter: Mit `MeshLambertMaterial` (ohne Spiegelanteil) sind dieselben Kronen satt grün; Materialfarbe, Rauheit 1,0 und Dunst ändern nichts. Filament nutzt dieselbe BRDF-Familie (GGX, Smith, Schlick) und hat keinen Schalter dafür im Kern-glTF | Normalen zusätzlich **zur Strecke hin** gebogen (die Kamera ist immer dort; Richtung je Baum aus der nächsten Stelle der Strecke), weniger nach oben; Albedo der Karten auf ein realistisches Niveau (Helligkeit 0,66–1,06 statt 0,36–0,68), satteres Grün; Laubzweig-Motiv mit geschlossener, dunklerer Laubschicht unter den Blättern (weniger Himmel durch kleine Lücken) |
| lange schmale Kartenstreifen aus der Krone | Karten nahe der Hülle mit großer Länge | Kartenspitze höchstens 12 % über der Kronenhülle (sonst gekürzt), MID-Karten kürzer |
| Fichtenäste wie gestapelte Platten | flache, gleich große Trapezkarten | gewölbte Schürzen (drei Eckpunktzeilen, Mitte nach außen), schmalere Karten (7 je Quirl, 10 Quirle), je Karte eigene Weite, Breite, Höhe und Durchhang |

Bildrate: Dreiecksdeckel unverändert, Kartenzahl praktisch gleich (Fichte nah
280 statt 288 Dreiecke). Fast kantenparallele Karten direkt vor der Kamera
zeigen weiterhin einen hellen Streifen (N·V ≈ 0 lässt sich ohne eigenen Shader
nicht ganz vermeiden).

| Welt (`-Xmx256m`) | Dreiecke gleichzeitig | Platte |
|---|---|---|
| Schauinsland | 560 017 → 560 553 | 13,2 → 13,2 MB/km |
| Altenberg kurz | 467 902 → 468 080 | 17,8 → 17,8 MB/km |
| Tharandt km 2–6 | 466 472 → 473 746 | 13,8 → 13,9 MB/km |
| Balkantrasse `--laser` (`-Xmx2g`) | 438 434 → 441 606 | 18,4 → 18,5 MB/km |

Die Balkantrasse mit `--laser` scheitert bei `-Xmx256m` weiterhin beim Laden
(`OutOfMemoryError`, Laser-Stufe, unabhängig von der Vegetation).

### Runde 3b: Spiegelanteil abgeschaltet, Rinde hell

Rückmeldung zu Runde 3: Sonnige Kronen weiterhin grau verschleiert, Stämme
fast schwarz.

- **`KHR_materials_specular` mit `specularFactor` 0** auf allen
  Vegetationsmaterialien (`x_mat_tree_cards` samt Bodenbewuchs und
  Kreuzbildern, `mat_tree_leaves`, `mat_tree_trunk`). In `Glb.kt` als
  optionale Material-Erweiterung (`GlbMaterial.noSpecular`, nur in
  `extensionsUsed`, nicht `extensionsRequired`), über
  `TileMaterial.noSpecular` gesetzt. **Beleg, dass gltfio 1.75.1 sie im
  Ubershader auswertet** (Tag `v1.75.1`):
  - `libs/gltfio/include/gltfio/MaterialProvider.h:98` `bool hasSpecular : 1;`
  - `libs/gltfio/src/AssetLoader.cpp:1462` `.hasSpecular = !!inputMat->has_specular,`;
    `:1760–1763` setzt `specularColorFactor` und
    `specularStrength = spConfig.specular_factor`
  - `libs/gltfio/src/UbershaderProvider.cpp:194`
    `requirements.features["Specular"] = config.hasSpecular;`; `:74–78`
    (Unlit und Specular schließen sich aus — hier nicht gebraucht)
  - `libs/gltfio/CMakeLists.txt:152–154` baut `specular_opaque`,
    `specular_fade`, **`specular_masked`** ins Archiv;
    `materials/specular.spec.in`: `VertexColors`, `BaseColorTexture` optional,
    `Specular = optional`
  - `materials/specular.mat.in:128`
    `material.specularFactor = materialParams.specularStrength;` — laut
    Materials-Doku skaliert `specularFactor` F0 und setzt F90, mit 0 also kein
    Spiegelanteil, auch nicht bei streifendem Blick.

  Der Betrachter lädt die Erweiterung als `MeshPhysicalMaterial`
  (`specularIntensity` 0) und erlaubt sie in der Formatprüfung, ebenso
  `GlbCheck` in `:core`. Auf dem Gerät nicht gesehen: Ob `specular_masked` mit
  `doubleSided` (Kronendach) und MSAA genauso wie `lit_masked` läuft, sagen die
  Quellen (gleiche Vorlage `${BLENDING}`/`${DOUBLESIDED}`), geprüft ist es nicht.
- **Rinde:** Die Albedo war linear rund 0,035 (Textur ~0,2 × Vertexfarbe ~0,15);
  echte Rinde liegt bei 0,1–0,2. Rindentextur heller (0,45–0,95 sRGB),
  Rindenfarben angehoben (Laubbaum sRGB 170/152/132, Fichte 158/138/118,
  Kiefer unten 162/142/126, oben 196/148/110, Birke 214/210/200). Die
  Stammnormalen sind radial (Loft), Stämme empfangen keine Schatten — der
  Rest war Albedo und Glanz.
- **Laub:** Ohne Glanz wurde das Grün zu kräftig; Grundfarben olivgrüner,
  Helligkeit 0,58–0,94.
- Die Normalen zur Strecke (Runde 3) bleiben: Sie halten die Schattenseite
  der Kronen hell genug.

Messwerte unverändert gegenüber Runde 3 (Schauinsland 560 553 Dreiecke,
13,2 MB/km; Altenberg 17,8; Tharandt 13,9; Balkantrasse 18,5 MB/km). Neu im
Bildsatz: `balkantrasse-laser-chase-km26`.

### Bildrate: Overdraw und Stellschrauben

Alpha-getestete Karten überlagern sich (Overdraw), und `discard` hebt Early-Z
für diese Fragmente auf; die Rückseiten-Hälfte jeder Karte wird dagegen schon
beim Rastern verworfen. Vorkehrungen: Karten nah klein und zahlreich, fern
wenige große mit dichtem Motiv (Laubmasse ≥ 80 % Deckung); keine großen, fast
leeren Karten (Fichte fern als Sternbild aus drei Karten, Laubbäume fern ohne
Sternbild); Kronendach der Waldkörper deckend (kein Alpha-Verwurf); Gras nur
bis 4,3 m neben der Fahrbahn.

Fällt auf dem Gerät die Bildrate, in dieser Reihenfolge drehen:

1. **`TreesLayer.DETAIL_M`** (volle Karten bis 16 m) auf 10 m und
   **`SINGLE_TREE_MAX`** (30 000) auf 20 000 — weniger Karten nah, wo der
   Overdraw entsteht.
2. **`UNDERGROWTH_MAX`** (1 600) auf 800 oder 0.
3. In der Darstellung: `RenderSettings.resolutionScale` senken bzw.
   dynamische Auflösung; MSAA abschalten verliert Alpha-to-Coverage (Kanten
   flimmern dann stärker).

### Offen

- **Kronendichte im dichten Wald:** besser, aber über dem Weg schließt das
  Kronendach nicht überall; im Mittelgrund bleiben Stämme sichtbar.
- **Waldboden dunkel:** Luftbild unter Kronen (Gelände/Darstellung, nicht hier).
- **Kronendach-Rampen am Steilhang:** am Schauinsland rechts weiterhin teils als
  helle, kantige Flächen zu sehen.
- **Karte schneidet die Nahebene** am Wegrand (selten).

### Nur im Betrachter geprüft (Gerät steht aus)

- Alles Sichtbare: three.js mit nachgebildetem MASK (Alpha-to-Coverage,
  Filaments Schärfung), SwiftShader, `HemisphereLight` statt SH-Umgebungslicht.
  Wie hell Schattenseiten und Kronenunterseiten unter Filaments
  `IndirectLight` wirken, ist offen.
- Bildrate (Overdraw, s. o.) — auf dem Handy messen (MEDIUM, 4× MSAA).
- PNG Grau+Alpha (Farbtyp 4) und normierte `UNSIGNED_SHORT`-UV in gltfio: nach
  den Quellen unterstützt, auf dem Gerät nicht gesehen.

## Kreuzungen und Ortsdurchfahrten (24.11.2026, Branch `claude/indoor-junctions`)

Anlass: erste Fahrt des Gründers in einer **auf dem Handy gebauten** Welt (eigene
35,6-km-Strecke, Start im Ort, ohne Laser): „schon etwas besser, aber teilweise
immer noch komisch". Gerätebilder: `docs/indoor/bilder/geraet/01–05`. Geändert:
`RouteLayer.kt`, `RoadsLayer.kt`, `RouteTextures.kt`, `Ground.kt`,
`GroundTexture.kt`, `Glb.kt`, `WorldInternals.kt`, `WorldBuilderImpl.kt`,
`GlbOverlap.kt`, neu `PlanClip.kt`; Werkzeug `overlaps`, Betrachter `hdr`/`clampColor`.
Keine Vertragsdatei geändert, `WORLD_BUILDER_ID` und Katalogversionen **nicht**
angehoben (beim Zusammenführen nötig: Ränder, Straßen und Gelände ändern sich in
jeder Welt).

Bildpaare (gleicher Blickpunkt, Betrachter, Sonne 235°/40°):
`docs/indoor/bilder/junctions/<welt>-<laufmeter>_<kamera>-vorher.jpg` / `-nachher.jpg` —
Balkantrasse km 0–3, Kreisel Bergisch Gladbach und Altenberg km 0–4, alle drei
**ohne Luftbild** (`--no-aerial`, wie die Gerätebilder: erzeugter Boden), dazu
Altenberg kurz **mit** Luftbild (`altenberg-kurz-luftbild-…`).

### Mängel, Ursache, Stand

| # | Mangel (Gerät) | Ursache im Code | Stand |
|---|---|---|---|
| 1 | **Weiße gepunktete Linien** an allen Nähten zwischen Fahrbahn, Gehweg, Nebenstraßen und Gras (01, 02, 04, 05) | Keine Risse: Das Gelände ist lückenlos (jede Kachel ein geschlossenes Gitter mit Schürze). Die Linien liegen genau an den **Außenkanten der weichen Ränder** (Straßenkanten `x_mat_road_*_edge`, Übergangsstreifen `x_mat_route_verge_*`, Belagswechsel `*_fade`), deren Deckung als **Vertex-Alpha** zum Rand auf 0 fiel. Filament zeichnet mit 4× MSAA in einen Float-Puffer und wertet den Shader eines Dreiecks am **Pixelmittelpunkt** aus, auch wenn nur einige Abtastpunkte im Dreieck liegen (keine `centroid`-Interpolation). Dort wird die Deckung über die Kante hinaus **negativ** fortgeschrieben; beim Mischen (`ONE, ONE_MINUS_SRC_ALPHA`, bei FADE zusätzlich das Entmultiplizieren durch `max(a, ε)`) wird der Hintergrund dadurch aufgehellt, bis weiß. Der Betrachter zeichnet in einen 8-Bit-Puffer, der vor dem Mischen auf 0..1 klemmt — deshalb war es dort nie zu sehen | **behoben:** Kein durchsichtiges Primitiv hat mehr Vertex-Deckung < 1. Die Deckung weicher Ränder steckt in der **Textur** (Werte bleiben in 0..1; fortgeschriebene UV landen am geklemmten Rand): Straßenkante `route_<belag>_edge` (PNG 512 × 16, `u` längs kachelnd, `v` quer geklemmt — neuer Sampler `wrapS = REPEAT`, `wrapT = CLAMP_TO_EDGE`, `TileMaterial.clampV`), Auslauf des Übergangsstreifens `verge_<art>_fade` (PNG 256 × 32). Der Übergangsstreifen selbst ist bis `tB` **deckend**; der Belagswechsel ist `MASK` mit unregelmäßiger Kante (negative Werte werden dort nur verworfen). Messbar: `indoor-dev overlaps` zählt „durchsichtige Eckpunkte mit Vertex-Deckung < 1": Balkantrasse 52 930 → **0**, Altenberg kurz 21 188 → **0**; Test `QualityTest` verlangt 0 für die Testwelt |
| 2a | **Gezackte Graskeile** ragen in Fahrbahn und Einmündungen (03, 05) | Der Rand der Strecke (Gras, Fransen, Gehweg, Bordstein, Bankett) wurde an Nebenstraßen **je Eckpunkt** ausgeblendet (`roadFactor`, Stationen 1–3 m) — dazwischen linear, also Keile; außerdem lag das Gras höher als die Nebenstraße | **behoben:** Die Randgestaltung entsteht je Stück in Zwischennetzen und wird im Grundriss **gegen die Bänder der übrigen Straßen geschnitten** (`PlanMask`/`clipInto`, Sutherland–Hodgman je Dreieck, Attribute linear interpoliert) — genau so abgetastet wie `RoadsLayer` sie zeichnet. Ebenso gegen die Fahrbahn anderer Streckenteile (Kreisel, enge Kurven, Hin- und Rückweg nebeneinander; die eigene Fahrbahn ±4 m bzw. ±15 m für Ränder innerhalb der Fahrbahn zählt nicht). Bahnen sperren nicht (wie vorher). Gras endet jetzt an der Straßenkante; Fransen, Randlinien und Bordstein enden an der Einmündung |
| 2b | **Brauner Belagfleck mit Zickzackrand** quer auf der eigenen Strecke (03) | Geplante Routen haben an Kreuzungen Wegstücke von wenigen Metern mit anderem Belag/anderer Wegart; gezeichnet mit eigenem Belag, Fransen und Grasrand | **behoben:** `TrackLine.forDrawing` schlägt Belagsabschnitte unter 25 m dem längeren Nachbarn zu — nur für die Zeichnung, das Manifest (Rollwiderstand, Anzeige) bleibt unverändert |
| 2c | **Nebenstraßen wellen sich**, stoßen mit Stufen/Rampen an (05) | `CarvedGround` legt neben der Fahrbahn eine flache Mulde an (Boden 25 cm unter der Fahrbahn über das Bankett von √2·Zelle + 0,3 m, damit das 4-m-Netz nicht durchsticht; dann Böschung über **mindestens 10 m**). Nebenstraßen folgten dem Boden und wurden nur auf 4,2 m an die Fahrbahn gezogen: Rampe, dann Mulde, dann wieder hoch | **behoben:** Nebenstraßen laufen über `JOIN_M` = 12 m stetig von der Fahrbahnkante auf ihre Höhe (über der Mulde; liegt die Strecke tiefer, bleibt der kurze Übergang, sonst tauchte die Straße unter die Böschung). Flache Böschungen (< 0,5 m) laufen auf 3–10 m aus statt auf ≥ 10 m |
| 2d | Flächen verschiedener Straßen überlagern sich in verschiedener Höhe | Gras über der Nebenstraße (s. 2a), Straßenkanten über dem Gras | **behoben** mit 2a; die Rangstaffel der Straßen untereinander (1,2 cm) bleibt |
| 3a | Gehwegpflaster sehr grob | 32-cm-Platten mit dunklen, zwei Pixel breiten Fugen | **behoben:** Betonsteinpflaster im Läuferverband (21 × 10,7 cm), schmale helle Fugen, gleiche Texturgröße |
| 3b | Bordsteinverlauf an Ecken eckig und abgehackt | Gehweg und Bordstein brachen an Einmündungen ab, sobald die Deckung je Eckpunkt unter 0,35 fiel | **teilweise:** Gehweg und Bordstein enden jetzt genau an der Kante der Nebenstraße (2a). Ausgerundete Bordsteinecken (Radius in die Nebenstraße hinein) gibt es **nicht** |
| 4 | Gelände am Straßenrand wellig-fleckig, harte Kanten Gras/Grau (04, 05) | (a) Gras folgte der Mulde neben der Fahrbahn: neben dem Gehweg fiel es auf 75 cm um bis zu 30 cm ab; (b) erzeugte Bodentextur: Beete und Hofflecken mit harter Kante (`smoothstep` über 6 % des Rauschens) | **behoben:** (a) Der deckende Übergangsstreifen läuft von der Außenkante des Randstreifens gleichmäßig auf den Boden am Ende hinaus (nie unter dem Boden), die Mulde ist schmaler (2c); (b) weichere Ränder (Beete 0,48–0,66, Hofflecken 0,66–0,80, 85 % Deckung). Jenseits des Übergangsstreifens bleibt die Mulde (max. 19 cm) über einige Meter sichtbar |

| 5 | **„Manchmal flackert es noch am Rand der Straße"** (Nachtrag des Gründers) | Fast deckungsgleiche Flächen am Fahrbahnrand. (a) Die Fransenkante reichte 28 cm über den Fahrbahnrand und lag dort 0,3–0,8 cm über dem Gras — seit das Gras deckend ist, ein Z-Kampf; (b) weiche Kanten der Nebenstraßen lagen 0,35 m über dem Gras der Strecke bzw. über anderen Straßen an Knoten, Millimeter darüber; (c) das Band des Belagswechsels lag 3 mm unter den Fransen | **behoben durch Aneinanderstoßen, nicht durch mehr Versatz:** (a) Die Fransen liegen nur noch auf der Fahrbahn (von 0,52 m innen bis zur Kante), das Gras beginnt genau dort. (b) Nebenstraßen haben im Randbereich der Strecke (Mitte < 16 m, `EDGE_FREE_TRACK_M`) keine weichen Kanten mehr — das geschnittene Gras stößt direkt an ihre Fahrbahn; sonst werden ihre Kanten an den Bändern anderer Straßen geschnitten (`PlanMask`). (c) Das Belagswechsel-Band endet seitlich an der Innenkante der Fransen. Gemessen mit `indoor-dev overlaps --tol-cm …` (neue Option), Paare mit Rand der Strecke (`route:`) ohne Gelände: Balkantrasse unter 4 mm **675 → 5**, unter 1 cm 908 → 35; Altenberg kurz unter 4 mm **294 → 28**, unter 1 cm 493 → 83. Übrig: einzelne Stellen Gehweg/Gras und Bordstein/Gehweg in engen Innenkurven, Fransen über der Fahrbahn in Kurven (sonst 1,5 cm), dazu Kreuzungen zweier Nebenstraßen untereinander (Rangstaffel 1,2 cm, unverändert) |

**Nebenwirkungen, gesehen und in Kauf genommen:**

- Der Übergangsstreifen hat je Stück **eine** Randtextur (die stärkere); wo Wiese
  und Waldboden wechseln, ist das jetzt eine Kante statt eines Verlaufs über
  eine Station.
- Am Bankett liegt der Einlauf des Grases über dem Schotter weiter weich
  (Auslauftextur umgekehrt), am Gehweg beginnt das Gras hart an dessen Außenkante.
- Mit Luftbild sieht der deckende Streifen gegenüber dem Luftbild etwas
  „gemalter" aus als der alte durchscheinende (Altenberg kurz, km 1,86).

**Offen:**

- **Kreiseleinfahrt** (Kreisel Bergisch Gladbach, km 0,77): Zwischen der
  Strecke und der Mittelinsel bleibt ein Graskeil (vorher schien dort der in
  den Boden gemalte Asphalt durch das durchscheinende Gras). Vermutlich ein
  Stück des Rings, das `RoadNet` als „auf der Strecke" einstuft und deshalb
  niemand als Straße zeichnet — nicht abschließend untersucht. Ringstücke, die
  als „auf der Strecke" gelten, aber schon > 1,5 m neben ihr liegen, sperren
  jetzt Gras und Gehweg; das reichte hier nicht.
- Ausgerundete Bordsteinecken (3b).
- Die Nahtlinien sind **nur rechnerisch** als behoben nachgewiesen (Zähler oben,
  `QualityTest`). Der Betrachter kann sie nicht zeigen: Auch mit `hdr=1`
  (Float-Puffer mit 4× MSAA, siehe `viewer/README.md`) interpoliert SwiftShader
  fast überall innerhalb des Dreiecks — im Vergleich mit `clampColor=1` blieben
  nur einzelne Pixel. **Auf dem Gerät prüfen.**

### Messwerte (vorher = `aa0eb22`, nachher = dieser Branch)

`-Xmx256m`, 2 Threads, MEDIUM, Geodaten aus dem Cache, ohne Laser; Heap = größter
Wert nach GC im GC-Protokoll.

| Welt | Dreiecke gleichzeitig | Texturen | Platte | Heap nach GC (max) |
|---|---|---|---|---|
| Altenberg kurz (3 km, Luftbild) | 467 902 → 466 553 | 177,7 → 179,2 MB | 17,8 → 18,3 MB/km (+2,8 %) | 212 → 206 MB |
| Balkantrasse (7,7 km, `--no-aerial`) | 430 916 → 428 897 | 176,1 → 177,0 MB | 18,6 → 19,1 MB/km (+2,7 %) | 223 → 231 MB |

Koplanare Überlappungen (`indoor-dev overlaps`, Stufe 0, unter 4 mm, alle Paare):
Balkantrasse 1 134 → 265, Altenberg kurz 638 → 336 (der Rest liegt überwiegend
zwischen Gelände und dem auslaufenden, dort fast durchsichtigen Übergangsstreifen,
an Wasserufern und an Brücken). Bildpaare zeigen den Stand vor Punkt 5; die
Fransen enden seitdem an der Fahrbahnkante (im Bild kaum zu unterscheiden).

### Nachrunde (nach dem Merge mit der Vegetation, Welt-Version `indoor-world/6`)

Vier Punkte aus dem Kombi-Render des Orchestrators. Bildpaare:
`docs/indoor/bilder/junctions/runde2-<welt>-<laufmeter>_<kamera>-vorher.jpg` /
`-nachher.jpg` (vorher = `e79c5bc`, Stand nach dem Merge; gleiche Blickpunkte).

| # | Mangel | Ursache | Stand |
|---|---|---|---|
| 1a | Neben Schotter-/Waldweg beidseitig ein **dunkelbrauner, hart begrenzter Randstreifen** (Altenberg kurz mit Luftbild, km 1,5) | Der deckende Übergangsstreifen (Runde 1) behielt die Farbregel aus der Zeit, als er durchschien: Waldboden und Schotter wurden nur zum Rand hin (Stärke³) ans Luftbild angeglichen; deckend war er bis `tB` = 4 m (Wald) | **behoben:** Waldboden und Schotter werden wie Gras an die Farbe des Bodens daneben angeglichen (Luftbild bzw. erzeugte Bodentextur über die Abtönung); deckend nur noch bis `tA` (≤ 1,5 m hinter dem Randstreifen), dann läuft der Streifen über `tB` bis `tE` weich aus (Deckung weiter in der **Textur**, Auslauf jetzt ab Deckung 1). Ort/Acker laufen breiter aus (6,0/6,5 statt 4,4/5,0 m) |
| 1b | Heller Belag endet im Vordergrund mit einer **geraden Querkante** (Rechteck-Stufe) | Altenberg kurz hat bei km 1,5 eine Wendeschleife: Die Rückfahrt (km 1,54–1,56) liegt wenige Meter neben der Hinfahrt. Deren Fahrbahn schnitt als Sperrfläche (Runde 1) die Fransen der Hinfahrt weg — wo sie begann, endeten die Fransen quer | **behoben:** Fahrbahnen anderer Streckenteile sperren nur noch den äußeren Rand (Gras, Gehweg, Bankett), nicht Fransen, Bordstein und Linien; ebenso Wege, die längs in der eigenen Fahrbahn liegen, ohne als „auf der Strecke“ zu gelten. Zusätzlich laufen die Fransen an Wechseln der Randgestaltung über rund zehn Stationen aus, und das Band des Belagswechsels hat wieder volle Breite (die Fransen liegen 1,3 cm darüber) mit fleckiger Kante (Rauschen je Eckpunkt) statt einer Querlinie |
| 2 | **Graskeil an der Kreiseleinfahrt** (Kreisel, km 0,77) | Untersucht: Die Ecke zwischen Fahrbahnkante der Strecke und der Kante der einmündenden Ringfahrbahn liegt außerhalb aller Straßenbänder (OSM-Breite `secondary` 6,5 m; der Boden malt 8 m). In der engen Linkskurve lief der Übergangsstreifen der Innenseite dort hinein — vorher durchscheinend, seit Runde 1 deckend. Kein Fehler in `RoadNet` | **behoben** mit 3 (ausgerundete Ecke füllt die Ecke mit Asphalt) |
| 3 | Bordsteinecken an Einmündungen eckig | Rand endete an der Kante der Nebenstraße; keine Ecke | **behoben:** neu `JunctionCorners.kt`. Wo eine Straße die Fahrbahn der Strecke verlässt (nicht fast gleichlaufend), entsteht auf beiden Seiten eine Ecke mit Radius 5 m im Ort / 4 m außerorts (Tangente ≤ 7 m, bei spitzen Winkeln kleinerer Radius), tangential an beide Kanten. `RoadsLayer` zeichnet die Fläche im Belag der Straße (8 Dreiecke) und im Ort einen **Bordstein entlang des Bogens** (`x_mat_road_kerb`, 12 cm); `RouteEnv` nimmt die Fläche bis hinter den Bordstein in die Sperrflächen auf — Gehweg und Gras laufen im Bogen um die Ecke. Budget: Ecken zuerst, aus dem Straßenbudget |
| 4 | Sichtbare Mulde hinter dem Grasstreifen (bis 19 cm) | `CarvedGround`: Bankett (25 cm, √2·Zelle + 0,3 m, Pflicht gegen Durchstechen) und danach Böschung über 3–10 m | **weiter geglättet:** Böschung für kleine Unterschiede `2 + 8·Δ` m (bei 25 cm Mulde 4 m statt 6,6 m), Übergangsstreifen läuft im Ort/Acker 6,0/6,5 m statt 4,4/5,0 m aus und deckt damit den größten Teil der Mulde; seine Höhe läuft gleichmäßig vom Randstreifen auf den Boden am Ende. Das Bankett selbst bleibt (sonst sticht das 4-m-Netz durch die Fahrbahn) |

**Weitere Vereinfachungsstufe:** Bei knappstem Routenbudget entfällt zuletzt der
Auslauf des Übergangsstreifens (`Detail(1, true, false, 1)`), weil die
geschnittenen Ränder mehr Dreiecke brauchen.

**Messwerte Nachrunde** (`-Xmx256m`, vorher = `e79c5bc`):

| Welt | Dreiecke | Platte | Heap nach GC (max) | Paare Streckenrand < 4 mm / < 1 cm |
|---|---|---|---|---|
| Altenberg kurz (Luftbild) | 466 669 → 467 081 | 18,3 → 18,3 MB/km | 207 → 209 MB | 28 → 16 / 83 → 63 |
| Balkantrasse 7,7 km (`--no-aerial`) | 448 598 → 448 854 | 19,2 → 19,2 MB/km | 232 → 214 MB | 5 → 15 / 35 → 42 |

Vertex-Deckung < 1 in durchsichtigen Flächen: weiter 0. Die zusätzlichen Treffer
der Balkantrasse liegen an Ecken, wo zwei Wege dicht nebeneinander einmünden und
sich zwei Eckflächen samt Bordstein überlagern (Bordstein bzw. Eckfläche gegen
den Auslauf des Grases, 6 Paare unter 4 mm) — offen.

**Offen nach der Nachrunde:** überlagerte Ecken dicht benachbarter Einmündungen
(s. o.); der Gehweg läuft an der Ecke nicht in die Nebenstraße weiter (er endet
im Bogen hinter dem Bordstein); an der Wendeschleife Altenberg kurz liegen die
Fahrbahnen von Hin- und Rückfahrt mit sichtbarer Kante übereinander (dünne
Linie); die Mulde ist hinter dem Grasstreifen über 2–4 m noch zu sehen.

### Letzte Runde: Flimmern an dicht benachbarten Einmündungen, Wendeschleife

Bildpaare: `docs/indoor/bilder/junctions/runde3-*` (vorher = `af62b95`).

| # | Mangel | Ursache (gefunden mit `overlaps --tol-cm` und Einzelstellen-Ausgabe) | Stand |
|---|---|---|---|
| 1 | Balkantrasse: Paare am Streckenrand unter 4 mm 5 → 15 nach den Ecken | (a) Die Sperrfläche einer Ecke war ein Fächer vom Eckpunkt zur **Hinterkante** des Bordsteins. Am Bogenende ist die Hinterkante senkrecht zur Straßenkante versetzt; der Strahl vom Eckpunkt lief am Bordstein vorbei, dort blieb der Grasauslauf 1–4 mm unter dem Bordstein (Stelle z. B. Balkantrasse e 1591,4 / n 1345,1, Kachel `L0_6_5`). (b) Eckflächen zweier dicht benachbarter Einmündungen überlagerten sich und ragten in Kurven Zentimeter über die gekrümmte Fahrbahnkante. (c) Auf der Innenseite enger Kurven drückt `limitOffset` die Querschnitte zusammen, Gras, Auslauf und Gehweg fielen übereinander | **behoben durch Aneinanderstoßen:** (a) Sperrfläche = Fächer bis zum Bogen **plus** Bordsteinstreifen als Vierecke zwischen Bogen und Hinterkante. (b) Jede Ecke wird an der Fahrbahn der Strecke, an den Bändern anderer Straßen und an bereits gezeichneten Ecken abgeschnitten (Budget zählt die entstehenden Dreiecke). (c) Wo die Innenseite zusammengedrückt ist, entfällt der Grasstreifen für dieses Stück. Balkantrasse **15 → 3** Paare unter 4 mm (unter 1 cm 42 → 18), Altenberg kurz **16 → 7** (63 → 27) |
| 2 | Dünne Linie an der Wendeschleife (Altenberg kurz, km 1,5) | Fahrbahnstücke derselben Strecke, 5–18 Laufmeter auseinander, überlagerten sich in der engen Kehre (Radius 2–5 m) mit 2–5 mm Abstand — das Fenster „eigene Fahrbahn sperrt nicht" war 15 m, die Sperrflächen nur aus Sehnen je halber Probe | **behoben:** Fahrbahn, Belagswechsel, Fransen und Linien werden an **früheren** Streckenteilen abgeschnitten (der frühere gewinnt), ab 3 Laufmetern Abstand (`MASK_OWN_ROAD_M`), mit 8 Sehnen je Probe. Selbstüberlagerung der Fahrbahn in Kachel `L0_0_0` 39 → 18 Dreieckspaare; die Linie ist im Bild weg. **Übrig:** Faltungen genau im Scheitel der Kehre (Radius 2–3 m), wo `limitOffset` die Innenkante innerhalb **eines** Stücks zusammenfaltet — Abschneiden hilft dort nicht |
| 3 | Gehweg läuft an der Ecke nicht in die Nebenstraße weiter | — | **nicht umgesetzt** (aus Zeitgründen; der Gehweg endet im Bogen hinter dem Bordstein) |

Messwerte (`-Xmx256m`, vorher = `af62b95` bzw. Zahlen der Nachrunde): Altenberg kurz 467 137 Dreiecke,
18,3 MB/km, Heap nach GC max 214 MB; Balkantrasse 448 861 Dreiecke, 19,3 MB/km (vorher 19,2), Heap 229 MB.
Vertex-Deckung < 1 weiter 0.

## Luftbild Baden-Württemberg (24.11.2026, Branch `claude/indoor-luftbild-bw`)

Entscheidung des Gründers: Das DOP20 des LGL ist auch beim Bau **eigener
Strecken auf dem Gerät** die Bodentextur, wie in NRW, Sachsen, Thüringen und
Brandenburg/Berlin. Bisher stand es nur im Register des Werkzeugs
(`CatalogAerial.kt`, Katalogbau Schauinsland); dort ist es jetzt entfernt,
Katalogbau und App nehmen den Eintrag aus `aerialSources`.

### Eintrag und Prüfung (echte Abrufe am 24.11.2026)

| | |
|---|---|
| Dienst | `https://owsproxy.lgl-bw.de/owsproxy/ows/WMS_LGL-BW_ATKIS_DOP_20_C` (GetCapabilities 11 kB, 1,3 s) |
| Ebene / Stil | `IMAGES_DOP_20_RGB`, einziger Stil `default` („DOP farbig 20cm“) — es gibt keine zweite Farbebene, die man verwechseln könnte |
| Format | JPEG Baseline, JFIF, 3 Kanäle, **ohne Farbprofil** (sRGB). PNG käme als 8-Bit-**Palettenbild** — wird nur bei progressivem JPEG angefragt, also nie |
| Größe, Zeit | 512 px 1,2 s / 51 kB · 1024 px 1–3 s / 190–290 kB · 2048 px 3,2 s / 0,66 MB · 4096 px 6,5 s / 2,1 MB; `MaxWidth` 15 000 (16 000 × 100 liefert trotzdem ein Bild). `maxSizePx` bleibt bei der Vorgabe 2048 — der Weltbau fragt höchstens 1024 an |
| Rechteck | 47,53–49,80° N, 7,50–10,50° O (Capabilities: 47,40–50,00 / 7,20–10,70). Überlappt keinen anderen Dienst der App (Test) |
| Landesgrenze | **grenzscharf**: außerhalb BW ein weißes, dreikanaliges JPEG (21 kB) → als Leerbild erkannt, erzeugter Boden. Eine Kachel **über** der Grenze (Kehl/Rhein) ist zu 29 % weiß und wird so genommen — wie bei den anderen Ländern; der weiße Teil liegt jenseits der Grenze |
| Quellenvermerk | `Luftbild: Datenquelle: LGL, www.lgl-bw.de, dl-de/by-2-0 (Datenbezug <Jahr>)` — Pflichtvermerk laut lgl-bw.de/Produkte/Open-Data, dazu das Jahr des Datenbezugs, das die Capabilities (`AccessConstraints`) verlangen. Jahr = Startjahr von App bzw. Werkzeug (`java.time.Year`, minSdk 26). Steht im Manifest jeder BW-Welt (HUD, Weltverwaltung) und unter *Mehr → Open-Source-Hinweise* |
| Lizenz | dl-de/by-2-0 |

Tests (`AerialTest`): Registereintrag samt Pflichtvermerk und Jahr,
Abdeckung (Strohgäu, Schauinsland, Maulbronn, Konstanz ja; NRW, Dresden,
München nein), keine Überlappung, Abruf und unveränderte Einbettung einer
echten LGL-Kachel, Leerbild von jenseits der Grenze → `null`, Positivliste
nur für genau diesen Pfad und nur `https`. Testdaten in
`core/src/test/resources/indoor/world/` (README).

### Messwerte: Strohgäu, 4 km, wie die App baut

Strecke `tool/indoor-dev/samples/strohgaeu.gpx` (BRouter, Ditzingen →
Hirschlanden, km 0–4), `JAVA_OPTS=-Xmx256m`, MEDIUM, ohne Laser.
„Vorher“ = App-Register ohne BW (erzeugter Boden), „nachher“ = mit BW.
Höhen und Vektorkacheln lagen bei beiden Läufen im Cache, der Luftbild-Cache
war vor „nachher“ geleert.

| | vorher | nachher |
|---|---|---|
| Kacheln mit Luftbild | 0 / 73 | 73 / 73 |
| Luftbild-Download | — | 73 Anfragen, 7,3 MB (≈ 1,8 MB/km) |
| Bauzeit | 26 s | 50 s |
| Heap belegt am Ende (Grenze 256 MB) | 163 MB | 90 MB |
| Texturen (Budget 256 MB) | 159,4 MB | 161,1 MB |
| Dreiecke gesamt / gleichzeitig im Betrachter (Budget 700 000) | 251 097 / ≤ 229 000 | 411 344 / ≤ 326 000 |
| Platte | 47,9 MB (12,0 MB/km) | 65,6 MB (16,4 MB/km) |
| `checkWorld` | in Ordnung | in Ordnung |

Mehr Dreiecke und Platte mit Luftbild: Bäume und Waldkörper kommen dann auch
aus der Kronenerkennung im Luftbild (Feldgehölze, Streuobst). Zum Vergleich
der Werkzeugbau mit `--catalog-aerial` (alter Stand): dieselbe Welt, aber
146 Anfragen und 199 s — dort wurde vor dem LGL jedes Mal das IGN
(Frankreich, Rechteck reicht bis 9,6° O) gefragt, das ein Leerbild lieferte.

### Farbe: „blass bis weißlich“

Untersucht an Maulbronn (`lidar-bw/kloster-maulbronn-*.jpg`) und am Strohgäu.
**Ergebnis: kein Fehler in den BW-Daten und nichts BW-Eigenes in unserem
Bau.**

- Falscher Layer/Stil: ausgeschlossen (nur eine Ebene, ein Stil).
- Farbraum/Gamma: JFIF ohne ICC-Profil, also sRGB; der Weltbau bettet die
  Bytes unverändert ein, der Betrachter liest sie als sRGB wie bei allen
  Ländern. Kein Palettenbild (PNG wird nicht angefragt).
- Fehlende Angleichung: Kein Land bekommt eine (alle Luftbilder gehen
  unverändert in die GLB).
- Aufnahme: Wiesen im BW-Luftbild sind **nicht** blasser als in NRW —
  grüne Pixel (Farbton 0,17–0,45) Strohgäu S 0,18 / V 0,44, NRW bei Soest
  S 0,16 / V 0,45; Hessen V 0,58. Hell sind die **offenen Äcker** der
  Befliegung (Saatbett/Stoppeln, sRGB um 185/170/155; Kraichgau-Mittel 161,
  Strohgäu 135, NRW-Börde 92) — Jahreszeit der Befliegung, BW aktualisiert
  jährlich rund 30 % der Fläche.
- Darstellung: Gemessen im Betrachter (Sonne 40°, Belichtung 0,62, ACES).
  Wiese km 0,5 im Bild RGB 106/127/102 (S 0,19) — fast genau die Farbe des
  Luftbilds; das Luftbild **ist** so grau-grün, es wirkt nur neben dem
  kräftigen erzeugten Gras (`feldweg-0500-vorher`) blass. Acker km 1,5:
  Luftbild 184/171/155 (S 0,16) → im Bild 213/210/203 (S 0,05), also in der
  Schulter der Tonabbildung fast weiß. Ohne Dunst (`fog=0`) dasselbe
  (212/207/200): nicht der Dunst. Mit Belichtung 0,40 statt 0,62
  191/186/178 (S 0,07, Bilder `*-belichtung-040.jpg`): heller Boden
  wird über die Belichtung bzw. ACES-Schulter entsättigt. Das trifft jedes
  Land mit hellen Äckern gleich; NRW-Bilder fallen weniger auf, weil die
  geprüften NRW-Befliegungen dunkler sind.

Behoben habe ich deshalb nichts: Ändern ließe es sich (a) in der Darstellung
(Belichtung/Tonabbildung für den Boden — Slice indoor-render) oder (b) im
Weltbau durch eine Angleichung der Luftbilder vor dem Einbetten (neu
kodieren; widerspricht „unverändert“ und kostet Bauzeit und Qualität). Beides
betrifft alle Länder und ist eine eigene Entscheidung — offen unten.

### Bilder (Betrachter, nicht Gerät)

Unter `docs/indoor/bilder/luftbild-bw/`, Strohgäu, mit Fahrer, Sonne
235°/40°. `<name>-vorher.jpg` = erzeugter Boden, `<name>-nachher.jpg` = LGL-Luftbild.

| Bild | Laufmeter, Kamera | Was man sieht |
|---|---|---|
| `feldweg-0500` | 500, Fahrer | Feldweg zwischen Wiesen und Obstbaumreihe; mit Luftbild Baumreihe aus der Kronenerkennung, Wiese grau-grün |
| `acker-1500` | 1 500, Drohne | große Ackerschläge; der helle Acker wird fast weiß (siehe Farbe) |
| `streuobst-3300` | 3 300, Drohne | Streuobst und Waldrand am Weg, Ackerflur bis zum Horizont |
| `ortsrand-3300` | 3 300, Fahrer | Weg am Gehölz |
| `acker-1500-belichtung-040`, `feldweg-0500-belichtung-040` | wie oben, nur Belichtung 0,40 | Belegbild zur Tonabbildung, keine Änderung im Code |
| `rohbild-acker-1500` | LGL-GetMap um km 1,5 (verkleinert) | das Luftbild selbst zum Vergleich |

### Auswirkung auf bestehende Welten

- **Eigene Strecken in BW, schon gebaut:** bleiben, wie sie sind (fertige
  Welten werden nicht neu gebaut). **Neu gebaut** bekommen sie das Luftbild
  statt des erzeugten Bodens, dazu Bäume aus der Kronenerkennung — also eine
  andere Welt. Damit die App bestehende BW-Welten als veraltet erkennt, wäre
  `WORLD_BUILDER_ID` anzuheben (nicht hier gemacht, Sache des Orchestrators).
  Welten außerhalb BW sind nicht betroffen.
- **Katalog Schauinsland:** schon mit LGL-Luftbild vorgebaut; die
  Luftbild-URLs und -Bytes bleiben gleich (gleicher Dienst, Ebene, Rechteck,
  Größe). Ein Neubau änderte nur den Quellenvermerk im Manifest
  („Datenquelle: LGL …“ statt „LGL-BW (2026) Datenlizenz …“, beide
  lizenzgerecht) und spart die IGN-Anfragen. Eine neue Katalogversion ist
  **nicht nötig**; wird ohnehin neu gebaut, erscheint der neue Wortlaut.

### Offen

- [ ] Heller Boden (Äcker) wird in der Darstellung fast weiß und entsättigt
      — alle Länder; Entscheidung Darstellung (Belichtung/Tonabbildung) oder
      Angleichung im Weltbau.
- [ ] Kacheln über der Landesgrenze: weißer Teil jenseits der Grenze (alle
      grenzscharfen Dienste); denkbar wäre, Weiß dort durch die erzeugte
      Bodentextur zu ersetzen.
- [ ] Nur im Betrachter gesehen, nicht auf dem Gerät.

## Abweichungen vom Vertrag

1. ~~Laub `alphaMode = MASK` ohne Ausschnitte.~~ Seit „Vegetation" (oben)
   Blattkarten mit echtem Alpha-Ausschnitt in `x_mat_tree_cards` (MASK,
   **einseitig**, beide Wicklungen im Netz); `mat_tree_leaves` (MASK,
   doppelseitig, wie im Vertrag) trägt das deckende Kronendach der Waldkörper.
   **Erweiterung `KHR_materials_specular`** (Vertrag 7.3: „keine weiteren
   Erweiterungen“): nur `specularFactor` 0 auf Vegetationsmaterialien, nur in
   `extensionsUsed`. Ohne sie überdeckt der Spiegelanteil der Sonne bei
   streifendem Blick das Grün (Runde 3/3b). gltfio 1.75.1 unterstützt sie im
   Ubershader (`specular_masked`, Belege oben). `GlbCheck` und Betrachter
   lassen genau diese Erweiterung zu. **Bitte an den Vertragsbesitzer:**
   7.3 entsprechend ergänzen.
2. **Wasser nur als Flächen.** Schmale Wasserläufe (Linien `waterway`)
   bekommen keinen eigenen Spiegel; sie erscheinen nur im Luftbild bzw. in der
   erzeugten Bodentextur. Wasser liegt als Gitterzellen (4 m in Stufe 0) knapp
   unter dem Ufer; das Gelände verdeckt die Zellenkanten.
3. **Dreiecksbudget je Kachel umverteilt** (`TileBudget`, nur intern): Wasser
   Stufe 0 500 → 2 000, Stufe 1 300 → 600, dafür Vegetation 22 000 → 20 500 bzw.
   4 000 → 3 700. Die Bäume nehmen zusätzlich, was Fahrbahn, Straßen,
   Gebäude und Wasser derselben Kachel nicht verbraucht haben. Die Summe je
   Kachel und damit die Planung bleiben gleich.
4. **Waldkörper auch in Stufe 0** jenseits von 70 m neben der Strecke (der
   Vertrag nennt sie nur für Stufe 1): sonst wäre Wald dort leer.
5. **Belagsfarben/-texturen von `UNKNOWN`** wie `COMPACTED` (`SurfaceKind.visual`),
   wie vereinbart.
6. **Abruf im Werkzeug** über `java.net.http`, `User-Agent: Trailscape-indoor-dev`.
7. **`Retry-After`** wird nicht ausgewertet (kommt über `GeoResponse` nicht an);
   bei 429/503 wartet die Hülle 2 s · 4 s · 8 s.

## Annahmen

- Die Darstellung zeigt `COLOR_0` multipliziert mit der Grundfarbe (Bäume,
  Wasser, Waldkörper und Kleinteile sind nur über Vertexfarben gefärbt).
- Kronenbedeckung aus dem Luftbild ist an NRW-Orthofotos abgestimmt; in
  Sachsen sah sie plausibel aus, andere Länder ungesehen.
- Die Durchschnittsgrößen im Voranschlag (`WorldEstimates`) sind Schätzwerte;
  gemessen: Altenberg kurz (3 km, MEDIUM) 47,9 MB Platte (15,9 MB/km, knapp über
  dem Richtwert von 15 MB/km), Tharandt 4 km 51,3 MB (12,8 MB/km), Testwelt 30 MB.
- Bauzeit auf dem Entwicklerrechner: 3 km mit leerem Cache rund 19 s
  (111 Anfragen); auf dem Handy deutlich länger (nicht gemessen).

## Netz-Hosts (für PRIVACY.md)

Nur nach „Welt laden“, nur über die Positivliste (`isAllowedGeoUrl`), nur
HTTPS, keine Weiterleitungen. Übertragen werden Kachelkoordinaten bzw.
Kartenausschnitte (Umgebungsrechtecke) entlang der gewählten Strecke.

| Host | Zweck | Pfad |
|---|---|---|
| `s3.amazonaws.com` | Höhen (Terrarium, AWS Open Data) | `/elevation-tiles-prod/terrarium/…` |
| `tiles.openfreemap.org` | Vektorkacheln (TileJSON + MVT) | `/planet…` |
| `www.wms.nrw.de` | Luftbild NRW | `/geobasis/wms_nw_dop` |
| `geodienste.sachsen.de` | Luftbild Sachsen | `/wms_geosn_dop-rgb/guest` |
| `www.geoproxy.geoportal-th.de` | Luftbild Thüringen | `/geoproxy/services/DOP` |
| `isk.geobasis-bb.de` | Luftbild Brandenburg und Berlin | `/mapproxy/dop20c/service/wms` |
| `owsproxy.lgl-bw.de` | Luftbild Baden-Württemberg | `/owsproxy/ows/WMS_LGL-BW_ATKIS_DOP_20_C` |

Quellenangaben stehen je Welt in `WorldManifest.attribution` (Höhen, Vektor,
je genutztem Luftbild-Dienst mit Lizenz).

## Integration (23.11.2026)

Gemeinsam mit indoor-render und indoor-app gebaut und geprüft:

- Die App baut mit dem echten Weltbau (`createWorldBuilder` in
  `DefaultIndoorParts`); `IndoorViewModelDemoFlowTest` fährt die echte
  Testwelt (ohne Netz, GLB-Kacheln aller drei Stufen) auf dem Band des
  Manifests (`worldRouteOf`) bis zur gespeicherten Indoor-Tour.
- `./gradlew :indoor-dev:run --args="testworld --out tool/indoor-dev/out/testwelt --quality MEDIUM"`
  läuft (9,6 s, 51 Kacheln, 25,7 MB, Budget eingehalten, Prüfung in Ordnung).
- Die GLBs passen zur Darstellung: Namen, Anker, Teilflächen, Materialien und
  Texturformate wie in `docs/indoor/render.md` (Abschnitt Integration)
  beschrieben. Dabei aufgefallen: Ab Stufe 1 hängt aller Inhalt an
  `terrain_sub_i_j` und gilt der Darstellung damit als Gelände (keine
  Schatten, Schalter Bäume/Gebäude nur für Stufe 0) — gewollt, damit die
  Teilfläche samt Waldkörpern verschwindet.
- Gefahren wird mit dem Manifest, das `buildTestWorld`/`build` zurückgibt;
  aus `manifest.json` neu gelesen weicht die Lage nur in der Rundung ab
  (< 1e-6°), die Laufmeter sind gleich.
- Abbruch: Die App zeigt „Wird abgebrochen …“ sofort, solange
  `runParallel` die laufenden Kacheln (bis zu vier) bzw. Anfragen noch zu Ende
  bringt. Ein schnellerer Abbruch innerhalb einer Kachel bleibt offen (siehe
  „Auf dem Gerät zu prüfen“).

## Lange Strecken auf dem Handy (Fehlerbericht, 23.11.2026)

Der Gründer bekam für eine echte Strecke „Beim Bau der Welt ist ein
unerwarteter Fehler aufgetreten.“ Nachgestellt mit dem Werkzeug an zwei
langen Strecken (BRouter `trekking`, echte Wege): Freiberg → Tharandt →
Dresden (42 km, Luftbild Sachsen, Stadtende) und eine Runde um den Ammersee
(101 km, Start = Ziel, ohne Luftbild). Zwei Ursachen, beide behoben:

1. **„Hoehenkachel … fehlt“ (INTERNAL)** — bei *beiden* Strecken, unabhängig
   vom Speicher. `CarvedGround.roadProfile` tastet das Längsprofil einer
   Straße auf ihrer ganzen Länge ab; über Kachelgrenzen verkettete Straßen
   reichen weit über die geladenen Höhenkacheln hinaus, und
   `ElevationField.heightAt` warf dort `ElevationMissingException`. Kurze
   Strecken (Altenberg, Tharandt) trafen das nicht. Jetzt nimmt
   `TerrainHeights` das andere Feld bzw. den nächsten geladenen Rand
   (`heightAtNearest`); das Feld selbst bleibt streng.
2. **`OutOfMemoryError` bei 256 MB Heap** (Handy ohne `largeHeap`): Der Bau
   hielt die rohen Vektorkacheln (bei 42 km rund 100 MB Merkmale samt
   Tag-Maps) neben den abgeleiteten Daten, dazu rohe und geglättete Höhen,
   12 fertige Luftbilder im Vorrat und unbegrenzt wachsende Zwischenspeicher
   (Landnutzungs-Blöcke, Straßenprofile). Jetzt: rohe Kacheln nach dem
   letzten `mapData()` frei (`GeoLoader.releaseRawTiles`), Rohhöhen nur
   während der Glättung, Luftbild-Vorrat 2, `BoundedCache` (LRU) für
   `LandRaster` und `CarvedGround`, Threads nach `Runtime.maxMemory()`
   (`defaultWorldThreads`), und `android:largeHeap="true"` in der App.

Messung (Entwicklerrechner, `-Xmx256m`, 2 Threads, Geodaten aus dem Cache):

| Strecke | vorher | nachher | Kacheln | Platte |
|---|---|---|---|---|
| Freiberg–Dresden 42 km | OOM nach 59 Kacheln (Live-Set ~210 MB vor dem ersten Bau), danach „Hoehenkachel fehlt“ | 83 s, Live-Set nach Full-GC 117–131 MB | 496 | 578 MB |
| Ammersee-Runde 101 km | „Hoehenkachel fehlt“ (auch mit 3 GB) | 270 s, Live-Set ≤ 104 MB | 932 | 922 MB |

Download ohne Cache laut Voranschlag 80 MB (Freiberg–Dresden, davon
Luftbild ~62 MB) bzw. 17 MB. Auf dem Handy ist mit dem 2–4-Fachen der
Rechenzeit plus Download zu rechnen.

Außerdem: Weicht der Ursprung nach dem Laden der Höhen in der Lage ab
(Vertragsverstoß eines `WorldParts`), bleibt die vorläufige Lage statt
INTERNAL; ein überschrittenes Budget lässt den fertigen Bau nicht mehr
scheitern; jeder `Throwable` im Bau wird zu `WorldBuildFailed(INTERNAL)` mit
Ursache, und die App zeigt sie als kopierbare „Technische Einzelheit“.

## Bitten an andere Slices

- **indoor-app** (erledigt bei der Integration: Hosts in `PRIVACY.md`,
  Lizenzen in `OpenSourceNotices`, ⓘ im HUD, Bau auf `Dispatchers.IO`):
  die sechs Hosts oben in `PRIVACY.md` aufnehmen;
  Quellenangaben aus `WorldManifest.attribution` in Weltverwaltung, HUD-„ⓘ“
  und `OpenSourceNotices` (dort zusätzlich: Höhen Tilezen/AWS, OpenFreeMap/
  OpenMapTiles/OSM ODbL, Luftbilder dl-de/zero-2-0, dl-de/by-2-0, CC BY 4.0).
  `WorldBuilder.build` auf `Dispatchers.IO` aufrufen; Abbruch über
  `isCancelled()` greift innerhalb von 50 ms, eine laufende Kachel wird aber
  noch zu Ende gerechnet.
- **indoor-render** (teils erledigt: Umgebungslicht bei tiefer Sonne 26 000 statt
  22 000 lx): Laub ist `MASK` + doppelseitig ohne echte Ausschnitte;
  Waldkörper und Kronen leben von Vertexfarben (`COLOR_0`) und haben
  geglättete Normalen. **Wald unter Kronendach ist zu dunkel:** Im Weltbau
  ist das nicht lösbar, ohne den Vertrag zu brechen (Luftbilder werden
  unverändert eingebettet, der Boden unter dem Wald ist also das dunkle
  Orthofoto). Bitte das Umgebungslicht (`IndirectLight`) kräftig genug wählen
  und die Schattenstärke begrenzen (Schatten nicht schwarz), ggf. Dunst.
  Wasser hat `roughness 0,6` ohne Spiegelung.
- **indoor-core:** keine.

## Auf dem Gerät zu prüfen

- [ ] Testwelt und eine echte Welt (MEDIUM) in Filament: Bäume, Waldkörper,
      Wasser sichtbar, keine Löcher an Kachelgrenzen und beim Umschalten der
      Teilflächen.
- [ ] Bauzeit und Datenmenge für eine 30–50-km-Runde über WLAN/Mobilfunk
      (Werkzeug: 42 km in 83 s bei 2 Threads aus dem Cache, s. o.).
- [ ] Speicher bei HIGH (Texturen bis 400 MB laut Budget).
- [ ] Abbruch während „Welt laden“ und Fortsetzen; wie lange steht „Wird
      abgebrochen …“ auf dem Handy (laufende Kacheln/Anfragen)? Bei mehr als
      einigen Sekunden `isCancelled` auch innerhalb einer Kachel abfragen.
- [ ] Wirkt der Wald im Gegenlicht und bei tiefer Sonne nicht zu dunkel?
