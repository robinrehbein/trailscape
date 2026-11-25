// filashots.mjs — Bilder einer Welt mit dem Filament-Referenzrenderer (../filament).
//
// Anders als shots.mjs (three.js im Browser) rechnet dieses Skript nur die Szene
// aus — Kacheln nach `show`, Teilflaechen-Regel, Fahrer und Kamera wie die App
// (`TilePlanner`, `CameraRig`, `modelMatrix`) — und laesst echtes Filament 1.75.1
// zeichnen (`filament/build/filarender`, unter Xvfb auf Mesa llvmpipe).
//
//   node filashots.mjs <welt> --sky <dir> [--dists 0,500] [--cameras chase,handlebar]
//        (Kameras mit ; trennen)  [--size 900x1200] [--out <dir>] [--prefix name_] [--materials <dir>] [--time s]
//        [--set shadows=0 …] [--rig v1] [--no-rider] [--speed 8]
//        [--riders "18:-0.9:0,60:0.9:2,140:-1.3:1:o"]   andere Radfahrer: Abstand voraus (m), Seitenversatz
//                                                     (rechts +), Trikot 0–5, „o" = Gegenverkehr
//        [--birds 5]                                   ein Schwarm von n Voegeln vor der Kamera
//        [--seti effectQuality=2] [--frames 16]        Zahl-Schalter; Bilder je Aufnahme (TAA)
//
// `--sky` ist ein Verzeichnis von `indoor-dev sky` (sky.json, cube.bin, clouds.bin).
// `--rig v1` nimmt die Lenkerkamera vor der Ueberarbeitung (fuer Vorher-Bilder).

import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { cameraPose, riderMatrix, laneOffsetM, projection, steerMatrix } from './camerarig.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..', '..', '..');
const BIN = join(HERE, '..', 'filament', 'build', 'filarender');

const a = {
  world: null, sky: null, dists: [0], cameras: ['chase'], width: 900, height: 1200, out: null, prefix: '',
  materials: join(REPO, 'app', 'src', 'main', 'assets', 'indoor', 'materials'), time: 0, set: {}, rig: 'v2', rider: true,
  models: join(REPO, 'app', 'src', 'main', 'assets', 'indoor', 'models'), speed: 8,
  cockpit: join(REPO, 'app', 'build', 'indoor', 'cockpit.glb'), // aus CockpitAndHandlebarTest
  indoorBuild: join(REPO, 'app', 'build', 'indoor'), // rider_<k>.glb, birds.glb aus LifeAndMaterialsTest
  riders: [], birds: 0,
};
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  const k = argv[i];
  const v = () => argv[++i];
  switch (k) {
    case '--sky': a.sky = resolve(v()); break;
    case '--dists': a.dists = v().split(',').map(Number); break;
    case '--cameras': a.cameras = v().split(';'); break;
    case '--size': { const m = /^(\d+)x(\d+)$/.exec(v()); a.width = +m[1]; a.height = +m[2]; break; }
    case '--out': a.out = resolve(v()); break;
    case '--prefix': a.prefix = v(); break;
    case '--materials': a.materials = resolve(v()); break;
    case '--models': a.models = resolve(v()); break;
    case '--time': a.time = Number(v()); break;
    case '--speed': a.speed = Number(v()); break;
    case '--rig': a.rig = v(); break;
    case '--no-rider': a.rider = false; break;
    case '--riders': a.riders = v().split(',').filter(Boolean).map((x) => { const p = x.split(':'); return { gap: +p[0], lat: +p[1], jersey: +(p[2] ?? 0), oncoming: p[3] === 'o' }; }); break;
    case '--birds': a.birds = Number(v()); break;
    case '--set': { const [kk, vv] = v().split('='); a.set[kk] = vv === '1' || vv === 'true'; break; }
    case '--seti': { const [kk, vv] = v().split('='); a.set[kk] = Number(vv); break; } // Zahl (effectQuality, cascades)
    case '--sets': { const [kk, vv] = v().split('='); a.set[kk] = vv; break; } // Text (shadowType=PCF)
    case '--frames': a.frames = Number(v()); break; // Bilder je Aufnahme (TAA braucht mehrere)
    default:
      if (k.startsWith('--')) { console.error(`unbekannte Option ${k}`); process.exit(2); }
      a.world = resolve(k);
  }
}
if (!a.world || !a.sky) {
  console.error('Aufruf: node filashots.mjs <welt> --sky <dir> [--dists …] [--cameras …] [--size BxH] [--out dir]');
  process.exit(2);
}
if (!existsSync(BIN)) {
  console.error(`${BIN} fehlt: zuerst tool/indoor-dev/filament/build.sh`);
  process.exit(2);
}
const manifest = JSON.parse(readFileSync(join(a.world, 'manifest.json'), 'utf8'));
const out = a.out ?? join(a.world, 'filashots');
mkdirSync(out, { recursive: true });

// ---- Strecke wie WorldTrack ----
const track = manifest.track;
const fold = (d) => {
  const len = track.lengthM;
  if (track.loop && len > 0) return ((d % len) + len) % len;
  return Math.min(Math.max(d, 0), len);
};
export function poseAt(distM) {
  const { e, n, u, stepM, lengthM } = track;
  const d = fold(distM);
  const distAt = (i) => Math.min(i * stepM, lengthM);
  const last = e.length - 1;
  const i = Math.min(Math.max(Math.trunc(d / stepM), 0), Math.max(0, last - 1));
  const j = Math.min(i + 1, last);
  const span = distAt(j) - distAt(i);
  const t = span > 0 ? Math.min(Math.max((d - distAt(i)) / span, 0), 1) : 0;
  const de = e[j] - e[i], dn = n[j] - n[i], du = u[j] - u[i];
  return {
    e: e[i] + de * t, n: n[i] + dn * t, u: u[i] + du * t,
    headingDeg: ((Math.atan2(de, dn) * 180) / Math.PI + 360) % 360,
    gradePct: span > 0 ? (du / span) * 100 : 0,
  };
}
const DEFAULT_W = { ASPHALT: 4, PAVED: 3, GRAVEL: 3, DIRT: 2, UNKNOWN: 3 };
function widthAt(d) {
  const f = fold(d);
  const s = (track.surfaces ?? []).find((x) => f >= x.fromM && f <= x.toM);
  if (!s) return 3;
  return typeof s.widthM === 'number' ? s.widthM : (s.effectiveWidthM ?? DEFAULT_W[s.kind] ?? 3);
}

// ---- Kacheln wie TilePlanner (Stelle + 200 m Vorlauf, Teilflaechen-Regel) ----
const tileByKey = new Map(manifest.tiles.map((t) => [`${t.level}_${t.ix}_${t.iy}`, t]));
function neededTiles(dist) {
  const ds = [fold(dist)];
  for (let x = 20; x <= 200; x += 20) ds.push(fold(dist + x));
  return new Set(manifest.tiles.filter((t) => t.show.some(([f, to]) => ds.some((d) => d >= f && d <= to))).map((t) => t.id));
}

// ---- Xvfb einmal starten ----
let dn = 100 + (process.pid % 400);
while (existsSync(`/tmp/.X${dn}-lock`)) dn++;
const display = `:${dn}`;
const xvfb = spawn('Xvfb', [display, '-screen', '0', '1280x1024x24', '-nolisten', 'tcp'], { stdio: 'ignore' });
await new Promise((r) => setTimeout(r, 800));
process.on('exit', () => xvfb.kill());

const settings = { shadows: true, fog: true, sky: true, postProcessing: true, ambientOcclusion: false, bloom: false, trees: true, props: true, ...a.set };
const portrait = a.width < a.height;
let failed = 0;
for (const dist of a.dists) {
  const needed = neededTiles(dist);
  const here = poseAt(dist);
  const lat = laneOffsetM(widthAt(dist));
  const rel = (e, n, u) => [e - Math.round(here.e), u, -(n - Math.round(here.n))]; // schwebender Ursprung
  const tiles = [];
  for (const id of needed) {
    const t = manifest.tiles.find((x) => x.id === id);
    const hidden = [];
    if (t.level >= 1) {
      for (let i = 0; i < 4; i++) for (let j = 0; j < 4; j++) {
        const finer = tileByKey.get(`${t.level - 1}_${t.ix * 4 + i}_${t.iy * 4 + j}`);
        if (finer && needed.has(finer.id)) hidden.push(`terrain_sub_${i}_${j}`);
      }
    }
    tiles.push({ file: t.file, pos: rel(t.anchor[0], t.anchor[1], t.anchor[2]), hidden });
  }
  for (const cam of a.cameras) {
    const ctx = { poseAt, widthAt, lat, speed: a.speed, rel, portrait, rig: a.rig };
    const pose = cameraPose(cam, dist, ctx);
    const proj = projection(cam, portrait, a.rig);
    const name = `${a.prefix}${String(Math.round(dist)).padStart(5, '0')}_${cam.startsWith('look:') ? 'blick' : cam}`;
    const showRider = a.rider && !(pose.hideRider);
    const scene = {
      world: a.world, sky: a.sky, materials: a.materials, width: a.width, height: a.height, timeS: a.time, settings,
      ...(a.frames ? { frames: a.frames } : {}),
      camera: { eye: pose.eye, target: pose.target, up: pose.up, fovV: proj.fovV, near: proj.near, far: 25000, shiftX: 0, shiftY: proj.shiftY },
      fogHeight: here.u - 40,
      tiles,
      rider: showRider ? { file: join(a.models, 'rider.glb'), matrix: riderMatrix(rel(...enuLateral(here, lat)), here.headingDeg, here.gradePct), pedal: 0.15, roll: 0 } : null,
      cockpit: pose.cockpit && existsSync(a.cockpit) ? { file: a.cockpit, matrix: pose.cockpit, nodes: { x_cockpit_bars: steerMatrix(pose.steer) } } : null,
    };
    const models = [];
    for (const r of a.riders) {
      const p = poseAt(dist + r.gap);
      const heading = r.oncoming ? (p.headingDeg + 180) % 360 : p.headingDeg;
      const grade = r.oncoming ? -p.gradePct : p.gradePct;
      models.push({ file: join(a.indoorBuild, `rider_${r.jersey}.glb`), matrix: riderMatrix(rel(...enuLateral(p, r.lat)), heading, grade), pedal: (r.gap * 0.37) % 1, roll: (r.gap * 0.71) % 1 });
    }
    if (a.birds > 0) models.push(birdModel(pose, a.birds));
    scene.models = models;
    const sceneFile = join(out, `${name}.scene.json`);
    writeFileSync(sceneFile, JSON.stringify(scene));
    const t0 = Date.now();
    const r = spawnSync(BIN, [sceneFile, join(out, `${name}.png`)], { env: { ...process.env, DISPLAY: display }, encoding: 'utf8' });
    const stat = (r.stdout || '').trim().split('\n').pop();
    if (r.status !== 0) {
      failed++;
      console.log(`${name}: FEHLER ${r.status}\n${(r.stderr || '').split('\n').slice(-8).join('\n')}`);
    } else {
      console.log(`${name}.png  ${((Date.now() - t0) / 1000).toFixed(1)} s  Kacheln ${tiles.length}  ${stat}`);
    }
  }
}
process.exit(failed ? 1 : 0);

// Ein Schwarm wie BirdFlock: 55–80 m vor der Kamera, 14–20 m hoch, quer fliegend, Fluegel in
// verschiedenen Phasen (birds.glb: x_bird_<i> mit x_bird_<i>_l/_r).
function birdModel(pose, n) {
  const [ex, ey, ez] = pose.eye, [tx, , tz] = pose.target;
  let fx = tx - ex, fz = tz - ez;
  const l = Math.hypot(fx, fz) || 1; fx /= l; fz /= l;
  const rx = -fz, rz = fx;
  const yaw = Math.atan2(rx, rz); // fliegen nach rechts
  const nodes = {};
  const c = Math.cos(yaw), s = Math.sin(yaw);
  for (let i = 0; i < 7; i++) {
    const vis = i < n;
    const row = Math.floor((i + 1) / 2), side = i % 2 ? -1 : 1;
    const ahead = 62 + row * 1.8 * side * 0.4, lateral = -6 - row * 2.2, up = 16 + (i % 3) * 0.7 + side * row * 0.4;
    const px = ex + fx * ahead + rx * (lateral + side * row * 1.4), pz = ez + fz * ahead + rz * (lateral + side * row * 1.4);
    const py = vis ? ey + up : -10000;
    nodes[`x_bird_${i}`] = [c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, px, py, pz, 1];
    const flap = 0.7 * Math.sin(i * 1.9 + 0.6);
    for (const [k, sign] of [['l', -1], ['r', 1]]) {
      const ca = Math.cos(sign * flap), sa = Math.sin(sign * flap);
      nodes[`x_bird_${i}_${k}`] = [ca, sa, 0, 0, -sa, ca, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
    }
  }
  return { file: join(a.indoorBuild, 'birds.glb'), matrix: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1], nodes };
}

function enuLateral(p, lateralM) {
  const h = (p.headingDeg * Math.PI) / 180;
  // rechts der Fahrtrichtung in ENU: (cos h, −sin h)
  return [p.e + Math.cos(h) * lateralM, p.n - Math.sin(h) * lateralM, p.u];
}
