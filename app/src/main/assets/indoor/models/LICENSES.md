# Modelle des Indoor-Trainings — Herkunft und Lizenz

| Datei | Inhalt |
|---|---|
| `rider.glb` | Gravel-Fahrer mit Rad in Lebensgröße, Animationen `pedal` und `roll`, einfärbbare Zonen (Garage) |
| `rider_lod.glb` | derselbe Fahrer als leichte Detailstufe für Mitfahrer in der Ferne |
| `ghost.glb` | derselbe Fahrer als durchscheinender Geist |
| `trees.glb` | Baumbibliothek: 9 Arten × 4 Varianten × 2 Detailstufen mit Laub- und Rindenatlas |

Alle Dateien sind **selbst erzeugt**: Das Weltbau-Werkzeug dieses Repositorys
berechnet Geometrie, Farben und Animationen vollständig aus Kotlin-Code
(`core/src/main/kotlin/de/trailscape/core/indoor/world/RiderModel.kt`,
Funktion `writeIndoorModels`). Es sind **keine fremden Bestandteile**
enthalten — keine Modelle, Texturen, Scans oder Bewegungsdaten Dritter.

Die Dateien stehen unter derselben Lizenz wie das übrige Repository.

Neu erzeugen: `writeIndoorModels(File("app/src/main/assets/indoor/models"))`.
Die Erzeugung ist deterministisch; der Test `RiderModelTest` prüft, dass die
eingecheckten Dateien Byte für Byte dem Code entsprechen.

`trees.glb` entsteht ebenso vollständig aus Kotlin-Code
(`core/src/main/kotlin/de/trailscape/core/indoor/tree/`, Geometrie und beide
Texturen gezeichnet, keine Fotos oder Scans). Neu erzeugen:
`indoor-dev tree-library --out app/src/main/assets/indoor/models/trees.glb`;
der Test `TreeLibraryAssetTest` prüft, dass die eingecheckte Datei inhaltlich
zum Generator passt. Siehe `docs/indoor/baeume.md`.
