// Vertrag zwischen Server und App: Die Beispielnachrichten in
// fixtures/raum-v1.json liest auch der Kotlin-Codec (core, RoomCodecTest).
// Hier wird geprüft, dass der Server die Geräte-Beispiele annimmt und dass
// seine eigenen Nachrichten genau die Felder der 'aktuell'-Beispiele haben.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRoomService } from '../rooms.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const FIX = JSON.parse(fs.readFileSync(path.join(here, 'fixtures', 'raum-v1.json'), 'utf8'));

function conn() {
  return {
    sent: [],
    closed: null,
    send(t) {
      this.sent.push(JSON.parse(t));
    },
    close(code) {
      this.closed = code;
    },
  };
}

const keys = (o) => Object.keys(o).sort();

test('Server nimmt alle Geräte-Beispiele an und antwortet in der Form der Beispiele', () => {
  let t = 1_764_072_000_000;
  const svc = createRoomService({ now: () => t });
  const created = svc.createRoom({ route: { id: 'katalog/eifel', lengthM: 12345.6, loop: true, version: '3', name: 'Eifel-Runde' } }).body;
  const ids = { room: created.room.id };

  const hostConn = conn();
  const host = svc.attach(hostConn, '1.1.1.1');
  host.onMessage(JSON.stringify({ ...FIX.client.join, room: ids.room, secret: created.hostSecret, name: 'Ben' }));
  const guestConn = conn();
  const guest = svc.attach(guestConn, '1.1.1.2');
  guest.onMessage(JSON.stringify({ ...FIX.client.join, room: ids.room, secret: created.secret }));
  const welcome = guestConn.sent.find((m) => m.type === 'welcome');
  assert.ok(welcome, JSON.stringify(guestConn.sent));

  for (const name of ['state', 'stateMinimal', 'ping', 'settings']) {
    t += 300;
    guest.onMessage(JSON.stringify(name === 'stateMinimal' ? { ...FIX.client[name], distM: 1300 } : FIX.client[name]));
  }
  t += 300;
  host.onMessage(JSON.stringify({ type: 'state', distM: 50, speedMps: 7, powerW: 180, cadenceRpm: 86, lane: 1 }));
  t += 300;
  svc.tick();
  host.onMessage(JSON.stringify(FIX.client.start));
  assert.equal(guestConn.sent.filter((m) => m.type === 'error').length, 0, JSON.stringify(guestConn.sent));
  assert.equal(hostConn.sent.filter((m) => m.type === 'error').length, 0, JSON.stringify(hostConn.sent));

  // Wiederbeitritt mit den echten Kennungen aus dem welcome.
  const again = conn();
  svc.attach(again, '1.1.1.2').onMessage(
    JSON.stringify({ ...FIX.client.joinRejoin, room: ids.room, secret: created.secret, rejoin: { id: welcome.you.id, key: welcome.you.rejoinKey } })
  );
  assert.equal(again.sent.find((m) => m.type === 'welcome').you.id, welcome.you.id);

  const third = conn();
  const thirdSession = svc.attach(third, '1.1.1.3');
  thirdSession.onMessage(JSON.stringify({ ...FIX.client.join, room: ids.room, secret: created.secret, name: 'Cleo' }));
  thirdSession.onMessage(JSON.stringify(FIX.client.leave));
  host.onMessage(JSON.stringify(FIX.client.end));

  const seen = {};
  for (const m of [...hostConn.sent, ...guestConn.sent, ...again.sent]) seen[m.type] ??= m;
  const expected = FIX.server.aktuell;
  for (const type of ['welcome', 'rider', 'left', 'snapshot', 'countdown', 'ended', 'pong']) {
    assert.ok(seen[type], `Server schickte kein ${type}`);
    assert.deepEqual(keys(seen[type]), keys(expected[type]), type);
  }
  const w = seen.welcome;
  assert.deepEqual(keys(w.you), keys(expected.welcome.you));
  assert.deepEqual(keys(w.room), keys(expected.welcome.room));
  assert.deepEqual(keys(w.room.route), keys(expected.welcome.room.route));
  assert.deepEqual(keys(w.limits), keys(expected.welcome.limits));
  const stateKeys = new Set(Object.keys(expected.snapshot.states[0]));
  for (const s of seen.snapshot.states) for (const k of Object.keys(s)) assert.ok(stateKeys.has(k), `unbekanntes Zustandsfeld ${k}`);
  const riderKeys = new Set(expected.welcome.riders.flatMap((r) => Object.keys(r)));
  for (const r of w.riders) for (const k of Object.keys(r)) assert.ok(riderKeys.has(k), `unbekanntes Fahrerfeld ${k}`);
});

test('Server schickt Fehler in der Form des Beispiels', () => {
  const svc = createRoomService();
  const c = conn();
  svc.attach(c, 'x').onMessage(JSON.stringify({ ...FIX.client.join, v: 7 }));
  assert.deepEqual(keys(c.sent[0]), keys(FIX.server.aktuell.error));
});
