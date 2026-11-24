// Attrappe des Weltbaus für die Tests des Bau-Dienstes (statt `indoor-dev`).
// FAKE_MODE=fail → Bau scheitert, FAKE_MODE=slow → Bau dauert ~5 s.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const args = process.argv.slice(2);
if (args[0] === 'server-info') {
  console.log(JSON.stringify({ builderId: 'indoor-world/9', laserSources: ['nw', 'bw'], surfaceKinds: ['ASPHALT', 'GRAVEL', 'UNKNOWN'] }));
  process.exit(0);
}
if (args[0] !== 'server-build') process.exit(2);
const job = args[args.indexOf('--job') + 1];
const req = JSON.parse(fs.readFileSync(path.join(job, 'request.json'), 'utf8'));
const mode = process.env.FAKE_MODE || '';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
console.log('@plan ' + JSON.stringify({ worldId: 'w' + '1'.repeat(24), tiles: 4, laser: req.laser, laserRegion: 'nw' }));
for (const phase of ['ELEVATION', 'VECTOR', 'AERIAL', 'MESHING', 'WRITING']) {
  console.log(`@progress {"phase":"${phase}","done":1,"total":1}`);
  if (mode === 'slow') await sleep(1000);
}
if (mode === 'fail') {
  fs.writeFileSync(path.join(job, 'result.json'), JSON.stringify({ ok: false, error: { code: 'BUILD_FAILED', detail: 'Attrappe' } }));
  process.exit(1);
}
const body = crypto.createHash('sha256').update(JSON.stringify(req.route.points)).digest();
const pkg = Buffer.concat(Array.from({ length: 4096 }, () => body)); // 128 KiB
fs.writeFileSync(path.join(job, 'package.zip'), pkg);
const sha = crypto.createHash('sha256').update(pkg).digest('hex');
fs.writeFileSync(path.join(job, 'result.json'), JSON.stringify({ ok: true, worldId: 'w' + '1'.repeat(24), packageBytes: pkg.length, sha256: sha, laserSource: req.laser ? 'nw' : undefined, buildSeconds: 0.1 }));
