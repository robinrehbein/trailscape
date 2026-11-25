# Indoor — Luftbild im Streckenband (12,5 cm nah an der Fahrlinie)

Stand: 25.11.2026, Branch `claude/grafik-luftbild-20cm`.

Ziel des Inhabers: Eine Strecke soll in der 3D-Ansicht aussehen wie in echt.
Dieser Teil: ein schärferes Luftbild im schmalen Band um die Strecke, dahinter
bleibt alles wie bisher. Umgesetzt im Weltbau (`core`), im Katalog- und
Server-Bau (`tool/indoor-dev`, `server/worlds`) und im Gelände-Material der App
(`terrain_band.mat`). **Der Kotlin-Teil ist in dieser Umgebung ungebaut**
(Gradle bekommt von Maven Central HTTP 429); die Bilder unten stammen aus einem
Python-Nachbau desselben Schritts, angewandt auf fertige Katalogpakete, und aus
dem echten Filament 1.75.1 (`filarender`) mit dem neuen Material.

## 1. Bestandsaufnahme (gemessen)

Gemessen am Katalogpaket `solitude-v3` (MEDIUM, 13,9 km, 140 MB, Laser +
LGL-Luftbild) und an eigenen GetMap-Abrufen beim LGL und bei Geobasis NRW.

| Stufe | Kachel | Textur | Bodenauflösung | JPEG im Paket (Mittel) | bit/px | GPU je Kachel (RGBA8 + Mipmaps) |
|---|---|---|---|---|---|---|
| 0 | 256 m | 1024 px | **25 cm** | 170 kB | 1,29 | 5,6 MB |
| 1 | 1024 m | 512 px | 2 m | 65 kB | 1,99 | 1,4 MB |
| 2 | 4096 m | 512 px | 8 m | 82 kB | 2,50 | 1,4 MB |

- **Quelle:** LGL BW `IMAGES_DOP_20_RGB` (DOP 20 cm), Geobasis NRW `nw_dop_rgb`
  (laut Capabilities **10 cm**, RGBI). Der WMS tastet beim Abruf auf 25 cm
  herunter — die feinere Hälfte der Quelle kommt nie an.
- **Kompression:** JPEG des Dienstes (rund 1,3 bit/px, Blockartefakte sichtbar).
  In Kacheln mit Wald wird das Bild durch `ForestFloor` abgetönt und **neu**
  kodiert (JPEG q85, zweite Generation); dort ist es nicht „unverändert“.
- **Paket:** Luftbild Stufe 0 12,7 MB + Stufe 1 2,8 MB + Stufe 2 1,1 MB = 16,6 MB
  von 140 MB (12 %); Stufe 0 ≈ 0,9 MB je km.
- **Grafikspeicher:** gltfio dekodiert JPEG zu RGBA8 und rechnet Mipmaps:
  1024² = 5,6 MB. Eine L0-Kachel hat insgesamt 13,5 MB `textureBytes` im Mittel
  (bis 19,3 MB): Fahrbahn-, Rand-, Gebäude- und Laubtexturen stecken in **jeder**
  Kachel-GLB und werden je Kachel hochgeladen. Das Luftbild ist davon nur gut
  ein Drittel.

### Wo die Unschärfe entsteht

Lenkerkamera: Auge 1,46 m über der Fahrbahn, 74° senkrechtes Bildfeld
(Hochformat, `camerarig.mjs`). Ein Bildpunkt (bei 1200 px Höhe) deckt auf dem
Boden in Entfernung *d* quer `d · 0,0011` und längs `d² · 0,0011 / 1,46`:

| Entfernung | quer | längs | Folge heute (25 cm, ohne Anisotropie) |
|---|---|---|---|
| 10 m | 1,1 cm | 7 cm | Texel 25 cm stark vergrößert → matschig, Blockartefakte des JPEG sichtbar |
| 20 m | 2,2 cm | 30 cm | Grenze Vergrößerung/Verkleinerung |
| 40 m | 4,3 cm | 1,2 m | Mip-Stufe nach der **langen** Achse: Boden quer auf ~1,2 m verwaschen |
| 80 m | 8,6 cm | 4,7 m | quer ~5 m verwaschen, nur noch Farbflächen |

Reihenfolge der Ursachen:

1. **Abruf** 20 cm (BW) bzw. 10 cm (NRW) → 25 cm.
2. **JPEG** 1,3 bit/px des Dienstes, im Wald zweite Generation.
3. **Nähe (bis ~20 m):** 25-cm-Texel vergrößert — hier hilft nur mehr Auflösung.
4. **Ferne (20–100 m):** keine anisotrope Filterung. gltfio übernimmt sie nicht
   aus dem glTF-Sampler, die Java-Seite gibt die Texturen nicht heraus, und
   `terrain.mat` tastet das Luftbild einmal ab. Die Hardware wählt die Mip-Stufe
   nach der langen (Längs-)Achse — das ist zwischen 20 und 100 m der **größte**
   sichtbare Verlust, größer als die Quellauflösung.
5. **Material:** `terrain.mat` legt Detailrauschen darüber (bis 55 m), schärft
   aber nichts.

## 2. Konzept „Streckenband“ — Optionen

Annahme: Band ±20 m, Ziel 12,5 cm (Zweierpotenz-Kanten verlangt der Vertrag;
20 cm ginge nur mit 320 px je 64 m). Zahlen für Solitude (75 L0-Kacheln),
geschätzt aus der Streckengeometrie (`band_est`), GPU in RGBA8 wie gltfio
heute lädt.

| Option | Paket je Strecke | GPU je L0-Kachel | Ladezeit | Nähte | Aufwand / Risiko |
|---|---|---|---|---|---|
| A: ganze L0-Kachel feiner (2048 px) | +4× Luftbild ≈ +40 MB | **+16,8 MB** | +4× Dekodieren | keine | zu teuer |
| B: zweite Detailtextur je Kachel im Band (zweiter Sampler im Material) | wie C | wie C | wie C | keine | gltfio bindet nur `baseColorMap`; zweite Textur müsste die App selbst aus der GLB holen und binden — neuer nativer Pfad |
| C: **feinere Kachelung nur im Band** (32-m-Felder, eigenes Primitiv + Bild) | **+0,5–1,2 MB/km** (gemessen) | **+3,5–3,9 MB** (gemessen, Mittel) | +12 kleine JPEG je Kachel | Schärfesprung an Feldgrenzen ≥ 20 m neben der Linie | nur Weltbau + ein neues Material; ältere Apps zeichnen es mit dem Ubershader |
| D: virtuelle Textur / Clipmap entlang der Strecke | klein | fest (z. B. 16 MB) | Nachladen während der Fahrt | Übergänge der Clipmap-Ringe | Seitentabelle, Streaming, Feedback-Pass: neuer Renderer, in Filament 1.75 über gltfio nicht vorgesehen |

Feldgröße (Solitude, ±20 m): 32-m-Felder decken 18 % der L0-Fläche (876
Felder), 64-m-Felder 27 % (329 Felder) — 32 m spart ein Drittel Grafikspeicher.
Breite: ±15 m 772, ±20 m 876, ±25 m 966, ±30 m 1096 Felder.

**Gewählt: C** mit 32-m-Feldern, 256 px (12,5 cm), Band ±20 m, höchstens 24
Felder je Kachel (die nächsten zuerst), dazu Software-Anisotropie im
Band-Material (gegen Ursache 4).

## 3. Umsetzung

### Weltbau (`core/…/indoor/world/AerialBand.kt`, neu)

- `AerialBand.cellsNear`: Felder der L0-Kachel mit Abstand ≤ `bandM` zur
  Fahrlinie (Strecke-zu-Rechteck), die nächsten zuerst, höchstens
  `maxCellsPerTile`.
- `AerialBandStore.fetch`: Bild je Feld vom **selben Dienst wie die Kachel**
  (zuerst gefragt), gleiche Positivliste, gleicher Cache, gleiche
  Leerbild-Erkennung (`fetchAerial`).
- `AerialBand.prepare`: unscharfe Maske (Stärke 0,35, 3 × 3), im Wald die
  abgetönte Farbe der Kachel übernommen (wo Kachel und rohes Luftbild sich
  unterscheiden) — sonst entstünde an den Feldgrenzen eine Farbkante.
  Baseline-JPEG q90.
- `AerialBand.split`: das fertige Geländenetz (Gitter oder Laser-Netz) wird
  **nicht neu gerechnet**. Dreiecke, deren drei Ecken in einem Feld mit Bild
  liegen, wandern in ein eigenes Netz am Knoten `terrain` mit Material
  `x_mat_terrain_band_<n>_<i>_<r>` (n = Felder je Kante, i = Spalte nach Osten,
  r = Zeile von Norden), UV über das Feld mit halbem Texel Einzug. Gleiche
  Eckpunkte → keine neuen Kanten, keine Risse. Dreiecke über einer Feldgrenze
  (grobe Laser-Dreiecke) behalten das Kachel-Luftbild.
- Haken in geteilten Dateien (additiv): `TileOutput.level0Mesh`/
  `replaceLevel0Mesh` (`WorldInternals.kt`), Konstruktor-Option `aerialBand`
  und ein Aufruf nach den Schichten (`WorldBuilderImpl.kt`, auch im
  Fingerabdruck), optionales Manifestfeld (`WorldManifest.kt`).
- Einstieg: `createWorldBuilder(laser, aerialBand)`.
- **Gerät:** Der Bau auf dem Handy bekommt kein Band (keine neuen Abrufe vom
  Handy).

### Paketqualität (wählbar)

| Stufe (`--aerial-band`) | Band | Auflösung | Felder je Kachel höchstens |
|---|---|---|---|
| `aus` (Vorgabe) | – | 25 cm wie bisher | – |
| `band` | ±20 m | 12,5 cm | 24 |
| `band-breit` | ±30 m | 12,5 cm | 32 |

- Bei Qualität **LOW** baut der Weltbau kein Band (Texturbudget 128 MB).
- `indoor-dev build|catalog-build|server-build … --aerial-band band`
- Bau-Dienst: Umgebungsvariable `AERIAL_BAND` (`server/README.md`); geht in den
  Auftragsschlüssel ein (gleiche Strecke mit anderer Stufe → neues Paket).
- Manifest: optionales Feld `"aerialBand": {"bandM":20,"cellM":32,"texturePx":256}`.
  Ältere Pakete haben es nicht und laden wie bisher; die App liest es nicht
  (nur Nachweis/Prüfung). Grafikspeicher zählt die App ohnehin über
  `textureBytes` je Kachel — die Bandbilder sind darin enthalten.

### Material (`app/src/main/materials/terrain_band.mat`, neu)

- Wie `terrain.mat` (Bodenart aus der Farbe, Detail, Makro, Waldboden wärmer),
  aber: Kachel-UV für Detail und Makro aus `bandCell` zurückgerechnet → das
  Detail läuft ohne Naht über Band und Kachel.
- **Software-Anisotropie** bis 90 m: vier Abtastungen entlang der langen Achse
  der Pixelspur, Mip-Stufe nach der kurzen Achse (gleiches Verfahren wie
  `surface.mat`).
- `WorldMaterialRules.kindFor`: `x_mat_terrain_band_…` (OPAQUE, gültiger Name)
  → `TERRAIN_BAND`; `WorldMaterialProvider` setzt `bandCell` und `anisoRange`
  nur, wenn das Material sie deklariert (`Material.hasParameter`), und bindet
  `detailMap`/`macroMap` wie beim Gelände. **Sicherer Modus:** das Paket wird
  nicht geladen → Ubershader, keine neuen Effekte.
- Ältere App mit neuem Paket: `x_…`-Material → Ubershader mit dem feineren
  Bild, kein Absturz (keine unbekannten Parameter).
- `filarender` spiegelt die Regel (`TERRAIN_BAND`, `settings.terrainBand`).
- `.filamat` mit matc 1.75.1 kompiliert (`tool/indoor-dev/filament/build`).

### Texturkompression: KTX2 / ASTC / ETC2 gegen JPEG

Was die App heute lädt: JPEG/PNG über den stb-Anbieter von gltfio → RGBA8.
Was Filament 1.75.1 zusätzlich kann (Quellcode nachgelesen,
`android/gltfio-android/…/ResourceLoader.java` und `libs/gltfio/src/Ktx2Provider.cpp`,
Tag `v1.75.1`, abgerufen 25.11.2026): Der Java-`ResourceLoader` registriert
`image/ktx2` (Basis Universal, `KHR_texture_basisu`) und transkodiert auf dem
Gerät bevorzugt nach **ETC2_EAC_SRGBA8** (1 Byte/px, ein Viertel von RGBA8);
ASTC fragt der Anbieter nicht an. Mipmaps müssen im KTX2 stecken.

| Format | Paket | GPU je 256²-Feld | GPU je 1024²-Kachel | Dekodieren | hier geprüft |
|---|---|---|---|---|---|
| JPEG (heute) | 8–9 kB (Band, gemessen) | 0,35 MB | 5,6 MB | stb, schnell | ja |
| KTX2 ETC1S | ähnlich JPEG (Schätzung) | 0,09 MB | 1,4 MB | Transkodieren, schnell | **nein** |
| KTX2 UASTC + zstd | ~2–3× JPEG (Schätzung) | 0,09 MB | 1,4 MB | Transkodieren, langsamer | **nein** |

Empfehlung: Als nächster Schritt **alle** Luftbilder einer Paketstufe als KTX2
(ETC1S für Kachel und Band) — das senkt den Grafikspeicher des Luftbilds auf
ein Viertel und bezahlt das Band mehrfach. Hier nicht umgesetzt, weil es sich
in dieser Umgebung nicht prüfen ließ: Der Basis-Encoder (`basisu`, liegt dem
Filament-Release bei) durfte hier nicht ausgeführt werden. Ungeprüft eingebaut
hätte es auf dem Gerät einen neuen nativen Ladeweg ohne jeden Beleg geöffnet.

### Fahrbahn: Luftbild maskieren?

Auf der Fahrbahn der Strecke ist das Luftbild **nicht sichtbar**: Die
Fahrbahnschicht (`route`) legt Belagstexturen darüber, die Randstreifen decken
bis 1,8–2,0 m daneben, ihre Ausläufe bis 4–6,5 m (world.md, „Kreuzungen und
Ortsdurchfahrten“). Autos und Schatten im Luftbild sieht man nur auf **anderen**
Straßen und Parkplätzen neben der Strecke — dort sind sie echt. Eine Maske auf
der Fahrlinie brächte also nichts; das Band beginnt sichtbar erst hinter den
Randstreifen. Nicht gemacht: Entsättigen/Maskieren von Straßenpixeln anderer
Wege (bräuchte die Straßenflächen im Bild; offen, falls es auf dem Gerät stört).

## 4. Kosten

Gemessen mit dem Python-Nachbau (`band`: ±20 m, 32-m-Felder, 256 px, Schärfe
0,35, JPEG q90, höchstens 24 Felder je Kachel) auf den Katalogpaketen; GPU wie
gltfio lädt (RGBA8 + Mipmaps), „Spitze“ = größte Summe `textureBytes` der zugleich
gebrauchten Kacheln (wie `worldBudgetOf`, alle 10 m).

| Strecke | km | Felder | Bandbilder | Geometrie (netto) | Paket mehr | je km | L0-Kachel GPU (Mittel) | Spitze GPU gleichzeitig |
|---|---|---|---|---|---|---|---|---|
| Solitude (BW, DOP 20) | 13,9 | 843 in 75 Kacheln | 7,17 MB (8,5 kB/Feld) | +0,38 MB | **+7,6 MB (+5 %)** | 0,54 MB | 13,5 → 17,4 MB | **188,5 → 238,1 MB (+26 %)** |
| Balkantrasse (NRW, DOP 10) | 7,7 | 624 in 63 Kacheln | 9,13 MB (14,6 kB/Feld) | +0,28 MB | **+9,4 MB (+8 %)** | 1,22 MB | 15,1 → 18,5 MB | **187,7 → 228,6 MB (+22 %)** |
| marbach-lemberg (BW), nur 25 Kacheln um km 4, 6,5, 9 | – | 291 | 3,82 MB | +0,12 MB | – | – | – | – |

- **Paket:** weit unter dem Rahmen (+150 MB); auch eine 40-km-Strecke in NRW
  läge bei rund +50 MB. „Geometrie netto“ ist, was der Kotlin-Bau zusätzlich
  schreibt (doppelte Eckpunkte an Feldgrenzen); der Nachbau selbst hängt die alten
  Indexdaten ungenutzt an und ist deshalb größer — nicht maßgeblich.
- **Grafikspeicher:** +22–26 % in der Spitze, bei MEDIUM (256 MB) noch im Budget,
  aber deutlich. Deshalb gibt es bei **LOW** (128 MB) kein Band. Die App zählt die
  Bandbilder über `textureBytes` mit; die Kachelplanung (`planTiles`) rechnet das
  Band **nicht** ein (Korridor unverändert, Schätzung). Mit KTX2 (ETC2, 1 Byte/px)
  läge Kachel-Luftbild + Band bei rund 2,4 statt 9,5 MB je L0-Kachel — die Spitze
  wäre dann **unter** heute (Schätzung).
- **Bauzeit:** +1 GetMap je Feld (Solitude 876, Balkantrasse 668). Der Nachbau
  brauchte einzeln ~11 min für Solitude; der Kotlin-Bau holt mit bis zu 4 Fäden,
  geschätzt +3–5 min je Strecke (Schätzung, Dienst-Antwortzeit 0,5–1,5 s).
- **Ladezeit Gerät:** je L0-Kachel bis 24 zusätzliche 256²-JPEG (stb) plus
  Mipmaps — geschätzt +15–30 ms je Kachel (**ungemessen**). Im Referenzrenderer
  (Desktop) unverändert (loadMs 310 → 308).
- **Gerätebau:** unverändert (kein Band, keine Abrufe).

## 5. Belege (Bilder)

Gleiche Kamera vorher/nachher, echtes Filament 1.75.1 (`filarender`, Mesa
llvmpipe), Band aus dem Nachbau, neues Material `terrain_band.filamat`.
**Einschränkungen der Bilder:** ohne Gradle kein `indoor-dev sky` — Himmel,
Licht und Dunst sind eine neutrale Hand-Stimmung (`sky=0`, Sonne 235°/40°),
die Detailtextur ist neutral (grau 128), Detail und Makro tun also nichts; der
Lenker ist ein leerer Platzhalter. Die Bilder zeigen den Unterschied des
**Luftbilds**, nicht das Gesamtbild des Geräts. Ordner
`docs/indoor/bilder/luftbild-band/`:

| Bild | Was man sieht |
|---|---|
| `solitude-quelle-25cm-20cm-12cm.jpg` | Rohbild des LGL an derselben Stelle: 25 cm (heute), 20 cm, 12,5 cm — Markierung, Bordkante, Kronen bei 12,5 cm klar schärfer |
| `balkantrasse-km1-8-drohne(-ausschnitt).jpg` | **deutlich:** geparkte Autos, Pflaster und Wiese neben der Straße scharf statt verschmiert; die Feldgrenze des Bands ist als Schärfesprung zu sehen (links Mitte) |
| `marbach-lemberg-km9-drohne.jpg` | Fahrspuren im Acker rechts der Straße schärfer; weniger Längsverschmierung (Anisotropie) |
| `marbach-lemberg-km6-5-drohne.jpg` | Ortsrand: Verschmierung neben den Häusern weg, dafür sichtbare JPEG-Blöcke des Dienstes (die Schärfung verstärkt sie etwas) |
| `marbach-lemberg-km4-drohne.jpg`, `balkantrasse-km5-8-drohne.jpg` | Wiese/Wald: kaum Unterschied — das Luftbild hat dort wenig Struktur bzw. ist Waldboden |
| `marbach-lemberg-km4-lenker.jpg`, `-km9-lenker.jpg`, `balkantrasse-km1-8-lenker.jpg`, `marbach-lemberg-km9-seitenblick.jpg` | **Lenkersicht: praktisch kein Unterschied** (mittlere Abweichung 0,06–0,12 Graustufen) |
| `marbach-lemberg-km9-draufsicht-12m.jpg` | Draufsicht aus 12 m: die ersten Meter neben der Fahrbahn deckt der Randstreifen samt Auslauf; dahinter etwas mehr Struktur |

Ehrlich: Aus der **Lenkersicht** bringt das Band an diesen Stellen fast nichts.
Gründe: (1) Randstreifen und ihre Ausläufe decken die ersten 2–6,5 m, wo der
Gewinn am größten wäre; (2) dahinter sieht man den Boden so flach, dass auch mit
12,5 cm und vier Abtastungen die Mip-Stufe bestimmt; (3) Wiesen und Äcker haben
im Luftbild wenig Struktur. Sichtbar wird es aus der Drohne, in Ortsdurchfahrten
(Parkplätze, Pflaster, Einfahrten) und an Strukturen wie Fahrspuren.

## 6. Offen

- **Ungebaut:** Kotlin (`core`, `tool`, `app`) — Gradle 429. Tests geschrieben
  (`AerialBandTest`, `TerrainBandRulesTest`), nicht gelaufen. Geprüft:
  `terrain_band.mat` kompiliert mit matc 1.75.1 und läuft in `filarender`
  (92–104 Band-Instanzen je Bild, keine Fehler); `server/worlds`-Tests grün;
  der Python-Nachbau folgt derselben Regel, ist aber nicht der Kotlin-Code.
- **`WORLD_BUILDER_ID` anheben**, sobald Katalog oder Bau-Dienst mit Band baut:
  Die Welt-ID hängt nicht an der Paketstufe; ohne Anhebung erkennt die App ein
  Bandpaket nicht als neue Fassung (nicht hier gemacht).
- **KTX2 (ETC1S) für alle Luftbilder** als nächster Schritt — senkt den
  Grafikspeicher stärker, als das Band ihn erhöht; Encoder `basisu` liegt dem
  Filament-Release bei, durfte hier aber nicht laufen. Danach Band ggf. auf ±30 m.
- **Lenkersicht:** größere Hebel als die Auflösung: Software-Anisotropie auch im
  normalen `terrain.mat` (heute nur im Band), schmalere Randstreifen-Ausläufe dort,
  wo das Luftbild gut zur Fahrbahn passt.
- **Schärfe 0,35** verstärkt die JPEG-Blöcke des Dienstes etwas
  (marbach-lemberg km 6,5); auf dem Gerät ansehen, ggf. 0,2.
- Voranschlag (`plan`) zählt die Bandabrufe noch nicht mit; `planTiles` rechnet das
  Band nicht ins Grafikbudget.
- Autos/Schatten auf Nebenstraßen und Parkplätzen bleiben (echt); Maske auf der
  Fahrlinie nicht nötig (Abschnitt 3).

### Prüfliste fürs Gerät

1. Katalog- oder Server-Bau einer BW/NRW-Strecke mit `--aerial-band band`
   (bzw. `AERIAL_BAND=band`), `check` muss sauber sein, Manifest hat `aerialBand`.
2. Laden in der App (MEDIUM): kein Absturz, keine schwarzen/rosa Felder neben der
   Strecke, keine Risse an Feldgrenzen.
3. Diagnose: Texturspeicher in der Spitze ≈ +25 % gegenüber derselben Strecke ohne
   Band, Bildrate unverändert (Band-Material mit 4 Abtastungen bis 90 m).
4. Drohnenkamera über Ortsdurchfahrt: Autos/Pflaster scharf; Feldgrenze des Bands
   als Schärfesprung sichtbar, aber ohne Farbkante (auch im Wald).
5. Sicherer Modus: Band erscheint mit Ubershader (ohne Detail), kein Absturz.
6. Ältere App-Version mit Bandpaket: lädt, Band ohne Detail.
7. Lenkersicht vorher/nachher vergleichen — Erwartung: kaum Unterschied.

## Quellen

- LGL BW, DOP 20 (WMS `WMS_LGL-BW_ATKIS_DOP_20_C`), Lizenz dl-de/by-2-0,
  https://www.lgl-bw.de/Produkte/Open-Data/ (Abruf 25.11.2026). Namensnennung
  im Manifest unverändert (`Luftbild: Datenquelle: LGL, www.lgl-bw.de, dl-de/by-2-0 (Datenbezug 2026)`).
- Geobasis NRW, DOP (WMS `wms_nw_dop`, Ebene `nw_dop_rgb`, „Bodenauflösung von
  10cm/Pixel“ laut GetCapabilities), Lizenz dl-de/zero-2-0,
  https://www.govdata.de/dl-de/zero-2-0 (Abruf 25.11.2026).
- Das Band nutzt **dieselben** Dienste wie das Kachel-Luftbild, keine neue
  Quelle; die Namensnennung bleibt dieselbe.
- Filament 1.75.1 (Apache-2.0), https://github.com/google/filament/tree/v1.75.1
  (Abruf 25.11.2026).
