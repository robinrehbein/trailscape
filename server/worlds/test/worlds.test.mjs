// Tests des Bau-Dienstes mit der Weltbau-Attrappe: node --test server/worlds/test/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const TOKEN = 'test-token-0123456789';

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer().listen(0, () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}

async function start(script, env) {
  const port = await freePort();
  const child = spawn(process.execPath, [script], {
    env: { ...process.env, PORT: String(port), SYNC_TOKEN: TOKEN, ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let log = '';
  child.stdout.on('data', (c) => (log += c));
  child.stderr.on('data', (c) => (log += c));
  for (let i = 0; i < 100; i++) {
    if (/Port \d+/.test(log) || /läuft auf Port/.test(log)) break;
    await new Promise((r) => setTimeout(r, 100));
  }
  return { port, child, log: () => log, stop: () => new Promise((r) => { child.on('close', r); child.kill('SIGTERM'); }) };
}

function startWorlds(dataDir, extra = {}) {
  return start(path.join(here, '..', 'worlds.mjs'), {
    DATA_DIR: dataDir,
    BUILDER_CMD: JSON.stringify([process.execPath, path.join(here, 'fake-builder.mjs')]),
    MAX_ROUTE_KM: '50',
    ...extra,
  });
}

async function call(port, method, p, body, headers = {}) {
  const res = await fetch(`http://127.0.0.1:${port}${p}`, {
    method,
    headers: { Authorization: `Bearer ${TOKEN}`, ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: body ? (typeof body === 'string' ? body : JSON.stringify(body)) : undefined,
  });
  const buf = Buffer.from(await res.arrayBuffer());
  let json = null;
  try { json = JSON.parse(buf.toString('utf8')); } catch { /* binär */ }
  return { status: res.status, json, buf, headers: res.headers };
}

function request(km = 3, extra = {}) {
  const n = km * 100;
  const points = Array.from({ length: n + 1 }, (_, i) => [i * 10, 51.05 + i * 1e-6, 7.13 + i * 1.4e-4, 100]);
  return {
    format: 1,
    builderId: 'indoor-world/9',
    quality: 'MEDIUM',
    laser: true,
    route: { name: 'Probe', isLoop: false, source: 'NONE', points, surfaces: [{ fromM: 0, toM: n * 10, kind: 'UNKNOWN' }] },
    ...extra,
  };
}

async function waitFor(port, id, states, ms = 15000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    const r = await call(port, 'GET', `/api/worlds/jobs/${id}`);
    if (states.includes(r.json?.state)) return r.json;
    await new Promise((res) => setTimeout(res, 100));
  }
  throw new Error(`Zustand ${states} nicht erreicht`);
}

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'worlds-'));

test('Auftrag, Stand, Paket mit Range, Wiederverwendung, Löschen', async () => {
  const dir = tmp();
  const s = await startWorlds(dir);
  try {
    assert.equal((await call(s.port, 'GET', '/api/worlds/info', null, { Authorization: 'Bearer falsch-falsch-falsch' })).status, 401);
    const info = await call(s.port, 'GET', '/api/worlds/info');
    assert.equal(info.json.service, 'trailscape-worlds');
    assert.equal(info.json.builderId, 'indoor-world/9');
    assert.deepEqual(info.json.laserSources, ['nw', 'bw']);

    const created = await call(s.port, 'POST', '/api/worlds/jobs', request());
    assert.equal(created.status, 201);
    const id = created.json.id;
    assert.match(id, /^[a-f0-9]{32}$/);
    const done = await waitFor(s.port, id, ['DONE']);
    assert.equal(done.laserSource, 'nw');
    assert.equal(done.packageBytes, 131072);

    const full = await call(s.port, 'GET', `/api/worlds/jobs/${id}/package`);
    assert.equal(full.status, 200);
    assert.equal(crypto.createHash('sha256').update(full.buf).digest('hex'), done.sha256);
    const part = await call(s.port, 'GET', `/api/worlds/jobs/${id}/package`, null, { Range: 'bytes=100000-' });
    assert.equal(part.status, 206);
    assert.equal(part.headers.get('content-range'), 'bytes 100000-131071/131072');
    assert.deepEqual(part.buf, full.buf.subarray(100000));
    assert.equal((await call(s.port, 'GET', `/api/worlds/jobs/${id}/package`, null, { Range: 'bytes=999999-' })).status, 416);

    // Gleiche Strecke (anderer Name) → derselbe Auftrag, kein neuer Bau.
    const again = await call(s.port, 'POST', '/api/worlds/jobs', { ...request(), route: { ...request().route, name: 'Anders' } });
    assert.equal(again.status, 200);
    assert.equal(again.json.id, id);
    assert.equal(again.json.state, 'DONE');

    assert.equal((await call(s.port, 'DELETE', `/api/worlds/jobs/${id}`)).status, 204);
    assert.equal((await call(s.port, 'GET', `/api/worlds/jobs/${id}`)).status, 404);
    assert.equal(fs.existsSync(path.join(dir, 'jobs', id)), false);
  } finally {
    await s.stop();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('Eingaben werden streng geprüft', async () => {
  const dir = tmp();
  const s = await startWorlds(dir);
  try {
    const post = (b) => call(s.port, 'POST', '/api/worlds/jobs', b);
    assert.equal((await post('kein json')).json.error.code, 'INVALID');
    const long = await post(request(60));
    assert.equal(long.status, 413);
    assert.equal(long.json.error.code, 'TOO_LONG');
    const other = await post(request(3, { builderId: 'indoor-world/8' }));
    assert.equal(other.status, 409);
    assert.equal(other.json.error.code, 'BUILDER_MISMATCH');
    assert.equal(other.json.error.serverBuilderId, 'indoor-world/9');
    const r = request();
    r.route.surfaces[0].kind = 'LAVA';
    assert.equal((await post(r)).json.error.code, 'INVALID');
    const hw = request();
    hw.route.surfaces[0].highway = '$(rm -rf /)';
    assert.equal((await post(hw)).json.error.code, 'INVALID');
    const pts = request();
    pts.route.points[5] = [1, 2, 3];
    assert.equal((await post(pts)).json.error.code, 'INVALID');
    assert.equal((await call(s.port, 'GET', '/api/worlds/jobs/..%2F..%2Fetc')).status, 400);
    assert.equal((await call(s.port, 'GET', '/api/worlds/jobs/' + 'a'.repeat(32))).status, 404);
    // Fremde Felder landen nicht auf der Platte.
    const extra = request();
    extra.route.evil = 'x';
    extra.extra = '../../x';
    const ok = await post(extra);
    assert.equal(ok.status, 201);
    const saved = JSON.parse(fs.readFileSync(path.join(dir, 'jobs', ok.json.id, 'request.json'), 'utf8'));
    assert.equal(saved.extra, undefined);
    assert.equal(saved.route.evil, undefined);
    // Zu großer Körper
    assert.equal((await post('x'.repeat(5 * 1024 * 1024))).status, 413);
  } finally {
    await s.stop();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('Fehler, Warteschlange, Abbrechen und Neustart', async () => {
  const dir = tmp();
  let s = await startWorlds(dir, { FAKE_MODE: 'fail' });
  try {
    const failed = await call(s.port, 'POST', '/api/worlds/jobs', request());
    const f = await waitFor(s.port, failed.json.id, ['FAILED']);
    assert.equal(f.error.code, 'BUILD_FAILED');
    await s.stop();

    s = await startWorlds(dir, { FAKE_MODE: 'slow', MAX_JOBS: '2' });
    // Nach einem Fehler startet derselbe Auftrag neu (neue Kennung).
    const a = await call(s.port, 'POST', '/api/worlds/jobs', request());
    assert.equal(a.status, 201);
    assert.notEqual(a.json.id, failed.json.id);
    const b = await call(s.port, 'POST', '/api/worlds/jobs', request(4));
    assert.equal(b.json.state, 'QUEUED');
    assert.equal(b.json.queuePosition, 2);
    const full = await call(s.port, 'POST', '/api/worlds/jobs', request(5));
    assert.equal(full.status, 429);
    assert.equal(full.json.error.code, 'QUEUE_FULL');
    await waitFor(s.port, a.json.id, ['RUNNING']);
    // Abbrechen während des Baus
    assert.equal((await call(s.port, 'DELETE', `/api/worlds/jobs/${a.json.id}`)).status, 204);
    await waitFor(s.port, b.json.id, ['RUNNING']);
    // Neustart während b läuft: b setzt danach fort.
    await s.stop();
    s = await startWorlds(dir);
    const back = await waitFor(s.port, b.json.id, ['DONE']);
    assert.equal(back.state, 'DONE');
    assert.equal(fs.existsSync(path.join(dir, 'jobs', a.json.id)), false);
  } finally {
    await s.stop();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('Sync-Server reicht /api/worlds durch', async () => {
  const dir = tmp();
  const syncDir = tmp();
  const w = await startWorlds(dir);
  const sync = await start(path.join(here, '..', '..', 'server.mjs'), { DATA_DIR: syncDir, WORLDS_URL: `http://127.0.0.1:${w.port}` });
  const plain = await start(path.join(here, '..', '..', 'server.mjs'), { DATA_DIR: syncDir });
  try {
    assert.equal((await call(plain.port, 'GET', '/api/worlds/info')).status, 404);
    assert.equal((await call(sync.port, 'GET', '/api/worlds/info', null, { Authorization: 'Bearer falsch-falsch-falsch' })).status, 401);
    const info = await call(sync.port, 'GET', '/api/worlds/info');
    assert.equal(info.json.service, 'trailscape-worlds');
    const c = await call(sync.port, 'POST', '/api/worlds/jobs', request());
    assert.equal(c.status, 201);
    const done = await waitFor(sync.port, c.json.id, ['DONE']);
    const part = await call(sync.port, 'GET', `/api/worlds/jobs/${c.json.id}/package`, null, { Range: 'bytes=131000-' });
    assert.equal(part.status, 206);
    assert.equal(part.buf.length, 72);
    assert.equal(part.headers.get('etag'), `"${done.sha256}"`);
    // Sync selbst geht weiter.
    assert.equal((await call(sync.port, 'GET', '/api/rides')).status, 200);
  } finally {
    await Promise.all([w.stop(), sync.stop(), plain.stop()]);
    fs.rmSync(dir, { recursive: true, force: true });
    fs.rmSync(syncDir, { recursive: true, force: true });
  }
});
