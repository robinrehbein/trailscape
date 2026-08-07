#!/usr/bin/env python3
"""Erzeugt die App-Icons fuer Trailscape.

Ausgabe:
  assets/icon/icon.png             1024x1024, Vollbild (dunkelgruener Hintergrund)
  assets/icon/icon_foreground.png  1024x1024, nur Motiv auf Transparenz (adaptive icon)

Motiv: flache Bergsilhouette in zwei Gruentoenen mit einem vorgelagerten
Huegel, darauf ein gestrichelter heller Gravel-Trail, der sich von unten
zwischen die Berge schlaengelt; oben rechts eine kleine Sonne.

Gezeichnet wird 4x supersampled und anschliessend mit LANCZOS herunter-
gerechnet (Antialiasing). Das komplette Motiv wird automatisch so skaliert,
dass es mit etwas Rand in einen zentrierten Kreis mit Durchmesser 680 px
passt (~66 % der Kantenlaenge) - adaptive Icons werden beschnitten.

Aufruf:  python3 tool/generate_app_icon.py
"""

from __future__ import annotations

import math
import os

from PIL import Image, ImageDraw

# --- Konstanten ------------------------------------------------------------

SIZE = 1024  # Kantenlaenge der Ausgabe
SS = 4  # Supersampling-Faktor

BG = (0x2D, 0x5A, 0x3D, 255)  # Markenfarbe dunkelgruen
MTN_BACK = (0x4A, 0x7A, 0x5C, 255)  # hintere Bergkette
MTN_FRONT = (0x6F, 0xA0, 0x80, 255)  # vorderer Huegel
TRAIL = (0xF2, 0xED, 0xE4, 255)  # Weg (creme)
SUN = (0xE8, 0xC1, 0x5A, 255)  # Sonne

CENTER = 512.0
SAFE_RADIUS = 312.0  # Zielradius des Motivs; laesst im 66-%-Kreis Luft

# --- Rohgeometrie (Entwurfskoordinaten, werden automatisch eingepasst) -----

# Hintere Bergkette: zwei Gipfel, Fuss hinter dem Huegel verdeckt.
MTN_BACK_POLY = [
    (300, 712),
    (415, 322),
    (530, 500),
    (645, 392),
    (742, 712),
]

# Vorgelagerter Huegel: sanft welliger Kamm, unten runder Abschluss (Ellipse).
HILL_CREST = [
    (256, 706),
    (330, 622),
    (430, 588),
    (512, 612),
    (604, 578),
    (700, 626),
    (770, 706),
]
HILL_BASE_CENTER = (513.0, 706.0)
HILL_BASE_RX = 257.0
HILL_BASE_RY = 90.0


def _hill_polygon():
    """Kamm oben, dazu ein elliptischer Bogen als unterer Abschluss."""
    poly = list(HILL_CREST)
    steps = 48
    for i in range(1, steps):
        a = math.pi * i / steps  # 0 = rechts, pi = links
        poly.append(
            (
                HILL_BASE_CENTER[0] + HILL_BASE_RX * math.cos(a),
                HILL_BASE_CENTER[1] + HILL_BASE_RY * math.sin(a),
            )
        )
    return poly


SUN_CENTER = (716, 342)
SUN_RADIUS = 42.0

# Stuetzpunkte des Trails (von unten nach oben, wird gespliniert)
TRAIL_POINTS = [
    (516, 782),
    (430, 716),
    (592, 646),
    (506, 566),
    (534, 522),
]

# Strichstaerke / Strichlaenge / Luecke jeweils unten -> oben (Perspektive)
TRAIL_WIDTH = (30.0, 11.0)
DASH_LEN = (48.0, 17.0)
DASH_GAP = (28.0, 13.0)


# --- Einpassen in den Sicherheitskreis -------------------------------------


HILL_POLY = _hill_polygon()


def _all_points():
    return list(MTN_BACK_POLY) + list(HILL_POLY) + list(TRAIL_POINTS)


def fit_transform():
    """Verschiebung + Skalierung, damit das Motiv den Sicherheitskreis fuellt."""
    pts = _all_points()
    xs = [p[0] for p in pts] + [SUN_CENTER[0] - SUN_RADIUS, SUN_CENTER[0] + SUN_RADIUS]
    ys = [p[1] for p in pts] + [SUN_CENTER[1] - SUN_RADIUS, SUN_CENTER[1] + SUN_RADIUS]
    dx = CENTER - (min(xs) + max(xs)) / 2
    dy = CENTER - (min(ys) + max(ys)) / 2

    half = max(TRAIL_WIDTH) / 2
    worst = 0.0
    for x, y in pts:
        worst = max(worst, math.hypot(x + dx - CENTER, y + dy - CENTER) + half)
    worst = max(
        worst,
        math.hypot(SUN_CENTER[0] + dx - CENTER, SUN_CENTER[1] + dy - CENTER)
        + SUN_RADIUS,
    )
    return dx, dy, SAFE_RADIUS / worst


DX, DY, FIT = fit_transform()


# --- Hilfsfunktionen -------------------------------------------------------


def catmull_rom(points, samples_per_segment=90):
    """Catmull-Rom-Spline durch alle Stuetzpunkte."""
    pts = [points[0]] + list(points) + [points[-1]]
    curve = []
    for i in range(len(pts) - 3):
        p0, p1, p2, p3 = pts[i], pts[i + 1], pts[i + 2], pts[i + 3]
        for j in range(samples_per_segment):
            t = j / samples_per_segment
            t2, t3 = t * t, t * t * t
            x = 0.5 * (
                2 * p1[0]
                + (-p0[0] + p2[0]) * t
                + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
                + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3
            )
            y = 0.5 * (
                2 * p1[1]
                + (-p0[1] + p2[1]) * t
                + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
                + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3
            )
            curve.append((x, y))
    curve.append(tuple(points[-1]))
    return curve


def arc_lengths(curve):
    lengths = [0.0]
    for i in range(1, len(curve)):
        dx = curve[i][0] - curve[i - 1][0]
        dy = curve[i][1] - curve[i - 1][1]
        lengths.append(lengths[-1] + math.hypot(dx, dy))
    return lengths


def point_at(curve, lengths, dist):
    """Punkt auf der Kurve bei gegebener Bogenlaenge."""
    dist = max(0.0, min(dist, lengths[-1]))
    lo, hi = 0, len(lengths) - 1
    while lo < hi - 1:
        mid = (lo + hi) // 2
        if lengths[mid] <= dist:
            lo = mid
        else:
            hi = mid
    span = lengths[hi] - lengths[lo]
    t = 0.0 if span <= 0 else (dist - lengths[lo]) / span
    return (
        curve[lo][0] + (curve[hi][0] - curve[lo][0]) * t,
        curve[lo][1] + (curve[hi][1] - curve[lo][1]) * t,
    )


def lerp(a, b, t):
    return a + (b - a) * t


class Canvas:
    """Zeichenflaeche mit Transformation Entwurfs- -> Pixelkoordinaten."""

    def __init__(self, scale=1.0):
        self.img = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
        self.draw = ImageDraw.Draw(self.img)
        self.k = FIT * scale

    def tp(self, p):
        return (
            (CENTER + (p[0] + DX - CENTER) * self.k) * SS,
            (CENTER + (p[1] + DY - CENTER) * self.k) * SS,
        )

    def polygon(self, pts, color):
        self.draw.polygon([self.tp(p) for p in pts], fill=color)

    def disc(self, center, radius, color):
        cx, cy = self.tp(center)
        r = radius * self.k * SS
        self.draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)

    def thick_segment(self, p1, p2, width, color):
        a, b = self.tp(p1), self.tp(p2)
        w = width * self.k * SS
        self.draw.line([a, b], fill=color, width=max(1, int(round(w))))
        for c in (a, b):  # runde Enden
            self.draw.ellipse(
                [c[0] - w / 2, c[1] - w / 2, c[0] + w / 2, c[1] + w / 2], fill=color
            )

    def finish(self):
        return self.img.resize((SIZE, SIZE), Image.LANCZOS)


def draw_trail(canvas):
    curve = catmull_rom(TRAIL_POINTS)
    lengths = arc_lengths(curve)
    total = lengths[-1]

    pos = 0.0
    while pos < total - 4:
        t = pos / total
        dash = lerp(DASH_LEN[0], DASH_LEN[1], t)
        gap = lerp(DASH_GAP[0], DASH_GAP[1], t)
        end = min(pos + dash, total)
        # Strich in kleinen Teilstuecken zeichnen, damit er der Kurve folgt
        steps = max(2, int((end - pos) / 4) + 1)
        prev = point_at(curve, lengths, pos)
        for s in range(1, steps + 1):
            d = pos + (end - pos) * s / steps
            cur = point_at(curve, lengths, d)
            width = lerp(TRAIL_WIDTH[0], TRAIL_WIDTH[1], d / total)
            canvas.thick_segment(prev, cur, width, TRAIL)
            prev = cur
        pos = end + gap


def draw_motif(canvas):
    canvas.disc(SUN_CENTER, SUN_RADIUS, SUN)
    canvas.polygon(MTN_BACK_POLY, MTN_BACK)
    canvas.polygon(HILL_POLY, MTN_FRONT)
    draw_trail(canvas)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, "assets", "icon")
    os.makedirs(out_dir, exist_ok=True)

    print(f"Einpassung: shift=({DX:.1f}, {DY:.1f}) scale={FIT:.3f}")

    # Adaptive-Icon-Vordergrund: Motiv auf Transparenz, im Sicherheitskreis.
    fg = Canvas()
    draw_motif(fg)
    fg_path = os.path.join(out_dir, "icon_foreground.png")
    fg.finish().save(fg_path)
    print(f"geschrieben: {fg_path}")

    # Vollbild-Icon: gleiches Motiv, etwas groesser, auf Markenfarbe.
    full = Canvas(scale=1.38)
    draw_motif(full)
    base = Image.new("RGBA", (SIZE, SIZE), BG)
    base.alpha_composite(full.finish())
    icon_path = os.path.join(out_dir, "icon.png")
    base.convert("RGB").save(icon_path)
    print(f"geschrieben: {icon_path}")


if __name__ == "__main__":
    main()
