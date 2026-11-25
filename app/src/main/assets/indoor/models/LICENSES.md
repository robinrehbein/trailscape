# Modelle des Indoor-Trainings — Herkunft und Lizenz

| Datei | Inhalt |
|---|---|
| `rider.glb` | Gravel-Fahrer mit Rad in Lebensgröße, Animationen `pedal` und `roll`, einfärbbare Zonen (Garage) |
| `rider_lod.glb` | derselbe Fahrer als leichte Detailstufe für Mitfahrer in der Ferne |
| `ghost.glb` | derselbe Fahrer als durchscheinender Geist |

Alle Dateien sind **selbst erzeugt**: Das Weltbau-Werkzeug dieses Repositorys
berechnet Geometrie, Farben und Animationen vollständig aus Kotlin-Code
(`core/src/main/kotlin/de/trailscape/core/indoor/world/RiderModel.kt`,
Funktion `writeIndoorModels`). Es sind **keine fremden Bestandteile**
enthalten — keine Modelle, Texturen, Scans oder Bewegungsdaten Dritter.

Die Dateien stehen unter derselben Lizenz wie das übrige Repository.

Neu erzeugen: `writeIndoorModels(File("app/src/main/assets/indoor/models"))`.
Die Erzeugung ist deterministisch; der Test `RiderModelTest` prüft, dass die
eingecheckten Dateien Byte für Byte dem Code entsprechen.
