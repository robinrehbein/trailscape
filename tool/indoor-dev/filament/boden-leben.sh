#!/usr/bin/env bash
# Bildsatz „Boden aus der Naehe und Leben" (docs/indoor/bilder/boden-leben/) mit dem
# Filament-Referenzrenderer. Aufruf:
#   boden-leben.sh <welt> <himmel-praefix> <ausgabe>
# <himmel-praefix>-NOON und -EVENING sind Verzeichnisse von `indoor-dev sky`. Die Welt ist der
# Ausschnitt km 1,5–7 der Katalogstrecke „solitude" (MEDIUM, --laser --catalog-aerial).
# vorher/: Ubershader wie bisher, ohne Mitfahrer und Voegel; nachher/: eigene Materialien,
# Mitfahrer, Voegel. Die Fahrer- und Vogelmodelle kommen aus app/build/indoor
# (LifeAndMaterialsTest), das Cockpit aus CockpitAndHandlebarTest.
set -euo pipefail
WORLD=$1; SKY=$2; OUT=$3
cd "$(dirname "$0")/../viewer"
P=900x1200; Q=1600x900
OLD="--set terrainMaterial=0 --set foliageMaterial=0"
LIFE="--riders 16:-0.95:0,70:0.9:1,130:-1.3:2:o,210:0.9:3 --birds 5"
for v in ${ONLY:-vorher nachher}; do
  if [ $v = vorher ]; then X="$OLD"; L=""; else X=""; L="$LIFE"; fi
  F="node filashots.mjs $WORLD --out $OUT/$v $X"
  $F --sky $SKY-NOON --dists 700 --cameras chase --size $P --prefix wald-hoch-
  $F --sky $SKY-NOON --dists 700 --cameras chase --size $Q --prefix wald-quer-
  $F --sky $SKY-NOON --dists 1500 --cameras chase --size $P --prefix kreuzung-hoch-
  $F --sky $SKY-NOON --dists 2500 --cameras chase --size $P --prefix asphalt-hoch-
  $F --sky $SKY-NOON --dists 4000 --cameras chase --size $P --prefix wiese-hoch- $L
  $F --sky $SKY-NOON --dists 4000 --cameras chase --size $Q --prefix wiese-quer- $L
  $F --sky $SKY-EVENING --dists 3300 --cameras chase --size $Q --prefix abend-quer- $L
  $F --sky $SKY-NOON --dists 2500 --cameras handlebar --size $P --prefix lenker-hoch- $L
  $F --sky $SKY-NOON --dists 4000 --cameras handlebar --size $Q --prefix lenker-quer- $L
  $F --sky $SKY-NOON --dists 700 --cameras handlebar --size $P --prefix lenker-wald-hoch-
done
