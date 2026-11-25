#!/usr/bin/env bash
# Bildsatz „Wasser" (docs/indoor/bilder/wasser/) mit dem Filament-Referenzrenderer.
# Aufruf:
#   wasser.sh <welten-dir> <himmel-dir> <ausgabe> [materialien]
# <welten-dir> enthaelt die entpackten Pakete enztal-v2, bottwartal-v2, sa-calobra-v11
# (Release indoor-worlds-v1). <himmel-dir> enthaelt je Ort und Zeit ein Verzeichnis von
# `indoor-dev sky` (enz-NOON, enz-EVENING: lat 48.957 lon 9.052; cal-NOON, cal-EVENING:
# lat 39.84 lon 2.806) samt water_waves.bin (WaterWavesTest schreibt core/build/indoor/
# water_waves.bin). Vorher-Bilder: dieselben Aufrufe mit Material und sky.json von main.
# Danach: vergleich.py <vorher> <nachher> <ziel>.
set -euo pipefail
W=$1; S=$2; OUT=$3; MAT=${4:-}
cd "$(dirname "$0")/../viewer"
F="node filashots.mjs"
M=""; [ -n "$MAT" ] && M="--materials $MAT"
Q=1280x720
for t in NOON EVENING; do
  # Fluss neben der Strecke (Enz), Blick flussaufwaerts gegen die Abendsonne
  $F $W/enztal-v2 --sky $S/enz-$t $M --out $OUT --size $Q --time 3 --dists 7250 --cameras "look:0,1.8,0,13.6,-3,58.4" --prefix fluss-$t-
  # Bruecke ueber die Enz
  $F $W/enztal-v2 --sky $S/enz-$t $M --out $OUT --size $Q --time 3 --dists 5060 --cameras "look:-1,2,-2,-14,-4,10" --prefix bruecke-$t-
  # See im Bottwartal
  $F $W/bottwartal-v2 --sky $S/enz-$t $M --out $OUT --size $Q --time 3 --dists 19085 --cameras "look:0,1.8,0,-52,-3,12" --prefix see-$t-
  # Kueste: Bucht von Sa Calobra von der Strasse oberhalb
  $F $W/sa-calobra-v11 --sky $S/cal-$t $M --out $OUT --size $Q --time 3 --dists 130 --cameras "look:0,25,0,300,-46,-460" --prefix kueste-$t-
  # Fahrerkamera am Fluss (so sieht man es beim Fahren)
  $F $W/enztal-v2 --sky $S/enz-$t $M --out $OUT --size $Q --time 3 --dists 10560 --cameras chase --prefix fahrt-$t-
done
