#!/usr/bin/env python3
"""Build launcher and Play Store artwork from the Trailscape square master."""

from pathlib import Path
import sys

from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
ICON = ROOT / "assets/icon"
RES = ROOT / "app/src/main/res"
PLAY = ROOT / "assets/play-store"
SIZES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def main(source: Path) -> None:
    ICON.mkdir(parents=True, exist_ok=True)
    PLAY.mkdir(parents=True, exist_ok=True)
    original = Image.open(source).convert("RGB")
    full = original.resize((1024, 1024), Image.Resampling.LANCZOS)
    full.save(ICON / "icon.png")
    full.resize((512, 512), Image.Resampling.LANCZOS).save(PLAY / "icon-512.png")

    # This master uses a deep green backdrop. The ivory trail and lime ridge
    # are separated by their red channel, so the adaptive foreground has a
    # true transparent edge instead of a green rectangular patch.
    red = original.getchannel("R")
    mask = red.point(lambda value: 255 if value > 90 else 0)
    bbox = mask.getbbox()
    if bbox is None:
        raise ValueError("No foreground artwork found")
    artwork = original.crop(bbox).convert("RGBA")
    alpha = mask.crop(bbox).filter(ImageFilter.GaussianBlur(1))
    artwork.putalpha(alpha)
    ratio = min(670 / artwork.width, 670 / artwork.height)
    size = (round(artwork.width * ratio), round(artwork.height * ratio))
    artwork = artwork.resize(size, Image.Resampling.LANCZOS)
    foreground = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    foreground.paste(artwork, ((1024-size[0])//2, (1024-size[1])//2))
    foreground.save(ICON / "icon_foreground.png")

    mono = Image.new("RGBA", foreground.size, (255, 255, 255, 0))
    mono.putalpha(foreground.getchannel("A"))
    mono.save(ICON / "icon_monochrome.png")

    for density, scale in SIZES.items():
        mipmap = RES / f"mipmap-{density}"
        drawable = RES / f"drawable-{density}"
        full.resize((round(48*scale), round(48*scale)), Image.Resampling.LANCZOS).save(mipmap / "ic_launcher.png")
        for name, image in (("foreground", foreground), ("monochrome", mono)):
            image.resize((round(108*scale), round(108*scale)), Image.Resampling.LANCZOS).save(drawable / f"ic_launcher_{name}.png")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
