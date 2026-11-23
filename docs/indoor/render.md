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
| `SkyModel.kt` | Sonnenstand, Licht, Himmelsfarben, SH-Koeffizienten, Himmels-Cubemap, Dunst | nein, getestet |
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
  Ursprungs, unter 8° auf 8° angehoben; Schatten mit 3 Kaskaden, 1024², bis
  300 m. Umgebungslicht über `IndirectLight.Builder().radiance(3, sh)` mit
  selbst integrierten SH-Koeffizienten (Basis exakt wie in Filaments
  `IndirectLight.cpp`, geprüft per „weißer Ofen“-Test), Stärke 26 000 lx bei
  tiefer bis 34 000 lx bei hoher Sonne. Belichtung f/16,
  1/125 s, ISO 100.
- **Himmel:** Cubemap 64² × 6 (`SRGB8_A8`, `setImage(…, depth = 6, …)`),
  Verlauf Horizont → Zenit, **symmetrisch zur Waagrechten** (siehe Annahmen);
  die Sonnenscheibe zeichnet Filament (`Skybox.showSun(true)`). Mit
  `sky = false` eine einfarbige Skybox in Horizontfarbe.
- **Dunst:** `View.fogOptions` mit Dichte aus `haze` (Sichtweite 40 km … 4 km,
  Koschmieder), Farbe = Horizontfarbe, `cutOffDistance` 100 km (Skybox ohne
  Dunst), Höhe relativ zum Fahrer, `heightFalloff` 0,0012/m.
- **Kameras:** CHASE 6 m hinter (auf dem Band zurück, folgt Kurven) und 2,5 m
  über dem höheren von Fahrer und Kamerapunkt, τ = 0,35 s; HANDLEBAR auf
  1,45 m Augenhöhe, Blick 25 m voraus; DRONE 28 m hoch, 22 m seitlich, 18 m
  zurück, τ = 0,9 s. Mindesthöhe über dem Band; Moduswechsel springt.
  Bildwinkel senkrecht 50° (quer) bzw. 72° (hoch), nah 0,3 m, fern 25 km.
- **Qualitätsstufen:** `RenderSettings` wirkt sofort: Schatten (View +
  Sonne), Umgebungsverdeckung, Überstrahlen, Dunst, FXAA, dynamische
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
  Stärke des Umgebungslichts multipliziert (Cubemap und Dunst schon); die
  Farbe wird deshalb selbst mit `ambientLux × Belichtung` skaliert.
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
      verkehrt: Cubemap-Ausrichtung in `SkyModel.cubemapPixels` prüfen.
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
  - Knotennamen bitte wie in `WorldNames` direkt an den Knoten mit Netz oder
    an einem Vorfahren — die Darstellung ordnet Renderables über die Kette der
    Vorfahren zu.
  - Die Modelle `rider.glb`/`ghost.glb` werden benutzt, wenn sie in den
    Assets liegen; die Animationen werden **per Name** (`pedal`, `roll`)
    gesucht. *Beides geprüft* (siehe Integration).
