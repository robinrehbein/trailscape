#!/usr/bin/env bash
# Baut den Filament-Referenzrenderer (filarender) gegen die Desktop-Bibliotheken
# derselben Filament-Version wie die App (1.81.0). Laedt beim ersten Lauf das
# Release (~55 MB), nlohmann/json und stb_image_write nach build/.
#
# Andere Version (z. B. fuer Vorher-Bilder): FILAMENT_VER=1.75.1 ./build.sh baut
# nach build-1.75.1/; filashots.mjs nimmt sie mit FILARENDER=…/build-1.75.1/filarender.
#
# Braucht: clang++ mit libc++ (Ubuntu: apt-get install libc++-18-dev libc++abi-18-dev),
# libX11; zum Rendern ohne Bildschirm Xvfb (xvfb-run) mit Mesa (llvmpipe).
set -euo pipefail
cd "$(dirname "$0")"
DEFAULT_VER=1.81.0
VER=${FILAMENT_VER:-$DEFAULT_VER}
B=build
[ "$VER" = "$DEFAULT_VER" ] || B=build-$VER
mkdir -p $B
if [ ! -x $B/filament/bin/matc ]; then
  curl -sSL -o $B/filament.tgz "https://github.com/google/filament/releases/download/v${VER}/filament-v${VER}-linux.tgz"
  tar xzf $B/filament.tgz -C $B
  rm $B/filament.tgz
fi
[ -f $B/json.hpp ] || curl -sSL -o $B/json.hpp https://raw.githubusercontent.com/nlohmann/json/v3.11.3/single_include/nlohmann/json.hpp
[ -f $B/stb_image_write.h ] || curl -sSL -o $B/stb_image_write.h https://raw.githubusercontent.com/nothings/stb/5c205738c191bcb0abc65c4febfa9bd25ff35234/stb_image_write.h
F=$B/filament
# ab 1.8x steckt das WebGPU-Backend (Dawn) als eigene Bibliothek im Release
EXTRA=""
[ -f "$F/lib/x86_64/libwebgpu_dawn.a" ] && EXTRA="-lwebgpu_dawn"
clang++ -O2 -std=c++20 -stdlib=libc++ -I"$F/include" -I$B filarender.cpp -o $B/filarender \
  -L"$F/lib/x86_64" -Wl,--start-group \
  -lgltfio_core -luberarchive -lgltfio -lktxreader -lbasis_transcoder -limage -lstb -ldracodec -lmeshoptimizer -lmikktspace -luberzlib -lzstd \
  -lfilament -lbackend -lbluegl -lbluevk -lfilabridge -lfilaflat -lutils -lgeometry -lsmol-v -libl -labseil -lperfetto -lshaders \
  -lfilament-generatePrefilterMipmap -lfilament-iblprefilter $EXTRA \
  -Wl,--end-group -lpthread -ldl -lc++abi -lX11 2>&1 | grep -v -E "warning|NOTE|^\s*$" || true
test -x $B/filarender && echo "$B/filarender gebaut (Filament $VER)"
