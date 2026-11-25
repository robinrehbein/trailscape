// Lastprobe für die Räume (kein Test, wird von `node --test` nicht erfasst):
//   node server/test/raum-last.mjs [Räume] [Fahrer je Raum] [Sekunden]
// Startet einen eigenen Server auf einem freien Port (lokal, mit Zufalls-Token),
// verbindet Räume × Fahrer über den in Node eingebauten WebSocket, schickt
// je Fahrer 4 Zustände pro Sekunde und misst CPU-Zeit und Speicher des
// Serverprozesses sowie die Datenmengen. Linux (liest /proc).
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const [ROOMS = 10, PER_ROOM = 10, SECONDS = 20] = process.argv.slice(2).map(Number);
const here = path.dirname(fileURLToPath(import.meta.url));
const token = crypto.randomBytes(24).toString('hex');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const port = await new Promise((resolve) => {
  const s = net.createServer().listen(0, '127.0.0.1', () => {
    const p = s.address().port;
    s.close(() => resolve(p));
  });
});
const child = spawn(process.execPath, [path.join(here, '..', 'server.mjs')], {
  env: {
    ...process.env,
    PORT: String(port),
    SYNC_TOKEN: token,
    DATA_DIR: fs.mkdtempSync(path.join(os.tmpdir(), 'raum-last-')),
    ROOMS: '1',
    ROOMS_MAX: String(ROOMS),
    ROOM_MAX_RIDERS: String(Math.max(2, PER_ROOM)),
  },
  stdio: ['ignore', 'ignore', 'inherit'],
});
await sleep(500);

function procStat(pid) {
  const f = fs.readFileSync(`/proc/${pid}/stat`, 'utf8').split(') ')[1].split(' ');
  const ticks = Number(f[11]) + Number(f[12]); // utime + stime
  const rssPages = Number(f[21]);
  return { cpuS: ticks / 100, rssMb: (rssPages * 4096) / 1e6 };
}

let bytesIn = 0;
let msgsIn = 0;
let bytesOut = 0;
const sockets = [];
for (let r = 0; r < ROOMS; r++) {
  const res = await fetch(`http://127.0.0.1:${port}/api/rooms`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ route: { id: 'last', lengthM: 100000 } }),
  });
  const room = await res.json();
  for (let i = 0; i < PER_ROOM; i++) {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/api/rooms/ws`);
    await new Promise((ok, fail) => {
      ws.onopen = ok;
      ws.onerror = fail;
    });
    ws.onmessage = (e) => {
      bytesIn += Buffer.byteLength(e.data);
      msgsIn++;
    };
    ws.send(JSON.stringify({ type: 'join', v: 1, room: room.room.id, secret: room.secret, name: `Fahrer ${r}-${i}`, avatar: { v: 1, jersey: '#123456', helmet: 2 } }));
    sockets.push({ ws, dist: 0 });
  }
}
await sleep(500);

const before = procStat(child.pid);
const t0 = Date.now();
bytesIn = 0;
msgsIn = 0;
let tick = 0;
const timer = setInterval(() => {
  tick++;
  for (const s of sockets) {
    s.dist += 2.1;
    const msg = JSON.stringify({ type: 'state', distM: Math.round(s.dist * 100) / 100, speedMps: 8.42, powerW: 215, cadenceRpm: 88, lane: 0 });
    bytesOut += Buffer.byteLength(msg);
    s.ws.send(msg);
    if (tick % 20 === 0) s.ws.send(JSON.stringify({ type: 'ping', c: Date.now() }));
  }
}, 250);
await sleep(SECONDS * 1000);
clearInterval(timer);
const after = procStat(child.pid);
const dt = (Date.now() - t0) / 1000;
const n = ROOMS * PER_ROOM;
console.log(
  JSON.stringify(
    {
      fahrer: n,
      raeume: ROOMS,
      sekunden: Math.round(dt),
      serverCpuAnteilEinesKerns: +((after.cpuS - before.cpuS) / dt).toFixed(3),
      serverRssMb: +after.rssMb.toFixed(1),
      nutzdatenZumServerKBps: +(bytesOut / dt / 1000).toFixed(1),
      nutzdatenVomServerKBps: +(bytesIn / dt / 1000).toFixed(1),
      nutzdatenVomServerJeFahrerKBps: +(bytesIn / dt / 1000 / n).toFixed(2),
      nachrichtenVomServerProS: Math.round(msgsIn / dt),
    },
    null,
    2
  )
);
for (const s of sockets) s.ws.close();
child.kill('SIGTERM');
await sleep(300);
process.exit(0);
