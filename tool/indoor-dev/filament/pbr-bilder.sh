#!/usr/bin/env bash
# Bildsatz „PBR-Texturen" (docs/indoor/bilder/pbr/) mit dem Filament-Referenzrenderer auf
# lavapipe-Vulkan (PCF-Schatten). Aufruf:
#   pbr-bilder.sh <welten> <himmel-praefix> <ausgabe> <vorher-assets> <pbr-entpackt>
# <welten>: entpackte Release-Pakete (indoor-worlds-v1) alpe-dhuez-v11, arenberg-v11,
#   monte-sante-marie-v11, rennsteig-v11.
# <himmel-praefix>-NOON / -EVENING: `indoor-dev sky --time NOON|EVENING --lat 48.8 --lon 9.1
#   --epoch-ms 1781000000000 --out …`.
# <vorher-assets>/materials: .filamat des Stands davor, mit `matc -a opengl -a vulkan` uebersetzt
#   (daneben surface-detail/ als Verweis); nachher: dieselben Quellen dieses Stands, ebenfalls fuer
#   Vulkan, in <ausgabe>/mat (wird hier erzeugt).
# <pbr-entpackt>: `pbr.py decode app/src/main/assets/indoor/pbr <dir>` (lavapipe kann kein ETC2).
set -euo pipefail
W=$1; SKY=$2; OUT=$3; OLD=$4; RAW=$5
F="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT/mat/materials"
for m in "$F"/../../../app/src/main/materials/*.mat; do
  "$F/build/filament/bin/matc" -a opengl -a vulkan -p all -o "$OUT/mat/materials/$(basename "$m" .mat).filamat" "$m" >/dev/null
done
ln -sfn "$F/../../../app/src/main/assets/indoor/surface-detail" "$OUT/mat/surface-detail"
cd "$F/../viewer"
export FILARENDER_BACKEND=vulkan FILARENDER_PBR_DIR=$RAW
for v in vorher nachher; do
  M="$OUT/mat/materials"; [ $v = vorher ] && M="$OLD/materials"
  R() { node filashots.mjs "$W/$1" --sky "$SKY-$2" --dists $3 --cameras "chase;handlebar" \
          --size 900x1200 --out "$OUT/$v" --no-rider --prefix $4- --materials "$M"; }
  R alpe-dhuez-v11 NOON 5000 asphalt
  R arenberg-v11 NOON 6500 kopfstein
  R monte-sante-marie-v11 NOON 3000 strada
  R rennsteig-v11 NOON 1500 schotter
  R rennsteig-v11 NOON 7000 waldweg
  R arenberg-v11 EVENING 6500 kopfstein-abend
  R monte-sante-marie-v11 EVENING 3000 strada-abend
done
