# Leben auf der Strecke: Bergwertung und Kilometer

Während einer Indoor-Fahrt erkennt Trailscape die Anstiege der Strecke selbst,
kündigt sie an, zeigt am Anstieg Rest und Abstand zur eigenen Bestzeit und an
der Kuppe das Ergebnis. Dazu kommen dezente Kilometermarken. Alles läuft auf
dem Gerät, ohne Netz, ohne Konto, ohne neue Datenquelle.

## Was der Fahrer sieht

| Wann | Einblendung (unter den Kernwerten im HUD) |
|---|---|
| ab 500 m vor dem Fuß | „Anstieg in 300 m: 2,4 km, 6,1 %" · Kategorie · eigene Bestzeit |
| am Anstieg | Rest (km), Rest-Höhe, aktuelle Steigung, Zeit im Anstieg, Abstand zur Bestzeit (grün schneller, orange langsamer) |
| an der Kuppe, 12 s | „Passhöhe 694 m" · Zeit am Anstieg · „Neue Bestzeit · 0:23 schneller" bzw. „Erste Zeit an diesem Anstieg", „0:08 hinter der Bestzeit", „Ohne Wertung" |
| an jeder Kilometermarke, 5 s | „km 10" (Strecken ab 40 km: alle 5 km; keine Marke in den letzten 200 m) |

In der **Auswertung** steht eine Karte „Anstiege" mit jedem gefahrenen
Anstieg: Nummer (auf Runden mit Runde), Länge, Steigung, Höhenmeter,
Passhöhe, Zeit und Ergebnis.

**Abschalten:** Indoor → Einstellungen → 3D-Ansicht → „Einblendungen auf der
Strecke" (Vorgabe an). Gemessen wird trotzdem, damit Bestzeiten und
Auswertung lückenlos bleiben; abgeschaltet ist nur die Anzeige während der
Fahrt.

## Erkennung (`core/…/indoor/climb/ClimbDetection.kt`)

Reine, getestete Funktion `detectClimbs(route)` auf dem Höhenprofil der
`IndoorRoute` (mit Welt: dem Streckenband der Welt, ohne Welt: der
aufbereiteten Strecke — dieselbe Route, die auch das Fahrgefühl bestimmt).

| Konstante | Wert | Bedeutung |
|---|---|---|
| `STEP_M` | 50 m | Neuabtastung des Profils |
| `STEP_MIN_GRADE_PCT` | 2 % | ab hier „steigt" ein Schritt |
| `MAX_FLAT_GAP_M` | 300 m | längstes Flachstück, das zwei Läufe verbindet |
| `MAX_DIP_M` | 10 m | größter Höhenverlust darin |
| `MIN_LENGTH_M` | 400 m | Mindestlänge |
| `MIN_AVG_GRADE_PCT` | 3 % | Mindeststeigung im Mittel |
| `MIN_SCORE` | 3000 | Mindest-Wertungszahl (Länge in m × Steigung in %) |
| `MAX_GRADE_WINDOW_M` | 100 m | Fenster der steilsten Stelle |

Kategorie nach der Wertungszahl, angelehnt an verbreitete Bergwertungen
(keine offizielle Einteilung): ohne Kategorie ab 3000, Kat. 4 ab 8000,
Kat. 3 ab 16000, Kat. 2 ab 32000, Kat. 1 ab 64000, HC ab 80000.
Anstiege laufen nie über das Streckenende hinaus, auch nicht auf Runden.

## Zeitnahme (`TrailTracker.kt`)

- Gewertet wird nur, wer vom Fuß an fährt (Start höchstens 5 m dahinter).
  Wer mittendrin startet oder in der Pause versetzt wird (Sprung > 100 m oder
  rückwärts), fährt „ohne Wertung".
- Die Zeit läuft nur in der Phase RUNNING (Pausen zählen nicht), je Schritt
  höchstens 2 s wie in der Sitzung; Fuß und Kuppe werden zwischen zwei
  Zuständen linear eingepasst.
- Der Abstand zur Bestzeit vergleicht mit den Zwischenzeiten der Bestzeit an
  jedem Zehntel des Anstiegs.
- Auf Runden wird jeder Anstieg je Runde gewertet; eine neue Bestzeit gilt
  sofort für die nächste Runde.
- Eine neue Bestzeit wird **an der Kuppe** gespeichert — auch wenn die Fahrt
  danach verworfen wird.

## Bestzeiten speichern (`ClimbBests.kt`, `app/…/indoor/ClimbBestStore.kt`)

- Schlüssel: Streckenkennung der Streckenwahl (`ride:<id>`, `catalog:<id>`,
  `testwelt`, `generated:<seed>`) plus Lage des Anstiegs. Zugeordnet wird mit
  150 m Toleranz für Fuß und Kuppe (nach einem neuen Streckenbau verschiebt
  sich eine Strecke um einige Meter); `climbKey()` liefert die auf 10 m
  gerundete Kennung, z. B. `ride:abc@6200-8600`.
- Ein einziger Eintrag `trailscape.indoor.climb_bests` im `KeyValueStore`,
  stabiles JSON (Version 1):
  `{"format":1,"bests":[{"route":…,"startM":…,"endM":…,"timeMs":…,"splitsMs":[11 Werte],"atMs":…}]}`.
  Unbekannte Felder werden ignoriert, kaputte Einträge übersprungen.
- **Backup:** optionales Feld `climbBests` im Trailscape-Backup (gleiches
  JSON). Ohne Bestzeiten bleibt das Backup byteidentisch wie bisher; ältere
  App-Versionen ignorieren das Feld. Der Import führt zusammen: je Anstieg
  gewinnt die schnellere Zeit.

## Für ein späteres Abzeichen-/Spielsystem

Die Ergebnisse sind saubere Datenklassen in `:core`: `Climb` (Lage, Höhe,
Steigung, Kategorie, Wertungszahl), `ClimbEffort` (Strecke, `climbKey`,
Runde, gewertet, Zeit, alte Bestzeit, neue Bestzeit, erste Zeit,
Verbesserung, Tempo, VAM) und `ClimbBest`. Ein Abzeichen kann z. B. auf
`ClimbEffort.newBest`, `climb.category == HC` oder die Summe der gewerteten
Anstiege hören, ohne die Erkennung zu kennen.

## Ortsnamen: warum (noch) nicht

Geprüft: Ortsnamen gibt es nur **während des Streckenbaus** — aus den
Vektorkacheln (Ebene `place`, `PropsLayer.placeName` für die Ortstafeln).
Das Manifest (`WorldManifest`) und die Kachel-GLBs tragen keine Namen
(Material der Ortstafel nur als Hash), Katalogpakete nur Namen und Region
der ganzen Strecke, BRouter-Wegmerkmale keine Orte. Der Geo-Cache darf nach
dem Bau geleert werden und fehlt bei Katalog- und Server-Strecken ganz.
Ortsnamen beim Durchfahren („Ortseingang Marbach") brauchen deshalb eine
Formatänderung beim Streckenbau — etwa eine Liste
`places: [{name, distM}]` im Manifest, die der Weltbau aus den schon
geladenen Vektorkacheln an den Ortstafeln füllt. Das gehört dem Weltbau
(eigene Builder-Version) und ist hier bewusst nicht gemacht. Bis dahin:
Kilometer und Passhöhen aus dem Profil.

## Grenzen

- Nicht auf einem Gerät geprüft. Die Einblendung ist in Screenshot-Tests
  (Roborazzi, `IndoorTrailScreenshotTest`) zu sehen, über einem gemalten
  Platzhalter statt der 3D-Ansicht.
- Keine 3D-Schilder (Kilometerstein, Passschild, Bogen): optionaler dritter
  Teil, nicht umgesetzt.
- Keine Sprachansage der Anstiege.
