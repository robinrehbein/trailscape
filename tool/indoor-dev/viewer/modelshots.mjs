// Kontaktbogen der Modelle rider.glb / ghost.glb in headless Chromium.
//
//   node modelshots.mjs [--models <dir>] [--model rider|ghost] [--views chase,side,front,legs]
//                       [--phases 0,0.25,0.5,0.75] [--roll 0.1] [--cell 640x480] [--out <datei.png>]
//                       [--jpeg <datei.jpg>] [--sun-az 235] [--sun-el 40]
//
// Stellt das Modell auf eine Bodenflaeche mit Sonne und Schatten und zeichnet je
// Ansicht (Zeile) und Pedalphase (Spalte) ein Feld: Verfolgerkamera (6 m
// hinten, 2,5 m hoch), Seite, schraeg vorn, Nahaufnahme Beine/Kurbel; dazu
// `back`, `top`, `hands`, `head`. `--jpeg` legt zusaetzlich ein verkleinertes
// JPEG unter 300 kB ab. Exit-Code ≠ 0 bei Konsolen- oder Ladefehlern.

import http from 'node:http';
import { createReadStream, existsSync, mkdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..', '..', '..');

const VIEWS = {
  // Lage der Kamera und Blickziel im Modellraum (Blick des Fahrers nach +Z), Brennweite als senkrechter Bildwinkel.
  chase: { pos: [0, 2.5, -6.0], target: [0, 1.0, 0.6], fov: 24 },
  back: { pos: [0.5, 1.5, -4.2], target: [0, 0.95, 0], fov: 26 },
  side: { pos: [-4.4, 1.0, 0.0], target: [0, 0.86, 0.02], fov: 24 },
  left: { pos: [4.4, 1.0, 0.0], target: [0, 0.86, 0.02], fov: 24 },
  front: { pos: [-2.6, 1.5, 3.6], target: [0, 0.9, 0], fov: 26 },
  legs: { pos: [-1.7, 0.7, 0.45], target: [0, 0.6, -0.12], fov: 34 },
  top: { pos: [0.01, 5.0, -0.6], target: [0, 0.9, 0.05], fov: 24 },
  hands: { pos: [-0.9, 1.35, 1.25], target: [0, 1.0, 0.42], fov: 30 },
  head: { pos: [-0.75, 1.7, 1.05], target: [0, 1.55, 0.2], fov: 30 },
  rear34: { pos: [-2.2, 1.6, -3.4], target: [0, 0.9, 0], fov: 26 },
};

function parseArgs(argv) {
  const a = {
    models: join(REPO, 'app', 'src', 'main', 'assets', 'indoor', 'models'), model: 'rider',
    views: ['chase', 'side', 'front', 'legs'], phases: [0, 0.25, 0.5, 0.75], roll: 0.1,
    cellW: 640, cellH: 480, out: null, jpeg: null, sunAz: 235, sunEl: 40,
  };
  const need = (i, name) => {
    if (i >= argv.length) throw new Error(`${name} braucht einen Wert`);
    return argv[i];
  };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    switch (k) {
      case '--models': a.models = resolve(need(++i, k)); break;
      case '--model': a.model = need(++i, k); break;
      case '--views': a.views = need(++i, k).split(',').map((s) => s.trim()).filter(Boolean); break;
      case '--phases': a.phases = need(++i, k).split(',').map(Number).filter(Number.isFinite); break;
      case '--roll': a.roll = Number(need(++i, k)); break;
      case '--cell': { const m = /^(\d+)x(\d+)$/.exec(need(++i, k)); if (!m) throw new Error('--cell erwartet BREITExHOEHE'); a.cellW = +m[1]; a.cellH = +m[2]; break; }
      case '--out': a.out = resolve(need(++i, k)); break;
      case '--jpeg': a.jpeg = resolve(need(++i, k)); break;
      case '--sun-az': a.sunAz = Number(need(++i, k)); break;
      case '--sun-el': a.sunEl = Number(need(++i, k)); break;
      case '-h': case '--help': a.help = true; break;
      default: throw new Error(`unbekannte Option ${k}`);
    }
  }
  for (const v of a.views) if (!VIEWS[v]) throw new Error(`unbekannte Ansicht ${v} (es gibt: ${Object.keys(VIEWS).join(', ')})`);
  if (!['rider', 'ghost'].includes(a.model)) throw new Error('--model erwartet rider oder ghost');
  return a;
}

const USAGE = 'Aufruf: node modelshots.mjs [--models <dir>] [--model rider|ghost] [--views chase,side,front,legs] [--phases 0,0.25,0.5,0.75] [--cell 640x480] [--out <datei.png>] [--jpeg <datei.jpg>]';

let args;
try {
  args = parseArgs(process.argv.slice(2));
} catch (e) {
  console.error(`${e.message}\n${USAGE}`);
  process.exit(2);
}
if (args.help) {
  console.log(USAGE);
  process.exit(0);
}
const modelFile = join(args.models, `${args.model}.glb`);
if (!existsSync(modelFile)) {
  console.error(`Modell fehlt: ${modelFile}`);
  process.exit(2);
}
if (!existsSync(join(HERE, 'node_modules', 'three'))) {
  console.error('three fehlt: zuerst `npm install` im Verzeichnis viewer/ ausfuehren.');
  process.exit(2);
}
const out = args.out ?? join(args.models, `${args.model}_sheet.png`);
mkdirSync(dirname(out), { recursive: true });

// ---------------------------------------------------------------------------
// Seite
// ---------------------------------------------------------------------------

const PAGE = `<!doctype html>
<html><head><meta charset="utf-8">
<style>html, body { margin: 0; background: #20242a; } canvas { display: block; }</style>
<script type="importmap">{ "imports": { "three": "/viewer/node_modules/three/build/three.module.js", "three/addons/": "/viewer/node_modules/three/examples/jsm/" } }</script>
</head><body><canvas id="view"></canvas>
<script type="module">
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';

const cfg = JSON.parse(decodeURIComponent(location.hash.slice(1)));
const canvas = document.getElementById('view');
const cols = cfg.phases.length;
const rows = cfg.views.length;
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(1);
renderer.setSize(cols * cfg.cellW, rows * cfg.cellH, false);
canvas.style.width = cols * cfg.cellW + 'px';
canvas.style.height = rows * cfg.cellH + 'px';
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.NeutralToneMapping;
renderer.autoClear = false;

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x9fc3e6);
scene.add(new THREE.HemisphereLight(0xcfe3ff, 0x6b7350, 1.15));
const sun = new THREE.DirectionalLight(0xfff2dc, 3.0);
const az = THREE.MathUtils.degToRad(cfg.sunAz);
const el = THREE.MathUtils.degToRad(cfg.sunEl);
// Azimut wie in der Welt: von Norden (−Z) im Uhrzeigersinn; das Modell blickt nach +Z.
sun.position.set(Math.sin(az) * Math.cos(el), Math.sin(el), -Math.cos(az) * Math.cos(el)).multiplyScalar(8);
sun.castShadow = true;
sun.shadow.mapSize.set(4096, 4096);
sun.shadow.camera.left = -2.2; sun.shadow.camera.right = 2.2; sun.shadow.camera.top = 2.6; sun.shadow.camera.bottom = -1.4;
sun.shadow.camera.near = 1; sun.shadow.camera.far = 20;
sun.shadow.bias = -0.0004; sun.shadow.normalBias = 0.004;
scene.add(sun);

// Boden: Asphaltband auf Gras, damit Massstab und Schatten lesbar sind.
const grass = new THREE.Mesh(new THREE.PlaneGeometry(60, 60), new THREE.MeshStandardMaterial({ color: 0x5f7a3c, roughness: 1 }));
grass.rotation.x = -Math.PI / 2; grass.position.y = -0.002; grass.receiveShadow = true; scene.add(grass);
const road = new THREE.Mesh(new THREE.PlaneGeometry(3.2, 60), new THREE.MeshStandardMaterial({ color: 0x6f6f6c, roughness: 0.95 }));
road.rotation.x = -Math.PI / 2; road.receiveShadow = true; scene.add(road);

const gltf = await new GLTFLoader().loadAsync('/models/' + cfg.model + '.glb');
const ghost = cfg.model === 'ghost';
let triangles = 0;
gltf.scene.traverse((o) => {
  if (!o.isMesh) return;
  o.castShadow = !ghost; o.receiveShadow = !ghost; o.frustumCulled = false;
  triangles += (o.geometry.index ? o.geometry.index.count : o.geometry.attributes.position.count) / 3;
});
scene.add(gltf.scene);
const mixer = new THREE.AnimationMixer(gltf.scene);
const actions = {};
for (const name of ['pedal', 'roll']) {
  const clip = gltf.animations.find((c) => c.name === name);
  if (!clip) throw new Error('Animation ' + name + ' fehlt');
  const action = mixer.clipAction(clip);
  action.play(); action.paused = true;
  actions[name] = action;
}

const camera = new THREE.PerspectiveCamera(30, cfg.cellW / cfg.cellH, 0.05, 200);
renderer.setScissorTest(true);
for (let r = 0; r < rows; r++) {
  const v = cfg.viewDefs[cfg.views[r]];
  camera.fov = v.fov; camera.updateProjectionMatrix();
  camera.position.set(...v.pos); camera.lookAt(new THREE.Vector3(...v.target));
  for (let c = 0; c < cols; c++) {
    const t = cfg.phases[c] - Math.floor(cfg.phases[c]);
    actions.pedal.time = t * actions.pedal.getClip().duration;
    actions.roll.time = ((cfg.roll + t * 0.37) % 1) * actions.roll.getClip().duration;
    mixer.update(0);
    const x = c * cfg.cellW;
    const y = (rows - 1 - r) * cfg.cellH;
    renderer.setViewport(x, y, cfg.cellW, cfg.cellH);
    renderer.setScissor(x, y, cfg.cellW, cfg.cellH);
    renderer.clear();
    renderer.render(scene, camera);
  }
}
window.__sheet = { triangles, drawCalls: renderer.info.render.calls };
</script></body></html>`;

// ---------------------------------------------------------------------------
// Statischer Server: /page → Seite, /viewer/ → dieses Verzeichnis, /models/ → Modelle
// ---------------------------------------------------------------------------

const MOUNTS = { viewer: HERE, models: args.models };
const MIME = { '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.json': 'application/json', '.glb': 'model/gltf-binary' };
const server = http.createServer((req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    if (path === '/page') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(PAGE);
      return;
    }
    const m = /^\/(viewer|models)\/(.*)$/.exec(path);
    if (!m) { res.writeHead(404); res.end(); return; }
    const root = MOUNTS[m[1]];
    const file = normalize(join(root, m[2]));
    if (!file.startsWith(root + sep) || !existsSync(file) || !statSync(file).isFile()) { res.writeHead(404); res.end('nicht gefunden'); return; }
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] ?? 'application/octet-stream', 'Content-Length': statSync(file).size, 'Cache-Control': 'no-store' });
    createReadStream(file).pipe(res);
  } catch (e) {
    res.writeHead(500);
    res.end(String(e.message));
  }
});
await new Promise((ok, fail) => { server.once('error', fail); server.listen(0, '127.0.0.1', ok); });
const base = `http://127.0.0.1:${server.address().port}`;

// Playwright ist global installiert; `import` findet es von hier aus nicht, `require` schon.
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const problems = [];
const browser = await chromium.launch({
  headless: true,
  args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--enable-webgl'],
});
try {
  const width = args.phases.length * args.cellW;
  const height = args.views.length * args.cellH;
  const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 });
  const page = await context.newPage();
  page.on('console', (msg) => { if (msg.type() === 'error') problems.push(`Konsole: ${msg.text()}`); });
  page.on('pageerror', (e) => problems.push(`Seitenfehler: ${e.message}`));
  page.on('response', (r) => { if (r.status() >= 400) problems.push(`HTTP ${r.status()}: ${r.url()}`); });
  const cfg = { model: args.model, views: args.views, phases: args.phases, roll: args.roll, cellW: args.cellW, cellH: args.cellH, sunAz: args.sunAz, sunEl: args.sunEl, viewDefs: VIEWS };
  await page.goto(`${base}/page#${encodeURIComponent(JSON.stringify(cfg))}`, { waitUntil: 'load' });
  await page.waitForFunction(() => !!window.__sheet, null, { timeout: 120000 });
  const info = await page.evaluate(() => window.__sheet);
  const png = await page.locator('#view').screenshot({ path: out, type: 'png' });
  console.log(`${out}  ${width}x${height}  ${args.model}.glb: ${info.triangles} Dreiecke, ${(statSync(modelFile).size / 1024).toFixed(0)} kB`);
  if (args.jpeg) {
    mkdirSync(dirname(args.jpeg), { recursive: true });
    // Auf hoechstens 1600 px Breite verkleinern, Qualitaet senken, bis die Datei unter 300 kB bleibt.
    const b64 = png.toString('base64');
    for (const q of [0.86, 0.8, 0.72, 0.64, 0.55, 0.45, 0.35]) {
      const data = await page.evaluate(async ({ b64, q }) => {
        const img = new Image();
        img.src = `data:image/png;base64,${b64}`;
        await img.decode();
        const w = Math.min(1600, img.width);
        const c = document.createElement('canvas');
        c.width = w;
        c.height = Math.round((img.height * w) / img.width);
        const ctx = c.getContext('2d');
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(img, 0, 0, c.width, c.height);
        return c.toDataURL('image/jpeg', q).split(',')[1];
      }, { b64, q });
      const buf = Buffer.from(data, 'base64');
      if (buf.length < 300 * 1024 || q === 0.35) {
        writeFileSync(args.jpeg, buf);
        console.log(`${args.jpeg}  ${(buf.length / 1024).toFixed(0)} kB`);
        break;
      }
    }
  }
} catch (e) {
  problems.push(`Abbruch: ${e.message}`);
} finally {
  await browser.close();
  server.close();
}
for (const p of problems) console.error(`FEHLER: ${p}`);
process.exit(problems.length ? 1 : 0);
