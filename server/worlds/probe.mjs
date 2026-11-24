// Probe-Client für den Bau-Dienst (Ende-zu-Ende-Prüfung, docs/indoor/serverbau.md).
//   SYNC_TOKEN=… node probe.mjs <server-url> <request.json> <paket.zip>
// Auftrag anlegen, Stand abfragen bis fertig, Paket in zwei Teilen laden
// (zweiter Teil per Range – wie die App nach einem Abbruch), SHA-256 prüfen.
import fs from 'node:fs';
import crypto from 'node:crypto';

const [url, requestFile, out] = process.argv.slice(2);
const token = process.env.SYNC_TOKEN;
if (!url || !requestFile || !out || !token) {
  console.error('Aufruf: SYNC_TOKEN=… node probe.mjs <server-url> <request.json> <paket.zip>');
  process.exit(2);
}
const base = url.replace(/\/+$/, '') + '/api/worlds';
const auth = { Authorization: `Bearer ${token}` };
const t0 = Date.now();
const sec = () => ((Date.now() - t0) / 1000).toFixed(1);

const info = await (await fetch(`${base}/info`, { headers: auth })).json();
console.log(`[${sec()} s] Dienst: ${JSON.stringify(info)}`);
const created = await fetch(`${base}/jobs`, {
  method: 'POST',
  headers: { ...auth, 'Content-Type': 'application/json' },
  body: fs.readFileSync(requestFile),
});
let job = await created.json();
console.log(`[${sec()} s] Auftrag ${created.status}: ${JSON.stringify(job)}`);
if (!job.id) process.exit(1);
let last = '';
while (job.state === 'QUEUED' || job.state === 'RUNNING') {
  await new Promise((r) => setTimeout(r, 2000));
  job = await (await fetch(`${base}/jobs/${job.id}`, { headers: auth })).json();
  const line = `${job.state} ${job.phase || ''} ${job.done ?? ''}/${job.total ?? ''} ${Math.round((job.fraction || 0) * 100)} %`;
  if (line !== last) console.log(`[${sec()} s] ${line}`);
  last = line;
}
if (job.state !== 'DONE') {
  console.error(`[${sec()} s] gescheitert: ${JSON.stringify(job)}`);
  process.exit(1);
}
console.log(`[${sec()} s] fertig: ${job.packageBytes} B, Laser ${job.laserSource}, Bau ${job.buildSeconds} s`);
const half = Math.floor(job.packageBytes / 2);
const first = await fetch(`${base}/jobs/${job.id}/package`, { headers: { ...auth, Range: `bytes=0-${half - 1}` } });
fs.writeFileSync(out, Buffer.from(await first.arrayBuffer()));
const second = await fetch(`${base}/jobs/${job.id}/package`, { headers: { ...auth, Range: `bytes=${half}-` } });
fs.appendFileSync(out, Buffer.from(await second.arrayBuffer()));
const sha = crypto.createHash('sha256').update(fs.readFileSync(out)).digest('hex');
console.log(`[${sec()} s] Paket ${out}: ${fs.statSync(out).size} B (Teile ${first.status}/${second.status}), SHA-256 ${sha === job.sha256 ? 'stimmt' : 'FALSCH'}`);
process.exit(sha === job.sha256 ? 0 : 1);
