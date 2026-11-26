# Indoor — Slice indoor-render (3D-Darstellung mit Filament)

Stand: 23.11.2026, Branch `claude/indoor-render`. Vertrag: `docs/indoor.md`
(vor allem 7.6–7.9), Schnittstelle `core/…/indoor/world/WorldRenderer.kt`.
Filament **1.75.1** (so steht es in `app/build.gradle.kts`; die Aufgabe nannte
1.81.0 — maßgeblich ist der Build).

**Wichtig:** In der Entwicklungsumgebung gibt es weder GPU noch Emulator. Die
Darstellung ist kompiliert, gegen die Filament-Quellen der Version 1.75.1
geprüft und in allen GPU-freien Teilen getestet — **gesehen hat sie noch
niemand.** Deshalb ist jeder Filament-Aufruf abgesichert und fällt auf eine
2D-Ersatzansicht zurück (siehe „Ausfall“).

## Was gebaut ist

Alles unter `app/src/main/kotlin/de/trailscape/app/indoor/render/`:

| Datei | Inhalt | GPU nötig |
|---|---|---|
| `IndoorRenderEntry.kt` | die drei Einstiege (`createWorldRenderer`, `WorldSurface`, `IndoorDiagnosticsScreen`) | – |
| `FilamentWorldRenderer.kt` | `WorldRenderer`: Zustände, Lebenszyklus, Bildschleife (Choreographer), Ausfall | ja |
| `FilamentCore.kt` | Engine, Renderer, Szene, View, Kamera, Sonne, Umgebungslicht, Himmel, gltfio-Lader | ja |
| `TileStreamer.kt` | Kacheln lesen (E/A-Thread), `asyncBeginLoad`/`asyncUpdateLoad`, entladen, Teilflächen | ja |
| `FilamentAssets.kt` | geladenes Asset mit Knotenarten (Schalter, Schatten), Fahrer/Geist mit Animationen | ja |
| `WorldSurfaceView.kt` | `SurfaceView` in Compose, Lebenszyklus, 2D-Ersatzansicht (Höhenprofil + Hinweis) | – |
| `DiagnosticsScreen.kt` | Diagnoseseite | – |
| `GlProbe.kt` | GL-Hersteller/-Renderer/-Version über einen kurzen eigenen EGL-Kontext | ja |
| `RenderMath.kt` | ENU→glTF, Fahrtrichtung, Modellmatrix, Falten/Verlängern des Bands | nein, getestet |
| `TilePlanner.kt` | welche Kacheln laden/entladen (nach `WorldTile.show`, Hysterese, Runden), Teilflächen-Regel | nein, getestet |
| `MotionSmoother.kt` | Fortschreiben zwischen `setState`, Ausgleich, Sprung, Kurbel- und Radphase | nein, getestet |
| `CameraRig.kt` | CHASE / HANDLEBAR / DRONE, Dämpfung, nie unter der Fahrbahn | nein, getestet |
| `SkyModel.kt` | Anbindung von `core/…/indoor/sky` (Licht, Himmel, Dunst): Richtung im glTF-Raum, SH-Koeffizienten, HDR-Cubemap | nein, getestet |
| `GlbWriter.kt`, `MeshBuilder.kt` | kleiner GLB-Schreiber und Grundkörper | nein, getestet |
| `AvatarModel.kt` | eingebauter Fahrer (Rad + Fahrer, Animationen `pedal`/`roll`, Beine per Zwei-Gelenk-Rechnung) | nein, getestet |
| `TestSceneModel.kt` | Testszene ohne Dateien (geneigte Wiese, Ringstraße, Bäume, Häuser) und ihr `WorldTrack` | nein, getestet |
| `RenderSupport.kt` | Bildrate/P95, Fehlereinordnung, Manifestprüfung, Knotenarten, Spurversatz | nein, getestet |

### Ablauf

- `createWorldRenderer(context)` ist billig und berührt **nichts Natives**.
  `loadWorld`/`loadTestScene`/`setSettings`/… merken sich alles.
- `WorldSurface` hängt eine `SurfaceView` an (`UiHelper`, `ContextErrorPolicy.DONT_CHECK`).
  Beim ersten Anhängen: `GlProbe` → `Gltfio.init()` (lädt `filament-jni`, dann
  `gltfio-jni`) → `Engine.create(Backend.OPENGL)` → Szene, Licht, Himmel →
  Modelle → Manifest → Kacheln → `onReady`. Jede Stufe hat einen Namen
  (`RenderStage`), der im `detail` von `onFailure` steht.
- **Threads:** Alles, was Filament berührt, läuft auf dem Hauptthread (Engine,
  Swapchain, Assets, Choreographer-Schleife, Zerstören). `setState` legt nur in
  eine `AtomicReference`. Kachel-Dateien liest ein eigener E/A-Thread
  (`trailscape-render-io`) in direkte Puffer. Rückrufe an den Listener gehen
  immer per `Handler.post` auf den Hauptthread — nie mitten in einen Aufruf.
- **Lebenszyklus:** `ON_STOP`/`ON_START` der Seite (nicht PAUSE/RESUME: im
  Mehrfenster-Modus oder unter einem Systemdialog bleibt das Bild lebendig) und `pause()`/`resume()`
  halten die Schleife an. Verliert die `SurfaceView` ihre Fläche, wird nur die
  Swapchain freigegeben (mit `flushAndWait`); Engine, Szene und Kacheln
  bleiben. Drehung = neue `SurfaceView`, dieselbe Engine (der Renderer lebt im
  ViewModel). `destroy()` gibt alles frei.
- **Kacheln:** nach `TilePlanner` (Stelle zuerst, dann 200 m Vorlauf, feine
  Stufe zuerst; Entladen erst außerhalb ±60 m Hysterese). Höchstens eine neue
  Kachel je Bild beginnen, immer nur eine im asynchronen Laden (ein
  `ResourceLoader` führt eine Ladung), höchstens zwei Dateien gleichzeitig
  lesen. Wird eine Ladung abgebrochen (Entladen, Zeitüberschreitung, neue
  Welt), ersetzt die Darstellung den `ResourceLoader` durch einen neuen:
  gltfio 1.75.1 zählt abgebrochene Texturen nicht mit (`StbProvider::cancelDecoding`),
  danach erreichte `asyncGetLoadProgress` nie wieder 100 %. Fertige Kacheln hängen unter einem Weltknoten an ihrem Anker
  `(anchorE, anchorU, −anchorN)`. Teilflächen `terrain_sub_i_j` werden über die
  Ebenenmaske ausgeblendet, solange die feinere Kachel fertig ist; beim
  Entladen kommen sie im selben Bild zurück. Zeitüberschreitung (45 s) oder
  Lesefehler → `onTileFailed`, die Fahrt geht weiter.
- **Schwebender Ursprung:** Der Weltknoten wird gegen die Kamera verschoben
  (Raster 256 m, neu ab 2 km Abstand); Kamera und Fahrer stehen nahe am
  Ursprung.
- **Bewegung:** `MotionSmoother` nach Vertrag (Fortschreiben ≤ 500 ms, Ausgleich
  τ = 300 ms, Sprung > 30 m). Kommt länger als 500 ms nichts, bleibt der
  Fahrer stehen (nicht weiterrollen). Seitlicher Versatz: rechts der Mitte,
  `min(0,22·Breite, Breite/2 − 0,4 m, 1,2 m)`, Geist spiegelbildlich links;
  bei Breitenwechsel weich (τ = 1 s).
- **Fahrer und Geist:** zuerst `assets/indoor/models/rider.glb`/`ghost.glb`
  aus dem Weltbau; fehlen sie oder lassen sie sich nicht laden, der eingebaute
  Fahrer (im Code erzeugte GLB nach derselben Vereinbarung). Animationen per
  Name (`pedal`, `roll`) über `Animator.applyAnimation(i, Phase × Dauer)`.
- **Licht:** Sonne (`LightManager.Type.SUN`) aus Tageszeit, Tag und Breite des
  Ursprungs, unter 8° auf 8° angehoben; Schatten mit 3 Kaskaden, 2048², DPCF, bis
  300 m. Umgebungslicht über `IndirectLight.Builder().radiance(3, sh)` mit
  selbst integrierten SH-Koeffizienten (Basis exakt wie in Filaments
  `IndirectLight.cpp`, geprüft per „weißer Ofen“-Test), Stärke 26 000 lx bei
  tiefer bis 34 000 lx bei hoher Sonne. Belichtung f/16,
  1/125 s, ISO 100.
- **Himmel:** Cubemap 256² × 6 als `R11F_G11F_B10F` (HDR, Rückfall
  `SRGB8_A8`), Verlauf und weiche Wolken, **symmetrisch zur Waagrechten**
  (siehe Annahmen); die Sonnenscheibe zeichnet Filament
  (`Skybox.showSun(true)`). Mit `sky = false` eine einfarbige Skybox in
  Horizontfarbe. Einzelheiten unten unter „Himmel, Wolken, Tageszeit“.
- **Dunst:** `View.fogOptions` mit Dichte aus `haze` (Sichtweite 30 km … 3 km,
  Koschmieder), mindestens aber so dicht, dass am Rand der Welt
  (`IndoorQuality.viewDistanceM`) rund 90 % Dunst liegen; Farbe = sichtbarer
  Horizont, `cutOffDistance` 100 km (Skybox ohne Dunst), Höhe relativ zum
  Fahrer, `heightFalloff` 0,0012/m.
- **Kameras:** CHASE 6 m hinter (auf dem Band zurück, folgt Kurven) und 2,5 m
  über dem höheren von Fahrer und Kamerapunkt, τ = 0,35 s; HANDLEBAR auf
  1,45 m Augenhöhe, Blick 25 m voraus; DRONE 28 m hoch, 22 m seitlich, 18 m
  zurück, τ = 0,9 s. Mindesthöhe über dem Band; Moduswechsel springt.
  Bildwinkel senkrecht 50° (quer) bzw. 72° (hoch), nah 0,3 m, fern 25 km.
- **Qualitätsstufen:** `RenderSettings` wirkt sofort: Schatten (View +
  Sonne), Umgebungsverdeckung, Überstrahlen, Dunst, Kantenglättung (4× MSAA, sonst FXAA), dynamische
  Auflösung (0,6–1,0), Nachbearbeitung, Himmelsverlauf, Bäume/Gebäude/
  Kleinteile (Ebenenmaske je Renderable), `resolutionScale` (feste kleinere
  Puffergröße der `SurfaceView`), `maxFps` 30 (Bilder auslassen +
  `FrameRateOptions.interval` in Einheiten der echten Bildwiederholrate, die als
  `Renderer.displayInfo` gesetzt wird; die Obergrenze gilt auch auf
  90/120-Hz-Anzeigen). Ohne `setSettings` gilt die Voreinstellung
  der Qualität, mit der die Welt gebaut ist (sonst MEDIUM).
- **Statistik:** `onStats` etwa einmal je Sekunde (Bildrate, P95 der
  Bildzeit, Kacheln, Texturspeicher und Dreiecke laut Manifest).

### Ausfall

Jeder Fehler (auch `UnsatisfiedLinkError`, `OutOfMemoryError`) wird
gefangen, eingeordnet (`classifyFailure`) und einmal als `onFailure`
gemeldet; danach baut die Darstellung ihre Filament-Teile ab und
`WorldSurface` zeigt eine schlichte **2D-Ersatzansicht**: Titel „Keine
3D-Ansicht“, ein Satz je Ausfallart, Höhenprofil des Bands mit der Stelle des
Fahrers. Die Fahrt (HUD) läuft weiter. Ein Listener, der sich nach dem Ausfall
anmeldet, bekommt ihn nachgereicht. Kommt bei vorhandener Fläche 8 s lang
kein Bild durch, gilt das als `NO_GRAPHICS` — kein stilles Schwarz.

Unter Robolectric geschieht genau das (keine native Bibliothek): Test
`RendererWithoutGraphicsTest`.

### Diagnoseseite

Vorschau (260 dp), Status, Testszene, Testwelt (über
`createWorldBuilder().buildTestWorld` im Cache-Verzeichnis, seit der
Integration der echte Weltbau), Probefahrt ohne
Trainer (8 m/s, 85 U/min, mit Geist), Kamera, Tageszeit, Messwerte, Gerät und
Treiber, Voreinstellungen LOW/MEDIUM/HIGH/SAFE, jeder Schalter einzeln,
30 fps, Auflösung, Ausfalldetails für Fehlerberichte, „Neu starten“ nach
einem Ausfall.

## Bildqualität (24.11.2026, Branch `claude/indoor-quality`)

Nach der Rückmeldung „ruppig und kantig“ (siehe `docs/indoor/world.md`,
„Sichtbare Qualität“). Geändert in `FilamentCore.kt`, alles gegen
`javap` der AARs 1.75.1 geprüft und einzeln abgesichert:

| Was | vorher | jetzt | Rückfall |
|---|---|---|---|
| Kantenglättung | FXAA | **4× MSAA** (`multiSampleAntiAliasingOptions`, `sampleCount = 4`); FXAA nur, wenn MSAA nicht gesetzt werden kann | FXAA |
| Schattenart | PCF (Filament-Standard, harte Treppen) | **PCF** (`setShadowType`). DPCF ist in Filament 1.75.1 veraltet und fällt auf PCSS zurück; damit erschien auf dem Gerät kein Schatten | PCF |
| Schattenkarten | 1024², Standard-Kaskadengrenzen | **2048²**, `cascadeSplitPositions = [0,05, 0,2]` (≈ 15 m / 60 m bei 300 m Reichweite) | – (Builder-Werte) |

`RenderSettings.antiAliasing = false` schaltet MSAA und FXAA ab; ohne
Schatten wird die Schattenart auf PCF zurückgesetzt. Ein Fehler in diesen
Aufrufen kostet nur die jeweilige Glättung — nie die Darstellung.

**Anisotrope Filterung geht nicht:** gltfio 1.75.1 übernimmt nur Wrap- und
Min/Mag-Filter aus dem glTF-Sampler (`GltfEnums.h`), und die Java-Schnittstelle
gibt die Texturen eines Assets nicht heraus (`FilamentAsset`/`FilamentInstance`
nur `MaterialInstance[]`, `MaterialInstance.setParameter` braucht die
`Texture`). Dafür bräuchte es eigenes Texturladen. Der Betrachter rendert
deshalb jetzt ebenfalls ohne (`aniso=1`), damit seine Bilder nicht schöner
sind als das Gerät.

**Flackern (Rückmeldung vom Gerät):** Die Ursachen lagen fast alle in der
Geometrie (koplanare Flächen, siehe `docs/indoor/world.md`, Nr. 14) und sind
dort behoben. In der Darstellung: Kamera `NEAR_M` 0,5 statt 0,3 m (bessere
Tiefenauflösung in der Ferne; die Lenkerkamera sitzt 1,45 m hoch). Kacheln
und Teilflächen wechseln weiterhin mit ±60 m Hysterese (`TilePlanner`),
kein Hin- und Herspringen gesehen. Texturen haben Mipmaps (glTF-Sampler
`LINEAR_MIPMAP_LINEAR`, gltfio erzeugt sie); dünne Geometrie (Zäune,
Markierungen) glättet jetzt 4× MSAA. **Nur im Betrachter und geometrisch
geprüft, nicht in Filament.**

Der Betrachter (`tool/indoor-dev/viewer`) bildet nach: Kameras wie
`CameraRig` (Verfolger 6 m auf dem Band zurück), weiche Schatten (PCF-Radius 2),
MSAA, ohne Anisotropie.

Auf dem Gerät zu prüfen: Bildrate MEDIUM mit MSAA + DPCF; Speicher der
Schattenkarten; ob DPCF auf dem Treiber des Gründers sauber aussieht (sonst
PCF zurück, eine Zeile).

## Himmel, Wolken, Tageszeit (24.11.2026, Branch `claude/indoor-himmel`)

**Befund auf dem Gerät** (Bilder auf `claude/trusting-heisenberg-mbokzx`,
`docs/indoor/bilder/geraet/`): ein fast einheitliches, flaches Grau. Die
Cubemap kam an (der Verlauf ist da), aber ihre Farben waren zu blass:
Zenit ≈ (0,2/0,38/0,76), Horizont ≈ (0,66/0,76/0,88), dazu 30 % Dunst zum
Grau hin. Filaments Standard-Abbildung der `View` ist `ACES_LEGACY` (ACES
mit Helligkeit 1/0,6, `ColorGrading.cpp`/`ToneMapper.cpp` v1.75.1) — sie
bleicht helle, wenig gesättigte Farben fast weiß. Nachgerechnet: Horizont
→ sRGB (208, 213, 218), gemessen auf dem Foto (182, 195, 210); in 25° Höhe
(175, 193, 214), gemessen unter dem HUD-Schleier umgerechnet (166, 184, 205).
Das Dunklere oben auf dem Foto ist der Schleier des HUD (`HudColors.topScrim`,
62 % Schwarz), nicht der Himmel. Eine Sonnenscheibe war nicht zu sehen,
weil die Sonne um 15:30 im Juni 45° hoch steht — im Hochformat reicht das
Bild nur bis rund 25° über den Horizont.

**Gebaut:**

- **Reine Logik in `core/…/indoor/sky`** (getestet): `IndoorDaylight`
  (Sonnenstand, wahre Sonnenzeit aus Weltzeit + Länge + Zeitgleichung, feste
  Tageszeiten, helle Dämmerung), `DaylightModel` (Sonne, Umgebungslicht,
  Dunst, sichtbarer Himmel je Sonnenhöhe), `SkyLookModel` (Farben),
  `SkyClouds` (Wolkenrauschen, Cubemap als Halbfloats, Rundumbild für den
  Betrachter). `SkyModel` in `:app` reicht nur noch weiter.
- **Farben gegen `ACES_LEGACY` ausgesucht:** Zielwerte nach der Abbildung
  (sRGB) Zenit 52/112/204 (hohe Sonne) bis 78/118/190 (8°), Horizont
  187/208/234 bis 222/205/189 (warm bei tiefer Sonne); daraus die
  szenenlinearen Werte per Umkehrung einer Nachbildung der Abbildung
  (`FilamentAces` in `tool/indoor-dev/…/Sky.kt`, gleiche Formeln wie
  `ToneMapper.cpp`). Der helle Horizontstreifen geht in den ersten rund 10°
  ins Blau über (`1 − exp(−y / 0,16)`), weil man im Hochformat nur 0–25°
  sieht.
- **HDR-Cubemap:** Der Horizont braucht Blau ≈ 1,6 (nach Teilen durch
  `ambientLux × Belichtung`), Wolkenränder bis rund 2 — in `SRGB8_A8` würde
  das bei 1 abgeschnitten. Deshalb `R11F_G11F_B10F` (in OpenGL ES 3.0
  filterbar), hochgeladen als `RGB`/`HALF`; vorher
  `Texture.isTextureFormatSupported`, sonst dieselben Farben abgeschnitten in
  `SRGB8_A8`. 256² je Fläche ≈ 1,5 MB Grafikspeicher (vorher 64², 96 kB).
- **Weiche Wolken, prozedural:** Wertrauschen (5 Lagen, leicht verwirbelt)
  auf einer gedachten Wolkenebene, Richtung `d` trifft sie bei
  `(d.x, d.z) / (|d.y| + 0,18)` — zum Horizont nur mäßig gestaucht, damit
  die Wolken dort, wo man hinsieht, nicht in Streifen zerfallen. Unter 1°
  keine Wolken, ab 7° volle (reiner Horizont = Dunstfarbe). Bedeckung
  30 % + 15 % × Dunst. Dicke Kerne grau, Ränder in der Sonnenfarbe. Weil
  alles nur von `|y|` abhängt, bleibt es richtig, falls ein Treiber die
  Seitenflächen spiegelt (dann ist nur das Muster gespiegelt). Kein Bild aus
  fremder Quelle. Das Rauschen (≈ 230 000 Texel, untere Hälften gespiegelt)
  rechnet ein Hintergrund-Thread beim `loadWorld` vor (Desktop-JVM rund
  0,1 s, auf dem Handy geschätzt 0,3–0,5 s); das Einfärben je Tageszeit ist
  billig.
- **Dunst:** Farbe = sichtbarer Horizont (geteilt durch dieselbe Skala wie
  die Skybox), mindestens 90 % am Rand der Sichtweite der Welt (LOW 6 km,
  MEDIUM 12 km, HIGH 20 km) — die Welt hört nicht mehr sichtbar auf.
  Skybox und Sonnenscheibe bekommen keinen Dunst (`cutOffDistance`, die
  Skybox liegt bei Filaments unendlich fernem Rendern auf Abstand ∞);
  Bäume mit `MASK` werden wie jede Fläche gedunstet, die Maske bleibt.
- **Umgebungslicht getrennt vom sichtbaren Himmel:** Die SH-Koeffizienten
  kommen weiter aus dem alten, ruhigen Verlauf — die Welt ist damit
  abgestimmt, und der kräftig blaue Himmel soll sie nicht blau färben. Die
  Sonnenfarbe bei tiefer Sonne ist etwas wärmer (1/0,76/0,52 statt
  1/0,80/0,58).
- **Tageszeit:** `IndoorTimeOfDay` AUTO / MORNING / NOON / EVENING.
  Automatisch: wahre Sonnenzeit am Ursprung der Welt; steht die Sonne
  tiefer als 8°, der nächste Zeitpunkt, an dem sie 8° hoch steht (abends
  die Abend-, nach Mitternacht die Morgendämmerung) — nie dunkel; im
  Polarwinter Mittag. Fest: Tag 165, Morgen 16° steigend (Osten), Mittag
  12:45 Sonnenzeit, Abend 11° fallend (Westen). Auswahl in den
  Indoor-Einstellungen (siehe `docs/indoor/app.md`).

**Bilder** (Betrachter mit dem Himmel der Darstellung, `shots.mjs --sky`;
die Welt rendert dort three.js, der Himmel geht durch die nachgebaute
`ACES_LEGACY`): `docs/indoor/bilder/himmel/01-morgen.jpg`,
`02-mittag.jpg`, `03-abend.jpg`, `04-mittag-wolken.jpg`,
`05-abend-wald.jpg`; das Rundumbild je Tageszeit `rundum-*.jpg`; die Auswahl im Schritt Koppeln `06-auswahl-tageszeit.jpg`.

**Leistung (geschätzt, nicht gemessen):** Je Bild nichts Neues — die
Skybox war schon da (ein Texturzugriff je Himmelspixel; jetzt aus einer
größeren, 4 Byte je Texel großen Textur), der Dunst auch. Keine weiteren
Durchgänge, keine volumetrischen Wolken. Einmalig: Rauschen im Hintergrund,
Einfärben und Hochladen von 1,5 MB bei jedem `setSky` (beim Start und bei
„Automatisch“ alle 10 Minuten).

**Nur auf dem Gerät zu sehen / prüfen:**

- [ ] Himmel blau mit hellem Horizont und Wolken (nicht grau), oben
      unter dem HUD-Schleier dunkler — das ist der Schleier.
- [ ] Abend/Morgen: Sonnenscheibe mit Hof sichtbar, wenn die Strecke zur
      Sonne führt; warmes, flaches Licht, lange Schatten.
- [ ] Horizont ohne harte Kante: entfernte Hügel und Wald gehen in den
      Dunst über; kein heller oder dunkler Streifen zwischen Gelände und
      Himmel.
- [ ] Bäume (Alpha-Maske) im Dunst ohne Säume; Sonnenscheibe nicht gedunstet.
- [ ] Bildrate wie vorher (MEDIUM, Hochformat und quer).
- [ ] Logcat: kein GL-Fehler beim Hochladen der Cubemap (sonst greift der
      Rückfall `SRGB8_A8` — Himmel blasser, aber da).
- [ ] Spiegelt der Treiber die Cubemap-Flächen, ändert sich nur das
      Wolkenmuster (oben/unten bleibt richtig).

## Licht, Himmel, Wasser, Lenkerperspektive (24.11.2026, Branch `claude/indoor-licht-kamera`)

**Befund des Gründers** (Handy, Katalogstrecke „solitude“, abends 18:19 Uhr):
trüb, dunkel, blaustichig, Wiese und Weg grau-grün, Himmel flach violett;
tagsüber helle Felder blass/weiß; die Lenkerkamera „sieht noch komisch aus“.

### Neues Werkzeug: Filament-Referenzrenderer

Bisher hat niemand die Darstellung gesehen außer auf dem Gerät; der
three.js-Betrachter ist nur eine Näherung (anderes Licht, andere Abbildung).
Deshalb rendert jetzt **echtes Filament 1.75.1** (dieselbe Version wie die
App, Desktop-Bibliotheken des Releases) ohne GPU auf Mesa llvmpipe unter Xvfb:

- `tool/indoor-dev/filament/filarender.cpp` (+ `build.sh`): Engine, Sonne
  mit 3 Kaskaden/DPCF, Umgebungslicht aus SH, Cubemap bzw. Himmelsmaterial,
  Dunst, Farbweg, MSAA, Wasser, Fahrer, Cockpit — Einstellungen wie
  `FilamentCore`.
- `tool/indoor-dev/viewer/filashots.mjs`: rechnet die Szene wie die App
  (`TilePlanner`-Kacheln samt Teilflächen-Regel, `CameraRig`, `modelMatrix`;
  Kameras in `camerarig.mjs`) und ruft den Renderer.
- `indoor-dev sky` schreibt dafür zusätzlich die exakte Cubemap
  (`cube.bin`), die SH-Koeffizienten, die Wolkentextur (`clouds.bin`) und die
  Stimmung (`look`: EV, Farbweg, Dunst-Einstreuung, Parameter der Materialien
  aus `SkyShading`).
- `licht-kamera.sh` rendert den Bildsatz, `vergleich.py` setzt Vorher/Nachher
  nebeneinander, `materials.sh` kompiliert die Materialien mit matc 1.75.1.

Der Renderer bestätigt den Befund genau (Vorher-Bilder unten). Er ist
Desktop-GL statt OpenGL ES; Shader, Abbildung, Licht und Dunst sind dieselben,
Genauigkeit (mediump) und Leistung nicht. **Alles unten ist im
Referenzrenderer gesehen, nicht auf dem Gerät.**

### Licht und Farbe (`core/…/indoor/sky`)

Ursachen des trüben, blauen Bilds (nachgerechnet):

1. **Feste Belichtung EV 15** (Sonnige-16-Regel) auch in der Dämmerung. Bei
   8° Sonne trifft auf eine waagrechte Fläche nur ein Siebtel des Sonnenlichts;
   das Bild war rund **zwei Blenden** unterbelichtet.
2. **Blaues Umgebungslicht:** Der Himmelsverlauf für die SH-Koeffizienten
   hatte oben 0,16/0,34/0,78 und einen dunklen Boden — bei tiefer Sonne
   beleuchtete fast nur noch er die Welt: Wiese und Weg graublau, Schatten
   schwarzblau.
3. **`ACES_LEGACY`** (Filaments Standard) bleicht helle, wenig gesättigte
   Flächen weiß und entsättigt — die blassen Felder am Mittag.

Neu (`DaylightModel`, `ToneGrade`, `SkyLook`, `SkyShading`):

| | vorher | jetzt |
|---|---|---|
| Belichtung | immer EV 15 | gemessen wie ein Handbelichtungsmesser: Leuchtdichte einer weißen, waagrechten Fläche aus Sonne und Umgebungslicht → 1,05 nach der Belichtung; Dämmerung (8°) EV ≈ 13,8, Morgen (16°) ≈ 14,1, Mittag ≈ 15,2. `FilamentCore` setzt die Verschlusszeit (Blende 16, ISO 100) bei jedem `setSky` |
| Abbildung | `ACES_LEGACY` | `PBR_NEUTRAL` (Khronos) über `ColorGrading`: Farben bis ≈ 0,76 bleiben, darüber weich zu Weiß; Kontrast 1,06, Lebendigkeit 1,17 |
| Umgebungslicht | Zenit 0,16/0,34/0,78, Boden 0,20/0,22/0,14 | tief: Zenit 0,50/0,48/0,56, Horizont 1,00/0,80/0,62, Boden 0,36/0,30/0,20; hoch: Zenit 0,40/0,49/0,68. Stärke unverändert 26 000–34 000 lx (Waldweg-Bitte) |
| Sonnenfarbe tief | 1/0,76/0,52 | 1/0,70/0,44 (goldene Stunde) |
| Himmelsfarben | gegen `ACES_LEGACY` ausgesucht | als **Zielfarben auf dem Bildschirm** (sRGB) angegeben und über eine Nachbildung von Filaments Farbweg (`ToneGrade`: Rec.2020, LogC-Kontrast, Lebendigkeit, PBR Neutral) zurückgerechnet — bleiben richtig, wenn die Abbildung sich ändert |

Die Rückrechnung ist eine kanalweise Bisektion (ein multiplikatives
Verfahren blieb bei fast schwarzem Rot hängen — der Zenit wurde lavendel);
geprüft in `LightAndShadingTest`. Nachts gibt es weiterhin kein Dunkel:
„Automatisch“ springt unter 8° Sonne in die nächste Dämmerung (unverändert);
mit der gemessenen Belichtung ist diese jetzt hell genug zum Fahren.

### Dunst / Luftperspektive

Dichte wie bisher (Sichtweite aus `haze`, mindestens 90 % am Rand der Welt —
deckt Kachelgrenzen und das Einblenden der groben Stufen). Neu: **Einstreuung
der Sonne** (`FogOptions.inScatteringStart` 300 m, `inScatteringSize` 18 bei
tiefer bis 48 bei hoher Sonne): gegen die tiefe Sonne ein warmer Schein in der
Ferne, nicht auf dem Weg. Das Himmelsmaterial rechnet am Horizont denselben
Schein (gleiche Formel wie `surface_fog.fs`, Sonnenfarbe aus `frameUniforms`),
die Horizontfarbe ist exakt Dunstfarbe × `iblLuminance` — Gelände und Himmel
stoßen ohne Kante aneinander (Test).

### Himmel (`app/src/main/materials/sky.mat`)

Eigenes Material statt Filaments Skybox: Vollbild-Dreieck auf der fernen
Ebene (wie `skybox.mat`), Verlauf Horizont → Zenit mit **entsättigtem
Übergang** (lineares Mischen von Orange und Blau ergab das Lila), breiter Hof
und Einstreuung um die Sonne, Sonnenscheibe wie bisher, **ziehende Wolken**:
kachelbares Wertrauschen 256² R8 (`SkyClouds.cloudTexture`, mit Mipmaps), zwei
Lagen mit verschiedenem Zug, auf eine gedachte Wolkenebene projiziert,
Selbstschatten durch eine dritte Probe zur Sonne hin, Silberrand nahe der
Sonne. Wolkenzug 0,0022/0,0009 Textureinheiten je Sekunde (ein Feld quert den
Himmel in einigen Minuten); die Uhr springt alle 2 h zurück
(Float-Genauigkeit). Kosten: drei Texturzugriffe, ein `exp`, drei `pow` je
Himmelspixel; keine Cubemap mehr im Normalfall. Fehlt das Material oder
scheitert es, nimmt `FilamentCore` wie bisher die Cubemap (deren Farben aus
denselben Zielwerten kommen); `sky = false` bleibt einfarbig.

### Wasser (`app/src/main/materials/water.mat`)

Ersetzt das Material der Knoten `water` (`LoadedAsset.applyWater`; die
Flächen kommen unverändert aus der Welt): dunkler, gering rauer Wasserkörper
(Filament-Licht, also Sonnenglanz nach GGX, Schatten, Dunst), Wellen aus
derselben Rauschtextur (zwei ziehende Lagen, Normale aus Differenzen, in der
Ferne geglättet — sonst flimmert die Fläche wie Kies), Spiegelung des
Himmelsverlaufs nach Fresnel als belichtete Eigenleuchtfarbe; flach
gespiegelte Strahlen bekommen eine dunkle Uferfarbe statt des hellen
Horizonts (Bäume und Gelände spiegeln sich, ohne dass wir sie zeichnen).
Wasser wirft keinen Schatten. Abschaltbar mit dem Schalter „Himmel“
(`RenderSettings.sky`), dann wieder das Material der Kachel.

**Überarbeitet (25.11.2026, `claude/grafik-wasser`):** eigene Wellenkarte,
Wolken- und Uferspiegelung, Transparenz mit weichem Uferrand — siehe
[wasser.md](wasser.md).

### Lenkerperspektive (`CameraRig`, `CockpitModel`, `FilamentCore.projectionFor`)

Befund (Vorher-Bilder): Die Kamera saß 1,45 m hoch 0,25 m vor dem
Ursprung — im Kinn des Fahrers; die nahe Ebene (0,5 m) schnitt Arme und
Lenker weg, man sah **kein Cockpit**. Der Horizont lag in der Bildmitte, die
untere Hälfte (unter dem HUD) zeigte nur Asphalt. Das Ziel 25 m voraus auf
der Strecke ließ die Kamera in Kehren auf Zäune und Wände starren.

Neu:

- **Cockpit** im Code gebaut (wie der eingebaute Fahrer): Rennlenker mit
  Bremsgriffen, Vorbau, Steuerrohr samt Ansatz von Ober- und Unterrohr,
  Radcomputer, Hände in Handschuhen auf den Griffen, kurze Unterarme mit
  Trikotsaum (Farben wie `rider.glb`). Rund 2 700 Dreiecke, 11 Teile. Der
  Lenker schlägt in Kurven um die Lenkachse ein. Am Lenker wird der Fahrer
  ausgeblendet, das Cockpit eingeblendet.
- **Kamera fest im Rahmen des Rads** (Auge 1,46 m hoch, 0,36 m hinter der
  Radmitte — über und hinter dem Lenker, damit Hände und Griffe im Bild
  liegen). Der Rahmen folgt dem Kurs (über ±3 m gemittelt), der Steigung
  (±4 m) und legt sich in Kurven (halbe physikalische Schräglage
  `atan(v²κ/g)`, höchstens 9°). Der Blick kippt nur mit 60 % der Steigung
  mit (der Kopf gleicht aus), liegt 3° unter der Waagrechten und schaut in
  die Kurve (60 % des Winkels zum Punkt 20 m voraus, höchstens 22°).
  Gedämpft werden die Winkel (τ 0,25–0,4 s), nicht die Lage — das Cockpit
  wackelt nie gegen die Kamera.
- **Bildwinkel und Verschiebung:** hoch 74°, quer 64° (senkrecht), nahe
  Ebene 0,2 m. Die Bildmitte wird mit `Camera.setShift` nach oben verschoben
  statt die Kamera zu kippen (Senkrechte bleiben senkrecht): Horizont im
  Hochformat bei rund 35 % der Höhe, quer bei rund 43 %; Hände und Griffe im
  Hochformat bei 70–80 %, quer am unteren Rand. **Gemessen:** Filament 1.75.1
  verschiebt die Bildmitte um das *Doppelte* des Werts in
  Gerätekoordinaten — die Werte sind entsprechend halb so groß.

### Leistung

Im Referenzrenderer nicht messbar (llvmpipe). Abschätzung je Bild:

- Draw Calls: Himmel 1 (ersetzt die Skybox); Wasser keine zusätzlichen
  (nur anderes Material); Cockpit 11 Teile (+ Schattendurchgang) statt der
  rund 30 Teile des Fahrers, der am Lenker ausgeblendet ist. Solitude
  MEDIUM: 380–420 Renderables, 930–1080 Primitive je Stelle (unverändert).
- Shader: Himmel 3 Texturzugriffe + wenige transzendente Funktionen auf
  ≈ 30–50 % der Pixel (vorher 1 Cubemap-Zugriff); Wasser ein beleuchtetes
  Material mit 6 Texturzugriffen auf kleiner Fläche; Farbweg unverändert
  (Filament wendet immer eine 3D-LUT an). Belichtung kostet nichts.
- Einmalig: Wolkentextur 64 KB (statt 1,5 MB Cubemap im Normalfall),
  Umkehrung der Himmelsfarben je `setSky` einige zehn Millisekunden.
- `water.filamat` 206 KB, `sky.filamat` 13 KB im APK.
- Abschaltbar über die vorhandenen Schalter: `sky` (Himmelsmaterial,
  Wolken, Wassermaterial), `fog` (Dunst samt Einstreuung),
  `postProcessing` (Farbweg; SAFE wie bisher ohne).

### Bilder (`docs/indoor/bilder/licht-kamera/`, Referenzrenderer, Solitude MEDIUM)

Je Bild links/oben vorher, rechts/unten nachher; Hochformat 900×1200,
Querformat 1600×900 (auf 1280 verkleinert). „AUTO“ ist der Testabend des
Gründers (24.11., 18:19 Uhr → Dämmerung bei 8° Sonne).

- `licht-<Zeit>-hoch|quer-05500_chase.jpg` — offene Felder, AUTO, MORNING,
  NOON, EVENING
- `wald-<Zeit>-hoch-02200_chase.jpg` — Waldweg, AUTO und NOON
- `dunst-<Zeit>-quer-06600_chase.jpg` — Fernsicht, AUTO und NOON
- `wasser-<Zeit>-quer-06230_blick.jpg` — Teich bei Solitude, NOON und EVENING
- `lenker-hoch|quer-<Laufmeter>_handlebar.jpg` — 02200 eben, 02650 bergab
  (−8 %), 06070 Kehre, 09560 bergauf (15 %)

### Offen / auf dem Gerät zu prüfen

- [ ] Alles oben auf dem Gerät ansehen (Abend, Mittag, Hoch- und Querformat,
      Lenker), vor allem Farbe und Helligkeit mit PBR Neutral.
- [ ] Himmelsmaterial: Logcat ohne Fehler; Wolken ziehen sichtbar, aber
      ruhig; kein Banding im Verlauf (mediump auf dem Gerät).
- [ ] Wasser: Wellen ruhig, kein Flimmern in der Ferne.
- [ ] Lenker: Cockpit nicht abgeschnitten (nahe Ebene 0,2 m), kein Flimmern
      der Straße in der Ferne durch die nähere Ebene; Kurvenlage angenehm,
      nicht übel erregend (sonst `HANDLEBAR_LEAN_FACTOR` senken).
- [ ] Bildrate MEDIUM wie vorher (Himmel und Wasser sind die einzigen
      teureren Shader).
- [ ] `ColorGrading` wird bei jedem `setSky` neu gebaut (alle 10 min bei
      „Automatisch“) — auf Ruckler achten.
- Der **Waldboden** (Bodentextur unter Bäumen) ist dunkel blaugrün und wirkt
  auch mit dem neuen Licht kühl — das ist die Textur der Welt, nicht das
  Licht (Bitte an indoor-world, siehe unten).
- Kehren mit Gebäude direkt voraus (Solitude 6070) zeigen am Lenker kurz die
  Wand — der Blick in die Kurve ist auf 22° begrenzt, damit das Cockpit nicht
  aus dem Bild wandert.
- Der three.js-Betrachter übernimmt nur die Lenkerkamera (`camerarig.mjs`),
  nicht Cockpit, Himmelsmaterial, Wasser und Farbweg — maßgeblich für das
  Aussehen ist jetzt `filashots.mjs`.

## Boden aus der Nähe und Leben (24.11.2026, Branch `claude/indoor-boden-leben`)

**Auftrag:** Der Boden direkt neben dem Rad war unscharf und flach, der Waldboden kühl
blaugrün; die Welt wirkte leblos. **Alles unten ist im Filament-Referenzrenderer gesehen
(Desktop-GL, llvmpipe), nicht auf dem Gerät** — Bildrate, mediump-Verhalten des Treibers und
Anisotropie der Hardware sind dort nicht prüfbar.

### Was neben dem Rad tatsächlich zu sehen ist

Nachgeprüft mit einem Diagnose-Shader: In den ersten 5–10 m beiderseits des Wegs liegt
**nicht** das Gelände, sondern die Randstreifen des Weltbaus (`x_mat_route_verge_*`, Bankett,
Gehweg) und ihr durchsichtiger Auslauf ins Luftbild (`x_mat_route_*_fade`, bewusst
weichgezeichnet), dazu der Belag selbst. Diese Texturen sind fein genug (≈ 1 cm je Texel),
verschwammen aber flach gesehen schon wenige Meter vor dem Rad: **gltfio 1.75.1 übernimmt
keine Anisotropie** (siehe „Bildqualität“), und die Mip-Stufe richtet sich nach der langen
Achse der Pixelspur. Das Gelände selbst sieht man erst ab rund 5–10 m, bis zum Rand des
Details also vor allem in 10–55 m Abstand.

### Eigene Materialien für die Kacheln (`WorldMaterialProvider`)

gltfio ruft für jedes glTF-Material einen `MaterialProvider`. Bisher war das der Ubershader;
jetzt eine eigene Klasse **über** dem Ubershader, die nach dem Materialnamen der Welt
(`WorldMaterialRules`, getestet; das Kachelformat ist unverändert) einige Materialien durch
eigene Filament-Materialien ersetzt — alles andere bleibt Ubershader:

| glTF-Material | eigenes Material | was es kann |
|---|---|---|
| `mat_terrain` (deckend) | `terrain.mat` | Luftbild + Boden-Detail nah, wärmerer Waldboden |
| `x_mat_tree_cards`, `mat_tree_leaves` (Maske) | `foliage.mat` | Aussehen wie Ubershader, Wind im Vertex-Shader |
| `mat_tree_trunk` (deckend, mit Textur) | `foliage.mat`, `atlasMode` 2 | Aussehen wie Ubershader, kein Wind; nur dieses Material kann gebackene Bäume und Bibliotheksbäume überblenden (`fadeMode`, `docs/indoor/baeume.md` „Wechselring“) |
| `mat_route_*`, `x_mat_road_*`, `x_mat_route_verge_*`, Bankett, Gehweg (deckend) | `surface.mat` | Software-Anisotropie, Detail auf Randstreifen, Nahdetail der Fahrbahn ([nahdetail.md](nahdetail.md)) |
| `x_mat_route_*_fade`, `x_mat_route_fringe` (durchsichtig) | `surface_blend.mat` | dasselbe, vormultipliziertes Alpha |

gltfio setzt danach auf jede Instanz dieselben Parameter wie beim Ubershader
(`baseColorFactor`, `emissiveFactor`, `metallicFactor`, `roughnessFactor`, `normalScale`,
`aoStrength`, bei `KHR_materials_specular` `specularColorFactor`/`specularStrength`,
AssetLoader.cpp) und bindet Luftbild bzw. Atlas an `baseColorMap` — die eigenen Materialien
deklarieren diese Parameter alle (unbekannte Namen brechen in Filament ab). Zwei Fallen,
beide im Referenzrenderer gefunden:

- **`flipUV : false`**: Filament-Materialien spiegeln standardmäßig die V-Koordinate, der
  gltfio-Ubershader nicht — ohne die Zeile lasen Luftbild und Laubatlas gespiegelt (eckige
  Blattkarten, verschobenes Luftbild).
- **JNI**: Ohne `getNativeObject()` nimmt gltfio den langsamen Weg (`JavaMaterialProvider`)
  und ruft `createMaterialInstance`, `getMaterial`, `getMaterials`, `needsDummyData`,
  `destroyMaterials` beim Namen auf — `proguard-rules.pro` hält sie. Eine Ausnahme darf nicht
  in den nativen Code: jeder Fehler fällt auf den Ubershader zurück, das Material wird nicht
  wieder versucht. Alles läuft auf dem Hauptthread (dort entstehen die Assets).

Der Referenzrenderer tauscht genauso (`WorldProvider` in `filarender.cpp`, Regeln gespiegelt).

### Boden-Detail (`GroundDetail`, `terrain.mat`)

- **Textur:** eigene, im Code gezeichnete RGBA8-Textur 256², kachelbar, **vier Bodenarten in
  den vier Kanälen** (R Wiese: Büschel und Halme; G Acker/Erde: Schollen und Krümel; B
  Waldboden: Blätter, Nadeln, Zweige; A Schotter/Fels: gerundete Steine 5–30 cm). Jede eine
  Höhe mit Mittel 0,5. 5,12 m je Wiederholung (2 cm je Texel) — der erste Versuch mit 1 cm je
  Texel war in 10–50 m Abstand in den Mip-Stufen verschwunden. Keine fremde Quelle, keine
  Lizenzfrage; Test `GroundDetailTest` (Mittel, Streuung, Naht, Determinismus).
- **Bodenart je Pixel** aus dem, was die Kachel weiß — und das ist beim Gelände nur das
  **Luftbild** (die Gitterpunkte tragen keine Vertexfarbe, die Landnutzung steckt nicht in der
  Kachel): Wiese = grün, Acker/Erde = braun-beige, Waldboden = dunkel und wenig gesättigt,
  Schotter/Fels = grau oder steiler als ≈ 37°. Weiche Gewichte, keine harten Grenzen. Auf den
  Randstreifen ist die Bodenart aus dem Materialnamen bekannt (`verge_grass` → Wiese,
  `verge_forest` → Waldboden, Bankett → Erde/Schotter).
- **Farbe bleibt:** Das Detail moduliert nur die Helligkeit (Mittel 1, ±42 %), dazu eine grobe
  Fleckung aus dem Wolkenrauschen (32 m, ±16 %) gegen sichtbare Wiederholung. Die
  Detail-Normale kommt aus Differenzen derselben Textur (drei Zugriffe), wird in Weltrichtung
  aufgebaut und in den (beliebig gedrehten) Tangentenraum der Fläche umgerechnet.
- **Weich aus** zwischen 30 und 55 m (Gelände) bzw. 19 und 35 m (Randstreifen); dahinter kostet
  das Material so viel wie der Ubershader.
- **Genauigkeit:** Detail-UV des Geländes aus der Kachel-UV (0..1 über 256 m, halber Texel
  Einzug wird herausgerechnet) mal 50 — klein und genau, nahtlos über Kachelgrenzen
  (ganzzahlige Wiederholungen je 256 m). Auf den Randstreifen aus der Weltlage modulo 5,12 m;
  die bleibt dank schwebendem Ursprung unter rund 2,5 km. Im erzeugten GLES-Shader (matinfo)
  kommen `vertex_uv01` und `vertex_worldPosition` als `highp`, die Detail-UV wird in `highp`
  gerechnet (`precision mediump float` gilt nur für den Rest).
- **Anisotropie für das Detail:** Die Detailtextur ist unsere eigene — ihr Sampler bekommt
  `anisotropy = 8` (geht anders als bei gltfio-Texturen).

### Waldboden wärmer

Die Bodentextur unter Wald ist im Luftbild (und nach `ForestFloor`) oft dunkel blaugrün. Im
Material ohne Weltbau: Pixel der Bodenart Waldboden werden bis 260 m (weich aus ab 156 m)
zu einem warmen Laubbraun gleicher Helligkeit gezogen (55 %), dazu allgemein dunkle,
blaugrüne Töne zu Grünbraun (65 %) — auch auf den an das Luftbild angeglichenen Randstreifen.
Die Ferne (Kronendach von oben in den groben Stufen) bleibt unverändert. Eine bessere
Waldbodentextur im Weltbau bleibt sinnvoll (Bitte unten).

### Fahrbahnbeläge: Schärfe und Wiederholung

Die Belagstexturen (256 × 1024, ≈ 1 cm je Pixel, 8–16 m bis zur Wiederholung, Vertexfarben
für die grobe Variation) sind fein genug; die Wiederholung ist in den Bildern nicht
auffällig. Unscharf waren sie wegen der fehlenden Anisotropie. **Software-Anisotropie** in
`surface.mat`: bis 45 m (weich aus ab 31,5 m) vier Abtastungen entlang der langen Achse der
Pixelspur mit `textureLod` in der Mip-Stufe der kurzen Achse, sobald die Spur mehr als
1,6 : 1 gestreckt ist; sonst ein Zugriff. Asphalt und Schotter bleiben dadurch deutlich weiter
voraus scharf (`asphalt-*`, `lenker-*`).

### Wind (`foliage.mat`)

Billig im Vertex-Shader: Wiegen aus zwei Sinus mit Phase aus der Weltlage (eine Böe läuft
durch den Wald, Hüllkurve 20 s), dazu kleines Flattern je Ecke. Das Gewicht kommt aus der Lage
im Laubatlas (`FoliageAtlas`, 512²): Grasbüschel, Farne und Kreuzbilder ganzer Bäume stehen
unten fest und wiegen oben (bis ≈ 7 cm), Zweigkarten flattern ab ihrem Ansatz (≈ 2–4 cm), das
Kronendach wiegt gleichmäßig wenig. Ab 66–110 m steht alles still (auch die Fern-Atlanten der
Stufe 1). Die Uhr ist Filaments `getUserTime()` — kein Setzen je Bild. Schatten wiegen mit
(derselbe Vertex-Shader im Schattendurchgang). Im Referenzrenderer: zwischen zwei Zeitpunkten
0,9 s auseinander ändern sich rund 1,3 % der Pixel (Kronenränder, Grasbüschel).

### Andere Radfahrer (`OtherRiders`, `LifeLayer`, `RiderRecolor`)

- **Modell:** `rider.glb` mit anderen Trikotfarben (Blau, Gelb, Grün, Weiß, Schwarz, Petrol):
  die rot-dominanten Vertexfarben der Teile mit `x_mat_kit` werden ersetzt, ihre Helligkeit
  relativ zum Trikotrot bleibt (dunkle Säume bleiben dunkel), Hose, Haut und Rad bleiben. Je
  Farbe ein eigenes Asset, höchstens eines je Bild nachgeladen.
- **Tempo je Steigung:** jeder Fahrer hat eine feste Dauerleistung 150–260 W und fährt das
  Gleichgewichtstempo aus Rollwiderstand, Hangabtrieb und Luftwiderstand (80 kg, Crr 0,005,
  CdA 0,32; bergab höchstens 15 m/s), Steigung über ±15 m gemittelt, weich (τ 3 s). Flach
  ≈ 8–10 m/s, 8 % bergauf ≈ 2–3,5 m/s.
- **Überholen statt Durchfahren:** Gleichgerichtete fahren in der Spur des Nutzers. Kommt einer
  schneller von hinten, schert er 0,95 m nach links aus; holt der Nutzer einen ein, weicht dieser
  nach rechts aus (zu schmal: links). Untereinander überholt der Schnellere links.
  **Gegenverkehr** (35 %) fährt links mit umgekehrter Steigung. Seitenversatz weich (τ 0,8 s),
  nie über den Wegrand. Test: 20 min Fahrt langsam und schnell, nie eine Überlappung im
  Abstand < 1,8 m (sechs Startwerte, wellige Strecke). Gefundene und behobene Fehler: zu kleines
  Überholfenster bei großem Tempounterschied; Seitenwechsel quer durch die Spur des Nutzers
  beim Überholen eines Dritten — neben dem Nutzer wird die Seite jetzt nie gewechselt, ohne
  Platz bleibt der Fahrer hinter dem Vordermann.
- **Kommen und Gehen:** Fenster 150 m hinter bis 340 m vor dem Nutzer; wer es verlässt,
  taucht nach 6–30 s am passenden Rand wieder auf (schnellere hinten, langsamere und
  Gegenverkehr vorn, im Dunst). Ohne Runde nie jenseits der Enden.
- Rein dekorativ: kein Einfluss auf Physik, Windschatten, Geist oder HUD. Abschaltbar in den
  Indoor-Einstellungen (`docs/indoor/app.md`).

### Vögel (`BirdFlock`, `BirdModel`)

Alle 35–110 s quert ein Schwarm (3–7) oder ein einzelner Vogel den Himmel 120–200 m voraus,
25–55 m hoch, quer zur Fahrtrichtung mit 10–13 m/s, leicht wellend, mit Flügelschlag und
Gleitphasen. Ein Asset mit sieben Vögeln (je zwei Flügel-Knoten, Spannweite 0,7 m, dunkel);
außerhalb eines Fluges ist alles per Ebenenmaske ausgeblendet. In den Bildern stellt
`filashots.mjs --birds` einen Schwarm näher (60 m) vor die Kamera, damit man ihn sieht.

### Optional nicht gemacht

Staub/Spritzer auf Schotter und fallende Blätter im Wald: nicht umgesetzt (Budget an die
Belagsschärfe gegeben, die mehr bringt).

### Qualitätsstufen und Schalter

`RenderSettings` (Vertragsdatei, abwärtsverträgliche Vorgaben) hat vier neue Felder:

| | LOW | MEDIUM | HIGH | SAFE |
|---|---|---|---|---|
| `groundDetail` (Boden-Detail + Software-Anisotropie) | aus | an | an | aus |
| `wind` | aus | an | an | aus |
| `birds` | an | an | an | aus |
| `otherRiders` (höchstens) | 2 | 3 | 5 | 0 |

Detail, Anisotropie und Wind schalten über Parameter (`detailRange`, `anisoRange`,
`windStrength` = 0) sofort auf allen lebenden Instanzen um; der Shader überspringt dann die
zusätzlichen Zugriffe (gleichförmige Verzweigung). Fehlen die `.filamat`, bleibt alles beim
Ubershader.

### Leistung (geschätzt; nicht auf dem Gerät gemessen)

| | vorher | nachher (MEDIUM) |
|---|---|---|
| Draw Calls, Solitude, je Stelle | 372–417 Renderables, 877–1033 Primitive | unverändert für die Welt (Materialtausch, keine neuen Netze); **+13 Renderables / +23 Primitive je anderem Fahrer** (MEDIUM bis 3 → bis +69 Primitive, HIGH bis 5 → +115), Schattendurchgang nur bis 60 m; **Vögel +2 Primitive je Vogel**, nur während eines Fluges (bis +14) |
| Texturzugriffe je Pixel, Gelände | 1 | bis 55 m: 5 (Luftbild, 3 × Detail, 1 × Fleckung); dahinter 1 |
| Belag/Randstreifen | 1 | bis 45 m: 4 (Anisotropie, nur bei gestreckter Spur) + 3 (Detail, nur Randstreifen bis 35 m) = höchstens 7; dahinter 1 |
| Laub | 1 | 1 (+ rund 25 Rechenschritte im Vertex-Shader bis 110 m) |
| Texturspeicher | – | +350 kB (Detail 256² RGBA8 mit Mip-Stufen); Wolkenrauschen geteilt |
| Puffer | – | je anderem Fahrer ≈ 0,8 MB Vertexdaten (MEDIUM ≈ 2,4 MB, HIGH ≈ 4 MB), Vögel ≈ 3 kB |
| Materialpakete im APK | sky 13 kB, water 206 kB | + terrain 215 kB, foliage 204 kB, surface 220 kB, surface_blend 199 kB (im APK komprimiert zusammen ≈ 164 kB) |
| APK (Release, lokal gebaut) | 79 052 648 B (75,4 MB) | 79 234 146 B (+181 kB, fast nur die vier Materialpakete) |

Teuerster neuer Teil ist die Software-Anisotropie: Der Belag deckt in der Verfolgerkamera im
Hochformat rund ein Drittel des Bildes, nah also vier statt einem Zugriff. Auf einem
Mittelklasse-Gerät mit 60 fps zu prüfen; zu teuer → `groundDetail` in MEDIUM aus oder
`SURFACE_ANISO_RANGE_M` kürzen. Das Detail auf dem Gelände liegt meist auf wenigen Prozent der
Pixel (Gelände sieht man nah kaum).

### Bilder (`docs/indoor/bilder/boden-leben/`, Referenzrenderer, Solitude km 1,5–7, MEDIUM)

Je Bild links/oben vorher (Ubershader, ohne Mitfahrer), rechts/unten nachher; Hochformat
900 × 1200, Querformat 1600 × 900 (auf 1280 verkleinert). Erzeugt mit
`tool/indoor-dev/filament/boden-leben.sh`, Laufmeter des Ausschnitts:

- `wald-hoch|quer-00700_chase.jpg` — Waldweg (Schotter), Waldboden warm statt blaugrün
- `kreuzung-hoch-01500_chase.jpg` — Kreuzung im Wald, Auslauf der Randstreifen
- `asphalt-hoch-02500_chase.jpg` — Asphalt, Schärfe voraus
- `wiese-hoch|quer-04000_chase.jpg` — Wiese, Mitfahrer, Gegenverkehr, Vögel
- `abend-quer-03300_chase.jpg` — Abend, Böschung mit Detail, Mitfahrer, Vögel
- `lenker-hoch-02500_handlebar.jpg`, `lenker-quer-04000_handlebar.jpg`,
  `lenker-wald-hoch-00700_handlebar.jpg` — Lenkerkamera

Kritisch gesehen: Das Gelände-Detail ist bewusst zurückhaltend (das Luftbild soll führen);
ganz nah bestimmen weiterhin die Randtexturen des Weltbaus das Bild. Die Acker-Schollen
wirken an steilen Böschungen noch etwas wie gesetzte Platten. Die Vögel sind in Wirklichkeit
weiter weg und damit nur wenige Pixel groß.

### Auf dem Gerät zu prüfen

- [ ] Kacheln laden wie vorher (Logcat ohne `No material with the specified requirements`,
      keine JNI-Abbrüche); **Release-Build** (R8) ebenso — sonst fehlt die Keep-Regel.
- [ ] Gelände nah mit Struktur, keine sichtbaren Kachelnähte des Details, kein Flimmern in
      10–50 m; Übergang bei 30–55 m nicht als Ring sichtbar.
- [ ] Waldboden warm-braun, nicht orange; Wiesen bleiben grün; Felder nicht fleckig.
- [ ] Belag voraus schärfer als vorher, ohne Glitzern/Flimmern (Software-Anisotropie);
      Randstreifen ohne Säume am Auslauf.
- [ ] Laub ohne eckige Karten (flipUV), Grasbüschel wiegen sichtbar, aber ruhig; Bäume
      schwanken nicht wie Gummi.
- [ ] Andere Radfahrer: Trikotfarben richtig, Beine treten, nie durch den eigenen Fahrer;
      Ein- und Ausblenden in der Ferne unauffällig; Schalter wirkt sofort.
- [ ] Ab und zu Vögel am Himmel, nicht zu oft.
- [ ] **Bildrate MEDIUM 60 fps** auf Mittelklasse (Hoch- und Querformat, Verfolger und Lenker)
      — vorher/nachher; LOW mit allem aus wie vorher.
- [ ] Speicher bei HIGH mit 5 Mitfahrern (≈ 4 MB mehr).

## Renderqualität: Grafikstufen Hoch und Ultra (25.11.2026, Branch `claude/indoor-renderqualitaet`)

Auftrag des Inhabers: „dass die Qualität der Grafik nochmal einen deutlichen
Schritt nach vorne macht und quasi fotorealistisch aussieht“ (Galaxy S25).
Bereich dieser Arbeit ist nur die **Bildentstehung** (Licht, Schatten,
Umgebungslicht, Nachbearbeitung, Kantenglättung, Stufen) — nicht Inhalt,
Materialien oder Streckenbau. Ehrlich vorweg: Fotorealismus ist mit
automatisch gebauten Strecken (Laubkarten, 1-m-Gelände, Luftbild als
Bodentextur) und Handy-Leistung nicht erreichbar; der größte Hebel dafür liegt
im Inhalt (Liste am Ende).

### Bestandsaufnahme (gegen die Quellen von Filament 1.75.1 geprüft)

| Thema | vorher | Befund |
|---|---|---|
| Schatten | DPCF, 3 Kaskaden 2048², 300 m | **DPCF ist in 1.75.1 veraltet und fällt auf PCSS (EVSM) zurück** (`Options.h`). Damit erschien im Referenzrenderer auf zwei Backends (llvmpipe-GL, lavapipe-Vulkan) kein einziger Schatten, auf dem Gerät des Inhabers ebenfalls nicht (bestätigt). Kosten nebenbei ~130 MB RGBA16F-Schattenkarten mit 8 Mips. Vorgabe ist seit Commit 2621a75 (Orchestrierung) PCF; dieser Branch übernimmt das zeilengleich. |
| Spiegelndes Umgebungslicht | keins | Ohne `IndirectLight.Builder.reflections()` bindet Filament eine **schwarze** 1×1-Cubemap (`Engine.cpp` `mDefaultIblTexture`, `ColorPassDescriptorSet.cpp`): kein Himmelsglanz, keine Fresnel-Aufhellung, Metall schwarz. |
| SSAO, Bloom | nur Stufe HIGH | `RenderSettings` folgen `manifest.quality`; alle Katalogwelten sind MEDIUM → **SSAO und Bloom waren in der App nie an.** |
| Kantenglättung | 4× MSAA | Laubkarten (Alpha-Maske) und feine Texturen flimmern weiter; MSAA erfasst sie nicht. |
| Gelände | wirft keinen Schatten | Hänge und Kuppen verschatten nichts (`shadowFlags`: nur Gebäude und Bäume). |
| Farbe | PBR Neutral + Kontrast/Lebendigkeit je Tageszeit | gut; kein Weißabgleich, mittags kühlgrauer Asphalt. |
| Bildrate | `FrameMeter` (fps, P95 je Sekunde) | keine längere Messung, kein 1-%-Wert, keine GPU-Zeit. |
| Referenzrenderer | `lispsm` | Java-Vorgabe `false`, C++ `true` — filarender rechnete Schatten anders als die App; angeglichen. |

### Was neu ist

**Grafikstufe** in den Indoor-Einstellungen unter „3D-Ansicht“
(`IndoorGraphicsCard.kt`, gemerkt als `trailscape.indoor.graphics`):
**Standard** (Vorgabe, unverändert = `RenderSettings.forQuality(Weltqualität)`),
**Hoch**, **Ultra** (`core/…/indoor/world/RenderTiers.kt`, getestet). Die
Stufe ist von der Paketqualität getrennt (kein neuer Weltbau nötig).

| Effekt | Standard | Hoch | Ultra | Datei |
|---|---|---|---|---|
| Spiegelungs-Umgebungslicht aus dem Himmelsmodell | – | ✓ | ✓ | `core/…/sky/SkyReflections.kt`, `RenderQuality.buildReflections` |
| SSAO | – | ✓ (Güte MITTEL, Radius 0,5) | ✓ (Güte HOCH, Radius 0,55) | `RenderQuality.applyAmbientOcclusion` |
| Bloom | – | ✓ (MITTEL) | ✓ (HOCH) | `RenderQuality.applyBloom` |
| Hochskalierung der dyn. Auflösung | bilinear | FSR (MITTEL) | FSR (HOCH) | `RenderQuality.applyDynamicResolution` |
| Weißabgleich je Tageszeit | – | ✓ | ✓ | `core/…/sky/PhotoGrade.kt` |
| Kantenglättung | 4× MSAA | 4× MSAA | **TAA** (Varianz-Box, Schärfe 0,25) | `RenderQuality.applyTemporalAA` |
| Schatten | PCF, 3 Kaskaden, 300 m | wie Standard | **4 Kaskaden (8/30/100 m), 800 m** | `RenderQuality.shadowOptions`, `FilamentCore.ensureSunShadows` |
| Gelände wirft Schatten | – | – | ✓ | `FilamentAssets.applyShadows`, `TileStreamer` |
| Vignette | – | – | sehr dezent | `RenderQuality.applyVignette` |
| Mitfahrer | 3 | 5 | 5 | (HIGH-Grundlage) |

Alles einzeln schaltbar auf der Diagnoseseite (neue Schalter am Ende der
Liste, Voreinstellungen „Hoch“ und „Ultra“). Im **sicheren Grafikmodus**
(`RenderGuard`) sind alle neuen Effekte aus (`RenderTiers.withoutTierExtras`
im Setter von `FilamentWorldRenderer.settings`). Es wird **kein
Materialparameter** gesetzt — nur View-, Licht- und Texturoptionen.

**Spiegelungs-Umgebungslicht** (`SkyReflections`): Cubemap 64² mit 7
Mip-Stufen aus dem sichtbaren Himmel (`SkyLook` samt Wolken) geteilt durch
`skyScale` — im selben Maß wie die SH des Umgebungslichts (geprüft: Mittag
Horizont 0,74/0,86/1,02 gegen `iblHorizon` 0,73/0,77/0,83); unter dem Horizont
der Boden des Umgebungslichts, abgeglichen auf die Helligkeit des sichtbaren
Horizonts (`groundFor`). Vorfilterung mit GGX nach Filaments Abbildung
`lod = (N−1)·p·(2−p)` (`surface_light_indirect.fs`); Stärke 0,85, weil Filament
Umgebungslicht nicht durch Geometrie verdeckt. Rechenzeit auf dem
Entwicklerrechner unter 50 ms, einmal je Tageszeitwechsel; Textur ≈ 130 kB.
Wirkung: dezent (mittlere Abweichung 2–3 von 255) — Straße und Gelände sind
rau, physikalisch richtig wenig Glanz; sichtbar als Aufhellung der Straße im
Gegenlicht und Glanz an Rad, Helm, Dächern.

**Sonne neu bauen:** Java kennt in 1.75.1 keine Schattenoptionen zur Laufzeit.
`ensureSunShadows` baut bei anderen Kaskaden/Reichweite/Stabilität eine neue
Sonne (neue Entity, Richtung/Farbe/Stärke übernommen), zeigt sie und entfernt
erst dann die alte.

**Bildratenmessung** (Diagnose, „Bildrate je Stufe“): lädt die Testwelt, fährt
selbst und misst Standard, Hoch und Ultra je 30 s nach 3 s Vorlauf
(`RenderBench`): mittlere Bildzeit, **1 % niedrig** (99. Perzentil der
Bildzeit) und mittlere **GPU-Zeit** aus `Renderer.getFrameInfoHistory`
(falls der Treiber sie liefert, sonst „–“). Die Bildzeit ist durch die
Obergrenze von 60 B/s gedeckelt; erst die GPU-Zeit zeigt die Reserve.

**Renderauflösung** (Wunsch des Inhabers): Die Diagnose zeigt unter
„Renderauflösung“ die Zeichenfläche in px (nach der festen Skalierung), den
zuletzt benutzten Faktor der dynamischen Auflösung je Achse
(`View.getLastDynamicResolutionScale`, in Java 1.75.1 vorhanden; scheitert der
Aufruf: „nicht verfügbar“) und die tatsächlich gezeichneten Pixel. Die Messung
„Bildrate je Stufe“ mittelt den Faktor je Stufe und nennt die Zeichenfläche.
**Untergrenze** der dynamischen Auflösung jetzt einstellbar
(`RenderSettings.dynamicResolutionMinScale`, Regler in der Diagnose): Standard
wie bisher 0,6; Hoch/Ultra 0,75 (`RenderTiers.HIGH_DYNRES_MIN_SCALE`) — mit
FSR ist 0,75 je Achse etwa AMDs Stufe „Quality“, darunter wird das Bild
sichtbar weich; dafür sinkt bei Überlast eher die Bildrate als die Schärfe.
Nicht auf dem Gerät gemessen: Wenn die Messung bei Hoch/Ultra einen Faktor
dauerhaft an der Untergrenze und „1 % niedrig“ über 25 ms zeigt, Untergrenze
in der Diagnose auf 0,6 stellen und vergleichen.

**Referenzrenderer:** neue Schalter (`settings.reflections`, `taa`,
`effectQuality`, `cascades`, `shadowFarM`, `stableShadows`, `terrainShadows`,
`whiteBalance`, `vignette`, `shadowType`, `lispsm`, …), `--seti`/`--sets`/
`--frames` in `filashots.mjs`, Bildsatz `filament/renderqualitaet.sh`.
**Wichtig:** Unter Mesa llvmpipe (OpenGL) zeigt Filament auch mit PCF keine
Schatten (nur der Debug-Typ PCFd); mit `FILARENDER_BACKEND=vulkan` (lavapipe,
`apt-get install mesa-vulkan-drivers`, Materialien mit `matc -a opengl -a
vulkan`) erscheinen sie. Die Bilder unten sind deshalb mit Vulkan gerechnet —
das Gerät nutzt OpenGL ES; Shader und Abbildung sind dieselben.

### Bilder (`docs/indoor/bilder/renderqualitaet/`)

Je Strecke dieselbe Kamera (Verfolger, Hochformat 900 × 1200), links
Standard (wie bisher, aber schon mit PCF-Schatten), Mitte Hoch, rechts Ultra.
Gerechnet mit `tool/indoor-dev/filament/renderqualitaet.sh` (Filament 1.75.1,
lavapipe-Vulkan; Ultra mit 16 Bildern für TAA), Strecken aus dem Release
`indoor-worlds-v1` (v11). In Klammern die mittlere Abweichung zu Standard (0–255).

| Bild | Strecke, Tageszeit | Was man sieht |
|---|---|---|
| `alpe-dhuez-EVENING-03000.jpg` | Alpe d'Huez, Abend, km 3 | Hoch (5,5): Tiefe im Laub und an der Böschung (SSAO). Ultra (11,1): **der Hang links verschattet die Straße** (Sonne 11° hinter dem Grat) — physikalisch plausibel, nimmt aber die Baumschatten-Flecken weg; Geschmackssache, einzeln abschaltbar („Gelände wirft Schatten“). |
| `arenberg-NOON-01500.jpg` | Wald von Arenberg, Mittag | Hoch (2,9): wärmerer Asphalt statt kühlgrau (Weißabgleich), leichter Himmelsglanz auf der Straße in der Ferne. Ultra (7,1): ruhigere Kanten am Horizont, Vignette. |
| `monte-sante-marie-EVENING-05000.jpg` | Crete Senesi, Abend | offenes Gelände: wenig Unterschied (3,3 / 7,5) — Wiese und Weg bekommen etwas Tiefe, Ränder dunkler. |
| `sa-calobra-NOON-03000.jpg` | Sa Calobra, Mittag | Hoch (2,9): dichterer Wald (SSAO unter Büschen), Fahrerschatten wie Standard. Ultra (5,7): glattere Laubkanten. |

Zur Einordnung: Der **größte sichtbare Schritt dieses Branches ist der
Schatten selbst** (vorher erschien keiner — auf dem Gerät bestätigt; jetzt in
allen Stufen über die Orchestrierung). Hoch/Ultra legen Tiefe, Glanz, Farbe und
ruhige Kanten darauf; das ist ein deutlicher, aber kein fotorealistischer
Sprung. TAA wirkt vor allem in Bewegung (Flimmern), was ein Standbild kaum zeigt.
Spiegelungs-IBL, Weißabgleich, Vignette, TAA nur im Referenzrenderer geprüft;
FSR-Hochskalierung (dyn. Auflösung) und die Bildratenmessung gar nicht im Bild
(nur auf dem Gerät wirksam).

### Erwartete Kosten je Effekt (geschätzt, nicht gemessen — dafür die Messung in der Diagnose)

| Effekt | GPU-Kosten (S25-Klasse, 1080×2340) | Speicher |
|---|---|---|
| Spiegelungs-IBL | ≈ 0 (ein Texturzugriff je Pixel) | 130 kB |
| SSAO MITTEL / HOCH | ≈ 1,0 / 1,5–2 ms | ~10 MB (halbe Auflösung) |
| Bloom MITTEL / HOCH | ≈ 0,3 / 0,5 ms | ~5 MB |
| FSR statt bilinear | ≈ 0,3 ms, nur bei Skalierung < 1 | – |
| TAA statt 4× MSAA | ≈ gleich (MSAA auf Kachel-GPUs billig, TAA ein Vollbilddurchgang); Verlaufspuffer | ~20 MB |
| 4. Kaskade + 800 m + Geländeschatten | ≈ +1–2 ms (mehr Werfer im Schattendurchgang) | +8 MB (Tiefe 2048²) |
| Vignette, Weißabgleich | ≈ 0 (im Farbweg) | – |
| PCF statt DPCF/PCSS (alle Stufen) | **billiger** (keine EVSM-Mips) | **−~120 MB** |

### Auf dem Gerät zu prüfen (Prüfliste für den Inhaber)

Alles in **Indoor → Einstellungen** bzw. **Diagnose** (Einstellungen → Erweitert → Diagnose).

1. **Diagnose → „Messen (Standard, Hoch, Ultra je 30 s)“** (unter „Bildrate je
   Stufe“). Lädt die Testwelt und fährt selbst; ~2 Minuten Bildschirm an
   lassen, Handy nicht bewegen. **Bitte melden (Foto genügt):** je Stufe die
   drei Zahlen „Ø … ms“, „1 % niedrig … ms“, „GPU … ms“ und die „Auflösung Ø … % von … px“. Faustregel:
   GPU unter 14 ms = 60 B/s mit Reserve; „1 % niedrig“ über 25 ms = spürbares Ruckeln.
2. **Einstellungen → 3D-Ansicht → Grafikstufe Ultra**, dann eine echte Strecke
   (am besten Alpe d'Huez oder Sa Calobra, Tageszeit Abend und Mittag):
   - Wirft der Fahrer einen Schatten auf die Straße? Bäume auf den Weg?
   - **Schatten-Akne** (streifige/gesprenkelte dunkle Muster auf Straße und
     Hängen), **schwebende Schatten** (Lücke zwischen Rad und Schatten),
     **sichtbare Kaskadengrenze** (Schatten wird ab einer Entfernung schlagartig
     gröber, besonders bei 8 m und 30 m vor dem Fahrer).
   - Flimmern Laub und Schotter bei Fahrt weniger als in Standard (TAA)? Gibt es
     **Schlieren/Geisterbilder** hinter dem Fahrer oder an Laubrändern?
   - Ist die Straße am Abend in den Bergen plausibel im Hangschatten, oder
     wirkt es nur dunkler? (Sonst in der Diagnose „Gelände wirft Schatten“ aus
     und vergleichen.)
3. **Stufe Hoch** dasselbe kurz: Laub und Böschungen mit mehr Tiefe? Farben
   mittags wärmer, nicht gelbstichig? Abends nicht orange überzogen?
4. **Diagnose → Schalter einzeln** (am Ende der Liste): Spiegelung des Himmels,
   TAA, Gelände wirft Schatten, Stabile Schatten, Weißabgleich, Vignette,
   Effekte in hoher Güte — jeder soll sofort wirken und nichts zum Absturz
   bringen. „Stabile Schatten“ ruhig testen: im Referenzrenderer verschwanden
   damit bei 800 m Reichweite die Schatten.
5. **Sicherer Modus:** Falls die App mit Hoch/Ultra abstürzt, startet sie beim
   nächsten Mal im sicheren Grafikmodus (alle neuen Effekte aus) — bitte melden,
   welche Stufe und welche Strecke.
6. 20 Minuten Ultra fahren: wird das Handy heiß, sinkt die Bildrate
   (Diagnose → Messwerte, „Skalierung“ unter 100 % = dynamische Auflösung greift)?

Wenn Hoch flüssig läuft (GPU deutlich unter 16 ms), kann Hoch die Vorgabe
werden (eine Zeile: `IndoorViewModel.readGraphics`, Rückfall `STANDARD`).

### Offen

- Kontaktschatten (`screenSpaceContactShadows`) und SSCT geprüft, im
  Referenzrenderer ohne sichtbare Wirkung → nicht eingebaut.
- Weiche Schatten: DPCF/PCSS/VSM zeigen in 1.75.1 nichts; PCF bleibt hart
  (2×2-Filter). Weiche Kanten erst mit einer neueren Filament-Version prüfen.
- Dunstfarbe aus dem IBL (`fogColorFromIbl`) nicht eingebaut (Farbe würde mit
  der Spiegelungs-Cubemap multipliziert; ohne Gerät nicht abstimmbar).
- Stufe Ultra bleibt Wahl des Inhabers; ob Hoch Vorgabe werden kann, entscheidet
  die Messung auf dem Gerät.

### Weichere Schatten innerhalb von PCF? (geprüft, nicht eingebaut)

Filament 1.75.1 filtert PCF fest im Shader: `ShadowSample_PCF_Low` =
Hardware-Tiefenvergleich + 3×3-Gauß **in Texeln** der Schattenkarte
(`surface_shadowing.fs`; die Methode ist eine Übersetzungs-Konstante,
`CONFIG_SHADOW_SAMPLING_METHOD`). `View.SoftShadowOptions` (Penumbra) wirkt nur
auf DPCF/PCSS, `ShadowOptions.vsm.blurWidth` nur auf VSM — beide zeigen keine
Schatten. Die einzige Java-Stellschraube ist damit die **Texelgröße**
(`mapSize`, Kaskadengrenzen): weichere Kanten hieße gröbere Schatten
(größere Texel), also Treppen statt Halbschatten — kein Gewinn. Ultra hat mit
4 Kaskaden (8/30/100 m) eher schärfere Nahschatten; TAA glättet dort die
Treppen der Kante zeitlich (Jitter), macht den Halbschatten aber nicht breiter.
Ergebnis: **keine belastbare Stellschraube**, Standard und Stufen bleiben, wie
sie sind.

### Notiz für die Baum-Bibliothek: Dither-Überblenden mittel ↔ fern

Der sichtbare Sprung beim Wechsel der Baumstufen bei 60 m ließe sich in
`foliage.mat` (Alpha-Maske, also ohne neue Shadervarianten) mit einem
ortsfesten Dither-`discard` über eine Entfernungsspanne überblenden: Fragment
der nahen Stufe verwirft ab `d > 60 m − b` mit wachsendem Anteil, die ferne
Stufe spiegelbildlich (Bayer-4×4 aus `gl_FragCoord`, Abstand aus
`getWorldPosition()`/`getWorldCameraPosition()`), mit TAA in Ultra fast
unsichtbar. **Nicht eingebaut**, weil (1) `foliage.mat` Inhalt der Sitzung
„Streckenqualität“ ist, (2) beide Stufen dafür wissen müssen, welche sie sind
(neuer Parameter oder zwei Materialinstanzen — Parameter-Risiko, Issue 106,
nur sicher, wenn er in der `.mat` deklariert ist und `WorldMaterialProvider`
ihn setzt), (3) der Schattendurchgang dasselbe verwerfen muss (sonst springen
die Schatten weiter) und (4) beide Stufen in der Spanne gleichzeitig gezeichnet
werden müssen (der Streamer blendet heute hart um). Aufwand etwa 1–2 Tage
zusammen mit der Baum-Bibliothek; abschaltbar über eine Spannweite 0.

### Für einen weiteren großen Schritt nötig — außerhalb dieses Bereichs

| Was | Wirkung | Aufwand | Paketgröße | Lizenz |
|---|---|---|---|---|
| PBR-Texturen mit Normal-/Rauheitskarten für Asphalt, Schotter, Wiese, Waldboden (gekachelt, 1–2 K) | sehr hoch: echtes Relief und Glanz aus der Nähe; nutzt dann das neue Spiegelungs-IBL voll | Materialien `surface.mat`/`terrain.mat` um Normal-/Rauheitskarten erweitern, Mischen nach Belag; 1–2 Wochen | +10–25 MB einmalig in der App (nicht je Strecke) | CC0 von ambientCG / Poly Haven |
| Echte 3D-Baummodelle (wenige Arten, LOD, Instanzen) statt Laubkarten | sehr hoch: Wald ist heute der künstlichste Teil | Weltbau + Instanzierung im Renderer; 2–4 Wochen | +5–15 MB App, je Strecke kaum mehr (nur Positionen) | CC0 (Poly Haven, Quaternius) oder selbst modelliert; SpeedTree nicht (Lizenz) |
| Höher aufgelöstes Luftbild (20 cm statt 1 m) nahe der Strecke | hoch für Bodennähe | Weltbau; je Land unterschiedlich | ×4–×10 Textur in einem 100-m-Band (~+50–150 MB je Strecke) | DOP20 offen in BW/NRW/Hessen (dl-de/by-2.0), anderswo oft nicht frei |
| Straßenmarkierungen, Bordsteine, Leitpfosten, Zäune als Geometrie | mittel-hoch: Maßstab und Glaubwürdigkeit | Weltbau aus OSM; 1–2 Wochen | gering | ODbL (OSM) |
| Gelände feiner als 1 m nahe der Strecke + Detail-Normalen | mittel | Weltbau | moderat | Laserdaten je Land |
| Neuere Filament-Version (weiche Schatten, bessere TAA/FSR) | mittel | AGP/compileSdk 37 nötig (siehe app/build.gradle.kts) | – | Apache 2.0 |

## Abweichungen vom Vertrag

1. **Fahrer ohne Datei möglich.** Vertrag 7.6 nennt „Modelle“ als Startstufe
   und `WorldModels` die Dateien des Weltbaus. Die Darstellung bringt
   zusätzlich einen eigenen Fahrer mit (im Code erzeugte GLB, gleiche
   Vereinbarung). Grund: die Testszene braucht „keine Datei außer dem, was der
   Slice selbst mitbringt“, und ein fehlendes oder kaputtes Modell soll nicht
   die ganze Fahrt kosten. `BAD_ASSETS` kommt nur, wenn auch der eingebaute
   Fahrer scheitert.
2. **Die Fläche zeigt selbst eine Ersatzansicht.** Der Vertrag sagt, die
   Oberfläche zeige bei `onFailure` eine Meldung statt der 3D-Fläche (das tut
   indoor-app). `WorldSurface` zeigt zusätzlich selbst die 2D-Ersatzansicht,
   damit auch die Diagnoseseite und jede künftige Einbindung nie schwarz
   bleiben.
3. **Teilflächen per Ebenenmaske.** Statt Entities aus der Szene zu nehmen,
   werden Renderables über `RenderableManager.setLayerMask` aus- und
   eingeblendet (billig, keine Szenenänderung je Bild). Gleiches für die
   Schalter Bäume/Gebäude/Kleinteile.
4. **`RenderStats.resolutionScale`** meldet nur die feste Skalierung aus den
   Einstellungen; den Stand der dynamischen Auflösung gibt Filaments
   Java-Schnittstelle nicht heraus.
5. **Schatten je Knotenart.** `trees` wirft, empfängt aber nicht (Vertrag:
   „wirft“); `props` weder noch; Fahrer und Geist werfen und empfangen.

## Annahmen (gegen die Quellen von 1.75.1 geprüft)

Geprüft durch Lesen der Java-Quellen und Shader im Tag `v1.75.1` und
`javap` der AARs:

- `Gltfio.init()` ruft selbst `Filament.init()` — `filament-utils`
  (`Utils.init()`) wird nicht gebraucht.
- `Engine.create(Backend)` wirft `IllegalStateException`, wenn der Treiber
  nicht startet (nicht `null`).
- `FogOptions` hat in 1.75.1: `distance`, `cutOffDistance`, `maximumOpacity`,
  `height`, `heightFalloff`, `color`, `density`, `inScatteringStart`,
  `inScatteringSize`, `fogColorFromIbl`, `skyColor`, `enabled`. Die
  Dunstfarbe wird im Shader mit `iblLuminance` multipliziert — genau wie die
  Skybox, sobald ein `IndirectLight` gesetzt ist (`Skybox.intensity` gilt
  dann nicht). Deshalb ist Dunstfarbe = Horizontfarbe der Cubemap.
- Standardmäßig wirkt der Dunst auch auf die Skybox (`cutOffDistance = ∞`);
  deshalb setzen wir `cutOffDistance` endlich.
- Cubemap-Befüllung: `Texture.setImage(engine, level, x, y, z, w, h, depth, buffer)`
  mit `depth = 6` („Cubemaps are treated like a 2D array of six layers“). Die
  ältere Variante mit `faceOffsets` gibt es in 1.75.1 nicht mehr.
- `IndirectLight.Builder.radiance(bands, sh)` multipliziert intern mit den
  Faktoren `A[i]` (inklusive Vorzeichen); unsere Projektion benutzt genau
  diese Basis.
- `ShadowOptions`: `mapSize`, `shadowCascades`, `shadowFar`,
  `shadowNearHint`, `shadowFarHint`, `stable` … vorhanden;
  `LightManager.setShadowCaster(i, bool)` schaltet die Sonne zur Laufzeit.
- `View.setShadowingEnabled` hat keinen Getter (keine Kotlin-Eigenschaft).
- gltfio ruft über JNI `UbershaderProvider.createMaterialInstance`,
  `getMaterial`, `getMaterials`, `needsDummyData` und Felder von
  `MaterialProvider$MaterialKey` beim Namen auf; diese tragen `@UsedByNative`
  und werden von den mitgelieferten consumer-rules gehalten (im
  Release-Mapping nachgeprüft, siehe unten).
- **Himmels-Cubemap symmetrisch:** Ob ein Treiber die Seitenflächen senkrecht
  spiegelt, ließ sich ohne Gerät nicht klären. Der Verlauf hängt deshalb nur
  von `|y|` ab — unter dem Horizont liegt ohnehin Gelände.
- Die einfarbige Skybox (`sky = false`) wird in Filament **nicht** mit der
  Stärke des Umgebungslichts multipliziert (Cubemap und Dunst schon); sie
  bekommt deshalb den sichtbaren Horizont direkt (szenenlinear), Cubemap und
  Dunst denselben Wert geteilt durch `ambientLux × Belichtung`.
- `RenderSettings.SAFE` (Vertragsdatei) schaltet auch die Nachbearbeitung ab;
  dann fehlen Tonwertabbildung und Gamma — das SAFE-Bild ist dunkler und
  härter. Das ist gewollt (Rettungsanker), kein Fehler der Darstellung.
- Kachel-GLBs sind höchstens 96 MB (Schutz gegen kaputte Dateien).

## R8

Keine eigenen Filament-Regeln in `app/proguard-rules.pro`: Die AARs von
filament und gltfio bringen consumer-rules mit, die alles mit
`@UsedByNative`/`@UsedByReflection` halten. Nachgeprüft im Mapping des
Release-Builds (`app/build/outputs/mapping/release/mapping.txt`): die per JNI
benutzten Namen bleiben erhalten.

## Unabhängiger Review

Ein eigener Unter-Agent hat den Code gegen die Filament-Quellen 1.75.1 (Java,
JNI, gltfio-C++, Shader) und auf Lebenszyklus/Threads gelesen und R8 8.2 mit
den consumer-rules nachgestellt. Behoben: der hängende `ResourceLoader` nach
einem Abbruch (siehe oben), einfarbige Skybox heller als der Dunst,
60-fps-Grenze auf 90/120 Hz, falscher „kein Bild“-Ausfall nach langer Pause,
`uiHelper` vor `attachTo` setzen, `key(renderer)` um die `AndroidView`,
GLB-Puffer früher freigeben, verwaiste gelesene Puffer, Dunsthöhe bei langen
Anstiegen, Rendern bis `ON_STOP`, Kettenblatt auf der rechten Seite des
eingebauten Fahrers, Asset-Leck bei Modellfehler, Diagnose löscht nicht mehr
das Verzeichnis, aus dem gerade gelesen wird, Fahrer nach `unloadWorld`
ausgeblendet. Ohne Befund: Koordinaten, Wicklung, Sonnenrichtung,
SH-Konvention, Cubemap, API-Namen, Abbau-Reihenfolge, Threads, R8.

## Auf dem Gerät zu prüfen

- [ ] Diagnoseseite öffnen: Gerät/Treiber werden angezeigt (GL-Hersteller,
      Renderer, Version, Schnittstelle „OPENGL“).
- [ ] **Testszene:** Wiese, Ringstraße, Bäume, Häuser, Fahrer sichtbar; Himmel
      blau mit Verlauf, Sonnenscheibe, Dunst am Horizont passt zur
      Himmelsfarbe (kein harter Rand).
- [ ] Ist der Himmel oben blau und am Horizont hell (nicht umgekehrt)? Falls
      verkehrt: Cubemap-Ausrichtung in `SkyClouds.cubeDirection` prüfen.
- [ ] Probefahrt: Fahrer fährt die Runde, Beine treten, Räder drehen sich
      vorwärts, Kurbel und Pedale passen zu den Füßen; Geist durchscheinend
      links daneben.
- [ ] Fahrer blickt in Fahrtrichtung (nicht rückwärts/seitwärts), Nase hebt
      sich bergauf.
- [ ] Kameras: „Von hinten“ folgt weich und taucht nie ins Gelände;
      „Lenker“; „Von oben“.
- [ ] Licht: Schatten des Fahrers liegen auf der Straße; Umgebungslicht nicht
      zu dunkel/hell (Seiten von Häusern im Schatten nicht schwarz).
      Tageszeit-Schieber verschiebt die Sonne von Ost nach West.
- [ ] Jeden Schalter einzeln: Wirkung sofort sichtbar; SAFE läuft überall.
- [ ] Bildrate je Voreinstellung (LOW/MEDIUM/HIGH) auf dem Handy des Gründers.
- [ ] Auflösung 50 %: Bild unschärfer, nicht verzerrt; zurück auf 100 %.
- [ ] Drehen während der Fahrt, App in den Hintergrund und zurück, Bildschirm
      aus/an: Bild kommt wieder, kein Absturz, keine schwarze Fläche.
- [ ] Testwelt (nach dem Merge mit indoor-world): Kacheln erscheinen ohne
      Löcher, Teilflächen der groben Stufen verschwinden erst, wenn die feine
      Kachel steht; beim Weiterfahren keine flackernden Kachelgrenzen.
- [ ] Echte Welt: Speicher bei HIGH (Absturz beim Laden?), 30 Minuten
      Dauerlauf (Wärme, Speicher wächst nicht).
- [ ] Ausfall: In der Diagnose nach einem erzwungenen Ausfall (z. B. Welt
      löschen während der Fahrt) erscheint die 2D-Ersatzansicht mit Profil.
- [ ] Release-Build (R8) startet die Darstellung genauso wie Debug.
- [ ] Startpunkt versetzen (docs/indoor/app.md, „Startpunkt und Fortsetzen“;
      an der Darstellung nichts geändert): Der Fahrer springt ab 30 m
      (`MotionSmoother.JUMP_M`), die Kamera ab 80 m (`CameraRig.SNAP_DISTANCE_M`);
      die Kacheln um die neue Stelle laden nach — prüfen, dass dabei keine
      Löcher bleiben und der erste Zustand schon an der Startstelle steht.

## Integration (23.11.2026)

Nach dem Zusammenführen mit indoor-world und indoor-app zum ersten Mal
gemeinsam gebaut. Geändert bzw. geprüft:

- **Frist „kein Bild“** zählt nur bei laufender Schleife. Vorher hätte nach
  mehr als 8 s im Hintergrund (Swapchain bleibt, z. B. Dialog oder geteilter
  Bildschirm) ein einziges ausgelassenes Bild beim Wiederanlauf
  (`beginFrame` = `false`) die Darstellung als `NO_GRAPHICS` abgeschaltet.
- **`onRelease` der `SurfaceView`** löst nur die eigene Fläche
  (`detach(view)`); baut Compose die neue View vor dem Freigeben der alten,
  bleibt die neue angehängt.
- **Ersatzansicht** bekommt das Profil der Welt auch, wenn die Darstellung
  schon vor `loadWorld` ausgefallen ist.
- **Umgebungslicht** bei tiefer Sonne 26 000 statt 22 000 lx (Bitte von
  indoor-world: Waldweg unter dem Kronendach). In Filament verdeckt Geometrie
  das Umgebungslicht nicht (nur SSAO dunkelt ab); im Schatten des Kronendachs
  bleibt genau dieses Licht. Himmel und Dunst werden dadurch um denselben
  Faktor heller (sie hängen an `IndirectLight.intensity`).
- **`filament-utils`** entfällt (eigene Bitte unten); `UiHelper` steckt in
  `filament-android`, `Gltfio.init()` lädt die Engine.
- **Filament 1.81.0:** Die Signaturen aller hier benutzten Klassen
  (`Texture`, `View.FogOptions`, `IndirectLight.Builder`, `Skybox.Builder`,
  `UiHelper`, `Engine`, `LightManager`, `RenderableManager`, `Renderer`,
  `Camera`, gltfio `AssetLoader`/`ResourceLoader`/`FilamentAsset`/
  `FilamentInstance`/`Animator`/`UbershaderProvider`) sind per `javap` in
  1.75.1 und 1.81.0 gleich; ein späteres Anheben (braucht compileSdk 37)
  ändert an diesem Code nichts.
- **gltfio-Quellen 1.75.1 nachgelesen:** Der Animator entsteht in
  `ResourceLoader` beim Laden (`createAnimator`), `releaseSourceData()`
  danach ist also sicher. `AssetLoader` nimmt den schnellen Weg über
  `UbershaderProvider.getNativeObject()` (`@UsedByNative`, im
  Release-Mapping erhalten); die Java-Rückrufe des langsamen Wegs
  (`createMaterialInstance` …) fehlen im R8-Build, werden aber nie gerufen.
- **GLBs des Weltbaus gegen die Erwartung geprüft** (Testwelt, 51 Kacheln,
  dazu `rider.glb`/`ghost.glb`): keine Erweiterungen (auch kein
  `KHR_materials_unlit` in Benutzung), nur Dreiecke, Indizes 16 Bit,
  `COLOR_0` als normierte `UNSIGNED_BYTE`×4, Texturen JPEG Baseline (`FF C0`)
  oder PNG in Zweierpotenzen bis 1024, `metallic = 0`, `roughness ≥ 0,6`,
  `OPAQUE`/`BLEND`/`MASK`+doppelseitig — alles, was der Ubershader kann.
  Kachelnamen `tiles/L<L>_<ix>_<iy>.glb`, Eckpunkte relativ zum Anker
  (`x = e − anchorE`, `y = u − anchorU`, `z = −(n − anchorN)`), Teilflächen
  `terrain_sub_i_j` mit `i` nach Osten, `j` nach Norden — genau wie
  `TileStreamer` und `TilePlanner.hiddenSubs` rechnen. Fahrer und Geist
  tragen die Animationen `pedal` und `roll`.
- **Folge der Teilflächen-Regel:** Ab Stufe 1 hängt im Weltbau *aller*
  Inhalt (Waldkörper, grobe Gebäude) an `terrain_sub_i_j`. Die Darstellung
  ordnet ihn deshalb als Gelände ein: In Stufe 1 und 2 werfen Waldkörper und
  Gebäude keine Schatten (liegen ohnehin jenseits von 300 m), und die
  Schalter Bäume/Gebäude wirken nur auf Stufe 0.

Risiken, die nur das Gerät klärt (zusätzlich zur Liste oben):

- Hauptthread: `createAsset` einer Kachel (bis rund 1–2 MB GLB) läuft im
  Bildtakt auf dem Hauptthread; beim Nachladen sind kurze Ruckler möglich.
- Ein Absturz im nativen Code (Treiber, `SIGSEGV`) lässt sich nicht fangen;
  die Ersatzansicht greift nur bei Java-Ausnahmen und bei „kein Bild“.
- Die Fahrt verlangt OpenGL ES 3.0; `GlProbe` lässt seine EGL-Anzeige
  bewusst offen (prozessweit, Filament nutzt dieselbe).

## Bitte an andere Slices

- **indoor-app:**
  - ~~`filament-utils-android` wird von der Darstellung nicht gebraucht
    (`Gltfio.init()` genügt) — kann aus `app/build.gradle.kts` entfallen
    (spart je ABI rund 1–2 MB), wenn sonst niemand es nutzt.~~ Erledigt bei
    der Integration (1,4 MB je ABI).
  - Die Darstellung bekommt derzeit weder `setSky` (Tageszeit) noch
    `setSettings` aus den Einstellungen; ohne `setSettings` gilt die Qualität
    der Welt. Wer Tageszeit/Qualität einstellbar machen will: einfach rufen,
    beides wirkt sofort. *Offen* (Prototyp: `SkySetting.DEFAULT`, 15:30 Uhr im Juni; Qualität der
    Welt).
  - `WorldState.timeMs` muss `SystemClock.elapsedRealtime()` sein (so im
    Vertrag) — die Darstellung schreibt damit fort und hält den Fahrer an,
    wenn länger als 500 ms nichts kommt. *Geprüft:* `IndoorRideRuntime`
    nimmt `SystemClock.elapsedRealtime()`.
- **indoor-world:**
  - Die Bodentextur unter Wald (Luftbild bzw. erzeugte Textur) ist dunkel
    blaugrün; mit dem neuen, warmen Licht bleibt der Waldboden das kühlste
    im Bild (`docs/indoor/bilder/licht-kamera/wald-*`). Etwas heller und
    bräunlicher (Laub, Nadelstreu) würde viel bringen. *Teilweise im Material
    ausgeglichen* (siehe „Boden aus der Nähe und Leben“); in der Textur bleibt es besser.
  - Für das Boden-Detail kennt das Gelände-Material die Bodenart nur aus der Luftbildfarbe.
    Eine Landnutzung je Gitterpunkt (z. B. als Vertexfarbe-Alpha von `mat_terrain`) würde
    Acker, Wiese und Wald sicherer trennen — braucht eine Formatänderung, daher nicht hier.
  - Knotennamen bitte wie in `WorldNames` direkt an den Knoten mit Netz oder
    an einem Vorfahren — die Darstellung ordnet Renderables über die Kette der
    Vorfahren zu.
  - Die Modelle `rider.glb`/`ghost.glb` werden benutzt, wenn sie in den
    Assets liegen; die Animationen werden **per Name** (`pedal`, `roll`)
    gesucht. *Beides geprüft* (siehe Integration).
