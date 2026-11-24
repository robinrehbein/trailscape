// camerarig.mjs — Kameras der App (app/.../indoor/render/CameraRig.kt) fuer die Werkzeuge.
// Zeile fuer Zeile nachgebaut, ohne Daempfung (Standbilder). Koordinaten: Szene = glTF
// relativ zum schwebenden Ursprung (ctx.rel(e, n, u) → [x, y, z]).

const deg = Math.PI / 180;

export function laneOffsetM(widthM) {
  const w = Number.isFinite(widthM) && widthM > 0 ? widthM : 3;
  return Math.max(0, Math.min(w * 0.22, w / 2 - 0.4, 1.2));
}

function lateral(p, lat) {
  const h = p.headingDeg * deg;
  return { e: p.e + Math.cos(h) * lat, n: p.n - Math.sin(h) * lat, u: p.u };
}

const fwdOf = (headingDeg) => [Math.sin(headingDeg * deg), 0, -Math.cos(headingDeg * deg)];
const add = (a, b, s = 1) => [a[0] + b[0] * s, a[1] + b[1] * s, a[2] + b[2] * s];

/** Bildwinkel und Verschiebung wie FilamentCore.updateProjection. */
export function projection(cam, portrait, rig) {
  if (rig === 'v1' || cam !== 'handlebar') {
    if (cam.startsWith('look:')) return { fovV: portrait ? 72 : 50, near: 0.5, shiftY: 0 };
    return { fovV: portrait ? 72 : 50, near: 0.5, shiftY: 0 };
  }
  return handlebarProjection(portrait);
}

export function cameraPose(cam, dist, ctx) {
  const { poseAt, lat, rel } = ctx;
  const here = poseAt(dist);
  const r = lateral(here, lat);
  const rider = rel(r.e, r.n, r.u);
  if (cam === 'chase') {
    const back = lateral(poseAt(dist - 6), lat);
    const ahead = lateral(poseAt(dist + 6), lat);
    const baseY = Math.max(back.u, r.u);
    const eye = rel(back.e, back.n, baseY + 2.5);
    eye[1] = Math.max(eye[1], Math.max(poseAt(dist - 6).u, here.u) + 1.2);
    const t = rel(ahead.e, ahead.n, (r.u + ahead.u) / 2 + 1.1);
    return { eye, target: t, up: [0, 1, 0] };
  }
  if (cam === 'drone') {
    const f = fwdOf(here.headingDeg);
    const right = [Math.cos(here.headingDeg * deg), 0, Math.sin(here.headingDeg * deg)];
    const eye = add(add(add(rider, right, 22), f, -18), [0, 28, 0]);
    return { eye, target: add(add(rider, f, 8), [0, 1, 0]), up: [0, 1, 0] };
  }
  if (cam === 'handlebar' && ctx.rig === 'v1') {
    // CameraRig HANDLEBAR vor der Ueberarbeitung: Auge 1,45 m, 0,25 m voraus, Blick 25 m voraus.
    const ahead = lateral(poseAt(dist + 25), lat);
    const eye = add(add(rider, fwdOf(here.headingDeg), 0.25), [0, 1.45, 0]);
    eye[1] = Math.max(eye[1], here.u + 1.0);
    return { eye, target: rel(ahead.e, ahead.n, ahead.u + 1.45 * 0.8), up: [0, 1, 0] };
  }
  if (cam === 'handlebar') return handlebarPose(dist, ctx);
  if (cam.startsWith('look:')) {
    // look:rx,uy,fz,rx,uy,fz — Auge und Ziel relativ zum Fahrer (rechts, oben, vorn) in Metern
    const v = cam.slice(5).split(',').map(Number);
    const f = fwdOf(here.headingDeg);
    const right = [Math.cos(here.headingDeg * deg), 0, Math.sin(here.headingDeg * deg)];
    const at = (x, y, z) => add(add(add(rider, right, x), [0, 1, 0], y), f, z);
    return { eye: at(v[0], v[1], v[2]), target: at(v[3], v[4], v[5]), up: [0, 1, 0] };
  }
  throw new Error(`Kamera ${cam} unbekannt`);
}

/** Modellmatrix wie RenderMath.modelMatrix (spaltenweise). */
export function riderMatrix(p, headingDeg, gradePct) {
  const a = (180 - headingDeg) * deg;
  const pp = -Math.atan(Math.max(-60, Math.min(60, gradePct)) / 100);
  const ca = Math.cos(a), sa = Math.sin(a), cp = Math.cos(pp), sp = Math.sin(pp);
  return [ca, 0, -sa, 0, sa * sp, cp, ca * sp, 0, sa * cp, -sp, ca * cp, 0, p[0], p[1], p[2], 1];
}

// ---------------------------------------------------------------------------
// Lenkerperspektive — wie CameraRig.handlebarAngles/handlebarPose und
// FilamentCore.projectionFor (Konstanten dort; hier gespiegelt)
// ---------------------------------------------------------------------------

const EYE = [0.0, 1.46, -0.36];
const LOOK_DOWN_DEG = 3.0;
const PITCH_FOLLOW = 0.6;
const LEAN_FACTOR = 0.5;
const MAX_LEAN = 9 * deg;
const LOOK_INTO_CURVE = 0.6;
const LOOK_AT_M = 20;
const MAX_LOOK_YAW = 22 * deg;
const WHEELBASE = 1.02;

export function handlebarProjection(portrait) {
  return portrait ? { fovV: 74, near: 0.2, shiftY: 0.105 } : { fovV: 64, near: 0.2, shiftY: 0.04 };
}

function angleDeltaDeg(a, b) {
  let d = (b - a) % 360;
  if (d > 180) d -= 360;
  if (d < -180) d += 360;
  return d;
}

export function handlebarAngles(dist, ctx) {
  const { poseAt, lat, speed } = ctx;
  const at = (d) => { const p = lateral(poseAt(d), lat); return [p.e, p.u, -p.n]; };
  const bearing = (a, b) => Math.atan2(b[0] - a[0], -(b[2] - a[2])) / deg;
  const heading = bearing(at(dist - 3), at(dist + 3));
  const pitch = Math.atan((poseAt(dist + 4).u - poseAt(dist - 4).u) / 8);
  const h1 = bearing(at(dist - 8), at(dist));
  const h2 = bearing(at(dist), at(dist + 8));
  const kappa = (angleDeltaDeg(h1, h2) * deg) / 8;
  const v = Math.min(Math.max(speed ?? 0, 0), 25);
  const lean = Math.max(-MAX_LEAN, Math.min(MAX_LEAN, Math.atan((v * v * kappa) / 9.81) * LEAN_FACTOR));
  const toAhead = angleDeltaDeg(heading, bearing(at(dist), at(dist + LOOK_AT_M)));
  const yaw = Math.max(-MAX_LOOK_YAW, Math.min(MAX_LOOK_YAW, toAhead * deg * LOOK_INTO_CURVE));
  const steer = Math.max(-0.35, Math.min(0.35, -Math.atan(WHEELBASE * kappa) * 1.5));
  return { headingDeg: heading, pitch, lean, yaw, steer };
}

function bikeRotation(a) {
  const ya = Math.PI - a.headingDeg * deg, p = -a.pitch, f = a.lean;
  const cy = Math.cos(ya), sy = Math.sin(ya), cp = Math.cos(p), sp = Math.sin(p), cf = Math.cos(f), sf = Math.sin(f);
  const c0 = [cy, 0, -sy], c1 = [sy * sp, cp, cy * sp], c2 = [sy * cp, -sp, cy * cp];
  const x = [c0[0] * cf + c1[0] * sf, c0[1] * cf + c1[1] * sf, c0[2] * cf + c1[2] * sf];
  const y = [-c0[0] * sf + c1[0] * cf, -c0[1] * sf + c1[1] * cf, -c0[2] * sf + c1[2] * cf];
  return [x, y, c2];
}

export function handlebarPose(dist, ctx) {
  const a = handlebarAngles(dist, ctx);
  const r0 = lateral(ctx.poseAt(dist), ctx.lat);
  const rider = ctx.rel(r0.e, r0.n, r0.u);
  const [x, y, z] = bikeRotation(a);
  const eye = add(add(add(rider, x, EYE[0]), y, EYE[1]), z, EYE[2]);
  const h = a.headingDeg * deg + a.yaw;
  const p = a.pitch * PITCH_FOLLOW - LOOK_DOWN_DEG * deg;
  const dir = [Math.sin(h) * Math.cos(p), Math.sin(p), -Math.cos(h) * Math.cos(p)];
  const right = [Math.cos(h), 0, Math.sin(h)];
  const up = [right[0] * Math.sin(a.lean), Math.cos(a.lean), right[2] * Math.sin(a.lean)];
  const cockpit = [...x, 0, ...y, 0, ...z, 0, ...rider, 1];
  return { eye, target: add(eye, dir, 10), up, cockpit, steer: a.steer, hideRider: true };
}

/** Lokale Matrix des Lenkerknotens wie CockpitModel.steerMatrix (spaltenweise). */
export function steerMatrix(angle) {
  const ax = [0, Math.sin(72 * deg), -Math.cos(72 * deg)];
  const s = Math.sin(angle / 2), w = Math.cos(angle / 2);
  const x = ax[0] * s, y = ax[1] * s, z = ax[2] * s;
  return [
    1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
    2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
    2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0,
    0, 0.88, 0.34, 1,
  ];
}
