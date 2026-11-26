# Indoor — Bäume aus der Nähe

Stand: 25.11.2026, Branch `claude/grafik-baumwechsel` (Wechselring; der Einbau
selbst: `claude/grafik-baeume-einbau`). Teil des Grafik-Schwarms
(Auftrag: „deutlicher Schritt nach vorn, quasi fotorealistisch“); dieser Teil
behandelt **nur Bäume in der Nahzone**.

Das Dokument hat drei Teile: den **Einbau** (was jetzt in Weltbau, App und
Werkzeug steckt — der folgende Abschnitt), darin den **Wechselring** (wie
gebackene Bäume und Instanzen ohne Sprung ineinander übergehen) und darunter unverändert den
**Prototyp** (Generator, Budget, Messung, Bilder alt/neu, Einbauplan), auf dem
der Einbau beruht.

**Ehrlich vorweg:** Fotorealismus ist das nicht. Es ist der Schritt von
„Kronenlappen aus großen Laubkarten an einem Rohr“ zu „Baum mit echtem Stamm,
Ästen, Zweigen und kleinen Blättern“. Alles ist nur im Filament-Referenzrenderer
(Filament 1.75.1 auf Mesa llvmpipe) geprüft, **nichts auf einem Gerät**
(Galaxy S25), keine Bildrate gemessen. Der App-Teil ist in dieser Sitzung
**nicht über Gradle gebaut** (siehe „Bauen und Prüfen“).

## Einbau

### Kurzfassung

- **Bibliothek als App-Asset:** `app/src/main/assets/indoor/models/trees.glb`
  (72 Vorlagen: 9 Arten × 4 Varianten × nah/mittel, beide Atlanten), 6,47 MB,
  gzip −9 4,38 MB (≈ Anteil am APK). Sie wird **nicht auf dem Gerät erzeugt**
  und liegt in **keinem Streckenpaket**.
- **Weltbau** (`WORLD_BUILDER_ID` **`indoor-world/15`**, vorher `/14`): Bäume
  bis 20 m neben der Streckenmitte stehen wie bisher gebacken in der Kachel,
  aber je 32 m Strecke in einem eigenen Knoten `x_trees_bin_<k>` unter `trees`;
  dazu je Baum ein leerer Knoten `x_treei_<art>_<variante>_<k>` mit Lage,
  Drehung und Maßstab. **Server und Katalog müssen neu gebaut werden.**
- **App:** `TreeInstancer` zeigt auf den Grafikstufen **Hoch** und **Ultra** nah
  an der Kamera Bäume der Bibliothek an diesen Stellen und blendet dafür den
  gebackenen Abschnitt aus. Jede Vorlage liegt einmal im Grafikspeicher; alle
  Bäume derselben Vorlage teilen Vertexpuffer, Indexpuffer und Materialinstanzen
  und werden über Filaments automatische Instanzierung gezeichnet.
- **Wechsel ohne Sprung:** Instanzen und gebackene Bäume wechseln in einem
  **Ring um die Kamera** (10 m breit, außen bei 45/60 m oder näher, wenn die
  Obergrenze greift): das Laubmaterial rastert dort beide ineinander und tönt
  die gebackenen Bäume bis zum Ring auf den Ton der Bibliothek. Siehe
  „Wechselring“. Der Weltbau ist dafür **nicht** geändert (`indoor-world/15`).
- **Standard** (Vorgabe) und der sichere Modus zeigen dieselben gebackenen
  Bäume, dieselben Dreiecke. Zwei Dinge ändern sich auch dort, siehe
  „Wechselring → Nebenwirkungen“: Rinde läuft über das Laubmaterial (im Bild
  gleich), und Laubkarten werfen jetzt bestimmt Schatten.
- **Wind und Schatten** wie bei den gebackenen Bäumen: Laub läuft über
  dasselbe `foliage.mat` (Wind, Atlas-Modus 1), Bäume werfen Schatten und
  empfangen keine.

### Entscheidungen (und Abweichungen vom Einbauplan unten)

| Punkt | Einbauplan | Umgesetzt | Warum |
|---|---|---|---|
| Asset | 72 Dateien oder eine je Art, Test prüft Gleichheit | **eine** GLB, eingecheckt; `TreeLibraryAssetTest` prüft sie gegen den Generator (Vorlagen, Eckpunkte, Indizes, Lagen auf 1 mm) | Eine Datei = Atlanten nur einmal; inhaltlicher Vergleich statt Byte für Byte, weil PNG-Kompression und letzte Stellen der Winkelfunktionen je nach JVM abweichen dürfen |
| eingecheckt oder im Build erzeugt | „wie `rider.glb`“ | **eingecheckt** (`indoor-dev tree-library --out …`) | Der App-Build bleibt ohne `:indoor-dev`-Lauf; das Asset ist deterministisch und ändert sich nur mit dem Generator; der Test schlägt an, wenn Generator und Datei auseinanderlaufen |
| gebackene Bandbäume | nur die Fernstufe (134–150 Dreiecke) | **unverändert** die bisherigen Stufen (nah/mittel/fern nach Budget), nur nach Abschnitten gruppiert | Die niedrigste Grafikstufe und ältere App-Versionen sehen exakt das alte Bild; das Kachelbudget ändert sich nicht. Preis: keine Ersparnis auf der Platte |
| Kennung der Bibliothek | Feld im Manifest | steckt im **Präfix** `x_treei_` (Bibliothek `trees/1`) | Kein neues Manifestfeld, das Manifestformat bleibt. Eine unverträgliche Bibliothek bekäme ein neues Präfix — ältere Apps zeigen dann die gebackenen Bäume |
| Instanzierung | `AssetLoader.createInstancedAsset` mit festem Vorrat | **eigene Vertex-/Indexpuffer je Vorlage**, ein Renderable je Baum, `Engine.setAutomaticInstancingEnabled(true)` | gltfio instanziert nur ganze Assets (alle 72 Vorlagen auf einmal, Materialinstanzen je Instanz — keine gemeinsamen Zeichenaufrufe). Die Java-API 1.75.1 hat keinen `InstanceBuffer`; die automatische Instanzierung fasst Renderables mit gleicher Geometrie und gleicher Materialinstanz zusammen und lässt jedem Baum seinen eigenen Umriss (Sichtkegel) |
| Abschnitt ausblenden | „solange einer seiner Bäume als Instanz steht“ | **Wechselring** (siehe unten): das Material blendet nach Abstand zur Kamera; ein Abschnitt wird nur noch abgeschaltet, wenn er ganz innerhalb des Rings liegt. Rückfall ohne blendfähiges Material: Abschnitt **als Ganzes** | Der Sprung je Abschnitt war sichtbar; so fehlt weiterhin nie ein Baum |
| Ton je Baum | im Instanzknoten | **entfällt** | Alle Instanzen teilen eine Materialinstanz (Voraussetzung der Instanzierung); Vielfalt kommt aus 4 Varianten, Drehung und Maßstab |
| Betrachter (three.js) | zeigt Instanzen | zeigt weiter die **gebackenen** Bäume | Die Abschnitte liegen unter `trees`; die Instanzknoten sind leer und stören nicht. Maßgeblich für die Instanzen ist filarender |

### Vertrag Weltbau ↔ Darstellung (`TreeInstanceNames`, `docs/indoor.md` 7.3)

- `x_trees_bin_<k>`: Kind von `trees`, mit Netz, ohne Transformation.
  `k` = Laufmeter der nächsten Streckenstelle / 32 m, abgerundet. Enthält die
  gebackenen Bäume (Stamm, Krone) des Abschnitts dieser Kachel.
- `x_treei_<art>_<variante>_<k>`: Kind von `trees`, **ohne Netz**, mit
  `translation` (Fußpunkt in Kachelkoordinaten), `rotation` (um die Hochachse)
  und gleichmäßigem `scale` = Baumhöhe / Höhe der Vorlage. `art` ist der
  Name aus `TreeKind` in Kleinbuchstaben (`beech`, `oak`, `birch`, `poplar`,
  `spruce`, `pine`, `aleppo_pine`, `fruit`, `shrub`), `variante` 0–3.
  Reines glTF, **keine Erweiterung**. `checkTileGlb` lässt eine Transformation
  nur an diesen leeren Knoten zu.
- Zuordnung (`TreeInstanceMapping`, getestet): Wuchsform des gebackenen Baums →
  Art (Esche ≈ Buche, Feldbaum ≈ Obstbaum; südlich von 42° Breite Aleppo- statt
  Waldkiefer). Variante aus dem Samen unter denen, deren Höhe höchstens um den
  Faktor 1,3 abweicht, sonst die nächstpassende.
- Eine App, die beides nicht kennt, zeigt die gebackenen Bäume (Abschnitte
  zählen zur Knotenart `trees`: Schalter, Schatten). Kennt die App eine Art
  oder Variante eines Abschnitts nicht, bleibt dieser Abschnitt gebacken.

### Auswahl je Bild (`TreeInstancePlanner` in `:core`, getestet)

| `RenderSettings.treeDetail` | Grafikstufe | nah bis | Abschnitt instanziert bis | höchstens nah | höchstens insgesamt |
|---|---|---|---|---|---|
| 0 | Standard, sicherer Modus | — | — | — | — |
| 1 | Hoch | 18 m | 45 m | 10 | 40 |
| 2 | Ultra | 25 m | 60 m | 16 | 56 |

Abstand zur **Kamera** (waagrecht). „Instanziert bis“ ist der äußere Rand des
Wechselrings (`midM`); eine Instanz bekommt jeder Baum, dessen Krone in diesen
Abstand ragt, die nächsten zuerst. Reicht „höchstens insgesamt“ nicht, rückt
der Ring näher (siehe „Wechselring“). Die nächsten Bäume bis zur Nahgrenze
bekommen die Nahstufe, alle anderen die mittlere. Hysterese 3 m; neu gewählt
wird nach 0,5 m Kamerabewegung. Der Schalter `RenderSettings.trees` schaltet
auch die Instanzen ab.

### Wechselring: gebackener Baum ↔ Instanz ohne Sprung

**Vorher:** Ein Abschnitt (32 m Strecke, bis 26 Bäume) schaltete als Ganzes
zwischen gebackenen Bäumen und Instanzen um — in einem Bild, mit anderem
Umriss, hellerem Laub und dunklerer Rinde.

**Jetzt** drei Mittel, alle in Material und Darstellung, **nichts im Weltbau**:

1. **Ring statt Abschnitt.** `foliage.mat` kennt `fadeMode` und `fadeRange`:
   bis `fadeRange.x` zeichnet nur die nahe Darstellung (Bibliothek,
   `fadeMode` +1), ab `fadeRange.y` nur die ferne (gebacken, −1); dazwischen
   entscheidet ein festes Bildschirmraster (interleaved gradient noise) je
   Bildpunkt für genau eine von beiden. Maßgeblich ist der waagrechte Abstand
   des Bildpunkts zur Kamera — ein Baum löst sich beim Heranfahren über 10 m
   Weg von vorn nach hinten auf, während der andere erscheint. Im
   Schattendurchgang gilt derselbe Abstand (zur Kamera des Bilds, nicht des
   Lichts), also wechseln auch die Schatten mit.
2. **Rinde blendet mit.** `mat_tree_trunk` läuft über dasselbe Material
   (`atlasMode` 2: kein Wind, Rückseiten weg) statt über den Ubershader — nur
   so können Stämme überblenden.
3. **Ton angleichen, die fernen an die nahen.** Die gebackenen Abschnitte
   nehmen bis zum Ring den Ton der Bibliothek an und hinter dem Ring über 30 m
   wieder ihren eigenen (`fadeTintDark/Light/Luma`, Werte in `TreeFadeLook`).
   Ein Abschnitt hat für alle Arten **ein** Material, deshalb je zwei
   Faktoren, gewählt nach der Helligkeit der Vertexfarbe (Fichtenkegel gegen
   Laubkrone, gewöhnlicher Stamm gegen Birke) und nach dem Feld im Laubatlas
   (Nadelzweige). Die Bibliothek selbst ist **unverändert** (Datei
   byteweise gleich) — aus der Nähe sieht kein Baum anders aus als vorher.

**Wie es zusammenhängt** (`TreeInstancePlanner.selectFading`, `:core`, getestet;
`TreeInstancer` in der App; `filarender.cpp` spiegelt beides):

- Die gebackenen Abschnitte einer Kachel bekommen **Kopien** ihrer zwei
  Materialinstanzen (`MaterialInstance.duplicate`), solange der Ring an ist.
  Die übrigen Bäume und das Gras der Kachel teilen die Originale und blenden
  nicht. Auf Standard tragen die Abschnitte ihre Originale.
- **Obergrenze:** Reicht „höchstens insgesamt“ nicht für alle Kronen bis
  `midM`, liegt der äußere Rand dort, wo die Krone des ersten Baums **ohne**
  Instanz beginnt (Abstand minus Ausladung der Vorlage × Maßstab,
  `TreeLibraryFile.reachM`). Der Rand hängt damit stetig an der Kamera — er
  springt nicht, wenn ein Abschnitt in Reichweite kommt (Test: je 0,5 m Weg
  höchstens 0,5 m). In der Testwelt: Ortsdurchfahrt 60 m, Fichtenwald 52 m,
  Mischwald 41 m, dichter Laubwald 38 m (Ultra); Fichtenwald auf Hoch 31 m.
- **Nie ein Loch:** Jeder Baum, dessen Krone in den Ring ragt, hat eine
  Instanz (Test). Ein gebackener Abschnitt wird nur abgeschaltet, wenn er mit
  3 m Zuschlag ganz innerhalb des Rings liegt — dort zeichnete sein Material
  ohnehin nichts.
- **Laden und Stufenwechsel:** Der Ring wächst mit 20 m/s auf, statt
  aufzuspringen; kleiner wird er sofort (sonst fehlten Instanzen).
- **Rückfall:** Kennt das Laubmaterial `fadeMode` nicht (älteres Paket,
  Ubershader nach einem Ladefehler), gilt der alte Wechsel je Abschnitt
  (`TreeInstancePlanner.select`, unverändert und getestet).

**Tonwerte** (`TreeFadeLook`), gemessen an der Testwelt:

| | gebacken → Bibliothek (linear, R G B) | gewählt für |
|---|---|---|
| Laub, Laubbäume und Sträucher | × 1,9 / 1,35 / 2,0 | Vertexfarbe heller als 0,045 |
| Laub, Nadelbäume | × 0,7 / 0,96 / 0,8 | dunkler als 0,022, Nadelfelder im Atlas |
| Rinde, gewöhnlich | × 0,44 / 0,47 / 0,56 | dunkler als 0,45 |
| Rinde, Birke | × 1,67 / 1,69 / 2,0 | heller als 0,60 |

Kontrolle im Bild (Kronenpixel, Schatten aus, Verhältnis Bibliothek zu
gebacken, linear): Ortsdurchfahrt R 2,41 / G 1,31 ohne Tönung → **1,03 / 1,01**
mit; Laubwald 2,03 / 1,26 → **0,95 / 0,99**. Im Mischwald (1,50 / 1,48 →
0,66 / 1,14) passt ein einziger Faktor je Kachel schlechter: dort stehen
Kiefern, deren gebackene Krone teils Laubkarten trägt.

**Nebenwirkungen, auch auf Standard:**

- **Rinde über `foliage.mat`** statt Ubershader, auf allen Stufen (die
  Entscheidung fällt beim Laden der Kachel). Gleiche Rechnung (Grundfarbe ×
  Textur × Vertexfarbe, Rauheit 0,95, kein Spiegelanteil). Im Referenzrenderer
  (Bild auf 1/8 verkleinert, Unterschied 0–255): Wald 0,26 im Mittel gegen
  0,24 zwischen zwei gleichen Läufen; Ortsdurchfahrt 0,09 gegen 0,05, einzelne
  Felder an Stammkanten bis 14 (gegen 8) — Kanten glättet „masked“ anders als
  deckend.
- **Laubkarten werfen jetzt bestimmt Schatten.** Das Material las im
  Schatten-/Tiefendurchgang `getWorldPosition()`; dort ist der Wert
  **unbestimmt** (Filament ruft `material()` ohne die Schattierungsgrößen
  auf). Die Stelle kam mit dem Ausblenden der Karten auf Kante („feineres
  Laub“); seither warfen Laubkarten im Referenzrenderer (llvmpipe) gar keinen
  Schatten — alle bisherigen Bilder dieses Dokuments zeigen nur Stamm- und
  Astschatten —, und was ein Gerät daraus machte, hing am Treiber. Jede
  Änderung am Shader würfelte das Ergebnis neu. Jetzt kommt die Weltlage als
  eigene Größe aus dem Vertex-Shader: Karten werfen Schatten, außer sie stehen
  auf Kante zur Sonne. Das ist die ältere, beabsichtigte Wirkung („Waldweg
  unter dem Kronendach“, `render.md`), macht Waldwege im Referenzrenderer aber
  **deutlich dunkler** als in den Bildern davor. Ob das Gerät bisher schon so
  aussah, ist offen (siehe „Offen“). Ein Test hält fest, dass der
  Fragment-Shader `getWorldPosition()` nicht mehr benutzt.

### App (`app/…/indoor/render/TreeInstancer.kt`)

- **Laden:** erst, wenn eine Stufe mit `treeDetail` > 0 läuft und eine Kachel
  Instanzknoten hat. Datei lesen und zerlegen auf dem E/A-Thread
  (`TreeLibraryFile` in `:core`); auf dem Hauptthread geht eine winzige GLB mit
  den zwei Materialien und Atlanten (`TreeLibraryFile.materialsGlb`, reines
  Umpacken) durch gltfio — also durch `GlbPatch` und `WorldMaterialProvider`
  wie jede Kachel. Die Netze einer Vorlage entstehen bei ihrem ersten Baum
  (Lage, Tangentenrahmen aus der Normale — `TangentFrames` in `:core`, UV,
  Farbe).
- **Sichtbarkeit:** Baumabschnitte sind eine eigene Knotenart
  (`nodeCategory`), Grundart `trees`. `TileStreamer` blendet die Abschnitte aus,
  die `TreeInstancer.hiddenBins` nennt — mit Wechselring die, die ganz
  innerhalb des Rings liegen.
- **Wechselring:** `TreeInstancer` setzt je Wahl (0,5 m Weg, oder jedes Bild,
  solange der Ring wächst) `fadeRange` auf den zwei Materialien der Bibliothek
  und den Kopien je Kachel; `WorldMaterialProvider.copyOf/dropCopy` legt die
  Kopien an und gibt sie frei (sie folgen dem Windschalter wie die Originale).
  Vor dem Entladen einer Kachel hängen die Originale wieder an ihren
  Abschnitten.
- **Rückfall:** Fehlt die Bibliothek, ist sie unvollständig oder lässt sich ein
  Puffer nicht anlegen, bleibt es für die Sitzung bei den gebackenen Bäumen;
  kein Fehler nach außen.
- **Diagnose:** Zeile „Bibliotheksbäume (nah / mittel / Dreiecke / Vorlagen)“
  auf der Diagnoseseite; `–` aus, `…` lädt, `×` nicht verfügbar.

### Kosten

Gemessen im Referenzrenderer an der Testwelt (`indoor-dev testworld --quality
HIGH`, 3 km, 568 Instanzknoten in 46 Abschnitten, im Mittel 12, höchstens 26
Bäume je Abschnitt), Kamera auf der Strecke in Fahrtrichtung. In Klammern der
Stand vor dem Wechselring (Abschnitt schaltet als Ganzes):

| Stelle | Stufe | Instanzen nah / mittel | Dreiecke der Instanzen | Vorlagen im Speicher | Ring (m) | Abschnitte ganz aus |
|---|---|---|---|---|---|---|
| Ortsdurchfahrt (780 m) | Ultra | 2 / 3 (2 / 3) | 8 456 (8 456) | 5 (5) | 50–60 | 1 (3) |
| Mischwald, Schotterweg (1740 m) | Ultra | 16 / 40 (16 / 31) | 75 750 (67 172) | 27 (25) | 31–41 | 1 (5) |
| Wald (2080 m) | Ultra | 16 / 40 (16 / 28) | 107 966 (98 166) | 23 (21) | 28–38 | 0 (3) |
| Fichtenwald (2440 m) | Ultra | 16 / 40 (16 / 34) | 86 272 (81 906) | 20 (21) | 42–52 | 2 (3) |
| Fichtenwald (2440 m) | Hoch | 10 / 30 (10 / 23) | 60 070 (54 814) | 14 (13) | 21–31 | 0 (2) |

- **Dreiecke:** bis ~108 000 zusätzlich (Obergrenze rechnerisch unverändert
  16 × 6 000 + 40 × 1 500 = 156 000). Der Wechselring kostet im Wald 5–13 %
  mehr Instanzdreiecke als vorher, weil die Obergrenze jetzt ausgeschöpft wird
  (nächste Bäume statt ganzer Abschnitte), und **die gebackenen Bäume im Ring
  und dahinter bleiben an**: vorher waren 2–5 Abschnitte ausgeblendet, jetzt
  0–2 (je Abschnitt einige Tausend Dreiecke und 2 Zeichenaufrufe, im
  Schattendurchgang je Kaskade noch einmal).
- **Zeichenaufrufe:** Instanzen wie vorher (höchstens 2 je Vorlage im Bild,
  wenn die automatische Instanzierung greift — **nicht gemessen**); dazu die
  nicht mehr ausgeblendeten Abschnitte: +4 bis +8 gegenüber vorher (im
  Referenzrenderer 681 statt 573 Primitive im Mischwald, davon 112 Instanzen).
  Die Kopien der Materialinstanzen ändern die Zahl der Aufrufe nicht (ein
  Abschnitt war schon ein eigenes Renderable): +2 Materialinstanzen je Kachel
  mit Abschnitten (6–8 im Bild).
- **Füllrate:** Im Ring werden beide Darstellungen gerechnet und je Bildpunkt
  eine verworfen; Rinde ist jetzt „masked“ statt deckend (kein früher
  Tiefentest für Stämme). **Nicht gemessen**, nur am Gerät zu klären.
- **Grafikspeicher:** Atlanten 2,8 MB mit Mip-Stufen; Netze ~36 B je Dreieck,
  bei 25 Vorlagen rund 2–3 MB; alle 72 Vorlagen wären 6,2 MB. Unverändert.
- **Arbeitsspeicher:** die gelesene Bibliothek (6,5 MB) bleibt geladen,
  solange die Welt läuft (Vorlagen entstehen bei Bedarf).
- **APK:** Bibliothek unverändert 6,47 MB (gzip 4,38 MB);
  `foliage.filamat` 226 kB statt 210 kB (+16 kB).
- **Platte je Welt:** unverändert (kein neuer Weltbau).
- **Laden:** Vorlagen entstehen beim ersten Baum auf dem Hauptthread
  (einige Millisekunden je Vorlage) — beim ersten Einfahren in einen Wald
  können mehrere in einem Bild zusammenkommen. Nicht gemessen. Die Ausladung
  aller 36 Vorlagen liest der E/A-Thread mit der Bibliothek (nur die Lagen).

### Belegbilder (`docs/indoor/bilder/baeume/`)

Dieselbe gebaute Welt (Testwelt, `indoor-world/15`), dieselbe Kamera, derselbe
Himmel (`indoor-dev sky --time NOON`). Gerendert mit filarender, 960 × 600 je
Hälfte.

**Wechsel vorher / nachher** (`wechsel-*.jpg`, beide Hälften mit Instanzen,
Ultra): links der Stand vor dem Wechselring (zweiter Bau des Renderers aus dem
Commit davor mit dessen Materialien), rechts jetzt.

| Datei | Stelle | Ring rechts |
|---|---|---|
| `wechsel-0780m.jpg` | Ortsdurchfahrt, zwei Laubbäume | 50–60 m |
| `wechsel-1740m.jpg` | Schotterweg im Mischwald | 31–41 m |
| `wechsel-2080m.jpg` | Wald | 28–38 m |
| `wechsel-2440m.jpg` | Fichtenwald | 42–52 m |
| `wechsel-folge-0700m.jpg` | **Anfahrt** auf die zwei Laubbäume der Ortsdurchfahrt, Kamera an den Laufmetern 700 … 770 (alle 10 m), Bildwinkel 26°; oben vorher, unten nachher | 50–60 m |

Was die Folge zeigt: **Vorher** stehen bei 730 m beide Bäume gebacken da
(dunkel, dicht), bei 740 m beide als Instanz (hell, licht, anderer Umriss) —
der ganze Abschnitt in einem Schritt. **Nachher** ist der vordere Baum bei
740 m halb aufgelöst und bei 750 m Instanz; der hintere folgt erst bei 770 m,
wenn er selbst im Ring steht. Die gebackenen Bäume sind vor dem Ring schon
heller getönt.

Dieselbe Anfahrt in Schritten von 0,5 m (725 … 775 m, 100 Schritte),
mittlerer Unterschied zweier aufeinanderfolgender Bilder (0–255, auf 1/8
verkleinert): Median vorher 4,25, nachher 4,26 (die Fahrt selbst); **größter
Schritt vorher 8,68** (bei 759 m, der Abschnitt schaltet um; ein zweiter mit
6,06 bei 733,5 m), **nachher 5,07**. Die beiden Ausreißer sind weg.

Im Standbild bleibt der Ring erkennbar: als feines Punktraster, wo eine
gebackene Krone über die schmalere Krone der Instanz hinausragt (rechts oben in
`wechsel-2080m.jpg`, bei 740/770 m in der Folge), und als doppelter Stamm, wo
beide Stämme nicht deckungsgleich stehen. Siehe „Bekannte Schwächen“.

**Einbau** (`einbau-*.jpg`, neu gerendert): links die gebackenen Bäume
(Standard), rechts mit Instanzen. Gegenüber dem Stand davor sind beide Hälften
im Wald dunkler, weil Laubkarten jetzt Schatten werfen (siehe „Nebenwirkungen“).

| Datei | Stelle | rechts |
|---|---|---|
| `einbau-0780m.jpg` | Ortsdurchfahrt, zwei Laubbäume | Ultra |
| `einbau-1740m.jpg` | Schotterweg im Mischwald | Ultra |
| `einbau-2080m.jpg` | Wald | Ultra |
| `einbau-2440m.jpg` | Fichtenwald | Ultra |
| `einbau-hoch-2440m.jpg` | Fichtenwald | Hoch |

Geprüft: Die Instanzen stehen an den Stellen der gebackenen Bäume, keiner
fehlt; sie werfen Schatten auf Weg und Boden. Ein Ring mit Radius 2 m ergab
(vor dem Einbau der Tönung geprüft) das gebackene Bild samt Schatten bis auf
die 2 m um die Kamera — die Schatten wechseln also mit. **Wind**
(Messung des Einbaus, nicht wiederholt): dieselbe Szene zu zwei Zeitpunkten
(0 s / 1,7 s) — mit Wind ändern sich mit Instanzen 2 742 Bildpunkte
(640 × 400), ohne Wind 224 (Wolkenzug); bei den gebackenen Bäumen 2 195 bzw.
314.

### Bekannte Schwächen des Einbaus

- **Wechselring, Rest:** Der Sprung je Abschnitt ist weg, die beiden Modelle
  sind aber verschieden. (a) **Umriss:** Die gebackene Krone ist dichter und
  ragt über die der Instanz hinaus; im Ring sieht man dort ein Punktraster
  vor dem Himmel. (b) **Raster:** ein Bildpunkt breit, im Referenzbild
  (960 × 600) erkennbar; auf dem Gerät feiner, mit zeitlicher Kantenglättung
  (Ultra) verrechnet — **nicht am Gerät gesehen**. Alpha-to-Coverage hilft
  nicht: beide Darstellungen träfen dieselben Abtastpunkte, dazwischen schiene
  der Hintergrund durch. (c) **Stämme** stehen nicht deckungsgleich (Neigung,
  Dicke): im Ring kurz doppelt. (d) **Ton:** ein Faktor je Kachel und
  Helligkeitsklasse; Kiefern im Mischwald treffen ihn schlechter (Messung
  oben). (e) Bäume **hinter dem Band** (über 20 m neben der Strecke) bleiben
  gebacken und ungetönt — neben getönten Bandbäumen im selben Abstand etwas
  dunkler. **Was den Rest beseitigen würde, geht nur im Weltbau** (nicht
  gebaut, neue Builder-Kennung nötig): die Fernstufe der Bandbäume aus der
  Vorlage ableiten (Kreuzbild oder grobe Hülle der Bibliothekskrone, gleiche
  Farben), dazu Art und Fußpunkt je Ecke, damit das Material je Baum statt je
  Bildpunkt blenden und je Art tönen kann.
- **Nahstufe ↔ mittlere Stufe** (18/25 m) schaltet weiter hart um (gleiche
  Vorlage, gleiche Farben; nicht Teil dieses Auftrags).
- **Kein Ton je Baum** (siehe Entscheidungen); gleiche Vorlagen nebeneinander
  unterscheiden sich nur in Drehung und Größe.
- **Fichten am Weg:** Der Weltbau stutzt gebackene Fichten nah am Weg in der
  Höhe; die Instanz wird nur gleichmäßig skaliert und kann breiter über die
  Fahrbahn ragen.
- **Hänge:** Die Instanz steht senkrecht am Fußpunkt; der Wurzelanlauf reicht
  0,3 m unter den Boden, an steilen Böschungen kann talseitig der Stammfuß
  frei stehen.
- **Automatische Instanzierung gilt für die ganze Engine**, solange Instanzen
  an sind; Filament nennt dafür „etwas Aufwand“, wenn sonst nichts gleich ist.
  Auf den Stufen ohne Instanzen bleibt sie aus.

### Offen — nur auf einem Gerät zu klären

1. Bildrate und Wärme auf Hoch/Ultra mit und ohne Instanzen (Wald- und
   Alleestrecke; die Probefahrt der Diagnoseseite fährt die Testwelt je Stufe).
   Stellschrauben: `TreeInstanceBudget.forDetail`.
2. Ob die automatische Instanzierung auf dem Adreno die Zeichenaufrufe
   wirklich zusammenfasst, und was sie für den Rest der Szene kostet.
3. Alpha-Maske nah (Laub mit `discard`, mehrere Lagen im Bild) — der
   erwartete Engpass, siehe Hochrechnung unten.
4. Ruckeln beim ersten Anlegen der Vorlagen.
5. Wechselring in Fahrt: Ist das Raster zu sehen (Hoch mit MSAA, Ultra mit
   zeitlicher Glättung)? Ringbreite `TreeInstanceBudget.FADE_BAND_M` (10 m),
   Tönung `TreeFadeLook`. Kosten der doppelt gerechneten Bäume im Ring.
6. **Schatten der Laubkarten:** Sah das Gerät vor dieser Änderung Kronenschatten
   auf dem Weg? Jetzt wirft jede Laubkarte bestimmt Schatten; wirkt der Wald
   zu dunkel, ist das eine Entscheidung über Licht, nicht über den Wechsel.
7. Rinde über `foliage.mat`: gleiche Farbe wie vorher auch auf dem Gerät?
8. Speicher auf kleinen Geräten (Bibliothek im Arbeitsspeicher, Vorlagen im
   Grafikspeicher).

---

# Prototyp (Stand vor dem Einbau)

Der Rest des Dokuments beschreibt den Generator und die Messungen des
Prototyps (`claude/grafik-baeume`), unverändert bis auf diesen Hinweis. Wo dort
„nicht eingebaut“ steht, gilt jetzt der Abschnitt „Einbau“ oben.

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

## Einbauplan (umgesetzt — Abweichungen siehe „Einbau“ oben)

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
./gradlew :core:test :app:testDebugUnitTest :indoor-dev:test
# Bibliothek neu erzeugen (nur nach Aenderungen am Generator)
./gradlew :indoor-dev:run --args="tree-library --out app/src/main/assets/indoor/models/trees.glb"
# Belegbilder des Einbaus
./gradlew :indoor-dev:run --args="testworld --out tool/indoor-dev/out/testwelt --quality HIGH"
./gradlew :indoor-dev:run --args="sky --time NOON --out tool/indoor-dev/out/sky"
tool/indoor-dev/filament/build.sh
python3 tool/indoor-dev/baeume/einbau.py tool/indoor-dev/out/testwelt --sky tool/indoor-dev/out/sky \
        --out tool/indoor-dev/out/baeume-einbau --at 780,1740,2080,2440 --jpeg-out docs/indoor/bilder/baeume
# Laubmaterial nach Aenderungen an foliage.mat neu uebersetzen (matc 1.75.1)
tool/indoor-dev/filament/materials.sh
# Wechsel vorher/nachher, Anfahrt und Feinfolge; <alt> = Arbeitsbaum des Commits davor, dort build.sh
python3 tool/indoor-dev/baeume/wechsel.py tool/indoor-dev/out/testwelt --sky tool/indoor-dev/out/sky \
        --out tool/indoor-dev/out/baeume-wechsel --alt-filarender <alt>/tool/indoor-dev/filament/build/filarender \
        --alt-materials <alt>/app/src/main/assets/indoor/materials --alt-library <alt>/app/src/main/assets/indoor/models/trees.glb \
        --at 780,1740,2080,2440 --folge 700:770:10 --fein 725:775:0.5 --tele 26 --jpeg-out docs/indoor/bilder/baeume
# Bilder des Prototyps (alt/neu, gebacken)
./gradlew :indoor-dev:run --args="tree-proto --out tool/indoor-dev/out/baeume"
python3 tool/indoor-dev/baeume/render.py tool/indoor-dev/out/baeume --sky tool/indoor-dev/out/sky \
        --renderer both --jpeg-out docs/indoor/bilder/baeume
```

**Stand Wechselring:** `./gradlew -Ptrailscape.toolOnly=true :core:test
:indoor-dev:test` gebaut und grün (darunter `TreeFadeTest`, 12 Tests, und die
unveränderten `TreeInstancesTest`). **`:app` UNGEBAUT:** Maven Central lieferte
für das Compose-Compiler-Plugin HTTP 429; `TreeInstancer.kt`,
`WorldMaterialProvider.kt`, `WorldMaterialRules.kt` und
`LifeAndMaterialsTest.kt` sind nicht übersetzt, die benutzten Filament-Methoden
(`MaterialInstance.duplicate`, `setCullingMode`, `setParameter` mit vier
Zahlen, `RenderableManager.get/setMaterialInstanceAt`) nur gegen die Klassen
von filament-android 1.75.1 nachgeschlagen. filarender (C++) und
`foliage.filamat` gebaut und für alle Bilder oben benutzt.

**Beim Einbau (Sitzung davor) über Gradle UNGEBAUT:** Maven Central lieferte HTTP 429,
ein Android-SDK gibt es im Container nicht. Stattdessen:

- `:core` (Haupt- und Testquellen) und `:indoor-dev` mit eigenständigem
  `kotlinc` 2.3.20 übersetzt (kotlinx-serialization 1.9.0 aus der
  Gradle-Distribution statt 1.7.3, BRouter-Quellen mit `javac`); **alle
  Testklassen von `:core`** mit JUnit 4 über `kotlin-test-junit` ausgeführt
  (die Annotation `org.junit.jupiter.api.Timeout` durch einen leeren Ersatz
  vertreten): grün. Ebenso die beiden Testklassen von `:indoor-dev`.
- `:app`: **nicht gebaut, Tests nicht gelaufen.** Die Dateien der Darstellung
  ohne Oberfläche (u. a. `TreeInstancer.kt`, `TileStreamer.kt`,
  `FilamentAssets.kt`, `RenderSupport.kt`) sind gegen die Java-Quellen von
  Filament/gltfio 1.75.1 typgeprüft; `FilamentWorldRenderer.kt`,
  `DiagnosticsScreen.kt` und `TilePlannerTest.kt` sind nur gegengelesen.
- filarender (C++) gebaut und für die Bilder oben benutzt.
