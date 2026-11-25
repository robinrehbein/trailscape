#!/usr/bin/env python3
"""Bilder des Baum-Prototyps: alt gegen neu aus derselben Kamera.

Liest `cameras.json` aus dem Verzeichnis von `indoor-dev tree-proto --out <dir>`
und rendert jede Kamera fuer die Welten `alt/` und `neu/` (bzw. `schau-alt/`,
`schau-neu/`) mit

  * dem three.js-Betrachter (`viewer/shots.mjs --cameras free`) und/oder
  * dem Filament-Referenzrenderer (`filament/build/filarender`, unter Xvfb),

legt je Kamera ein Vergleichsbild (links alt, rechts neu) an und schreibt
JPEG-Kopien fuer die Doku.

  python3 tool/indoor-dev/baeume/render.py <dir> --sky <skydir> [--renderer three|filament|both]
         [--only einzel,allee] [--size 960x600] [--jpeg-out docs/indoor/bilder/baeume]

Die Sonne je Kamera steht in `cameras.json` (Gegenlicht/Auflicht); fuer
filarender wird dazu eine Kopie des Himmels mit verschobener Sonne angelegt
(Himmelsbild und Wolken bleiben, nur Richtung des Sonnenlichts aendert sich).
Nur Werkzeug — aendert weder Betrachter noch filarender.
"""
import argparse
import json
import math
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
VIEWER = os.path.join(REPO, 'tool', 'indoor-dev', 'viewer')
FILAREN = os.path.join(REPO, 'tool', 'indoor-dev', 'filament', 'build', 'filarender')
MATERIALS = os.path.join(REPO, 'app', 'src', 'main', 'assets', 'indoor', 'materials')


def sun_enu(az, el):
    a, e = math.radians(az), math.radians(el)
    return [math.sin(a) * math.cos(e), math.cos(a) * math.cos(e), math.sin(e)]


def sky_for(base, out_dir, az, el):
    """Kopie des Himmels mit Sonne bei az/el (nur sky.json geaendert, Binaerdateien verlinkt)."""
    d = os.path.join(out_dir, f'sky_{int(round(az))}_{int(round(el))}')
    if not os.path.isdir(d):
        os.makedirs(d)
        for f in os.listdir(base):
            if f != 'sky.json':
                os.symlink(os.path.join(base, f), os.path.join(d, f))
        k = json.load(open(os.path.join(base, 'sky.json')))
        k['sunDirEnu'] = sun_enu(az, el)
        k['sunAzimuthDeg'] = az
        k['sunElevationDeg'] = el
        json.dump(k, open(os.path.join(d, 'sky.json'), 'w'))
    return d


def three(world, cam, sky, out_png, size):
    out_dir = out_png + '.d'
    os.makedirs(out_dir, exist_ok=True)
    e, a = cam['eye'], cam['at']
    cmd = ['node', 'shots.mjs', world, '--dists', '0', '--cameras', 'free',
           '--param', f'eye={e[0]},{e[1]},{e[2]}', '--param', f'at={a[0]},{a[1]},{a[2]}',
           '--param', 'fov=55', '--no-rider', '--no-attribution', '--param', 'ui=0', '--out', out_dir, '--size', size,
           '--sun-az', str(cam['sunAz']), '--sun-el', str(cam['sunEl'])]
    if sky:
        cmd += ['--sky', sky]
    r = subprocess.run(cmd, cwd=VIEWER, capture_output=True, text=True, timeout=600)
    src = os.path.join(out_dir, '00000_free.png')
    if r.returncode != 0 or not os.path.exists(src):
        print(r.stdout[-2000:], r.stderr[-2000:])
        raise SystemExit(f'three.js: {world} {cam["name"]} fehlgeschlagen')
    shutil.move(src, out_png)
    shutil.rmtree(out_dir, ignore_errors=True)
    stat = [l for l in r.stdout.splitlines() if 'Dreiecke' in l]
    return stat[-1] if stat else ''


def filament(world, cam, sky, out_png, size, display):
    w, h = map(int, size.split('x'))
    man = json.load(open(os.path.join(world, 'manifest.json')))
    e0, n0 = cam['eye'][0], cam['eye'][1]

    def rel(e, n, u):
        return [e - round(e0), u, -(n - round(n0))]

    tiles = [{'file': t['file'], 'pos': rel(*t['anchor']), 'hidden': []} for t in man['tiles']]
    scene = {
        'world': world, 'sky': sky, 'materials': MATERIALS, 'width': w, 'height': h, 'timeS': 0,
        'settings': {'shadows': True, 'fog': True, 'sky': True, 'postProcessing': True, 'ambientOcclusion': False,
                     'bloom': False, 'trees': True, 'props': True},
        'camera': {'eye': rel(*cam['eye']), 'target': rel(*cam['at']), 'up': [0, 1, 0], 'fovV': 55.0,
                   'near': 0.2, 'far': 25000, 'shiftX': 0, 'shiftY': 0},
        'fogHeight': -40, 'tiles': tiles, 'rider': None, 'cockpit': None, 'models': [],
    }
    sf = out_png + '.scene.json'
    json.dump(scene, open(sf, 'w'))
    t0 = time.time()
    r = subprocess.run([FILAREN, sf, out_png], env={**os.environ, 'DISPLAY': display}, capture_output=True, text=True, timeout=900)
    if r.returncode != 0:
        print(r.stdout[-2000:], r.stderr[-2000:])
        raise SystemExit(f'filarender: {world} {cam["name"]} fehlgeschlagen ({r.returncode})')
    os.remove(sf)
    return f'{time.time() - t0:.1f} s ' + (r.stdout.strip().splitlines() or [''])[-1]


def side_by_side(a, b, out, label_a='alt (heute)', label_b='neu (Prototyp)'):
    from PIL import Image, ImageDraw
    ia, ib = Image.open(a).convert('RGB'), Image.open(b).convert('RGB')
    w, h = ia.size
    im = Image.new('RGB', (w * 2 + 6, h), (255, 255, 255))
    im.paste(ia, (0, 0))
    im.paste(ib, (w + 6, 0))
    d = ImageDraw.Draw(im)
    for x, t in ((10, label_a), (w + 16, label_b)):
        d.rectangle([x - 4, 6, x + 8 * len(t) + 4, 26], fill=(0, 0, 0))
        d.text((x, 10), t, fill=(255, 255, 255))
    im.save(out)
    return im


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dir')
    ap.add_argument('--sky', required=True)
    ap.add_argument('--renderer', default='three', choices=['three', 'filament', 'both'])
    ap.add_argument('--only', default='')
    ap.add_argument('--size', default='960x600')
    ap.add_argument('--jpeg-out', default='')
    ap.add_argument('--jpeg-width', type=int, default=1600)
    ap.add_argument('--jpeg-quality', type=int, default=80)
    args = ap.parse_args()
    base = os.path.abspath(args.dir)
    sky = os.path.abspath(args.sky)
    cams = json.load(open(os.path.join(base, 'cameras.json')))
    only = [s for s in args.only.split(',') if s]
    if only:
        cams = [c for c in cams if any(c['name'].startswith(o) for o in only)]
    out = os.path.join(base, 'bilder')
    os.makedirs(out, exist_ok=True)
    renderers = ['three', 'filament'] if args.renderer == 'both' else [args.renderer]
    xvfb = None
    display = None
    if 'filament' in renderers:
        dn = 100 + os.getpid() % 400
        while os.path.exists(f'/tmp/.X{dn}-lock'):
            dn += 1
        display = f':{dn}'
        xvfb = subprocess.Popen(['Xvfb', display, '-screen', '0', '1280x1024x24', '-nolisten', 'tcp'],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(0.8)
    try:
        for cam in cams:
            pre = 'schau-' if cam.get('world') == 'schau' else ''
            csky = sky_for(sky, out, cam['sunAz'], cam['sunEl'])
            for rd in renderers:
                pngs = []
                for variant in ('alt', 'neu'):
                    world = os.path.join(base, pre + variant)
                    png = os.path.join(out, f'{cam["name"]}-{rd}-{variant}.png')
                    if rd == 'three':
                        stat = three(world, cam, csky, png, args.size)
                    else:
                        stat = filament(world, cam, csky, png, args.size, display)
                    print(f'{os.path.basename(png)}  {stat}', flush=True)
                    pngs.append(png)
                cmp_png = os.path.join(out, f'{cam["name"]}-{rd}.png')
                im = side_by_side(pngs[0], pngs[1], cmp_png)
                if args.jpeg_out:
                    os.makedirs(args.jpeg_out, exist_ok=True)
                    if im.width > args.jpeg_width:
                        im = im.resize((args.jpeg_width, round(im.height * args.jpeg_width / im.width)))
                    im.save(os.path.join(args.jpeg_out, f'{cam["name"]}-{rd}.jpg'), quality=args.jpeg_quality, optimize=True)
    finally:
        if xvfb:
            xvfb.kill()


if __name__ == '__main__':
    sys.exit(main())
