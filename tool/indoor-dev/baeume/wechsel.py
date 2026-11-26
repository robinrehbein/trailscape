#!/usr/bin/env python3
"""Belegbilder zum Wechsel gebackener Baum <-> Bibliotheksbaum (docs/indoor/baeume.md,
„Wechselring"): dieselbe gebaute Welt, dieselbe Kamera, einmal mit dem Stand **vorher**
(Abschnitt schaltet als Ganzes um) und einmal mit dem Stand **nachher** (Ring um die
Kamera, in dem das Material beide Darstellungen ineinander rastert).

Der Stand vorher ist ein zweiter Bau des Referenzrenderers aus dem Commit davor samt
dessen Materialien und Bibliothek (z. B. `git worktree add ../alt <commit>`, dort
`tool/indoor-dev/filament/build.sh`):

  python3 tool/indoor-dev/baeume/wechsel.py <welt> --sky <skydir> --out <dir>
         --alt-filarender <alt>/tool/indoor-dev/filament/build/filarender
         --alt-materials <alt>/app/src/main/assets/indoor/materials
         --alt-library <alt>/app/src/main/assets/indoor/models/trees.glb
         [--at 780,1740,2080,2440] [--folge 2030:2080:8] [--fein 736:746:0.5] [--tele 30]
         [--detail 2] [--jpeg-out docs/indoor/bilder/baeume]

 * `--at`: je Stelle ein Bild links vorher / rechts nachher (beide mit Instanzen).
 * `--folge a:b:schritt`: Bildfolge einer Anfahrt (Kamera an den Laufmetern a…b),
   oben vorher, unten nachher; `--tele` = Bildwinkel der Folge in Grad (enger als
   die Fahrerkamera, damit der Wechsel in 30–60 m gross im Bild ist).
 * `--fein a:b:schritt`: dieselbe Anfahrt in kleinen Schritten (0,5 m = ein Schritt des
   Planers), ohne Bilder zu behalten: der mittlere Unterschied aufeinanderfolgender Bilder.
   Ein Sprung zeigt sich als Ausreisser gegenueber dem Median (gleichmaessige Fahrt).
 * `wechsel.json`: Zaehler je Bild, die Schritte der Feinfolge und ihr Groesstwert/Median.
"""
import argparse
import json
import os
import subprocess
import time

import einbau


def render(args, world, man, d, sky, size, out_png, display, alt, fov=None):
    trees = {'library': os.path.abspath(args.alt_library if alt else args.library), 'detail': args.detail}
    scene = einbau.scene_for(world, man, d, sky, size, trees)
    if alt:
        scene['materials'] = os.path.abspath(args.alt_materials)
    if fov:
        scene['camera']['fovV'] = fov
    einbau.FILAREN = os.path.abspath(args.alt_filarender) if alt else FILAREN_NEW
    return einbau.render(scene, out_png, display)


def label(draw, x, y, text):
    draw.rectangle([x - 4, y - 4, x + 7 * len(text) + 6, y + 16], fill=(0, 0, 0))
    draw.text((x, y), text, fill=(255, 255, 255))


def step_diff(a, b):
    """Mittlerer Unterschied zweier Bilder (0–255), auf 1/8 verkleinert — Rauschen des Rasters mittelt sich weg."""
    from PIL import Image, ImageChops, ImageStat
    w, h = a.size
    sa, sb = a.resize((w // 8, h // 8), Image.BOX), b.resize((w // 8, h // 8), Image.BOX)
    return round(sum(ImageStat.Stat(ImageChops.difference(sa, sb)).mean) / 3, 2)


FILAREN_NEW = einbau.FILAREN


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('world')
    ap.add_argument('--sky', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--alt-filarender', required=True)
    ap.add_argument('--alt-materials', required=True)
    ap.add_argument('--alt-library', required=True)
    ap.add_argument('--library', default=einbau.LIBRARY)
    ap.add_argument('--at', default='')
    ap.add_argument('--folge', default='')
    ap.add_argument('--fein', default='')
    ap.add_argument('--tele', type=float, default=30.0)
    ap.add_argument('--detail', type=int, default=2)
    ap.add_argument('--size', default='960x600')
    ap.add_argument('--jpeg-out', default='')
    ap.add_argument('--prefix', default='wechsel')
    args = ap.parse_args()
    from PIL import Image, ImageDraw
    world, sky, out = os.path.abspath(args.world), os.path.abspath(args.sky), os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    man = json.load(open(os.path.join(world, 'manifest.json')))
    display = os.environ.get('DISPLAY')
    xvfb = None
    if not display:
        display = ':97'
        xvfb = subprocess.Popen(['Xvfb', display, '-screen', '0', '1280x1024x24', '-nolisten', 'tcp'],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(3.0)
    report = {'builder': man.get('builder'), 'detail': args.detail, 'spots': [], 'folge': [], 'fein': {}}

    def save(im, name, width):
        im.save(os.path.join(out, name + '.png'))
        if args.jpeg_out:
            os.makedirs(args.jpeg_out, exist_ok=True)
            im.resize((width, round(im.size[1] * width / im.size[0]))).save(os.path.join(args.jpeg_out, name + '.jpg'), quality=86)

    try:
        for d in [float(x) for x in args.at.split(',') if x]:
            name = f'{args.prefix}-{int(d):04d}m'
            a, b = os.path.join(out, name + '-vorher.png'), os.path.join(out, name + '-nachher.png')
            sa = render(args, world, man, d, sky, args.size, a, display, True)
            sb = render(args, world, man, d, sky, args.size, b, display, False)
            im = einbau.side_by_side(a, b, os.path.join(out, name + '.png'), 'vorher: Abschnitt schaltet um',
                                     f'nachher: Wechselring {sb.get("treeFadeStart")}-{sb.get("treeFadeEnd")} m')
            save(im, name, 1280)
            report['spots'].append({'distM': d, 'name': name, 'vorher': sa, 'nachher': sb})
            print(name, 'vorher', sa.get('treeNear'), '/', sa.get('treeMid'), sa.get('treeTriangles'), '| nachher', sb.get('treeNear'), '/',
                  sb.get('treeMid'), sb.get('treeTriangles'), 'Ring', sb.get('treeFadeStart'), sb.get('treeFadeEnd'))
        if args.folge:
            a0, b0, st = [float(x) for x in args.folge.split(':')]
            spots = []
            d = a0
            while d <= b0 + 1e-6:
                spots.append(d)
                d += st
            w, h = map(int, args.size.split('x'))
            cw, ch = w // 2, h // 2
            sheet = Image.new('RGB', (cw * len(spots) + 4 * (len(spots) - 1), ch * 2 + 4), (255, 255, 255))
            draw = ImageDraw.Draw(sheet)
            prev = [None, None]
            for i, d in enumerate(spots):
                row = {'distM': d}
                for r, alt in enumerate((True, False)):
                    png = os.path.join(out, f'{args.prefix}-folge-{int(d):04d}m-{"vorher" if alt else "nachher"}.png')
                    s = render(args, world, man, d, sky, args.size, png, display, alt, args.tele)
                    im = Image.open(png).convert('RGB')
                    key = 'vorher' if alt else 'nachher'
                    row[key] = {k: s.get(k) for k in ('treeNear', 'treeMid', 'treeTriangles', 'treeBinsHidden', 'treeFadeStart', 'treeFadeEnd')}
                    if prev[r] is not None:
                        row[key]['schritt'] = step_diff(prev[r], im)
                    prev[r] = im
                    sheet.paste(im.resize((cw, ch), Image.LANCZOS), (i * (cw + 4), r * (ch + 4)))
                    label(draw, i * (cw + 4) + 8, r * (ch + 4) + 8, f'{"vorher" if alt else "nachher"} {int(d)} m')
                report['folge'].append(row)
                print('Folge', int(d), 'Schritt vorher', row['vorher'].get('schritt'), 'nachher', row['nachher'].get('schritt'))
            save(sheet, f'{args.prefix}-folge-{int(a0):04d}m', min(sheet.size[0], 2400))
        if args.fein:
            a0, b0, st = [float(x) for x in args.fein.split(':')]
            steps = {'vorher': [], 'nachher': []}
            for key, alt in (('vorher', True), ('nachher', False)):
                prev, d = None, a0
                png = os.path.join(out, f'{args.prefix}-fein-{key}.png')
                while d <= b0 + 1e-6:
                    render(args, world, man, d, sky, args.size, png, display, alt, args.tele)
                    im = Image.open(png).convert('RGB')
                    im.load()
                    if prev is not None:
                        steps[key].append({'distM': d, 'schritt': step_diff(prev, im)})
                    prev = im
                    d += st
                os.remove(png)
                v = sorted(x['schritt'] for x in steps[key])
                top = max(steps[key], key=lambda x: x['schritt'])
                report['fein'][key] = {'von': a0, 'bis': b0, 'schrittM': st, 'median': v[len(v) // 2], 'max': top['schritt'], 'maxBeiM': top['distM'],
                                       'schritte': steps[key]}
                print('Feinfolge', key, 'Median', v[len(v) // 2], 'groesster Schritt', top['schritt'], 'bei', top['distM'], 'm')
    finally:
        if xvfb:
            xvfb.kill()
    json.dump(report, open(os.path.join(out, 'wechsel.json'), 'w'), indent=1)


if __name__ == '__main__':
    main()
