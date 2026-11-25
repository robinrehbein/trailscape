# PBR-Texturen der 3D-Ansicht — Herkunft und Lizenz

Die Dateien `*_c.ktx` und `*_n.ktx` in diesem Verzeichnis sind **verkleinerte, umgerechnete
Fassungen** freier Materialsätze von **ambientCG** (https://ambientcg.com). Alle Sätze von
ambientCG stehen unter **Creative Commons CC0 1.0 Universal** (gemeinfrei; Kopieren, Ändern,
Weitergeben auch kommerziell ohne Erlaubnis und ohne Namensnennung erlaubt). Geprüft am
25.11.2026 auf https://docs.ambientcg.com/license/ („All ambientCG assets are provided under the
Creative Commons CC0 1.0 Universal License“). Volltext:
https://creativecommons.org/publicdomain/zero/1.0/legalcode

Namensnennung (freiwillig): Created using the materials listed below from ambientCG.com,
licensed under the Creative Commons CC0 1.0 Universal License.

| Satz | Seite | verwendet für | Gruppe (Schicht) |
|---|---|---|---|
| Asphalt 031 | https://ambientcg.com/a/Asphalt031 | Asphalt | `asphalt` (0) |
| Road 013 A | https://ambientcg.com/a/Road013A | alter, rissiger Asphalt (Flecken) | `asphalt` (1) |
| Paving Stones 070 | https://ambientcg.com/a/PavingStones070 | Kopfsteinpflaster | `cobble` (0) |
| Concrete 048 | https://ambientcg.com/a/Concrete048 | Beton, Verbundpflaster, Gehweg, Holz (fein) | `concrete` (0) |
| Gravel 022 | https://ambientcg.com/a/Gravel022 | Schotter | `gravel` (0) |
| Ground 091 | https://ambientcg.com/a/Ground091 | wassergebundene Decke | `gravel` (1) |
| Ground 106 | https://ambientcg.com/a/Ground106 | Erde, Waldweg | `earth` (0) |
| Ground 080 | https://ambientcg.com/a/Ground080 | Sand | `earth` (1) |
| Grass 004 | https://ambientcg.com/a/Grass004 | Wiese, Wiesenweg, Graskante | `terrain` (0) |
| Ground 103 | https://ambientcg.com/a/Ground103 | Acker, Erde, Bankett | `terrain` (1) |
| Ground 082 S | https://ambientcg.com/a/Ground082S | Waldboden | `terrain` (2) |
| Rock 030 | https://ambientcg.com/a/Rock030 | Fels, steile Hänge | `terrain` (3) |

Verarbeitung (reproduzierbar, `tool/indoor-dev/filament/pbr.py`): Quelle jeweils die 1K-JPG-
Fassung; Farbe mit Umgebungsverdeckung verrechnet, grobe Helligkeitsverläufe herausgenommen, auf
Mittel 1 normiert und Farbschwankung gedämpft (die Farbe der Strecke kommt weiter aus Belagsbild
und Luftbild); Normale (OpenGL-Konvention, Y gedreht) und Rauheit übernommen; auf 512² bzw.
1024² gebracht, Mip-Ketten, ETC2/EAC komprimiert. Die Originaldateien liegen nicht im Repository.
