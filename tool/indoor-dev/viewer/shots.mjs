// Screenshots einer Indoor-Welt in headless Chromium.
//
//   node shots.mjs <weltverzeichnis> [--dists 0,500,…|--count N] [--cameras chase,handlebar,drone]
//                  [--size 1600x900] [--jpeg] [--out <dir>] [--sun-az 235] [--sun-el 40]
//                  [--no-rider] [--no-attribution] [--models <dir>] [--param k=v …] [--serve [--port 8123]]
//
// Startet einen kleinen HTTP-Server (nur 127.0.0.1), oeffnet den Betrachter und
// macht je Stelle × Kamera ein Bild nach <welt>/shots/<laufmeter>_<kamera>.png.
// Exit-Code ≠ 0 bei Konsolenfehlern, Formatverstoessen, fehlenden Kacheln oder
// schwarzem Bild.

import http from 'node:http';
import { createReadStream, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..', '..', '..');

// ---------------------------------------------------------------------------
// Argumente
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const a = {
    world: null, dists: null, count: 6, cameras: ['chase', 'handlebar', 'drone'], width: 1600, height: 900,
    jpeg: false, out: null, serve: false, port: 0, rider: true, attribution: true, sunAz: null, sunEl: null,
    models: join(REPO, 'app', 'src', 'main', 'assets', 'indoor', 'models'), extra: [],
  };
  const need = (i, name) => {
    if (i >= argv.length) throw new Error(`${name} braucht einen Wert`);
    return argv[i];
  };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    switch (k) {
      case '--dists': a.dists = need(++i, k).split(',').map(Number).filter(Number.isFinite); break;
      case '--count': a.count = Math.max(1, parseInt(need(++i, k), 10)); break;
      case '--cameras': a.cameras = need(++i, k).split(',').map((s) => s.trim().toLowerCase()).filter(Boolean); break;
      case '--size': { const m = /^(\d+)x(\d+)$/.exec(need(++i, k)); if (!m) throw new Error('--size erwartet BREITExHOEHE'); a.width = +m[1]; a.height = +m[2]; break; }
      case '--jpeg': a.jpeg = true; break;
      case '--out': a.out = resolve(need(++i, k)); break;
      case '--serve': a.serve = true; break;
      case '--port': a.port = parseInt(need(++i, k), 10); break;
      case '--no-rider': a.rider = false; break;
      case '--no-attribution': a.attribution = false; break;
      case '--sun-az': a.sunAz = Number(need(++i, k)); break;
      case '--sun-el': a.sunEl = Number(need(++i, k)); break;
      case '--models': a.models = resolve(need(++i, k)); break;
      case '--param': a.extra.push(need(++i, k)); break;
      case '-h': case '--help': a.help = true; break;
      default:
        if (k.startsWith('--')) throw new Error(`unbekannte Option ${k}`);
        if (a.world) throw new Error(`zweites Weltverzeichnis "${k}"`);
        a.world = resolve(k);
    }
  }
  return a;
}

const USAGE = 'Aufruf: node shots.mjs <weltverzeichnis> [--dists 0,500,…|--count N] [--cameras chase,handlebar,drone] [--size 1600x900] [--jpeg] [--out <dir>] [--serve]';

let args;
try {
  args = parseArgs(process.argv.slice(2));
} catch (e) {
  console.error(`${e.message}\n${USAGE}`);
  process.exit(2);
}
if (args.help || !args.world) {
  console.log(USAGE);
  process.exit(args.help ? 0 : 2);
}
if (!existsSync(join(args.world, 'manifest.json'))) {
  console.error(`Kein manifest.json in ${args.world}`);
  process.exit(2);
}
if (!existsSync(join(HERE, 'node_modules', 'three'))) {
  console.error('three fehlt: zuerst `npm install` im Verzeichnis viewer/ ausfuehren.');
  process.exit(2);
}

// ---------------------------------------------------------------------------
// Statischer Server: /viewer/ → dieses Verzeichnis, /world/ → Welt, /models/ → Modelle
// ---------------------------------------------------------------------------

const MOUNTS = { viewer: HERE, world: args.world, models: args.models };
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.glb': 'model/gltf-binary', '.png': 'image/png', '.jpg': 'image/jpeg', '.css': 'text/css',
};

const server = http.createServer((req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    const m = /^\/(viewer|world|models)\/(.*)$/.exec(path);
    if (!m) { res.writeHead(path === '/' ? 302 : 404, path === '/' ? { Location: '/viewer/index.html' } : {}); res.end(); return; }
    const root = MOUNTS[m[1]];
    const file = normalize(join(root, m[2] || 'index.html'));
    if (file !== root && !file.startsWith(root + sep)) { res.writeHead(403); res.end(); return; }
    if (!existsSync(file) || !statSync(file).isFile()) { res.writeHead(404); res.end('nicht gefunden'); return; }
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] ?? 'application/octet-stream', 'Content-Length': statSync(file).size, 'Cache-Control': 'no-store' });
    if (req.method === 'HEAD') res.end(); else createReadStream(file).pipe(res);
  } catch (e) {
    res.writeHead(500);
    res.end(String(e.message));
  }
});
await new Promise((ok, fail) => { server.once('error', fail); server.listen(args.port, '127.0.0.1', ok); });
const base = `http://127.0.0.1:${server.address().port}`;

function pageUrl(extra = {}) {
  const q = new URLSearchParams({ world: '/world', ...extra });
  // Gibt es die Modelle (noch) nicht, zeichnet der Betrachter Platzhalter, ohne einen Ladeversuch.
  q.set('models', existsSync(join(args.models, 'rider.glb')) ? '/models' : '');
  if (!args.attribution) q.set('attribution', '0');
  if (!args.rider) q.set('rider', '0');
  for (const kv of args.extra) { const i = kv.indexOf('='); q.set(i < 0 ? kv : kv.slice(0, i), i < 0 ? '1' : kv.slice(i + 1)); }
  return `${base}/viewer/index.html?${q}`;
}

if (args.serve) {
  console.log(`Betrachter: ${pageUrl()}`);
  console.log('Strg+C beendet.');
  await new Promise(() => {});
}

// ---------------------------------------------------------------------------
// Bilder
// ---------------------------------------------------------------------------

const manifest = JSON.parse(readFileSync(join(args.world, 'manifest.json'), 'utf8'));
const lengthM = manifest.track.lengthM;
const dists = args.dists ?? Array.from({ length: args.count }, (_, i) => Math.round((i * lengthM) / args.count));
const outDir = args.out ?? join(args.world, 'shots');
mkdirSync(outDir, { recursive: true });

// Playwright ist global installiert; `import` findet es von hier aus nicht, `require` schon.
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const problems = [];
const browser = await chromium.launch({
  headless: true,
  args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--enable-webgl'],
});
let exitCode = 0;
try {
  const context = await browser.newContext({ viewport: { width: args.width, height: args.height }, deviceScaleFactor: 1 });
  const page = await context.newPage();
  page.on('console', (msg) => {
    if (msg.type() === 'error') problems.push(`Konsole: ${msg.text()}`);
    else if (msg.type() === 'warning' && msg.text().startsWith('[viewer]')) console.log(`  Warnung: ${msg.text().slice(9)}`);
  });
  page.on('pageerror', (e) => problems.push(`Seitenfehler: ${e.message}`));
  page.on('requestfailed', (r) => problems.push(`Abruf gescheitert: ${r.url()} (${r.failure()?.errorText})`));
  page.on('response', (r) => { if (r.status() >= 400) problems.push(`HTTP ${r.status()}: ${r.url()}`); });

  await page.goto(pageUrl({ ui: '0', auto: '0' }), { waitUntil: 'load' });
  await page.waitForFunction(() => !!window.__viewer, null, { timeout: 30000 });
  await page.evaluate(() => window.__viewer.ready.then(() => true));
  const gl = await page.evaluate(() => {
    const g = window.__viewer.three.renderer.getContext();
    const ext = g.getExtension('WEBGL_debug_renderer_info');
    return ext ? g.getParameter(ext.UNMASKED_RENDERER_WEBGL) : g.getParameter(g.RENDERER);
  });
  console.log(`Welt ${manifest.worldId} (${manifest.quality}), Strecke ${lengthM.toFixed(0)} m, ${manifest.tiles.length} Kacheln — ${gl}`);

  const mb = (b) => `${(b / 1048576).toFixed(0)} MB`;
  for (const dist of dists) {
    for (const cam of args.cameras) {
      const name = `${String(Math.round(dist)).padStart(5, '0')}_${cam}`;
      const t0 = Date.now();
      const before = problems.length;
      const opts = { dist, camera: cam, rider: args.rider };
      if (args.sunAz !== null) opts.sunAzimuthDeg = args.sunAz;
      if (args.sunEl !== null) opts.sunElevationDeg = args.sunEl;
      const result = await page.evaluate((o) => window.__viewer.setView(o), opts);
      const stats = await page.evaluate(() => window.__viewer.stats());
      const px = await page.evaluate(() => window.__viewer.pixelStats());
      const file = join(outDir, `${name}.png`);
      const pngBuf = await page.screenshot({ path: file, type: 'png' });
      let jpegNote = '';
      if (args.jpeg) {
        // Verkleinert auf 1280 px Breite, Qualitaet so weit senken, bis die Datei unter 300 kB bleibt.
        const b64 = pngBuf.toString('base64');
        for (const q of [0.86, 0.8, 0.72, 0.64, 0.55, 0.45, 0.35]) {
          const data = await page.evaluate(async ({ b64, q }) => {
            const img = new Image();
            img.src = `data:image/png;base64,${b64}`;
            await img.decode();
            const w = Math.min(1280, img.width);
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
            writeFileSync(join(outDir, `${name}.jpg`), buf);
            jpegNote = `, JPEG ${(buf.length / 1024).toFixed(0)} kB`;
            break;
          }
        }
      }
      if (!result.ok) problems.push(`${name}: Stelle unvollstaendig (${result.failedTiles.length} Kacheln fehlen: ${result.failedTiles.join(', ')})`);
      if (px.mean < 10 || px.stddev < 3 || px.darkFraction > 0.6) {
        problems.push(`${name}: Bild schwarz oder leer (Mittel ${px.mean.toFixed(1)}, Streuung ${px.stddev.toFixed(1)}, dunkel ${(px.darkFraction * 100).toFixed(0)} %)`);
      }
      console.log(
        `${name}.png  ${((Date.now() - t0) / 1000).toFixed(1)} s (Kacheln laden und zeichnen ${result.loadMs} ms)  ` +
        `Kacheln ${stats.loadedTiles} ${JSON.stringify(stats.tilesByLevel)}  Teilflaechen aus ${result.hiddenSubs}  ` +
        `Dreiecke ${stats.visibleTriangles} (gezeichnet ${stats.renderedTriangles})  Draw Calls ${stats.drawCalls}  ` +
        `Texturen ${mb(stats.textureBytes)} (Manifest ${mb(stats.manifestTextureBytes)})  Helligkeit ${px.mean.toFixed(0)}±${px.stddev.toFixed(0)}${jpegNote}`,
      );
      for (const p of problems.slice(before)) console.log(`  FEHLER: ${p}`);
    }
  }
  const viewerErrors = await page.evaluate(() => window.__viewer.errors.slice());
  for (const e of viewerErrors) if (!problems.some((p) => p.includes(e))) problems.push(e);
} catch (e) {
  problems.push(`Abbruch: ${e.message}`);
} finally {
  await browser.close();
  server.close();
}

if (problems.length) {
  console.error(`\n${problems.length} Problem(e):`);
  for (const p of [...new Set(problems)].slice(0, 60)) console.error(`  - ${p}`);
  exitCode = 1;
} else {
  console.log(`\nIn Ordnung: ${dists.length * args.cameras.length} Bilder in ${outDir}`);
}
process.exit(exitCode);
