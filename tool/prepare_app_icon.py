#!/usr/bin/env python3
"""Prepare Trailscape's app icon assets from a single supplied source image.

Given a square source artwork consisting of a circular motif centered on a
flat background color, this script produces the three PNGs consumed by
flutter_launcher_icons:

  * assets/icon/icon.png             - 1024x1024 RGB, full source image
                                        (used for the legacy/round launcher
                                        icon and as the mipmap fallback).
  * assets/icon/icon_foreground.png  - 1024x1024 RGBA, transparent canvas
                                        with just the circular motif,
                                        scaled so it fits inside the
                                        adaptive-icon safe zone (66.7%
                                        visible-circle diameter).
  * assets/icon/icon_monochrome.png  - 1024x1024 RGBA, white-on-transparent
                                        silhouette of the motif's dark
                                        linework, for Android 13+ themed
                                        (monochrome) icons.

It also prints the measured background color so it can be copied into
pubspec.yaml's `adaptive_icon_background`.

Usage:
    python3 tool/prepare_app_icon.py <source.png>
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageFilter

CANVAS_SIZE = 1024
FOREGROUND_CIRCLE_DIAMETER = 672  # ~66.7% safe zone of a 1024 adaptive icon
BG_SAMPLE_BOX = 20  # corner patch size used to sample the background color
BG_TOLERANCE = 30  # per-channel tolerance for background/foreground split
FEATHER_PX = 2  # soft antialiasing on the extracted circle edge

REPO_ROOT = Path(__file__).resolve().parent.parent
ICON_DIR = REPO_ROOT / "assets" / "icon"


def sample_background_color(im: Image.Image) -> tuple[int, int, int]:
    """Average a small corner patch to get the flat background color."""
    patch = im.crop((0, 0, BG_SAMPLE_BOX, BG_SAMPLE_BOX))
    pixels = list(patch.getdata())
    n = len(pixels)
    r = sum(p[0] for p in pixels) / n
    g = sum(p[1] for p in pixels) / n
    b = sum(p[2] for p in pixels) / n
    return (round(r), round(g), round(b))


def find_motif_bbox(
    im: Image.Image, bg: tuple[int, int, int], tolerance: int
) -> tuple[int, int, int, int]:
    """Find the bounding box of pixels that differ from the background."""
    w, h = im.size
    px = im.load()
    min_x, min_y, max_x, max_y = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if (
                abs(r - bg[0]) > tolerance
                or abs(g - bg[1]) > tolerance
                or abs(b - bg[2]) > tolerance
            ):
                if x < min_x:
                    min_x = x
                if x > max_x:
                    max_x = x
                if y < min_y:
                    min_y = y
                if y > max_y:
                    max_y = y
    return min_x, min_y, max_x, max_y


def build_icon_full(src: Image.Image) -> Image.Image:
    return src.convert("RGB").resize(
        (CANVAS_SIZE, CANVAS_SIZE), Image.LANCZOS
    )


def build_icon_foreground(
    src: Image.Image, cx: float, cy: float, radius: float
) -> Image.Image:
    """Extract the circular motif and place it on a transparent canvas."""
    src_rgb = src.convert("RGB")

    # Crop a square exactly bounding the circle, using the measured center
    # and radius so the crop is symmetric even if the motif's bbox wasn't
    # perfectly centered in the source.
    crop_box = (
        int(round(cx - radius)),
        int(round(cy - radius)),
        int(round(cx + radius)),
        int(round(cy + radius)),
    )
    crop = src_rgb.crop(crop_box)
    crop_size = crop.size[0]

    # Build a circular alpha mask with a soft feathered edge.
    mask = Image.new("L", crop.size, 0)
    from PIL import ImageDraw

    draw = ImageDraw.Draw(mask)
    inset = FEATHER_PX
    draw.ellipse(
        (inset, inset, crop_size - inset, crop_size - inset), fill=255
    )
    if FEATHER_PX > 0:
        mask = mask.filter(ImageFilter.GaussianBlur(FEATHER_PX))

    circle_rgba = crop.convert("RGBA")
    circle_rgba.putalpha(mask)

    # Scale the extracted circle down to the target foreground diameter.
    circle_rgba = circle_rgba.resize(
        (FOREGROUND_CIRCLE_DIAMETER, FOREGROUND_CIRCLE_DIAMETER),
        Image.LANCZOS,
    )

    canvas = Image.new("RGBA", (CANVAS_SIZE, CANVAS_SIZE), (0, 0, 0, 0))
    offset = (CANVAS_SIZE - FOREGROUND_CIRCLE_DIAMETER) // 2
    canvas.paste(circle_rgba, (offset, offset), circle_rgba)
    return canvas


def build_icon_monochrome(
    foreground: Image.Image, luminance_threshold: float
) -> Image.Image:
    """Turn the dark linework of the foreground into an opaque white
    silhouette on a transparent canvas, for Android themed icons."""
    fg = foreground.convert("RGBA")
    px = fg.load()
    w, h = fg.size
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out_px = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            luminance = 0.299 * r + 0.587 * g + 0.114 * b
            if luminance < luminance_threshold:
                out_px[x, y] = (255, 255, 255, a)
    return out


def build_icon_monochrome_fallback(foreground: Image.Image) -> Image.Image:
    """Fallback: opaque white disc matching the foreground's alpha shape."""
    fg = foreground.convert("RGBA")
    alpha = fg.split()[3]
    out = Image.new("RGBA", fg.size, (0, 0, 0, 0))
    white = Image.new("RGBA", fg.size, (255, 255, 255, 255))
    out.paste(white, (0, 0), alpha)
    return out


def main() -> None:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <source.png>", file=sys.stderr)
        sys.exit(1)

    src_path = Path(sys.argv[1])
    src = Image.open(src_path).convert("RGB")

    bg = sample_background_color(src)
    print(f"Measured background color: rgb{bg} #{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}")

    min_x, min_y, max_x, max_y = find_motif_bbox(src, bg, BG_TOLERANCE)
    diam_x = max_x - min_x
    diam_y = max_y - min_y
    cx = (min_x + max_x) / 2
    cy = (min_y + max_y) / 2
    radius = max(diam_x, diam_y) / 2
    print(
        f"Motif bbox: x[{min_x},{max_x}] y[{min_y},{max_y}] "
        f"center=({cx:.1f},{cy:.1f}) diam=({diam_x},{diam_y}) "
        f"radius={radius:.1f} (diam/w={diam_x / src.size[0]:.3f})"
    )

    ICON_DIR.mkdir(parents=True, exist_ok=True)

    icon_full = build_icon_full(src)
    icon_full.save(ICON_DIR / "icon.png")
    print(f"Wrote {ICON_DIR / 'icon.png'} ({icon_full.size[0]}x{icon_full.size[1]})")

    icon_fg = build_icon_foreground(src, cx, cy, radius)
    icon_fg.save(ICON_DIR / "icon_foreground.png")
    print(
        f"Wrote {ICON_DIR / 'icon_foreground.png'} "
        f"({icon_fg.size[0]}x{icon_fg.size[1]})"
    )

    # Threshold at 60% between the dark motif linework (~luminance 64) and
    # the cream circle fill (~luminance 230): 64 + 0.6 * (230 - 64) ~= 164.
    icon_mono = build_icon_monochrome(icon_fg, luminance_threshold=164)
    opaque_ratio = sum(
        1 for p in icon_mono.getdata() if p[3] > 0
    ) / (CANVAS_SIZE * CANVAS_SIZE)
    print(f"Monochrome opaque coverage: {opaque_ratio:.1%}")
    icon_mono.save(ICON_DIR / "icon_monochrome.png")
    print(
        f"Wrote {ICON_DIR / 'icon_monochrome.png'} "
        f"({icon_mono.size[0]}x{icon_mono.size[1]})"
    )


if __name__ == "__main__":
    main()
