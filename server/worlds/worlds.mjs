// Trailscape-Bau-Dienst („trailscape-worlds")
// Baut Indoor-Welten in Laserqualität für die eigenen Strecken (docs/indoor/serverbau.md).
// Null Abhängigkeiten – nur Node-Core-Module (Node.js >= 18). Der Weltbau selbst
// läuft als eigener Prozess (Werkzeug `indoor-dev server-build`, JVM); dieser
// Dienst nimmt Aufträge an, hält die Warteschlange, liefert Stand und Paket aus
// und räumt auf.

import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';

// ---------------------------------------------------------------------------
// Konfiguration
// ---------------------------------------------------------------------------

function num(name, def) {
  const v = process.env[name];
  if (v === undefined || v === '') return def;
  const n = Number(v);
  if (!Number.isFinite(n) || n <= 0) {
    console.error(`FEHLER: ${name}=${v} ist keine positive Zahl`);
    process.exit(1);
  }
  return n;
}

const PORT = num('PORT', 8081);
const SYNC_TOKEN = (process.env.SYNC_TOKEN || '').trim();
const DATA_DIR = path.resolve(process.env.DATA_DIR || './data-worlds');
const JOBS_DIR = path.join(DATA_DIR, 'jobs');
const CACHE_DIR = path.join(DATA_DIR, 'cache');
const TRUST_PROXY = /^(1|true|yes)$/i.test(process.env.TRUST_PROXY || '');

/** Befehl des Weltbaus: Pfad oder JSON-Feld (`["node","fake.mjs"]` in Tests). Nie über eine Shell. */
function builderCommand() {
  const raw = process.env.BUILDER_CMD || '/opt/indoor-dev/bin/indoor-dev';
  if (raw.trim().startsWith('[')) {
    const arr = JSON.parse(raw);
    if (!Array.isArray(arr) || arr.length === 0 || !arr.every((s) => typeof s === 'string')) {
      throw new Error('BUILDER_CMD: JSON-Feld aus Zeichenketten erwartet');
    }
    return arr;
  }
  return [raw];
}
const BUILDER = builderCommand();

const LIMITS = {
  maxBodyBytes: 4 * 1024 * 1024,
  maxPoints: Math.floor(num('MAX_POINTS', 40000)),
  maxLengthM: num('MAX_ROUTE_KM', 100) * 1000,
  maxSurfaces: 20000,
};
const MAX_JOBS = Math.floor(num('MAX_JOBS', 20)); // wartend + laufend
const KEEP_DAYS = num('KEEP_DAYS', 30); // fertige Pakete ohne Abruf
const MAX_PACKAGES_BYTES = num('MAX_PACKAGES_GB', 20) * 1024 ** 3;
const CACHE_DAYS = num('CACHE_DAYS', 180);
const CACHE_MAX_BYTES = num('CACHE_MAX_GB', 20) * 1024 ** 3;
const BUILD_TIMEOUT_MS = num('BUILD_TIMEOUT_MIN', 90) * 60 * 1000;
const CLEANUP_EVERY_MS = 60 * 60 * 1000;

const MIN_TOKEN_LENGTH = 16;
if (SYNC_TOKEN.length < MIN_TOKEN_LENGTH) {
  console.error(
    `FEHLER: SYNC_TOKEN fehlt oder ist zu kurz (Minimum ${MIN_TOKEN_LENGTH} Zeichen). ` +
      'Derselbe Token wie beim Sync-Server, z. B. SYNC_TOKEN=$(openssl rand -hex 32)',
  );
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Hilfen
// ---------------------------------------------------------------------------

function sendJson(res, status, body, extra = {}) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Cache-Control': 'no-store',
    ...extra,
  });
  res.end(payload);
}

function sendError(res, status, code, detail, extra = {}) {
  sendJson(res, status, { error: { code, detail, ...extra } });
}

function isAuthorized(req) {
  const m = /^Bearer (.+)$/.exec(req.headers['authorization'] || '');
  if (!m) return false;
  const a = crypto.createHash('sha256').update(m[1]).digest();
  const b = crypto.createHash('sha256').update(SYNC_TOKEN).digest();
  return crypto.timingSafeEqual(a, b);
}

// Sperre nach Fehlversuchen wie beim Sync-Server.
const AUTH_WINDOW_MS = 15 * 60 * 1000;
const AUTH_MAX_FAILURES = 10;
const authFailures = new Map();

function retryAfter(ip) {
  const e = authFailures.get(ip);
  if (!e) return 0;
  const elapsed = Date.now() - e.windowStart;
  if (elapsed > AUTH_WINDOW_MS) {
    authFailures.delete(ip);
    return 0;
  }
  if (e.count < AUTH_MAX_FAILURES) return 0;
  return Math.max(1, Math.ceil((AUTH_WINDOW_MS - elapsed) / 1000));
}

function recordFailure(ip) {
  const now = Date.now();
  const e = authFailures.get(ip);
  if (!e || now - e.windowStart > AUTH_WINDOW_MS) authFailures.set(ip, { count: 1, windowStart: now });
  else e.count += 1;
  console.warn(`${new Date(now).toISOString()} Fehlgeschlagene Authentifizierung von ${ip}`);
}

function clientIp(req) {
  const socketIp = req.socket.remoteAddress || 'unbekannt';
  if (!TRUST_PROXY) return socketIp;
  const h = req.headers['x-forwarded-for'];
  const v = Array.isArray(h) ? h.join(',') : h;
  if (!v) return socketIp;
  const parts = v.split(',').map((s) => s.trim()).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : socketIp;
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let tooLarge = false;
    req.on('data', (c) => {
      total += c.length;
      if (total > limit) {
        tooLarge = true;
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => (tooLarge ? reject(new Error('TOO_LARGE')) : resolve(Buffer.concat(chunks))));
    req.on('error', reject);
    req.on('close', () => tooLarge && reject(new Error('TOO_LARGE')));
  });
}

async function writeAtomic(file, text) {
  const tmp = `${file}.${crypto.randomBytes(6).toString('hex')}.tmp`;
  await fsp.writeFile(tmp, text, 'utf8');
  await fsp.rename(tmp, file);
}

// ---------------------------------------------------------------------------
// Prüfung eines Auftrags (dieselben Regeln wie ServerWorldRequest.parse in :core)
// ---------------------------------------------------------------------------

const QUALITIES = new Set(['LOW', 'MEDIUM', 'HIGH']);
// Belagsklassen: beim Start vom Weltbau erfragt (`server-info`), dies ist nur die Vorgabe.
const SURFACES = new Set(['ASPHALT', 'CONCRETE', 'PAVING_STONES', 'COBBLESTONE', 'WOOD', 'COMPACTED', 'GRAVEL', 'DIRT', 'GRASS', 'SAND', 'UNKNOWN']);
const SOURCES = new Set(['BROUTER_WAY_TAGS', 'BROUTER_REMATCH', 'VECTOR_TILES', 'NONE']);
const BUILDER_ID = /^[a-z0-9-]{1,40}\/[0-9]{1,6}$/;
const WORLD_ID = /^w[0-9a-f]{24}$/;
const HIGHWAY = /^[a-z_]{1,40}$/;
const JOB_ID = /^[a-f0-9]{32}$/;

const fin = (v) => typeof v === 'number' && Number.isFinite(v);

/** Liefert `{ok:true, request}` (bereinigt) oder `{ok:false, code, detail}`. */
export function validateRequest(o, limits = LIMITS) {
  const bad = (detail, code = 'INVALID') => ({ ok: false, code, detail });
  if (!o || typeof o !== 'object' || Array.isArray(o)) return bad('kein Objekt');
  if (o.format !== 1) return bad('Format unbekannt');
  if (typeof o.builderId !== 'string' || !BUILDER_ID.test(o.builderId)) return bad('builderId unzulässig');
  if (!QUALITIES.has(o.quality)) return bad('quality unbekannt');
  if (o.laser !== undefined && typeof o.laser !== 'boolean') return bad('laser kein Wahrheitswert');
  if (o.expectedWorldId !== undefined && (typeof o.expectedWorldId !== 'string' || !WORLD_ID.test(o.expectedWorldId))) {
    return bad('expectedWorldId unzulässig');
  }
  const r = o.route;
  if (!r || typeof r !== 'object' || Array.isArray(r)) return bad('route fehlt');
  if (r.name !== undefined && typeof r.name !== 'string') return bad('name keine Zeichenkette');
  if (typeof r.isLoop !== 'boolean') return bad('isLoop fehlt');
  if (!SOURCES.has(r.source)) return bad('source unbekannt');
  if (!Array.isArray(r.points) || r.points.length < 2) return bad('points fehlt');
  if (r.points.length > limits.maxPoints) return bad(`${r.points.length} Punkte (höchstens ${limits.maxPoints})`, 'TOO_MANY_POINTS');
  let prev = -1;
  for (let i = 0; i < r.points.length; i++) {
    const p = r.points[i];
    if (!Array.isArray(p) || p.length !== 4 || !p.every(fin)) return bad(`Punkt ${i} unlesbar`);
    const [d, lat, lon, ele] = p;
    if (lat < -85 || lat > 85 || lon < -180 || lon > 180) return bad(`Punkt ${i} außerhalb`);
    if (ele < -500 || ele > 9000) return bad(`Punkt ${i}: Höhe`);
    if (i === 0 && d !== 0) return bad('erster Punkt nicht bei 0 m');
    if (d < prev) return bad(`Laufmeter fallen bei Punkt ${i}`);
    prev = d;
  }
  const length = prev;
  if (length < 100) return bad('Strecke kürzer als 100 m');
  if (length > limits.maxLengthM) return bad(`${Math.round(length)} m (höchstens ${limits.maxLengthM} m)`, 'TOO_LONG');
  if (!Array.isArray(r.surfaces) || r.surfaces.length === 0 || r.surfaces.length > limits.maxSurfaces) return bad('surfaces');
  let expectFrom = 0;
  for (let i = 0; i < r.surfaces.length; i++) {
    const s = r.surfaces[i];
    if (!s || typeof s !== 'object' || !fin(s.fromM) || !fin(s.toM) || !SURFACES.has(s.kind)) return bad(`Belag ${i} unlesbar`);
    if (s.highway !== undefined && s.highway !== null && (typeof s.highway !== 'string' || !HIGHWAY.test(s.highway))) return bad(`Belag ${i}: highway`);
    if (s.widthM !== undefined && s.widthM !== null && (!fin(s.widthM) || s.widthM <= 0 || s.widthM > 60)) return bad(`Belag ${i}: widthM`);
    if (s.toM < s.fromM || Math.abs(s.fromM - expectFrom) > 0.5) return bad(`Belag ${i} schließt nicht an`);
    expectFrom = s.toM;
  }
  if (Math.abs(expectFrom - length) > 1) return bad('Belag endet nicht am Streckenende');
  // Nur bekannte Felder weiterreichen (nichts Fremdes landet auf der Platte).
  const name = (r.name || '').replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]/g, '').trim().slice(0, 120);
  const request = {
    format: 1,
    builderId: o.builderId,
    quality: o.quality,
    laser: o.laser === true,
    ...(o.expectedWorldId ? { expectedWorldId: o.expectedWorldId } : {}),
    route: {
      name,
      isLoop: r.isLoop,
      source: r.source,
      points: r.points.map((p) => [p[0], p[1], p[2], p[3]]),
      surfaces: r.surfaces.map((s) => ({
        fromM: s.fromM,
        toM: s.toM,
        kind: s.kind,
        ...(typeof s.highway === 'string' ? { highway: s.highway } : {}),
        ...(fin(s.widthM) ? { widthM: s.widthM } : {}),
      })),
    },
  };
  return { ok: true, request };
}

/** Gleiche Strecke + gleiche Version + gleiche Stufe → gleicher Schlüssel (Paket wiederverwenden). */
function requestKey(req) {
  const route = { ...req.route, name: '' };
  return crypto
    .createHash('sha256')
    .update(JSON.stringify([req.builderId, req.quality, req.laser, route]))
    .digest('hex');
}

// ---------------------------------------------------------------------------
// Aufträge
// ---------------------------------------------------------------------------

/** id -> Auftrag (Inhalt von job.json) */
const jobs = new Map();
let builderId = null;
let laserRegions = [];
let running = null; // { id, child, timer }
let stopping = false;

const jobDir = (id) => path.join(JOBS_DIR, id);

async function saveJob(job) {
  job.updatedAtMs = Date.now();
  await writeAtomic(path.join(jobDir(job.id), 'job.json'), JSON.stringify(job));
}

function queue() {
  return [...jobs.values()].filter((j) => j.state === 'QUEUED').sort((a, b) => a.createdAtMs - b.createdAtMs);
}

function publicJob(job) {
  const q = queue();
  const out = {
    id: job.id,
    state: job.state,
    createdAtMs: job.createdAtMs,
    updatedAtMs: job.updatedAtMs,
    queuePosition: job.state === 'QUEUED' ? q.findIndex((j) => j.id === job.id) + 1 + (running ? 1 : 0) : 0,
  };
  if (job.state === 'RUNNING') Object.assign(out, { phase: job.phase, done: job.done, total: job.total, fraction: job.fraction });
  if (job.laserRegion) out.laserRegion = job.laserRegion;
  if (job.state === 'DONE') {
    Object.assign(out, {
      worldId: job.worldId,
      packageBytes: job.packageBytes,
      sha256: job.sha256,
      laserSource: job.laserSource || null,
      buildSeconds: job.buildSeconds,
      fraction: 1,
    });
  }
  if (job.state === 'FAILED') out.error = job.error;
  return out;
}

async function loadJobs() {
  await fsp.mkdir(JOBS_DIR, { recursive: true });
  for (const name of await fsp.readdir(JOBS_DIR)) {
    if (!JOB_ID.test(name)) continue;
    try {
      const job = JSON.parse(await fsp.readFile(path.join(jobDir(name), 'job.json'), 'utf8'));
      if (job.id !== name) continue;
      // Ein Bau, der beim Beenden lief, kommt wieder in die Warteschlange (der Weltbau setzt fort).
      if (job.state === 'RUNNING') {
        job.state = 'QUEUED';
        await saveJob(job);
      }
      jobs.set(job.id, job);
    } catch {
      // Unvollständiger Auftrag (z. B. Absturz beim Anlegen): weg damit.
      await fsp.rm(jobDir(name), { recursive: true, force: true });
    }
  }
}

const WEIGHTS = { ELEVATION: 0.08, VECTOR: 0.12, AERIAL: 0.3, MESHING: 0.25, WRITING: 0.25 };
const ORDER = ['ELEVATION', 'VECTOR', 'AERIAL', 'MESHING', 'WRITING'];

function applyProgress(job, p) {
  if (!ORDER.includes(p.phase)) return;
  job.progress = job.progress || {};
  const idx = ORDER.indexOf(p.phase);
  job.progress[p.phase] = p.total > 0 ? Math.min(1, p.done / p.total) : 0;
  for (let i = 0; i < Math.min(idx, 2); i++) job.progress[ORDER[i]] = 1;
  let sum = 0;
  for (const ph of ORDER) sum += WEIGHTS[ph] * (job.progress[ph] || 0);
  job.fraction = Math.max(job.fraction || 0, Math.min(0.99, sum));
  job.phase = ORDER.find((ph) => (job.progress[ph] ?? 1) < 1 && job.progress[ph] !== undefined) || p.phase;
  job.done = p.done;
  job.total = p.total;
}

async function startNext() {
  if (running || !builderId) return;
  const job = queue()[0];
  if (!job) {
    await cleanupCache().catch((e) => console.error('Aufräumen des Zwischenspeichers:', e));
    return;
  }
  job.state = 'RUNNING';
  job.startedAtMs = Date.now();
  job.fraction = 0;
  job.attempts = (job.attempts || 0) + 1;
  await saveJob(job);
  const dir = jobDir(job.id);
  const args = [
    ...BUILDER.slice(1),
    'server-build',
    '--job', dir,
    '--cache', CACHE_DIR,
    '--max-km', String(LIMITS.maxLengthM / 1000),
    '--max-points', String(LIMITS.maxPoints),
  ];
  console.log(`${new Date().toISOString()} Bau ${job.id} startet (Versuch ${job.attempts})`);
  const log = fs.createWriteStream(path.join(dir, 'build.log'), { flags: 'a' });
  const child = spawn(BUILDER[0], args, { stdio: ['ignore', 'pipe', 'pipe'], env: process.env });
  const timer = setTimeout(() => {
    job.timedOut = true;
    child.kill('SIGTERM');
    setTimeout(() => child.kill('SIGKILL'), 10000).unref();
  }, BUILD_TIMEOUT_MS);
  running = { id: job.id, child, timer };
  let buf = '';
  let lastSave = 0;
  child.stdout.on('data', (chunk) => {
    log.write(chunk);
    buf += chunk.toString('utf8');
    let nl;
    while ((nl = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      try {
        if (line.startsWith('@progress ')) applyProgress(job, JSON.parse(line.slice(10)));
        else if (line.startsWith('@plan ')) {
          const p = JSON.parse(line.slice(6));
          if (p.laser && typeof p.laserRegion === 'string') job.laserRegion = p.laserRegion;
        }
      } catch {
        // fremde Zeile, egal
      }
    }
    if (buf.length > 65536) buf = '';
    if (Date.now() - lastSave > 5000) {
      lastSave = Date.now();
      saveJob(job).catch(() => {});
    }
  });
  child.stderr.on('data', (c) => log.write(c));
  child.on('error', (err) => console.error('Weltbau nicht startbar:', err.message));
  child.on('close', async (code, signal) => {
    clearTimeout(timer);
    log.end();
    running = null;
    if (!jobs.has(job.id)) {
      // Während des Baus gelöscht.
      await fsp.rm(dir, { recursive: true, force: true });
      setImmediate(() => startNext().catch(console.error));
      return;
    }
    if (stopping) {
      // Dienst wird beendet: der Auftrag bleibt in der Warteschlange und setzt beim nächsten Start fort.
      job.state = 'QUEUED';
      await saveJob(job).catch(console.error);
      return;
    }
    let result = null;
    try {
      result = JSON.parse(await fsp.readFile(path.join(dir, 'result.json'), 'utf8'));
    } catch {
      result = null;
    }
    if (code === 0 && result && result.ok && fs.existsSync(path.join(dir, 'package.zip'))) {
      Object.assign(job, {
        state: 'DONE',
        worldId: result.worldId,
        packageBytes: result.packageBytes,
        sha256: result.sha256,
        laserSource: result.laserSource || null,
        buildSeconds: result.buildSeconds,
        finishedAtMs: Date.now(),
        lastAccessMs: Date.now(),
        error: undefined,
      });
      console.log(`${new Date().toISOString()} Bau ${job.id} fertig: ${result.packageBytes} B, ${result.buildSeconds} s`);
    } else {
      const err = job.timedOut
        ? { code: 'TIMEOUT', detail: `länger als ${BUILD_TIMEOUT_MS / 60000} Minuten` }
        : (result && result.error) || { code: 'BUILD_FAILED', detail: `Prozess endete mit ${code ?? signal}` };
      Object.assign(job, { state: 'FAILED', error: err, finishedAtMs: Date.now(), lastAccessMs: Date.now() });
      console.warn(`${new Date().toISOString()} Bau ${job.id} gescheitert: ${err.code}`);
      // Eine halb gebaute Welt braucht niemand mehr.
      await fsp.rm(path.join(dir, 'world'), { recursive: true, force: true });
    }
    delete job.timedOut;
    await saveJob(job).catch(console.error);
    await cleanupJobs().catch(console.error);
    setImmediate(() => startNext().catch(console.error));
  });
}

async function removeJob(job) {
  jobs.delete(job.id);
  if (running && running.id === job.id) {
    running.child.kill('SIGTERM');
    const child = running.child;
    setTimeout(() => child.kill('SIGKILL'), 10000).unref();
    return; // Verzeichnis räumt 'close' weg
  }
  await fsp.rm(jobDir(job.id), { recursive: true, force: true });
}

// ---------------------------------------------------------------------------
// Aufräumen
// ---------------------------------------------------------------------------

async function cleanupJobs() {
  const now = Date.now();
  const finished = [...jobs.values()].filter((j) => ['DONE', 'FAILED', 'CANCELLED'].includes(j.state));
  for (const j of finished) {
    if (now - (j.lastAccessMs || j.finishedAtMs || j.createdAtMs) > KEEP_DAYS * 86400000) {
      console.log(`Aufräumen: Auftrag ${j.id} (${j.state}) älter als ${KEEP_DAYS} Tage`);
      await removeJob(j);
    }
  }
  const done = [...jobs.values()].filter((j) => j.state === 'DONE').sort((a, b) => (a.lastAccessMs || 0) - (b.lastAccessMs || 0));
  let total = done.reduce((s, j) => s + (j.packageBytes || 0), 0);
  while (total > MAX_PACKAGES_BYTES && done.length > 1) {
    const j = done.shift();
    total -= j.packageBytes || 0;
    console.log(`Aufräumen: Paket ${j.id} (Platzgrenze ${MAX_PACKAGES_BYTES} B)`);
    await removeJob(j);
  }
}

/** Geodaten-Zwischenspeicher: Dateien älter als CACHE_DAYS weg, danach die ältesten bis unter 90 % der Grenze. */
export async function cleanupCache(dir = CACHE_DIR, maxBytes = CACHE_MAX_BYTES, maxDays = CACHE_DAYS) {
  if (running) return { removed: 0 };
  const files = [];
  async function walk(d) {
    let entries;
    try {
      entries = await fsp.readdir(d, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const p = path.join(d, e.name);
      if (e.isSymbolicLink()) continue;
      if (e.isDirectory()) await walk(p);
      else if (e.isFile()) {
        const st = await fsp.stat(p);
        files.push({ p, size: st.size, mtime: st.mtimeMs });
      }
    }
  }
  await walk(dir);
  const now = Date.now();
  let removed = 0;
  let total = 0;
  const keep = [];
  for (const f of files) {
    if (now - f.mtime > maxDays * 86400000) {
      await fsp.rm(f.p, { force: true });
      removed++;
    } else {
      keep.push(f);
      total += f.size;
    }
  }
  if (total > maxBytes) {
    keep.sort((a, b) => a.mtime - b.mtime);
    while (total > maxBytes * 0.9 && keep.length) {
      const f = keep.shift();
      await fsp.rm(f.p, { force: true });
      total -= f.size;
      removed++;
    }
  }
  if (removed) console.log(`Aufräumen: ${removed} Dateien aus dem Geodaten-Zwischenspeicher`);
  return { removed, bytes: total };
}

// ---------------------------------------------------------------------------
// Routen
// ---------------------------------------------------------------------------

async function handleCreate(req, res) {
  const declared = Number(req.headers['content-length'] || 0);
  if (declared > LIMITS.maxBodyBytes) {
    sendError(res, 413, 'TOO_LARGE', `Auftrag größer als ${LIMITS.maxBodyBytes} B`);
    req.resume();
    return;
  }
  let body;
  try {
    body = await readBody(req, LIMITS.maxBodyBytes);
  } catch (e) {
    sendError(res, 413, 'TOO_LARGE', `Auftrag größer als ${LIMITS.maxBodyBytes} B`);
    return;
  }
  let parsed;
  try {
    parsed = JSON.parse(body.toString('utf8'));
  } catch {
    sendError(res, 400, 'INVALID', 'kein JSON');
    return;
  }
  const v = validateRequest(parsed);
  if (!v.ok) {
    const status = v.code === 'TOO_LONG' || v.code === 'TOO_MANY_POINTS' ? 413 : 400;
    sendError(res, status, v.code, v.detail);
    return;
  }
  if (v.request.builderId !== builderId) {
    sendError(res, 409, 'BUILDER_MISMATCH', `Server baut ${builderId}, Auftrag verlangt ${v.request.builderId}`, { serverBuilderId: builderId });
    return;
  }
  const key = requestKey(v.request);
  const existing = [...jobs.values()].find((j) => j.key === key && j.state !== 'FAILED' && j.state !== 'CANCELLED');
  if (existing) {
    existing.lastAccessMs = Date.now();
    await saveJob(existing);
    sendJson(res, 200, publicJob(existing));
    return;
  }
  // Ein gescheiterter Auftrag derselben Strecke wird ersetzt (neuer Versuch).
  for (const j of [...jobs.values()].filter((x) => x.key === key)) await removeJob(j);
  const active = [...jobs.values()].filter((j) => j.state === 'QUEUED' || j.state === 'RUNNING').length;
  if (active >= MAX_JOBS) {
    sendError(res, 429, 'QUEUE_FULL', `schon ${active} Aufträge in Arbeit`);
    return;
  }
  const id = crypto.randomBytes(16).toString('hex');
  const dir = jobDir(id);
  await fsp.mkdir(dir, { recursive: true });
  await writeAtomic(path.join(dir, 'request.json'), JSON.stringify(v.request));
  const job = { id, key, state: 'QUEUED', createdAtMs: Date.now(), lastAccessMs: Date.now() };
  await saveJob(job);
  jobs.set(id, job);
  console.log(`${new Date().toISOString()} Auftrag ${id}: ${v.request.route.points.length} Punkte, ${Math.round(v.request.route.points.at(-1)[0])} m, laser=${v.request.laser}`);
  sendJson(res, 201, publicJob(job));
  setImmediate(() => startNext().catch(console.error));
}

const RANGE = /^bytes=(\d*)-(\d*)$/;

async function handlePackage(req, res, job) {
  if (job.state !== 'DONE') {
    sendError(res, 409, 'NOT_READY', `Auftrag ist ${job.state}`);
    return;
  }
  const file = path.join(jobDir(job.id), 'package.zip');
  let st;
  try {
    st = await fsp.stat(file);
  } catch {
    sendError(res, 410, 'GONE', 'Paket fehlt');
    return;
  }
  job.lastAccessMs = Date.now();
  saveJob(job).catch(() => {});
  const size = st.size;
  const headers = {
    'Content-Type': 'application/zip',
    'Accept-Ranges': 'bytes',
    ETag: `"${job.sha256}"`,
    'Cache-Control': 'no-store',
  };
  let start = 0;
  let end = size - 1;
  let status = 200;
  const range = req.headers['range'];
  const ifRange = req.headers['if-range'];
  if (range && (!ifRange || ifRange === headers.ETag)) {
    const m = RANGE.exec(range.trim());
    if (!m || (m[1] === '' && m[2] === '')) {
      res.writeHead(416, { 'Content-Range': `bytes */${size}` });
      res.end();
      return;
    }
    if (m[1] === '') {
      start = Math.max(0, size - Number(m[2]));
    } else {
      start = Number(m[1]);
      if (m[2] !== '') end = Math.min(size - 1, Number(m[2]));
    }
    if (start >= size || start > end) {
      res.writeHead(416, { 'Content-Range': `bytes */${size}` });
      res.end();
      return;
    }
    status = 206;
    headers['Content-Range'] = `bytes ${start}-${end}/${size}`;
  }
  headers['Content-Length'] = end - start + 1;
  res.writeHead(status, headers);
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  fs.createReadStream(file, { start, end }).on('error', () => res.destroy()).pipe(res);
}

async function handleRequest(req, res) {
  const method = req.method || 'GET';
  let pathname;
  try {
    pathname = new URL(req.url, 'http://localhost').pathname;
  } catch {
    sendError(res, 400, 'INVALID', 'Adresse unlesbar');
    return;
  }
  const ip = clientIp(req);
  const wait = retryAfter(ip);
  if (wait > 0) {
    sendJson(res, 429, { error: 'Zu viele fehlgeschlagene Anmeldeversuche – bitte später erneut versuchen' }, { 'Retry-After': String(wait) });
    return;
  }
  if (!isAuthorized(req)) {
    recordFailure(ip);
    sendJson(res, 401, { error: 'Nicht autorisiert' });
    return;
  }
  authFailures.delete(ip);

  if (pathname === '/api/worlds/info' && method === 'GET') {
    sendJson(res, 200, {
      service: 'trailscape-worlds',
      api: 1,
      builderId,
      laserSources: laserRegions,
      maxLengthM: LIMITS.maxLengthM,
      maxPoints: LIMITS.maxPoints,
      queueLength: queue().length + (running ? 1 : 0),
    });
    return;
  }
  if (pathname === '/api/worlds/jobs' && method === 'POST') {
    await handleCreate(req, res);
    return;
  }
  const m = /^\/api\/worlds\/jobs\/([^/]+)(\/package)?$/.exec(pathname);
  if (m) {
    const id = m[1];
    if (!JOB_ID.test(id)) {
      sendError(res, 400, 'INVALID', 'Auftragskennung unzulässig');
      return;
    }
    const job = jobs.get(id);
    if (!job) {
      sendError(res, 404, 'NOT_FOUND', 'Auftrag unbekannt');
      return;
    }
    if (m[2]) {
      if (method === 'GET' || method === 'HEAD') {
        await handlePackage(req, res, job);
        return;
      }
    } else if (method === 'GET') {
      sendJson(res, 200, publicJob(job));
      return;
    } else if (method === 'DELETE') {
      console.log(`${new Date().toISOString()} Auftrag ${id} gelöscht (${job.state})`);
      await removeJob(job);
      res.writeHead(204);
      res.end();
      setImmediate(() => startNext().catch(console.error));
      return;
    }
  }
  sendJson(res, 404, { error: 'Nicht gefunden' });
}

// ---------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------

function askBuilder() {
  return new Promise((resolve, reject) => {
    const child = spawn(BUILDER[0], [...BUILDER.slice(1), 'server-info'], { stdio: ['ignore', 'pipe', 'inherit'] });
    let out = '';
    const timer = setTimeout(() => child.kill('SIGKILL'), 120000);
    child.stdout.on('data', (c) => (out += c));
    child.on('error', reject);
    child.on('close', (code) => {
      clearTimeout(timer);
      const line = out.split('\n').map((s) => s.trim()).find((s) => s.startsWith('{'));
      try {
        const info = JSON.parse(line);
        if (code !== 0 || typeof info.builderId !== 'string') throw new Error(`Exit ${code}`);
        resolve(info);
      } catch (e) {
        reject(new Error(`server-info unlesbar (${e.message})`));
      }
    });
  });
}

async function main() {
  await fsp.mkdir(CACHE_DIR, { recursive: true });
  await loadJobs();
  const info = await askBuilder();
  builderId = info.builderId;
  laserRegions = Array.isArray(info.laserSources) ? info.laserSources : [];
  if (Array.isArray(info.surfaceKinds) && info.surfaceKinds.length) {
    SURFACES.clear();
    info.surfaceKinds.forEach((k) => SURFACES.add(k));
  }
  const server = http.createServer((req, res) => {
    handleRequest(req, res).catch((err) => {
      console.error('Unerwarteter Fehler:', err);
      if (!res.headersSent) sendJson(res, 500, { error: 'Interner Serverfehler' });
      else res.end();
    });
  });
  server.listen(PORT, () => {
    console.log(`Trailscape-Bau-Dienst auf Port ${PORT} – Welt-Version ${builderId}, Laser ${laserRegions.join(', ')}, Daten ${DATA_DIR}`);
  });
  const shutdown = () => {
    // Ein laufender Bau wird beendet und beim nächsten Start fortgesetzt.
    stopping = true;
    if (running) running.child.kill('SIGTERM');
    server.close();
    setTimeout(() => process.exit(0), 2000).unref();
  };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
  setInterval(() => {
    cleanupJobs().catch(console.error);
    if (!running) cleanupCache().catch(console.error);
  }, CLEANUP_EVERY_MS).unref();
  await cleanupJobs();
  startNext().catch(console.error);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((err) => {
    console.error('Bau-Dienst konnte nicht starten:', err.message);
    process.exit(1);
  });
}
