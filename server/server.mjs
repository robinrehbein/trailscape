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
const MIN_TOKEN_LENGTH = 16;

if (!SYNC_TOKEN || SYNC_TOKEN.trim() === '') {
  console.error(
    'FEHLER: Die Umgebungsvariable SYNC_TOKEN ist nicht gesetzt. ' +
      'Bitte einen geheimen Token vergeben, z. B.: SYNC_TOKEN=$(openssl rand -hex 32) node server.mjs'
  );
  process.exit(1);
}

if (SYNC_TOKEN.trim().length < MIN_TOKEN_LENGTH) {
  console.error(
    `FEHLER: SYNC_TOKEN ist zu kurz (${SYNC_TOKEN.trim().length} Zeichen, Minimum: ${MIN_TOKEN_LENGTH}). ` +
      'Der Token schützt sämtliche GPS-Spuren – bitte einen langen, zufälligen Wert verwenden, ' +
      'z. B.: SYNC_TOKEN=$(openssl rand -hex 32) node server.mjs'
  );
  process.exit(1);
}

const RIDE_ID_PATTERN = /^[A-Za-z0-9-]{1,64}$/;

// Datei mit den Lösch-Merkzetteln (Tombstones); liegt im DATA_DIR neben den
// Tour-Dateien und wird beim Auflisten der Touren übersprungen.
const TOMBSTONES_FILE = 'tombstones.json';

// ---------------------------------------------------------------------------
// Hilfsfunktionen
// ---------------------------------------------------------------------------

/** Sendet eine JSON-Antwort. */
function sendJson(res, statusCode, body, extraHeaders = {}) {
  const payload = JSON.stringify(body);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    ...extraHeaders,
  });
  res.end(payload);
}

/** Sendet eine leere Antwort (z. B. 204). */
function sendEmpty(res, statusCode) {
  res.writeHead(statusCode);
  res.end();
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

// ---------------------------------------------------------------------------
// Rate-Limit für fehlgeschlagene Authentifizierungen
// ---------------------------------------------------------------------------
// Simpel und in-memory: Nach AUTH_MAX_FAILURES Fehlversuchen pro IP innerhalb
// von AUTH_WINDOW_MS antwortet der Server mit 429 (inkl. Retry-After). Ein
// Neustart setzt die Zähler zurück – für einen Selfhost-Server mit einem
// Nutzer ist das der richtige Kompromiss aus Schutz und Einfachheit.

const AUTH_WINDOW_MS = 15 * 60 * 1000; // 15 Minuten
const AUTH_MAX_FAILURES = 10;

/** IP -> { count, windowStart } */
const authFailures = new Map();

/** Liefert die verbleibende Sperrzeit in Sekunden, oder 0 wenn nicht gesperrt. */
function rateLimitRetryAfterSeconds(ip) {
  const entry = authFailures.get(ip);
  if (!entry) return 0;
  const elapsed = Date.now() - entry.windowStart;
  if (elapsed > AUTH_WINDOW_MS) {
    authFailures.delete(ip);
    return 0;
  }
  if (entry.count < AUTH_MAX_FAILURES) return 0;
  return Math.max(1, Math.ceil((AUTH_WINDOW_MS - elapsed) / 1000));
}

/** Merkt sich einen Fehlversuch und protokolliert ihn (Zeitstempel + IP, OHNE Token). */
function recordAuthFailure(ip) {
  const now = Date.now();
  const entry = authFailures.get(ip);
  if (!entry || now - entry.windowStart > AUTH_WINDOW_MS) {
    authFailures.set(ip, { count: 1, windowStart: now });
  } else {
    entry.count += 1;
  }
  console.warn(`${new Date(now).toISOString()} Fehlgeschlagene Authentifizierung von ${ip}`);
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

/** Letzte Änderung einer Tour: updatedAt aus dem JSON, sonst createdAt. */
function rideUpdatedAt(parsed) {
  if (typeof parsed.updatedAt === 'number') return parsed.updatedAt;
  return parsed.createdAt;
}

/** Schreibt Daten atomar: erst in eine temporäre Datei, dann umbenennen. */
async function writeFileAtomic(filePath, data) {
  const tmpPath = `${filePath}.${crypto.randomBytes(6).toString('hex')}.tmp`;
  await fs.writeFile(tmpPath, data, 'utf8');
  await fs.rename(tmpPath, filePath);
}

// ---------------------------------------------------------------------------
// Tombstones (Lösch-Merkzettel)
// ---------------------------------------------------------------------------
// Ohne Merkzettel würde ein zweites Gerät eine gelöschte Tour einfach wieder
// hochladen. Deshalb merkt sich der Server jede Löschung als
// { id, deletedAt } (ms seit Epoch), liefert sie in GET /api/rides als
// { id, deleted: true, deletedAt } mit aus und räumt den Merkzettel erst
// wieder weg, wenn eine NEUERE Bearbeitung (updatedAt > deletedAt) die Tour
// wiederbelebt. Gehalten in-memory (Map id -> { deletedAt, name }), persistiert
// als JSON-Array in DATA_DIR/tombstones.json (atomar geschrieben).

/** id -> { deletedAt, name } */
const tombstones = new Map();

async function loadTombstones() {
  let raw;
  try {
    raw = await fs.readFile(path.join(DATA_DIR, TOMBSTONES_FILE), 'utf8');
  } catch {
    return; // Datei existiert (noch) nicht – leerer Bestand.
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    console.warn('WARNUNG: tombstones.json ist nicht lesbar und wird ignoriert.');
    return;
  }
  if (!Array.isArray(parsed)) return;
  for (const entry of parsed) {
    if (
      entry &&
      typeof entry === 'object' &&
      typeof entry.id === 'string' &&
      RIDE_ID_PATTERN.test(entry.id) &&
      typeof entry.deletedAt === 'number'
    ) {
      tombstones.set(entry.id, {
        deletedAt: entry.deletedAt,
        name: typeof entry.name === 'string' ? entry.name : '',
      });
    }
  }
}

async function saveTombstones() {
  const list = [...tombstones.entries()].map(([id, t]) => ({
    id,
    name: t.name,
    deletedAt: t.deletedAt,
  }));
  await writeFileAtomic(path.join(DATA_DIR, TOMBSTONES_FILE), JSON.stringify(list));
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
  const liveIds = new Set();
  for (const entry of entries) {
    if (!entry.endsWith('.json')) continue;
    if (entry === TOMBSTONES_FILE) continue;
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
        // updatedAt: aus dem JSON; alte Dateien ohne das Feld fallen auf
        // createdAt zurück, kaputte Zeitstempel auf die Datei-mtime.
        let updatedAt = rideUpdatedAt(parsed);
        if (typeof updatedAt !== 'number') {
          try {
            updatedAt = Math.round((await fs.stat(filePath)).mtimeMs);
          } catch {
            updatedAt = 0;
          }
        }
        liveIds.add(parsed.id);
        rides.push({
          id: parsed.id,
          name: parsed.name,
          createdAt: parsed.createdAt,
          updatedAt,
        });
      }
    } catch {
      // Defekte oder unlesbare Datei überspringen.
      continue;
    }
  }

  // Lösch-Merkzettel mit ausliefern, damit Clients lokal nachziehen können.
  // Existiert (inkonsistenterweise) noch eine Datei zur selben ID, gewinnt
  // die Datei – der Merkzettel wird dann nicht mit ausgeliefert.
  for (const [id, t] of tombstones) {
    if (liveIds.has(id)) continue;
    rides.push({ id, name: t.name, deleted: true, deletedAt: t.deletedAt });
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

  // Tombstone-Abgleich: Nur eine Bearbeitung, die NEUER als die Löschung
  // ist, darf die Tour wiederbeleben – sie räumt den Merkzettel weg.
  // Alles Ältere wird abgewiesen, sonst würde eine Löschung durch das
  // erneute Hochladen von einem zweiten Gerät rückgängig gemacht.
  const tombstone = tombstones.get(id);
  if (tombstone) {
    if (rideUpdatedAt(parsed) > tombstone.deletedAt) {
      tombstones.delete(id);
      try {
        await saveTombstones();
      } catch (err) {
        console.error('Fehler beim Schreiben der Tombstones', err);
      }
    } else {
      sendJson(res, 409, {
        error: 'Tour wurde gelöscht; die hochgeladene Fassung ist älter als die Löschung',
      });
      return;
    }
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
  // Namen für die Liste merken (rein informativ; best effort).
  let name = tombstones.get(id)?.name || '';
  try {
    const parsed = JSON.parse(await fs.readFile(ridePath(id), 'utf8'));
    if (parsed && typeof parsed.name === 'string') name = parsed.name;
  } catch {
    // Datei fehlt oder ist unlesbar – der Merkzettel entsteht trotzdem.
  }

  tombstones.set(id, { deletedAt: Date.now(), name });
  try {
    await saveTombstones();
  } catch (err) {
    console.error('Fehler beim Schreiben der Tombstones', err);
    sendJson(res, 500, { error: 'Fehler beim Speichern der Löschung' });
    return;
  }

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
  const method = req.method || 'GET';

  let pathname;
  try {
    pathname = new URL(req.url, `http://${req.headers.host || 'localhost'}`).pathname;
  } catch {
    sendJson(res, 400, { error: 'Ungültige Anfrage-URL' });
    return;
  }

  const ip = req.socket.remoteAddress || 'unbekannt';

  const retryAfter = rateLimitRetryAfterSeconds(ip);
  if (retryAfter > 0) {
    sendJson(
      res,
      429,
      { error: 'Zu viele fehlgeschlagene Anmeldeversuche – bitte später erneut versuchen' },
      { 'Retry-After': String(retryAfter) }
    );
    return;
  }

  if (!isAuthorized(req)) {
    recordAuthFailure(ip);
    sendJson(res, 401, { error: 'Nicht autorisiert' });
    return;
  }
  authFailures.delete(ip);

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
  await loadTombstones();

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
