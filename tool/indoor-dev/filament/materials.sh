#!/usr/bin/env bash
# Kompiliert die eigenen Filament-Materialien der Indoor-Darstellung
# (app/src/main/materials/*.mat → app/src/main/assets/indoor/materials/*.filamat)
# mit matc derselben Version wie die App (1.75.1, aus build.sh). Nur OpenGL
# (Geraet: OpenGL ES; Referenzrenderer: Desktop-GL). Die .filamat liegen im Repo,
# der Gradle-Bau braucht matc nicht.
set -euo pipefail
cd "$(dirname "$0")"
[ -x build/filament/bin/matc ] || ./build.sh
SRC=../../../app/src/main/materials
DST=../../../app/src/main/assets/indoor/materials
mkdir -p "$DST"
for m in "$SRC"/*.mat; do
  n=$(basename "$m" .mat)
  build/filament/bin/matc -a opengl -p all -o "$DST/$n.filamat" "$m"
  echo "$n.filamat $(stat -c %s "$DST/$n.filamat") Bytes"
done
