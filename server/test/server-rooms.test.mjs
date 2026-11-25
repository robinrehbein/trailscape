// Räume im echten Serverprozess: REST, WebSocket (roh und mit dem in Node
// eingebauten Client), Berechtigungen, Grenzen, Protokoll ohne Geheimnisse.
//   node --test server/test/        (Node >= 22 wegen des eingebauten WebSocket)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { encodeFrame, OP_TEXT, OP_CLOSE, OP_PING, OP_BINARY, OP_CONT } from '../websocket.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(here, '..', 'server.mjs');
const TOKEN = 'test-token-0123456789-raum';
const ROUTE = { id: 'katalog/probe', lengthM: 5000, loop: false };

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer().listen(0, '127.0.0.1', () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}

async function startServer(env = {}) {
  const port = await freePort();
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'trailscape-raum-'));
  const child = spawn(process.execPath, [SERVER], {
    env: { ...process.env, PORT: String(port), SYNC_TOKEN: TOKEN, DATA_DIR: dataDir, ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let log = '';
  child.stdout.on('data', (c) => (log += c));
  child.stderr.on('data', (c) => (log += c));
  for (let i = 0; i < 100 && !/läuft auf Port/.test(log); i++) await sleep(50);
  return {
    port,
    dataDir,
    log: () => log,
    stop: () =>
      new Promise((r) => {
        child.on('close', r);
        child.kill('SIGTERM');
      }),
  };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function http(port, method, p, { body, token = TOKEN, headers = {} } = {}) {
  const res = await fetch(`http://127.0.0.1:${port}${p}`, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* kein JSON */
  }
  return { status: res.status, json, headers: res.headers };
}

/** Client mit dem eingebauten WebSocket von Node (unabhängige Umsetzung des RFC). */
function wsClient(port) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/api/rooms/ws`);
  const inbox = [];
  const waiters = [];
  let closeInfo = null;
  const closeWaiters = [];
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    inbox.push(m);
    for (const w of [...waiters]) {
      if (w.pred(m)) {
        waiters.splice(waiters.indexOf(w), 1);
        w.resolve(m);
      }
    }
  });
  ws.addEventListener('close', (e) => {
    closeInfo = { code: e.code, reason: e.reason };
    for (const w of closeWaiters) w(closeInfo);
  });
  const opened = new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', reject, { once: true });
  });
  return {
    ws,
    inbox,
    opened,
    send: (obj) => ws.send(typeof obj === 'string' ? obj : JSON.stringify(obj)),
    next(pred, ms = 3000) {
      const found = inbox.find(pred);
      if (found) {
        inbox.splice(inbox.indexOf(found), 1);
        return Promise.resolve(found);
      }
      return new Promise((resolve, reject) => {
        const w = { pred, resolve };
        waiters.push(w);
        setTimeout(() => reject(new Error('Zeitüberschreitung beim Warten auf Nachricht')), ms).unref();
      }).then((m) => {
        inbox.splice(inbox.indexOf(m), 1);
        return m;
      });
    },
    closed(ms = 4000) {
      if (closeInfo) return Promise.resolve(closeInfo);
      return new Promise((resolve, reject) => {
        closeWaiters.push(resolve);
        setTimeout(() => reject(new Error('nicht geschlossen')), ms).unref();
      });
    },
  };
}

async function joinRoom(port, created, extra = {}) {
  const c = wsClient(port);
  await c.opened;
  c.send({ type: 'join', v: 1, room: created.room.id, secret: created.secret, name: 'Fahrer', ...extra });
  const welcome = await c.next((m) => m.type === 'welcome' || m.type === 'error');
  return { c, welcome };
}

/** Roher Client: Handshake von Hand, Rahmen von Hand (für kaputte Rahmen). */
async function rawClient(port, { path: p = '/api/rooms/ws', headers = {} } = {}) {
  const sock = net.connect(port, '127.0.0.1');
  await new Promise((r) => sock.once('connect', r));
  const key = crypto.randomBytes(16).toString('base64');
  const lines = [
    `GET ${p} HTTP/1.1`,
    `Host: 127.0.0.1:${port}`,
    'Upgrade: websocket',
    'Connection: Upgrade',
    `Sec-WebSocket-Key: ${key}`,
    'Sec-WebSocket-Version: 13',
    ...Object.entries(headers).map(([k, v]) => `${k}: ${v}`),
  ];
  sock.write(lines.join('\r\n') + '\r\n\r\n');
  let buf = Buffer.alloc(0);
  let ended = false;
  sock.on('data', (c) => (buf = Buffer.concat([buf, c])));
  sock.on('close', () => (ended = true));
  for (let i = 0; i < 100 && buf.indexOf('\r\n\r\n') < 0 && !ended; i++) await sleep(20);
  const headEnd = buf.indexOf('\r\n\r\n');
  const head = buf.subarray(0, headEnd).toString();
  buf = buf.subarray(headEnd + 4);
  const status = Number(/^HTTP\/1\.1 (\d+)/.exec(head)?.[1]);
  const mask = () => crypto.randomBytes(4);
  return {
    status,
    head,
    sock,
    sendFrame: (op, payload, opts = {}) => sock.write(encodeFrame(op, payload, { mask: mask(), ...opts })),
    sendRaw: (b) => sock.write(b),
    /** Liest Server-Rahmen (unmaskiert) bis zum Schließen oder Zeitablauf. */
    async frames(ms = 1500) {
      const until = Date.now() + ms;
      while (!ended && Date.now() < until) await sleep(20);
      const out = [];
      let b = buf;
      while (b.length >= 2) {
        let len = b[1] & 0x7f;
        let off = 2;
        if (len === 126) {
          len = b.readUInt16BE(2);
          off = 4;
        } else if (len === 127) {
          len = Number(b.readBigUInt64BE(2));
          off = 10;
        }
        if (b.length < off + len) break;
        out.push({ opcode: b[0] & 0x0f, masked: (b[1] & 0x80) !== 0, payload: b.subarray(off, off + len) });
        b = b.subarray(off + len);
      }
      return { frames: out, ended };
    },
  };
}

function closeCodeOf(frames) {
  const f = frames.find((x) => x.opcode === OP_CLOSE);
  return f && f.payload.length >= 2 ? f.payload.readUInt16BE(0) : null;
}

let srv;
before(async () => {
  srv = await startServer({ ROOMS: '1', ROOMS_MAX: '5', ROOM_MAX_RIDERS: '3', ROOM_REJOIN_GRACE_S: '1' });
});
after(async () => {
  await srv.stop();
  // Nichts auf der Platte: Das Datenverzeichnis bleibt leer.
  assert.deepEqual(fs.readdirSync(srv.dataDir), []);
});

test('Raum anlegen nur mit SYNC_TOKEN', async () => {
  assert.equal((await http(srv.port, 'POST', '/api/rooms', { body: { route: ROUTE }, token: null })).status, 401);
  assert.equal((await http(srv.port, 'POST', '/api/rooms', { body: { route: ROUTE }, token: 'falsch-falsch-falsch' })).status, 401);
  const ok = await http(srv.port, 'POST', '/api/rooms', { body: { route: ROUTE } });
  assert.equal(ok.status, 201);
  assert.equal(ok.headers.get('cache-control'), 'no-store');
  assert.ok(ok.json.secret && ok.json.hostSecret && ok.json.room.id);
  assert.equal((await http(srv.port, 'POST', '/api/rooms', { body: '{kaputt' })).status, 400);
  assert.equal((await http(srv.port, 'POST', '/api/rooms', { body: { route: { id: 'x' } } })).status, 400);
  assert.equal((await http(srv.port, 'POST', '/api/rooms', { body: { route: ROUTE, pad: 'x'.repeat(5000) } })).status, 413);
  const list = await http(srv.port, 'GET', '/api/rooms');
  assert.equal(list.status, 200);
  assert.ok(!JSON.stringify(list.json).includes(ok.json.secret));
  assert.equal((await http(srv.port, 'DELETE', `/api/rooms/${ok.json.room.id}`)).status, 204);
  assert.equal((await http(srv.port, 'DELETE', `/api/rooms/${ok.json.room.id}`)).status, 404);
});

test('Gäste mit Raum-Geheimnis kommen an keinen anderen Endpunkt', async () => {
  // Eigener Server: Die vielen 401 hier lösen die Sperre nach Fehlversuchen aus.
  const own = await startServer({ ROOMS: '1' });
  const srv = own;
  const room = (await http(srv.port, 'POST', '/api/rooms', { body: { route: ROUTE } })).json;
  for (const secret of [room.secret, room.hostSecret]) {
    for (const [m, p] of [
      ['GET', '/api/rides'],
      ['GET', '/api/rides/abc'],
      ['PUT', '/api/rides/abc'],
      ['DELETE', '/api/rides/abc'],
      ['GET', '/api/rooms'],
      ['POST', '/api/rooms'],
      ['DELETE', `/api/rooms/${room.room.id}`],
      ['GET', '/api/worlds/info'],
    ]) {
      const r = await http(srv.port, m, p, { token: secret, body: m === 'PUT' || m === 'POST' ? { route: ROUTE } : undefined });
      // 401, nach zehn Fehlversuchen 429 (Sperre) — beides ist eine Abweisung.
      assert.ok(r.status === 401 || r.status === 429, `${m} ${p}: ${r.status}`);
    }
  }
  // Der WebSocket-Pfad ohne Upgrade braucht keinen Token, verrät aber nichts.
  const plain = await http(srv.port, 'GET', '/api/rooms/ws', { token: null });
  assert.equal(plain.status, 426);
  // WebSocket auf anderen Pfaden gibt es nicht.
  for (const p of ['/api/rides', '/api/rooms', '/api/rooms/ws/x', '/']) {
    const raw = await rawClient(srv.port, { path: p });
    assert.equal(raw.status, 404, p);
    raw.sock.destroy();
  }
  await http(srv.port, 'DELETE', `/api/rooms/${room.room.id}`);
  // Die vielen 401 oben dürfen die eigene App nicht aussperren: Sperre greift
  // erst nach 10 Fehlversuchen; ein richtiger Token setzt sie zurück. Hier
  // wurden 16 verbraucht — deshalb kommt jetzt 429.
  const blocked = await http(srv.port, 'GET', '/api/rooms');
  assert.equal(blocked.status, 429);
  await own.stop();
});

test('zwei Fahrer: welcome, Zustand, snapshot, Uhrenabgleich, Countdown, Ende', async () => {
  const s = await startServer({ ROOMS: '1' });
  try {
    const room = (await http(s.port, 'POST', '/api/rooms', { body: { route: ROUTE } })).json;
    const host = await joinRoom(s.port, room, { secret: room.hostSecret, name: 'Gastgeberin', avatar: { jersey: 3 } });
    assert.equal(host.welcome.type, 'welcome');
    assert.equal(host.welcome.you.host, true);
    const guest = await joinRoom(s.port, room, { name: 'Gast', sharePower: false });
    assert.equal(guest.welcome.riders.length, 2);
    const seen = await host.c.next((m) => m.type === 'rider');
    assert.equal(seen.rider.name, 'Gast');

    guest.c.send({ type: 'state', distM: 12.5, speedMps: 7.5, powerW: 333, cadenceRpm: 90 });
    const snap = await host.c.next((m) => m.type === 'snapshot');
    assert.equal(snap.states[0].distM, 12.5);
    assert.equal(snap.states[0].powerW, undefined, 'Leistung abgeschaltet');

    const t0 = Date.now();
    guest.c.send({ type: 'ping', c: t0 });
    const pong = await guest.c.next((m) => m.type === 'pong');
    assert.equal(pong.c, t0);
    assert.ok(Math.abs(pong.s - Date.now()) < 2000);

    host.c.send({ type: 'start', inS: 5 });
    const cd = await guest.c.next((m) => m.type === 'countdown');
    assert.ok(cd.startAt > cd.serverTime);

    host.c.send({ type: 'end' });
    const ended = await guest.c.next((m) => m.type === 'ended');
    assert.equal(ended.reason, 'host');
    assert.equal((await guest.c.closed()).code, 4004);
  } finally {
    await s.stop();
  }
});

test('falsches Geheimnis, voller Raum, Version', async () => {
  const room = (await http(srv.port, 'POST', '/api/rooms', { body: { route: ROUTE } })).json;
  const wrong = await joinRoom(srv.port, room, { secret: 'nein' });
  assert.equal(wrong.welcome.code, 'JOIN_DENIED');
  assert.equal((await wrong.c.closed()).code, 4001);

  const v = await joinRoom(srv.port, room, { v: 99 });
  assert.equal(v.welcome.code, 'VERSION');
  assert.equal((await v.c.closed()).code, 4003);

  const riders = [];
  for (let i = 0; i < 3; i++) riders.push(await joinRoom(srv.port, room, { name: `F${i}` }));
  const full = await joinRoom(srv.port, room, { name: 'zu viel' });
  assert.equal(full.welcome.code, 'ROOM_FULL');
  assert.equal((await full.c.closed()).code, 4002);
  for (const r of riders) r.c.ws.close();
  await http(srv.port, 'DELETE', `/api/rooms/${room.room.id}`);
});

test('Abbruch und Wiederbeitritt', async () => {
  const room = (await http(srv.port, 'POST', '/api/rooms', { body: { route: ROUTE } })).json;
  const a = await joinRoom(srv.port, room, { name: 'A' });
  const b = await joinRoom(srv.port, room, { name: 'B' });
  // A reißt hart ab (kein Close-Rahmen) und kommt mit rejoin zurück.
  a.c.ws.close(); // sauber; der harte Abbruch kommt unten mit dem rohen Client
  await sleep(100);
  const back = await joinRoom(srv.port, room, { name: 'A', rejoin: { id: a.welcome.you.id, key: a.welcome.you.rejoinKey } });
  assert.equal(back.welcome.you.id, a.welcome.you.id);
  // Roh verbunden, beigetreten, dann Socket kappen: B bekommt nach der Frist (1 s) left/timeout.
  const raw = await rawClient(srv.port);
  raw.sendFrame(OP_TEXT, JSON.stringify({ type: 'join', v: 1, room: room.room.id, secret: room.secret, name: 'Roh' }));
  const joined = await b.c.next((m) => m.type === 'rider' && m.rider.name === 'Roh');
  raw.sock.destroy();
  const left = await b.c.next((m) => m.type === 'left', 5000);
  assert.deepEqual(left, { type: 'left', id: joined.rider.id, reason: 'timeout' });
  back.c.ws.close();
  b.c.ws.close();
  await http(srv.port, 'DELETE', `/api/rooms/${room.room.id}`);
});

test('kaputte Rahmen und Nachrichten', async () => {
  const room = (await http(srv.port, 'POST', '/api/rooms', { body: { route: ROUTE } })).json;
  const joinMsg = JSON.stringify({ type: 'join', v: 1, room: room.room.id, secret: room.secret, name: 'X' });

  // Unmaskierter Client-Rahmen → 1002.
  let raw = await rawClient(srv.port);
  raw.sendRaw(encodeFrame(OP_TEXT, joinMsg));
  assert.equal(closeCodeOf((await raw.frames()).frames), 1002);

  // RSV-Bit gesetzt → 1002.
  raw = await rawClient(srv.port);
  raw.sendFrame(OP_TEXT, joinMsg, { rsv: 4 });
  assert.equal(closeCodeOf((await raw.frames()).frames), 1002);

  // Übergroß angekündigt (1 GB) → 1009, ohne dass der Server puffert.
  raw = await rawClient(srv.port);
  const head = Buffer.from([0x81, 0x80 | 127, 0, 0, 0, 0, 0x40, 0, 0, 0, 1, 2, 3, 4]);
  raw.sendRaw(head);
  assert.equal(closeCodeOf((await raw.frames()).frames), 1009);

  // Übergroß in Fragmenten → 1009.
  raw = await rawClient(srv.port);
  raw.sendFrame(OP_TEXT, 'x'.repeat(800), { fin: false });
  raw.sendFrame(OP_CONT, 'x'.repeat(800), { fin: true });
  assert.equal(closeCodeOf((await raw.frames()).frames), 1009);

  // Binär → 1003.
  raw = await rawClient(srv.port);
  raw.sendFrame(OP_BINARY, Buffer.from([1, 2, 3]));
  assert.equal(closeCodeOf((await raw.frames()).frames), 1003);

  // Ungültiges UTF-8 → 1007.
  raw = await rawClient(srv.port);
  raw.sendFrame(OP_TEXT, Buffer.from([0xc3, 0x28]));
  assert.equal(closeCodeOf((await raw.frames()).frames), 1007);

  // Kein JSON vor dem join → 4011.
  raw = await rawClient(srv.port);
  raw.sendFrame(OP_TEXT, 'hallo');
  assert.equal(closeCodeOf((await raw.frames()).frames), 4011);

  // Fragmentiert, aber korrekt → welcome; Ping wird mit Pong beantwortet.
  raw = await rawClient(srv.port);
  raw.sendFrame(OP_TEXT, joinMsg.slice(0, 10), { fin: false });
  raw.sendFrame(OP_CONT, joinMsg.slice(10), { fin: true });
  raw.sendFrame(OP_PING, 'hallo');
  const { frames } = await raw.frames(600);
  const texts = frames.filter((f) => f.opcode === OP_TEXT).map((f) => JSON.parse(f.payload.toString()));
  assert.equal(texts[0].type, 'welcome');
  assert.ok(frames.some((f) => f.opcode === 0xa && f.payload.toString() === 'hallo'));
  assert.ok(frames.every((f) => !f.masked), 'Server maskiert nicht');
  raw.sock.destroy();

  // Kein join in 5 s → 4006 (dauert etwas).
  raw = await rawClient(srv.port);
  assert.equal(closeCodeOf((await raw.frames(7000)).frames), 4006);

  // Handshake ohne passende Version → 426.
  const bad = await rawClient(srv.port, { headers: { 'Sec-WebSocket-Version': '8' } });
  bad.sock.destroy();
  // Node hängt zweite Kopfzeile an; der Server sieht "13, 8" und lehnt ab.
  assert.equal(bad.status, 426);
  await http(srv.port, 'DELETE', `/api/rooms/${room.room.id}`);
});

test('zu schnelle Nachrichten werden verworfen, Flut trennt', async () => {
  const s = await startServer({ ROOMS: '1' });
  try {
    const room = (await http(s.port, 'POST', '/api/rooms', { body: { route: ROUTE } })).json;
    const raw = await rawClient(s.port);
    raw.sendFrame(OP_TEXT, JSON.stringify({ type: 'join', v: 1, room: room.room.id, secret: room.secret, name: 'Flut' }));
    await sleep(100);
    for (let i = 0; i < 200; i++) raw.sendFrame(OP_TEXT, JSON.stringify({ type: 'ping', c: i }));
    const { frames } = await raw.frames(1500);
    const texts = frames.filter((f) => f.opcode === OP_TEXT).map((f) => JSON.parse(f.payload.toString()));
    assert.ok(texts.some((m) => m.type === 'error' && m.code === 'RATE'));
    assert.ok(texts.filter((m) => m.type === 'pong').length <= 16);
    assert.equal(closeCodeOf(frames), 4005);
  } finally {
    await s.stop();
  }
});

test('Höchstzahl der Räume', async () => {
  const s = await startServer({ ROOMS: '1', ROOMS_MAX: '3' });
  try {
    for (let i = 0; i < 3; i++) assert.equal((await http(s.port, 'POST', '/api/rooms', { body: { route: ROUTE } })).status, 201);
    const r = await http(s.port, 'POST', '/api/rooms', { body: { route: ROUTE } });
    assert.equal(r.status, 429);
    assert.equal(r.json.code, 'ROOMS_FULL');
  } finally {
    await s.stop();
  }
});

test('Vorgabe aus: ohne ROOMS=1 keine Räume', async () => {
  const s = await startServer({});
  try {
    assert.equal((await http(s.port, 'POST', '/api/rooms', { body: { route: ROUTE } })).status, 404);
    assert.equal((await http(s.port, 'GET', '/api/rooms')).status, 404);
    const raw = await rawClient(s.port);
    assert.equal(raw.status, 404);
    raw.sock.destroy();
    // Sync läuft unverändert.
    assert.equal((await http(s.port, 'GET', '/api/rides')).status, 200);
  } finally {
    await s.stop();
  }
});

test('Protokoll enthält keine Geheimnisse, Namen oder Positionen', async () => {
  const s = await startServer({ ROOMS: '1' });
  try {
    const room = (await http(s.port, 'POST', '/api/rooms', { body: { route: ROUTE } })).json;
    const a = await joinRoom(s.port, room, { name: 'Unverwechselbar Mustermann' });
    const b = await joinRoom(s.port, room, { secret: room.hostSecret, name: 'Zweite Person' });
    a.c.send({ type: 'state', distM: 3141.5926, speedMps: 9.87, powerW: 271 });
    await b.c.next((m) => m.type === 'snapshot');
    await joinRoom(s.port, room, { secret: 'geheim-falsch-123' });
    await http(s.port, 'GET', '/api/rides', { token: 'token-falsch-456789' });
    await sleep(200);
    const log = s.log();
    for (const needle of [
      TOKEN,
      room.secret,
      room.hostSecret,
      room.room.id,
      a.welcome.you.rejoinKey,
      'Unverwechselbar',
      'Zweite Person',
      '3141',
      'geheim-falsch',
      'token-falsch',
    ]) {
      assert.ok(!log.includes(needle), `Protokoll enthält ${needle}:\n${log}`);
    }
    assert.match(log, /Fehlgeschlagener Raum-Beitritt/);
    a.c.ws.close();
    b.c.ws.close();
  } finally {
    await s.stop();
  }
});
