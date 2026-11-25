# Strecke vor der Tour ansehen (3D-Vorschau)

Stand: 25.11.2026, Branch `claude/idee-04-vorschau-3d` (Idee 4).

Eine geplante Route oder eine gefahrene Tour lässt sich in 3D abfliegen —
**ohne Trainer, ohne Indoor-Sitzung, ohne Aufzeichnung.** Gebaut auf dem, was
es schon gibt: derselbe Ablauf zum Vorbereiten einer Strecke („Wird einmal
vorbereitet“, Voranschlag mit Datenmenge), dieselbe Darstellung
(`WorldRenderer`/`WorldSurface`, Filament) mit ihren Schutzpfaden
(`GlbPatch`, `RenderGuard`), dieselben Kameras. Es gibt keinen zweiten
Ladeweg und keine eigene `createAsset`-Stelle.

**Nichts davon ist auf einem Gerät geprüft.** Logik (Zeitplan, Abspieler,
Zustände, Ablauf) ist in Unit-Tests geprüft, die Oberfläche in
Robolectric-Bildern mit einem gezeichneten Platzhalter statt der 3D-Fläche.

## Ablauf

1. **Einstieg:** Tourdetail → „In 3D ansehen“ (unter „Erneut fahren“; nicht
   bei Indoor-Fahrten, nur mit mindestens zwei Punkten).
   `AppViewModel.requestIndoorPreview(rideId)` merkt die Tour und öffnet das
   Ziel `indoor`; `IndoorFlowScreen` holt sie mit
   `consumeIndoorPreviewPick()` ab und ruft `IndoorViewModel.openPreview`.
2. **Strecke vorbereiten** (Stufe `WORLD`, Schalter `previewMode`): genau der
   Weg vor einer Fahrt (`prepareWorld` → `prepareSaved`):
   - 3D-Ansicht schon vorhanden → sofort weiter zu 3.
   - sonst der Voranschlag (Länge, Datenmenge, Platz, Datenschutz,
     Einzelheiten) mit „Vorbereiten“ → `loadWorld` (Belagsabgleich, Bau mit
     Fortschritt und Abbruch) → fertig → weiter zu 3.
   - Statt „Ohne 3D-Ansicht fahren“ steht **„Nur Höhenprofil ansehen“**
     (Vorschau ohne Darstellung). Den Server-Bau gibt es hier nicht.
   - Zurück verlässt den Ablauf (zurück ins Tourdetail bzw. die Liste);
     während des Baus bricht Zurück wie bisher erst den Bau ab.
3. **Vorschau** (neue Stufe `IndoorStage.PREVIEW`, `IndoorPreviewScreen`):
   vollflächig die 3D-Ansicht, oben Name, „12,4 von 38,4 km“, Höhe und
   Steigung (farbig wie im Fahr-HUD), unten das Höhenprofil als **Schieber**
   (Tippen oder Ziehen springt, für TalkBack ein Wert mit „Wert setzen“) und
   die Knöpfe **Kamera** (Drohne → Verfolger → Lenker), **Wiedergabe/Pause**
   (am Ziel „Noch einmal von vorn“) und **Geschwindigkeit** (1× → 2× → 4× →
   8×), dazu die Restzeit bei der gewählten Geschwindigkeit und ein Satz, was
   1× bedeutet. Die Vorschau beginnt angehalten mit der Drohne. Der
   Bildschirm bleibt an. Geht die App in den Hintergrund, hält sie an.
   Zurück schließt die Vorschau, gibt die Darstellung frei und verlässt den
   Ablauf. Gespeichert wird nichts.

## Was „1×“ heißt

`PreviewTimeline` (`core/…/indoor/preview/RoutePreview.kt`) rechnet für jedes
Stück der Strecke das Gleichgewichtstempo der Fahrphysik der Sitzung
(`steadySpeedMps`, Steigung, Belag über `effectiveCrr`, Luftdichte nach
Höhe, kein Wind) bei **65 % der FTP** aus dem Profil (ohne brauchbare FTP
160 W), geklemmt auf 5,4–60 km/h.

- **Gefahrene Tour:** Der Zeitplan wird gleichmäßig auf ihren Schnitt
  (Strecke / Bewegungszeit) gestreckt — „1× ist so schnell wie bei dieser
  Tour“. Das Verhältnis Berg/Ebene bleibt das des Modells (die echten
  Zeitstempel samt Pausen werden bewusst nicht nachgespielt). Ein
  unplausibler Schnitt (unter 7,2 oder über 54 km/h) bleibt beim Modell.
- **Planung:** das Modell — „1× ist eine lockere Ausdauerfahrt mit … W“.

## Abspieler

`PreviewPlayer` (rein, in `:core` getestet):

- `tick(nowMs)` schreibt mit der monotonen Uhr fort, höchstens 250 ms je
  Schritt (nach einer Lücke springt nichts); Geschwindigkeit × Zeitplan.
- **Tempo gedeckelt auf 60 m/s** (216 km/h gezeigt): Darüber kämen
  Fortschreibung der Darstellung (`MotionSmoother` begrenzt auf 40 m/s und
  gleicht über 300 ms aus, Sprung erst ab 30 m) und Kachelnachschub (200 m
  Vorlauf) nicht mit. 8× bergab ist deshalb etwas langsamer als 8×.
- **Warten auf Kacheln** wie ein Video beim Puffern: Meldet die Darstellung
  `onLoading(geladen < nötig)`, steht die Wiedergabe, höchstens 4 s am Stück;
  danach geht sie weiter und wartet 10 s lang nicht erneut (eine Kachel, die
  nie kommt, hält nichts auf). `onReady` und ein Sprung setzen das zurück.
- `worldState(nowMs)` ist der Zustand für `WorldRenderer.setState`: der
  eingebaute Fahrer als Markierung an der Stelle, mit dem gezeigten Tempo
  (für die Fortschreibung zwischen zwei Takten) und 85 U/min, solange es
  läuft; sonst `paused`.
- Steigung über 50 m gemittelt (die Rohsteigung eines Stücks springt).

`IndoorPreviewController` (`app/…/ui/indoor/`) taktet mit 50 ms (wie die
Fahrt 10–20 Hz), füttert die Darstellung und veröffentlicht
`IndoorPreviewUi`. Jeder Aufruf der Darstellung steckt in `indoorCatching`;
wirft sie, wird sie nicht mehr gerufen (wie in `IndoorRideRuntime`).

## Ausfall

- Meldet die Darstellung `onFailure` oder wirft sie: dunkler Grund, Hinweis
  „Keine 3D-Ansicht“ mit dem Satz zur Ausfallart, die Vorschau läuft auf dem
  Höhenprofil weiter (dasselbe Verhalten wie die Fahrt).
- Native Abbrüche in Filament lassen sich nicht abfangen; dafür gelten
  unverändert `GlbPatch` und `RenderGuard` — die Vorschau lädt nur über
  `WorldRenderer.loadWorld`.
- „Nur Höhenprofil ansehen“: keine Darstellung, nur Profil und Werte.
- Strecke nicht lesbar: die vorhandene Meldung des Ablaufs (`IndoorWorldUi.RouteUnavailable`).

## Was nicht geändert wurde

Renderer (`app/…/indoor/render/**`), Weltbau (`core/…/indoor/world/**`),
`tool/indoor-dev/**`, Formate, Materialien: unberührt. Kein neuer
Kameramodus (`CameraMode` ist Vertrag); die Vorschau nutzt DRONE, CHASE und
HANDLEBAR. Keine Telemetrie, kein Netz außer dem bekannten Vorbereiten
(nach Nutzeraktion, Hosts wie im Voranschlag).

Berührte gemeinsame Dateien (klein, additiv): `IndoorViewModel.kt`
(Vorschau-Block, zwei Haken in `commitWorld`/`prepareWorld`, `back()`,
`release()`, `onAppBackground()`), `IndoorUiState.kt` (`PREVIEW`),
`IndoorFlowScreen.kt` (zwei Stufen), `IndoorWorldScreen.kt` (zwei optionale
Beschriftungen), `AppViewModel.kt` (Merker für die Tour),
`RideDetailScreen.kt`/`TourList.kt` (Knopf), `StringResourceParityTest.kt`
(Präfix `idee04_`). Texte in `values[-en]/strings_idee04.xml`.

## Tests

- `core/…/indoor/preview/RoutePreviewTest.kt`: Zeitplan stetig und
  umkehrbar, Berg langsamer als Ebene und realistisch, bergab gedeckelt,
  Strecken auf den Schnitt, 1×/8×, Deckel, Lücke, Pause, Springen, Ziel und
  Neustart, Warten auf Kacheln mit Obergrenze, Zustand für die Darstellung.
- `app/…/ui/indoor/IndoorPreviewFlowTest.kt`: ohne Trainer über
  „Vorbereiten“ in die Vorschau (Darstellung lädt die vorbereitete Strecke,
  keine Sitzung, kein Server-Bau), Wiedergabe, 2× doppelt so weit, Springen,
  Kamera, Hintergrund, Zurück gibt alles frei und speichert nichts;
  vorhandene 3D-Ansicht startet sofort; nur Höhenprofil; Ausfall und
  werfende Darstellung; Zurück aus „Vorbereiten“; unbekannte Tour.
- `app/…/ui/indoor/IndoorPreviewScreenshotTest.kt`: Knöpfe, Tippen aufs
  Profil, Kamerafolge; mit `-Pscreenshots` die Bilder unten.

## Bilder (`docs/indoor/bilder/vorschau/`)

`vorschau-01` läuft (Drohne, 4×), `-02` angehalten (Verfolger, Schnitt der
Tour), `-03` 3D-Ansicht lädt, `-04` wartet auf Kacheln, `-05` ohne
3D-Ansicht, `-06` nur Höhenprofil, `-07` am Ziel, `-08` „Strecke
vorbereiten“ vor der Vorschau, `-09` englisch. Die 3D-Fläche ist ein
gezeichneter Platzhalter.

## Auf dem Gerät zu prüfen

- [ ] Tourdetail → „In 3D ansehen“ ohne gekoppelten Trainer: Voranschlag,
      „Vorbereiten“, Fortschritt, danach die Vorschau mit Bild
- [ ] Tour mit fertiger 3D-Ansicht: Vorschau startet sofort, ohne Netz
- [ ] Drohne/Verfolger/Lenker bei 1× und 8×: Kamera folgt ruhig, kein
      Ruckeln durch die Fortschreibung, Fahrer bleibt im Bild
- [ ] 8× auf langer Abfahrt: Wie oft und wie lange „Strecke lädt nach …“?
      Kommt der Kachelnachschub mit, oder sollte der Deckel tiefer liegen?
- [ ] Springen über das Profil (weit, > 30 m): Kacheln am Ziel laden, die
      Wiedergabe wartet kurz und läuft dann weiter
- [ ] App in den Hintergrund und zurück: Vorschau steht, Bild kommt wieder
- [ ] Zurück: Darstellung freigegeben (kein schwarzes Bild beim nächsten
      Öffnen einer Fahrt), Rückkehr ins Tourdetail bzw. die Tourenliste
- [ ] TalkBack: Profil als Schieber bedienbar, Knöpfe verständlich

## Offen / Vorschläge

- Gefahrene Tour mit echten Zeitstempeln nachspielen (Pausen
  herausgeschnitten), statt nur auf den Schnitt zu strecken.
- Eigene Vorschau-Kamera (langsamer Überflug, weiter Blick) wäre eine Kür
  der Darstellung: ein zusätzlicher `CameraMode` ändert eine Vertragsdatei
  und gehört zum Slice indoor-render.
- Einstieg auch auf dem Karten-Tourblatt (`MapPanels.kt`) und nach dem
  Planen einer Route.
- Geplante Route ohne gespeicherte Tour (frisch geplant, noch nicht
  gespeichert) kann noch nicht angesehen werden.
