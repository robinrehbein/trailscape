// Erzeugt eine erfundene Mini-Welt zum Selbsttest des Betrachters, solange es
// den Kotlin-Weltbau noch nicht gibt: huegeliges Gelaende in drei Stufen samt
// `terrain_sub_i_j`, eine Rundstrecke mit Fahrbahn, Haeuser, Baeume, ein Teich
// und ein gueltiges Manifest nach WorldManifest.kt.
//
//   node mockworld.mjs [zielverzeichnis]      (Vorgabe: ../out/mock)
//
// Keine echten Geodaten, kein Netz. Der GLB-Schreiber unten ist bewusst klein.

import { mkdirSync, writeFileSync, rmSync, statSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(process.argv[2] ?? join(HERE, '..', 'out', 'mock'));

const LEVEL_FACTOR = 4;
const cellM = (level) => 256 * LEVEL_FACTOR ** level;
const TEXTURE_PX = [512, 512, 256];
const STEP_M = 5;

// ---------------------------------------------------------------------------
// Rauschen
// ---------------------------------------------------------------------------

function hash2(x, y) {
  let h = (Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}
const smooth = (t) => t * t * (3 - 2 * t);
function valueNoise(x, y) {
  const xi = Math.floor(x), yi = Math.floor(y);
  const tx = smooth(x - xi), ty = smooth(y - yi);
  const a = hash2(xi, yi), b = hash2(xi + 1, yi), c = hash2(xi, yi + 1), d = hash2(xi + 1, yi + 1);
  return a + (b - a) * tx + (c - a) * ty + (a - b - c + d) * tx * ty;
}
function fbm(x, y, octaves = 4) {
  let s = 0, a = 0.5, f = 1;
  for (let i = 0; i < octaves; i++) { s += a * valueNoise(x * f, y * f); a *= 0.5; f *= 2.03; }
  return s / (1 - 0.5 ** octaves);
}
const smoothstep = (a, b, x) => smooth(Math.min(1, Math.max(0, (x - a) / (b - a))));
let seed = 12345;
const rnd = () => ((seed = (Math.imul(seed, 1664525) + 1013904223) | 0) >>> 0) / 4294967296;

// ---------------------------------------------------------------------------
// Gelaende und Strecke
// ---------------------------------------------------------------------------

const POND = { e: 150, n: -110, r: 62 };

function rawHeight(e, n) {
  const r = Math.hypot(e, n);
  let h = 55 * Math.sin(e / 900 + 0.4) * Math.cos(n / 1100 - 0.2);
  h += 22 * Math.sin(e / 310 + 1.0) * Math.sin(n / 270 + 0.5);
  h += 14 * (fbm(e / 400, n / 400, 3) - 0.5);
  // Mittelgebirge am Horizont
  h += 330 * smoothstep(2500, 11000, r) * (0.35 + 0.65 * fbm(e / 2600 + 7, n / 2600 + 3, 4));
  // Senke fuer den Teich
  h -= 5 * (1 - smoothstep(0.5 * POND.r, 1.5 * POND.r, Math.hypot(e - POND.e, n - POND.n)));
  return h;
}

// Rundstrecke: verbeulter Kreis, nach Bogenlaenge in 5-m-Schritten abgetastet.
function buildTrack() {
  const dense = [];
  const N = 20000;
  for (let k = 0; k <= N; k++) {
    const th = (k / N) * 2 * Math.PI;
    const r = 430 + 95 * Math.sin(3 * th + 0.6) + 45 * Math.cos(2 * th);
    // im Uhrzeigersinn von oben (Nord → Ost)
    dense.push([60 + r * Math.sin(th), -40 + r * Math.cos(th)]);
  }
  const cum = [0];
  for (let k = 1; k <= N; k++) cum.push(cum[k - 1] + Math.hypot(dense[k][0] - dense[k - 1][0], dense[k][1] - dense[k - 1][1]));
  const lengthM = Math.round(cum[N] * 100) / 100;
  const count = Math.ceil(lengthM / STEP_M) + 1;
  const e = [], n = [];
  let k = 0;
  for (let i = 0; i < count; i++) {
    const d = Math.min(i * STEP_M, lengthM) * (cum[N] / lengthM);
    while (k < N - 1 && cum[k + 1] < d) k++;
    const t = (d - cum[k]) / (cum[k + 1] - cum[k] || 1);
    e.push(dense[k][0] + (dense[k + 1][0] - dense[k][0]) * t);
    n.push(dense[k][1] + (dense[k + 1][1] - dense[k][1]) * t);
  }
  // Hoehe der Fahrbahn: Gelaende, entlang der Strecke geglaettet (±40 m, umlaufend)
  const raw = e.map((x, i) => rawHeight(x, n[i]));
  const m = count - 1; // Probe m liegt auf Probe 0
  const u = raw.map((_, i) => {
    let s = 0, w = 0;
    for (let o = -8; o <= 8; o++) {
      const wt = 9 - Math.abs(o);
      s += raw[(((i + o) % m) + m) % m] * wt;
      w += wt;
    }
    return s / w;
  });
  u[count - 1] = u[0];
  return { lengthM, e, n, u, count };
}

const track = buildTrack();
const U0 = track.u[0]; // origin.eleM = Hoehe des Streckenstarts → u(0) = 0
for (let i = 0; i < track.count; i++) track.u[i] -= U0;
const distAt = (i) => Math.min(i * STEP_M, track.lengthM);

const SURFACES = [
  { fromM: 0, toM: 1500, kind: 'ASPHALT', highway: 'tertiary', widthM: 5.0, key: 'asphalt' },
  { fromM: 1500, toM: 2100, kind: 'GRAVEL', highway: 'track', key: 'gravel', widthM: 3.0 },
  { fromM: 2100, toM: track.lengthM, kind: 'ASPHALT', highway: 'tertiary', widthM: 5.0, key: 'asphalt' },
];
const surfaceAt = (d) => SURFACES.find((s) => d >= s.fromM && d <= s.toM) ?? SURFACES[0];

// Raeumlicher Index der Proben fuer „wie weit ist die Strecke weg?"
const HASH_CELL = 64;
const trackHash = new Map();
for (let i = 0; i < track.count - 1; i++) {
  const key = `${Math.floor(track.e[i] / HASH_CELL)}/${Math.floor(track.n[i] / HASH_CELL)}`;
  if (!trackHash.has(key)) trackHash.set(key, []);
  trackHash.get(key).push(i);
}
/** Naechster Punkt der Strecke im Umkreis von 64 m: `{ d, u, dist }` oder null. */
function nearRoute(e, n) {
  const cx = Math.floor(e / HASH_CELL), cy = Math.floor(n / HASH_CELL);
  let best = null;
  for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
    const list = trackHash.get(`${cx + dx}/${cy + dy}`);
    if (!list) continue;
    for (const i of list) {
      const ax = track.e[i], ay = track.n[i];
      const bx = track.e[i + 1] - ax, by = track.n[i + 1] - ay;
      const t = Math.min(1, Math.max(0, ((e - ax) * bx + (n - ay) * by) / (bx * bx + by * by || 1)));
      const d = Math.hypot(e - ax - bx * t, n - ay - by * t);
      if (!best || d < best.d) best = { d, u: track.u[i] + (track.u[i + 1] - track.u[i]) * t, dist: distAt(i) + STEP_M * t };
    }
  }
  return best && best.d <= HASH_CELL ? best : null;
}

/** Gelaendehoehe u. Mit `fine` wird das Gelaende an die Fahrbahn herangezogen (nur Stufe 0). */
function height(e, n, fine) {
  const h = rawHeight(e, n) - U0;
  if (!fine) return h;
  const r = nearRoute(e, n);
  if (!r) return h;
  const half = surfaceAt(r.dist).widthM / 2;
  const w = smoothstep(half + 2.0, half + 28, r.d);
  return (r.u - 0.07) * (1 - w) + h * w;
}

const forestMask = (e, n) => fbm(e / 520 + 11, n / 520 + 5, 3);
const isForest = (e, n) => forestMask(e, n) > 0.56;

/** Bodenfarbe wie ein (sehr schlichtes) Luftbild, sRGB 0..255. */
function groundColor(e, n, pxM) {
  const warp = 40 * (valueNoise(e / 300, n / 300) - 0.5);
  const fx = Math.floor((e + warp) / 230), fy = Math.floor((n - warp) / 170);
  const t = hash2(fx + 91, fy - 17);
  let c = t < 0.5 ? [86, 118, 58] : t < 0.7 ? [168, 158, 96] : t < 0.86 ? [70, 104, 52] : [124, 104, 78];
  const tone = 0.9 + 0.2 * hash2(fx, fy);
  c = c.map((v) => v * tone);
  const f = smoothstep(0.53, 0.58, forestMask(e, n));
  if (f > 0) c = c.map((v, i) => v * (1 - f) + [44, 68, 40][i] * f);
  const hh = rawHeight(e, n) - U0;
  const rock = smoothstep(190, 300, hh);
  if (rock > 0) c = c.map((v, i) => v * (1 - rock * 0.6) + [118, 120, 108][i] * rock * 0.6);
  const pond = Math.hypot(e - POND.e, n - POND.n);
  if (pond < POND.r + 6) c = c.map((v, i) => v * 0.55 + [70, 80, 60][i] * 0.45);
  const r = nearRoute(e, n);
  if (r) {
    const s = surfaceAt(r.dist);
    const road = 1 - smoothstep(s.widthM / 2 - 0.2, s.widthM / 2 + Math.max(0.6, pxM), r.d);
    const rc = s.kind === 'GRAVEL' ? [150, 138, 116] : [104, 104, 106];
    if (road > 0) c = c.map((v, i) => v * (1 - road) + rc[i] * road);
  }
  // Koernung: grob (Bewuchs) und fein (Pixelrauschen)
  const g = 0.86 + 0.2 * fbm(e / 14, n / 14, 3) + 0.08 * (hash2(Math.floor(e / pxM), Math.floor(n / pxM)) - 0.5);
  return c.map((v) => Math.min(255, Math.max(0, v * g)));
}

// ---------------------------------------------------------------------------
// PNG (8 Bit RGB oder RGBA, ohne Filter)
// ---------------------------------------------------------------------------

function png(width, height, channels, pixels) {
  const stride = width * channels;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;
    Buffer.from(pixels.buffer, pixels.byteOffset + y * stride, stride).copy(raw, y * (stride + 1) + 1);
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(zlib.crc32(body) >>> 0);
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = channels === 4 ? 6 : 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 6 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function terrainTexture(level, ix, iy) {
  const c = cellM(level), N = TEXTURE_PX[level], pxM = c / N;
  const px = new Uint8Array(N * N * 3);
  for (let y = 0; y < N; y++) {
    const n = iy * c + c - (y + 0.5) * pxM; // Zeile 0 liegt im Norden
    for (let x = 0; x < N; x++) {
      const col = groundColor(ix * c + (x + 0.5) * pxM, n, pxM);
      const o = (y * N + x) * 3;
      px[o] = col[0]; px[o + 1] = col[1]; px[o + 2] = col[2];
    }
  }
  return png(N, N, 3, px);
}

/** Kachelnde Belagstextur 256². */
function surfaceTexture(key) {
  const N = 256;
  const px = new Uint8Array(N * N * 3);
  const base = key === 'gravel' ? [158, 146, 124] : [98, 98, 101];
  const amp = key === 'gravel' ? 0.34 : 0.16;
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    // periodisch: Rauschen ueber den Rand gefaltet
    const g = 1 + amp * (hash2(x + 3, y + 7) - 0.5) + amp * 0.8 * (hash2(x >> 2, y >> 2) - 0.5) + amp * 0.5 * (hash2((x >> 4) + 50, (y >> 4) + 9) - 0.5);
    const o = (y * N + x) * 3;
    for (let k = 0; k < 3; k++) px[o + k] = Math.min(255, base[k] * g);
  }
  return png(N, N, 3, px);
}

// ---------------------------------------------------------------------------
// Minimaler GLB-Schreiber
// ---------------------------------------------------------------------------

class MeshData {
  constructor(withColor = false) {
    this.pos = []; this.nrm = []; this.uv = []; this.col = withColor ? [] : null; this.idx = [];
  }
  get count() { return this.pos.length / 3; }
  vertex(p, n, uv = [0, 0], col = [1, 1, 1, 1]) {
    this.pos.push(p[0], p[1], p[2]);
    this.nrm.push(n[0], n[1], n[2]);
    this.uv.push(uv[0], uv[1]);
    if (this.col) this.col.push(col[0], col[1], col[2], col[3] ?? 1);
    return this.count - 1;
  }
  tri(a, b, c) { this.idx.push(a, b, c); }
  quad(a, b, c, d) { this.idx.push(a, b, c, a, c, d); }
}

class Glb {
  constructor() {
    this.json = {
      asset: { version: '2.0', generator: 'trailscape mockworld.mjs' },
      scene: 0, scenes: [{ nodes: [] }], nodes: [], meshes: [], materials: [], accessors: [], bufferViews: [],
    };
    this.parts = [];
    this.length = 0;
    this.triangles = 0;
    this.textureBytes = 0;
    this.materialIndex = new Map();
  }
  view(buf, target) {
    const pad = (4 - (this.length % 4)) % 4;
    if (pad) { this.parts.push(Buffer.alloc(pad)); this.length += pad; }
    const v = { buffer: 0, byteOffset: this.length, byteLength: buf.length };
    if (target) v.target = target;
    this.parts.push(buf);
    this.length += buf.length;
    this.json.bufferViews.push(v);
    return this.json.bufferViews.length - 1;
  }
  accessor(typed, type, componentType, extra = {}, target = 34962) {
    const bufferView = this.view(Buffer.from(typed.buffer, typed.byteOffset, typed.byteLength), target);
    const size = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4 }[type];
    this.json.accessors.push({ bufferView, componentType, count: typed.length / size, type, ...extra });
    return this.json.accessors.length - 1;
  }
  texture(pngBuffer, name, repeat) {
    this.json.images ??= [];
    this.json.textures ??= [];
    this.json.samplers ??= [];
    this.json.images.push({ name, mimeType: 'image/png', bufferView: this.view(pngBuffer) });
    // Luftbild: am Rand klemmen; Belag: kacheln. Trilinear mit Mipmaps.
    this.json.samplers.push({ magFilter: 9729, minFilter: 9987, wrapS: repeat ? 10497 : 33071, wrapT: repeat ? 10497 : 33071 });
    this.json.textures.push({ source: this.json.images.length - 1, sampler: this.json.samplers.length - 1 });
    const w = pngBuffer.readUInt32BE(16), h = pngBuffer.readUInt32BE(20);
    this.textureBytes += Math.round((w * h * 4 * 4) / 3);
    return this.json.textures.length - 1;
  }
  material(name, def) {
    if (this.materialIndex.has(name)) return this.materialIndex.get(name);
    const { baseColorFactor, baseColorTexture, roughness = 0.9, ...rest } = def;
    const pbr = { metallicFactor: 0, roughnessFactor: roughness };
    if (baseColorFactor) pbr.baseColorFactor = baseColorFactor;
    if (baseColorTexture !== undefined) pbr.baseColorTexture = { index: baseColorTexture };
    this.json.materials.push({ name, pbrMetallicRoughness: pbr, ...rest });
    this.materialIndex.set(name, this.json.materials.length - 1);
    return this.json.materials.length - 1;
  }
  primitive(data, material) {
    const pos = new Float32Array(data.pos);
    const min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
    for (let i = 0; i < pos.length; i++) { const k = i % 3; if (pos[i] < min[k]) min[k] = pos[i]; if (pos[i] > max[k]) max[k] = pos[i]; }
    const attributes = {
      POSITION: this.accessor(pos, 'VEC3', 5126, { min, max }),
      NORMAL: this.accessor(new Float32Array(data.nrm), 'VEC3', 5126),
      TEXCOORD_0: this.accessor(new Float32Array(data.uv), 'VEC2', 5126),
    };
    if (data.col) {
      const col = new Uint8Array(data.col.map((v) => Math.round(Math.min(1, Math.max(0, v)) * 255)));
      attributes.COLOR_0 = this.accessor(col, 'VEC4', 5121, { normalized: true });
    }
    const big = data.count > 65535;
    const idx = big ? new Uint32Array(data.idx) : new Uint16Array(data.idx);
    this.triangles += data.idx.length / 3;
    return { attributes, indices: this.accessor(idx, 'SCALAR', big ? 5125 : 5123, {}, 34963), material, mode: 4 };
  }
  /** Knoten mit Netz aus `[MeshData, materialIndex]`-Paaren; leere Netze werden uebersprungen. */
  node(name, prims, parent) {
    const usable = prims.filter(([d]) => d.idx.length > 0);
    const node = { name };
    if (usable.length) {
      this.json.meshes.push({ name, primitives: usable.map(([d, m]) => this.primitive(d, m)) });
      node.mesh = this.json.meshes.length - 1;
    }
    this.json.nodes.push(node);
    const index = this.json.nodes.length - 1;
    if (parent === undefined) this.json.scenes[0].nodes.push(index);
    else (this.json.nodes[parent].children ??= []).push(index);
    return index;
  }
  finish() {
    const bin = Buffer.concat(this.parts);
    const binPad = (4 - (bin.length % 4)) % 4;
    this.json.buffers = [{ byteLength: bin.length + binPad }];
    let json = Buffer.from(JSON.stringify(this.json), 'utf8');
    const jsonPad = (4 - (json.length % 4)) % 4;
    json = Buffer.concat([json, Buffer.alloc(jsonPad, 0x20)]);
    const header = Buffer.alloc(12);
    header.writeUInt32LE(0x46546c67, 0);
    header.writeUInt32LE(2, 4);
    header.writeUInt32LE(12 + 8 + json.length + 8 + bin.length + binPad, 8);
    const jh = Buffer.alloc(8);
    jh.writeUInt32LE(json.length, 0);
    jh.writeUInt32LE(0x4e4f534a, 4);
    const bh = Buffer.alloc(8);
    bh.writeUInt32LE(bin.length + binPad, 0);
    bh.writeUInt32LE(0x004e4942, 4);
    return Buffer.concat([header, jh, json, bh, bin, Buffer.alloc(binPad)]);
  }
}

// ---------------------------------------------------------------------------
// Geometrie
// ---------------------------------------------------------------------------

/**
 * Gelaendeflaeche ueber `[e0, e0+size] × [n0, n0+size]` mit `cells²` Feldern und
 * ringsum einer Schuerze. Eckpunkte relativ zum Anker; UV ueber die ganze Kachel.
 */
function terrainPatch(e0, n0, size, cells, tile, fine, skirtM) {
  const mesh = new MeshData();
  const step = size / cells;
  const eps = Math.max(0.5, step / 4);
  const idxOf = (i, j) => j * (cells + 1) + i;
  let minU = Infinity, maxU = -Infinity;
  for (let j = 0; j <= cells; j++) for (let i = 0; i <= cells; i++) {
    const e = e0 + i * step, n = n0 + j * step;
    const u = height(e, n, fine);
    minU = Math.min(minU, u); maxU = Math.max(maxU, u);
    const he = (height(e + eps, n, fine) - height(e - eps, n, fine)) / (2 * eps);
    const hn = (height(e, n + eps, fine) - height(e, n - eps, fine)) / (2 * eps);
    const l = Math.hypot(he, 1, hn);
    mesh.vertex(
      [e - tile.anchorE, u - tile.anchorU, -(n - tile.anchorN)],
      [-he / l, 1 / l, hn / l],
      [(e - tile.e0) / tile.c, (tile.n0 + tile.c - n) / tile.c],
    );
  }
  for (let j = 0; j < cells; j++) for (let i = 0; i < cells; i++) {
    mesh.quad(idxOf(i, j), idxOf(i + 1, j), idxOf(i + 1, j + 1), idxOf(i, j + 1));
  }
  // Schuerze: Rand gegen den Uhrzeigersinn (von oben) ablaufen, Flaechen zeigen nach aussen
  const rim = [];
  for (let i = 0; i < cells; i++) rim.push(idxOf(i, 0));
  for (let j = 0; j < cells; j++) rim.push(idxOf(cells, j));
  for (let i = cells; i > 0; i--) rim.push(idxOf(i, cells));
  for (let j = cells; j > 0; j--) rim.push(idxOf(0, j));
  const bottom = rim.map((v) => mesh.vertex(
    [mesh.pos[v * 3], mesh.pos[v * 3 + 1] - skirtM, mesh.pos[v * 3 + 2]],
    [mesh.nrm[v * 3], mesh.nrm[v * 3 + 1], mesh.nrm[v * 3 + 2]],
    [mesh.uv[v * 2], mesh.uv[v * 2 + 1]],
  ));
  for (let k = 0; k < rim.length; k++) {
    const k2 = (k + 1) % rim.length;
    mesh.quad(rim[k], bottom[k], bottom[k2], rim[k2]);
  }
  return { mesh, minU: minU - skirtM, maxU };
}

/** Quader/Prismen und Rotationskoerper in ein MeshData, Punkte in ENU relativ zum Anker. */
function pushFace(mesh, tile, pts, color) {
  // pts: Ecken in ENU [e, n, u], gegen den Uhrzeigersinn von aussen gesehen
  const P = pts.map(([e, n, u]) => [e - tile.anchorE, u - tile.anchorU, -(n - tile.anchorN)]);
  const a = [P[1][0] - P[0][0], P[1][1] - P[0][1], P[1][2] - P[0][2]];
  const b = [P[2][0] - P[0][0], P[2][1] - P[0][1], P[2][2] - P[0][2]];
  const nx = a[1] * b[2] - a[2] * b[1], ny = a[2] * b[0] - a[0] * b[2], nz = a[0] * b[1] - a[1] * b[0];
  const l = Math.hypot(nx, ny, nz) || 1;
  const ids = P.map((p) => mesh.vertex(p, [nx / l, ny / l, nz / l], [0, 0], color));
  for (let k = 1; k < ids.length - 1; k++) mesh.tri(ids[0], ids[k], ids[k + 1]);
}

function addBuilding(walls, roof, tile, cx, cy, heading, w, d, h, wallColor, roofColor) {
  const s = Math.sin(heading), c = Math.cos(heading);
  // lokale Achsen: x quer (rechts), y laengs (Kurs)
  const at = (x, y) => [cx + x * c + y * s, cy - x * s + y * c];
  const corners = [at(-w / 2, -d / 2), at(w / 2, -d / 2), at(w / 2, d / 2), at(-w / 2, d / 2)];
  const hs = corners.map(([e, n]) => height(e, n, true));
  const base = Math.min(...hs) - 0.6, eave = Math.max(...hs) + h, ridge = eave + w * 0.32;
  const shade = (col, f) => [col[0] * f, col[1] * f, col[2] * f, 1];
  for (let k = 0; k < 4; k++) {
    const p = corners[k], q = corners[(k + 1) % 4];
    // Waende: unten etwas dunkler (Sockel) ueber die Eckfarbe
    const P = [[p[0], p[1], base], [q[0], q[1], base], [q[0], q[1], eave], [p[0], p[1], eave]];
    const A = P.map(([e, n, u]) => [e - tile.anchorE, u - tile.anchorU, -(n - tile.anchorN)]);
    const a = [A[1][0] - A[0][0], 0, A[1][2] - A[0][2]];
    const nl = Math.hypot(a[0], a[2]) || 1;
    const nrm = [-a[2] / nl, 0, a[0] / nl]; // nach aussen (rechts der Umlaufrichtung)
    const ids = A.map((pt, i) => walls.vertex(pt, nrm, [0, 0], shade(wallColor, i < 2 ? 0.78 : 1)));
    walls.quad(ids[0], ids[1], ids[2], ids[3]);
  }
  // Satteldach, First laengs
  const r0 = at(0, -d / 2), r1 = at(0, d / 2);
  const o = 0.5; // Dachueberstand
  const e0 = at(-w / 2 - o, -d / 2 - o), e1 = at(w / 2 + o, -d / 2 - o), e2 = at(w / 2 + o, d / 2 + o), e3 = at(-w / 2 - o, d / 2 + o);
  const ro0 = at(0, -d / 2 - o), ro1 = at(0, d / 2 + o);
  const low = eave - 0.15;
  pushFace(roof, tile, [[e1[0], e1[1], low], [e2[0], e2[1], low], [ro1[0], ro1[1], ridge], [ro0[0], ro0[1], ridge]], roofColor);
  pushFace(roof, tile, [[e3[0], e3[1], low], [e0[0], e0[1], low], [ro0[0], ro0[1], ridge], [ro1[0], ro1[1], ridge]], roofColor);
  // Giebeldreiecke
  pushFace(walls, tile, [[corners[0][0], corners[0][1], eave], [corners[1][0], corners[1][1], eave], [r0[0], r0[1], ridge]], wallColor);
  pushFace(walls, tile, [[corners[2][0], corners[2][1], eave], [corners[3][0], corners[3][1], eave], [r1[0], r1[1], ridge]], wallColor);
}

function addTree(trunk, leaves, tile, e, n, conifer, scale, tint) {
  const u = height(e, n, true) - 0.2;
  const P = (x, y, z) => [e + x - tile.anchorE, u + y - tile.anchorU, -(n + z - tile.anchorN)];
  const trunkH = (conifer ? 1.6 : 2.6) * scale, trunkR = 0.22 * scale;
  const SEG = 6;
  const tc = [0.30, 0.22, 0.15, 1];
  const ring = (mesh, y, r, col, ny = 0) => {
    const ids = [];
    for (let k = 0; k < SEG; k++) {
      const a = (k / SEG) * 2 * Math.PI;
      const l = Math.hypot(1, ny);
      ids.push(mesh.vertex(P(Math.cos(a) * r, y, Math.sin(a) * r), [Math.cos(a) / l, ny / l, -Math.sin(a) / l], [0, 0], col));
    }
    return ids;
  };
  // Verbindet zwei Ringe; Umlauf so, dass die Flaechen nach aussen zeigen
  const band = (mesh, lo, hi) => {
    for (let k = 0; k < SEG; k++) {
      const k2 = (k + 1) % SEG;
      mesh.quad(lo[k], lo[k2], hi[k2], hi[k]);
    }
  };
  band(trunk, ring(trunk, 0, trunkR * 1.25, tc), ring(trunk, trunkH + 0.5, trunkR, tc));
  const shade = (f) => [tint[0] * f, tint[1] * f, tint[2] * f, 1];
  if (conifer) {
    // zwei gestufte Kegel
    const tiers = [[trunkH, 2.3 * scale, 4.2 * scale], [trunkH + 2.6 * scale, 1.6 * scale, 3.6 * scale]];
    for (const [y0, r, h] of tiers) {
      const lo = ring(leaves, y0, r, shade(0.72), r / h);
      const tip = leaves.vertex(P(0, y0 + h, 0), [0, 1, 0], [0, 0], shade(1.05));
      for (let k = 0; k < SEG; k++) leaves.tri(lo[k], lo[(k + 1) % SEG], tip);
    }
  } else {
    // Krone als gestauchte Kugel, unten dunkler (vorgetaeuschte Eigenverschattung)
    const R = 2.5 * scale, H = 2.9 * scale, cy = trunkH + H * 0.75;
    const rows = [[-0.85, 0.52], [-0.35, 0.95], [0.25, 0.98], [0.75, 0.62]];
    const rings = rows.map(([y, r]) => ring(leaves, cy + y * H, r * R * (0.9 + 0.2 * rnd()), shade(0.74 + 0.18 * (y + 1)), y * 1.2));
    for (let k = 0; k < rings.length - 1; k++) band(leaves, rings[k], rings[k + 1]);
    const bottom = leaves.vertex(P(0, cy - H, 0), [0, -1, 0], [0, 0], shade(0.66));
    const top = leaves.vertex(P(0, cy + H, 0), [0, 1, 0], [0, 0], shade(1.12));
    for (let k = 0; k < SEG; k++) {
      leaves.tri(rings[0][(k + 1) % SEG], rings[0][k], bottom);
      leaves.tri(rings[3][k], rings[3][(k + 1) % SEG], top);
    }
  }
}

// ---------------------------------------------------------------------------
// Inhalt der Welt
// ---------------------------------------------------------------------------

const tileOf = (e, n) => `${Math.floor(e / 256)}/${Math.floor(n / 256)}`;
const content = new Map(); // "ix/iy" (Stufe 0) → { buildings: [], trees: [], posts: [], segments: [] }
const bucket = (e, n) => {
  const k = tileOf(e, n);
  if (!content.has(k)) content.set(k, { buildings: [], trees: [], posts: [], segments: [], pond: false });
  return content.get(k);
};

// Fahrbahnsegmente und Leitpfosten
const trackNormal = (i) => {
  const m = track.count - 1;
  const a = (((i - 1) % m) + m) % m, b = (i + 1) % m;
  const de = track.e[b] - track.e[a], dn = track.n[b] - track.n[a];
  const l = Math.hypot(de, dn) || 1;
  return { fe: de / l, fn: dn / l, re: dn / l, rn: -de / l }; // vorwaerts, rechts
};
for (let i = 0; i < track.count - 1; i++) {
  bucket((track.e[i] + track.e[i + 1]) / 2, (track.n[i] + track.n[i + 1]) / 2).segments.push(i);
  if (i % 10 === 0) {
    const t = trackNormal(i), half = surfaceAt(distAt(i)).widthM / 2 + 0.9;
    for (const side of [-1, 1]) bucket(track.e[i], track.n[i]).posts.push({ e: track.e[i] + t.re * half * side, n: track.n[i] + t.rn * half * side, u: track.u[i] - 0.05 });
  }
}

// Zwei Doerfer an der Strecke
const WALLS = [[0.86, 0.84, 0.78], [0.80, 0.74, 0.62], [0.62, 0.36, 0.28], [0.74, 0.76, 0.78], [0.88, 0.82, 0.66]];
const ROOFS = [[0.48, 0.22, 0.16], [0.30, 0.29, 0.30], [0.42, 0.26, 0.20]];
const houses = [];
for (const [from, to] of [[180, 620], [1120, 1420], [2250, 2500]]) {
  for (let d = from; d < to; d += 26 + rnd() * 18) {
    for (const side of [-1, 1]) {
      if (rnd() < 0.25) continue;
      const i = Math.round(d / STEP_M), t = trackNormal(i);
      const off = (14 + rnd() * 16) * side;
      const e = track.e[i] + t.re * off, n = track.n[i] + t.rn * off;
      const h = { e, n, heading: Math.atan2(t.fe, t.fn) + (rnd() - 0.5) * 0.3 + (rnd() < 0.5 ? Math.PI / 2 : 0), w: 7 + rnd() * 4, d: 9 + rnd() * 6, h: 3.2 + rnd() * 3.2, wall: WALLS[Math.floor(rnd() * WALLS.length)], roof: ROOFS[Math.floor(rnd() * ROOFS.length)] };
      houses.push(h);
      bucket(e, n).buildings.push(h);
    }
  }
}
const nearHouse = (e, n) => houses.some((h) => Math.hypot(h.e - e, h.n - n) < 12);

// Wald (dort, wo auch die Bodentextur dunkel ist) im Korridor, dazu Einzelbaeume
let treeCount = 0;
const TREE_GRID = 10;
for (let gx = -80; gx <= 80; gx++) for (let gy = -80; gy <= 80; gy++) {
  const e = (gx + rnd() * 0.8) * TREE_GRID, n = (gy + rnd() * 0.8) * TREE_GRID;
  const r = nearRoute(e, n);
  const forest = isForest(e, n);
  const closeEnough = r ? true : forest && rnd() < 0.5 && Math.hypot(e - 60, n + 40) < 760;
  if (!closeEnough) continue;
  if (r && r.d < surfaceAt(r.dist).widthM / 2 + 3.5) continue;
  if (!forest && rnd() > 0.035) continue; // lockere Einzelbaeume in der Flur
  if (nearHouse(e, n) || Math.hypot(e - POND.e, n - POND.n) < POND.r + 4) continue;
  const conifer = forest ? hash2(Math.floor(e / 90), Math.floor(n / 90)) < 0.45 : rnd() < 0.15;
  const g = 0.75 + rnd() * 0.5;
  const tint = conifer ? [0.13 * g, 0.24 * g, 0.12 * g] : [0.17 * g, 0.36 * g + 0.02, 0.08 * g];
  bucket(e, n).trees.push({ e, n, conifer, scale: 0.8 + rnd() * 0.7, tint });
  treeCount++;
}
bucket(POND.e, POND.n).pond = true;

// ---------------------------------------------------------------------------
// Kacheln bauen
// ---------------------------------------------------------------------------

rmSync(OUT, { recursive: true, force: true });
mkdirSync(join(OUT, 'tiles'), { recursive: true });

const asphaltPng = surfaceTexture('asphalt');
const gravelPng = surfaceTexture('gravel');
const tiles = [];

function tileFrame(level, ix, iy) {
  const c = cellM(level);
  const anchorE = (ix + 0.5) * c, anchorN = (iy + 0.5) * c;
  return { level, ix, iy, c, e0: ix * c, n0: iy * c, anchorE, anchorN, anchorU: Math.round(height(anchorE, anchorN, false)) };
}

function buildTile(level, ix, iy, show) {
  const tile = tileFrame(level, ix, iy);
  const glb = new Glb();
  const matTerrain = glb.material('mat_terrain', { baseColorTexture: glb.texture(terrainTexture(level, ix, iy), 'ground', false), roughness: 0.95 });
  let minU = Infinity, maxU = -Infinity;
  const track_ = (r) => { minU = Math.min(minU, r.minU); maxU = Math.max(maxU, r.maxU); return r.mesh; };

  if (level === 0) {
    glb.node('terrain', [[track_(terrainPatch(tile.e0, tile.n0, tile.c, 64, tile, true, 6)), matTerrain]]);
  } else {
    const terrain = glb.node('terrain', []);
    const sub = tile.c / LEVEL_FACTOR;
    const cells = 8;
    for (let j = 0; j < LEVEL_FACTOR; j++) for (let i = 0; i < LEVEL_FACTOR; i++) {
      const patch = terrainPatch(tile.e0 + i * sub, tile.n0 + j * sub, sub, cells, tile, false, level === 1 ? 14 : 30);
      glb.node(`terrain_sub_${i}_${j}`, [[track_(patch), matTerrain]], terrain);
    }
  }

  const c = level === 0 ? content.get(`${ix}/${iy}`) : null;
  if (c) {
    // Fahrbahn: Band je Belag, Raender mit Alpha-Uebergang
    if (c.segments.length) {
      const bands = new Map();
      const edges = new MeshData(true);
      for (const i of c.segments) {
        const s = surfaceAt(distAt(i) + STEP_M / 2);
        if (!bands.has(s.key)) bands.set(s.key, new MeshData());
        const band = bands.get(s.key);
        const half = s.widthM / 2, edgeW = 1.4;
        const row = (k) => {
          const t = trackNormal(k % (track.count - 1));
          const at = (off, du) => [track.e[k] + t.re * off - tile.anchorE, track.u[k] + du - tile.anchorU, -(track.n[k] + t.rn * off - tile.anchorN)];
          return { t, at, v: distAt(k) / 2.5 };
        };
        const a = row(i), b = row(i + 1);
        const up = [0, 1, 0];
        const v = [
          band.vertex(a.at(-half, 0), up, [0, a.v]), band.vertex(a.at(half, 0), up, [s.widthM / 2.5, a.v]),
          band.vertex(b.at(half, 0), up, [s.widthM / 2.5, b.v]), band.vertex(b.at(-half, 0), up, [0, b.v]),
        ];
        band.quad(v[0], v[1], v[2], v[3]);
        const ec = s.kind === 'GRAVEL' ? [0.36, 0.33, 0.25] : [0.33, 0.31, 0.24];
        for (const side of [-1, 1]) {
          const i0 = edges.vertex(a.at(side * half, 0.0), up, [0, 0], [...ec, 1]);
          const o0 = edges.vertex(a.at(side * (half + edgeW), -0.02), up, [0, 0], [...ec, 0]);
          const o1 = edges.vertex(b.at(side * (half + edgeW), -0.02), up, [0, 0], [...ec, 0]);
          const i1 = edges.vertex(b.at(side * half, 0.0), up, [0, 0], [...ec, 1]);
          if (side > 0) edges.quad(i0, o0, o1, i1); else edges.quad(i0, i1, o1, o0);
        }
      }
      const prims = [];
      for (const [key, band] of bands) {
        prims.push([band, glb.material(`mat_route_${key}`, { baseColorTexture: glb.texture(key === 'gravel' ? gravelPng : asphaltPng, key, true), roughness: key === 'gravel' ? 0.95 : 0.85 })]);
      }
      prims.push([edges, glb.material('x_mat_route_edge', { roughness: 0.95, alphaMode: 'BLEND' })]);
      glb.node('route', prims);
    }
    if (c.buildings.length) {
      const walls = new MeshData(true), roof = new MeshData(true);
      for (const h of c.buildings) addBuilding(walls, roof, tile, h.e, h.n, h.heading, h.w, h.d, h.h, [...h.wall, 1], [...h.roof, 1]);
      glb.node('buildings', [[walls, glb.material('mat_building_wall', { roughness: 0.9 })], [roof, glb.material('mat_building_roof', { roughness: 0.8 })]]);
      maxU = Math.max(maxU, ...c.buildings.map((h) => height(h.e, h.n, true) + h.h + 6));
    }
    if (c.trees.length) {
      const trunk = new MeshData(true), leaves = new MeshData(true);
      for (const t of c.trees) addTree(trunk, leaves, tile, t.e, t.n, t.conifer, t.scale, t.tint);
      glb.node('trees', [
        [trunk, glb.material('mat_tree_trunk', { roughness: 0.95 })],
        [leaves, glb.material('mat_tree_leaves', { roughness: 0.9, alphaMode: 'MASK', alphaCutoff: 0.5, doubleSided: true })],
      ]);
      maxU = Math.max(maxU, ...c.trees.map((t) => height(t.e, t.n, true) + 12));
    }
    if (c.pond) {
      const water = new MeshData();
      const level_ = height(POND.e, POND.n, false) + 2.2;
      const centre = water.vertex([POND.e - tile.anchorE, level_ - tile.anchorU, -(POND.n - tile.anchorN)], [0, 1, 0]);
      const rim = [];
      for (let k = 0; k < 32; k++) {
        const a = (k / 32) * 2 * Math.PI;
        rim.push(water.vertex([POND.e + Math.cos(a) * POND.r * 1.3 - tile.anchorE, level_ - tile.anchorU, -(POND.n + Math.sin(a) * POND.r * 1.3 - tile.anchorN)], [0, 1, 0]));
      }
      for (let k = 0; k < 32; k++) water.tri(centre, rim[k], rim[(k + 1) % 32]);
      glb.node('water', [[water, glb.material('mat_water', { baseColorFactor: [0.07, 0.13, 0.15, 1], roughness: 0.6 })]]);
    }
    if (c.posts.length) {
      const posts = new MeshData(true);
      for (const p of c.posts) {
        const r = 0.06, h = 1.0;
        const col = [0.9, 0.9, 0.88, 1];
        const q = (pts) => pushFace(posts, tile, pts, col);
        const [e, n, u] = [p.e, p.n, p.u];
        q([[e - r, n - r, u], [e + r, n - r, u], [e + r, n - r, u + h], [e - r, n - r, u + h]]);
        q([[e + r, n - r, u], [e + r, n + r, u], [e + r, n + r, u + h], [e + r, n - r, u + h]]);
        q([[e + r, n + r, u], [e - r, n + r, u], [e - r, n + r, u + h], [e + r, n + r, u + h]]);
        q([[e - r, n + r, u], [e - r, n - r, u], [e - r, n - r, u + h], [e - r, n + r, u + h]]);
        q([[e - r, n - r, u + h], [e + r, n - r, u + h], [e + r, n + r, u + h], [e - r, n + r, u + h]]);
      }
      glb.node('props', [[posts, glb.material('x_mat_post', { roughness: 0.8 })]]);
    }
  }

  const id = `L${level}_${ix}_${iy}`;
  const file = `tiles/${id}.glb`;
  const data = glb.finish();
  writeFileSync(join(OUT, file), data);
  tiles.push({
    id, level, ix, iy, file, bytes: data.length,
    anchor: [tile.anchorE, tile.anchorN, tile.anchorU],
    minU: Math.round(minU * 100) / 100, maxU: Math.round(maxU * 100) / 100,
    show, textureBytes: glb.textureBytes, triangles: glb.triangles,
  });
}

// Stufe 0: gebraucht, solange die Kachel naeher als 330 m an der Stelle des Fahrers liegt.
const L0_RANGE = 330;
const samples = [];
for (let i = 0; i < track.count; i++) samples.push({ d: distAt(i), e: track.e[i], n: track.n[i] });
function showIntervals(e0, n0, c) {
  const out = [];
  let from = null, last = null;
  for (const s of samples) {
    const dx = Math.max(e0 - s.e, 0, s.e - (e0 + c)), dy = Math.max(n0 - s.n, 0, s.n - (n0 + c));
    if (Math.hypot(dx, dy) <= L0_RANGE) { if (from === null) from = s.d; last = s.d; } else if (from !== null) {
      out.push([Math.max(0, from - STEP_M), Math.min(track.lengthM, last + STEP_M)]);
      from = null;
    }
  }
  if (from !== null) out.push([Math.max(0, from - STEP_M), track.lengthM]);
  return out;
}

const t0 = Date.now();
for (let ix = -6; ix <= 6; ix++) for (let iy = -6; iy <= 6; iy++) {
  const show = showIntervals(ix * 256, iy * 256, 256);
  if (show.length) buildTile(0, ix, iy, show);
}
const everywhere = [[0, track.lengthM]];
for (let ix = -3; ix <= 2; ix++) for (let iy = -3; iy <= 2; iy++) buildTile(1, ix, iy, everywhere);
for (let ix = -3; ix <= 2; ix++) for (let iy = -3; iy <= 2; iy++) buildTile(2, ix, iy, everywhere);

// Budget: Maximum ueber alle Stellen
let maxTextureBytes = 0, maxTriangles = 0;
for (const s of samples) {
  let tex = 0, tri = 0;
  for (const t of tiles) if (t.show.some(([a, b]) => s.d >= a && s.d <= b)) { tex += t.textureBytes; tri += t.triangles; }
  maxTextureBytes = Math.max(maxTextureBytes, tex);
  maxTriangles = Math.max(maxTriangles, tri);
}

const r2 = (v) => Math.round(v * 100) / 100;
const manifest = {
  format: 1,
  worldId: 'mock-1',
  builder: 'mockworld.mjs/1',
  createdAtMs: Date.now(),
  quality: 'MEDIUM',
  routeId: 'mock-route',
  routeName: 'Mock-Runde',
  origin: { lat: 51.2, lon: 7.6, eleM: Math.round((U0 + 240) * 10) / 10 },
  bounds: {
    minE: Math.min(...tiles.map((t) => t.ix * cellM(t.level))), minN: Math.min(...tiles.map((t) => t.iy * cellM(t.level))),
    maxE: Math.max(...tiles.map((t) => (t.ix + 1) * cellM(t.level))), maxN: Math.max(...tiles.map((t) => (t.iy + 1) * cellM(t.level))),
    minU: Math.min(...tiles.map((t) => t.minU)), maxU: Math.max(...tiles.map((t) => t.maxU)),
  },
  track: {
    lengthM: track.lengthM, stepM: STEP_M, loop: true,
    e: track.e.map(r2), n: track.n.map(r2), u: track.u.map(r2),
    surfaces: SURFACES.map(({ key, ...s }) => s),
    source: 'NONE',
  },
  levels: [0, 1, 2].map((level) => ({ level, cellM: cellM(level), texturePx: TEXTURE_PX[level] })),
  tiles,
  budget: { maxTextureBytes, maxTriangles, diskBytes: 0 },
  attribution: [
    { source: 'ELEVATION', text: 'Mock-Welt: erfundenes Gelaende, keine Geodaten', license: '', url: '' },
    { source: 'VECTOR', text: '© Niemand (Selbsttest des Betrachters)', license: '', url: '' },
  ],
};
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify(manifest));
const disk = readdirSync(join(OUT, 'tiles')).reduce((s, f) => s + statSync(join(OUT, 'tiles', f)).size, 0) + statSync(join(OUT, 'manifest.json')).size;
manifest.budget.diskBytes = disk;
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify(manifest));

// ---------------------------------------------------------------------------
// Testmodelle: Fahrer und Geist aus Quadern, mit den Animationen `pedal` und
// `roll` (je 1,0 s) nach WorldModels — nur damit der Betrachter den Weg ueber
// echte Modelle (Ausrichtung, Phasen) pruefen kann. Blick nach +Z, Ursprung am
// Boden zwischen den Raedern.
// ---------------------------------------------------------------------------

function boxMesh(w, h, d, color) {
  const m = new MeshData(true);
  const x = w / 2, y = h / 2, z = d / 2;
  const face = (pts, n) => {
    const ids = pts.map((p) => m.vertex(p, n, [0, 0], color));
    m.quad(ids[0], ids[1], ids[2], ids[3]);
  };
  face([[x, -y, z], [x, -y, -z], [x, y, -z], [x, y, z]], [1, 0, 0]);
  face([[-x, -y, -z], [-x, -y, z], [-x, y, z], [-x, y, -z]], [-1, 0, 0]);
  face([[-x, y, z], [x, y, z], [x, y, -z], [-x, y, -z]], [0, 1, 0]);
  face([[-x, -y, -z], [x, -y, -z], [x, -y, z], [-x, -y, z]], [0, -1, 0]);
  face([[-x, -y, z], [x, -y, z], [x, y, z], [-x, y, z]], [0, 0, 1]);
  face([[x, -y, -z], [-x, -y, -z], [-x, y, -z], [x, y, -z]], [0, 0, -1]);
  return m;
}

function buildActor(ghost) {
  const glb = new Glb();
  const mat = (name, rgb) => glb.material(name, ghost
    ? { baseColorFactor: [0.75, 0.85, 1.0, 0.4], roughness: 0.9, alphaMode: 'BLEND' }
    : { baseColorFactor: [...rgb, 1], roughness: 0.8 });
  const white = [1, 1, 1, 1];
  const part = (name, size, at, material, parent, rotX = 0) => {
    const idx = glb.node(name, [[boxMesh(...size, white), material]], parent);
    glb.json.nodes[idx].translation = at;
    if (rotX) glb.json.nodes[idx].rotation = [Math.sin(rotX / 2), 0, 0, Math.cos(rotX / 2)];
    return idx;
  };
  const frame = mat('frame', [0.75, 0.12, 0.10]);
  const rubber = mat('rubber', [0.05, 0.05, 0.05]);
  const jersey = mat('jersey', [0.10, 0.30, 0.65]);
  const skin = mat('skin', [0.75, 0.55, 0.45]);
  const root = glb.node('rider', []);
  const R = 0.35; // Radius 0,35 m → Abrollumfang 2,2 m
  // Raeder als Speichenkreuz, damit man die Drehung sieht
  const wheels = [];
  for (const [name, z] of [['wheel_front', 0.52], ['wheel_rear', -0.52]]) {
    const w = glb.node(name, [[boxMesh(0.04, 2 * R, 0.07, white), rubber]], root);
    glb.json.nodes[w].translation = [0, R, z];
    part(`${name}_spoke`, [0.04, 0.07, 2 * R], [0, 0, 0], rubber, w);
    wheels.push(w);
  }
  part('tube_top', [0.04, 0.04, 0.60], [0, 0.82, 0.0], frame, root);
  part('tube_down', [0.04, 0.04, 0.66], [0, 0.56, 0.22], frame, root, -0.75);
  part('tube_seat', [0.04, 0.62, 0.04], [0, 0.60, -0.22], frame, root);
  part('fork', [0.04, 0.58, 0.04], [0, 0.62, 0.50], frame, root, 0.25);
  part('bar', [0.42, 0.03, 0.03], [0, 0.95, 0.46], rubber, root);
  const crank = glb.node('crank', [[boxMesh(0.03, 0.34, 0.03, white), rubber]], root);
  glb.json.nodes[crank].translation = [0, 0.30, -0.08];
  part('pedal_l', [0.10, 0.02, 0.08], [-0.10, 0.17, 0], rubber, crank);
  part('pedal_r', [0.10, 0.02, 0.08], [0.10, -0.17, 0], rubber, crank);
  part('torso', [0.34, 0.56, 0.24], [0, 1.18, 0.02], jersey, root, 0.75);
  part('head', [0.18, 0.22, 0.20], [0, 1.52, 0.32], skin, root);
  part('thigh_l', [0.11, 0.42, 0.12], [-0.10, 0.72, -0.10], rubber, root, -0.5);
  part('thigh_r', [0.11, 0.42, 0.12], [0.10, 0.72, -0.10], rubber, root, 0.4);

  // Animationen: eine volle Umdrehung um die X-Achse in 1,0 s, fuenf Stuetzstellen
  const times = new Float32Array([0, 0.25, 0.5, 0.75, 1.0]);
  const quats = new Float32Array(times.length * 4);
  // q und −q sind dieselbe Drehung; ohne Vorzeichenwechsel liefe die zweite Haelfte rueckwaerts
  times.forEach((t, i) => quats.set([Math.sin(Math.PI * t), 0, 0, Math.cos(Math.PI * t)], i * 4));
  const input = glb.accessor(times, 'SCALAR', 5126, { min: [0], max: [1] }, 0);
  const output = glb.accessor(quats, 'VEC4', 5126, {}, 0);
  const anim = (name, nodes) => ({
    name,
    samplers: [{ input, output, interpolation: 'LINEAR' }],
    channels: nodes.map((node) => ({ sampler: 0, target: { node, path: 'rotation' } })),
  });
  glb.json.animations = [anim('pedal', [crank]), anim('roll', wheels)];
  return glb.finish();
}

mkdirSync(join(OUT, 'models'), { recursive: true });
writeFileSync(join(OUT, 'models', 'rider.glb'), buildActor(false));
writeFileSync(join(OUT, 'models', 'ghost.glb'), buildActor(true));

console.log(`Mock-Welt nach ${OUT}`);
console.log(`  Strecke ${track.lengthM.toFixed(0)} m, ${track.count} Proben, ${tiles.length} Kacheln (${[0, 1, 2].map((l) => tiles.filter((t) => t.level === l).length).join('/')}), ${houses.length} Haeuser, ${treeCount} Baeume`);
console.log(`  Testmodelle (Quader, mit pedal/roll): ${join(OUT, 'models')}`);
console.log(`  Budget: ${(maxTextureBytes / 1048576).toFixed(0)} MB Texturen, ${maxTriangles} Dreiecke gleichzeitig; ${(disk / 1048576).toFixed(1)} MB auf der Platte; ${((Date.now() - t0) / 1000).toFixed(1)} s`);
