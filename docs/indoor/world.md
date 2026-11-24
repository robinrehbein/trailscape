# Indoor — Slice indoor-world (Weltbau und Entwicklerwerkzeug)

Stand: 23.11.2026, Branch `claude/indoor-world`. Vertrag: `docs/indoor.md`
(Abschnitte 7.1–7.5, 8, 9).

## Stand

| Teil | Stand |
|---|---|
| Einstieg `IndoorWorldEntry.kt` | **ohne Platzhalter.** `createWorldBuilder()` → `WorldBuilderImpl(FullWorldParts)`, `createGeoPipeline` → Positivliste, Plattencache, Drosselung; Register `aerialSources` mit NRW, Sachsen, Thüringen, Brandenburg/Berlin |
| Voranschlag `plan` | ohne Netz: Kacheln, Anfragen je Art, Download, Platz, Hosts, Luftbild-Dienste |
| Bau `build` | Höhen (Terrarium z13/z11) → Ursprung → Vektorkacheln (TileJSON, z14/z12) → Brücken, Belag aus Vektorkacheln, falls die Strecke keinen hat → Streckenband → endgültige Planung → Kacheln parallel (bis 4 Threads, nach `Runtime.maxMemory()` begrenzt: bei 256 MB Heap 2), atomar geschrieben, `manifest.json` zuletzt. Fortsetzen nach Abbruch (fertige Kacheln bleiben). `isCancelled()` wird mindestens alle 50 ms gefragt |
| Testwelt `buildTestWorld` | ohne Netz, 3 km Runde mit allen Belagsklassen, Dorf, Laub- und Nadelwald, Felder, Teich, Bach mit Holzbrücke; deterministisch (Byte-gleich) |
| Streckenband | Lage geglättet (≤ 1,2 m), Höhe vom Gelände (σ 12 m), Brücken überbrückt, Steigung ≤ 24,5 %, Laufmeter unverändert |
| Gelände | `CarvedGround`: gedämpftes Höhenmodell, Straßen quer eben, Strecke mit Bankett und Böschung (Nachweis im KDoc: kein Dreieck sticht durch die Fahrbahn), Wasser abgesenkt; Stufe 0 Gitter 4 m (MEDIUM), ab Stufe 1 4×4 Teilflächen, Schürzen |
| Bodentextur | Luftbild unverändert (JPEG Baseline, sonst PNG neu angefragt) oder aus Landnutzung erzeugt; Leerbild-Erkennung |
| Fahrbahn `route` | je Belag kachelnde Textur, weicher Belagswechsel, Ränder nach Belag und Umgebung (Bankett, Bordstein mit Gehweg, Graskante), Böschungsstreifen, Markierung auf Landstraßen |
| Straßen `roads` | übrige Straßen und Wege der Karte, flach auf dem Gelände |
| Gebäude `buildings` | Grundriss × Höhe, Satteldächer/Walmdächer, Anbauten, Fassadenatlas, Stufe 1 grob. Dachfarbe je Gebäude (deterministisch): Ziegelrot, Rotbraun, Braun, Anthrazit, Grau, wo möglich aus der Luftbildfarbe; Grau aus dem Luftbild wird bei Ziegeldächern teils als Rotbraun/Braun gedeutet. Die Vektorkacheln nennen keine Gebäudeart (kein church/warehouse); große Grundrisse (> 800 m²) werden nur in Gewerbe- und Landwirtschaftsflächen zur Wellblechhalle, sonst zu Putz-/Ziegelbauten mit Fenstern und Ziegeldach (Traufe 6,6–9,6 m, wenn die Daten keine Höhe nennen) |
| Bäume `trees` | **neu:** Einzelbäume in vier Arten (runder Laubbaum, schlanke Pappel/Birke, gestufte Fichte, kleiner Feld-/Obstbaum) bis 70 m neben der Strecke: verbeulte, überlappende Kronenkörper mit geglätteten Normalen, Größen- und Farbvariation, Wurzelanlauf. Drei Detailstufen, budgetabhängig vom Weg nach außen vergeben. Standorte aus der Kronenbedeckung des Luftbilds bzw. der Landnutzung; dahinter und in Stufe 1 Waldkörper (hügeliges Kronendach, das am Waldrand bis zum Boden abfällt, ohne Wände) |
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
- **Bäume:** vier Arten mit runden Kronen, aber weiterhin Körper ohne
  Laubkarten; keine Sträucher, kein Unterholz. In dichtem Wald reicht das
  Budget nur für die nächsten Reihen in feiner Stufe.
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

**Welt-Version:** `WORLD_BUILDER_ID` = `indoor-world/2` (vorher `/1`). Sie geht
in die Welt-ID ein — selbst gebaute Welten der alten Fassung werden nicht mehr
gefunden und neu gebaut (die alten bleiben in der Weltverwaltung löschbar).
Katalog: `version` aller Strecken in `catalog-src.json` auf 2.

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
`schauinsland-tal-fernsicht`. Vorher = Stand `main` (6cd9387), beide mit
demselben Betrachter gerendert.

### Wie es gebaut ist

- **Blattkarten.** Kronen sind viele kleine, verdrehte Vierecke mit einem Motiv
  aus dem Atlas und Alpha-Ausschnitt (`mat_tree_leaves`: `MASK`, Schwelle 0,5,
  doppelseitig — wie im Vertrag, jetzt mit echten Ausschnitten). Stamm und
  (nah) die Hauptäste zu den Teilkronen sind Geometrie. Laubbaum nah: 104
  Zweigkarten auf 5 Teilkronen + 16 innere Laubmasse-Karten (≈ 300 Dreiecke,
  vorher ≈ 970 für den Kugelkörper).
- **Licht ohne eigenen Shader.** Eckpunkt-Normalen sind zu 70–90 % die Richtung
  vom Kronenmittelpunkt aus („gebogene Normalen"; senkrecht auf 45 % gestaucht,
  weil die Kamera fast immer unter den Kronen ist — steil nach oben zeigende
  Normalen ergaben von unten gesehene, schwarze Kronenoberseiten): Die
  Krone schattiert wie ein weicher Körper, Karte für Karte flimmert nichts.
  Verschattung innen/unten steckt in den Vertexfarben (Bäume empfangen keine
  Schatten). **Vorderseite = Richtung der gebogenen Normalen:** Filament
  (`doubleSided`) und three.js drehen auf der Rückseite die Normale um; lag
  eine Karte „falsch herum", zeigte die umgedrehte Normale zur Sonne, und
  innere Karten erschienen als blasse Flächen (im Betrachter gesehen, per
  Motiv-Einfärbung gefunden). Mit der Regel sieht man von unten/hinten die
  dunkle Seite.
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
  Kronenmosaik (`x_mat_tree_canopy`, JPEG 256², 30 m je Kachelung) statt
  Sprenkeltextur; am Waldrand je Randzelle ein Kreuzbild eines ganzen Baums
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
in `Glb.kt` — nicht gemacht, weil auf dem Gerät ungeprüft und außerhalb dieses
Auftrags. Texturen +23 MB: Atlas 1,4 MB GPU je Kachel der Stufe 0, Kronendach
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
| Kronen zu licht / Stangenwald, wenig Mittelschicht | **offen:** Budget (Deckel 13 000 je Kachel); mehr Dichte kostet Platte |
| Karte schneidet die Nahebene am Wegrand | **offen** (selten; Kronen am Weg kürzen) |

### Offen

- **Kronendichte im dichten Wald:** Je 256-m-Kachel reicht der Deckel für rund
  40 volle Kronen; dahinter mittlere und ferne Stufe. Wirkt licht
  („Stangenwald"). Hebel: Deckel anheben (Platte +), `UNSIGNED_SHORT`-UV.
- **Waldboden dunkel:** Luftbild unter Kronen (Gelände/Darstellung, nicht hier).
- **Kronendach der Waldkörper** am Steilhang aus der Nähe weiterhin als Rampe
  mit Kanten zu sehen, wo keine Randbäume stehen (Schauinsland rechts oben).
- **Von hinten gesehene Karten** sind grundsätzlich dunkler (Normalenumkehr
  doppelseitiger Materialien) — die Regeln oben machen es selten, nicht unmöglich.

### Nur im Betrachter geprüft (Gerät steht aus)

- Alles Sichtbare: three.js mit nachgebildetem MASK (Alpha-to-Coverage,
  Filaments Schärfung), SwiftShader, `HemisphereLight` statt SH-Umgebungslicht.
  Wie hell die Rückseiten und Kronenunterseiten unter Filaments `IndirectLight`
  wirken, ist offen.
- Bildrate: deutlich mehr Alpha-getestete Fläche (Overdraw, `discard` schaltet
  Early-Z ab) bei weniger Dreiecken — auf dem Handy messen (MEDIUM, 4× MSAA).
- PNG Grau+Alpha (Farbtyp 4) in gltfio: nach den Quellen (stb_image auf RGBA
  erweitert) unkritisch, auf dem Gerät nicht gesehen.

## Abweichungen vom Vertrag

1. ~~Laub `alphaMode = MASK` ohne Ausschnitte.~~ Seit „Vegetation" (oben)
   Blattkarten mit echtem Alpha-Ausschnitt, also wie im Vertrag. Die
   Waldkörper tragen ein eigenes, deckendes Material `x_mat_tree_canopy`.
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
