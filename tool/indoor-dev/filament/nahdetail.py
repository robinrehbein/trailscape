#!/usr/bin/env python3
# Nahdetail der Belaege: erzeugt die kachelbaren Detailkarten fuer surface.mat/surface_blend.mat
# (app/src/main/assets/indoor/surface-detail/<karte>.jpg) und deren Parameter je Belag
# (detail.json, von der App — SurfaceNearDetail.kt — und vom Referenzrenderer filarender gelesen).
#
# Jede Karte ist ein RGB-Bild, in beiden Richtungen periodisch:
#   R, G  Neigung der Oberflaeche (dH/dx, dH/dz) als 0,5 ± Gefaelle, x = Osten, z = Sueden
#         (Weltkoordinaten: das Material legt die Karte ueber getUserWorldPosition().xz auf);
#   B     Hoehe bzw. Hohlraum (0,5 = Mittel; Poren, Fugen und Luecken dunkel, Kornspitzen hell).
# Das Material leitet daraus Detail-Normale, Helligkeit (Mikroschatten in Poren) und Rauheit
# (polierte Kornspitzen etwas glatter) ab.
#
# Alles hier gezeichnet: band-begrenztes Rauschen ueber die FFT (dadurch automatisch kachelbar)
# und periodische Voronoi-Zellen fuer Steine. Kein fremdes Bild, keine Lizenzfrage; feste
# Startwerte, also reproduzierbar (gleiche numpy/Pillow-Versionen → gleiche Bytes; die Optik
# haengt davon nicht ab).
#
# Aufruf: python3 tool/indoor-dev/filament/nahdetail.py [--size 512] [--preview <dir>]
# Braucht numpy und Pillow (nur zum Erzeugen; App und Gradle-Bau brauchen das nicht).
import argparse
import json
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "..", "app", "src", "main", "assets", "indoor", "surface-detail")

# ------------------------------------------------------------------------------------------
# Bausteine
# ------------------------------------------------------------------------------------------


def band(n, lo, hi, seed, slope=0.0):
    """Periodisches Rauschen mit Wellenlaengen zwischen n/hi und n/lo Texeln (Mittel 0, Std 1)."""
    rng = np.random.RandomState(seed)
    w = rng.standard_normal((n, n))
    f = np.fft.fft2(w)
    k = np.fft.fftfreq(n) * n
    kx, ky = np.meshgrid(k, k)
    r = np.sqrt(kx * kx + ky * ky)
    m = (r >= lo) & (r <= hi)
    amp = np.where(m, np.maximum(r, 1.0) ** (-slope), 0.0)
    out = np.real(np.fft.ifft2(f * amp))
    out -= out.mean()
    return out / (out.std() + 1e-9)


def voronoi(n, cells, seed, jitter=0.9):
    """Periodische Voronoi-Zellen: F1, F2 (in Zellbreiten) und Zellnummer je Texel."""
    rng = np.random.RandomState(seed)
    pts = (rng.rand(cells, cells, 2) - 0.5) * jitter + 0.5  # Lage in der Zelle
    ys, xs = np.mgrid[0:n, 0:n].astype(np.float64)
    gx = xs * cells / n
    gy = ys * cells / n
    cx = np.floor(gx).astype(int)
    cy = np.floor(gy).astype(int)
    f1 = np.full((n, n), 9.0)
    f2 = np.full((n, n), 9.0)
    idx = np.zeros((n, n), dtype=int)
    for oy in (-1, 0, 1):
        for ox in (-1, 0, 1):
            ix = cx + ox
            iy = cy + oy
            p = pts[iy % cells, ix % cells]
            dx = ix + p[..., 0] - gx
            dy = iy + p[..., 1] - gy
            d = np.sqrt(dx * dx + dy * dy)
            cid = (iy % cells) * cells + (ix % cells)
            closer = d < f1
            f2 = np.where(closer, f1, np.minimum(f2, d))
            idx = np.where(closer, cid, idx)
            f1 = np.where(closer, d, f1)
    return f1, f2, idx


def strokes(n, count, seed, length, width, value, angle=None):
    """Kurze Striche (Halme, Nadeln, Zweige) als Hoehenbeitrag, periodisch."""
    rng = np.random.RandomState(seed)
    h = np.zeros((n, n))
    for _ in range(count):
        x0, y0 = rng.rand(2) * n
        a = rng.rand() * 2 * np.pi if angle is None else angle + (rng.rand() - 0.5) * 0.6
        ln = length[0] + rng.rand() * (length[1] - length[0])
        v = value * (0.6 + 0.4 * rng.rand())
        steps = max(2, int(ln * 2))
        for s in range(steps + 1):
            t = s / steps
            px = x0 + np.cos(a) * ln * t
            py = y0 + np.sin(a) * ln * t
            ix = int(px) % n
            iy = int(py) % n
            w = int(width)
            for dy in range(-w, w + 1):
                for dx in range(-w, w + 1):
                    fall = max(0.0, 1.0 - (dx * dx + dy * dy) ** 0.5 / (width + 0.5))
                    h[(iy + dy) % n, (ix + dx) % n] = max(h[(iy + dy) % n, (ix + dx) % n], v * fall * (0.5 + 0.5 * t))
    return h


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def norm01(h):
    """Auf Mittel 0,5 und robuste Spanne bringen (1.–99. Perzentil → 0,1..0,9)."""
    lo, hi = np.percentile(h, [1, 99])
    out = (h - lo) / max(hi - lo, 1e-9) * 0.8 + 0.1
    out += 0.5 - out.mean()
    return np.clip(out, 0.0, 1.0)


# ------------------------------------------------------------------------------------------
# Belaege (Hoehe 0..1, Mittel 0,5). Massstab: eine Karte deckt `repeatM` Meter (detail.json).
# ------------------------------------------------------------------------------------------


def asphalt(n):
    # 0,75 m je Karte → 1,5 mm je Texel: Splitt 3–8 mm, Poren dazwischen, feine Koernung.
    grit = band(n, n / 8, n / 2.5, 101)                 # Korn 3–8 Texel
    fine = band(n, n / 2.5, n / 1.2, 102)               # Mikrokoernung
    mid = band(n, n / 40, n / 8, 103)                   # Unebenheit 1–5 cm
    pores = smooth(-1.2, -2.2, band(n, n / 6, n / 2, 104))   # dunkle Poren
    f1, f2, _ = voronoi(n, 96, 105)
    chips = smooth(0.0, 0.18, f2 - f1) * (1.0 - 0.8 * f1)    # einzelne Splittkoerner
    h = 0.35 * grit + 0.18 * fine + 0.25 * mid + 0.45 * chips - 1.4 * pores
    return norm01(h)


def concrete(n):
    # 1,0 m je Karte: sehr feine Koernung, Luftporen, leichte Wellen der Abziehbohle.
    fine = band(n, n / 3, n / 1.2, 201)
    mid = band(n, n / 30, n / 6, 202)
    swirl = band(n, 2, 8, 203)
    pores = smooth(-2.0, -2.8, band(n, n / 4, n / 1.5, 204))
    h = 0.30 * fine + 0.25 * mid + 0.08 * swirl - 1.2 * pores
    return norm01(h)


def stone(n):
    # 0,5 m je Karte, fuer Pflaster und Kopfstein: Bruch- und Spaltflaeche des Steins
    # (Kanten, kleine Ausbrueche); die Fugen kommen aus dem Belagsbild selbst (Relief).
    f1, f2, _ = voronoi(n, 14, 301, 0.95)
    facets = smooth(0.0, 0.25, f2 - f1)
    rough = band(n, n / 16, n / 3, 302)
    fine = band(n, n / 3, n / 1.2, 303)
    chips = smooth(-1.6, -2.4, band(n, n / 12, n / 4, 304))
    h = 0.10 * facets + 0.35 * rough + 0.25 * fine - 0.6 * chips
    return norm01(h)


def gravel(n):
    # 1,2 m je Karte → 2,3 mm je Texel: Steine 1–4 cm, gerundet, Sand und Luecken dazwischen.
    rng = np.random.RandomState(401)
    h = 0.25 * band(n, n / 3, n / 1.2, 402)  # Sand
    for cells, amp, seed in ((34, 1.0, 403), (60, 0.75, 404), (110, 0.5, 405)):
        f1, f2, idx = voronoi(n, cells, seed, 0.95)
        hval = rng.rand(cells * cells)[idx]
        keep = (hval > 0.35).astype(np.float64)       # nicht jede Zelle ist ein Stein
        size = 0.42 + 0.12 * rng.rand(cells * cells)[idx]
        dome = np.sqrt(np.clip(1.0 - (f1 / size) ** 2, 0.0, 1.0))
        gap = smooth(0.0, 0.10, f2 - f1)
        h = np.maximum(h, amp * (0.4 + 0.6 * hval) * dome * gap * keep)
    h += 0.10 * band(n, n / 4, n / 1.5, 406)
    return norm01(h)


def dirt(n):
    # 1,5 m je Karte: Erde mit Kruemeln, kleinen Steinchen, Nadeln und Zweigstuecken.
    clods = band(n, n / 24, n / 6, 501)
    crumbs = band(n, n / 6, n / 2, 502)
    f1, f2, _ = voronoi(n, 70, 503)
    pebbles = np.sqrt(np.clip(1.0 - (f1 / 0.3) ** 2, 0.0, 1.0)) * (band(n, 2, 6, 504) > 0.6)
    twigs = strokes(n, 260, 505, (6, 22), 1, 0.9)
    needles = strokes(n, 900, 506, (3, 7), 0, 0.6)
    h = 0.45 * clods + 0.30 * crumbs + 1.3 * pebbles + 1.0 * twigs + 0.7 * needles
    return norm01(h)


def grass(n):
    # 1,0 m je Karte: Halme und Bueschel von oben, dunkle Luecken.
    tufts = band(n, n / 16, n / 5, 601)
    blades = strokes(n, 2600, 602, (5, 14), 0, 1.0)
    gaps = smooth(-0.8, -1.8, band(n, n / 10, n / 3, 603))
    h = 0.5 * tufts + 1.2 * blades - 0.9 * gaps
    return norm01(h)


def sand(n):
    # 1,0 m je Karte: feine Koernung, flache Riffel, Trittspuren angedeutet.
    fine = band(n, n / 3, n / 1.2, 701)
    ripples = band(n, n / 12, n / 6, 702, slope=0.5)
    soft = band(n, 2, 10, 703)
    h = 0.4 * fine + 0.30 * ripples + 0.08 * soft
    return norm01(h)


MAPS = {
    "asphalt": asphalt,
    "concrete": concrete,
    "stone": stone,
    "gravel": gravel,
    "dirt": dirt,
    "grass": grass,
    "sand": sand,
}

# Parameter je Belag (SurfaceKind.textureKey). map: Karte; repeatM: Meter je Karte; normal:
# Staerke der Detail-Normale; albedo: Helligkeit aus der Hoehe; relief: Relief aus dem
# Belagsbild (Fugen, Risse, Steine der Belagstextur als Rillen); rough: Rauheit des Belags
# (perzeptiv, Filament); roughVar: Spitzen glatter, Poren rauer; trackGloss: wie viel glatter die
# Fahrspuren sind (Zweispur-Wege); trackU: Abstand der Spurmitte von der Wegmitte in u
# (RouteTextures.TRACK_OFFSET_M / Breite der Textur), 0 = keine Spuren; reliefTexels: Abstand der
# zentralen Differenzen des Reliefs in Texeln der Belagstextur (breitere Fasen bei grossen Steinen);
# reliefMip: kleinste Mip-Stufe der Belagstextur fuers Relief (Sprenkel im Stein ausblenden).
KINDS = {
    "asphalt":   dict(map="asphalt",  repeatM=0.75, normal=0.75, albedo=0.20, relief=2.0, rough=0.90, roughVar=0.22, trackGloss=0.00, trackU=0.0, reliefTexels=1.0, reliefMip=0.0),
    "concrete":  dict(map="concrete", repeatM=1.00, normal=0.60, albedo=0.15, relief=2.0, rough=0.82, roughVar=0.12, trackGloss=0.00, trackU=0.0, reliefTexels=1.0, reliefMip=0.5),
    "paving":    dict(map="stone",    repeatM=0.50, normal=0.60, albedo=0.15, relief=2.0, rough=0.80, roughVar=0.18, trackGloss=0.00, trackU=0.0, reliefTexels=2.0, reliefMip=1.0),
    "cobble":    dict(map="stone",    repeatM=0.60, normal=0.60, albedo=0.15, relief=2.4, rough=0.74, roughVar=0.22, trackGloss=0.00, trackU=0.0, reliefTexels=3.0, reliefMip=1.0),
    "wood":      dict(map="concrete", repeatM=0.70, normal=0.50, albedo=0.15, relief=3.0, rough=0.84, roughVar=0.10, trackGloss=0.00, trackU=0.0, reliefTexels=1.5, reliefMip=1.0),
    "compacted": dict(map="gravel",   repeatM=0.70, normal=0.90, albedo=0.28, relief=2.0, rough=0.95, roughVar=0.10, trackGloss=0.10, trackU=0.242, reliefTexels=1.0, reliefMip=0.0),
    "gravel":    dict(map="gravel",   repeatM=1.20, normal=1.10, albedo=0.32, relief=1.5, rough=0.96, roughVar=0.08, trackGloss=0.08, trackU=0.242, reliefTexels=1.0, reliefMip=0.0),
    "dirt":      dict(map="dirt",     repeatM=1.50, normal=1.00, albedo=0.30, relief=1.8, rough=0.95, roughVar=0.08, trackGloss=0.10, trackU=0.242, reliefTexels=1.5, reliefMip=0.5),
    "grass":     dict(map="grass",    repeatM=1.00, normal=1.00, albedo=0.35, relief=2.0, rough=0.92, roughVar=0.06, trackGloss=0.05, trackU=0.242, reliefTexels=1.0, reliefMip=0.0),
    "sand":      dict(map="sand",     repeatM=1.00, normal=0.70, albedo=0.20, relief=1.5, rough=0.97, roughVar=0.04, trackGloss=0.04, trackU=0.242, reliefTexels=1.5, reliefMip=0.5),
}

# Reichweite in Metern: bis dahin Detail (weich aus ab 55 %), Relief aus dem Belagsbild etwas weiter.
RANGE_M = 32.0
RELIEF_RANGE_M = 40.0


def encode(h, slope_scale):
    """Hoehe → RGB (Gefaelle in R/G, Hoehe in B). Zentrale Differenzen, periodisch."""
    gx = (np.roll(h, -1, axis=1) - np.roll(h, 1, axis=1)) * 0.5
    gy = (np.roll(h, -1, axis=0) - np.roll(h, 1, axis=0)) * 0.5
    r = np.clip(0.5 + gx * slope_scale, 0, 1)
    g = np.clip(0.5 + gy * slope_scale, 0, 1)
    rgb = np.stack([r, g, h], axis=-1)
    return (rgb * 255 + 0.5).astype(np.uint8)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=512)
    ap.add_argument("--quality", type=int, default=90)
    ap.add_argument("--preview", default=None)
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    n = a.size
    sizes = {}
    slopes = {}
    for name, fn in MAPS.items():
        h = fn(n)
        # Gefaelle so skaliert, dass das 99. Perzentil den Wertebereich fuellt; der Faktor
        # steht in detail.json (slope), damit das Material die echte Neigung kennt.
        gx = (np.roll(h, -1, axis=1) - np.roll(h, 1, axis=1)) * 0.5
        gy = (np.roll(h, -1, axis=0) - np.roll(h, 1, axis=0)) * 0.5
        p = np.percentile(np.abs(np.concatenate([gx.ravel(), gy.ravel()])), 99)
        scale = 0.45 / max(p, 1e-6)
        img = Image.fromarray(encode(h, scale), "RGB")
        path = os.path.join(OUT, name + ".jpg")
        # 4:4:4 (keine Farbunterabtastung: R/G sind Richtungen, keine Farbe), ohne EXIF.
        img.save(path, quality=a.quality, subsampling=0)
        sizes[name] = os.path.getsize(path)
        slopes[name] = round(float(scale), 3)
        if a.preview:
            os.makedirs(a.preview, exist_ok=True)
            Image.fromarray((h * 255).astype(np.uint8), "L").save(os.path.join(a.preview, name + "_h.png"))
    meta = {
        "comment": "Erzeugt von tool/indoor-dev/filament/nahdetail.py — nicht von Hand aendern.",
        "version": 1,
        "size": n,
        "rangeM": RANGE_M,
        "reliefRangeM": RELIEF_RANGE_M,
        "maps": {k: {"file": k + ".jpg", "slopeScale": slopes[k]} for k in MAPS},
        "kinds": KINDS,
    }
    with open(os.path.join(OUT, "detail.json"), "w") as f:
        json.dump(meta, f, indent=1, sort_keys=True)
        f.write("\n")
    tot = sum(sizes.values())
    for k, v in sizes.items():
        print(f"{k}.jpg {v} Bytes")
    print(f"zusammen {tot} Bytes; Grafikspeicher je geladene Karte {n * n * 4 * 4 // 3} Bytes (RGBA8 mit Mip-Stufen)")


if __name__ == "__main__":
    main()
