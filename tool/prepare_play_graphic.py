#!/usr/bin/env python3
"""Create the 1024 × 500 Play Store feature graphic from the icon mark."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "assets/play-store/feature-graphic.png"
FONT = "/System/Library/Fonts/SFNS.ttf"

canvas = Image.new("RGB", (1024, 500), "#104537")
draw = ImageDraw.Draw(canvas)
title = ImageFont.truetype(FONT, 82)
tagline = ImageFont.truetype(FONT, 30)
draw.text((64, 162), "Trailscape", fill="#F4EBDD", font=title)
draw.text((69, 279), "Fahr deinen Weg.", fill="#BDD65B", font=tagline)

art = Image.open(ROOT / "assets/icon/icon_foreground.png").convert("RGBA")
art = art.crop(art.getbbox()).resize((400, 400), Image.Resampling.LANCZOS)
canvas.paste(art, (610, 48), art)
OUT.parent.mkdir(parents=True, exist_ok=True)
canvas.save(OUT)
