# PBR-Texturen der 3D-Ansicht (docs/indoor/pbr-texturen.md): laedt freie CC0-Saetze von
# ambientCG, verkleinert sie auf Handy-Groesse, normiert die Farbe zu einem Detail (Mittel 1,
# die Farbe selbst kommt weiter aus Belagstextur bzw. Luftbild), packt die Kanaele und schreibt
# Textur-Arrays mit Mip-Ketten als KTX1:
#
#   <gruppe>_c.ktx  ETC2_EAC_SRGB8_ALPHA8: RGB Farbdetail (sRGB, Mittel 0,5 = Faktor 1; AO
#                   eingerechnet), A Rauheit (linear)
#   <gruppe>_n.ktx  EAC_RG11: Normale X/Y (0..1 = -1..1; X nach +s, Y nach +t der Textur)
#   pbr.json        Gruppen, Schichten und Parameter je Belag/Randstreifen/Gelaende
#
# Aufruf (Downloads sind fremde Daten: eigenes, leeres Verzeichnis, nichts davon ins Repo):
#   python3 -I tool/indoor-dev/filament/pbr.py fetch <download-verzeichnis>
#   python3 -I tool/indoor-dev/filament/pbr.py build <download-verzeichnis> [<ausgabe>]
#   python3 -I tool/indoor-dev/filament/pbr.py decode <pbr-verzeichnis> <ausgabe>   (nur Referenzrenderer
#       auf lavapipe-Vulkan, das kein ETC2 kann: dieselben Bloecke entpackt; braucht texture2ddecoder)
# Braucht numpy, pillow, etcpak (pip install numpy pillow etcpak). Ausgabe ohne Angabe:
# app/src/main/assets/indoor/pbr. Reproduzierbar (feste Eingaben, keine Zufallszahlen).
import json, math, os, struct, sys, urllib.request, zipfile, io

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "..", "app", "src", "main", "assets", "indoor", "pbr")

# Saetze: ambientCG-Kennung → geschaetzte Kantenlaenge in Metern (ambientCG nennt sie nicht
# fuer alle Saetze; nach Korngroesse geschaetzt, wo „0“).
SETS = {
    "Asphalt031": 1.5,       # Asphalt, feiner Splitt (Fotogrammetrie)
    "Road013A": 3.0,         # alter Asphalt, Risse, Flicken (prozedural)
    "PavingStones070": 1.5,  # Kopfsteinpflaster, Granit, Moos in den Fugen (Fotogrammetrie, laut Quelle 115 cm;
                             # auf 1,5 m gestreckt = Steine ≈ 12–14 cm wie Grosspflaster der Pave-Strecken)
    "Concrete048": 2.0,      # Beton (Fotogrammetrie)
    "Gravel022": 1.5,        # Schotter (prozedural, 150 cm)
    "Ground091": 2.0,        # feiner Splitt, wassergebundene Decke (Fotogrammetrie)
    "Ground106": 2.5,        # Erde/Waldweg mit Steinchen und Streu (Fotogrammetrie, 250 cm)
    "Ground080": 2.0,        # Sand (Fotogrammetrie)
    "Grass004": 1.4,         # Wiese (prozedural, 140 cm)
    "Ground103": 2.0,        # Acker, trockene Erde mit Keimlingen (Fotogrammetrie)
    "Ground082S": 1.2,       # Waldboden, Nadeln, Zweige (Fotogrammetrie)
    "Rock030": 3.0,          # Fels (prozedural)
}

# Gruppen = ein Textur-Array je Gruppe (gleiche Groesse); geladen wird eine Gruppe erst, wenn
# ein Belag sie braucht.
GROUPS = {
    "asphalt": (1024, ["Asphalt031", "Road013A"]),  # (Kantenlaenge, Saetze[, Hochpass])
    "cobble": (1024, ["PavingStones070"]),
    "concrete": (512, ["Concrete048"]),
    "gravel": (1024, ["Gravel022", "Ground091"]),
    "earth": (512, ["Ground106", "Ground080"]),
    "terrain": (512, ["Grass004", "Ground103", "Ground082S", "Rock030"], 0.5),  # Gelaende: Bueschel/Schollen behalten
}


def layer(group, set_id):
    return GROUPS[group][1].index(set_id)


def kind(group, set_id, alt=None, alt_mix=0.0, coord="world", uv=(0.0, 0.0), normal=1.0, albedo=0.8,
         replace=0.0, keep_lod=3.0, rough=(1.0, 0.0), rough_mix=0.7):
    return {
        "group": group, "layer": layer(group, set_id), "meters": SETS[set_id],
        "alt": layer(group, alt) if alt else -1, "altMeters": SETS[alt] if alt else 1.0, "altMix": alt_mix,
        "coord": 1 if coord == "uv" else 0, "uvMetersU": uv[0], "uvMetersV": uv[1],
        "normal": normal, "albedo": albedo, "replace": replace, "keepLod": keep_lod,
        "roughScale": rough[0], "roughBias": rough[1], "roughMix": rough_mix,
    }


# Schluessel = Belag (`SurfaceKind.textureKey`) bzw. Randstreifen aus dem Materialnamen
# (PbrRules.keyFor in der App, pbrKey in filarender).
KINDS = {
    # Asphalt: Splitt und Glanz aus Asphalt031, in Flecken (Makrovariation, ~10 m) alter, rissiger
    # Belag; Feinstruktur des Belagsbilds zu 60 % ersetzt (Flicken/Risse des Belagsbilds bleiben).
    "asphalt": kind("asphalt", "Asphalt031", alt="Road013A", alt_mix=0.55, normal=1.3, albedo=0.9, replace=0.5,
                    keep_lod=2.0, rough=(0.55, 0.42), rough_mix=0.8),
    "concrete": kind("concrete", "Concrete048", normal=0.8, albedo=0.8, replace=0.5, rough=(0.6, 0.35)),
    # Verbundpflaster ist Beton: Betonkoernung auf den Steinen, Fugen bleiben aus dem Belagsbild.
    "paving": kind("concrete", "Concrete048", normal=0.6, albedo=0.6, replace=0.0, rough=(0.6, 0.35)),
    # Kopfstein: echte Steine statt des gezeichneten Rasters — in Fahrbahn-UV (Reihen folgen der
    # Strasse), Farbe aus dem Belagsbild (grobe Mip-Stufe), Form/Normale/Rauheit aus PavingStones070.
    "cobble": kind("cobble", "PavingStones070", coord="uv", uv=(2.0, 8.0), normal=1.6, albedo=1.0, replace=1.0,
                   keep_lod=5.0, rough=(0.7, 0.25), rough_mix=0.85),
    "wood": kind("concrete", "Concrete048", normal=0.4, albedo=0.4, replace=0.0, rough=(0.5, 0.45)),
    "gravel": kind("gravel", "Gravel022", alt="Ground091", alt_mix=0.35, normal=1.2, albedo=0.9, replace=0.65,
                   keep_lod=2.5, rough=(0.4, 0.6)),
    "compacted": kind("gravel", "Ground091", alt="Gravel022", alt_mix=0.3, normal=1.0, albedo=0.85, replace=0.6,
                      keep_lod=2.5, rough=(0.4, 0.6)),
    "dirt": kind("earth", "Ground106", normal=1.1, albedo=0.9, replace=0.55,
                 keep_lod=2.5, rough=(0.3, 0.68)),
    "sand": kind("earth", "Ground080", normal=0.9, albedo=0.7, replace=0.5, keep_lod=2.5, rough=(0.2, 0.78)),
    "grass": kind("terrain", "Grass004", alt="Ground103", alt_mix=0.25, normal=1.0, albedo=0.9, replace=0.6,
                  keep_lod=3.0, rough=(0.3, 0.68)),
    # Randstreifen und ihre Auslaeufe
    "verge_grass": kind("terrain", "Grass004", alt="Ground103", alt_mix=0.2, normal=1.0, albedo=0.9, replace=0.5,
                        keep_lod=3.0, rough=(0.3, 0.68)),
    "verge_forest": kind("terrain", "Ground082S", alt="Ground103", alt_mix=0.3, normal=1.1, albedo=0.9, replace=0.5,
                         keep_lod=3.0, rough=(0.3, 0.66)),
    "verge_shoulder": kind("terrain", "Ground103", alt="Rock030", alt_mix=0.5, normal=1.0, albedo=0.8, replace=0.4,
                           rough=(0.3, 0.66)),
    "shoulder": kind("terrain", "Ground103", alt="Rock030", alt_mix=0.5, normal=1.0, albedo=0.8, replace=0.4,
                     rough=(0.3, 0.66)),
    "verge_sidewalk": kind("concrete", "Concrete048", normal=0.6, albedo=0.6, rough=(0.6, 0.35)),
    "sidewalk": kind("concrete", "Concrete048", normal=0.6, albedo=0.6, rough=(0.6, 0.35)),
    "fringe": kind("terrain", "Grass004", normal=1.0, albedo=0.8, replace=0.3, rough=(0.3, 0.68)),
}

# Gelaende: Bodenarten von terrain.mat (Wiese, Acker/Erde, Waldboden, Fels) = Schichten der
# Gruppe „terrain“. Wiederholungen je 256-m-Kachel ganzzahlig (nahtlos ueber Kachelgrenzen).
TERRAIN = {
    "group": "terrain",
    "repeats": [round(256.0 / SETS[s]) for s in GROUPS["terrain"][1]],
    "normal": 1.0, "albedo": 0.7, "roughMix": 0.6, "roughScale": 0.3, "roughBias": 0.68,
}

RANGE_M = 30.0          # Fahrbahn und Randstreifen: weich aus zwischen 15 und 30 m
TERRAIN_RANGE_M = 55.0  # Gelaende: weich aus zwischen 30 und 55 m

GL_ETC2_SRGBA8 = 0x9279  # GL_COMPRESSED_SRGB8_ALPHA8_ETC2_EAC
GL_EAC_RG11 = 0x9272     # GL_COMPRESSED_RG11_EAC


def fetch(dst):
    os.makedirs(dst, exist_ok=True)
    for s in SETS:
        z = os.path.join(dst, s + ".zip")
        if not os.path.exists(z):
            url = "https://ambientcg.com/get?file=%s_1K-JPG.zip" % s
            print("lade", url)
            req = urllib.request.Request(url, headers={"User-Agent": "trailscape-indoor-dev"})
            with urllib.request.urlopen(req, timeout=300) as r, open(z, "wb") as f:
                f.write(r.read())
        d = os.path.join(dst, s)
        os.makedirs(d, exist_ok=True)
        with zipfile.ZipFile(z) as zf:
            for n in zf.namelist():
                b = os.path.basename(n)
                if b.endswith(".jpg") and "/" not in b and ".." not in b:
                    with open(os.path.join(d, b), "wb") as f:
                        f.write(zf.read(n))


def load(src, s, what, mode):
    p = os.path.join(src, s, "%s_1K-JPG_%s.jpg" % (s, what))
    if not os.path.exists(p):
        return None
    im = Image.open(p)
    im.load()
    return im.convert(mode)


def srgb_to_lin(x):
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def lin_to_srgb(x):
    x = np.clip(x, 0.0, 1.0)
    return np.where(x <= 0.0031308, x * 12.92, 1.055 * x ** (1 / 2.4) - 0.055)


def blur_wrap(a, sigma):
    """Gauss mit Wiederholung am Rand (die Saetze sind kachelbar), per FFT."""
    n = a.shape[0]
    f = np.fft.fftfreq(n)
    g = np.exp(-2 * (math.pi * sigma) ** 2 * (f[:, None] ** 2 + f[None, :] ** 2))
    if a.ndim == 2:
        return np.real(np.fft.ifft2(np.fft.fft2(a) * g))
    return np.stack([np.real(np.fft.ifft2(np.fft.fft2(a[..., c]) * g)) for c in range(a.shape[2])], -1)


def resize(a, n):
    """Float-Bild (H, W[, C]) auf n×n (Lanczos je Kanal)."""
    if a.shape[0] == n:
        return a
    if a.ndim == 2:
        return np.asarray(Image.fromarray(a.astype(np.float32), "F").resize((n, n), Image.LANCZOS))
    return np.stack([resize(a[..., c], n) for c in range(a.shape[2])], -1)


def down2(a):
    return 0.25 * (a[0::2, 0::2] + a[1::2, 0::2] + a[0::2, 1::2] + a[1::2, 1::2])


def prepare(src, s, n, highpass=0.8, chroma=0.45):
    col = srgb_to_lin(np.asarray(load(src, s, "Color", "RGB"), np.float32) / 255.0)
    nrm = np.asarray(load(src, s, "NormalGL", "RGB"), np.float32) / 255.0 * 2.0 - 1.0
    rough_im = load(src, s, "Roughness", "L")
    rough = np.asarray(rough_im, np.float32) / 255.0 if rough_im else np.full(col.shape[:2], 0.8, np.float32)
    ao_im = load(src, s, "AmbientOcclusion", "L")
    disp_im = load(src, s, "Displacement", "L")
    if ao_im is not None:
        col = col * ((np.asarray(ao_im, np.float32) / 255.0) ** 0.5)[..., None]
    # NormalGL: Y zeigt im Bild nach oben (kleinere Zeile). Unsere Konvention: Y nach +t (Zeile
    # waechst) → Y umdrehen. Gegenprobe mit dem Hoehenbild: die Normale zeigt bergab.
    nrm[..., 1] = -nrm[..., 1]
    if disp_im is not None:
        h = np.asarray(disp_im, np.float32) / 255.0
        gx = np.roll(h, -1, 1) - np.roll(h, 1, 1)
        gy = np.roll(h, -1, 0) - np.roll(h, 1, 0)
        cx = np.corrcoef(nrm[..., 0].ravel(), -gx.ravel())[0, 1]
        cy = np.corrcoef(nrm[..., 1].ravel(), -gy.ravel())[0, 1]
        print("  %s: Normale gegen Hoehe, Korrelation x %.2f, y %.2f" % (s, cx, cy))
        if cx < 0.1 or cy < 0.1:
            raise SystemExit("%s: Normale passt nicht zum Hoehenbild (Konvention?)" % s)
    # Farbe → Detail: grobe Helligkeitsverlaeufe der Kachel (sonst sieht man die Wiederholung)
    # weitgehend herausnehmen, dann je Kanal auf Mittel 0,5 (= Faktor 1) normieren.
    col = resize(col, n)
    low = blur_wrap(col, n / 8.0)
    col = col / np.maximum(low, 1e-4) ** highpass
    col = col / np.maximum(col.reshape(-1, 3).mean(0), 1e-4)
    # Farbschwankung nur gedaempft uebernehmen (sonst bunte Kiesel auf grauem Belag)
    lum = (col * np.array([0.2126, 0.7152, 0.0722], np.float32)).sum(-1, keepdims=True)
    col = (lum + chroma * (col - lum)) * 0.5
    nrm = resize(nrm, n)
    rough = resize(rough, n)
    return col, nrm, rough


def mips(col, nrm, rough):
    """Mip-Kette in linearer Farbe; Normalen gemittelt und normiert, Rauheit nach Toksvig erhoeht."""
    out = []
    c, nr, r = col, nrm, rough
    while True:
        l = np.linalg.norm(nr, axis=-1, keepdims=True)
        nn = nr / np.maximum(l, 1e-6)
        ft = np.clip(l[..., 0], 0.05, 1.0)
        rr = np.sqrt(np.clip(r * r + (1.0 - ft) / ft * 0.5, 0, 1))
        out.append((c, nn, rr))
        if c.shape[0] == 1:
            break
        c, nr, r = down2(c), down2(nn * np.minimum(l, 1.0)), down2(r)
    return out


def pad4(a):
    n = a.shape[0]
    if n >= 4:
        return a
    return np.tile(a, (4 // n, 4 // n, 1))


def encode(levels):
    import etcpak
    cl, nl = [], []
    for c, n, r in levels:
        rgba = np.concatenate([lin_to_srgb(c), r[..., None]], -1)
        rgba = pad4((np.clip(rgba, 0, 1) * 255 + 0.5).astype(np.uint8))
        w = rgba.shape[0]
        cl.append(etcpak.compress_etc2_rgba(np.ascontiguousarray(rgba).tobytes(), w, w))
        nn = np.zeros(n.shape[:2] + (4,), np.float32)
        nn[..., 0] = n[..., 0] * 0.5 + 0.5
        nn[..., 1] = n[..., 1] * 0.5 + 0.5
        nn[..., 3] = 1.0
        nn = pad4((np.clip(nn, 0, 1) * 255 + 0.5).astype(np.uint8))
        nl.append(etcpak.compress_eac_rg(np.ascontiguousarray(nn).tobytes(), w, w))
    return cl, nl


def write_ktx(path, internal, base, size, layers_levels):
    """KTX1, komprimiert, Array mit len(layers_levels) Schichten; je Mip-Stufe alle Schichten."""
    nl = len(layers_levels)
    nlev = len(layers_levels[0])
    hdr = b"\xabKTX 11\xbb\r\n\x1a\n" + struct.pack("<13I", 0x04030201, 0, 1, 0, internal, base, size, size, 0,
                                                     nl if nl > 1 else 0, 1, nlev, 0)
    with open(path, "wb") as f:
        f.write(hdr)
        for lv in range(nlev):
            data = b"".join(layers_levels[k][lv] for k in range(nl))
            f.write(struct.pack("<I", len(data)))
            f.write(data)


def build(src, out):
    os.makedirs(out, exist_ok=True)
    meta = {"comment": "Erzeugt von tool/indoor-dev/filament/pbr.py — nicht von Hand aendern.", "version": 1,
            "rangeM": RANGE_M, "terrainRangeM": TERRAIN_RANGE_M, "groups": {}, "kinds": KINDS, "terrain": TERRAIN,
            "sources": {s: {"url": "https://ambientcg.com/a/" + s, "license": "CC0-1.0", "meters": m}
                        for s, m in SETS.items()}}
    for g, spec in GROUPS.items():
        n, sets = spec[0], spec[1]
        hp = spec[2] if len(spec) > 2 else 0.8
        cls, nls = [], []
        for s in sets:
            col, nrm, rough = prepare(src, s, n, hp)
            cl, nl = encode(mips(col, nrm, rough))
            cls.append(cl)
            nls.append(nl)
        fc, fn = g + "_c.ktx", g + "_n.ktx"
        write_ktx(os.path.join(out, fc), GL_ETC2_SRGBA8, 0x1908, n, cls)   # GL_RGBA
        write_ktx(os.path.join(out, fn), GL_EAC_RG11, 0x8227, n, nls)      # GL_RG
        bytes_ = os.path.getsize(os.path.join(out, fc)) + os.path.getsize(os.path.join(out, fn))
        meta["groups"][g] = {"size": n, "layers": sets, "color": fc, "normal": fn, "levels": len(cls[0]),
                             "bytes": bytes_}
        print("%-9s %4d² × %d  %.2f MB" % (g, n, len(sets), bytes_ / 1e6))
    with open(os.path.join(out, "pbr.json"), "w") as f:
        json.dump(meta, f, indent=1, sort_keys=True, ensure_ascii=False)
    total = sum(v["bytes"] for v in meta["groups"].values())
    print("zusammen %.2f MB" % (total / 1e6))


def read_ktx(path):
    with open(path, "rb") as f:
        raw = f.read()
    h = struct.unpack("<13I", raw[12:64])
    ifmt, w, layers, levels = h[4], h[6], max(1, h[9]), h[11]
    off = 64 + h[12]
    out = []
    for lv in range(levels):
        n = struct.unpack("<I", raw[off:off + 4])[0]
        off += 4
        out.append(raw[off:off + n])
        off += (n + 3) & ~3
    return ifmt, w, layers, out


def decode(src, dst):
    """Entpackt die KTX-Arrays (gleiche Bloecke) zu RGBA8/RG8 — nur fuer den Referenzrenderer
    auf lavapipe, das kein ETC2 kann; nie ins Repo."""
    import texture2ddecoder as t2d
    os.makedirs(dst, exist_ok=True)
    for f in sorted(os.listdir(src)):
        if not f.endswith(".ktx"):
            continue
        ifmt, w, layers, levels = read_ktx(os.path.join(src, f))
        per = []
        for lv, data in enumerate(levels):
            d = max(1, w >> lv)
            bw = max(4, d)
            bs = len(data) // layers
            lay = []
            for k in range(layers):
                blk = data[k * bs:(k + 1) * bs]
                if ifmt == GL_ETC2_SRGBA8:
                    px = np.frombuffer(t2d.decode_etc2a8(blk, bw, bw), np.uint8).reshape(bw, bw, 4)[:d, :d]
                    px = px[..., [2, 1, 0, 3]]
                else:
                    px = np.frombuffer(t2d.decode_eacrg(blk, bw, bw), np.uint8).reshape(bw, bw, 4)[:d, :d]
                    px = px[..., [2, 1]]
                lay.append(np.ascontiguousarray(px).tobytes())
            per.append(b"".join(lay))
        internal, base, tsize = (0x8C43, 0x1908, 4) if ifmt == GL_ETC2_SRGBA8 else (0x822B, 0x8227, 2)
        hdr = b"\xabKTX 11\xbb\r\n\x1a\n" + struct.pack("<13I", 0x04030201, 0x1401, 1, base, internal, base, w, w, 0,
                                                         layers if layers > 1 else 0, 1, len(per), 0)
        with open(os.path.join(dst, f), "wb") as o:
            o.write(hdr)
            for data in per:
                o.write(struct.pack("<I", len(data)))
                o.write(data)
                o.write(b"\0" * ((-len(data)) % 4))
    with open(os.path.join(src, "pbr.json"), "rb") as i, open(os.path.join(dst, "pbr.json"), "wb") as o:
        o.write(i.read())


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "fetch":
        fetch(sys.argv[2])
    elif len(sys.argv) >= 4 and sys.argv[1] == "decode":
        decode(sys.argv[2], sys.argv[3])
    elif len(sys.argv) >= 3 and sys.argv[1] == "build":
        build(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else OUT)
    else:
        print(__doc__ or "pbr.py fetch|build <download-verzeichnis> [<ausgabe>]")
        sys.exit(2)
