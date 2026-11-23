# Indoor — Slice indoor-world (Weltbau und Entwicklerwerkzeug)

Stand: 23.11.2026, Branch `claude/indoor-world`. Vertrag: `docs/indoor.md`
(Abschnitte 7.1–7.5, 8, 9).

## Stand

| Teil | Stand |
|---|---|
| Einstieg `IndoorWorldEntry.kt` | **ohne Platzhalter.** `createWorldBuilder()` → `WorldBuilderImpl(FullWorldParts)`, `createGeoPipeline` → Positivliste, Plattencache, Drosselung; Register `aerialSources` mit NRW, Sachsen, Thüringen, Brandenburg/Berlin |
| Voranschlag `plan` | ohne Netz: Kacheln, Anfragen je Art, Download, Platz, Hosts, Luftbild-Dienste |
| Bau `build` | Höhen (Terrarium z13/z11) → Ursprung → Vektorkacheln (TileJSON, z14/z12) → Brücken, Belag aus Vektorkacheln, falls die Strecke keinen hat → Streckenband → endgültige Planung → Kacheln parallel (bis 4 Threads), atomar geschrieben, `manifest.json` zuletzt. Fortsetzen nach Abbruch (fertige Kacheln bleiben). `isCancelled()` wird mindestens alle 50 ms gefragt |
| Testwelt `buildTestWorld` | ohne Netz, 3 km Runde mit allen Belagsklassen, Dorf, Laub- und Nadelwald, Felder, Teich, Bach mit Holzbrücke; deterministisch (Byte-gleich) |
| Streckenband | Lage geglättet (≤ 1,2 m), Höhe vom Gelände (σ 12 m), Brücken überbrückt, Steigung ≤ 24,5 %, Laufmeter unverändert |
| Gelände | `CarvedGround`: gedämpftes Höhenmodell, Straßen quer eben, Strecke mit Bankett und Böschung (Nachweis im KDoc: kein Dreieck sticht durch die Fahrbahn), Wasser abgesenkt; Stufe 0 Gitter 4 m (MEDIUM), ab Stufe 1 4×4 Teilflächen, Schürzen |
| Bodentextur | Luftbild unverändert (JPEG Baseline, sonst PNG neu angefragt) oder aus Landnutzung erzeugt; Leerbild-Erkennung |
| Fahrbahn `route` | je Belag kachelnde Textur, weicher Belagswechsel, Ränder nach Belag und Umgebung (Bankett, Bordstein mit Gehweg, Graskante), Böschungsstreifen, Markierung auf Landstraßen |
| Straßen `roads` | übrige Straßen und Wege der Karte, flach auf dem Gelände |
| Gebäude `buildings` | Grundriss × Höhe, Satteldächer/Walmdächer, Anbauten, Fassadenatlas, Stufe 1 grob. Dachfarbe je Gebäude (deterministisch): Ziegelrot, Rotbraun, Braun, Anthrazit, Grau, wo möglich aus der Luftbildfarbe; Grau aus dem Luftbild wird bei Ziegeldächern teils als Rotbraun/Braun gedeutet |
| Bäume `trees` | **neu:** Einzelbäume in vier Arten (runder Laubbaum, schlanke Pappel/Birke, gestufte Fichte, kleiner Feld-/Obstbaum) bis 70 m neben der Strecke: verbeulte, überlappende Kronenkörper mit geglätteten Normalen, Größen- und Farbvariation, Wurzelanlauf. Drei Detailstufen, budgetabhängig vom Weg nach außen vergeben. Standorte aus der Kronenbedeckung des Luftbilds bzw. der Landnutzung; dahinter und in Stufe 1 Waldkörper (hügeliges Kronendach, das am Waldrand bis zum Boden abfällt, ohne Wände) |
| Wasser `water` | **neu:** Wasserspiegel auf Uferhöhe über den Wasserflächen der Karte |
| Kleinteile `props` | Leitpfosten, Laternen, Ortstafel, Weidezäune, Holzpolter, Bänke, Wegweiser |
| Detailstufen | 3 Stufen (256 m / 1 km / 4 km), `show` nach Laufmetern, Budget je Qualität nachgerechnet (Bau bricht ab, falls überschritten) |
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
| `dorf-altenberg-fachwerk.jpg` | Altenberg (NRW), Start: Fachwerkhaus direkt am Weg, Laubbäume, Halle dahinter |
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

## Abweichungen vom Vertrag

1. **Laub `alphaMode = MASK` ohne Ausschnitte.** Kronen sind geschlossene
   Körper mit voll deckender, kachelnder Textur (Material ist dennoch
   `MASK` und doppelseitig, wie in `WorldNames`). Löcher in der Textur zeigten
   die dunklen Innenseiten als schwarze Flecken.
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

## Bitten an andere Slices

- **indoor-app:** die sechs Hosts oben in `PRIVACY.md` aufnehmen;
  Quellenangaben aus `WorldManifest.attribution` in Weltverwaltung, HUD-„ⓘ“
  und `OpenSourceNotices` (dort zusätzlich: Höhen Tilezen/AWS, OpenFreeMap/
  OpenMapTiles/OSM ODbL, Luftbilder dl-de/zero-2-0, dl-de/by-2-0, CC BY 4.0).
  `WorldBuilder.build` auf `Dispatchers.IO` aufrufen; Abbruch über
  `isCancelled()` greift innerhalb von 50 ms, eine laufende Kachel wird aber
  noch zu Ende gerechnet.
- **indoor-render:** Laub ist `MASK` + doppelseitig ohne echte Ausschnitte;
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
- [ ] Bauzeit und Datenmenge für eine 30–50-km-Runde über WLAN/Mobilfunk.
- [ ] Speicher bei HIGH (Texturen bis 400 MB laut Budget).
- [ ] Abbruch während „Welt laden“ und Fortsetzen.
- [ ] Wirkt der Wald im Gegenlicht und bei tiefer Sonne nicht zu dunkel?
