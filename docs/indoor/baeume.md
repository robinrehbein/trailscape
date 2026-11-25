# Indoor — Bäume aus der Nähe (Prototyp)

Stand: 25.11.2026, Branch `claude/grafik-baeume`. Teil des Grafik-Schwarms
(Auftrag: „deutlicher Schritt nach vorn, quasi fotorealistisch“); dieser Teil
behandelt **nur Bäume in der Nahzone**. Der Prototyp ist **nicht eingebaut**:
Weltbau (`core/**/indoor/world/**`), Streckenformat, `.mat`-Dateien und
Renderer sind unverändert. Einzige Änderung außerhalb des neuen Pakets: eine
Zeile in `tool/indoor-dev/.../Main.kt` (Kommando `tree-proto`).

**Ehrlich vorweg:** Fotorealismus ist das nicht. Es ist der Schritt von
„Kronenlappen aus großen Laubkarten an einem Rohr“ zu „Baum mit echtem Stamm,
Ästen, Zweigen und kleinen Blättern“. Alles ist nur in Bildern aus dem
three.js-Betrachter (SwiftShader) und dem Filament-Referenzrenderer (Filament
1.75.1 auf Mesa llvmpipe) geprüft, **nichts auf einem Gerät** (Galaxy S25),
keine Bildrate gemessen.

## Was es gibt

| Teil | Datei | Stand |
|---|---|---|
| Arten und Wachstumsparameter | `core/src/main/kotlin/de/trailscape/core/indoor/tree/TreeSpecies.kt` | 9 Arten: Buche, Eiche, Birke, Pappel, Fichte, Kiefer, Aleppo-Kiefer (Mallorca/Sa Calobra), Obstbaum, Strauch; je 4 Varianten (Höhe, Breite, Zufall) |
| Wachstumsmodell | `TreeSkeleton.kt` (`TreeGrowth`) | Weber/Penn vereinfacht: Stamm mit Schräglage/Krümmung, Hauptäste spiralig (137,5°) oder in Quirlen (Fichte, Kiefer), Länge nach Kronenform, Abgangswinkel oben steiler, Seitenäste wechselständig in der Astebene, Krümmung über die Astlänge (Birke hängend), mehrere Triebe (Strauch); Laub als Zweigbüschel an Endästen und im äußeren Teil der Hauptäste; deterministisch (SplitMix64 aus Art, Variante, Same) |
| Vernetzung, Budget | `TreeMesher.kt` (`TreeMesher`, `TreeBudget`) | verjüngte Röhren (parallel mitgeführter Rahmen), Ringseiten nach Umfang, Wurzelanlauf mit Rippen; Rinden-UV in der Spalte der Art, v nach Umfang (unverzerrt); Laub: 2–3 gekreuzte Karten je Büschel, Normalen kugelig aufgeweicht, Verschattung innen/unten in `COLOR_0`; dunkle Kernkarten im Kroneninneren; mittlere Stufe: Hauptäste + zusammengefasste Büschel (gleiche Silhouette); hartes Dreiecksbudget je Art und Stufe |
| Texturen | `TreeTextures.kt` | Laubatlas 512² RGBA-PNG (Laubbüschel, Fichtenzweig, Kiefernbürste, Birkenzweig, Laubmasse), Quadranten wie `foliage.mat`/`windWeight` sie erwartet; Rindenatlas 512² JPEG mit 4 kachelnden Spalten (glatt/Buche, Borkenplatten/Eiche–Kiefer–Pappel–Obst, Birke, Schuppen/Fichte). Alles im Code gezeichnet |
| Vorlagen, GLB | `TreeLibrary.kt` | `template`, `templateGlb` (eine Vorlage, Knoten `trees`), `libraryGlb` (alle 72 Vorlagen, Knoten `x_tree_<art>_<v>_<stufe>`), Kennzahlen |
| Vergleich, Messung | `TreeCompare.kt` (`writeTreeComparison`) | Welten `alt/`, `neu/`, `schau-alt/`, `schau-neu/`, `cameras.json`, `metrics.txt/json`, `library.glb` |
| Tests | `core/src/test/kotlin/de/trailscape/core/indoor/tree/TreeGeneratorTest.kt` | 9 Tests: Determinismus, Budget je Art/Variante/Stufe, gültige Netze und UV, Proportionen je Art, mittlere Stufe hält die Silhouette, Normalen kugelig, GLB nach Kachelregeln (`checkTileGlb`) ohne neue Erweiterung, Atlanten ohne dunkle Säume und kachelnd, Vergleichsszene gültig |
| Werkzeug | `indoor-dev tree-proto --out <dir>`; `tool/indoor-dev/baeume/render.py` | Vergleichswelten schreiben; Bilder je Kamera mit Betrachter und/oder filarender, Vergleich links alt / rechts neu |

### Warum ein Paket in `:core` (und nicht im Werkzeug)

Der spätere Einbau läuft im Weltbau (`:core`, `TreesLayer`) bzw. erzeugt ein
App-Asset aus einem `:core`-Test (wie `rider.glb`). Im selben Modul kann der
Prototyp `MeshBuilder`, `Vec3`/`Quat`, `GlbScene` und die Bild-Encoder
benutzen, **ohne** etwas in `world/` zu ändern; im Werkzeug müsste er sie
nachbauen. Das Paket hängt nur von diesen stabilen Bausteinen ab;
`TreeCompare.kt` (nur Werkzeug) liest zusätzlich `TreeModels`, `TreesLayer`,
`FoliageAtlas` und `TileOutput` für die „alt“-Bilder — dort kann ein Umbau der
Sitzung „Streckenqualität“ eine Anpassung nötig machen (rein Werkzeug).

### Materialien (unverändert verwendet)

- Rinde `mat_tree_trunk`: Kernmaterial, Rindenatlas (JPEG, kachelnd), Rauheit
  0,95, `noSpecular` wie heute (gleiche Erweiterung `KHR_materials_specular`,
  die `GlbPatch` in der App entschärft; Test prüft: keine andere Erweiterung).
- Laub `x_mat_tree_cards`: Laubatlas (PNG), `MASK` 0,5, einseitig, beide
  Wicklungen im Netz — die Darstellung tauscht es wie heute gegen
  `foliage.mat` (Wind, Atlas-Modus 1). In filarender bestätigt
  (`foliageMi: 1`).

## Dreiecksbudget (fest im Code, im Test geprüft)

Gezählt inkl. beider Wicklungen der Laubkarten.

| Art | nah | mittel | fern |
|---|---|---|---|
| Buche, Eiche, Fichte | 6 000 | 1 500 | heutige Fernstufe (`TreeModels`, 134–150) |
| Birke, Pappel, Kiefer, Aleppo-Kiefer | 4 500 | 1 100 | heutige (96–108) |
| Obstbaum | 3 000 | 800 | heutige (102) |
| Strauch | 1 800 | 500 | heutige (48) |

Herleitung: höchstens ~24 Nahbäume gleichzeitig im Bild × 6 000 ≈ 150 000
Dreiecke — knapp ein Drittel dessen, was eine Welt heute gleichzeitig zeigt
(460 000–530 000, `world.md`). Rinde höchstens 30 % des Budgets (sonst
entfallen die dünnsten Seitenäste), ein Zehntel für Kernkarten, der Rest Laub.

## Messung (`metrics.txt`, Mittel über die 4 Varianten)

| Art | nah: Dreiecke | nah: Netz in der GLB | mittel: Dreiecke | mittel: Netz | heute nah/mittel/fern |
|---|---|---|---|---|---|
| Buche | 5 541 | 201 kB | 998 | 38 kB | 524 / 294 / 134 |
| Eiche | 5 656 | 205 kB | 1 163 | 44 kB | 524 / 294 / 134 |
| Birke | 3 719 | 137 kB | 630 | 25 kB | 394 / 232 / 108 |
| Pappel | 4 255 | 154 kB | 855 | 33 kB | 396 / 202 / 102 |
| Fichte | 5 054 | 185 kB | 851 | 33 kB | 600 / 324 / 150 |
| Kiefer | 3 947 | 144 kB | 665 | 26 kB | 196 / 116 / 96 |
| Aleppo-Kiefer | 3 583 | 130 kB | 710 | 28 kB | (heute Kiefer) |
| Obstbaum | 2 625 | 97 kB | 698 | 28 kB | 376 / 214 / 102 |
| Strauch | 1 172 | 44 kB | 298 | 13 kB | 106 / 60 / 48 |

- **Zeichenaufrufe je Baum:** 2 (Rinde, Laub), in jeder Stufe; mit Schatten
  (Bäume werfen Schatten) 4.
- **Bytes je Dreieck:** ~36 B nah (Karten: 4 Eckpunkte × 32 B für 4
  Dreiecke + Indizes), ~38 B mittel.
- **Bibliothek** (alle 9 Arten × 4 Varianten × nah/mittel = 72 Vorlagen,
  eine GLB): **6,47 MB**, gzip −9 **4,38 MB** (≈ APK-Anteil); davon Atlanten
  0,25 MB in der Datei, 2,8 MB Grafikspeicher mit Mip-Stufen. Mit 3 statt 4
  Varianten ~4,9 MB.
- **Vergleichsszene** (eine Kachel, Dreiecke der Bäume alt → neu; neu: nah
  ≤ 20 m, mittel ≤ 45 m neben der Strecke, dahinter die heutige Fernstufe):
  Einzelbaum 524 → 5 656; Allee (16 Buchen) 8 384 → 88 664; Waldrand
  (123 Bäume, davon 39 nah, 41 mittel) 29 920 → 167 646; mediterran (31)
  3 840 → 45 080. Kachel-GLB 2,6 MB → 13,9 MB (gebacken).

### Hochrechnung: typische Kachel (256 m) und Bildrate

Heute stehen Einzelbäume bis 70 m neben der Strecke, im Wald alle ~6,5 m
(Kandidat je 42 m², Wahrscheinlichkeit ~0,7). Im Band ≤ 20 m neben der Strecke
(abzüglich Fahrbahn und Abstand ~15 m je Seite) sind das je Kachel:

| Lage | Bäume ≤ 20 m je Kachel | gebacken nah (heute: alles in die Kachel) | Platte gebacken |
|---|---|---|---|
| Wald | ~120 | ~600 000 Dreiecke | ~22 MB |
| Allee im Offenland | ~54 | ~300 000 | ~11 MB |
| gemischt | ~60 | ~300 000 | ~11 MB |

**Gebacken wie heute geht also nicht** (Kachelbudget heute ~47 000 Dreiecke,
Platte ~13 MB/km). Die Nahstufe braucht **Instanzen** aus einer Bibliothek und
eine Stufenwahl **nach Abstand zur Kamera**, nicht zur Strecke:

| Annahme (instanziert, Wahl je Bild) | Wald | Allee |
|---|---|---|
| nah (Kamera ≤ 25 m, nur Bäume im Band ≤ 20 m neben der Strecke), im Sichtkegel | ~8 Bäume ≈ 40 000 Dreiecke | ~6 ≈ 33 000 |
| mittel (25–60 m) im Band | ~17 ≈ 15 000 | ~8 ≈ 8 000 |
| Zeichenaufrufe Bäume (mit Schatten) | ~100 | ~56 |
| harte Obergrenze (16 nah + 40 mittel) | 156 000 Dreiecke, 112 Aufrufe (+112 Schatten) | |

Das sind +10–30 % Dreiecke gegenüber heute. Was das auf dem S25 kostet, ist
**nicht gemessen**. Erwartung: Dreiecke unkritisch (Adreno 830), kritischer
sind (a) die Alpha-Maske (Laub mit `discard` schaltet die frühe
Tiefenverwerfung ab; nahe Kronen füllen große Bildteile mit 3–6 Lagen) und (b)
die zusätzlichen Zeichenaufrufe im Schattendurchgang. Stellschrauben: Radius
nah 25 → 18 m, Obergrenze 16 → 10, Laubkarten der Nahstufe größer/weniger.
Platte: Instanzliste ~12 B je Baum (Lage, Drehung, Maßstab, Vorlage, Ton) →
~1–2 kB je Kachel; die Bibliothek liegt einmal in der App (+4,4 MB APK).

## Bilder

`docs/indoor/bilder/baeume/`, je Bild links **alt** (heute), rechts **neu**
(Prototyp), gleiche Kamera, gleiche Sonne; gerendert mit 960 × 600 je Hälfte,
als JPEG auf 1280 px Breite verkleinert:

| Datei | Szene | Renderer |
|---|---|---|
| `einzel-05m-gegenlicht-filament.jpg` | Einzelbaum (Eiche), 5 m, Gegenlicht | filarender |
| `einzel-05m-auflicht-filament.jpg` | Einzelbaum, 5 m, Auflicht | filarender |
| `einzel-05m-auflicht-three.jpg` | Einzelbaum, 5 m, Auflicht | three.js |
| `einzel-15m-gegenlicht-filament.jpg` | Einzelbaum, 15 m, Gegenlicht | filarender |
| `einzel-15m-auflicht-filament.jpg` | Einzelbaum, 15 m, Auflicht | filarender |
| `einzel-40m-gegenlicht-filament.jpg` | Einzelbaum, 40 m (neu: mittlere Stufe), Gegenlicht | filarender |
| `einzel-40m-auflicht-filament.jpg` | Einzelbaum, 40 m, Auflicht | filarender |
| `allee-gegenlicht-filament.jpg` | Allee (Buchen alle 9,5 m), Gegenlicht | filarender |
| `allee-auflicht-filament.jpg` | Allee, Auflicht | filarender |
| `allee-auflicht-three.jpg` | Allee, Auflicht | three.js |
| `waldrand-auflicht-filament.jpg` | Waldrand (Buche/Fichte/Birke/Eiche, Strauchmantel), Auflicht | filarender |
| `waldrand-gegenlicht-three.jpg` | Waldrand, Gegenlicht | three.js |
| `mediterran-gegenlicht-filament.jpg` | Aleppo-Kiefern und Sträucher (Sa Calobra), Gegenlicht | filarender |
| `typenschau-filament-neu.jpg` | nur neu: alle Arten, Varianten 0–3 (Buche/Eiche · Birke/Pappel/Fichte · Kiefer/Aleppo) | filarender |
| `atlanten.jpg` | Laubatlas (auf Blau) und Rindenatlas | — |

Sonne: Gegenlicht = Sonne in Blickrichtung 22–25° hoch; Auflicht = Sonne im
Rücken 40–50° hoch. Himmel/Licht aus `indoor-dev sky --time NOON` (für
filarender mit verschobener Sonnenrichtung, Himmelsbild unverändert).

### Was die Bilder zeigen

- **Nah (5 m):** heute ein glattes Rohr unter einer Laubhaube aus großen
  Karten; neu Stamm mit Wurzelanlauf und Borke, Hauptäste, Seitenäste, Blätter
  von 5–9 cm. Im Gegenlicht leuchten die Ränder, durch Lücken sieht man Äste
  und dunkle Kernkarten statt Himmel.
- **15 m:** echte Kronenstruktur, Äste zwischen den Laubbüscheln.
- **40 m:** neu (mittlere Stufe) etwas lichter als heute; die Silhouette hält
  (getestet), die Krone wirkt bei 40 m weniger „voll“.
- **Allee:** heute helle, gefleckte „Masten“ unter einem Dach aus großen
  Karten; neu Buchenstämme mit Wurzelanlauf und Astansätzen, das Kronendach
  über der Straße schließt sich, mit Licht zwischen den Büscheln. Buchenrinde
  bleibt (wie in der Natur) glatt-grau.
- **Waldrand:** dichter Strauchmantel, Fichten mit Quirlen, helle Birken.
- **Mediterran:** Aleppo-Kiefern mit schiefem Stamm und offener Krone.
- **Typenschau:** alle Arten in Variante 0–3.

### Bekannte Schwächen (ehrlich)

- Laubkarten bleiben Karten: von schräg unten sieht man einzelne Kanten,
  Kiefernbürsten wirken aus der Nähe etwas „gezeichnet“.
- Die mittlere Stufe ist bei 30–45 m lichter als die heutige Krone; die
  Fernstufe ist das heutige Modell (anderer Umriss) → beim Wechsel bei 60 m ist
  ein Sprung zu erwarten. Ohne Überblenden in `foliage.mat` (Dither-Fade,
  Sache der Sitzung Renderqualität) springt jeder Stufenwechsel.
- Kernkarten liegen im Atlas im Quadranten unten rechts; `windWeight` gibt
  dort teils das Gras-Gewicht → sie wiegen stärker als das übrige Laub.
- Rindenspalten sind 128 px breit: dicke Stämme aus 2–5 m sind unscharf.
- Kein Schattenwurf **in** die Krone (Bäume empfangen keine Schatten, wie
  heute) — die Verschattung steckt nur in den Vertexfarben.
- Betrachter (three.js, eigenes Material) und filarender (Filament mit dem
  Laubmaterial der App) unterscheiden sich in Helligkeit und Kontrast des
  Laubs, besonders im Gegenlicht; maßgeblich ist filarender.

## Einbauplan (für die Orchestrierung, nach Abschluss „Streckenqualität“)

Ziel: Bäume im Band **≤ 20 m neben der Strecke** werden zur Laufzeit als
Instanzen der Bibliothek gezeigt (nah ≤ 25 m, mittel ≤ 60 m Abstand zur
Kamera); weiter weg bleiben sie gebacken (heutige Fernstufe). Alles dahinter
(Einzelbäume 20–70 m, Waldkörper, Stufe 1/2) bleibt, wie es ist.

### 1. Bibliothek als App-Asset

- Ein `:core`-Test erzeugt `app/src/main/assets/indoor/models/trees/<art>_<v>_<stufe>.glb`
  (72 Dateien, oder eine Datei je Art) aus `TreeLibrary` — wie `rider.glb`
  heute (Datei eingecheckt, Test prüft Gleichheit). Vorlagen-Same fest
  (`TEMPLATE_SEED`), damit Welt und App dieselben Bäume meinen.
- Kennung der Bibliothek (`TREE_LIBRARY_ID = "trees/1"`) im Manifest jeder
  Welt; passt sie nicht, zeigt die App die gebackene Fernstufe (siehe 3).

### 2. Weltbau (`core/**/indoor/world/`)

- **`TreesLayer.singleTrees`**: Kandidaten wie heute. Für Kandidaten mit
  `dist ≤ NEAR_BAND_M` (20 m): (a) **Instanz** schreiben (Vorlage aus Art →
  `TreeKind`, Variante aus dem Samen, Drehung, Maßstab = Höhe / Vorlagenhöhe,
  Ton), (b) ihre **Fernstufe** (`TreeModels.Lod.FAR`) in einen Knoten je
  **Abschnitt** backen: `x_trees_bin_<k>` unter `trees`, `k` = Laufmeter der
  nächsten Streckenstelle / 32 m. Kandidaten > 20 m wie heute (NEAR/MID/FAR
  nach Budget, `DETAIL_M`/`MID_M` dann nur noch für 20–40 m).
  Abbildung Art: `BROAD`→Buche/Eiche (`Form.OAK`→Eiche), `TALL`→Birke (`pale`)
  bzw. Pappel (`POPLAR`) bzw. Buche (Esche/Ahorn ≈ schlanke Buche, Variante 1),
  `CONIFER`→Fichte, `PINE`→Kiefer (in Mallorca-Welten Aleppo-Kiefer, Region
  aus dem Ursprung), `SMALL`→Obstbaum, `BUSH`→Strauch. Laserbäume: Höhe
  gemessen → Maßstab; Kronenbreite → Variante mit passendem Verhältnis.
- **Instanzen in der Kachel-GLB** als leere Knoten (ohne Netz)
  `x_treei_<art>_<v>_<k>` unter `trees` mit Verschiebung/Drehung/Maßstab —
  glTF-eigen, keine Erweiterung. Dafür in `GlbCheck.checkNames` **eine**
  Ausnahme: Knoten mit Präfix `x_treei_` ohne Netz dürfen eine Transformation
  haben. Vertrag `docs/indoor.md` 7.3 entsprechend ergänzen.
- **Budget:** Die Fernstufe der Bandbäume (134–150 statt heute 524 nah) ist
  billiger als heute → das Kachelbudget sinkt sogar; Platte je Kachel
  +~2 kB (Instanzknoten), −(nah→fern gebacken).
- **`WORLD_BUILDER_ID`** `indoor-world/11` → `/12` (Kacheln ändern sich in
  jeder Welt). Katalogwelten neu bauen.

### 3. App (`app/**/indoor/render/`)

- **`TreeInstancer`** (neu): lädt die Bibliothek einmal
  (`AssetLoader.createInstancedAsset` je Vorlage mit festem Vorrat, z. B. 6
  nah / 12 mittel je Vorlage; vor dem Einbau prüfen, ob gltfio 1.75
  `createInstance` zum Nachlegen anbietet). Liest beim Laden einer Kachel die
  `x_treei_*`-Knoten (Name → Vorlage, `TransformManager` → Matrix).
- **Je Bild** (Hauptthread, wie `TilePlanner`): Bandbäume nach Abstand zur
  Kamera: ≤ 25 m nah, ≤ 60 m mittel, sonst keine Instanz. Ein Abschnitt
  `x_trees_bin_<k>` wird **ausgeblendet**, solange einer seiner Bäume als
  Instanz steht (dieselbe Mechanik wie die Teilflächen-Regel `terrain_sub_*`),
  sonst gezeigt. Hysterese 3 m gegen Flackern.
- **Schatten:** Instanzen ausdrücklich `castShadows(true)`, `receive false`
  (die Bibliotheksknoten heißen `x_tree_*`, `nodeCategory` fände dort keine
  Kategorie). Schalter `RenderSettings.trees` gilt auch für Instanzen.
- **Materialien:** `WorldMaterialProvider` greift für die Bibliothek genauso
  (`x_mat_tree_cards` → Laub mit Wind, Atlas-Modus 1); `GlbPatch` vor dem Laden
  anwenden. Wind bewegt jede Instanz mit ihrer Weltlage — keine Änderung.
- **Diagnose:** Zahl der Instanzen nah/mittel und Zeichenaufrufe im
  Diagnosebildschirm.
- Werkzeuge: Betrachter und filarender müssen die Instanzen zeigen
  (Betrachter: Bibliothek laden und `x_treei_*` ersetzen; filarender kann
  Modelle mit Matrix schon über `models` in der Szene).

### 4. Nähte

- **nah ↔ mittel (25 m):** gleiche Silhouette (Test: Höhe ±15 %, Breite
  ±30 %), gleiche Atlanten und Farben → Wechsel kaum sichtbar.
- **mittel ↔ fern (60 m):** Modellwechsel zum heutigen Kronenlappenbaum.
  Kurzfristig: Farbe/Höhe angleichen (Vertexfarben der Fernstufe an
  `SpeciesParams.leafSrgb`). Mittelfristig: Fernstufe aus derselben Vorlage
  (Kreuzbild/Impostor aus einer offline gerenderten Ansicht der Vorlage) und
  ein Dither-Übergang in `foliage.mat` (Sitzung Renderqualität).
- **Band ↔ dahinter (20 m):** Bäume > 20 m bleiben heutige Modelle; der
  Unterschied ist ab 25–30 m Kameraabstand gering.

### 5. Reihenfolge und Prüfung

1. Bibliothek-Asset + Test (nur `:core`), 2. Instanzknoten + Abschnitte im
Weltbau + `GlbCheck`-Ausnahme + Vertrag + Builder-ID, 3. `TreeInstancer` in
der App, 4. Betrachter/filarender, 5. **Gerätetest S25**: Bildrate und
Wärme auf einer Wald- und einer Alleestrecke mit/ohne Instanzen, Obergrenzen
danach festlegen.

## Bauen und Prüfen

```
./gradlew --no-daemon :core:test --tests 'de.trailscape.core.indoor.tree.*'
./gradlew --no-daemon :indoor-dev:run --args="tree-proto --out tool/indoor-dev/out/baeume"
./gradlew --no-daemon :indoor-dev:run --args="sky --time NOON --out tool/indoor-dev/out/sky"
python3 tool/indoor-dev/baeume/render.py tool/indoor-dev/out/baeume --sky tool/indoor-dev/out/sky \
        --renderer both --jpeg-out docs/indoor/bilder/baeume
```

**In dieser Sitzung über Gradle UNGEBAUT:** Maven Central lieferte HTTP 429
(dreimal versucht). Übersetzt und getestet wurde mit eigenständigem
`kotlinc` 2.3.20 (ganzes `:core`-Hauptverzeichnis + `:indoor-dev`, Plugin
kotlinx-serialization, BRouter-Quellen mit `javac`) und der
JUnit-Plattform-Konsole 1.11.4: alle 9 Tests des Pakets grün. Die übrigen
Tests von `:core` und `:indoor-dev` sind hier **nicht** gelaufen.
