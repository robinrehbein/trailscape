#!/usr/bin/env bash
# Bildsatz „Nahdetail der Fahrbahn" (docs/indoor/bilder/nahdetail/) mit dem Filament-
# Referenzrenderer. Aufruf:
#   nahdetail-bilder.sh <welten> <himmel-praefix> <ausgabe> [vorher-materialien]
# <welten>: Verzeichnis mit den entpackten Release-Paketen (indoor-worlds-v1) alpe-dhuez-v11,
# arenberg-v11, monte-sante-marie-v11, rennsteig-v11.
# <himmel-praefix>-NOON und -EVENING: Verzeichnisse von
#   indoor-dev sky --time NOON|EVENING --lat 48.8 --lon 9.1 --epoch-ms 1781000000000 --out …
# vorher/: die .filamat aus [vorher-materialien] (Stand vor dem Nahdetail, z. B. per
# `git show <stand>:app/src/main/assets/indoor/materials/surface.filamat`); ohne Angabe
# schaltet `--set nearDetail=0` nur das Nahdetail ab. nachher/: Materialien und Karten des Repos.
# Ohne Fahrer (--no-rider), damit die Fahrbahn frei ist; Lenker- und Verfolgerkamera.
set -euo pipefail
W=$1; SKY=$2; OUT=$3; OLD=${4:-}
cd "$(dirname "$0")/../viewer"
for v in ${ONLY:-vorher nachher}; do
  X=""
  if [ $v = vorher ]; then
    if [ -n "$OLD" ]; then X="--materials $OLD"; else X="--set nearDetail=0"; fi
  fi
  R() { local w=$1 s=$2 d=$3 p=$4; node filashots.mjs "$W/$w" --sky "$SKY-$s" --dists $d --cameras "chase;handlebar" \
          --size 900x1200 --out "$OUT/$v" --no-rider --prefix $p- $X; }
  R alpe-dhuez-v11 NOON 5000 asphalt              # Asphalt (Alpe d'Huez)
  R arenberg-v11 NOON 6500 kopfstein              # Kopfsteinpflaster (Wald von Arenberg)
  R monte-sante-marie-v11 NOON 3000 strada        # wassergebundene Decke (Strade Bianche)
  R rennsteig-v11 NOON 1500 schotter              # Schotter im Wald (Rennsteig)
  R rennsteig-v11 NOON 7000 waldweg
  R arenberg-v11 EVENING 6500 kopfstein-abend     # tiefe Sonne
  R monte-sante-marie-v11 EVENING 3000 strada-abend
done
