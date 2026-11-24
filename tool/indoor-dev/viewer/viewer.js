// Betrachter fuer Indoor-Welten (docs/indoor.md, Abschnitte 7 und 9).
//
// Referenzleserin des Formats: liest manifest.json, laedt Kacheln nach `show`,
// bildet die Teilflaechen-Regel nach und zeigt das Ganze so, wie es spaeter
// Filament zeigen soll. Die Materialien der Kacheln bleiben, wie der GLTFLoader
// sie liefert — Licht, Schatten, Himmel und Dunst sind Sache der Darstellung.
//
// Gerendert wird nur auf Anforderung (kein Dauerlauf), weil das Bild in
// headless Chromium per Software entsteht.

import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { SunLight } from 'three/addons/lights/SunLight.js';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';

// ---------------------------------------------------------------------------
// Parameter
// ---------------------------------------------------------------------------

const params = new URLSearchParams(location.search);
const pStr = (k, d) => (params.has(k) ? params.get(k) : d);
const pNum = (k, d) => {
  const v = parseFloat(params.get(k));
  return Number.isFinite(v) ? v : d;
};
const pFlag = (k, d) => (params.has(k) ? !['0', 'false', 'off', ''].includes(params.get(k)) : d);

const CONFIG = {
  world: pStr('world', '../out/mock').replace(/\/+$/, ''),
  // Verzeichnis mit rider.glb/ghost.glb; leer = Platzhalter ohne Ladeversuch.
  models: pStr('models', '../../../app/src/main/assets/indoor/models').replace(/\/+$/, ''),
  attribution: pFlag('attribution', true),
  ui: pFlag('ui', true),
  check: pFlag('check', true),
  shadows: pFlag('shadows', true),
  fog: pFlag('fog', true),
  sky: pFlag('sky', true),
  shadowRangeM: pNum('shadowRange', 300),
  shadowMapPx: pNum('shadowMap', 4096),
  lookaheadM: pNum('lookahead', 60),
  haze: pNum('haze', 0.3),
  exposure: pNum('exposure', 0.62),
  near: pNum('near', 0),
  // Verzeichnis mit dem Himmel der Darstellung (`indoor-dev sky --out …`: sky.json + sky.f32);
  // leer = eigener Verlauf des Betrachters.
  skyDir: pStr('skyDir', '').replace(/\/+$/, ''),
  // Wie Filament: Szene in einen Float-Puffer (RGBA16F) mit 4× MSAA, Tonemapping danach. Dort wird die
  // Ausgabe vor dem Mischen NICHT auf 0..1 geklemmt — negative Deckung (Vertexfarben, die MSAA ueber die
  // Dreieckskante hinaus fortschreibt) hellt den Hintergrund auf, wie auf dem Geraet.
  hdr: pFlag('hdr', false),
};

/** Zustand der Ansicht; `setView` aendert nur, was uebergeben wird. */
const view = {
  dist: pNum('dist', 0),
  camera: pStr('camera', 'chase').toLowerCase(),
  sunAzimuthDeg: pNum('sunAz', 235),
  sunElevationDeg: pNum('sunEl', 40),
  rider: pFlag('rider', true),
  pedalPhase: pNum('pedal', 0.15),
  rollPhase: pNum('roll', 0.0),
  ghostDist: params.has('ghost') ? pNum('ghost', NaN) : null,
};

// Vertrag (WorldContract.kt, WorldManifest.kt)
const FORMAT = 1;
const LEVEL_FACTOR = 4;
const NODE_NAMES = ['terrain', 'route', 'roads', 'buildings', 'trees', 'water', 'props'];
const MATERIAL_NAMES = ['mat_terrain', 'mat_building_wall', 'mat_building_roof', 'mat_tree_trunk', 'mat_tree_leaves', 'mat_water'];
const SUB_RE = /^terrain_sub_(\d+)_(\d+)$/;
// KHR_materials_specular: nur specularFactor 0 fuer Vegetation (Abweichung, docs/indoor/world.md; gltfio 1.75.1: Ubershader specular_*)
const ALLOWED_EXTENSIONS = ['KHR_materials_unlit', 'KHR_materials_specular'];
const ALLOWED_ATTRIBUTES = ['POSITION', 'NORMAL', 'TEXCOORD_0', 'COLOR_0'];
const MODEL_ATTRIBUTES = [...ALLOWED_ATTRIBUTES, 'JOINTS_0', 'WEIGHTS_0'];
const VISIBILITY_M = { LOW: 6000, MEDIUM: 12000, HIGH: 20000 };
// Standardbreiten je Belag (SurfaceKind.defaultWidthM in IndoorSurface.kt)
const DEFAULT_WIDTH_M = {
  ASPHALT: 5.0, CONCRETE: 3.5, PAVING_STONES: 3.0, COBBLESTONE: 4.0, WOOD: 2.5, COMPACTED: 3.0,
  GRAVEL: 3.0, DIRT: 1.5, GRASS: 2.0, SAND: 2.0, UNKNOWN: 3.0,
};

const errors = [];
const warnings = [];
function reportError(msg) {
  errors.push(msg);
  console.error(`[viewer] ${msg}`);
}
function reportWarning(msg) {
  warnings.push(msg);
  console.warn(`[viewer] ${msg}`);
}
function showMessage(text) {
  const el = document.getElementById('message');
  el.textContent = text;
  el.style.display = 'flex';
}

// ---------------------------------------------------------------------------
// Dunst wie Filaments `fogOptions`: exponentiell mit Hoehenabnahme, im
// linearen Farbraum VOR dem Tonemapping gemischt.
//
// three.js mischt seinen Nebel erst nach Tonemapping und sRGB-Wandlung und
// kennt keine Hoehe. Deshalb werden hier die Nebel-Bausteine der mitgelieferten
// Shader ersetzt — das betrifft die Darstellung als Ganzes (jedes Material
// gleich), nicht einzelne Kachelmaterialien.
// ---------------------------------------------------------------------------

const FOG_HEIGHT_FALLOFF = 1 / 1400; // 1/m, Skalenhoehe der Dunstschicht

THREE.ShaderChunk.fog_pars_vertex = /* glsl */ `
#ifdef USE_FOG
  varying float vFogDepth;
  varying vec3 vFogWorldPos;
#endif`;
THREE.ShaderChunk.fog_vertex = /* glsl */ `
#ifdef USE_FOG
  vFogDepth = - mvPosition.z;
  vFogWorldPos = ( modelMatrix * vec4( transformed, 1.0 ) ).xyz;
#endif`;
THREE.ShaderChunk.fog_pars_fragment = /* glsl */ `
#ifdef USE_FOG
  uniform vec3 fogColor;
  uniform float fogDensity;
  varying float vFogDepth;
  varying vec3 vFogWorldPos;
#endif`;
// Der Nebel wandert vor das Tonemapping …
THREE.ShaderChunk.tonemapping_fragment = /* glsl */ `
#ifdef USE_FOG
  {
    // fogDensity gilt auf Kamerahoehe; nach oben nimmt der Dunst exponentiell ab.
    vec3 fogRay = vFogWorldPos - cameraPosition;
    float fogH = ${FOG_HEIGHT_FALLOFF.toFixed(8)} * fogRay.y;
    float fogIntegral = abs( fogH ) > 1e-4 ? ( 1.0 - exp( - fogH ) ) / fogH : 1.0;
    float fogFactor = 1.0 - exp( - fogDensity * fogIntegral * length( fogRay ) );
    gl_FragColor.rgb = mix( gl_FragColor.rgb, fogColor, clamp( fogFactor, 0.0, 1.0 ) );
  }
#endif
#if defined( TONE_MAPPING )
  gl_FragColor.rgb = toneMapping( gl_FragColor.rgb );
#endif`;
// Vergleich: `clampColor=1` klemmt die (ggf. fortgeschriebene) Vertexfarbe auf 0..1 — so saehe es mit
// `centroid`-Interpolation aus. Nur zum Eingrenzen der hellen Nahtlinien (mit `hdr=1`).
if (pFlag('clampColor', false)) {
  THREE.ShaderChunk.color_fragment = /* glsl */ `
#if defined( USE_COLOR ) || defined( USE_COLOR_ALPHA )
  diffuseColor *= clamp( vColor, 0.0, 1.0 );
#endif`;
}
// … und entfaellt an der alten Stelle.
THREE.ShaderChunk.fog_fragment = '';

// glTF alphaMode MASK wie in Filament (gltfio-Ubershader, `blending: masked`): Filament schaltet bei
// MASKED automatisch Alpha-to-Coverage ein (wirkt mit MSAA) und schaerft das Alpha vorher ueber die
// Ableitung: a' = (a − Schwelle) / max(fwidth(a), 1e-3) + 0,5; verworfen wird bei a' ≤ 0
// (shaders/src/common_material.fs `computeMaskedAlpha`, `applyAlphaMask`). three.js nimmt sonst
// smoothstep(Schwelle, Schwelle + fwidth) — um eine halbe Ableitung duenner.
THREE.ShaderChunk.alphatest_fragment = /* glsl */ `
#ifdef USE_ALPHATEST
  #ifdef ALPHA_TO_COVERAGE
  diffuseColor.a = clamp( ( diffuseColor.a - alphaTest ) / max( fwidth( diffuseColor.a ), 1e-3 ) + 0.5, 0.0, 1.0 );
  if ( diffuseColor.a <= 0.0 ) discard;
  #else
  if ( diffuseColor.a < alphaTest ) discard;
  #endif
#endif`;

// ---------------------------------------------------------------------------
// Renderer, Szene, Licht
// ---------------------------------------------------------------------------

const canvas = document.getElementById('view');
let renderer;
try {
  renderer = new THREE.WebGLRenderer({
    canvas,
    antialias: true, // MSAA der Zeichenflaeche
    alpha: false,
    powerPreference: 'high-performance',
    preserveDrawingBuffer: true, // damit Screenshots und readPixels sicher das letzte Bild sehen
  });
} catch (e) {
  showMessage(`WebGL laesst sich nicht starten: ${e.message}`);
  reportError(`WebGL laesst sich nicht starten: ${e.message}`);
  throw e;
}
renderer.setPixelRatio(1);
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = CONFIG.exposure;
renderer.shadowMap.enabled = CONFIG.shadows;
renderer.shadowMap.type = THREE.PCFShadowMap; // Hardware-PCF, fuenf Abtastungen
renderer.shadowMap.autoUpdate = false; // nur neu rechnen, wenn sich etwas aendert
renderer.info.autoReset = false;
// gltfio 1.75.1 setzt keine anisotrope Filterung (Sampler nur aus glTF: Wrap, Min/Mag), und die
// Java-Schnittstelle reicht die Texturen nicht heraus — deshalb hier wie in Filament ohne (`aniso=16` zum Vergleich).
const MAX_ANISOTROPY = Math.min(pNum('aniso', 1), renderer.capabilities.getMaxAnisotropy());

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(50, 16 / 9, 0.5, 30000);
scene.add(camera);

// Alles Geografische haengt an `worldRoot`; die Welt wird waagerecht gegen die
// Kamera verschoben (Ursprung = Stelle des Fahrers, auf ganze Meter gerundet).
// Die Hoehe bleibt unverschoben: u ist klein, und der Dunst braucht sie.
const worldRoot = new THREE.Group();
worldRoot.name = 'worldRoot';
scene.add(worldRoot);
const origin = { e: 0, n: 0 };
/** ENU → Szene (relativ zum verschobenen Ursprung): X = e, Y = u, Z = −n. */
const toScene = (e, n, u, out = new THREE.Vector3()) => out.set(e - origin.e, u, -(n - origin.n));

// Sonne: Richtungslicht mit zwei Schattenkaskaden, die three.js an den Sichtkegel
// anpasst — wie Filaments Kaskaden. Reichweite `shadowRange` ab der Kamera,
// `shadowMap` Pixel Kantenlaenge je Kaskade.
const sun = new SunLight(0xfff1dc, 10.0);
sun.castShadow = CONFIG.shadows;
sun.shadow.mapSize.set(CONFIG.shadowMapPx, CONFIG.shadowMapPx);
sun.shadow.camera.near = 1;
sun.shadow.camera.far = CONFIG.shadowRangeM;
sun.shadow.bias = -0.0001;
sun.shadow.normalBias = 0.06; // Meter; nur Gebaeude und Fahrer beschatten sich selbst
// Weiche Kanten wie Filaments DPCF (FilamentCore: ShadowType.DPCF, penumbraScale 1,5).
sun.shadow.radius = pNum('shadowRadius', 2.0);
scene.add(sun);

// Umgebungslicht: blaeulicher Himmel von oben, warm-gruener Widerschein vom Boden
// (entspricht zwei Baendern Kugelflaechenfunktionen).
const hemi = new THREE.HemisphereLight(0xb4ceff, 0x7d7f58, 2.1);
scene.add(hemi);

// Filaments Standard-Tonwertabbildung ACES_LEGACY (ToneMapper.cpp v1.75.1, Helligkeit 1/0,6) —
// nur fuer den Himmel der Darstellung (skyDir), damit er aussieht wie auf dem Geraet.
// Gleiche Rechnung wie FilamentAces in tool/indoor-dev (Sky.kt).
const FILAMENT_ACES_GLSL = /* glsl */ `
  vec3 filamentAces( vec3 c ) {
    const mat3 sRGB_to_AP0 = mat3( 0.4397010, 0.0897923, 0.0175440, 0.3829780, 0.8134230, 0.1115440, 0.1773350, 0.0967616, 0.8707040 );
    const mat3 AP0_to_AP1 = mat3( 1.4514393161, -0.0765537734, 0.0083161484, -0.2365107469, 1.1762296998, -0.0060324498, -0.2149285693, -0.0996759264, 0.9977163014 );
    const mat3 AP1_to_sRGB = mat3( 1.70505, -0.13026, -0.02400, -0.62179, 1.14080, -0.12897, -0.08326, -0.01055, 1.15297 );
    const mat3 AP1_to_XYZ = mat3( 0.6624541811, 0.2722287168, -0.0055746495, 0.1340042065, 0.6740817658, 0.0040607335, 0.1561876870, 0.0536895174, 1.0103391003 );
    const mat3 XYZ_to_AP1 = mat3( 1.6410233797, -0.6636628587, 0.0117218943, -0.3248032942, 1.6153315917, -0.0082844420, -0.2364246952, 0.0167563477, 0.9883948585 );
    const vec3 LUM = vec3( 0.272229, 0.674082, 0.0536895 );
    vec3 ap0 = sRGB_to_AP0 * max( c, vec3( 0.0 ) );
    float mi = min( ap0.r, min( ap0.g, ap0.b ) );
    float ma = max( ap0.r, max( ap0.g, ap0.b ) );
    float sat = ( max( ma, 1e-5 ) - max( mi, 1e-5 ) ) / max( ma, 1e-2 );
    float chroma = sqrt( max( 0.0, ap0.b * ( ap0.b - ap0.g ) + ap0.g * ( ap0.g - ap0.r ) + ap0.r * ( ap0.r - ap0.b ) ) );
    float yc = ( ap0.r + ap0.g + ap0.b + 1.75 * chroma ) / 3.0;
    float xs = ( sat - 0.4 ) / 0.2;
    float t = max( 1.0 - abs( xs / 2.0 ), 0.0 );
    float s = ( 1.0 + sign( xs ) * ( 1.0 - t * t ) ) / 2.0;
    float gain = 0.05 * s;
    float glow = yc <= 2.0 / 3.0 * 0.08 ? gain : ( yc >= 0.16 ? 0.0 : gain * ( 0.08 / yc - 0.5 ) );
    ap0 *= 1.0 + glow;
    float hue = ( ap0.r == ap0.g && ap0.g == ap0.b ) ? 0.0 : degrees( atan( sqrt( 3.0 ) * ( ap0.g - ap0.b ), 2.0 * ap0.r - ap0.g - ap0.b ) );
    float hw = smoothstep( 0.0, 1.0, 1.0 - abs( 2.0 * hue / 135.0 ) );
    hw *= hw;
    ap0.r += hw * sat * ( 0.03 - ap0.r ) * ( 1.0 - 0.82 );
    vec3 ap1 = clamp( AP0_to_AP1 * ap0, 0.0, 65504.0 );
    ap1 = mix( vec3( dot( ap1, LUM ) ), ap1, 0.96 ) / 0.6;
    vec3 post = ( ap1 * ( 2.785085 * ap1 + 0.107772 ) ) / ( ap1 * ( 2.936045 * ap1 + 0.887122 ) + 0.806889 );
    vec3 xyz = AP1_to_XYZ * post;
    float sum = xyz.x + xyz.y + xyz.z;
    if ( sum > 0.0 && xyz.y > 0.0 ) {
      vec2 cxy = xyz.xy / sum;
      float yy = pow( xyz.y, 0.9811 );
      xyz = vec3( cxy.x * yy / cxy.y, yy, ( 1.0 - cxy.x - cxy.y ) * yy / cxy.y );
    }
    vec3 lin = XYZ_to_AP1 * xyz;
    lin = mix( vec3( dot( lin, LUM ) ), lin, 0.93 );
    return clamp( AP1_to_sRGB * lin, 0.0, 1.0 );
  }`;

/** Dieselbe Abbildung in JS (fuer die Dunstfarbe). */
function filamentAcesJs([r, g, b]) {
  const m = (a, v) => [0, 1, 2].map((i) => a[i] * v[0] + a[3 + i] * v[1] + a[6 + i] * v[2]);
  const sRGB_to_AP0 = [0.4397010, 0.0897923, 0.0175440, 0.3829780, 0.8134230, 0.1115440, 0.1773350, 0.0967616, 0.8707040];
  const AP0_to_AP1 = [1.4514393161, -0.0765537734, 0.0083161484, -0.2365107469, 1.1762296998, -0.0060324498, -0.2149285693, -0.0996759264, 0.9977163014];
  const AP1_to_sRGB = [1.70505, -0.13026, -0.02400, -0.62179, 1.14080, -0.12897, -0.08326, -0.01055, 1.15297];
  const AP1_to_XYZ = [0.6624541811, 0.2722287168, -0.0055746495, 0.1340042065, 0.6740817658, 0.0040607335, 0.1561876870, 0.0536895174, 1.0103391003];
  const XYZ_to_AP1 = [1.6410233797, -0.6636628587, 0.0117218943, -0.3248032942, 1.6153315917, -0.0082844420, -0.2364246952, 0.0167563477, 0.9883948585];
  const LUM = [0.272229, 0.674082, 0.0536895];
  const dot = (a, v) => a[0] * v[0] + a[1] * v[1] + a[2] * v[2];
  let ap0 = m(sRGB_to_AP0, [Math.max(r, 0), Math.max(g, 0), Math.max(b, 0)]);
  const mi = Math.min(...ap0); const ma = Math.max(...ap0);
  const sat = (Math.max(ma, 1e-5) - Math.max(mi, 1e-5)) / Math.max(ma, 1e-2);
  const chroma = Math.sqrt(Math.max(0, ap0[2] * (ap0[2] - ap0[1]) + ap0[1] * (ap0[1] - ap0[0]) + ap0[0] * (ap0[0] - ap0[2])));
  const yc = (ap0[0] + ap0[1] + ap0[2] + 1.75 * chroma) / 3;
  const xs = (sat - 0.4) / 0.2; const t = Math.max(1 - Math.abs(xs / 2), 0);
  const gain = 0.05 * (1 + Math.sign(xs) * (1 - t * t)) / 2;
  const glow = yc <= (2 / 3) * 0.08 ? gain : yc >= 0.16 ? 0 : gain * (0.08 / yc - 0.5);
  ap0 = ap0.map((v) => v * (1 + glow));
  let hue = ap0[0] === ap0[1] && ap0[1] === ap0[2] ? 0 : (Math.atan2(Math.sqrt(3) * (ap0[1] - ap0[2]), 2 * ap0[0] - ap0[1] - ap0[2]) * 180) / Math.PI;
  const h0 = Math.min(Math.max(1 - Math.abs((2 * hue) / 135), 0), 1);
  let hw = h0 * h0 * (3 - 2 * h0); hw *= hw;
  ap0[0] += hw * sat * (0.03 - ap0[0]) * (1 - 0.82);
  let ap1 = m(AP0_to_AP1, ap0).map((v) => Math.min(Math.max(v, 0), 65504));
  let y = dot(ap1, LUM);
  ap1 = ap1.map((v) => (y + (v - y) * 0.96) / 0.6);
  const post = ap1.map((v) => (v * (2.785085 * v + 0.107772)) / (v * (2.936045 * v + 0.887122) + 0.806889));
  let xyz = m(AP1_to_XYZ, post);
  const sum = xyz[0] + xyz[1] + xyz[2];
  if (sum > 0 && xyz[1] > 0) {
    const cx = xyz[0] / sum; const cy = xyz[1] / sum; const yy = Math.pow(xyz[1], 0.9811);
    xyz = [(cx * yy) / cy, yy, ((1 - cx - cy) * yy) / cy];
  }
  let lin = m(XYZ_to_AP1, xyz);
  y = dot(lin, LUM);
  lin = lin.map((v) => y + (v - y) * 0.93);
  return m(AP1_to_sRGB, lin).map((v) => Math.min(Math.max(v, 0), 1));
}

/** three.js ACESFilmicToneMapping (r16x) mit Belichtung — die Abbildung der Welt im Betrachter. */
function threeAcesJs([r, g, b], exposure) {
  const m = (a, v) => [0, 1, 2].map((i) => a[i] * v[0] + a[3 + i] * v[1] + a[6 + i] * v[2]);
  const IN = [0.59719, 0.07600, 0.02840, 0.35458, 0.90834, 0.13383, 0.04823, 0.01566, 0.83777];
  const OUT = [1.60475, -0.10208, -0.00327, -0.53108, 1.10813, -0.07276, -0.07367, -0.00605, 1.07602];
  let c = m(IN, [r, g, b].map((v) => (v * exposure) / 0.6));
  c = c.map((v) => (v * (v + 0.0245786) - 0.000090537) / (v * (0.983729 * v + 0.4329510) + 0.238081));
  return m(OUT, c).map((v) => Math.min(Math.max(v, 0), 1));
}

/** Szenenfarbe, die nach der Abbildung der Welt [target] (Anzeige linear) ergibt (Newton). */
function invertThreeAces(target, exposure) {
  let x = target.map((v) => v * 0.8 + 0.01);
  for (let it = 0; it < 60; it++) {
    const y = threeAcesJs(x, exposure);
    const J = [0, 1, 2].map((j) => { const xp = x.slice(); xp[j] += 1e-4; const yp = threeAcesJs(xp, exposure); return yp.map((v, i) => (v - y[i]) / 1e-4); });
    // J[j][i] = d y_i / d x_j; nur die Diagonale (Kanaele kaum gekoppelt) reicht hier.
    x = x.map((v, i) => Math.max(0, v + (0.8 * (target[i] - y[i])) / Math.max(J[i][i], 1e-4)));
  }
  return x;
}

// Himmel: grosse Kugel um die Kamera, Verlauf Horizont → Zenit mit Sonnenscheibe.
// (Filament: zur Laufzeit erzeugte Cubemap mit demselben Verlauf.)
const SKY_ZENITH = new THREE.Vector3(0.055, 0.17, 0.56);
const SKY_HORIZON = new THREE.Vector3(0.50, 0.66, 0.90);
const skyMaterial = new THREE.ShaderMaterial({
  uniforms: {
    zenith: { value: SKY_ZENITH.clone() },
    horizon: { value: SKY_HORIZON.clone() },
    sunDir: { value: new THREE.Vector3(0, 1, 0) },
    sunColor: { value: new THREE.Vector3(1.0, 0.93, 0.82) },
    // Himmel der Darstellung (skyDir): Rundumbild szenenlinear, Sonne wie Filaments Skybox.
    useTex: { value: 0 },
    skyTex: { value: null },
    sunPreExposed: { value: 0 },
    sunCos: { value: Math.cos((1.2 * Math.PI) / 180) },
    sunHaloInv: { value: 1 / (Math.cos((1.2 * 8 * Math.PI) / 180) - Math.cos((1.2 * Math.PI) / 180)) },
    sunHaloFalloff: { value: 80 },
  },
  vertexShader: /* glsl */ `
    varying vec3 vDir;
    void main() {
      vDir = position;
      vec4 p = projectionMatrix * modelViewMatrix * vec4( position, 1.0 );
      gl_Position = p;
    }`,
  fragmentShader: /* glsl */ `
    uniform vec3 zenith;
    uniform vec3 horizon;
    uniform vec3 sunDir;
    uniform vec3 sunColor;
    uniform int useTex;
    uniform sampler2D skyTex;
    uniform float sunPreExposed;
    uniform float sunCos;
    uniform float sunHaloInv;
    uniform float sunHaloFalloff;
    varying vec3 vDir;
    float hash( vec2 p ) { return fract( sin( dot( p, vec2( 12.9898, 78.233 ) ) ) * 43758.5453 ); }
    ${FILAMENT_ACES_GLSL}
    void main() {
      vec3 d = normalize( vDir );
      if ( useTex == 1 ) {
        // Rundumbild aus core/…/indoor/sky (Spalte 0 blickt nach −Z), szenenlinear wie in Filament;
        // Sonnenscheibe und Hof wie Filaments Skybox-Material (showSun), dann Filaments ACES_LEGACY.
        vec2 uv = vec2( fract( atan( d.x, -d.z ) / 6.28318530718 + 1.0 ), 0.5 - asin( clamp( d.y, -1.0, 1.0 ) ) / 3.14159265359 );
        vec3 col = texture2D( skyTex, uv ).rgb;
        float x = ( dot( d, sunDir ) - sunCos ) * sunHaloInv;
        col += pow( 1.0 - clamp( x, 0.0, 1.0 ), sunHaloFalloff ) * sunColor * sunPreExposed * 12.566370614;
        gl_FragColor = vec4( filamentAces( col ), 1.0 );
        #include <colorspace_fragment>
        gl_FragColor.rgb += ( hash( gl_FragCoord.xy ) - 0.5 ) / 255.0;
        return;
      }
      float h = max( d.y, 0.0 );
      // Der helle Horizontsaum faellt in den ersten 10–15 Grad ab.
      vec3 col = mix( horizon, zenith, pow( h, 0.55 ) );
      float c = max( dot( d, sunDir ), 0.0 );
      // Aufhellung um die Sonne; am Horizont selbst null, damit Horizont = Dunstfarbe bleibt.
      float above = smoothstep( 0.0, 0.12, d.y );
      col += sunColor * ( 0.35 * pow( c, 10.0 ) + 1.6 * pow( c, 220.0 ) ) * above;
      // Sonnenscheibe (rund 0,4 Grad Radius, weicher Rand)
      col += sunColor * 60.0 * smoothstep( 0.999955, 0.999985, c );
      gl_FragColor = vec4( col, 1.0 );
      #include <tonemapping_fragment>
      #include <colorspace_fragment>
      // feines Rauschen gegen Streifen im Verlauf
      gl_FragColor.rgb += ( hash( gl_FragCoord.xy ) - 0.5 ) / 255.0;
    }`,
  side: THREE.BackSide,
  depthWrite: false,
  depthTest: false,
  fog: false,
});
const sky = new THREE.Mesh(new THREE.SphereGeometry(1000, 48, 24), skyMaterial);
sky.frustumCulled = false;
sky.renderOrder = -1000;
sky.visible = CONFIG.sky;
scene.add(sky);
scene.background = new THREE.Color(0x9fbfe6); // wenn `sky=0`: einfarbig wie Filaments Skybox

// Dunst. Die Farbe ist die Horizontfarbe des Himmels (linear); three.js wandelt
// die Uniform fuer die Bildschirmausgabe nach sRGB, deshalb hier vorab die Umkehrung.
const fog = new THREE.FogExp2(0xffffff, 0);
fog.color.setRGB(SKY_HORIZON.x, SKY_HORIZON.y, SKY_HORIZON.z, THREE.SRGBColorSpace);
if (CONFIG.fog) scene.fog = fog;

const controls = new OrbitControls(camera, canvas);
controls.enableDamping = false;
controls.addEventListener('change', () => { shadowsDirty = true; requestRender(); }); // Kaskaden haengen am Sichtkegel

// ---------------------------------------------------------------------------
// Manifest und Streckenband
// ---------------------------------------------------------------------------

let manifest = null;
let track = null;
const tileById = new Map(); // id → Manifesteintrag
const tileByKey = new Map(); // "L/ix/iy" → Manifesteintrag
const tileKey = (level, ix, iy) => `${level}/${ix}/${iy}`;

function requireField(o, key, type) {
  const v = o?.[key];
  const ok = type === 'array' ? Array.isArray(v) : type === 'object' ? v && typeof v === 'object' : typeof v === type;
  if (!ok) throw new Error(`Welt-Manifest: Pflichtfeld "${key}" fehlt oder hat den falschen Typ`);
  return v;
}

/** Liest und prueft das Manifest wie `WorldManifest.parse`. */
function parseManifest(m) {
  if (!m || typeof m !== 'object') throw new Error('Welt-Manifest: kein JSON-Objekt');
  const format = requireField(m, 'format', 'number');
  if (format !== FORMAT) throw new Error(`Welt-Manifest: Format ${format} wird nicht unterstuetzt (erwartet ${FORMAT})`);
  requireField(m, 'worldId', 'string');
  requireField(m, 'routeId', 'string');
  const o = requireField(m, 'origin', 'object');
  for (const k of ['lat', 'lon', 'eleM']) requireField(o, k, 'number');
  const b = requireField(m, 'bounds', 'object');
  for (const k of ['minE', 'minN', 'maxE', 'maxN', 'minU', 'maxU']) requireField(b, k, 'number');
  const t = requireField(m, 'track', 'object');
  requireField(t, 'lengthM', 'number');
  requireField(t, 'stepM', 'number');
  for (const k of ['e', 'n', 'u', 'surfaces']) requireField(t, k, 'array');
  if (t.e.length < 2 || t.e.length !== t.n.length || t.e.length !== t.u.length) {
    throw new Error(`Welt-Manifest: Streckenband mit ${t.e.length}/${t.n.length}/${t.u.length} Proben`);
  }
  requireField(m, 'levels', 'array');
  requireField(m, 'tiles', 'array');
  requireField(m, 'budget', 'object');
  if (!VISIBILITY_M[m.quality]) {
    reportWarning(`Manifest: quality "${m.quality}" unbekannt, nehme MEDIUM`);
    m.quality = 'MEDIUM';
  }
  const cells = new Map();
  for (const l of m.levels) cells.set(requireField(l, 'level', 'number'), requireField(l, 'cellM', 'number'));
  for (const tile of m.tiles) {
    requireField(tile, 'id', 'string');
    for (const k of ['level', 'ix', 'iy']) requireField(tile, k, 'number');
    requireField(tile, 'file', 'string');
    const a = requireField(tile, 'anchor', 'array');
    if (a.length !== 3) throw new Error('Welt-Manifest: anchor braucht drei Zahlen');
    for (const pair of requireField(tile, 'show', 'array')) {
      if (!Array.isArray(pair) || pair.length !== 2) throw new Error('Welt-Manifest: show braucht Paare');
    }
    // Plausibilitaet ueber das hinaus, was `parse` verlangt — als Formatfehler gemeldet.
    const expectedId = `L${tile.level}_${tile.ix}_${tile.iy}`;
    if (tile.id !== expectedId) reportError(`Manifest: Kachel "${tile.id}" muesste "${expectedId}" heissen`);
    const c = cells.get(tile.level) ?? 256 * LEVEL_FACTOR ** tile.level;
    if (c !== 256 * LEVEL_FACTOR ** tile.level) reportError(`Manifest: Stufe ${tile.level} hat cellM ${c}, erwartet ${256 * LEVEL_FACTOR ** tile.level}`);
    if (Math.abs(a[0] - (tile.ix + 0.5) * c) > 0.01 || Math.abs(a[1] - (tile.iy + 0.5) * c) > 0.01) {
      reportError(`Manifest: Anker von ${tile.id} ist nicht die Kachelmitte (${a[0]}, ${a[1]})`);
    }
    if (tileById.has(tile.id)) reportError(`Manifest: Kachel ${tile.id} doppelt`);
    tileById.set(tile.id, tile);
    tileByKey.set(tileKey(tile.level, tile.ix, tile.iy), tile);
  }
  return m;
}

function foldDist(d) {
  if (!Number.isFinite(d)) return 0;
  const len = track.lengthM;
  if (track.loop && len > 0) return ((d % len) + len) % len;
  return Math.min(Math.max(d, 0), len);
}

/** `WorldTrack.poseAt`, Zeile fuer Zeile. */
function poseAt(distM) {
  const { e, n, u, stepM, lengthM } = track;
  const d = foldDist(distM);
  const distAt = (i) => Math.min(i * stepM, lengthM);
  const last = e.length - 1;
  const i = Math.min(Math.max(Math.trunc(d / stepM), 0), Math.max(0, last - 1));
  const j = Math.min(i + 1, last);
  const span = distAt(j) - distAt(i);
  const t = span > 0 ? Math.min(Math.max((d - distAt(i)) / span, 0), 1) : 0;
  const de = e[j] - e[i];
  const dn = n[j] - n[i];
  const du = u[j] - u[i];
  return {
    e: e[i] + de * t,
    n: n[i] + dn * t,
    u: u[i] + du * t,
    headingDeg: ((Math.atan2(de, dn) * 180) / Math.PI + 360) % 360,
    gradePct: span > 0 ? (du / span) * 100 : 0,
  };
}

/** Pose ausserhalb des Bands: an den Enden einer offenen Strecke geradeaus verlaengert. */
function poseAtExtended(distM) {
  if (track.loop) return poseAt(distM);
  const p = poseAt(distM);
  const over = distM < 0 ? distM : distM > track.lengthM ? distM - track.lengthM : 0;
  if (over !== 0) {
    const h = (p.headingDeg * Math.PI) / 180;
    p.e += Math.sin(h) * over;
    p.n += Math.cos(h) * over;
  }
  return p;
}

function widthAt(distM) {
  const d = foldDist(distM);
  const s = track.surfaces.find((x) => d >= x.fromM && d <= x.toM);
  if (!s) return DEFAULT_WIDTH_M.UNKNOWN;
  return typeof s.widthM === 'number' ? s.widthM : (DEFAULT_WIDTH_M[s.kind] ?? DEFAULT_WIDTH_M.UNKNOWN);
}

/** `WorldTile.neededAt`: beide Grenzen einschliesslich. */
const neededAt = (tile, d) => tile.show.some(([from, to]) => d >= from && d <= to);

/** Kacheln fuer die Stelle samt Vorlauf in Fahrtrichtung. */
function neededTiles(dist) {
  const ds = [foldDist(dist)];
  for (let a = 20; a <= CONFIG.lookaheadM; a += 20) ds.push(foldDist(dist + a));
  const out = new Set();
  for (const tile of manifest.tiles) if (ds.some((d) => neededAt(tile, d))) out.add(tile.id);
  return out;
}

// ---------------------------------------------------------------------------
// Formatpruefung einer GLB gegen docs/indoor.md 7.3
// ---------------------------------------------------------------------------

function parseGlb(buffer) {
  const dv = new DataView(buffer);
  if (buffer.byteLength < 20 || dv.getUint32(0, true) !== 0x46546c67) throw new Error('keine GLB-Datei (Magic fehlt)');
  if (dv.getUint32(4, true) !== 2) throw new Error(`glTF-Version ${dv.getUint32(4, true)} statt 2`);
  let off = 12;
  let json = null;
  let bin = null;
  while (off + 8 <= buffer.byteLength) {
    const len = dv.getUint32(off, true);
    const type = dv.getUint32(off + 4, true);
    const data = new Uint8Array(buffer, off + 8, len);
    if (type === 0x4e4f534a) json = JSON.parse(new TextDecoder().decode(data));
    else if (type === 0x004e4942 && !bin) bin = data;
    off += 8 + len + ((4 - (len % 4)) % 4);
  }
  if (!json) throw new Error('GLB ohne JSON-Block');
  return { json, bin };
}

/** Breite, Hoehe und Art eines eingebetteten Bilds, ohne es zu dekodieren. */
function sniffImage(bytes) {
  if (bytes.length > 24 && bytes[0] === 0x89 && bytes[1] === 0x50) {
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    return { type: 'png', width: dv.getUint32(16), height: dv.getUint32(20), progressive: false };
  }
  if (bytes.length > 4 && bytes[0] === 0xff && bytes[1] === 0xd8) {
    let p = 2;
    while (p + 9 < bytes.length) {
      if (bytes[p] !== 0xff) { p++; continue; }
      const marker = bytes[p + 1];
      if (marker === 0xff) { p++; continue; }
      if (marker === 0xd8 || (marker >= 0xd0 && marker <= 0xd7) || marker === 0x01) { p += 2; continue; }
      const len = (bytes[p + 2] << 8) | bytes[p + 3];
      // SOF0..SOF15 ausser DHT (C4), JPG (C8), DAC (CC)
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        return {
          type: 'jpeg',
          height: (bytes[p + 5] << 8) | bytes[p + 6],
          width: (bytes[p + 7] << 8) | bytes[p + 8],
          progressive: marker !== 0xc0 && marker !== 0xc1,
          sof: marker,
        };
      }
      p += 2 + len;
    }
    return { type: 'jpeg', width: 0, height: 0, progressive: false };
  }
  return { type: 'unbekannt', width: 0, height: 0, progressive: false };
}

const isPow2 = (v) => v > 0 && (v & (v - 1)) === 0;

/**
 * Prueft eine GLB gegen den Vertrag. `kind` ist 'tile' oder 'model'.
 * Liefert `{ errors, warnings, triangles, textureBytes }`.
 */
function checkGlb(glb, kind, tile) {
  const errs = [];
  const warns = [];
  const g = glb.json;
  const isTile = kind === 'tile';

  if (g.asset?.version !== '2.0') errs.push(`asset.version "${g.asset?.version}" statt "2.0"`);
  for (const ext of new Set([...(g.extensionsUsed ?? []), ...(g.extensionsRequired ?? [])])) {
    if (!ALLOWED_EXTENSIONS.includes(ext)) errs.push(`verbotene Erweiterung ${ext}`);
  }
  if ((g.buffers ?? []).length > 1 || (g.buffers ?? []).some((b) => b.uri)) errs.push('Puffer nicht eingebettet (uri oder mehr als ein Puffer)');
  if ((g.scenes ?? []).length !== 1) errs.push(`${(g.scenes ?? []).length} Szenen statt einer`);
  if (isTile) {
    if ((g.cameras ?? []).length) errs.push('Kachel enthaelt Kameras');
    if ((g.animations ?? []).length) errs.push('Kachel enthaelt Animationen');
    if ((g.skins ?? []).length) errs.push('Kachel enthaelt Skins');
  }
  if (g.extensions?.KHR_lights_punctual || (g.nodes ?? []).some((n) => n.extensions?.KHR_lights_punctual)) errs.push('GLB enthaelt Lichter');
  if (isTile && (g.nodes ?? []).some((n) => n.camera !== undefined)) errs.push('Kachelknoten verweist auf eine Kamera');

  // Knotennamen
  if (isTile) {
    const nodes = g.nodes ?? [];
    const roots = g.scenes?.[g.scene ?? 0]?.nodes ?? [];
    const seen = new Set();
    const subsSeen = new Set();
    const visit = (idx, parentName, depth) => {
      const node = nodes[idx];
      if (!node) return;
      const name = node.name ?? '';
      const sub = SUB_RE.exec(name);
      if (sub) {
        if (parentName !== 'terrain') errs.push(`"${name}" ist kein direktes Kind von "terrain"`);
        if (+sub[1] >= LEVEL_FACTOR || +sub[2] >= LEVEL_FACTOR) errs.push(`"${name}": Index ausserhalb 0..${LEVEL_FACTOR - 1}`);
        if (subsSeen.has(name)) errs.push(`Teilflaeche "${name}" mehrfach`);
        subsSeen.add(name);
        if (tile && tile.level === 0) errs.push(`Stufe-0-Kachel mit Teilflaeche "${name}"`);
      } else if (NODE_NAMES.includes(name)) {
        if (depth !== 0) errs.push(`Knoten "${name}" liegt nicht direkt unter der Szene`);
        if (seen.has(name)) errs.push(`Knoten "${name}" mehrfach`);
        seen.add(name);
      } else if (!name.startsWith('x_')) {
        errs.push(`unbekannter Knoten "${name || '(ohne Namen)'}" (erlaubt: ${NODE_NAMES.join(', ')}, terrain_sub_i_j oder x_…)`);
      }
      if (node.translation || node.rotation || node.scale || node.matrix) {
        warns.push(`Knoten "${name}" hat eine eigene Transformation (erwartet: Eckpunkte relativ zum Anker)`);
      }
      for (const c of node.children ?? []) visit(c, name, depth + 1);
    };
    for (const r of roots) visit(r, '', 0);
    if (tile && tile.level >= 1) {
      const terrainIdx = roots.find((r) => nodes[r]?.name === 'terrain');
      if (terrainIdx === undefined) errs.push('Kachel ab Stufe 1 ohne Knoten "terrain"');
      else if (subsSeen.size !== LEVEL_FACTOR * LEVEL_FACTOR) {
        errs.push(`"terrain" hat ${subsSeen.size} Teilflaechen statt ${LEVEL_FACTOR * LEVEL_FACTOR}`);
      } else if (nodes[terrainIdx].mesh !== undefined) {
        errs.push('"terrain" traegt ab Stufe 1 selbst ein Netz — es liesse sich nicht teilweise ausblenden');
      }
    }
  }

  // Materialien
  (g.materials ?? []).forEach((mat, i) => {
    const name = mat.name ?? `#${i}`;
    for (const ext of Object.keys(mat.extensions ?? {})) {
      if (!ALLOWED_EXTENSIONS.includes(ext)) errs.push(`Material ${name}: verbotene Erweiterung ${ext}`);
    }
    const unlit = !!mat.extensions?.KHR_materials_unlit;
    const pbr = mat.pbrMetallicRoughness ?? {};
    if (!unlit) {
      if ((pbr.metallicFactor ?? 1) !== 0) errs.push(`Material ${name}: metallicFactor ${pbr.metallicFactor ?? '1 (Vorgabe)'} statt 0`);
      if ((pbr.roughnessFactor ?? 1) < 0.6) errs.push(`Material ${name}: roughnessFactor ${pbr.roughnessFactor} < 0,6`);
    }
    if (pbr.metallicRoughnessTexture || mat.normalTexture || mat.occlusionTexture || mat.emissiveTexture) {
      warns.push(`Material ${name}: nutzt weitere Texturen (Normal/ORM/Emission) — im Prototyp nicht vorgesehen`);
    }
    if (isTile) {
      const known = MATERIAL_NAMES.includes(name) || name.startsWith('mat_route_') || name.startsWith('x_');
      if (!known) warns.push(`Material "${name}" steht nicht in WorldNames und beginnt nicht mit x_`);
      if (name === 'mat_tree_leaves' && (mat.alphaMode !== 'MASK' || !mat.doubleSided)) {
        warns.push('mat_tree_leaves ohne alphaMode MASK / doubleSided');
      }
    }
  });

  // Texturen
  let textureBytes = 0;
  (g.images ?? []).forEach((img, i) => {
    const label = `Bild ${img.name ?? `#${i}`}`;
    if (img.uri !== undefined) {
      if (!img.uri.startsWith('data:')) errs.push(`${label}: nicht eingebettet (uri)`);
      else warns.push(`${label}: als data-URI statt bufferView eingebettet`);
      return;
    }
    const bv = g.bufferViews?.[img.bufferView];
    if (!bv || !glb.bin) { errs.push(`${label}: bufferView fehlt`); return; }
    const info = sniffImage(glb.bin.subarray(bv.byteOffset ?? 0, (bv.byteOffset ?? 0) + bv.byteLength));
    if (info.type === 'unbekannt') { errs.push(`${label}: weder PNG noch JPEG`); return; }
    if (img.mimeType && img.mimeType !== `image/${info.type}`) errs.push(`${label}: mimeType ${img.mimeType}, Inhalt ist ${info.type}`);
    if (info.progressive) errs.push(`${label}: JPEG ist nicht Baseline (SOF-Marker FF ${info.sof.toString(16).toUpperCase()})`);
    if (!isPow2(info.width) || !isPow2(info.height)) errs.push(`${label}: ${info.width}×${info.height} ist keine Zweierpotenz`);
    if (info.width > 2048 || info.height > 2048) errs.push(`${label}: ${info.width}×${info.height} groesser als 2048`);
    textureBytes += Math.round((info.width * info.height * 4 * 4) / 3);
  });
  for (const tex of g.textures ?? []) {
    for (const ext of Object.keys(tex.extensions ?? {})) errs.push(`Textur: verbotene Erweiterung ${ext}`);
  }

  // Netze
  let triangles = 0;
  const allowed = isTile ? ALLOWED_ATTRIBUTES : MODEL_ATTRIBUTES;
  (g.meshes ?? []).forEach((mesh, i) => {
    const label = `Netz ${mesh.name ?? `#${i}`}`;
    for (const prim of mesh.primitives ?? []) {
      if ((prim.mode ?? 4) !== 4) errs.push(`${label}: Primitiv-Modus ${prim.mode} statt Dreiecke`);
      for (const ext of Object.keys(prim.extensions ?? {})) errs.push(`${label}: verbotene Erweiterung ${ext}`);
      for (const attr of Object.keys(prim.attributes ?? {})) {
        if (!allowed.includes(attr)) errs.push(`${label}: Attribut ${attr} nicht erlaubt`);
      }
      for (const need of ['POSITION', 'NORMAL']) {
        if (prim.attributes?.[need] === undefined) errs.push(`${label}: Attribut ${need} fehlt`);
      }
      if (prim.targets) errs.push(`${label}: Morph-Targets nicht erlaubt`);
      if (prim.material === undefined) errs.push(`${label}: Primitiv ohne Material`);
      const count = prim.indices !== undefined ? g.accessors?.[prim.indices]?.count : g.accessors?.[prim.attributes?.POSITION]?.count;
      triangles += Math.floor((count ?? 0) / 3);
    }
  });

  // Abgleich mit dem Manifest
  if (tile) {
    if (tile.triangles && tile.triangles !== triangles) warns.push(`Manifest nennt ${tile.triangles} Dreiecke, die GLB hat ${triangles}`);
    if (tile.textureBytes !== undefined && Math.abs(tile.textureBytes - textureBytes) > 16) {
      warns.push(`Manifest nennt ${tile.textureBytes} Texturbytes, aus den Bildern folgen ${textureBytes}`);
    }
  }
  return { errors: errs, warnings: warns, triangles, textureBytes };
}

// ---------------------------------------------------------------------------
// Kacheln laden und entladen
// ---------------------------------------------------------------------------

const gltfLoader = new GLTFLoader();
const loaded = new Map(); // id → { tile, state, group, subs, triangles, textureBytes, promise }
const failedTiles = new Map(); // id → Grund
let shadowsDirty = true;

const contractName = (obj) => obj.userData?.name ?? obj.name ?? '';

function forEachMaterial(root, fn) {
  root.traverse((o) => {
    if (!o.isMesh) return;
    for (const m of Array.isArray(o.material) ? o.material : [o.material]) if (m) fn(m, o);
  });
}

/** Was die Darstellung an den gelieferten Materialien einstellt — nichts, was Filament nicht haette. */
function prepareMaterials(root) {
  const textures = new Set();
  forEachMaterial(root, (m) => {
    m.dithering = true; // Filament dithert im Nachbearbeitungsschritt
    // MASK: Filament schaltet fuer `masked` Alpha-to-Coverage ein (MaterialDefinition.cpp:
    // rasterState.alphaToCoverage = blendingMode == MASKED); hier dasselbe (MSAA ist an).
    if (m.alphaTest > 0 && !m.transparent) m.alphaToCoverage = true;
    for (const key of ['map', 'emissiveMap']) {
      const t = m[key];
      if (t && !textures.has(t)) {
        t.anisotropy = MAX_ANISOTROPY;
        t.needsUpdate = true;
        textures.add(t);
      }
    }
  });
  return textures;
}

/** Schatten nach Knotennamen (Tabelle 7.3). */
function applyShadowFlags(sceneRoot) {
  for (const top of sceneRoot.children) {
    const name = contractName(top);
    const cast = name === 'buildings' || name === 'trees';
    const receive = ['terrain', 'route', 'roads', 'water', 'buildings'].includes(name);
    top.traverse((o) => {
      if (o.isMesh) {
        o.castShadow = cast;
        o.receiveShadow = receive;
      }
    });
  }
}

function disposeObject(root) {
  const textures = new Set();
  root.traverse((o) => {
    if (!o.isMesh) return;
    o.geometry?.dispose();
    for (const m of Array.isArray(o.material) ? o.material : [o.material]) {
      if (!m) continue;
      for (const v of Object.values(m)) if (v && v.isTexture) textures.add(v);
      m.dispose();
    }
  });
  for (const t of textures) {
    t.dispose();
    t.source?.data?.close?.(); // ImageBitmap freigeben
  }
}

async function loadTile(tile) {
  const entry = { tile, state: 'loading', group: null, subs: new Map(), triangles: 0, textureBytes: 0, textures: new Set() };
  loaded.set(tile.id, entry);
  const url = `${CONFIG.world}/${tile.file}`;
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status} fuer ${url}`);
    const buffer = await res.arrayBuffer();
    if (CONFIG.check) {
      const result = checkGlb(parseGlb(buffer), 'tile', tile);
      for (const e of result.errors) reportError(`Format ${tile.file}: ${e}`);
      for (const w of result.warnings) reportWarning(`Format ${tile.file}: ${w}`);
    }
    const gltf = await gltfLoader.parseAsync(buffer, '');
    if (loaded.get(tile.id) !== entry) { disposeObject(gltf.scene); return; } // inzwischen abbestellt
    const group = gltf.scene;
    group.name = tile.id;
    // Eckpunkte sind relativ zum Anker: (anchorE, anchorU, −anchorN)
    group.position.set(tile.anchor[0], tile.anchor[2], -tile.anchor[1]);
    applyShadowFlags(group);
    entry.textures = prepareMaterials(group);
    group.traverse((o) => {
      const sub = SUB_RE.exec(contractName(o));
      if (sub) entry.subs.set(`${sub[1]}_${sub[2]}`, o);
      if (o.isMesh) {
        const geo = o.geometry;
        entry.triangles += Math.floor((geo.index ? geo.index.count : geo.attributes.position.count) / 3);
      }
    });
    for (const t of entry.textures) {
      const img = t.image;
      if (img?.width) entry.textureBytes += Math.round((img.width * img.height * 4 * 4) / 3);
    }
    group.visible = false; // erst zeigen, wenn alles fuer die Stelle da ist
    group.updateMatrixWorld(true);
    worldRoot.add(group);
    entry.group = group;
    entry.state = 'ready';
  } catch (e) {
    entry.state = 'failed';
    failedTiles.set(tile.id, String(e.message ?? e));
    reportError(`Kachel ${tile.id} nicht ladbar: ${e.message ?? e}`);
  }
}

function unloadTile(id) {
  const entry = loaded.get(id);
  if (!entry) return;
  loaded.delete(id);
  if (entry.group) {
    worldRoot.remove(entry.group);
    disposeObject(entry.group);
  }
}

/** Laedt, was fehlt (wenige gleichzeitig), und wartet, bis alles da oder gescheitert ist. */
async function ensureTiles(needed) {
  const queue = [...needed].filter((id) => !loaded.has(id)).map((id) => tileById.get(id));
  // Grobe Stufen zuerst: Sie sind der Hintergrund, der nie fehlen darf.
  queue.sort((a, b) => b.level - a.level);
  const workers = Array.from({ length: Math.min(4, queue.length) }, async () => {
    while (queue.length) await loadTile(queue.shift());
  });
  await Promise.all(workers);
}

/**
 * Teilflaechen-Regel (7.2): Eine Teilflaeche `terrain_sub_i_j` einer Kachel der
 * Stufe L ist ausgeblendet, solange die feinere Kachel (ix·4+i, iy·4+j) der
 * Stufe L−1 geladen und sichtbar ist. Reihenfolge: erst die feinen Kacheln
 * einblenden, dann die Teilflaechen ausblenden.
 */
function applyVisibility(needed) {
  for (const [id, entry] of loaded) {
    if (entry.state === 'ready') entry.group.visible = needed.has(id);
  }
  let hidden = 0;
  for (const entry of loaded.values()) {
    if (entry.state !== 'ready' || entry.tile.level < 1) continue;
    const { level, ix, iy } = entry.tile;
    for (const [key, node] of entry.subs) {
      const [i, j] = key.split('_').map(Number);
      const finer = tileByKey.get(tileKey(level - 1, ix * LEVEL_FACTOR + i, iy * LEVEL_FACTOR + j));
      const finerEntry = finer && loaded.get(finer.id);
      const covered = !!finerEntry && finerEntry.state === 'ready' && finerEntry.group.visible;
      node.visible = !covered;
      if (covered) hidden++;
    }
  }
  shadowsDirty = true;
  return hidden;
}

// ---------------------------------------------------------------------------
// Fahrer und Geist
// ---------------------------------------------------------------------------

const actors = { rider: null, ghost: null };

/** Unauffaelliger Platzhalter, solange es rider.glb nicht gibt: Rad und Rumpf als Quader. */
function makePlaceholder(ghost) {
  const g = new THREE.Group();
  const mat = new THREE.MeshStandardMaterial({
    color: ghost ? 0xb8c4d0 : 0x4a4f57, roughness: 0.85, metalness: 0,
    transparent: ghost, opacity: ghost ? 0.4 : 1, depthWrite: !ghost,
  });
  const box = (w, h, d, x, y, z, rx = 0) => {
    const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat);
    m.position.set(x, y, z);
    m.rotation.x = rx;
    g.add(m);
  };
  box(0.08, 0.66, 1.70, 0, 0.36, 0); // Rad
  box(0.36, 0.60, 0.28, 0, 1.18, 0.05, 0.5); // Rumpf, nach vorn geneigt
  box(0.20, 0.22, 0.22, 0, 1.56, 0.36); // Kopf
  box(0.26, 0.50, 0.20, 0, 0.72, -0.05); // Beine
  return { root: g, mixer: null, clips: {}, placeholder: true };
}

async function loadActor(file, ghost) {
  let actor = null;
  if (CONFIG.models) {
    const url = `${CONFIG.models}/${file}`;
    try {
      const res = await fetch(url);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const buffer = await res.arrayBuffer();
      if (CONFIG.check) {
        const result = checkGlb(parseGlb(buffer), 'model', null);
        for (const e of result.errors) reportError(`Format ${file}: ${e}`);
        for (const w of result.warnings) reportWarning(`Format ${file}: ${w}`);
      }
      const gltf = await gltfLoader.parseAsync(buffer, '');
      prepareMaterials(gltf.scene);
      const mixer = new THREE.AnimationMixer(gltf.scene);
      const clips = {};
      for (const name of ['pedal', 'roll']) {
        const clip = gltf.animations.find((c) => c.name === name);
        if (!clip) { reportWarning(`${file}: Animation "${name}" fehlt`); continue; }
        if (Math.abs(clip.duration - 1.0) > 0.02) reportWarning(`${file}: Animation "${name}" dauert ${clip.duration.toFixed(3)} s statt 1,0 s`);
        const action = mixer.clipAction(clip);
        action.play();
        action.paused = true;
        clips[name] = action;
      }
      actor = { root: gltf.scene, mixer, clips, placeholder: false };
    } catch (e) {
      reportWarning(`${file} nicht ladbar (${e.message ?? e}) — Platzhalter`);
    }
  }
  if (!actor) actor = makePlaceholder(ghost);
  actor.root.traverse((o) => {
    if (o.isMesh) {
      o.castShadow = !ghost;
      o.receiveShadow = !ghost;
      o.frustumCulled = false; // Skins haben keine verlaessliche Huelle
    }
  });
  actor.root.rotation.order = 'YXZ';
  actor.root.visible = false;
  scene.add(actor.root);
  return actor;
}

/** Stelle eines Fahrers: `side` +1 = rechts der Mitte (Fahrer), −1 = links (Geist). */
function actorPlacement(dist, side) {
  const pose = poseAt(dist);
  const h = (pose.headingDeg * Math.PI) / 180;
  const offset = side * Math.min(0.8, Math.max(0, widthAt(dist) / 2 - 0.4));
  // rechts der Fahrtrichtung in (e, n): (cos h, −sin h)
  return {
    pose, h, offset,
    e: pose.e + Math.cos(h) * offset,
    n: pose.n - Math.sin(h) * offset,
    u: pose.u,
  };
}

function placeActor(actor, placement, pedalPhase, rollPhase) {
  toScene(placement.e, placement.n, placement.u, actor.root.position);
  // Modell blickt nach +Z; Fahrtrichtung in der Szene ist (sin h, 0, −cos h).
  actor.root.rotation.set(-Math.atan(placement.pose.gradePct / 100), Math.PI - placement.h, 0);
  if (actor.mixer) {
    const frac = (p) => ((p % 1) + 1) % 1;
    if (actor.clips.pedal) actor.clips.pedal.time = frac(pedalPhase) * actor.clips.pedal.getClip().duration;
    if (actor.clips.roll) actor.clips.roll.time = frac(rollPhase) * actor.clips.roll.getClip().duration;
    actor.mixer.update(0);
  }
  actor.root.updateMatrixWorld(true);
}

// ---------------------------------------------------------------------------
// Sonne, Dunst, Kamera
// ---------------------------------------------------------------------------

const sunDir = new THREE.Vector3();

function updateSun() {
  const az = (view.sunAzimuthDeg * Math.PI) / 180;
  const el = (Math.max(8, view.sunElevationDeg) * Math.PI) / 180; // nachts wird nicht dunkel gefahren
  // Richtung ZUR Sonne: Azimut 0 = Nord (−Z), 90 = Ost (+X)
  sunDir.set(Math.sin(az) * Math.cos(el), Math.sin(el), -Math.cos(az) * Math.cos(el));
  skyMaterial.uniforms.sunDir.value.copy(sunDir);
  // Tiefe Sonne ist waermer und schwaecher.
  const k = Math.min(1, Math.sin(el) / Math.sin((40 * Math.PI) / 180));
  sun.color.setRGB(1.0, 0.86 + 0.09 * k, 0.68 + 0.18 * k);
  sun.intensity = 10.0 * (0.55 + 0.45 * k);
  if (skyInfo) {
    // Himmel der Darstellung: Sonnenfarbe und Verhaeltnisse der Staerken wie in Filament
    // (bezogen auf Mittag 110 000 lx Sonne, 34 000 lx Umgebung).
    sun.color.setRGB(...skyInfo.sunColor);
    sun.intensity = 10.0 * (skyInfo.sunLux / 110000);
    hemi.intensity = 2.1 * (skyInfo.ambientLux / 34000);
  }

  // Das Licht scheint von seiner Position zum Ursprung; die Schattenkaskaden folgen der Kamera von selbst.
  sun.position.copy(sunDir);
  sun.updateMatrixWorld();
  shadowsDirty = true;
}

function updateFog() {
  // Sichtweite = Entfernung, in der vom Kontrast noch 2 % uebrig sind (Koschmieder).
  const visibility = VISIBILITY_M[manifest.quality];
  const density = (3.912 / visibility) * (0.25 + 1.5 * Math.min(Math.max(CONFIG.haze, 0), 1));
  fog.density = skyInfo ? skyInfo.fogDensity : density; // gilt auf Kamerahoehe, siehe Shader oben
  camera.far = visibility * 1.6;
}

const CAMERAS = ['chase', 'handlebar', 'drone'];

/** Stellt die Kamera fuer die aktuelle Stelle; liefert den Punkt, auf den sie blickt. */
function updateCamera(placement) {
  const d = view.dist;
  const fwd = (dist) => {
    const p = poseAtExtended(dist);
    const hh = (p.headingDeg * Math.PI) / 180;
    return { e: p.e + Math.cos(hh) * placement.offset, n: p.n - Math.sin(hh) * placement.offset, u: p.u };
  };
  const rider = toScene(placement.e, placement.n, placement.u);
  // Fahrtrichtung, ueber ein Stueck Strecke gemittelt (ruhiger als der Kurs einer einzelnen Probe)
  const a = fwd(d - 6);
  const b = fwd(d + 6);
  const dir = toScene(b.e, b.n, 0).sub(toScene(a.e, a.n, 0)).setY(0);
  if (dir.lengthSq() < 1e-6) dir.set(Math.sin(placement.h), 0, -Math.cos(placement.h));
  dir.normalize();
  const left = new THREE.Vector3(dir.z, 0, -dir.x);
  const pos = new THREE.Vector3();
  const look = new THREE.Vector3();
  let fov = 50;
  let near = 0.5;

  if (view.camera === 'handlebar') {
    // wie CameraRig HANDLEBAR: Auge 1,45 m, 0,25 m voraus, Blick 25 m voraus auf 0,8 × Augenhoehe
    pos.copy(rider).addScaledVector(dir, 0.25).setY(rider.y + 1.45);
    const ahead = fwd(d + 25);
    toScene(ahead.e, ahead.n, ahead.u + 1.16, look);
    pos.y = Math.max(pos.y, placement.u + 1.0);
    fov = 62;
    near = 0.3;
  } else if (view.camera === 'drone') {
    pos.copy(rider).addScaledVector(dir, -125).addScaledVector(left, 55).setY(rider.y + 85);
    const ahead = fwd(d + 70);
    toScene(ahead.e, ahead.n, ahead.u, look);
    fov = 42;
    near = 5;
  } else {
    // wie CameraRig CHASE: 6 m auf dem Band zurueck (folgt Kurven und Kehren), 2,5 m ueber dem
    // hoeheren von Fahrer und Kamerapunkt, Blick 6 m voraus auf 1,1 m ueber der mittleren Hoehe
    const back = fwd(d - 6);
    toScene(back.e, back.n, Math.max(back.u, placement.u) + 2.5, pos);
    pos.y = Math.max(pos.y, Math.max(back.u, placement.u) + 1.2);
    const ahead = fwd(d + 6);
    toScene(ahead.e, ahead.n, (placement.u + ahead.u) / 2 + 1.1, look);
    fov = 52;
    near = 0.5;
  }
  camera.fov = fov;
  camera.near = CONFIG.near > 0 ? CONFIG.near : near;
  camera.position.copy(pos);
  camera.up.set(0, 1, 0);
  camera.lookAt(look);
  camera.updateProjectionMatrix();
  controls.target.copy(look);
  controls.update();
  return look;
}

// ---------------------------------------------------------------------------
// Zeichnen (nur auf Anforderung)
// ---------------------------------------------------------------------------

let renderQueued = false;
let lastRender = { calls: 0, triangles: 0, ms: 0 };

function resize() {
  const w = canvas.clientWidth || window.innerWidth;
  const h = canvas.clientHeight || window.innerHeight;
  if (canvas.width !== w || canvas.height !== h) renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}

function renderNow() {
  renderQueued = false;
  resize();
  sky.position.copy(camera.position);
  if (shadowsDirty) {
    renderer.shadowMap.needsUpdate = true;
    shadowsDirty = false;
  }
  renderer.info.reset();
  const t0 = performance.now();
  if (CONFIG.hdr) hdrRender();
  else renderer.render(scene, camera);
  renderer.getContext().finish(); // sonst misst die Zeit nur das Absetzen der Befehle
  lastRender = { calls: renderer.info.render.calls, triangles: renderer.info.render.triangles, ms: performance.now() - t0 };
  updateHud();
}

let composer = null;
function hdrRender() {
  const size = renderer.getDrawingBufferSize(new THREE.Vector2());
  if (!composer) {
    const rt = new THREE.WebGLRenderTarget(size.x, size.y, { type: THREE.HalfFloatType, samples: 4 });
    composer = new EffectComposer(renderer, rt);
    composer.addPass(new RenderPass(scene, camera));
    composer.addPass(new OutputPass());
  }
  composer.setPixelRatio(1);
  composer.setSize(size.x, size.y);
  composer.render();
}

function requestRender() {
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(renderNow);
}

window.addEventListener('resize', requestRender);

const nextFrame = () => new Promise((r) => requestAnimationFrame(() => r()));

// ---------------------------------------------------------------------------
// API
// ---------------------------------------------------------------------------

let chain = Promise.resolve();

/**
 * Stellt Stelle, Kamera, Sonne und Fahrer ein. Erfuellt erst, wenn alle fuer
 * die Stelle noetigen Kacheln geladen (oder gescheitert), die Texturen im
 * Grafikspeicher und ein Bild gezeichnet ist.
 */
function setView(opts = {}) {
  const run = async () => {
    await ready;
    const t0 = performance.now();
    if (opts.dist !== undefined) view.dist = Number(opts.dist);
    if (opts.camera !== undefined) {
      const c = String(opts.camera).toLowerCase();
      if (!CAMERAS.includes(c)) throw new Error(`Kamera "${opts.camera}" unbekannt (${CAMERAS.join(', ')})`);
      view.camera = c;
    }
    if (opts.sunAzimuthDeg !== undefined) view.sunAzimuthDeg = Number(opts.sunAzimuthDeg);
    if (opts.sunElevationDeg !== undefined) view.sunElevationDeg = Number(opts.sunElevationDeg);
    if (opts.rider !== undefined) view.rider = !!opts.rider;
    if (opts.pedalPhase !== undefined) view.pedalPhase = Number(opts.pedalPhase);
    if (opts.rollPhase !== undefined) view.rollPhase = Number(opts.rollPhase);
    if (opts.ghostDist !== undefined) view.ghostDist = opts.ghostDist === null ? null : Number(opts.ghostDist);

    const needed = neededTiles(view.dist);
    await ensureTiles(needed);
    for (const id of [...loaded.keys()]) if (!needed.has(id)) unloadTile(id);
    const hiddenSubs = applyVisibility(needed);

    // Welt gegen die Kamera verschieben
    const placement = actorPlacement(view.dist, +1);
    origin.e = Math.round(placement.e);
    origin.n = Math.round(placement.n);
    worldRoot.position.set(-origin.e, 0, origin.n);
    worldRoot.updateMatrixWorld(true);

    // Steht der Fahrer auf geladenem Gelaende?
    const covered = [...loaded.values()].some((en) => {
      if (en.state !== 'ready') return false;
      const c = 256 * LEVEL_FACTOR ** en.tile.level;
      return Math.floor(placement.e / c) === en.tile.ix && Math.floor(placement.n / c) === en.tile.iy;
    });
    if (!covered) reportError(`Bei Laufmeter ${foldDist(view.dist).toFixed(0)} liegt keine geladene Kachel unter dem Fahrer (show lueckenhaft?)`);
    const fine = tileByKey.get(tileKey(0, Math.floor(placement.e / 256), Math.floor(placement.n / 256)));
    if (!fine || !needed.has(fine.id)) reportWarning(`Bei Laufmeter ${foldDist(view.dist).toFixed(0)} steht der Fahrer auf keiner Stufe-0-Kachel`);

    const showRider = view.rider && view.camera !== 'handlebar'; // am Lenker saesse die Kamera im Kopf
    actors.rider.root.visible = showRider;
    placeActor(actors.rider, placement, view.pedalPhase, view.rollPhase);
    const ghostOn = view.ghostDist !== null && Number.isFinite(view.ghostDist);
    actors.ghost.root.visible = ghostOn;
    if (ghostOn) placeActor(actors.ghost, actorPlacement(view.ghostDist, -1), view.pedalPhase + 0.4, view.rollPhase);

    updateFog();
    updateCamera(placement);
    updateSun();

    // Erstes Bild uebersetzt Shader und laedt Texturen hoch, das zweite ist das fertige.
    renderNow();
    renderNow();
    await nextFrame();
    await nextFrame();
    const failed = [...needed].filter((id) => loaded.get(id)?.state === 'failed');
    return {
      ok: failed.length === 0 && covered,
      dist: foldDist(view.dist),
      camera: view.camera,
      neededTiles: needed.size,
      failedTiles: failed,
      hiddenSubs,
      loadMs: Math.round(performance.now() - t0),
      renderMs: Math.round(lastRender.ms),
    };
  };
  const p = chain.then(run, run);
  chain = p.catch(() => {});
  return p;
}

function stats() {
  let triangles = 0;
  let visibleTriangles = 0;
  let textureBytes = 0;
  let textures = 0;
  let manifestTextureBytes = 0;
  let manifestTriangles = 0;
  const byLevel = {};
  for (const en of loaded.values()) {
    if (en.state !== 'ready') continue;
    triangles += en.triangles;
    textureBytes += en.textureBytes;
    textures += en.textures.size;
    manifestTextureBytes += en.tile.textureBytes ?? 0;
    manifestTriangles += en.tile.triangles ?? 0;
    byLevel[en.tile.level] = (byLevel[en.tile.level] ?? 0) + 1;
    en.group.traverseVisible((o) => {
      if (o.isMesh) visibleTriangles += Math.floor((o.geometry.index ? o.geometry.index.count : o.geometry.attributes.position.count) / 3);
    });
  }
  return {
    loadedTiles: [...loaded.values()].filter((en) => en.state === 'ready').length,
    tilesByLevel: byLevel,
    failedTiles: [...failedTiles.keys()],
    triangles, // Summe der geladenen Kacheln
    visibleTriangles, // ohne ausgeblendete Teilflaechen
    renderedTriangles: lastRender.triangles, // letztes Bild samt Schattendurchgang
    drawCalls: lastRender.calls, // letztes Bild samt Schattendurchgang
    textures,
    textureBytes, // geschaetzt: Breite × Hoehe × 4 × 4/3
    manifestTextureBytes,
    manifestTriangles,
    budget: manifest?.budget ?? null,
    renderMs: Math.round(lastRender.ms),
    glMemory: { geometries: renderer.info.memory.geometries, textures: renderer.info.memory.textures },
    errors: errors.length,
    warnings: warnings.length,
  };
}

/** Mittelwert und Streuung der Helligkeit des letzten Bilds (0..255) — fuer die Schwarzbild-Pruefung. */
function pixelStats() {
  const gl = renderer.getContext();
  const w = gl.drawingBufferWidth;
  const h = gl.drawingBufferHeight;
  const px = new Uint8Array(w * h * 4);
  gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, px);
  let n = 0, sum = 0, sum2 = 0, dark = 0;
  for (let i = 0; i < px.length; i += 4 * 7) {
    const l = 0.2126 * px[i] + 0.7152 * px[i + 1] + 0.0722 * px[i + 2];
    sum += l; sum2 += l * l; n++;
    if (l < 6) dark++;
  }
  const mean = sum / n;
  return { width: w, height: h, mean, stddev: Math.sqrt(Math.max(0, sum2 / n - mean * mean)), darkFraction: dark / n };
}

// ---------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------

function updateHud() {
  const hud = document.getElementById('hud');
  if (!CONFIG.ui || !manifest) return;
  const s = stats();
  hud.hidden = false;
  hud.textContent = [
    `${manifest.routeName || manifest.worldId}  ·  ${manifest.quality}`,
    `Laufmeter ${foldDist(view.dist).toFixed(0)} / ${track.lengthM.toFixed(0)}  ·  Kamera ${view.camera}  ·  Sonne ${view.sunAzimuthDeg}°/${view.sunElevationDeg}°`,
    `Kacheln ${s.loadedTiles} ${JSON.stringify(s.tilesByLevel)}  ·  ${s.visibleTriangles} Dreiecke  ·  ${s.drawCalls} Draw Calls  ·  ${(s.textureBytes / 1048576).toFixed(0)} MB Texturen  ·  ${s.renderMs} ms`,
    errors.length ? `${errors.length} FEHLER (Konsole)` : 'Format in Ordnung',
    '←/→ Strecke (Umschalt ×10) · 1/2/3 Kamera · Q/E Sonnenazimut · R/F Sonnenhoehe · G Fahrer · Maus: drehen',
  ].join('\n');
}

window.addEventListener('keydown', (ev) => {
  if (!manifest) return;
  const step = ev.shiftKey ? 250 : 25;
  const o = {};
  switch (ev.key) {
    case 'ArrowRight': case 'ArrowUp': case 'w': case 'W': o.dist = view.dist + step; break;
    case 'ArrowLeft': case 'ArrowDown': case 's': case 'S': o.dist = view.dist - step; break;
    case '1': o.camera = 'chase'; break;
    case '2': o.camera = 'handlebar'; break;
    case '3': o.camera = 'drone'; break;
    case 'q': o.sunAzimuthDeg = (view.sunAzimuthDeg + 345) % 360; break;
    case 'e': o.sunAzimuthDeg = (view.sunAzimuthDeg + 15) % 360; break;
    case 'r': o.sunElevationDeg = Math.min(85, view.sunElevationDeg + 5); break;
    case 'f': o.sunElevationDeg = Math.max(8, view.sunElevationDeg - 5); break;
    case 'g': o.rider = !view.rider; break;
    default: return;
  }
  ev.preventDefault();
  setView(o).catch((e) => reportError(String(e.message ?? e)));
});

/** Himmel der Darstellung laden (skyDir): Rundumbild, Sonne, Licht und Dunst wie in Filament. */
let skyInfo = null;
async function loadSky() {
  const info = await (await fetch(`${CONFIG.skyDir}/sky.json`)).json();
  const buf = await (await fetch(`${CONFIG.skyDir}/sky.f32`)).arrayBuffer();
  const rgb = new Float32Array(buf);
  const w = info.skyWidth; const h = info.skyHeight;
  if (rgb.length !== w * h * 3) throw new Error(`sky.f32: ${rgb.length} Werte statt ${w * h * 3}`);
  const rgba = new Float32Array(w * h * 4);
  for (let i = 0; i < w * h; i++) { rgba[i * 4] = rgb[i * 3]; rgba[i * 4 + 1] = rgb[i * 3 + 1]; rgba[i * 4 + 2] = rgb[i * 3 + 2]; rgba[i * 4 + 3] = 1; }
  const tex = new THREE.DataTexture(rgba, w, h, THREE.RGBAFormat, THREE.FloatType);
  tex.wrapS = THREE.RepeatWrapping;
  tex.magFilter = THREE.LinearFilter;
  tex.minFilter = THREE.LinearFilter;
  tex.needsUpdate = true;
  const u = skyMaterial.uniforms;
  u.skyTex.value = tex;
  u.useTex.value = 1;
  u.sunColor.value.set(...info.sunColor);
  u.sunPreExposed.value = info.sunLux * info.exposure;
  skyMaterial.toneMapped = false;
  // Sonne aus der Darstellung, ausser die Seite bekam sie ausdruecklich.
  if (!params.has('sunAz')) view.sunAzimuthDeg = info.sunAzimuthDeg;
  if (!params.has('sunEl')) view.sunElevationDeg = info.sunElevationDeg;
  // Dunst: Farbe so, dass sie nach der Abbildung der Welt wie der Horizont der Darstellung aussieht.
  const target = filamentAcesJs(info.skyHorizon);
  const f = invertThreeAces(target, CONFIG.exposure);
  fog.color.setRGB(f[0], f[1], f[2], THREE.LinearSRGBColorSpace);
  scene.background = new THREE.Color().setRGB(target[0], target[1], target[2], THREE.LinearSRGBColorSpace);
  skyInfo = info;
}

const ready = (async () => {
  if (CONFIG.skyDir) await loadSky();
  const url = `${CONFIG.world}/manifest.json`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Manifest nicht ladbar: HTTP ${res.status} fuer ${url}`);
  manifest = parseManifest(await res.json());
  track = manifest.track;
  track.loop = !!track.loop;
  if (CONFIG.attribution) {
    document.getElementById('attribution').textContent = (manifest.attribution ?? []).map((a) => a.text).filter(Boolean).join(' · ');
  }
  [actors.rider, actors.ghost] = await Promise.all([loadActor('rider.glb', false), loadActor('ghost.glb', true)]);
  return manifest;
})();

ready.catch((e) => {
  reportError(String(e.message ?? e));
  showMessage(`Welt nicht darstellbar:\n${e.message ?? e}`);
});

window.__viewer = {
  /** Erfuellt mit dem Manifest, sobald es gelesen und die Modelle geladen sind. */
  ready,
  setView,
  stats,
  pixelStats,
  /** Formatfehler und Ladefehler (Text) — nicht leer heisst: Welt verletzt den Vertrag. */
  errors,
  warnings,
  poseAt: (d) => poseAt(d),
  neededAt: (d) => manifest.tiles.filter((t) => neededAt(t, foldDist(d))).map((t) => t.id),
  get view() { return { ...view }; },
  get manifest() { return manifest; },
  three: { THREE, scene, camera, renderer, sun, hemi, fog, skyMaterial },
  render: renderNow,
};

// Lokal geoeffnet: gleich die Stelle aus der URL zeigen.
if (pFlag('auto', true)) {
  ready.then(() => setView({})).catch(() => {});
}
