# Indoor — Garage: Fahrer und Rad anpassen (Etappe 2)

Stand: 25.11.2026, Branch `claude/spiel-2-garage`. Grundlage:
`docs/konzept-spiel-community.md`, Abschnitte 1.3, 1.4, 3, 7, 8.

Rein kosmetisch. Nichts hier ändert Physik, Leistung, Widerstand oder
Vergleiche; `RiderSetup` (Masse, CdA, Rollwiderstand) bleibt unabhängig vom
Aussehen.

## Was gebaut ist

| Teil | Wo | Kurz |
|---|---|---|
| Fahrermodell mit Zonen | `core/…/indoor/world/RiderModel.kt` | 14 einfärbbare Materialien (`RiderZone`) mit grauen Vertexfarben, Vorgabefarbe in `baseColorFactor`; feste Teile behalten ihre Vertexfarben (`x_mat_hair`, `x_mat_kit` = Socken/Handschuhe, `x_mat_gear`, `x_mat_bike`, `x_mat_tire`) |
| LOD-Stufe | `rider_lod.glb` (4 788 Dreiecke, 190 kB) | dieselben Knoten, Animationen und Zonen; ohne Kette, Speichen, Finger, Flaschenhalter, Helmschlitze, Kassette, Bremsscheiben |
| Geist | `ghost.glb` | unverändert, Byte für Byte |
| Avatar-Format | `core/…/garage/RiderAvatar.kt` | kleines JSON v1, ohne Freitext; nachsichtiges Lesen |
| Paletten, Muster | `GaragePalettes.kt`, `RiderLook.kt` | 21 benannte Farben, 8 Hauttöne; Muster einfarbig, Brustband (Vorgabe = bisheriges Trikot), zweifarbig, Farbverlauf, Streifen |
| Freischaltungen | `GarageUnlocks.kt` | Schnittstelle `GarageProgress` (Stufe, Abzeichen); ohne Erfolge (`progress = null`) ist **alles frei** |
| Einfärben im Renderer | `app/…/indoor/render/RiderTint.kt` | je Instanz `baseColorFactor`, nur nach `Material.hasParameter` |
| Mitfahrer | `LifeLayer.kt` | ein Asset je Platz, eingefärbt (sechs feste, unterscheidbare Trikots, nie das des Nutzers); ab 30 m die LOD-Stufe |
| Bildschirm | `app/…/ui/indoor/garage/` | Ziel `indoor/garage`; Einstieg aus Indoor-Einstellungen (Abschnitt 3D-Ansicht) und Paletten-Symbol oben auf der Indoor-Startseite |
| Backup | `core/…/Export.kt` | optionaler Schlüssel `avatar`, nur wenn je etwas gewählt wurde |

### Zonen und Muster

Das Trikot ist in Felder geteilt (unten, Mitte, Brust, oben, Schultern mit
Ärmeln, Seitenteile, Säume). Ein Muster legt fest, welches Feld Grundfarbe,
Akzentfarbe oder eine Mischung bekommt — keine Texturen, keine neue Geometrie
je Muster. Säume und Seitenteile sind die Grundfarbe, linear auf 38 %
abgedunkelt (wie früher `0x8C2519` zu `0xD8432C`).

Der Generator bemalt die Felder mit Markierungsfarben und verteilt die
Dreiecke beim Schreiben nach der Farbe ihres ersten Eckpunkts auf die
Materialien (`splitZones`). In der Datei stehen danach nur Grau (Zonen) und
echte Farben (feste Teile); ein Test prüft das.

### Format

```json
{"v":1,"body":{"skin":2},
 "kit":{"jersey":"#D8432C","jerseyAccent":"#F1EEE8","pattern":"band","bibs":"#25262B","shoes":"#F1EEE8","helmet":"#F1EEE8"},
 "bike":{"frame":"#1F6272","frameAccent":"#F1EEE8","barTape":"#25262B"}}
```

Gespeichert unter `garage_avatar` in `trailscape_prefs` (vom Systembackup
ausgenommen, wie alles dort), im Backup unter `avatar`. Unbekannte Felder und
Kennungen werden überlesen, ungültige Werte nehmen die Vorgabe; gültige
Farben außerhalb der Paletten bleiben erhalten. Ältere App-Versionen lesen ein
Backup mit `avatar` problemlos (sie lesen nur bekannte Schlüssel) — damit ist
die offene Frage aus Konzept 2.6/8 für diesen Schlüssel beantwortet.

## Sicherheit im Renderer

- **Kein Parameter ohne Prüfung.** `RiderTint` fragt vor jedem Setzen
  `MaterialInstance.getMaterial().hasParameter("baseColorFactor")` (in den
  Quellen von Filament 1.75.1 nachgesehen: `Material.java`, `hasParameter`).
  Fehlt er, passiert still nichts. Jede Instanz ist einzeln in `runCatching`.
- **Keine neuen glTF-Erweiterungen**, keine Texturen. Alle Zonen behalten
  `COLOR_0` (Grau 254 statt Weiß), damit gltfio dieselbe
  Ubershader-Variante wie bisher wählt — kein neues Shaderprogramm.
- **Sicherer Grafikmodus** (`RenderGuard`): zur Laufzeit wird nichts gesetzt.
  Die Farben des eigenen Fahrers werden vor dem Laden in die GLB geschrieben
  (`RiderGlbColors`, nur `baseColorFactor` im JSON-Teil, Binärblock
  unverändert), gltfio liest sie wie bisher. Andere Radfahrer sind dort wie
  vorher aus.
- Hat das Modell keine Zonen (eingebauter Ersatzfahrer `AvatarModel`), bekommt
  nur dessen `x_mat_kit` die Trikotfarbe.

## 2D- statt 3D-Vorschau

Die Vorschau ist eine **gezeichnete Seitenansicht** (`GaragePreview`) mit
denselben Feldern wie das Modell. Eine 3D-Vorschau hätte eine zweite
Filament-Darstellung außerhalb der Fahrt gebraucht (eigene Engine oder eine
Szene ohne Strecke) — ein weiterer nativer Pfad, der auf manchen Geräten den
Prozess beenden kann, und die parallel entstehende 3D-Vorschau ist noch nicht
da. Sobald es sie gibt und sie einen Fahrer allein zeigen kann, lässt sich die
Zeichnung ersetzen; `RiderTint.apply(asset, avatar)` passt dafür unverändert.

## Freischaltungen (vorbereitet, noch ohne Wirkung)

| Teil | Bedingung (Vorschlag) |
|---|---|
| alle Grundfarben, alle Hauttöne, Einfarbig, Brustband | immer frei |
| Zweifarbig | Stufe 2 |
| Streifen | Stufe 5 |
| Farbverlauf | Abzeichen 26 „Schneller oben“ (`faster_up`) |
| Gold | Abzeichen 10 „Everest“ (`everest`) |
| Kupfer | Stufe 10 |

Beim Zusammenführen mit `claude/spiel-1-erfolge`: die Abzeichen-Kennungen in
`GarageUnlocks` an die der Erfolge angleichen und in `GarageDestination` statt
`progress = null` den Fortschritt hereinreichen. Gesperrtes zeigt die
Bedingung im Text („Zweifarbig · ab Stufe 2“) und im Vorlesetext.

## Budget

| | Dreiecke | Primitive (≈ Draw Calls ohne Schatten) |
|---|---|---|
| `rider.glb` | ≈ 30 000 (wie bisher) | ≤ 48 (vorher etwa 25) |
| `rider_lod.glb` | 4 788 | ≤ 48 |

Mehr Materialien heißen mehr Draw Calls je Fahrer. Gemessen auf einem Gerät
ist das **nicht** (siehe unten).

## Was der Inhaber auf dem Gerät prüfen muss

1. **Farben:** Garage öffnen (Indoor-Startseite → Paletten-Symbol, oder
   Einstellungen → Garage), Trikot, Muster, Hose, Helm, Schuhe, Rahmen,
   Rahmen-Akzent, Lenkerband, Hautton ändern; dann eine Strecke starten.
   Stimmt der Fahrer in der Verfolgerkamera mit der Vorschau überein? Sind die
   Feldgrenzen am Trikot sauber (keine Zacken, keine falsch gefärbten
   Dreiecke an Brust, Seiten, Kragen, Ärmelabschluss, Lenker)? Sieht die
   Vorgabe aus wie vorher (rotes Trikot, helles Brustband)?
2. **Andere Radfahrer:** Einstellung „Andere Radfahrer anzeigen“ an. Sechs
   verschiedene Trikots; nie dasselbe wie das eigene (z. B. eigenes Trikot
   Blau wählen: der blaue Mitfahrer wird violett). In der Ferne (> 30 m)
   wechseln sie auf die grobe Stufe — der Wechsel sollte kaum auffallen.
3. **Bildrate mit mehreren Fahrern:** Qualität HIGH (5 andere Fahrer) und LOW
   (2), Diagnoseseite: Bildzeit mit und ohne andere Radfahrer vergleichen,
   besonders auf einem schwachen Gerät mit LOW. Ziel: kein merklicher
   Unterschied zu vorher.
4. **Sicherer Grafikmodus:** auf der Diagnoseseite ist er sonst nur nach
   einem Absturz aktiv. Prüfen, dass der eigene Fahrer dort in den gewählten
   Farben erscheint (Farben aus der Datei) und keine anderen Fahrer da sind;
   die Garage zeigt dann einen Hinweis.
5. **Kein Absturz** beim Starten mehrerer Strecken hintereinander mit
   wechselnden Farben dazwischen (Garage zwischen zwei Fahrten öffnen).
6. **Backup:** Sichern, Farben ändern, Backup einspielen — die gesicherten
   Farben sind wieder da.

## Offen

- Draw Calls und Bildrate mit Zonen und mehreren Fahrern sind nicht gemessen.
- Gesehen hat die Zonen niemand auf einem Gerät; in der Entwicklungsumgebung
  gibt es keine GPU.
- Radtyp, Ärmel, Haarfarbe, Socken aus dem Konzept 3.6 sind nicht umgesetzt;
  das Format überliest sie und lässt Platz dafür.
- Die Vorschau ist 2D (siehe oben).
