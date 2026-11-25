# Indoor — Wasser (Flüsse, Seen, Meer)

Stand: 25.11.2026, Branch `claude/grafik-wasser`. Teil des Grafik-Schwarms
„deutlicher Schritt nach vorne“. **Alles unten ist im Filament-Referenzrenderer
(1.75.1, Desktop-GL auf llvmpipe) gesehen, nicht auf einem Gerät.** Der App-Bau
(`:app`) und `:core:test` liefen in dieser Sitzung nicht (Maven Central drosselt
mit HTTP 429); das Werkzeug (`:indoor-dev`, mit `:core`) ist gebaut.

## Bestandsaufnahme (vorher)

Gerendert an vier Stellen aus den veröffentlichten Paketen (Release
`indoor-worlds-v1`: `enztal-v2`, `bottwartal-v2`, `sa-calobra-v11`), Mittag und
Abend (Bilder unten, jeweils oben „vorher“):

- **Flache Farbfläche:** opakes, gleichmäßiges Blaugrau; bei flachem Blick fast
  überall dieselbe Farbe. Wellen kaum sichtbar, Spiegelung nur Himmelsverlauf
  (keine Wolken), Sonnenglanz nur als weicher Fleck.
- **Harte Uferkante:** die Wasserfläche (Gitter 4 m, `WaterLayer`) schneidet das
  Gelände als scharfe Linie mit sichtbaren Stufen.
- **Keine Tiefe:** keine Durchsicht, kein flacher Saum am Ufer.
- **Wellen in Welt-, nicht Nutzerkoordinaten:** `getWorldPosition()` ist in
  Filament 1.75.1 die *interne* Welt; `getUserWorldPosition()` rechnet mit der
  Matrix `userWorldFromWorld` (in der matc-Ausgabe nachgewiesen) in die Welt der
  App zurück. Verschiebt Filament seine interne Welt, wandern Wellen mit der
  Kamera. Jetzt an `getUserWorldPosition()` verankert.
- **Flimmern:** in der Ferne ruhig (die alte Dämpfung wirkte), siehe Messung unten.

### Was die Daten hergeben

- **Uferabstand: ja, grob.** `WaterLayer` schreibt den Wasseranteil der Karte
  (0 am Ufer … 1 im Wasser, bilinear zwischen Rasterzellen) in die Vertexfarbe:
  linear gespeichert sRGB `58/78/80 − 18/20/14·Anteil`, also Grün 19/255
  (Ufer) … 11/255 (Wasser). Daraus liest der Shader Uferrand und eine
  geschätzte Tiefe. Schmale Flüsse erreichen den Anteil 1 nie (Rasterzelle
  breiter als der Fluss) — der Shader nimmt die Tiefe deshalb früh voll.
- **Wassertiefe: nein.** Der Grund liegt überall 0,35 m unter dem Spiegel
  (`CarvedGround`). Für echte Tiefe müsste der Streckenbau je Vertex eine
  Tiefe in Metern liefern (z. B. `TEXCOORD_1.x` = Tiefe aus Bathymetrie oder
  geschätzt aus Abstand zum Ufer in m, `TEXCOORD_1.y` = Uferabstand in m).
- **Fließrichtung: nein.** Die Wasserflächen sind Rasterzellen ohne Bezug zu den
  Linien `waterway`. Bräuchte je Vertex einen Fließvektor (z. B.
  `TEXCOORD_1.zw` oder ein eigenes Attribut, aus der nächstgelegenen
  `waterway`-Linie und deren Gefälle). Ohne ihn ziehen die Wellen mit dem Wind.
- **Meer: kaum.** Sa Calobra hat nur 5 Kacheln der Stufe 0 mit Wasser (eine
  Bucht von etwa 320 × 230 m). Das offene Meer ist im Paket **kein Wasser**,
  sondern dunstiges Gelände (Ozeanflächen fehlen im Weltbau) — dort kann ein
  Wassermaterial nichts verbessern. Bitte an „Streckenqualität“.

## Neu (`app/src/main/materials/water.mat`)

| | vorher | jetzt |
|---|---|---|
| Wellen | zwei Lagen Wolkenrauschen, Normale aus 6 Differenz-Zugriffen | drei Lagen einer eigenen **Steigungskarte** (`WaterWaves`, 128² RGBA8, Mipmaps), je Lage gedreht (0°, 23°, 134°), krumm skaliert (19,3 m / 6,1 m / 1,93 m), eigener Zug; Steigung direkt aus der Textur, korrekt in den Tangentenraum gedreht |
| Kachelmuster | sichtbar bei Draufsicht | Drehung + krumme Maßstäbe: kein gemeinsames Raster der Lagen |
| Windflecken | — | großräumige (260 m), langsam ziehende Zonen mit stärkerer/schwächerer Kräuselung und Rauheit; in der Ferne als hellere/dunklere Streifen sichtbar |
| Spiegelung | Himmelsverlauf nach Fresnel | Verlauf **+ Sonnenhof + Wolken** in Richtung des gespiegelten Blicks, Formeln und Werte wie `sky.mat` (`SkyShading.sky`) — passt zu Tageszeit und Bewölkung |
| flacher Blick | dunkle Uferfarbe aus dem Horizont | **Uferkulisse**: gezackte Silhouette (Rauschen über die Himmelsrichtung, nahtlos), unten dunkler; Farbe `bankColor` aus Umgebungs- und Sonnenlicht der Stimmung (abends nicht mehr orange-braun); bei hoher Kamera (> 12–60 m über dem Wasser) Himmel statt Ufer |
| Sonnenglitzern | GGX, Rauheit 0,06 | GGX auf der Wellen-Normale, Rauheit 0,045 → 0,16 mit dem Abstand (Sonnenstraße statt Pixelglitzern), Filaments Specular-Anti-Aliasing |
| Tiefe / Ufer | opak, harte Kante | `transparent` (vormultipliert): Durchsicht auf den Grund nach Blickwinkel (`clarity`^Weg), Streuung flach→tief (`shallowColor` → `deepColor`), **weicher Uferrand** aus dem Wasseranteil |
| Ferne | Wellen gedämpft | Mipmaps mitteln die Steigung weg, jede Lage klingt mit eigenem Abstand ab, Rauheit steigt |

Parameter (`core/…/indoor/water/WaterShading`): Namen exakt wie im Material
(Test gegen die .mat-Datei). `SkyShading.water` delegiert dorthin, damit
`indoor-dev sky` dieselben Werte in `sky.json` schreibt.

### Sicherheit (Issue 106)

- `FilamentCore` setzt nur Parameter, die das geladene Material deklariert
  (`WaterSurface.declared` → `Material.hasParameter`, Java-API 1.75.1 geprüft).
- Die Wellenkarte wird gebunden, sobald das Material `waveNormals` deklariert,
  **bevor** die Instanz an ein Renderable geht; scheitert ihr Bau, scheitert das
  Wassermaterial als Ganzes und die Kacheln behalten ihr Material (wie bisher).
- `requires : [ color ]`: alle Wasser-Primitive haben `COLOR_0`. Fehlt sie
  doch, warnt Filament nur und liefert (0,0,0,1) → Shader nimmt „offenes Wasser“.
- Shader konservativ (GLES 3.0): `texture()` nur in gleichförmigem
  Kontrollfluss (Verzweigung nur auf das Uniform `detail`), Koordinaten und
  Uhr `highp` (mediump zerreißt die Wellen nach Minuten), keine Schleifen.
  Mit `matc -p all` (mobile + desktop) kompiliert; gelinkt nur auf Desktop-GL.

### Abschalten

- Schalter „Himmel“ (`RenderSettings.sky`): wie bisher Material der Kachel.
- Stufe LOW (`groundDetail = false`): `detail 0` → eine Wellenlage, keine
  gespiegelten Wolken.
- Sicherer Modus (`RenderGuard`): das Wassermaterial wird gar nicht geladen
  (unverändert).

## Kosten (Abschätzung, nicht gemessen)

- Je Wasserpixel: 7 Texturzugriffe (vorher 6), auf LOW 3; ein `exp`, vier
  `pow`, Specular-AA (Ableitungen). Nur auf der Wasserfläche.
- **Neu durch die Transparenz:** der abgesenkte Grund unter dem Wasser wird
  jetzt immer schattiert (vorher konnte opakes Wasser ihn verdecken) und das
  Wasser darübergeblendet — einmal Geländematerial mehr auf der Wasserfläche.
  Wasser schreibt Tiefe (matc-Standard), Draw Calls unverändert.
- Einmalig: Wellenkarte 64 KB (+ Mipmaps ≈ 85 KB). Die Pixel rechnet ein
  Hintergrund-Thread beim Setzen des Himmels (Start der Fahrt) vor — gemessen
  23 ms warm auf dem Desktop-JVM, auf dem Gerät kalt vermutlich 50–150 ms, also
  nicht im Hauptthread. `water.filamat` 204 KB (vorher 206 KB).

## Messung Flimmern (Referenzrenderer)

Zwei Bilder mit 0,25 m Kameraversatz (ein Bild bei 15 m/s und 60 Hz) bzw.
+1/60 s, mittlere Helligkeitsabweichung im Ausschnitt:

| Stelle | vorher | nachher |
|---|---|---|
| See, ferne Wasserfläche (Kamera) | 2,98 | 3,48 |
| Fluss gegen Abendsonne, ferne Wasserzeile (Kamera) | 4,40 | 4,64 |
| Fluss gegen Abendsonne, nur Zeit (+1/60 s) | 0,14 | 0,53 |

In der Ferne also kein zusätzliches Flimmern. Im Nahbereich ändert sich bei
Bewegung mehr (5,0 → 11,3), weil dort jetzt kontrastreiche Wellen liegen —
das ist Parallaxe der Wellen, kein Pixelrauschen; auf dem Gerät prüfen.
Animation: nur als Einzelbilder bei zwei Zeitpunkten geprüft, **nicht als
Bewegung angesehen**.

## Bilder (`docs/indoor/bilder/wasser/`, oben vorher, unten nachher)

Referenzrenderer, 1280 × 720 (auf 960 verkleinert), Mittag (`NOON`) und Abend
(`EVENING`), gleiche Kamera je Stelle. Vorher = Material und Parameter von
`main` (4260ba5). Reproduzierbar mit `tool/indoor-dev/filament/wasser.sh` und
`vergleich.py`.

- `fluss-<Zeit>-07250.jpg` — Enz neben der Strecke, Blick gegen die Abendsonne
- `bruecke-<Zeit>-05060.jpg` — Brücke über die Enz
- `see-<Zeit>-19085.jpg` — See im Bottwartal vom Ufer
- `kueste-<Zeit>-00130.jpg` — Bucht von Sa Calobra von der Straße (kaum
  Unterschied: siehe „Meer: kaum“)
- `fahrt-<Zeit>-10560.jpg` — Verfolgerkamera am Fluss, wie beim Fahren

## Prüfliste Gerät (Galaxy S25)

- [ ] Fahrt Enztal und Bottwartal: Logcat ohne Filament-Fehler/Warnungen zum
      Wassermaterial („missing required attributes“, ungebundener Sampler).
- [ ] Wellen bewegen sich ruhig, kein Wandern mit der Kamera, kein Kachelmuster
      in Draufsicht (Brücke).
- [ ] Ferne Wasserflächen ruhig, kein Funkeln im Sonnenglitzer (Abend, Blick
      gegen die Sonne); sonst `specularAntiAliasingVariance` erhöhen.
- [ ] Ufer: weicher Saum statt Linie, keine sichtbaren 4-m-Stufen; Grund unter
      flachem Wasser nicht „wie Strand“ zu hell.
- [ ] Nach 2 h Fahrt (Uhr springt bei 7200 s) kein sichtbarer Sprung außer
      einmal kurz; Wellen nicht zerrissen (mediump).
- [ ] Bildrate MEDIUM an Wasserstellen wie vorher (±1 fps).
- [ ] Schalter „Himmel“ aus → altes Kachelmaterial; LOW → ruhigeres Wasser.

## Offen

- Echte Spiegelbilder (Bäume, Brücken, Häuser) gibt es nicht — nur die
  angedeutete Uferkulisse. Planare Spiegelung wäre ein zweiter Szenendurchlauf
  (Kosten etwa ×2 an Wasserstellen) und gehört nicht ins Material.
- Tiefe und Fließrichtung brauchen Felder aus dem Streckenbau (oben).
- Meer/Ozean fehlt im Weltbau (Sa Calobra).
- Schaum an Wehren/Küste nicht umgesetzt (keine Klasse/Strömung in den Daten;
  Kanal `B` der Wellenkarte enthält die Höhe dafür).
