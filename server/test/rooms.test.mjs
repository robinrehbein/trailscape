// Raum-Logik ohne Sockets, mit gestellter Uhr: node --test server/test/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { createRoomService, secretMatches, cleanName, CLOSE, DEFAULT_LIMITS } from '../rooms.mjs';

/** Gestellte Uhr. */
function clock(start = 1_700_000_000_000) {
  let t = start;
  return { now: () => t, advance: (ms) => (t += ms), set: (v) => (t = v) };
}

/** Gestellte Verbindung: merkt sich alles Gesendete. */
function fakeConn() {
  return {
    sent: [],
    closed: null,
    send(text) {
      if (this.closed) return false;
      this.sent.push(JSON.parse(text));
      return true;
    },
    close(code, reason) {
      if (!this.closed) this.closed = { code, reason };
    },
    of(type) {
      return this.sent.filter((m) => m.type === type);
    },
    last(type) {
      const all = this.of(type);
      return all[all.length - 1];
    },
  };
}

const ROUTE = { id: 'katalog/eifel-runde', version: '3', lengthM: 12_345, loop: true, name: 'Eifel-Runde' };

function setup(limits = {}) {
  const c = clock();
  const logs = [];
  const svc = createRoomService({ now: c.now, limits, log: (m) => logs.push(m) });
  const created = svc.createRoom({ route: ROUTE });
  assert.equal(created.status, 201);
  return { c, svc, logs, room: created.body };
}

function join(svc, room, { name = 'Anna', secret = room.secret, ip = '10.0.0.1', ...extra } = {}) {
  const conn = fakeConn();
  const session = svc.attach(conn, ip);
  session.onMessage(JSON.stringify({ type: 'join', v: 1, room: room.room.id, secret, name, ...extra }));
  return { conn, session, welcome: conn.last('welcome') };
}

test('Anlegen liefert Kennung, zwei verschiedene Geheimnisse und Grenzen', () => {
  const { room, svc } = setup();
  assert.match(room.room.id, /^[A-Za-z0-9_-]{16}$/);
  assert.match(room.secret, /^[A-Za-z0-9_-]{43}$/);
  assert.match(room.hostSecret, /^[A-Za-z0-9_-]{43}$/);
  assert.notEqual(room.secret, room.hostSecret);
  assert.deepEqual(room.room.route, ROUTE);
  const list = svc.listRooms();
  assert.equal(list.rooms.length, 1);
  assert.deepEqual(list.versions, [1]);
  // Die Übersicht enthält kein Geheimnis und keinen Hash.
  const text = JSON.stringify(list);
  assert.ok(!text.includes(room.secret) && !text.includes(room.hostSecret));
  assert.ok(!/Hash/i.test(text));
});

test('Anlegen prüft Eingaben', () => {
  const { svc } = setup();
  for (const body of [
    null,
    [],
    {},
    { route: { id: '', lengthM: 100 } },
    { route: { id: 'x', lengthM: -1 } },
    { route: { id: 'x', lengthM: Number.NaN } },
    { route: { id: 'x'.repeat(200), lengthM: 100 } },
    { route: ROUTE, maxRiders: 1 },
    { route: ROUTE, maxRiders: 11 },
    { route: ROUTE, ttlMin: 2 },
    { route: ROUTE, ttlMin: 100000 },
    { route: ROUTE, startAt: 5 },
  ]) {
    assert.equal(svc.createRoom(body).status, 400, JSON.stringify(body));
  }
});

test('Höchstzahl der Räume je Server', () => {
  const c = clock();
  const svc = createRoomService({ now: c.now, limits: { maxRooms: 3 } });
  for (let i = 0; i < 3; i++) assert.equal(svc.createRoom({ route: ROUTE }).status, 201);
  const full = svc.createRoom({ route: ROUTE });
  assert.equal(full.status, 429);
  assert.equal(full.body.code, 'ROOMS_FULL');
});

test('viele Räume: 500 Räume mit je 10 Fahrern bleiben getrennt', () => {
  const c = clock();
  const svc = createRoomService({ now: c.now, limits: { maxRooms: 500, maxConnections: 10_000 } });
  const all = [];
  for (let i = 0; i < 500; i++) {
    const room = svc.createRoom({ route: ROUTE }).body;
    const riders = [];
    for (let j = 0; j < 10; j++) riders.push(join(svc, room, { name: `F${j}`, ip: `10.${i % 250}.0.${j}` }));
    all.push({ room, riders });
  }
  assert.equal(svc.stats().rooms, 500);
  // Ein Zustand in Raum 7 erreicht nur Raum 7.
  all[7].riders[0].session.onMessage(JSON.stringify({ type: 'state', distM: 10, speedMps: 8 }));
  svc.tick();
  assert.equal(all[7].riders[1].conn.of('snapshot').length, 1);
  assert.equal(all[8].riders[1].conn.of('snapshot').length, 0);
  // Ein Geheimnis öffnet keinen fremden Raum.
  const r = join(svc, { room: all[1].room.room, secret: all[2].room.secret });
  assert.equal(r.conn.closed.code, CLOSE.JOIN_DENIED);
});

test('Beitritt mit falschem Geheimnis, unbekanntem Raum oder Gastgeber-Geheimnis', () => {
  const { svc, room } = setup();
  const wrong = join(svc, room, { secret: 'falsch' });
  assert.equal(wrong.conn.closed.code, CLOSE.JOIN_DENIED);
  assert.equal(wrong.conn.last('error').code, 'JOIN_DENIED');

  const unknown = join(svc, { room: { id: 'AAAAAAAAAAAAAAAA' }, secret: room.secret });
  assert.equal(unknown.conn.closed.code, CLOSE.JOIN_DENIED);
  // Gleiche Antwort für „gibt es nicht“ und „falsches Geheimnis“.
  assert.deepEqual(unknown.conn.last('error'), wrong.conn.last('error'));

  const noSecret = join(svc, room, { secret: null });
  assert.equal(noSecret.conn.closed.code, CLOSE.JOIN_DENIED);

  const guest = join(svc, room);
  assert.equal(guest.welcome.you.host, false);
  const host = join(svc, room, { secret: room.hostSecret, name: 'Gastgeberin' });
  assert.equal(host.welcome.you.host, true);
});

test('erste Nachricht muss join sein; kaputte Nachrichten vor dem Beitritt trennen', () => {
  const { svc } = setup();
  for (const text of ['{kaputt', '[]', '"x"', '{"type":"state","distM":1,"speedMps":1}', '{}']) {
    const conn = fakeConn();
    const s = svc.attach(conn, '1.1.1.1');
    s.onMessage(text);
    assert.equal(conn.closed.code, CLOSE.BAD_MESSAGE, text);
  }
});

test('Version wird geprüft und unterstützte Versionen genannt', () => {
  const { svc, room } = setup();
  const r = join(svc, room, { v: 2 });
  assert.equal(r.conn.closed.code, CLOSE.VERSION);
  assert.deepEqual(r.conn.last('error').supported, [1]);
});

test('Name wird bereinigt und gekürzt, Avatar ist undurchsichtig und begrenzt', () => {
  const { svc, room } = setup();
  const avatar = { v: 1, jersey: '#ff0000', zukunft: { egal: [1, 2, 3] } };
  const a = join(svc, room, { name: '  Anna\n‮B\u0007  ', avatar });
  assert.equal(a.welcome.riders[0].name, 'AnnaB');
  assert.deepEqual(a.welcome.riders[0].avatar, avatar);
  const long = join(svc, room, { name: 'x'.repeat(100) });
  assert.equal(long.welcome.riders.find((r) => r.id === long.welcome.you.id).name.length, 32);
  const empty = join(svc, room, { name: ' \n ' });
  assert.equal(empty.conn.closed.code, CLOSE.BAD_MESSAGE);
  const big = join(svc, room, { avatar: { x: 'y'.repeat(600) } });
  assert.equal(big.conn.closed.code, CLOSE.BAD_MESSAGE);
  assert.equal(big.conn.last('error').code, 'AVATAR_TOO_BIG');
  assert.equal(cleanName(42, 10), null);
});

test('Raum voll; Wiederbeitritt findet trotzdem seinen Platz', () => {
  const c = clock();
  const svc = createRoomService({ now: c.now });
  const room = svc.createRoom({ route: ROUTE, maxRiders: 2 }).body;
  const a = join(svc, room, { name: 'A' });
  join(svc, room, { name: 'B' });
  const third = join(svc, room, { name: 'C' });
  assert.equal(third.conn.closed.code, CLOSE.ROOM_FULL);
  // A bricht ab und kommt mit rejoin zurück.
  a.session.onClose();
  const back = join(svc, room, { name: 'A', rejoin: { id: a.welcome.you.id, key: a.welcome.you.rejoinKey } });
  assert.equal(back.welcome.you.id, a.welcome.you.id);
  assert.notEqual(back.welcome.you.rejoinKey, a.welcome.you.rejoinKey, 'Schlüssel wird erneuert');
});

test('Zustand wird gesammelt und im Takt rundgesendet', () => {
  const { svc, room, c } = setup();
  const a = join(svc, room, { name: 'A' });
  const b = join(svc, room, { name: 'B' });
  assert.equal(a.conn.last('rider').rider.name, 'B', 'A erfährt von B');
  a.session.onMessage(JSON.stringify({ type: 'state', distM: 100.5, speedMps: 8.2, powerW: 210.4, cadenceRpm: 88.6, lane: 1, neuesFeld: 'egal' }));
  a.session.onMessage(JSON.stringify({ type: 'state', distM: 102.5, speedMps: 8.3, powerW: 215 }));
  assert.equal(b.conn.of('snapshot').length, 0, 'erst im Takt');
  c.advance(250);
  svc.tick();
  const snap = b.conn.last('snapshot');
  assert.equal(snap.states.length, 1, 'nur der neueste Zustand');
  assert.deepEqual(snap.states[0], { id: a.welcome.you.id, at: c.now() - 250, distM: 102.5, speedMps: 8.3, powerW: 215 });
  assert.equal(snap.serverTime, c.now());
  // Ohne neue Zustände kein Rundruf.
  c.advance(250);
  svc.tick();
  assert.equal(b.conn.of('snapshot').length, 1);
  // Wer später kommt, bekommt die letzten Zustände im welcome.
  const late = join(svc, room, { name: 'C' });
  assert.equal(late.welcome.states.length, 1);
});

test('Leistung teilen ist abschaltbar – auch mitten in der Fahrt', () => {
  const { svc, room, c } = setup();
  const quiet = join(svc, room, { name: 'Leise', sharePower: false });
  const loud = join(svc, room, { name: 'Laut' });
  const watcher = join(svc, room, { name: 'Zuschauer' });
  quiet.session.onMessage(JSON.stringify({ type: 'state', distM: 1, speedMps: 5, powerW: 300 }));
  loud.session.onMessage(JSON.stringify({ type: 'state', distM: 1, speedMps: 5, powerW: 250 }));
  c.advance(250);
  svc.tick();
  const states = watcher.conn.last('snapshot').states;
  assert.equal(states.find((s) => s.id === quiet.welcome.you.id).powerW, undefined);
  assert.equal(states.find((s) => s.id === loud.welcome.you.id).powerW, 250);
  loud.session.onMessage(JSON.stringify({ type: 'settings', sharePower: false }));
  assert.equal(watcher.conn.last('rider').rider.sharePower, false);
  loud.session.onMessage(JSON.stringify({ type: 'state', distM: 3, speedMps: 5, powerW: 260 }));
  c.advance(250);
  svc.tick();
  assert.equal(watcher.conn.last('snapshot').states[0].powerW, undefined);
});

test('kaputte und unplausible Zustände werden verworfen und gemeldet', () => {
  const { svc, room, c } = setup();
  const a = join(svc, room);
  const b = join(svc, room, { name: 'B' });
  const bad = [
    { type: 'state' },
    { type: 'state', distM: 'x', speedMps: 1 },
    { type: 'state', distM: -1, speedMps: 1 },
    { type: 'state', distM: 1, speedMps: 99 },
    { type: 'state', distM: 1, speedMps: 1, powerW: 99999 },
    { type: 'state', distM: 1, speedMps: 1, cadenceRpm: -3 },
    { type: 'state', distM: 1, speedMps: 1, lane: 1.5 },
    { type: 'state', distM: 1, speedMps: 1, lane: 9 },
  ];
  for (const m of bad) a.session.onMessage(JSON.stringify(m));
  assert.equal(a.conn.of('error').length, bad.length);
  a.session.onMessage('{kaputt');
  assert.equal(a.conn.last('error').code, 'BAD_MESSAGE');
  assert.equal(a.conn.closed, null, 'nach dem Beitritt nur melden, nicht trennen');
  // Sprung: erst 100 m, dann 1 s später 500 m weiter.
  a.session.onMessage(JSON.stringify({ type: 'state', distM: 100, speedMps: 8 }));
  c.advance(1000);
  a.session.onMessage(JSON.stringify({ type: 'state', distM: 600, speedMps: 8 }));
  assert.equal(a.conn.last('error').code, 'IMPLAUSIBLE');
  a.session.onMessage(JSON.stringify({ type: 'state', distM: 50, speedMps: 8 }));
  assert.equal(a.conn.last('error').code, 'IMPLAUSIBLE');
  a.session.onMessage(JSON.stringify({ type: 'state', distM: 108, speedMps: 8 }));
  c.advance(250);
  svc.tick();
  assert.equal(b.conn.last('snapshot').states[0].distM, 108);
  // Unbekannte Typen werden still ignoriert (neuere Clients).
  const errs = a.conn.of('error').length;
  a.session.onMessage(JSON.stringify({ type: 'emote', what: 'winken' }));
  assert.equal(a.conn.of('error').length, errs);
});

test('übergroße Nachricht trennt', () => {
  const { svc, room } = setup();
  const a = join(svc, room);
  a.session.onMessage(JSON.stringify({ type: 'state', distM: 1, speedMps: 1, pad: 'x'.repeat(2000) }));
  assert.equal(a.conn.closed.code, CLOSE.TOO_BIG);
});

test('Ratenbegrenzung: 4 Hz geht, Flut wird verworfen und dann getrennt', () => {
  const { svc, room, c } = setup();
  const ok = join(svc, room, { name: 'Gut' });
  for (let i = 0; i < 400; i++) {
    c.advance(250);
    ok.session.onMessage(JSON.stringify({ type: 'state', distM: i * 2, speedMps: 8 }));
  }
  assert.equal(ok.conn.of('error').length, 0);
  assert.equal(ok.conn.closed, null);

  const flood = join(svc, room, { name: 'Flut' });
  for (let i = 0; i < 100 && !flood.conn.closed; i++) flood.session.onMessage(JSON.stringify({ type: 'ping', c: i }));
  assert.equal(flood.conn.of('pong').length, DEFAULT_LIMITS.rateBurst - 1, 'Eimer (join hat eine Marke verbraucht)');
  assert.equal(flood.conn.of('error').filter((e) => e.code === 'RATE').length, 1, 'höchstens eine Meldung je Sekunde');
  assert.equal(flood.conn.closed.code, CLOSE.RATE);
});

test('Uhrenabgleich: pong spiegelt die Client-Zeit und nennt die Server-Zeit', () => {
  const { svc, room, c } = setup();
  const a = join(svc, room);
  a.session.onMessage(JSON.stringify({ type: 'ping', c: 12345 }));
  assert.deepEqual(a.conn.last('pong'), { type: 'pong', c: 12345, s: c.now() });
  assert.equal(a.welcome.serverTime, c.now());
});

test('Abbruch: Platz bleibt reserviert, nach der Frist left/timeout', () => {
  const { svc, room, c } = setup({ rejoinGraceMs: 30_000, connectionIdleMs: 3_600_000 });
  const a = join(svc, room, { name: 'A' });
  const b = join(svc, room, { name: 'B' });
  a.session.onMessage(JSON.stringify({ type: 'state', distM: 500, speedMps: 8 }));
  a.session.onClose(); // Funkloch
  c.advance(29_000);
  svc.tick();
  assert.equal(b.conn.of('left').length, 0);
  // Wiederbeitritt: erster Zustand darf weit weg sein (keine Sprungprüfung).
  const back = join(svc, room, { name: 'A', rejoin: { id: a.welcome.you.id, key: a.welcome.you.rejoinKey } });
  assert.equal(back.welcome.you.id, a.welcome.you.id);
  back.session.onMessage(JSON.stringify({ type: 'state', distM: 900, speedMps: 8 }));
  assert.equal(back.conn.of('error').length, 0);
  // Falscher Schlüssel: neuer Fahrer statt Übernahme.
  const thief = join(svc, room, { name: 'Dieb', rejoin: { id: b.welcome.you.id, key: 'falsch' } });
  assert.notEqual(thief.welcome.you.id, b.welcome.you.id);
  // Jetzt bricht A endgültig ab.
  back.session.onClose();
  c.advance(31_000);
  svc.tick();
  assert.deepEqual(b.conn.last('left'), { type: 'left', id: a.welcome.you.id, reason: 'timeout' });
  const old = join(svc, room, { name: 'A', rejoin: { id: a.welcome.you.id, key: back.welcome.you.rejoinKey } });
  assert.notEqual(old.welcome.you.id, a.welcome.you.id, 'nach der Frist ist der Platz weg');
});

test('Wiederbeitritt über eine zweite Verbindung ersetzt die alte', () => {
  const { svc, room } = setup();
  const a = join(svc, room, { name: 'A' });
  const again = join(svc, room, { name: 'A', rejoin: { id: a.welcome.you.id, key: a.welcome.you.rejoinKey } });
  assert.equal(a.conn.closed.code, CLOSE.REPLACED);
  assert.equal(again.welcome.riders.length, 1);
});

test('leave entfernt sofort', () => {
  const { svc, room } = setup();
  const a = join(svc, room, { name: 'A' });
  const b = join(svc, room, { name: 'B' });
  a.session.onMessage(JSON.stringify({ type: 'leave' }));
  assert.equal(a.conn.closed.code, CLOSE.LEFT);
  assert.deepEqual(b.conn.last('left'), { type: 'left', id: a.welcome.you.id, reason: 'leave' });
});

test('Countdown und Ende nur für den Gastgeber', () => {
  const { svc, room, c } = setup();
  const host = join(svc, room, { secret: room.hostSecret, name: 'H' });
  const guest = join(svc, room, { name: 'G' });
  guest.session.onMessage(JSON.stringify({ type: 'start', inS: 10 }));
  assert.equal(guest.conn.last('error').code, 'NOT_HOST');
  guest.session.onMessage(JSON.stringify({ type: 'end' }));
  assert.equal(guest.conn.last('error').code, 'NOT_HOST');
  host.session.onMessage(JSON.stringify({ type: 'start', inS: 1 }));
  assert.equal(host.conn.last('error').code, 'INVALID');
  host.session.onMessage(JSON.stringify({ type: 'start', inS: 10 }));
  assert.deepEqual(guest.conn.last('countdown'), { type: 'countdown', startAt: c.now() + 10_000, serverTime: c.now() });
  // Neu ansetzen vor dem Start geht, danach nicht mehr.
  host.session.onMessage(JSON.stringify({ type: 'start', inS: 20 }));
  assert.equal(guest.conn.last('countdown').startAt, c.now() + 20_000);
  c.advance(21_000);
  host.session.onMessage(JSON.stringify({ type: 'start', inS: 20 }));
  assert.equal(host.conn.last('error').code, 'ALREADY_STARTED');
  host.session.onMessage(JSON.stringify({ type: 'end' }));
  assert.equal(guest.conn.last('ended').reason, 'host');
  assert.equal(guest.conn.closed.code, CLOSE.ROOM_ENDED);
  assert.equal(svc.stats().rooms, 0);
  assert.equal(svc.stats().sessions, 0);
  // Danach ist der Raum weg.
  assert.equal(join(svc, room).conn.closed.code, CLOSE.JOIN_DENIED);
});

test('Lebensdauer, Leerlauf-Abbau, Beitrittsfrist und stille Verbindungen', () => {
  const c = clock();
  const svc = createRoomService({ now: c.now, limits: { roomTtlMs: 60 * 60_000, roomIdleMs: 10 * 60_000 } });
  const busy = svc.createRoom({ route: ROUTE }).body;
  const idle = svc.createRoom({ route: ROUTE }).body;
  const a = join(svc, busy);
  // Verbindung ohne join wird nach 5 s getrennt.
  const silent = fakeConn();
  svc.attach(silent, '2.2.2.2');
  for (let i = 0; i < 24; i++) {
    c.advance(2_500);
    a.session.onMessage(JSON.stringify({ type: 'ping', c: 1 }));
    svc.tick();
  }
  assert.equal(silent.closed.code, CLOSE.JOIN_TIMEOUT);
  c.advance(9.5 * 60_000);
  a.session.onMessage(JSON.stringify({ type: 'ping', c: 1 }));
  svc.tick();
  assert.equal(svc.listRooms().rooms.length, 1, 'leerer Raum nach 10 min weg, belegter bleibt');
  assert.equal(join(svc, idle).conn.closed.code, CLOSE.JOIN_DENIED);
  // Gerät schweigt 20 s → getrennt.
  c.advance(21_000);
  svc.tick();
  assert.equal(a.conn.closed.code, CLOSE.IDLE);
  // Lebensdauer: nach 60 min endet auch ein belegter Raum.
  const b = join(svc, busy);
  for (let i = 0; i < 400 && !b.conn.closed; i++) {
    c.advance(10_000);
    b.session.onMessage(JSON.stringify({ type: 'ping' }));
    svc.tick();
  }
  assert.equal(b.conn.last('ended').reason, 'expired');
  assert.equal(svc.stats().rooms, 0);
});

test('Fehlversuche je Adresse werden gesperrt; Protokoll ohne Geheimnisse', () => {
  const { svc, room, logs } = setup({ joinFailureLimit: 5 });
  for (let i = 0; i < 5; i++) join(svc, room, { secret: `falsch-${i}`, ip: '6.6.6.6' });
  assert.equal(svc.canAccept('6.6.6.6').ok, false);
  assert.equal(svc.canAccept('6.6.6.6').status, 429);
  assert.equal(svc.canAccept('7.7.7.7').ok, true);
  // Selbst mit richtigem Geheimnis gesperrt, solange die Sperre läuft.
  assert.equal(join(svc, room, { ip: '6.6.6.6' }).conn.closed.code, CLOSE.TOO_MANY_FAILURES);
  const ok = join(svc, room, { ip: '7.7.7.7', name: 'Geheimer Name' });
  ok.session.onMessage(JSON.stringify({ type: 'state', distM: 4242.4242, speedMps: 8 }));
  svc.tick();
  const all = logs.join('\n');
  for (const s of [room.secret, room.hostSecret, room.room.id, ok.welcome.you.rejoinKey, 'Geheimer Name', '4242', 'falsch-']) {
    assert.ok(!all.includes(s), `Protokoll enthält ${s}`);
  }
  assert.match(all, /Fehlgeschlagener Raum-Beitritt von 6\.6\.6\.6/);
});

test('konstantzeitiger Vergleich: richtig, falsch, andere Länge, kein String', () => {
  const secret = 'abc';
  const hash = crypto.createHash('sha256').update(secret).digest();
  assert.equal(secretMatches('abc', hash), true);
  assert.equal(secretMatches('abd', hash), false);
  assert.equal(secretMatches('abcabcabc', hash), false);
  assert.equal(secretMatches('', hash), false);
  assert.equal(secretMatches(undefined, hash), false);
  assert.equal(secretMatches({ toString: () => 'abc' }, hash), false);
});

test('Server-Höchstzahl an Verbindungen', () => {
  const c = clock();
  const svc = createRoomService({ now: c.now, limits: { maxConnections: 2 } });
  svc.attach(fakeConn(), 'a');
  svc.attach(fakeConn(), 'b');
  assert.deepEqual(svc.canAccept('c'), { ok: false, status: 503, message: 'Server ausgelastet' });
});

test('Herunterfahren beendet alle Räume', () => {
  const { svc, room } = setup();
  const a = join(svc, room);
  svc.shutdown();
  assert.equal(a.conn.last('ended').reason, 'server');
  assert.equal(svc.stats().rooms, 0);
});
