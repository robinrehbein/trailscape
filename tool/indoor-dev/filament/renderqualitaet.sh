#!/usr/bin/env bash
# Bildsatz „Renderqualitaet" (docs/indoor/bilder/renderqualitaet/): dieselbe Kamera je Strecke
# mit den Grafikstufen Standard (wie bisher), Hoch und Ultra (core RenderTiers / app RenderQuality).
# Aufruf: renderqualitaet.sh <out-dir> <welten-dir> <materialien-dir> [szenen]
#   <welten-dir> enthaelt <id>-v11/ (Release indoor-worlds-v1) und sky-<id>-<ZEIT>/ (indoor-dev sky).
#   <materialien-dir>: .filamat fuer OpenGL+Vulkan (matc -a opengl -a vulkan), denn die Bilder entstehen
#   mit FILARENDER_BACKEND=vulkan (lavapipe): unter llvmpipe-GL zeigt Filament keine PCF-Schatten.
set -euo pipefail
OUT=$1; W=$2; MAT=$3
SCENES=${4:-"alpe-dhuez:EVENING:3000 arenberg:NOON:1500 monte-sante-marie:EVENING:5000 sa-calobra:NOON:3000"}
cd "$(dirname "$0")/../viewer"
export FILARENDER_BACKEND=vulkan
STANDARD=""  # seit dem Geraetebefund auch PCF (Vorgabe des Referenzrenderers)
HIGH="--set reflections=1 --set ambientOcclusion=1 --set bloom=1 --seti effectQuality=1 --set whiteBalance=1"
ULTRA="$HIGH --seti effectQuality=2 --set taa=1 --frames 16 --seti cascades=4 --seti shadowFarM=800 --set stableShadows=1 --set terrainShadows=1 --set vignette=1"
for sc in $SCENES; do
  IFS=: read -r id t d <<< "$sc"
  for tier in standard hoch ultra; do
    case $tier in standard) X=$STANDARD;; hoch) X=$HIGH;; ultra) X=$ULTRA;; esac
    node filashots.mjs "$W/$id-v11" --sky "$W/sky-$id-$t" --dists "$d" --cameras chase --size 900x1200 \
      --materials "$MAT" --out "$OUT" --prefix "$id-$t-$tier-" $X | tail -1 | cut -c1-80
  done
done
