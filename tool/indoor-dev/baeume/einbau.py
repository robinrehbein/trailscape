#!/usr/bin/env python3
"""Belegbilder des Baum-Einbaus: dieselbe gebaute Welt, links die gebackenen
Baeume (Grafikstufe Standard), rechts die Instanzen der Bibliothek (Hoch/Ultra).

Nimmt eine mit `indoor-dev` gebaute Welt (ab `indoor-world/15`, z. B.
`indoor-dev testworld`), setzt die Kamera an Laufmetern der Strecke in
Fahrtrichtung und rendert jede Stelle zweimal mit dem Filament-Referenzrenderer
(`filament/build/filarender`, unter Xvfb): ohne und mit `"trees"` in der Szene
(filarender spiegelt dort `TreeInstancer` der App). Schreibt je Stelle ein
Vergleichsbild und `einbau.json` mit den Zaehlern (Instanzen nah/mittel,
Dreiecke, ausgeblendete Abschnitte, Zeichenaufrufe).

  python3 tool/indoor-dev/baeume/einbau.py <welt> --sky <skydir> --out <dir>
         [--at 120,860] [--auto 4] [--detail 2] [--size 960x600]
         [--library app/src/main/assets/indoor/models/trees.glb]
         [--jpeg-out docs/indoor/bilder/baeume] [--prefix einbau]

`--auto N` waehlt die N Stellen mit den meisten Instanzknoten voraus.
"""
import argparse
import json
import math
import os
import struct
import subprocess
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
FILAREN = os.path.join(REPO, 'tool', 'indoor-dev', 'filament', 'build', 'filarender')
MATERIALS = os.path.join(REPO, 'app', 'src', 'main', 'assets', 'indoor', 'materials')
LIBRARY = os.path.join(REPO, 'app', 'src', 'main', 'assets', 'indoor', 'models', 'trees.glb')
FACTOR = 4  # WORLD_LEVEL_FACTOR


def glb_json(path):
    with open(path, 'rb') as f:
        head = f.read(20)
        return json.loads(f.read(struct.unpack('<I', head[12:16])[0]))


def instances(world, man):
    """Alle Instanzknoten der Welt als (e, n) in Weltkoordinaten."""
    out = []
    for t in man['tiles']:
        if t['level'] != 0:
            continue
        ae, an, _ = t['anchor']
        for n in glb_json(os.path.join(world, t['file']))['nodes']:
            if n.get('name', '').startswith('x_treei_') and 'translation' in n:
                x, _, z = n['translation']
                out.append((ae + x, an - z))
    return out


def pose(track, d):
    """Lage (e, n, u) und Fahrtrichtung der Strecke am Laufmeter d."""
    step, cnt = track['stepM'], len(track['e'])
    i = int(d / step) % (cnt - 1)
    f = d / step - int(d / step)
    j = i + 1
    e = track['e'][i] + (track['e'][j] - track['e'][i]) * f
    n = track['n'][i] + (track['n'][j] - track['n'][i]) * f
    u = track['u'][i] + (track['u'][j] - track['u'][i]) * f
    de, dn = track['e'][j] - track['e'][i], track['n'][j] - track['n'][i]
    l = math.hypot(de, dn) or 1.0
    return e, n, u, de / l, dn / l


def auto_spots(track, inst, count):
    """Laufmeter mit den meisten Instanzen im Blick (5–45 m voraus), mindestens 150 m auseinander."""
    scored = []
    for k in range(0, int(track['lengthM']) - 60, 10):
        e, n, _, he, hn = pose(track, k)
        s = 0
        for (te, tn) in inst:
            fe, fn = te - e, tn - n
            ahead = fe * he + fn * hn
            if 5 < ahead < 45 and abs(-fe * hn + fn * he) < 22:
                s += 1
        scored.append((s, k))
    scored.sort(reverse=True)
    picked = []
    for s, k in scored:
        if all(abs(k - p) >= 150 for p in picked):
            picked.append(k)
        if len(picked) == count:
            break
    return sorted(picked)


def scene_for(world, man, d, sky, size, trees):
    track = man['track']
    e, n, u, he, hn = pose(track, d)
    # Augenhoehe eines Fahrers, etwas rechts der Mitte; Blick 25 m voraus leicht nach oben (Kronen im Bild)
    eye = (e + hn * 0.8, n - he * 0.8, u + 1.6)
    te, tn, tu, _, _ = pose(track, d + 25)
    at = (te, tn, tu + 3.2)
    e0, n0 = round(eye[0]), round(eye[1])

    def rel(pe, pn, pu):
        return [pe - e0, pu, -(pn - n0)]

    shown = set()
    tiles = []
    for level in (0, 1, 2):
        for t in man['tiles']:
            if t['level'] != level:
                continue
            if level == 0 and math.hypot(t['anchor'][0] - eye[0], t['anchor'][1] - eye[1]) > 420:
                continue
            hidden = []
            if level > 0:
                for i in range(FACTOR):
                    for j in range(FACTOR):
                        if (level - 1, t['ix'] * FACTOR + i, t['iy'] * FACTOR + j) in shown:
                            hidden.append(f'terrain_sub_{i}_{j}')
            tiles.append({'file': t['file'], 'pos': rel(*t['anchor']), 'hidden': hidden})
        shown |= {(t['level'], t['ix'], t['iy']) for t in man['tiles'] if t['level'] == level and any(x['file'] == t['file'] for x in tiles)}
    w, h = map(int, size.split('x'))
    scene = {
        'world': world, 'sky': sky, 'materials': MATERIALS, 'width': w, 'height': h, 'timeS': 0,
        'settings': {'shadows': True, 'fog': True, 'sky': True, 'postProcessing': True, 'ambientOcclusion': False,
                     'bloom': False, 'trees': True, 'props': True},
        'camera': {'eye': rel(*eye), 'target': rel(*at), 'up': [0, 1, 0], 'fovV': 55.0, 'near': 0.2, 'far': 25000,
                   'shiftX': 0, 'shiftY': 0},
        'fogHeight': -40, 'tiles': tiles, 'rider': None, 'cockpit': None, 'models': [],
    }
    if trees:
        scene['trees'] = trees
    return scene


def render(scene, out_png, display):
    sf = out_png + '.scene.json'
    json.dump(scene, open(sf, 'w'))
    t0 = time.time()
    r = subprocess.run([FILAREN, sf, out_png], env={**os.environ, 'DISPLAY': display}, capture_output=True, text=True, timeout=1800)
    if r.returncode != 0:
        print(r.stdout[-2000:], r.stderr[-2000:])
        raise SystemExit(f'filarender fehlgeschlagen ({r.returncode}): {out_png}')
    os.remove(sf)
    stats = json.loads((r.stdout.strip().splitlines() or ['{}'])[-1])
    stats['seconds'] = round(time.time() - t0, 1)
    return stats


def side_by_side(a, b, out, label_a, label_b):
    from PIL import Image, ImageDraw
    ia, ib = Image.open(a).convert('RGB'), Image.open(b).convert('RGB')
    w, h = ia.size
    im = Image.new('RGB', (w * 2 + 6, h), (255, 255, 255))
    im.paste(ia, (0, 0))
    im.paste(ib, (w + 6, 0))
    d = ImageDraw.Draw(im)
    for x, t in ((10, label_a), (w + 16, label_b)):
        d.rectangle([x - 4, 6, x + 7 * len(t) + 6, 26], fill=(0, 0, 0))
        d.text((x, 10), t, fill=(255, 255, 255))
    im.save(out)
    return im


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('world')
    ap.add_argument('--sky', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--at', default='')
    ap.add_argument('--auto', type=int, default=0)
    ap.add_argument('--detail', type=int, default=2)
    ap.add_argument('--size', default='960x600')
    ap.add_argument('--library', default=LIBRARY)
    ap.add_argument('--jpeg-out', default='')
    ap.add_argument('--prefix', default='einbau')
    args = ap.parse_args()
    world, sky, out = os.path.abspath(args.world), os.path.abspath(args.sky), os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    man = json.load(open(os.path.join(world, 'manifest.json')))
    spots = [float(x) for x in args.at.split(',') if x]
    if args.auto:
        spots += auto_spots(man['track'], instances(world, man), args.auto)
    if not spots:
        raise SystemExit('keine Stelle: --at oder --auto')
    display = os.environ.get('DISPLAY')
    xvfb = None
    if not display:
        display = ':97'
        xvfb = subprocess.Popen(['Xvfb', display, '-screen', '0', '1280x1024x24', '-nolisten', 'tcp'],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(3.0)
    report = {'builder': man.get('builder'), 'detail': args.detail, 'spots': []}
    try:
        for d in spots:
            name = f'{args.prefix}-{int(d):04d}m'
            a, b = os.path.join(out, name + '-gebacken.png'), os.path.join(out, name + '-instanzen.png')
            sa = render(scene_for(world, man, d, sky, args.size, None), a, display)
            sb = render(scene_for(world, man, d, sky, args.size, {'library': os.path.abspath(args.library), 'detail': args.detail}), b, display)
            im = side_by_side(a, b, os.path.join(out, name + '.png'), 'gebacken (Standard)',
                              f'Bibliothek, instanziert (treeDetail {args.detail})')
            if args.jpeg_out:
                os.makedirs(args.jpeg_out, exist_ok=True)
                wj = 1280
                im.resize((wj, round(im.size[1] * wj / im.size[0]))).save(os.path.join(args.jpeg_out, name + '.jpg'), quality=86)
            report['spots'].append({'distM': d, 'name': name, 'gebacken': sa, 'instanzen': sb})
            print(name, 'gebacken', sa.get('primitives'), 'Primitive |', 'Instanzen', sb.get('treeNear'), 'nah', sb.get('treeMid'), 'mittel',
                  sb.get('treeTriangles'), 'Dreiecke', sb.get('treeTemplates'), 'Vorlagen', sb.get('treeBinsHidden'), 'Abschnitte aus')
    finally:
        if xvfb:
            xvfb.kill()
    json.dump(report, open(os.path.join(out, 'einbau.json'), 'w'), indent=1)


if __name__ == '__main__':
    main()
