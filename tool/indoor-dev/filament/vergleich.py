#!/usr/bin/env python3
"""Vorher/Nachher-Gegenueberstellungen fuer docs/indoor/bilder/licht-kamera/.

Aufruf: vergleich.py <vorher-dir> <nachher-dir> <ziel-dir>
Gleich benannte PNG aus beiden Verzeichnissen (von licht-kamera.sh) werden
nebeneinander (Hochformat) bzw. uebereinander (Querformat) gesetzt, beschriftet
und als JPEG gespeichert. Braucht Pillow.
"""
import glob
import os
import sys

from PIL import Image, ImageDraw, ImageFont

src_a, src_b, dst = sys.argv[1:4]
os.makedirs(dst, exist_ok=True)
try:
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 34)
except OSError:
    font = ImageFont.load_default()


def label(img, text):
    d = ImageDraw.Draw(img)
    x, y = 18, 14
    box = d.textbbox((x, y), text, font=font)
    d.rectangle((box[0] - 10, box[1] - 6, box[2] + 10, box[3] + 8), fill=(0, 0, 0))
    d.text((x, y), text, fill=(255, 255, 255), font=font)
    return img


for fa in sorted(glob.glob(os.path.join(src_a, "*.png"))):
    name = os.path.basename(fa)
    fb = os.path.join(src_b, name)
    if not os.path.exists(fb):
        continue
    a = label(Image.open(fa).convert("RGB"), "vorher")
    b = label(Image.open(fb).convert("RGB"), "nachher")
    w, h = a.size
    if h > w:  # Hochformat: nebeneinander
        out = Image.new("RGB", (2 * w + 12, h), (255, 255, 255))
        out.paste(a, (0, 0))
        out.paste(b, (w + 12, 0))
    else:  # Querformat: uebereinander, auf 1280 breit
        out = Image.new("RGB", (w, 2 * h + 12), (255, 255, 255))
        out.paste(a, (0, 0))
        out.paste(b, (0, h + 12))
        out = out.resize((1280, round(out.height * 1280 / w)), Image.LANCZOS)
    stem = name[:-4]
    out.save(os.path.join(dst, f"{stem}.jpg"), quality=80, optimize=True)
    print(stem, out.size)
