# Indoor — generierte Welt zur Einheit des Tages

Stand: 24.11.2026, Branch `claude/indoor-generiert`. Vertrag: `docs/indoor.md`
(unverändert), Weltbau: `docs/indoor/world.md`.

Neben echten Welten für echte Strecken gibt es für die „Einheit des Tages“
eine **erfundene** Landschaft: auf Abruf, auf dem Handy, ohne Server und ohne
Download, passend zur Trainingseinheit. Die Einheit wird an anderer Stelle in
ein **Zielprofil** übersetzt (Branch `claude/indoor-tageseinheit`); hier
entsteht aus dem Zielprofil die Strecke und die Welt. Gleiches Profil →
gleiche Strecke → bytegleiche Welt.

## Schnittstelle

Paket `de.trailscape.core.indoor.generated`:

```kotlin
data class GeneratedProfile(val seed: Long, val legs: List<Leg>) {
    data class Leg(val lengthM: Double, val gradePct: Double, val kind: Kind)
    enum class Kind { FLAT, ROLLING, CLIMB, DESCENT }
    val lengthM: Double
}

fun generatedIndoorRoute(profile: GeneratedProfile, name: String = ""): IndoorRoute
fun generatedWorldPlan(profile: GeneratedProfile, quality: IndoorQuality, name: String = ""): WorldPlan
fun buildGeneratedWorld(plan: WorldPlan, profile: GeneratedProfile, outDir: File, listener: WorldBuildListener): WorldManifest
fun describeGeneratedProfile(profile: GeneratedProfile): List<String>   // Werkzeug/Fehlersuche
```

* `generatedIndoorRoute` liefert eine `IndoorRoute` mit allen Zusicherungen
  aus `IndoorRoute.kt` (Abstand 5 m, Steigungen geglättet und in ±25 %,
  Belag lückenlos). Länge = `profile.lengthM` (auf den Meter genau), kein
  Rundkurs. `id` = `gen<Fassung>-<Hash von Samen und Legs>`.
* `generatedWorldPlan` ist der Voranschlag ohne Netz: `requests` leer,
  `hosts` leer, `downloadBytes` 0, keine Luftbild-Dienste. `worldId` wie bei
  echten Strecken aus `route.id` und Qualität (also auch abhängig von
  `WORLD_BUILDER_ID`).
* `buildGeneratedWorld` baut wie `WorldBuilder.build` (fortsetzbar, Manifest
  zuletzt, `isCancelled()` wird abgefragt), wirft `WorldBuildCancelled` bzw.
  `WorldBuildFailed`. Nach dem Bau kann die Sitzung wie gewohnt
  `WorldTrack.toIndoorRoute` aus dem Manifest nehmen.
* Voranschlag und Bau desselben Profils teilen sich die erzeugte Szene
  (weich gehaltener Zwischenspeicher, eine Szene) — das Ergebnis hängt davon
  nicht ab.

## Verfahren

### Höhenverlauf

1. Legs mit nicht endlicher oder nicht positiver Länge entfallen. Gesamtlänge
   200 m … 150 km, sonst `IllegalArgumentException`.
2. **Kappen:** mittlere Steigung je Leg auf ±18 % (`MAX_LEG_GRADE_PCT`);
   `FLAT`-Legs auf ±2 %. Gekappte Legs nennt `describeGeneratedProfile`.
3. **Wellen** je Art, mittelwertfrei je Leg (die Höhe am Leg-Ende stimmt):
   `FLAT` ±0,6 % (Wellenlänge ~900 m), `ROLLING` ±2,6 % (Mittelgebirge) bzw.
   ±3,2 % (Hügelland, ~650 m), `CLIMB`/`DESCENT` ±1,1 % (~420 m). An den
   Leg-Enden (120 m) laufen die Wellen aus.
4. **Übergänge** weich: Gauß über die Steigung (σ 40 m), danach aufintegriert;
   jede Stationssteigung ≤ 22 %. Starthöhe nach Landschaft (Mittelgebirge
   280–440 m, Hügelland 90–180 m), nie unter 40 m.

Test `Hoehenverlauf folgt den Legs`: in der Mitte jedes Legs weicht die
mittlere Steigung höchstens 0,8 Prozentpunkte vom Profil ab.

### Linienführung

„Schildkröte“ in 5-m-Schritten im Entwurfs-ENU:

* **Krümmung** aus zwei glatten Rauschfunktionen (Wellenlänge 700/220 m im
  Mittelgebirge, 1 100/330 m im Hügelland), gelegentlich gestreckt;
  Mindestradius 70 m (Mittelgebirge) bzw. 110 m (Hügelland), in Orten
  ruhiger.
* **Hauptrichtung**, die langsam wandert; die Linie wird zu ihr
  zurückgezogen (Zeitkonstante ~400 m): Die Strecke kommt voran, statt sich
  zu verknäueln.
* **Ausweichen:** alle 20 m Vorausschau 60–240 m; liegt dort schon Strecke
  (mehr als 220 m zurück), dreht die Linie zur freieren Seite (Radius ≥ 55 m)
  und die Hauptrichtung mit.
* **Serpentinen** an Anstiegen und Abfahrten ab 5,5 % (Mittelgebirge) bzw.
  9 % (Hügelland) und ≥ 900 m Länge, mit einer Wahrscheinlichkeit, die mit
  der Steigung wächst (Mittelgebirge 6 % → 0,31, 8 % → 0,55, ab 10 % → 0,8;
  Hügelland 0,5): 45–75 % des Legs als Folge gerader Rampen (170–420 m, 70°
  quer zur Hangrichtung) und Kehren (140°, Radius 15 m), ein- und ausgeleitet mit
  Radius 60 m. Benachbarte Rampen laufen von der Kehre aus mit 40°
  auseinander, übernächste liegen parallel ~150 m versetzt — keine
  Überschneidung.
* **Prüfung:** Kommt eine Station einer anderen Stelle näher als 45 m (bei
  mehr als 250 m Laufmeterabstand), wird mit abgeleitetem Samen neu gezogen
  (bis 16 Versuche, dann die beste). Test: 12 Samen × 30 km ohne Treffer,
  außerhalb von Serpentinen kein Radius unter 50 m.
* Die Linie wird so verschoben, dass ihr Umgebungsrechteck mittig auf dem
  Entwurfsursprung liegt — der Weltbau legt seinen Ursprung genau dorthin.

**Ort der Welt:** Entwurfsursprung 50,0° N, 12,0° W — offener Atlantik
südwestlich von Irland. Das ergibt den Sonnenstand einer mitteleuropäischen
Breite, kein Luftbild-Dienst deckt die Stelle ab, und eine aufgezeichnete
Fahrt liegt erkennbar auf keiner echten Straße.

### Gelände

`H = E + M · g(d)` (`GenTerrain`):

* **E** — Streckenhöhe ins Gelände fortgesetzt: gewichtetes Mittel über die
  Streckenstücke (alle 25 m) im Umkreis von 500 m, Gewicht `1/(d²+4)^1,5`,
  Höhe am Lotfußpunkt (keine Plateaus an Stützstellen); dazu ein grobes Mittel
  über 4 km als Hintergrund. Auf der Linie ist E genau die Streckenhöhe;
  zwischen zwei Rampen einer Serpentine entsteht der Hang von selbst.
* **M** — Mittelgebirge: Rücken (fBm, 2,6 km) bis ~220 m über dem Talgrund,
  Hügel (700 m) ±38 m; Hügelland: ±60 m (1,9 km) und ±16 m (620 m). Feines
  Rauschen (170–180 m) nur bis 1,8 km neben der Strecke. In Ortsnähe auf 30 %
  gedämpft.
* **g(d)** — 0 an der Strecke, auf 1 bei 420 m (Mittelgebirge) bzw. 320 m:
  Die Strecke liegt im Talgrund, auf Wellen oder am Hang zwischen Kehren,
  nie unter einer Steilwand, die nicht zu ihrer Steigung passt.
* **Seen** (Hügelland 1–6, Mittelgebirge 0–3): 70–250 m Radius, 120–520 m
  neben der Strecke, nur wo das Ufer höchstens 14 bzw. 20 m Höhenunterschied
  hat; Mulde mit ebenem Spiegel unter dem tiefsten Ufer.
* Abgefragt wird ein **Raster** (10 m bis 2,2 km neben der Strecke, sonst
  40 m), bikubisch (Catmull-Rom), blockweise beim ersten Bedarf gerechnet,
  rein aus den Koordinaten — unabhängig von Reihenfolge und Threads.

Test `Gelaende liegt auf der Strecke`: Abweichung < 1 m an jeder 7. Station.

### Karte

`GenMap` liefert genau die Strukturen, die der Weltbau sonst aus den
Vektorkacheln liest (`MapData`: Straßen, Gebäude, Flächen, Wasserläufe), in
Weltkoordinaten über Breite/Länge umgerechnet wie bei echten Daten:

* **Landbedeckung** aus glatten Feldern: Wald (Rauschen + Hangneigung; im
  Mittelgebirge Talwiesen entlang der Strecke, wo ein Maskenrauschen es
  will), Acker (flach, Rauschen, mehr in Ortsnähe), Wiese als Rückfall;
  Waldart nach einem Nadelholz-Feld (im Mittelgebirge mit der Höhe mehr
  Nadelwald) als `needleleaved`/`broadleaved`/`mixed`. Jede Nulllinie wird mit
  Marching Squares zu Ringen (gerade/ungerade, wie der Weltbau sie liest), in
  festen Blöcken: 256 m mit 8-m-Raster bis gut 2 km neben der Strecke, sonst
  1 024 m mit 32 m; beim ersten Bedarf gerechnet. Schläge in Äckern zeichnet
  wie bisher die Bodentextur.
* **Orte** an flachen Stellen (Legs ≤ 4 %, Stücke ≤ 5 %), 300–800 m lang,
  alle ~3,6 km (Hügelland) bzw. ~4,8 km (Mittelgebirge), höchstens 10:
  Häuser beidseits (Einzelhaus, Winkelhaus, großer Bau, Nebengebäude), 1–3
  Querstraßen mit Häusern, Wohngebiet als Fläche. In etwa jedem zweiten Ort
  ein 90–160 m langes Stück Kopfsteinpflaster.
* **Feldwege** alle 650–1 400 m (nicht in Orten, nicht an Serpentinen),
  gelegentlich ein **Hof** am Wegende (Wohnhaus, Halle, Schuppen), alle
  ≥ 3,5 km eine **Querstraße** (`minor`/`tertiary`) über die Strecke. Kein
  Weg kommt der Strecke (außer an der Einmündung), einem See oder einem
  anderen Weg zu nahe.
* **Belag** der Strecke sparsam: Landstraße Asphalt (`tertiary`, 6 m, mit
  Markierung), Kopfsteinpflaster in Ortskernen, im Mittelgebirge höchstens ein
  Waldweg (`compacted`, 600–1 100 m) in einem flachen Leg, das zu ≥ 70 %
  durch Wald führt. `IndoorRoute.source` = `BROUTER_WAY_TAGS` (der Vertrag
  kennt keine eigene Quelle für erfundene Wegmerkmale; die Testwelt hält es
  genauso).
* Ohne Luftbild: Bodentextur aus der Landbedeckung — derselbe Weg wie bei
  echten Strecken ohne Luftbild.

### Abwechslung

Zwei Landschaftstypen, gewählt über den Samen (viel Höhe je km macht
Mittelgebirge wahrscheinlicher):

* **Mittelgebirge** — bewaldete Rücken, Talwiesen, Nadelwald in der Höhe,
  kurvigere Straße, Serpentinen, seltener Seen;
* **Hügelland** — Felder und Waldinseln, sanfte Wellen, mehr Orte und Seen,
  gestrecktere Straße.

Innerhalb eines Typs ändern sich mit dem Samen Linie, Relief, Wald/Feld-
Verteilung, Orte, Seen und Wege. Test: unter 30 Samen kommen beide Typen vor;
andere Samen ergeben andere Linien und Welt-IDs.

### Einbindung in den Weltbau

Der bestehende Weltbau bleibt, wie er ist; einzige Änderung in
`core/.../indoor/world/`: `WorldBuilderImpl.buildTestWorld` ist in ein
internes `buildSynthetic(route, heightAtLatLon, isBridge, mapOf, …)`
aufgeteilt (Ursprung, Einpassen, Planung, Kachelbau — genau die Schritte, die
die Testwelt schon immer ging). Die Testwelt läuft unverändert darüber
(Test der Bytegleichheit unverändert grün). Bäume, Straßen, Häuser,
Kacheln, Detailstufen und Budgets sind dieselben wie bei echten Welten.
`RoadsLayer.kt`/`RouteLayer.kt` sind nicht angefasst, `WORLD_BUILDER_ID`
und Katalogversionen auch nicht. **Welten echter Strecken ändern sich
nicht.**

Manifest: Quellenangabe „Generierte Landschaft, keine Geodaten“
(`GeoSourceKind.VECTOR`, ohne Lizenz und URL), keine Luftbild-Angabe.

`GENERATOR_VERSION` (heute 1) geht in die Strecken-ID ein: Wer Strecke oder
Landschaft anders erzeugt, zählt hoch, damit keine alte Welt zu einem
geänderten Generator geladen wird.

## Werkzeug

```
./gradlew :indoor-dev:installDist
tool/indoor-dev/build/install/indoor-dev/bin/indoor-dev generated --profile <vorlage|datei.json|kurz> [--seed n] --out <dir> [--quality LOW|MEDIUM|HIGH] [--name text]
```

* Vorlagen: `grundlage60`, `intervalle4x8`, `erholung30`, `berg`
  (`tool/indoor-dev/src/main/kotlin/de/trailscape/tool/indoor/Generated.kt`).
* JSON: `{"seed": 1, "legs": [{"lengthM": 3000, "gradePct": 0, "kind": "FLAT"}, …]}`.
* Kurzschreibweise: `3km:f,2.4km@6:c,1.5km:f,5km@0:r` — Länge in km (oder
  `m`), `@` Steigung in %, `:f|r|c|d` für FLAT/ROLLING/CLIMB/DESCENT.

Ausgabe: Landschaftstyp, Orte, Serpentinen, Seen, Belag, Kachelzahl, Budget,
Heap, `checkWorld`. Betrachter: `node shots.mjs <welt> --cameras chase,overview`
— die Kamera `overview` (neu in `viewer.js`) schaut aus ~650 m Höhe über die
Landschaft.

## Messwerte

MESSWERTE

## Belegbilder (`docs/indoor/bilder/generiert/`)

BILDER

## Grenzen und offene Punkte

* **Kein Rundkurs.** Die Strecke ist Punkt-zu-Punkt; die Einheit endet am
  Ziel.
* **Keine Brücken, keine Bäche über die Strecke** (Bäche würden der
  Uferabsenkung wegen die Fahrbahn anschneiden). Seen liegen immer neben der
  Strecke.
* **Steigungen über 18 %** werden gekappt (die Einheit wird im ERG-Modus
  trotzdem gefahren; im Simulationsmodus fühlt sich die Rampe flacher an als
  geplant).
* **Kehren** sind so steil wie die Rampen (echte Kehren sind flacher).
* **Höhenlage:** Eine Einheit mit vielen Anstiegen und flachen Pausen
  steigt insgesamt (4 × 8 min mit 6 % ≈ +580 m) — die Welt folgt dem Profil.
* **Orte** sind Straßendörfer ohne Kirche, Platz oder Gewerbegebiet; die
  Vektorkacheln-Strukturen kennen keine Gebäudeart.
* **Nur im Betrachter geprüft** (three.js, SwiftShader), nicht auf dem
  Gerät in Filament. Bauzeit und Heap am Entwicklerrechner gemessen; auf dem
  Handy ist der Bau 2–4× langsamer zu erwarten.
* Die Bauzeit wird vom Kachelbau bestimmt (wie bei echten Welten);
  Strecke, Gelände und Karte selbst brauchen 1–2 s.
