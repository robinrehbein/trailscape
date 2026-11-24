# Indoor — Vertrag für die parallele Umsetzung

Training auf dem Smart-Trainer zu Hause: mit einem Avatar durch die nachgebaute
3D-Welt der eigenen echten Strecke fahren. Ziel dieses Durchgangs ist ein auf
dem Handy installierbarer Prototyp.

Dieses Dokument ist der **Vertrag** zwischen fünf Slices, die gleichzeitig und
ohne Absprache gebaut werden. Was hier und in den Vertragsdateien unter
`core/src/main/kotlin/de/trailscape/core/indoor/` steht, gilt. Wer davon
abweichen muss, tut es nur innerhalb des eigenen Besitzes und schreibt es in
`docs/indoor/<slice>.md` (Abschnitt 2.6).

Inhalt: 1 Überblick · 2 Regeln für alle · 3 Slices und Besitz · 4 Strecke ·
5 Trainer, Gänge, Sitzung · 6 Belagsklassen · 7 Welt und Darstellung ·
8 Geodaten · 9 Entwicklerwerkzeug · 10 App: Ablauf und Speicherung ·
11 Auf dem Gerät zu prüfen, Entscheidungen, Risiken · 12 Zusammenführen

---

## 1 Überblick

```
                        ┌──────────────────────── :core (reines Kotlin) ─────────────────────────┐
 Rollentrainer ─BLE─► BleLink ─► IndoorTrainer (FTMS│Demo) ─Telemetrie─┐                          │
 Schalttaster  ─BLE─► BleLink ─► IndoorShifter (Zwift Click) ─Schalten─┤                          │
                        │                                              ▼                          │
                        │   IndoorRoute ─────────────────────► IndoorSession.step(nowMs, input)   │
                        │       ▲                                      │ IndoorStep(state,commands)│
                        │       │ WorldTrack.toIndoorRoute             │                          │
 Geodaten ─HTTPS─► GeoFetcher ─► WorldBuilder ─► <welt>/manifest.json + tiles/*.glb               │
                        └───────────────────────────────────────────────┼─────────────────────────┘
                                                                        ▼
   :app  Sitzungs-Laufzeit (ui/indoor) ──► WorldRenderer.setState ──► Filament (indoor/render)
                │                    └───► HUD (Compose) über der 3D-Fläche
                └── finish() → IndoorRideRecord → Ride(indoor = true) → RideStorage
```

Zwei Datenflüsse, die sich nur an zwei Stellen berühren:

1. **Fahren:** Trainer → Sitzung → Darstellung/HUD → Speicherung. Die Sitzung
   ist ein reiner Automat in `:core`; die Laufzeit in `:app` ruft sie etwa
   zehnmal je Sekunde.
2. **Welt:** Geodaten → Weltbau → GLB-Kacheln + Manifest → Darstellung. Der
   Weltbau ist reines Kotlin/JVM; **glTF ist das Austauschformat**. Dadurch
   lässt sich die Welt in der Entwicklungsumgebung ansehen (three.js in
   headless Chrome), obwohl die Filament-Darstellung nur auf dem Handy läuft.

Berührungspunkte: Die Welt liefert der Sitzung das **Streckenband** (damit das
gefühlte Gefälle dem gezeichneten entspricht), und die Sitzung liefert der
Darstellung die **Laufmeter** des Fahrers.

Jeder Teil funktioniert ohne den anderen: Die Fahrt läuft ohne Welt (nur HUD),
die Welt lässt sich ohne Trainer ansehen (Demo-Trainer), beides ohne Netz
(Testwelt aus dem Weltbau, Testszene der Darstellung).

---

## 2 Regeln für alle Slices

### 2.1 Vertragsdateien (eingefroren)

| Datei (`core/…/indoor/`) | Inhalt | Besitzer |
|---|---|---|
| `IndoorSurface.kt` | `SurfaceKind`, Crr, Zuordnung aus OSM-Merkmalen | indoor-core |
| `IndoorRoute.kt` | `IndoorRoute`, `SurfaceSection`, `sampleAt` | indoor-core |
| `IndoorTrainer.kt` | `BleLink`, `IndoorTrainer`, `TrainerCommand`, `TrainerTelemetry`, `DemoTrainer`, `IndoorShifter`, `IndoorUuids` | indoor-core |
| `IndoorSession.kt` | `IndoorSession`, `IndoorSessionConfig`, `IndoorSessionState`, `ErgWorkout`, `GhostSpec`, `IndoorRideRecord` | indoor-core |
| `world/WorldManifest.kt` | `WorldManifest` samt JSON-Format, `GeoOrigin`, `WorldTrack`, `WorldTile` | indoor-world |
| `world/WorldContract.kt` | `IndoorQuality`, `WorldNames`, `WorldModels`, `GeoFetcher`, `RasterDecoder`, `WorldBuilder`, Positivliste | indoor-world |
| `world/WorldRenderer.kt` | `WorldRenderer`, `WorldState`, `RenderSettings`, Ereignisse | indoor-render |

„Eingefroren“ heißt: Kein Typ, kein Feld, keine Signatur, kein Enum-Name und
keine Konstante wird geändert oder entfernt. Der Besitzer darf **ergänzen**
(neue Funktion, neues Feld mit Vorgabewert am Ende), wenn nur sein eigener
Slice es braucht, und berichtigt Tippfehler im KDoc. Einzige Wertänderung, die
ausdrücklich erlaubt ist: die Zwift-UUIDs in `IndoorUuids`, falls die Quellen
von indoor-core etwas anderes sagen.

Die Vertragsdateien kompilieren (vom Architekten mit dem Kotlin-Compiler ohne
Gradle geprüft; Manifest-Rundreise, `poseAt`, `sampleAt` und die Positivliste
sind einmal durchgelaufen). Tests dazu schreibt der jeweilige Besitzer.

### 2.2 Einstiegsdateien (Platzhalter, die der Besitzer ersetzt)

Die Slices rufen einander nur über diese Funktionen auf. Der Architekt hat sie
mit Platzhaltern angelegt, damit jeder Branch für sich baut:

| Datei | Besitzer | Funktionen | Platzhalter |
|---|---|---|---|
| `core/…/indoor/IndoorCoreEntry.kt` | indoor-core | `buildIndoorRoute`, `surfaceRematchWaypoints`, `riderSetupFrom`, `ergWorkoutFor`, `createIndoorSession`, `createFtmsTrainer`, `createDemoTrainer`, `createZwiftClickShifter` | wirft `NotImplementedError` |
| `core/…/indoor/world/IndoorWorldEntry.kt` | indoor-world | `aerialSources`, `createGeoPipeline`, `createWorldBuilder` | wirft `NotImplementedError` (Register enthält NRW) |
| `app/…/sensors/indoor/IndoorDevices.kt` | indoor-ble | `IndoorDevices.remembered/openLink/forget`, `IndoorDevicesSection()` | läuft, kennt kein Gerät |
| `app/…/indoor/render/IndoorRenderEntry.kt` | indoor-render | `createWorldRenderer`, `WorldSurface()`, `IndoorDiagnosticsScreen()` | läuft, meldet `onFailure` |

Der Besitzer ersetzt nur die **Rümpfe**; Namen, Parameter, Rückgabetypen
bleiben. Die Umsetzung selbst gehört in eigene Dateien desselben Pakets.

**Folge für Tests:** Auf dem eigenen Branch sind die fremden Einstiege noch
Platzhalter. Wer fremde Teile im Test braucht, baut eine Attrappe gegen die
Vertragsschnittstelle (`IndoorSession`, `IndoorTrainer`, `BleLink`,
`WorldRenderer`, `WorldBuilder`, `GeoFetcher`) — nie gegen einen Einstieg.

### 2.3 Besitz

Jede Datei hat genau einen Besitzer (Abschnitt 3). **Kein Slice ändert eine
Datei eines anderen** — auch nicht „nur eine Zeile“. Was man in einer fremden
Datei bräuchte, steht entweder schon da (Vorab-Änderungen, Abschnitt 3.6) oder
kommt in den Abschlussbericht des Slices unter „Bitte an <Besitzer>“.

Neue Dateien nur in den eigenen Verzeichnissen. Alles, was in keiner Tabelle
steht, gehört **indoor-app**.

### 2.4 Bauen und Testen

- Befehle: `./gradlew :core:test` · `./gradlew :app:testDebugUnitTest` ·
  `./gradlew :app:assembleDebug` · `./gradlew :indoor-dev:run --args="…"`.
- Nie mehr als ein Gradle-Lauf gleichzeitig. Maven Central antwortet manchmal
  mit HTTP 429: rund 45 s warten, bis zu achtmal wiederholen.
- Tests werden nie übersprungen oder deaktiviert. Kein Force-Push, keine
  Secrets, keine Analytics, kein Konto.
- `:core`-Hauptquellen bleiben android-frei **und** ohne `javax.imageio` /
  `java.awt` (gibt es auf Android nicht). In `:core`-Tests und in
  `tool/indoor-dev` ist `ImageIO` erlaubt.
- `:core` hat weder Coroutines noch `@Serializable`-Zwang: JSON von Hand über
  `JsonObject` (`JsonSupport.kt`), Zeit über `tick(nowMs)`/`step(nowMs)`.

### 2.5 Texte

- UI-Texte Deutsch (du-Form, ruhig, präzise, echte Umlaute) **und** Englisch,
  Regeln in `docs/i18n.md` (Schlüsselschema, Platzhalter, Plurals, Glossar).
- Je Slice mit Oberfläche eine eigene Datei in `values/` und `values-en/`:

  | Datei | Präfix | Slice |
  |---|---|---|
  | `strings_indoor_app.xml` | `indoor_app_` | indoor-app |
  | `strings_indoor_ble.xml` | `indoor_ble_` | indoor-ble |
  | `strings_indoor_render.xml` | `indoor_render_` | indoor-render |

  Die Präfixe sind im `StringResourceParityTest` schon eingetragen, und die
  sechs Dateien liegen leer bereit (der Test verlangt, dass es sie gibt) —
  jeder Slice füllt nur seine eigene. Niemand ergänzt andere `strings_*.xml`.
- `:core` erzeugt für Indoor **keine** Sätze: Die Slices indoor-core und
  indoor-world liefern Typen und Zahlen, `core/**/i18n` bleibt unberührt.
  Diagnosetexte (`reason`, `detail`, Ausnahmen) sind Deutsch und gehen nie
  ungefiltert in die Oberfläche.
- Keine Zwift-Marken oder -Logos in der Oberfläche. Erlaubt ist allein der
  Gerätename, wie das Gerät ihn selbst meldet, und die Bezeichnung
  „Schalttaster“ / „shifter“.
- Kommentare und KDoc auf Deutsch im Stil der umgebenden Datei (im Code
  `ae/oe/ue`, in `.md` und String-Ressourcen echte Umlaute).

### 2.6 Notizen je Slice

Jeder Slice führt `docs/indoor/<slice>.md` (z. B. `docs/indoor/render.md`) mit
drei Abschnitten: **Abweichungen vom Vertrag** (mit Begründung),
**Annahmen**, **Auf dem Gerät zu prüfen** (als Checkliste `- [ ]`). indoor-ble
verlinkt alle fünf Dateien aus `docs/geraete-pruefliste.md` (Abschnitt
„Indoor“) — die Dateinamen stehen damit fest: `core.md`, `ble.md`, `world.md`,
`render.md`, `app.md`.

### 2.7 Datenschutz

Local-first, kein Konto, keine Telemetrie. Das Feature erzeugt Netzverkehr
**nur** nach der Nutzeraktion „Welt laden“ und nur an die Positivliste
(Abschnitt 8). Bluetooth-Verbindungen bestehen nur, solange der Indoor-Ablauf
oder Mehr → Sensoren offen ist. Jede neue Anfrage steht in `PRIVACY.md`
(Besitzer indoor-app; die anderen melden Hosts im Abschlussbericht).

---

## 3 Slices und Besitz

### 3.1 indoor-core — Logik in `:core`

**Besitz:** `core/src/main/kotlin/de/trailscape/core/indoor/*.kt` (ohne den
Unterordner `world/`), `core/src/test/kotlin/de/trailscape/core/indoor/*`
(ohne `world/`), `core/src/test/resources/indoor/` (ohne `world/`), dazu die
bestehenden Dateien `core/…/RouteSurface.kt`, `core/…/Routing.kt`,
`core/…/OfflineRouting.kt` und deren Tests; `docs/indoor/core.md`.

**Auftrag:** Abschnitte 4, 5 und 6. Alle acht Einstiege aus
`IndoorCoreEntry.kt`. In den bestehenden Dateien nur: `parseBrouterGeoJson`
und `concatRouteLegs` füllen `PlannedRoute.waySections` (Feld ist angelegt),
`RouteSurface.kt` bekommt die Zerlegung der `messages` in `RouteWaySection`
— `surfaceBreakdownFromMessages` und `classifySurface` bleiben im Verhalten
unverändert.

### 3.2 indoor-ble — Funk in `:app`

**Besitz:** `app/src/main/kotlin/de/trailscape/app/sensors/**` (einschließlich
des neuen `sensors/indoor/`), `app/…/ui/more/SensorsCard.kt`,
`app/src/test/kotlin/de/trailscape/app/sensors/**`,
`res/values[-en]/strings_indoor_ble.xml`, `docs/geraete-pruefliste.md`,
`docs/indoor/ble.md`.

**Auftrag:** Abschnitt 5.1 und 5.5. `GattBleLink` (das Rohr), Suche nach
Trainer (Dienst 0x1826) und Schalttaster, Koppeln/Vergessen, Berechtigungen,
`IndoorDevicesSection()`, Einbettung in Mehr → Sensoren.

### 3.3 indoor-world — Weltbau in `:core` und Werkzeug

**Besitz:** `core/src/main/kotlin/de/trailscape/core/indoor/world/**`,
`core/src/test/kotlin/de/trailscape/core/indoor/world/**`,
`core/src/test/resources/indoor/world/**`, `tool/indoor-dev/**`,
`app/src/main/assets/indoor/**` (Modelle, Lizenznachweise),
`docs/indoor/world.md`.

**Auftrag:** Abschnitte 7.1–7.5, 8 und 9. `createWorldBuilder`,
`createGeoPipeline`, Register `aerialSources`, die Modelle `rider.glb` und
`ghost.glb`.

### 3.4 indoor-render — Filament in `:app`

**Besitz:** `app/src/main/kotlin/de/trailscape/app/indoor/render/**`,
`app/src/test/kotlin/de/trailscape/app/indoor/render/**`,
`res/values[-en]/strings_indoor_render.xml`, `app/proguard-rules.pro`,
`docs/indoor/render.md`.

**Auftrag:** Abschnitte 7.6–7.9. `createWorldRenderer`, `WorldSurface()`,
`IndoorDiagnosticsScreen()`.

### 3.5 indoor-app — Ablauf, Laufzeit, Speicherung, Doku

**Besitz:** `app/…/ui/indoor/**`, `app/…/indoor/**` **außer** `indoor/render/`,
die Tests dazu, `res/values[-en]/strings_indoor_app.xml`, und **alle
geteilten Dateien**, die nicht oben einem anderen gehören — insbesondere:
`app/build.gradle.kts`, `AndroidManifest.xml`, `settings.gradle.kts`,
`res/xml/*`, `TrailscapeApp.kt`, `AppViewModel.kt`, `AppServices.kt`,
`OkHttpClientAdapter.kt`, `RideStorage.kt`, alles unter `ui/today`, `ui/map`,
`ui/rides`, `ui/training`, `ui/more` (ohne `SensorsCard.kt`), `record/**`,
`strava/**`, `OpenSourceNotices.kt`, `core/…/Models.kt` und jede andere
bestehende `:core`-Datei außerhalb von 3.1 (`RiddenWays.kt`,
`ExplorerTiles.kt`, `RouteMatch.kt`, `RideSegments.kt`, `Export.kt`,
`StravaClient.kt`, `TrainingLoad.kt` …), `StringResourceParityTest.kt`,
`ScreenshotTest`-Klassen, `README.md`, `PRIVACY.md`, `docs/TODO.md`,
`docs/indoor/app.md`.

**Auftrag:** Abschnitt 10.

### 3.6 Vorab-Änderungen des Architekten (schon im Arbeitsbaum)

| Datei | Änderung |
|---|---|
| `app/build.gradle.kts` | Filament 1.75.1: `filament-android`, `gltfio-android`, `filament-utils-android` |
| `AndroidManifest.xml` | `<uses-feature android:glEsVersion="0x00030000" required="false">`; Kommentar zu den Bluetooth-Berechtigungen |
| `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | `<filesDir>/indoor` vom Backup ausgenommen |
| `settings.gradle.kts`, `tool/indoor-dev/` | JVM-Modul `:indoor-dev` (Platzhalter-`Main.kt`) |
| `core/…/Models.kt` | `Ride.indoor`, `RideSummary.indoor`, `RideInfo.indoor`, `outdoorRides()`; `PlannedRoute.waySections`, `RouteWaySection` |
| `app/…/sensors/BleSensors.kt` | `BleNutzer.INDOOR` |
| `StringResourceParityTest.kt` | Präfixe der drei Indoor-Dateien |
| `res/values[-en]/strings_indoor_{app,ble,render}.xml` | leer angelegt, damit der Paritätstest auf jedem Branch grün ist |

Neue Berechtigungen braucht das Feature nicht: `BLUETOOTH_SCAN`
(`neverForLocation`), `BLUETOOTH_CONNECT`, `INTERNET` sind deklariert. Kein
Vordergrunddienst im Prototyp (Abschnitt 11.2).

---

## 4 Strecke

`IndoorRoute` (Zusicherungen im KDoc von `IndoorRoute.kt`) entsteht auf drei
Wegen; die App wählt den besten, der zur Quelle passt:

| Quelle der Strecke | Belag über | `IndoorRouteSource` |
|---|---|---|
| Gerade geplante Runde (`PlannedRoute` im Speicher) | `PlannedRoute.waySections` | `BROUTER_WAY_TAGS` |
| Gespeicherte Planung oder gefahrene Tour (`Ride`, nur Punkte) | Abgleich: mit `surfaceRematchWaypoints(points)` durch den vorhandenen Router (offline zuerst), `waySections` der Antwort | `BROUTER_REMATCH` |
| Abgleich scheitert (kein Routing-Segment, kein Netz) | Weltbau schätzt aus den Vektorkacheln | `VECTOR_TILES` |
| nichts davon | ein Abschnitt `UNKNOWN` | `NONE` |

- `buildIndoorRoute` tastet neu ab (Abstand 1–15 m), ergänzt und glättet die
  Höhen (Steigung je Stück in −25..+25 %), streckt `waySections` anteilig auf
  die Linienlänge und fasst gleichartige Nachbarn zusammen. `isLoop`, wenn
  Start und Ziel höchstens 60 m auseinanderliegen.
- Der Abgleich ist eine Routing-Anfrage **mit den vorhandenen Mitteln** der App
  (kein neuer Host). Er läuft nur im Schritt „Welt laden“ bzw. beim Start einer
  Fahrt — also nach einer Nutzeraktion.
- **Laufmeter sind unantastbar.** Der Weltbau glättet Lage und Höhe, behält
  aber `distM` jeder Stelle bei. Nur so zeigen HUD, Geist, Aufzeichnung und
  3D-Bild dieselbe Stelle.
- Sobald eine Welt geladen ist, fährt die Sitzung mit
  `manifest.track.toIndoorRoute(manifest.origin, routeId, routeName)`: gleiche
  Laufmeter, Höhen der gezeichneten Fahrbahn, aufgelöste Beläge. Ohne Welt
  fährt sie mit der ursprünglichen `IndoorRoute`.

---

## 5 Trainer, Gänge, Sitzung

### 5.1 Das Rohr (`BleLink`, indoor-ble)

Die Android-Seite kennt **kein Protokoll**. Sie verbindet, erkennt Dienste,
reiht Operationen ein und reicht Bytes durch. Verbindlich (Details im KDoc):

- eine FIFO-Warteschlange für `subscribe`/`read`/`write`/`requestMtu`, jede
  Operation endet mit `onOperationResult` (spätestens nach 5 s als Fehler);
- alle Listener-Aufrufe von einem Thread (dem vorhandenen `trailscape-ble`);
- nach Abbruch selbst neu verbinden (Takt `neuerVersuchNachMs` aus
  `BleSensorLogik.kt`, Status 133 wie in `BleSensorConnection`), aber keine
  Abonnements wiederherstellen — das tut die Protokollschicht nach jedem
  `READY`;
- `connectGatt` mit `TRANSPORT_LE`, kein Bonding, `autoConnect = false`;
- Indications über CCCD `0x0002`, Notifications `0x0001`; ab API 33 die neuen
  `writeCharacteristic`/`writeDescriptor`-Varianten, darunter die alten.

Suche: Trainer über den Dienstfilter `IndoorUuids.FTMS_SERVICE`; Schalttaster
über `IndoorUuids.ZWIFT_SERVICE` **oder** die Herstellerkennung
`ZWIFT_COMPANY_ID` (manche Geräte bewerben den Dienst nicht). Je Rolle
(`IndoorDeviceRole`) wird höchstens ein Gerät gemerkt, im Format von
`kodiereGemerkteSensoren` sinngemäß (eigener Prefs-Schlüssel
`trailscape.indoor.devices`).

### 5.2 FTMS (indoor-core)

`createFtmsTrainer(link)` kapselt den Fitness Machine Service vollständig:

| Characteristic | Nutzung |
|---|---|
| Feature `0x2ACC` (read) | Fähigkeiten → `TrainerCapabilities` |
| Supported Power Range `0x2AD8`, Resistance Range `0x2AD6` (read) | Grenzen |
| Indoor Bike Data `0x2AD2` (notify) | Flags-gesteuert: Tempo, Trittfrequenz (0,5 U/min), Leistung (sint16 W) … → `TrainerTelemetry` |
| Control Point `0x2AD9` (write + indicate) | `0x00` Request Control, `0x01` Reset, `0x05` Set Target Power (sint16 W), `0x07` Start/Resume, `0x08` Stop/Pause (1 = Stopp, 2 = Pause), `0x11` Simulation Parameters (Wind sint16 0,001 m/s · Steigung sint16 0,01 % · Crr uint8 0,0001 · Cw uint8 0,01 kg/m); Antwort `0x80 <opcode> <result>` |
| Machine Status `0x2ADA` (notify) | u. a. „Control Permission Lost“ → Kontrolle neu anfordern |

Automat: `READY` (Rohr) → Feature/Ranges lesen → Control Point abonnieren →
Request Control → `TrainerConnection.READY` → gemerkte Befehle nachschicken.
Jeder Control-Point-Befehl wartet auf seine Indication (Frist 3 s, zweimal
wiederholen, dann `onCommandFailed`); höchstens ein Befehl unterwegs; je
Befehlsart zählt nur der neueste. „Control Not Permitted“ → einmal neu
anfordern. Fehlt der Dienst oder scheitert Request Control dauerhaft:
`UNSUPPORTED`.

`IndoorTrainer`-Implementierungen sind **threadsicher**: `tick`/`send` kommen
von der Laufzeit-Schleife, Rohr-Ereignisse vom BLE-Thread.

### 5.3 Virtuelle Gänge und Widerstandsmodell (Entscheidung bei indoor-core)

24 Gänge (`VIRTUAL_GEAR_COUNT`), gerechnet in der App — das Cog hat ein
Ritzel. Zwei bekannte Wege; indoor-core wählt, begründet in
`docs/indoor/core.md` und hält die Logik rein und getestet:

- **(a) Simulation mit umgerechneter Steigung.** Die Bremskraft am Rad soll
  `Fahrwiderstand × virtuelle Übersetzung / reale Übersetzung` sein
  (`RiderSetup.realGearRatio`). Gesendet wird `SetSimulation` mit einer
  Ersatzsteigung, die der Trainer in genau diese Kraft übersetzt. Die vom
  Trainer angenommene Masse ist unbekannt → `RiderSetup.trainerScale` als
  kalibrierbarer Faktor.
- **(b) Zielleistung nachführen.** `SetTargetPower` laufend aus
  Trittfrequenz × virtueller Übersetzung und der Fahrphysik.

Der Vertrag trägt beide: `TrainerCommand.SetSimulation` und
`SetTargetPower`, `TrainerTelemetry.speedKmh` (Schwungradtempo) und
`cadenceRpm`. Unabhängig vom Weg gilt: **Das Tempo der virtuellen Fahrt
rechnet immer die Sitzung aus der gemessenen Leistung** (Masse, CdA, Crr des
Belags, Steigung der Strecke, Wind, Trägheit) — nie aus dem Schwungradtempo.
`RiderSetup.difficulty` dämpft nur, was der Trainer spürt.

### 5.4 Schalttaster (indoor-core, best effort)

Proprietäres, von der Community dokumentiertes Protokoll. Quellen, gegen die
indoor-core prüft: die Projekte „SwiftControl/BikeControl“ und „zwiftplay“
(ajchellew) sowie die Protokoll-Artikel im Blog von Makinolo. Handschlag
(„RideOn“ über `ZWIFT_SYNC_RX`, Antwort über `ZWIFT_SYNC_TX`), danach
Tastenereignisse über `ZWIFT_ASYNC`. Manche Firmware verschlüsselt (ECDH +
AES-CCM); geht das nicht mit vertretbarem Aufwand in reinem Kotlin, meldet der
Taster `UNSUPPORTED` — die Knöpfe auf dem Bildschirm sind der Rückfall und
immer da. Der Parser ist rein und mit aufgezeichneten Byte-Beispielen aus den
Quellen getestet.

### 5.5 Demo-Trainer

`createDemoTrainer()` gehört fest dazu: `READY` nach etwa einer Sekunde,
Messwerte im Sekundentakt aus `tick`, Leistung/Trittfrequenz über die Felder
`riderPowerW`/`riderCadenceRpm` (Regler im HUD, indoor-app), im ERG-Modus der
Zielleistung nachlaufend. Er braucht weder Bluetooth noch Berechtigung und ist
der Trainer aller Screenshot- und Laufzeit-Tests.

### 5.6 Sitzung

`createIndoorSession(config)` → `IndoorSession` (Vertrag im KDoc). Kern:

- **Physik:** `P·η = v·(m·g·(sin θ + Crr·cos θ) + ½·ρ·CdA·(v + v_wind)²) + m·v·dv/dt`,
  Luftdichte über `airDensity(Höhe)` und `gravity` aus `TrainingLoad.kt`,
  integriert mit dem Schrittabstand, höchstens 2 s je Schritt. Crr je Belag über
  `effectiveCrr(kind, profileCrr)`.
- **Auto-Pause:** keine Leistung und Tempo unter 1 km/h für 3 s, oder der
  Trainer ist länger als 5 s nicht `READY` → `AUTO_PAUSED`; weiter mit dem
  ersten Tritt.
- **Runden:** `isLoop` → nach dem Ziel beginnt Runde 2; sonst `FINISHED` am Ziel.
- **Geist:** `ConstantPower` fährt dieselbe Physik; `PastRide` wird über die
  Laufmeter der Strecke auf eine Zeit-Weg-Kurve abgebildet.
- **ERG:** Zielleistung je Abschnitt (`ErgStep.ftpFraction × ftpW`), geklemmt
  auf `TrainerCapabilities.min/maxPowerW`; `adjustErg` verschiebt dauerhaft.
- **Aufzeichnung:** ein `TrackPoint` je gefahrener Sekunde (virtuelle Lage und
  Höhe, Wanduhrzeit, `power`, `cad`, `hr`), `stats` über `computeStats`.

### 5.7 Laufzeit (indoor-app)

Eine Schleife mit 10 Hz auf einem eigenen, seriellen Dispatcher:

```
trainer.tick(now); shifter?.tick(now)
step = session.step(now, SessionInput(letzteTelemetrie, hr, trainerReady))
step.commands.forEach(trainer::send)
renderer.setState(WorldState(now, ActorState(…), ghost, paused))
zustandFürHud.value = step.state
```

`now` = `SystemClock.elapsedRealtime()`. Telemetrie und Schaltereignisse
kommen vom BLE-Thread und werden nur abgelegt (`@Volatile`/Queue). Puls über
`BleSensors.acquire(context, BleNutzer.INDOOR)` und `BleSensors.kanaele`.

---

## 6 Feine Belagsklassen

Eine Liste für Physik **und** Welt (`SurfaceKind` in `IndoorSurface.kt`).

| `SurfaceKind` | OSM `surface` | `tracktype` | `highway` ohne Belag | Crr | Breite |
|---|---|---|---|---|---|
| `ASPHALT` | asphalt, paved, chipseal | grade1 | Kraftverkehrsstraßen (motorway … service, road) | 0,0055 | 5,0 m |
| `CONCRETE` | concrete, concrete:plates, concrete:lanes | | | 0,0060 | 3,5 m |
| `PAVING_STONES` | paving_stones, bricks, grass_paver | | | 0,0075 | 3,0 m |
| `COBBLESTONE` | sett, cobblestone, unhewn_cobblestone | | | 0,0110 | 4,0 m |
| `WOOD` | wood, metal | | | 0,0070 | 2,5 m |
| `COMPACTED` | compacted, fine_gravel | grade2 | | 0,0080 | 3,0 m |
| `GRAVEL` | gravel, pebblestone, unpaved, rock, stone | grade3 | track | 0,0120 | 3,0 m |
| `DIRT` | ground, dirt, earth, clay | grade4 | path, bridleway | 0,0160 | 1,5 m |
| `GRASS` | grass | grade5 | | 0,0250 | 2,0 m |
| `SAND` | sand, mud | | | 0,0350 | 2,0 m |
| `UNKNOWN` | — | | cycleway, footway, pedestrian … | 0,0080 | 3,0 m |

- Reihenfolge der Zuordnung wie in `classifySurface`: `surface` →
  `tracktype` → `highway` (`surfaceKindFromTags`).
- **Crr-Werte** sind begründete Annahmen für einen rund 40 mm breiten
  Gravelreifen bei 2,5–3 bar: Asphalt aus den Trommelmessungen von
  bicyclerollingresistance.com mal dem üblichen Aufschlag zur echten Straße;
  lose Beläge in der Größenordnung, die Zwift für Gravelreifen ansetzt
  (Asphalt 0,008 · Schotter 0,012 · Erde 0,018) und die Feldmessungen von
  Silca/Tom Anhalt nahelegen. `UNKNOWN` = `defaultCrr` (0,008) aus
  `TrainingLoad.kt`, damit eine Strecke ohne Belagsdaten rechnet wie bisher.
- Wirksam ist `kind.crr × (Profil.crr / 0,008)` — wer im Profil einen anderen
  Reifen einträgt, verschiebt alle Beläge im selben Verhältnis.
- `UNKNOWN` sieht aus wie `COMPACTED` (`SurfaceKind.visual`).
- Rückfall aus Vektorkacheln (`surfaceKindFromVectorTile`): Das
  OpenMapTiles-Schema kennt nur `paved`/`unpaved` und die Wegklasse — daraus
  werden höchstens `ASPHALT`, `GRAVEL`, `DIRT`.
- Material in der GLB: `mat_route_<textureKey>`; die Belagstextur ist
  kachelbar und wird mit `REPEAT` über die Fahrbahn gelegt.

---

## 7 Welt und Darstellung

### 7.1 Koordinaten

- **ENU in Metern** um `GeoOrigin` (Mitte des Umgebungsrechtecks der Strecke,
  `eleM` = Höhe des Streckenstarts): `e` Ost, `n` Nord, `u` oben.
- Abbildung: `e = Δlon·cos(lat₀)·R`, `n = Δlat·R`, `R = 6 371 000 m`
  (`GeoOrigin.toEast/toNorth`). **Alles** geht durch diese Funktionen. Die
  Näherung verzerrt in 50 km Abstand um etwa 1 % — überall gleich, deshalb
  passt es zusammen. Sie ist in Länge und Breite linear: Ein Luftbild mit
  `CRS=EPSG:4326` über das Rechteck einer Kachel liegt exakt; `EPSG:3857` ist
  zulässig, solange der Fehler unter einem Pixel bleibt.
- **Höhenbezug:** Meter über dem Meer aus den Terrarium-Kacheln;
  `u = Höhe − origin.eleM`. Die Höhen der Strecke (BRouter/GPS) werden nur
  relativ verwendet: Der Weltbau legt die Fahrbahn auf das Gelände.
- **glTF:** rechtshändig, Y oben: `X = e`, `Y = u`, `Z = −n`. Eckpunkte einer
  Kachel sind relativ zu ihrem Anker (`WorldTile.anchorE/N/U`, die
  Kachelmitte); die Darstellung verschiebt um `(anchorE, anchorU, −anchorN)`.
  Für die Genauigkeit hält die Darstellung die Kamera nahe am Ursprung ihres
  Wurzelknotens (Welt gegen die Kamera verschieben), nicht umgekehrt.

### 7.2 Kacheln und Detailstufen

Ein festes Gitter im ENU-System, am Ursprung ausgerichtet:

| Stufe | Kante | Inhalt | geladen |
|---|---|---|---|
| 0 „nah“ | 256 m | Gelände fein, Fahrbahn mit Rändern, übrige Wege, Gebäude, Bäume, Wasser, Kleinteile | Korridor um die Stelle des Fahrers |
| 1 „mittel“ | 1024 m | Gelände mittel, Wald als Fläche/Volumen, große Gebäude grob | einige Kilometer |
| 2 „fern“ | 4096 m | Gelände grob | bis zur Sichtweite |

- Kachel `(ix, iy)` der Stufe `L` bedeckt `e ∈ [ix·c, (ix+1)·c)`,
  `n ∈ [iy·c, (iy+1)·c)` mit `c = worldCellM(L)`. ID und Dateiname
  `L<L>_<ix>_<iy>` (`worldTileId`), Datei `tiles/<id>.glb`.
- **Übergang ohne eigene Shader:** Das Gelände einer Kachel ab Stufe 1 ist in
  4×4 Teilflächen `terrain_sub_<i>_<j>` zerlegt; jede deckt genau eine Kachel
  der nächstfeineren Stufe. Die Darstellung blendet eine Teilfläche aus,
  **solange** die feinere Kachel fertig geladen und sichtbar ist — erst
  einblenden, dann ausblenden, nie ein Loch. Jede Geländefläche hat ringsum
  eine senkrechte Schürze (5–30 m nach unten), die Spalten zwischen
  Detailstufen verdeckt.
- **Wann eine Kachel gebraucht wird**, rechnet der Weltbau:
  `WorldTile.show` sind Bereiche der Laufmeter innerhalb der Runde. Die
  Darstellung lädt, was `neededAt(routeDistM)` meldet (mit etwas Vorlauf in
  Fahrtrichtung), und entlädt den Rest. Sie braucht keine Raumlogik, und das
  Speicherbudget ist dadurch **beweisbar**: `WorldBudget.maxTextureBytes` ist
  das Maximum über alle Stellen.

### 7.3 Inhalt einer Kachel-GLB

Namen in `WorldNames`. Eine Szene, darunter höchstens je ein Knoten:

| Knoten | Inhalt | Schatten |
|---|---|---|
| `terrain` (+ `terrain_sub_i_j`) | Geländenetz, Material `mat_terrain` mit der Bodentextur | empfängt |
| `route` | Fahrbahn der Strecke je Belag (`mat_route_<key>`), Ränder mit weichem Übergang ins Gelände | empfängt |
| `roads` | übrige Straßen und Wege, flach auf dem Gelände | empfängt |
| `buildings` | Grundriss × Höhe, Dach (`mat_building_wall`, `mat_building_roof`) | wirft + empfängt |
| `trees` | Einzelbäume nah, Waldkörper fern (`mat_tree_trunk`, `mat_tree_leaves`) | wirft |
| `water` | Wasserflächen (`mat_water`) | empfängt |
| `props` | Kleinteile | — |

Verbindlich für **jede** GLB (Kacheln und Modelle), weil die Darstellung nur
den mitgelieferten Ubershader hat:

- glTF 2.0 binär, eine Datei, alles eingebettet. Nur Kernmaterial
  (Metallic-Roughness) und `KHR_materials_unlit`. `metallicFactor = 0`,
  `roughnessFactor ≥ 0,6` (es gibt keine Spiegelungs-Umgebung). Zusätzlich
  erlaubt: `KHR_materials_specular` mit `specularFactor = 0` (nur in
  `extensionsUsed`) für Vegetation — schaltet den Sonnenglanz auf Blattkarten
  und Rinde ab; gltfio wertet es im Ubershader aus. Keine weiteren
  Erweiterungen — insbesondere **kein** `EXT_mesh_gpu_instancing`,
  `KHR_texture_basisu`, `KHR_draco_mesh_compression`, `EXT_texture_webp`.
- Texturen: JPEG **Baseline** (kein progressives JPEG — der Decoder von gltfio
  kann es nicht) oder PNG, Kantenlängen Zweierpotenzen, höchstens 2048.
  Luftbilder werden unverändert eingebettet; liefert ein Dienst progressives
  JPEG (Marker `FF C2`), wird als PNG neu angefragt.
- Attribute: `POSITION`, `NORMAL`, `TEXCOORD_0`, optional `COLOR_0` (RGBA,
  wird mit der Grundfarbe multipliziert: Abtönung von Bäumen und Fassaden,
  Alpha für weiche Ränder mit `alphaMode = BLEND`). Laub: `alphaMode = MASK`,
  `doubleSided`. Keine Tangenten nötig (keine Normal-Maps im Prototyp).
- Bäume sind **in die Kachel gebacken** (ein Netz je Material), nicht
  instanziert.
- Kacheln: keine Kameras, Lichter, Animationen, Skins.
- Was über die Namensliste hinausgeht, heißt `x_…`.

**Nah und fern.** Luftbilder tragen aus der Ferne; aus zwei Metern sind 25 cm
je Pixel Matsch. Deshalb liegt in Stufe 0 über dem Luftbild erzeugte
Nahgeometrie: die Fahrbahn mit kachelnder Belagstextur, Ränder (Bankett,
Gras) mit Alpha-Übergang, Bäume und Gebäude als Körper. Ohne Luftbild
(`WorldTile.aerial = false`) ist die Bodentextur aus der Landnutzung erzeugt
(Wiese, Acker, Wald, Siedlung, Wasser), in derselben Auflösung.

### 7.4 Texturgrößen und Speicherbudget

Texturen liegen unkomprimiert im Grafikspeicher: `Breite × Höhe × 4 × 4/3`
Bytes (`textureGpuBytes`) — 1024² sind 5,6 MB, 2048² 22 MB.

| `IndoorQuality` | Stufe 0 | Stufe 1 | Stufe 2 | Texturen gleichzeitig | Dreiecke gleichzeitig | Sichtweite |
|---|---|---|---|---|---|---|
| `LOW` | 512 px (50 cm) | 512 px (2 m) | 256 px (16 m) | ≤ 128 MB | ≤ 300 000 | 6 km |
| `MEDIUM` | 1024 px (25 cm) | 512 px (2 m) | 512 px (8 m) | ≤ 256 MB | ≤ 700 000 | 12 km |
| `HIGH` | 1024 px (25 cm) | 1024 px (1 m) | 512 px (8 m) | ≤ 400 MB | ≤ 1 500 000 | 20 km |

Die Grenzen sind verbindlich; der Weltbau wählt Korridorbreite, Ringe und
Baumdichte so, dass er sie einhält, rechnet nach und schreibt das Ergebnis
nach `WorldBudget`. Richtwerte zum Start (MEDIUM): Stufe 0 im Korridor ±250 m,
700 m voraus und 250 m zurück; Stufe 1 bis 4 km; Stufe 2 bis zur Sichtweite.
Platte: Richtwert höchstens 15 MB je Streckenkilometer (MEDIUM).

### 7.5 Streckenband und Modelle

- `WorldTrack` (im Manifest): Proben alle `stepM` (üblich 5 m) als `e`, `n`,
  `u`; `u` ist die **Fahrbahnoberfläche**. `poseAt(distM)` liefert Lage,
  Richtung, Steigung. Der Fahrer fährt rechts der Mitte (höchstens halbe
  Wegbreite minus 0,4 m), der Geist links davon — die Darstellung entscheidet.
- Modelle `rider.glb`, `ghost.glb` unter `app/src/main/assets/indoor/models/`
  (`WorldModels`): Ursprung am Boden zwischen den Rädern, Blick nach +Z,
  Lebensgröße; Animation `pedal` = eine Kurbelumdrehung in 1,0 s (Kurbel,
  Pedale, Beine), `roll` = eine Radumdrehung in 1,0 s, Abrollumfang 2,2 m.
  Erzeugt vom Weltbau-Werkzeug oder CC0-Modelle mit Lizenznachweis in
  `app/src/main/assets/indoor/models/LICENSES.md`. Zusammen höchstens 60 000
  Dreiecke und 2 MB.
- `WorldBuilder.buildTestWorld` baut ohne Netz eine erfundene Runde von etwa
  3 km mit allen Belagsklassen — die gemeinsame Testwelt für Darstellung,
  Demo und Screenshots.

### 7.6 Die Darstellung (`WorldRenderer`, indoor-render)

Schnittstelle und Verhalten im KDoc von `world/WorldRenderer.kt`. Zusätzlich:

- **Dünn und defensiv.** Jede Stufe aus `RenderSettings` ist einzeln
  abschaltbar und wirkt sofort. Nichts wirft nach außen; jeder Fehler wird zu
  `onTileFailed` (Fahrt geht weiter) oder `onFailure` (Meldung statt Bild).
  Ein schwarzes Bild ohne Meldung ist ein Fehler dieses Slices.
- **Start in Stufen:** Engine → Testszene möglich → Modelle → Manifest →
  Kacheln um den Start → `onReady`. Scheitert eine Stufe, benennt
  `onFailure` sie.
- **Bewegung:** `setState` kommt mit 10–20 Hz. Die Darstellung schreibt je
  Bild fort (`distM + speedMps·Δt`, höchstens 500 ms), gleicht Abweichungen
  über etwa 300 ms aus und springt nur bei mehr als 30 m Differenz.
  Kurbelphase aus `cadenceRpm`, Radphase aus `speedMps / 2,2 m`.
- **Laden:** Kacheln asynchron (`ResourceLoader.asyncBeginLoad` /
  `asyncUpdateLoad`), höchstens eine neue je Bild beginnen; Dateien nicht auf
  dem Hauptthread lesen. Entladen mit `destroyAsset`, sobald `show` die
  Kachel nicht mehr braucht (mit Hysterese, damit an Grenzen nichts flattert).
- **Licht:** Sonne als Richtungslicht aus `SkySetting` und der Breite des
  Ursprungs, mit Schatten (Kaskaden, Reichweite höchstens rund 300 m).
  Umgebungslicht als `IndirectLight` aus Kugelflächenfunktionen (drei Bänder,
  zur Tageszeit gerechnet). Himmel als zur Laufzeit erzeugte Cubemap
  (Verlauf Horizont → Zenit, Sonnenscheibe) oder — wenn `sky = false` — als
  einfarbige Skybox. Dunst über `View.fogOptions`, Horizontfarbe = Dunstfarbe.
- **Kameras:** `CHASE` (rund 6 m hinter, 2,5 m über dem Fahrer, weich
  gedämpft, nie unter Gelände), `HANDLEBAR`, `DRONE`.
- **FPS und Diagnose:** `onStats` etwa einmal je Sekunde;
  `IndoorDiagnosticsScreen` zeigt `RenderDeviceInfo`, die Statistik, alle
  Schalter, `RenderSettings.SAFE`, die Testszene (`loadTestScene`, braucht
  **keine** Datei außer dem, was der Slice selbst mitbringt) und die Testwelt
  aus dem Weltbau.
- **Compose:** `WorldSurface` hostet eine `SurfaceView` (`AndroidView`), nicht
  `TextureView`; `UiHelper` + `Choreographer`; pausiert mit dem Lebenszyklus;
  überlebt Drehung und den Verlust der Zeichenfläche.
- **R8:** Regeln für Filament in `app/proguard-rules.pro` nur, soweit die
  mitgelieferten consumer-rules nicht reichen — mit Begründung wie die
  vorhandenen Einträge. Der Release-Build (`assembleRelease`) muss durchlaufen.

### 7.7 Filament: geprüfte Annahmen und Abweichungen

Stand 23.11.2026, Filament **1.75.1**. Das ist die neueste Version, die gegen
`compileSdk 36` / AGP 9.0.1 baut: Ab 1.76.0 verlangen die AARs `compileSdk 37`
(`minCompileSdk=37` in den AAR-Metadaten; die neueste Version auf Maven Central
ist 1.81.0). Die Annahmen unten sind gegen die aktuelle Dokumentation (1.81.0)
geprüft, nicht eigens gegen 1.75.1 — die genutzten Schnittstellen sind alt und
stabil, indoor-render prüft beim ersten Build nach:

| Annahme | Befund |
|---|---|
| gltfio lädt GLB mit dem mitgelieferten Ubershader | ja: `Utils.init()` bzw. `Gltfio.init()` zuerst, dann `AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())`, `createAsset(buffer)`, `ResourceLoader.asyncBeginLoad(asset)`, `asset.releaseSourceData()`, `scene.addEntities(asset.entities)` |
| Animationen/Skins | ja: `asset.instance.animator.applyAnimation(index, sekunden)` + `updateBoneMatrices()` |
| Umgebungslicht ohne KTX | ja: `IndirectLight.Builder().irradiance(bands, sh)` (1–3 Bänder, vorskalierte Koeffizienten) oder `.radiance(bands, sh)` (unskalierte — einfacher zu rechnen) |
| Himmel ohne KTX | ja: `Skybox.Builder().color(r, g, b, a)`; Cubemap über eine zur Laufzeit gefüllte `Texture` mit `environment(…)` |
| Qualitätsoptionen der View | ja, Muster `view.xOptions = view.xOptions.apply { … }` für `dynamicResolutionOptions`, `ambientOcclusionOptions`, `bloomOptions`, `fogOptions`, `multiSampleAntiAliasingOptions`; `view.antiAliasing = FXAA` |

Abweichungen und Grenzen, die daraus folgen (schon in 7.3 eingearbeitet):

1. **Keine Spiegelungs-Umgebung.** Ohne `reflections(…)` fehlt der spiegelnde
   Anteil des Umgebungslichts — Metall wäre schwarz. Deshalb `metallic = 0`
   und raue Materialien. Eine zur Laufzeit vorgefilterte Cubemap ist eine
   Kür der Darstellung und muss abschaltbar sein.
2. **Kein eigener Shader**, also kein Textur-Splatting, kein Wasser-Shader,
   kein Wind im Laub, keine Detail-Maps. Nahschärfe kommt aus Geometrie und
   kachelnden Texturen (7.3).
3. **Kein Instancing über glTF.** Bäume sind gebacken; das Dreiecksbudget
   begrenzt die Dichte.
4. **Texturen unkomprimiert** im Grafikspeicher (kein Encoder für
   KTX2/Basis in reinem Kotlin) — daher die Budgets in 7.4.
5. **Nebel und Himmel:** Der Dunst wirkt auf die Szene, nicht auf die Skybox;
   deshalb muss die Horizontfarbe des Himmels zur Dunstfarbe passen.

indoor-render prüft beim ersten Build gegen 1.75.1 nach (API-Namen der
`FogOptions`, Cubemap-Befüllung, Schattenoptionen) und hält Abweichungen in
`docs/indoor/render.md` fest. Auf einem Gerät gesehen hat das noch niemand.

---

## 8 Geodaten

### 8.1 Quellen

| Art | Quelle | URL-Muster | Format | Lizenz / Angabe |
|---|---|---|---|---|
| Höhen | Terrarium-Kacheln (Tilezen, AWS Open Data) | `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png` | PNG 256², Höhe = `R·256 + G + B/256 − 32768` m | Quellenmix (u. a. SRTM, EU-DEM), Angabe laut Tilezen-Attribution |
| Karteninhalt | OpenFreeMap (OpenMapTiles-Schema, OSM) | TileJSON `https://tiles.openfreemap.org/planet` → `tiles[0]`, z. B. `https://tiles.openfreemap.org/planet/<version>/{z}/{x}/{y}.pbf` | MVT/Protobuf, bis z14 | „OpenFreeMap © OpenMapTiles Data from OpenStreetMap“ (ODbL) |
| Luftbild | amtliche WMS je Bundesland (`aerialSources`) | `<wmsUrl>?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=<layer>&STYLES=&CRS=EPSG:4326&BBOX=<minLat>,<minLon>,<maxLat>,<maxLon>&WIDTH=…&HEIGHT=…&FORMAT=image/jpeg` | JPEG (PNG, falls progressiv) | je Land, steht am Registereintrag |

- Gebrauchte Ebenen der Vektorkacheln: `building` (`render_height`,
  `render_min_height`), `landcover`, `landuse`, `park`, `water`, `waterway`,
  `transportation` (`class`, `surface`, `brunnel`). Eigener kleiner
  MVT-Dekoder in reinem Kotlin (Protobuf-Varints, Geometriebefehle, Zickzack).
- Zoomstufen wählt der Weltbau (Richtwert Höhen z13 nah / z10–11 fern,
  Vektor z14 nah / z11–12 fern); die Version der Vektorkacheln wird je Bau
  einmal aus dem TileJSON gelesen.
- **Register der Luftbilder:** NRW ist eingetragen und geprüft
  (`https://www.wms.nrw.de/geobasis/wms_nw_dop`, Ebene `nw_dop_rgb`,
  dl-de/zero-2-0). indoor-world ergänzt weitere Länder — mindestens Sachsen —
  nur nach einer echten `GetCapabilities`- und `GetMap`-Anfrage aus dem
  Werkzeug und mit Lizenz, Wortlaut der Quellenangabe und Prüfdatum am
  Eintrag. Auswahl je Kachel über das Umgebungsrechteck des Landes plus
  Leerbild-Erkennung (einfarbige Antwort = außerhalb der Abdeckung).

### 8.2 Positivliste

`isAllowedGeoUrl(url, aerialSources)` ist die einzige Wahrheit: nur `https`,
nur die drei Arten oben, Host **und** Pfad geprüft, unmittelbar vor dem
Senden. Der rohe Abruf folgt **keinen Weiterleitungen**. Jede neue Quelle
braucht einen Registereintrag und einen Satz in `PRIVACY.md`.

### 8.3 Abruf (`GeoFetcher`)

- Roh: `:app` (indoor-app) mit OkHttp — ein eigener Adapter neben
  `OkHttpClientAdapter` (das vorhandene `HttpClient` liefert Text, Kacheln
  sind Bytes; **Abweichung von der Vorgabe „über das HttpClient-Interface“**,
  die Haltung ist dieselbe: schmale Schnittstelle in `:core`, OkHttp
  dahinter). `followRedirects(false)`, Timeouts wie der vorhandene Adapter,
  `User-Agent: Trailscape/<versionName>` ohne Gerätekennung. Im Werkzeug:
  `java.net.http`.
- Hülle `createGeoPipeline(network, cacheDir)` (indoor-world): Positivliste
  → Plattencache → Drosselung. Niemand ruft den rohen Abruf direkt.
- **Drosselung:** höchstens 4 gleichzeitige Anfragen insgesamt, höchstens 2
  je Host; bei 429/503 mit `Retry-After` bzw. 2 s · 4 s · 8 s warten, dann
  aufgeben.

### 8.4 Cache

- Ort: `<cacheDir>/indoor/geo/<art>/<2 Zeichen>/<sha256(url)>` plus
  Metadatei (URL, Zeit, Content-Type). Das System darf ihn räumen.
- Gespeichert werden nur Antworten mit Status 200 und plausiblem Inhalt.
- Haltbarkeit: Höhen 365 Tage · TileJSON 24 Stunden · Vektorkacheln 30 Tage
  (die versionierte URL ist Teil des Schlüssels) · Luftbilder 180 Tage.
- **Ohne Netz gilt auch Abgelaufenes.** Eine fertige Welt braucht den Cache
  nicht mehr; er darf nach dem Bau geleert werden.
- Fertige Welten liegen unter `<filesDir>/indoor/worlds/<worldId>/` (vom
  Android-Backup ausgenommen), sind offline fahrbar und werden von indoor-app
  verwaltet (Abschnitt 10.4).

### 8.5 Fehlerfälle

| Fall | Verhalten |
|---|---|
| Höhenkachel fehlt (kein Netz, nicht im Cache) | Bau scheitert: `WorldBuildFailed(OFFLINE)` bzw. `SOURCE_ERROR`; Fertiges bleibt liegen, „Erneut versuchen“ setzt fort |
| Vektorkachel fehlt | Kachel ohne Gebäude/Landnutzung, `degraded = true`, Bau läuft weiter |
| Luftbild fehlt, Land nicht im Register, Leerbild | erzeugte Bodentextur, `aerial = false` |
| Abbruch durch den Nutzer | `WorldBuildCancelled`; ohne `manifest.json` ist es eine angefangene Welt |
| Platz reicht nicht | `WorldBuildFailed(NO_SPACE)` |
| Strecke über 150 km | `WorldBuildFailed(ROUTE_UNSUPPORTED)` (`MAX_WORLD_ROUTE_M`) |
| URL nicht auf der Positivliste | Ausnahme in der Hülle, kein Abruf — das ist ein Programmfehler |

### 8.6 Quellenangaben in der Oberfläche

`WorldManifest.attribution` ist verbindlich: Die Oberfläche zeigt die Texte
**während der Fahrt** erreichbar (ein „ⓘ“ im HUD, das die Liste öffnet) und
dauerhaft in der Weltverwaltung und unter Mehr → Über (`OpenSourceNotices`).
Vor dem Laden zeigt der Voranschlag (`WorldPlan`) Hosts, Datenmenge und
Platzbedarf. In die Screenshots der Entwicklungsumgebung gehören die Angaben
ebenfalls.

---

## 9 Entwicklerwerkzeug (`tool/indoor-dev/`, indoor-world)

Zweck: die Welt **ansehen**, bevor es ein Gerät gibt.

```
./gradlew :indoor-dev:run --args="build --gpx tool/indoor-dev/samples/<name>.gpx --quality MEDIUM --out tool/indoor-dev/out/<name>"
./gradlew :indoor-dev:run --args="testworld --out tool/indoor-dev/out/testwelt"
cd tool/indoor-dev/viewer && npm install && node shots.mjs ../out/<name>
```

- `build` liest eine GPX-Datei (über `:core`), baut `IndoorRoute` und Welt
  mit echten Geodaten (roher Abruf über `java.net.http`, `ImageIO` als
  `RasterDecoder`), Cache unter `tool/indoor-dev/cache/`.
- `viewer/`: eine statische Seite mit three.js, die `manifest.json` liest,
  Kacheln nach `show` lädt, die Teilflächen-Regel aus 7.2 nachbildet und eine
  Kamera wie `CHASE` den `WorldTrack` entlangführt. Sie ist die
  **Referenzleserin** des Formats — was sie nicht lesen kann, kann Filament
  auch nicht.
- `shots.mjs`: Playwright mit dem vorhandenen `/usr/bin/google-chrome`
  (headless, `--use-angle=swiftshader`), Bilder an festen Laufmetern nach
  `tool/indoor-dev/out/<name>/shots/`. Node 22 ist vorhanden; `node_modules`,
  `out/` und `cache/` sind git-ignoriert (eigene `.gitignore` im Verzeichnis).
- Mindestens eine Beispielstrecke mit echten Daten in NRW (Luftbild) und eine
  ohne Luftbild-Abdeckung liegen als GPX unter `samples/`; die Bilder dazu
  hat sich der Slice **angesehen** und in `docs/indoor/world.md` beschrieben,
  was zu sehen ist und was noch nicht stimmt.
- Zusätzlich prüft das Werkzeug jede Welt gegen den Vertrag (Namen, erlaubte
  Erweiterungen, Texturformate, Budget, lückenloses `show`) und bricht bei
  Verstößen ab — dieselbe Prüfung läuft als `:core`-Test über die Testwelt.

---

## 10 App: Ablauf und Speicherung (indoor-app)

### 10.1 Ablauf

1. **Einstieg:** eine Karte auf „Heute“ (mit der Einheit des Tages, wenn es
   eine gibt) und ein Eintrag beim Aufzeichnen-Knopf. Ziel ist eine eigene
   Route im `NavHost` ohne Navigationsleiste.
2. **Koppeln:** `IndoorDevicesSection()` plus der Schalter „Demo-Trainer“.
3. **Strecke und Modus:** eine gespeicherte Planung, eine gefahrene Tour
   (dann wahlweise als Geist) oder die Testwelt; Modus freie Fahrt (SIM) oder
   Einheit des Tages (ERG, über `sessionsForDay` + `ergWorkoutFor`); Geist:
   keiner, frühere Fahrt, Vergleichsleistung.
4. **Welt laden:** Voranschlag zeigen → Nutzeraktion → Belagsabgleich
   (Abschnitt 4) → `WorldBuilder.build` auf `Dispatchers.IO` mit Fortschritt
   und Abbruch → Manifest. Vorhandene Welt: direkt weiter. „Ohne Welt fahren“
   ist immer möglich.
5. **Fahren:** `WorldSurface` vollflächig, HUD darüber (Leistung, Tempo,
   Trittfrequenz, Puls, Gang mit ±-Knöpfen, Steigung, Belag, Abstand zum
   Geist, ERG-Abschnitt, Höhenprofil mit Position, Kamera-Wechsel, Pause,
   Beenden über Halten). Bildschirm bleibt an (`FLAG_KEEP_SCREEN_ON` wie in
   `RideModeScreen`). Hoch- und Querformat. Bei `onFailure` der Darstellung:
   Meldung statt 3D-Fläche, HUD läuft weiter.
6. **Auswertung:** Kennzahlen, Vergleich mit dem Geist, Speichern/Verwerfen
   (Fahrten unter 60 s werden nicht gespeichert).

Die Diagnoseseite (`IndoorDiagnosticsScreen`) ist aus dem Indoor-Ablauf und
unter Mehr erreichbar.

### 10.2 Speichern als Indoor-Tour

`IndoorRideRecord` → `Ride(indoor = true, planned = false, points, stats)`,
Name aus Ressource („Indoor · <Strecke>“). Das Feld ist angelegt und
abwärtskompatibel (nur geschrieben, wenn `true`); `RideStorage` muss es durch
den Index tragen (`RideSummary.indoor` ist da — prüfen, dass `IndexEntry` es
nicht verliert, und den Index bei Bedarf neu aufbauen).

Eine Indoor-Fahrt **zählt** für Belastung, Wochenziel, Serie, Statistik
(`riddenRides`). Sie zählt **nicht** für alles mit echtem Ort — dort
`outdoorRides` einsetzen. Mindestliste, je Stelle prüfen und testen:

- Neue Wege / gefahrene Wege (`RiddenWays.kt`, Aufrufer von `updateRiddenWays`)
- Entdeckt-Kacheln (`ExplorerTiles.kt`, `collectExplorerTiles`)
- Rundenvergleich und Strecken-Signaturen (`RouteMatch.kt`)
- lokale Segmente/Bestzeiten (`RideSegments.kt`)
- Tempo-Historie für die Planung (`typicalAvgSpeedKmh`)
- „erste Runde“-Logik (`FirstRound.kt`) — hier zählt die Indoor-Fahrt als gefahren
- Karte im Tourdetail, Teilen-Karte, Tourbild: Strecke zeigen, aber als
  Indoor kennzeichnen; in der Tourenliste ein Kennzeichen

Sync und Backup tragen das Feld von selbst (es steht im Tour-JSON).

### 10.3 Export und Strava (soweit günstig)

GPX/FIT wie jede Tour (Leistung und Trittfrequenz stehen an den Punkten).
Strava: als virtuelle Fahrt (`sport_type=VirtualRide` bzw. `trainer=1`), wenn
der vorhandene Upload das mit einem Parameter kann — sonst in `docs/TODO.md`.
Health-Import: prüfen, dass eine gleichzeitig von der Uhr aufgezeichnete
Einheit nicht als zweite Tour entsteht (`findDuplicateRide`), sonst TODO.

### 10.4 Weltverwaltung

Liste der Welten (Strecke, Qualität, Größe, Datum, Quellenangaben), Löschen,
Gesamtgröße, „Zwischenspeicher leeren“. Welten, deren Strecke es nicht mehr
gibt, werden zum Löschen angeboten, nie stillschweigend gelöscht.
`worldId` kommt aus `WorldPlan`.

### 10.5 Was indoor-app in `:app` für andere bereitstellt

- den rohen `GeoFetcher` (OkHttp, Abschnitt 8.3) und den `RasterDecoder`
  (`BitmapFactory`, `inPremultiplied = false`, `ARGB_8888`, ohne
  Farbraumumwandlung) unter `app/…/indoor/geo/`;
- Einträge in `AppServices` für Laufzeit, Weltverzeichnis, Geo-Hülle.

### 10.6 Tests und Doku

- Screenshot-Tests (Roborazzi) für Einstieg, Koppeln, Streckenwahl, Welt
  laden, Fahren (3D-Fläche durch einen Platzhalter ersetzt, Sitzung als
  Attrappe mit festen Zuständen), Auswertung; mindestens einer englisch
  (`+en-rGB`, siehe `docs/i18n.md` Abschnitt E).
- Laufzeit-Test mit Attrappen für Trainer, Sitzung und Darstellung.
- `README.md` (Funktion, Geräte, Grenzen), `PRIVACY.md` (Abschnitte 2, 3, 4:
  neue Verzeichnisse, Bluetooth-Geräte, die drei Quellenarten mit Hosts,
  Auslöser „Welt laden“, was übertragen wird — Kachelkoordinaten bzw.
  Kartenausschnitte entlang der Strecke, also ein Hinweis auf die gewählte
  Strecke), `docs/TODO.md` (Abschnitt 11), `OpenSourceNotices` (Filament
  Apache-2.0, Datenquellen).

---

## 11 Auf dem Gerät zu prüfen, Entscheidungen, Risiken

### 11.1 Auf echter Hardware zu prüfen (hier nicht testbar)

- [ ] **Darstellung überhaupt:** Testszene, dann Testwelt, dann echte Welt
      auf dem Handy des Gründers; FPS je Qualitätsstufe; jede Stufe einzeln
      schalten; Drehung, Hintergrund/Vordergrund, 30 Minuten Dauerlauf
      (Speicher, Wärme).
- [ ] **Speicher:** hält `HIGH` auf dem Gerät, oder stürzt es beim Laden ab?
- [ ] **KICKR CORE über FTMS:** Koppeln, Request Control, SIM- und
      ERG-Befehle, Verhalten bei Kontrollverlust (z. B. Hersteller-App
      parallel), Neuverbinden nach Abbruch.
- [ ] **Virtuelle Gänge:** fühlt sich das Schalten richtig an? Faktor
      `trainerScale` kalibrieren; Verzögerung zwischen Schalten und
      Widerstand.
- [ ] **Zwift Click:** Wird das Gerät gefunden, klappt der Handschlag mit der
      Firmware des Gründers, kommen Tasten an?
- [ ] **Gleichzeitig:** Trainer + Schalttaster + Pulsgurt an einem Handy.
- [ ] **Belag spürbar:** Asphalt → Schotter → Waldboden auf einer bekannten
      Strecke; passt das Gefälle zum Bild?
- [ ] **Welt laden über Mobilfunk/WLAN:** Dauer und Datenmenge für eine
      50-km-Runde; Fortsetzen nach Abbruch; offline fahren.

### 11.2 Entscheidungen des Gründers

1. **OpenFreeMap-Nutzungsbedingungen.** Der Weltbau lädt Vektorkacheln entlang
   einer Strecke gezielt herunter — dieselbe Frage wie beim Offline-Download
   (`docs/TODO.md`, Blocker): vor dem Release schriftlich bestätigen lassen
   oder eine eigene Kachelquelle. Für den Prototyp vertretbar.
2. **Luftbild-Lizenzen je Land.** Welche Länder ins Register kommen (manche
   verlangen Namensnennung mit Jahr, manche schließen Weiterverwendung in Apps
   nicht ausdrücklich ein). Außerhalb Deutschlands gibt es im Prototyp kein
   Luftbild.
3. **APK-Größe.** Filament bringt je ABI rund 10 MB. Auf `arm64-v8a`
   beschränken (schließt alte 32-Bit-Geräte aus) oder hinnehmen?
4. **Fahrt im Hintergrund.** Der Prototyp hat keinen Vordergrunddienst: Wer
   die App verlässt, pausiert die Fahrt. Soll sie weiterlaufen
   (`connectedDevice`-Dienst, Play-Prüfung)?
5. **Reale Übersetzung.** Vorgabe 40 Zähne vorn auf dem 14er-Cog; das
   Kettenblatt des Gründers gehört in die Einstellungen.
6. **Crr-Tabelle.** Die Werte sind Annahmen — nach den ersten Fahrten
   justieren (oder im Profil je Belag einstellbar machen?).
7. **Strava:** Indoor-Fahrten als virtuelle Fahrt hochladen, mit oder ohne
   virtuelle Strecke?
8. **Standard-Qualität** und ob „Welt laden“ über Mobilfunk warnen soll.

### 11.3 Risiken

- **Filament ungesehen.** Die Darstellung ist bis zum ersten Lauf auf dem
  Handy nur kompiliert. Gegenmittel: Testszene, Schalter, Diagnoseseite,
  three.js-Betrachter als Formatreferenz.
- **Texturspeicher.** Unkomprimierte Texturen sind der engste Engpass; das
  Budget ist gerechnet, nicht gemessen.
- **Wirklichkeitsnähe ohne eigene Shader** hat eine Decke (kein Splatting,
  kein Wasser, kein Wind). Wenn das nicht reicht, braucht es `matc` in der CI.
- **Belag gespeicherter Touren** hängt am Abgleich über den Router; wo der
  scheitert, bleibt nur die grobe Schätzung aus den Vektorkacheln.
- **Virtuelle Gänge** stehen auf einer Annahme über das Verhalten des
  Trainers im Simulationsmodus.
- **Zwift Click** ist rückentwickelt; ein Firmware-Update kann es brechen.
- **Datenmenge und Dauer** von „Welt laden“ bei langen Strecken.
- **Maven Central 429** beim ersten Auflösen von Filament.

---

## 12 Zusammenführen

Reihenfolge der Merges: **indoor-core → indoor-world → indoor-ble →
indoor-render → indoor-app.** Die ersten vier fügen nur eigene Dateien hinzu
und ersetzen den Rumpf ihrer Einstiegsdatei; indoor-app berührt die geteilten
Dateien und kommt deshalb zuletzt. Konflikte kann es nach Vertrag nur geben,
wenn jemand fremden Besitz geändert hat.

Prüfliste des Abschluss-Agenten (abgehakt bei der Integration am 23.11.2026,
Einzelheiten in den Abschnitten „Integration“ von `docs/indoor/world.md`,
`render.md` und `app.md`):

- [x] `./gradlew :core:test`, `:app:testDebugUnitTest`, `:app:assembleDebug`,
      `:app:assembleRelease` (R8 mit Filament), `:indoor-dev:run … testworld`
- [x] kein `NotImplementedError("Indoor: …")` und kein Platzhalter mehr in den
      vier Einstiegsdateien
- [x] Filament löst in 1.75.1 auf — inzwischen **zwei** Artefakte in einer
      Version: `filament-utils` ist entfallen (Bitte von indoor-render)
- [x] `StringResourceParityTest` grün; drei `strings_indoor_*.xml` je Sprache
- [x] `PRIVACY.md` nennt jeden Host aus `aerialSources` und `GeoEndpoints`
- [x] „Bitte an <Besitzer>“ aus den fünf Abschlussberichten abgearbeitet,
      soweit klein und klar; Offenes steht in den Notizen der Slices
- [x] Demo-Fahrt von Einstieg bis gespeicherter Indoor-Tour läuft im Test mit
      den echten Teilen aus `:core` (Demo-Trainer, Sitzung, Testwelt)
- [x] `docs/indoor/*.md` vorhanden und aus `docs/geraete-pruefliste.md` verlinkt
