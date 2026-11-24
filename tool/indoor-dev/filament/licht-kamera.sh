#!/usr/bin/env bash
# Bildsatz „Licht und Kamera" (docs/indoor/bilder/licht-kamera/) mit dem
# Filament-Referenzrenderer. Aufruf:
#   licht-kamera.sh <welt> <himmel-praefix> <ausgabe> [v1|v2] [materialien]
# <himmel-praefix>-AUTO, -MORNING, -NOON, -EVENING sind Verzeichnisse von `indoor-dev sky`.
# AUTO ist die Daemmerung des Testabends des Gruenders (24.11., 18:19 Uhr, Solitude).
set -euo pipefail
WORLD=$1; SKY=$2; OUT=$3; RIG=${4:-v2}; MAT=${5:-}
cd "$(dirname "$0")/../viewer"
F="node filashots.mjs $WORLD --rig $RIG --out $OUT"
[ -n "$MAT" ] && F="$F --materials $MAT"
P=900x1200; Q=1600x900
for t in AUTO MORNING NOON EVENING; do
  $F --sky $SKY-$t --dists 5500 --cameras chase --size $P --prefix licht-$t-hoch-
  $F --sky $SKY-$t --dists 5500 --cameras chase --size $Q --prefix licht-$t-quer-
done
for t in AUTO NOON; do
  $F --sky $SKY-$t --dists 2200 --cameras chase --size $P --prefix wald-$t-hoch-
  $F --sky $SKY-$t --dists 6600 --cameras chase --size $Q --prefix dunst-$t-quer-
done
for t in NOON EVENING; do
  $F --sky $SKY-$t --dists 6230 --cameras "look:-6,9,0,-40,-1,25" --size $Q --prefix wasser-$t-quer- --time 3
done
for d in 2200 9560 2650 6070; do
  $F --sky $SKY-MORNING --dists $d --cameras handlebar --size $P --prefix lenker-hoch-
  $F --sky $SKY-MORNING --dists $d --cameras handlebar --size $Q --prefix lenker-quer-
done
