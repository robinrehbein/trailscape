// Trailscape Sync-Server
// Minimaler Selfhost-Sync-Server für Trailscape-Touren.
// Null Abhängigkeiten – nur Node-Core-Module (Node.js >= 18).

import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

// ---------------------------------------------------------------------------
// Konfiguration
// ---------------------------------------------------------------------------

const PORT = Number(process.env.PORT) || 8080;
const SYNC_TOKEN = process.env.SYNC_TOKEN;
const DATA_DIR = path.resolve(process.env.DATA_DIR || './data');
const MAX_BODY_BYTES = 20 * 1024 * 1024; // 20 MB

if (!SYNC_TOKEN || SYNC_TOKEN.trim() === '') {
  console.error(
    'FEHLER: Die Umgebungsvariable SYNC_TOKEN ist nicht gesetzt. ' +
      'Bitte einen geheimen Token vergeben, z. B.: SYNC_TOKEN=geheim node server.mjs'
  );
  process.exit(1);
}

const RIDE_ID_PATTERN = /^[A-Za-z0-9-]{1,64}$/;

// ---------------------------------------------------------------------------
// Hilfsfunktionen
// ---------------------------------------------------------------------------

/** Sendet eine JSON-Antwort inklusive CORS-Headern. */
function sendJson(res, statusCode, body) {
  const payload = JSON.stringify(body);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
  });
  res.end(payload);
}

/** Sendet eine leere Antwort (z. B. 204) inklusive CORS-Headern. */
function sendEmpty(res, statusCode) {
  res.writeHead(statusCode);
  res.end();
}

/** Setzt die CORS-Header, die für jede Antwort gelten. */
function setCorsHeaders(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, PUT, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
}

/** Prüft den Authorization-Header timing-sicher gegen SYNC_TOKEN. */
function isAuthorized(req) {
  const header = req.headers['authorization'] || '';
  const match = /^Bearer (.+)$/.exec(header);
  if (!match) return false;

  const provided = match[1];

  // Timing-sicherer Vergleich: beide Werte hashen, damit die Länge
  // des Klartexts keinen Einfluss auf die Vergleichszeit hat und
  // crypto.timingSafeEqual auf gleich lange Buffer angewendet werden kann.
  const providedHash = crypto.createHash('sha256').update(provided).digest();
  const expectedHash = crypto.createHash('sha256').update(SYNC_TOKEN).digest();

  return crypto.timingSafeEqual(providedHash, expectedHash);
}

/** Liest den Request-Body ein, begrenzt auf MAX_BODY_BYTES. Wirft bei Überschreitung. */
function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let totalBytes = 0;
    let tooLarge = false;

    req.on('data', (chunk) => {
      totalBytes += chunk.length;
      if (totalBytes > MAX_BODY_BYTES) {
        tooLarge = true;
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });

    req.on('end', () => {
      if (tooLarge) {
        reject(new Error('PAYLOAD_TOO_LARGE'));
        return;
      }
      resolve(Buffer.concat(chunks));
    });

    req.on('error', (err) => {
      reject(err);
    });

    req.on('aborted', () => {
      if (tooLarge) {
        reject(new Error('PAYLOAD_TOO_LARGE'));
      }
    });
  });
}

/** Pfad zur JSON-Datei einer Tour. */
function ridePath(id) {
  return path.join(DATA_DIR, `${id}.json`);
}

/** Validiert, ob der übergebene Wert eine gültige Ride-Struktur ist. */
function isValidRide(value, id) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  if (value.id !== id) return false;
  if (typeof value.name !== 'string') return false;
  if (typeof value.createdAt !== 'number') return false;
  if (!Array.isArray(value.points)) return false;
  return true;
}

/** Schreibt Daten atomar: erst in eine temporäre Datei, dann umbenennen. */
async function writeFileAtomic(filePath, data) {
  const tmpPath = `${filePath}.${crypto.randomBytes(6).toString('hex')}.tmp`;
  await fs.writeFile(tmpPath, data, 'utf8');
  await fs.rename(tmpPath, filePath);
}

// ---------------------------------------------------------------------------
// Routen-Handler
// ---------------------------------------------------------------------------

async function handleListRides(res) {
  let entries;
  try {
    entries = await fs.readdir(DATA_DIR);
  } catch {
    entries = [];
  }

  const rides = [];
  for (const entry of entries) {
    if (!entry.endsWith('.json')) continue;
    const filePath = path.join(DATA_DIR, entry);
    try {
      const raw = await fs.readFile(filePath, 'utf8');
      const parsed = JSON.parse(raw);
      if (
        parsed &&
        typeof parsed.id === 'string' &&
        typeof parsed.name === 'string' &&
        typeof parsed.createdAt === 'number'
      ) {
        rides.push({ id: parsed.id, name: parsed.name, createdAt: parsed.createdAt });
      }
    } catch {
      // Defekte oder unlesbare Datei überspringen.
      continue;
    }
  }

  sendJson(res, 200, rides);
}

async function handleGetRide(res, id) {
  try {
    const raw = await fs.readFile(ridePath(id), 'utf8');
    const parsed = JSON.parse(raw);
    sendJson(res, 200, parsed);
  } catch {
    sendJson(res, 404, { error: 'Nicht gefunden' });
  }
}

async function handlePutRide(req, res, id) {
  let body;
  try {
    body = await readBody(req);
  } catch (err) {
    if (err.message === 'PAYLOAD_TOO_LARGE') {
      sendJson(res, 413, { error: 'Anfrage zu groß (Limit: 20 MB)' });
    } else {
      sendJson(res, 400, { error: 'Fehler beim Lesen der Anfrage' });
    }
    return;
  }

  let parsed;
  try {
    parsed = JSON.parse(body.toString('utf8'));
  } catch {
    sendJson(res, 400, { error: 'Ungültiges JSON' });
    return;
  }

  if (!isValidRide(parsed, id)) {
    sendJson(res, 400, {
      error: 'Ungültige Tour: erwartet Objekt mit passender id, string name, number createdAt, Array points',
    });
    return;
  }

  try {
    await writeFileAtomic(ridePath(id), JSON.stringify(parsed));
    sendEmpty(res, 204);
  } catch (err) {
    console.error('Fehler beim Speichern der Tour', id, err);
    sendJson(res, 500, { error: 'Fehler beim Speichern' });
  }
}

async function handleDeleteRide(res, id) {
  try {
    await fs.unlink(ridePath(id));
  } catch {
    // Datei existierte nicht – wird ebenfalls als Erfolg behandelt.
  }
  sendEmpty(res, 204);
}

// ---------------------------------------------------------------------------
// Request-Routing
// ---------------------------------------------------------------------------

async function handleRequest(req, res) {
  setCorsHeaders(res);

  const method = req.method || 'GET';

  if (method === 'OPTIONS') {
    sendEmpty(res, 204);
    return;
  }

  let pathname;
  try {
    pathname = new URL(req.url, `http://${req.headers.host || 'localhost'}`).pathname;
  } catch {
    sendJson(res, 400, { error: 'Ungültige Anfrage-URL' });
    return;
  }

  if (!isAuthorized(req)) {
    sendJson(res, 401, { error: 'Nicht autorisiert' });
    return;
  }

  if (pathname === '/api/rides' && method === 'GET') {
    await handleListRides(res);
    return;
  }

  const rideMatch = /^\/api\/rides\/([^/]+)$/.exec(pathname);
  if (rideMatch) {
    const id = decodeURIComponent(rideMatch[1]);

    if (!RIDE_ID_PATTERN.test(id)) {
      sendJson(res, 400, { error: 'Ungültige Touren-ID' });
      return;
    }

    if (method === 'GET') {
      await handleGetRide(res, id);
      return;
    }
    if (method === 'PUT') {
      await handlePutRide(req, res, id);
      return;
    }
    if (method === 'DELETE') {
      await handleDeleteRide(res, id);
      return;
    }
  }

  sendJson(res, 404, { error: 'Nicht gefunden' });
}

// ---------------------------------------------------------------------------
// Server-Start
// ---------------------------------------------------------------------------

async function main() {
  await fs.mkdir(DATA_DIR, { recursive: true });

  const server = http.createServer((req, res) => {
    handleRequest(req, res).catch((err) => {
      console.error('Unerwarteter Fehler bei der Anfragebearbeitung:', err);
      if (!res.headersSent) {
        sendJson(res, 500, { error: 'Interner Serverfehler' });
      } else {
        res.end();
      }
    });
  });

  server.listen(PORT, () => {
    console.log(`Trailscape-Sync-Server läuft auf Port ${PORT} – Datenverzeichnis: ${DATA_DIR}`);
  });
}

main().catch((err) => {
  console.error('Server konnte nicht gestartet werden:', err);
  process.exit(1);
});
