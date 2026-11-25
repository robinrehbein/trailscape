// Raum-Dienst für verabredete Gruppenfahrten in Echtzeit (Protokoll v1).
//
// Spezifikation: docs/raum-protokoll.md. Kurz:
//  * Räume leben NUR im Arbeitsspeicher. Nichts geht auf die Platte, nichts
//    über Positionen, Namen oder Geheimnisse ins Protokoll (Log).
//  * Anlegen nur mit SYNC_TOKEN (REST, server.mjs); Beitreten mit dem
//    Raum-Geheimnis aus dem Einladungslink über WebSocket. Das Geheimnis
//    öffnet nur diesen einen Raum und gilt nirgends sonst.
//  * Geheimnisse liegen nur als SHA-256 im Speicher und werden
//    konstantzeitig verglichen.
//
// Dieser Baustein kennt keine Sockets: Eine Verbindung ist ein Objekt mit
// `send(text)` und `close(code, reason)`. Uhr und Zufall sind einspeisbar,
// damit sich Zeitüberschreitungen und Grenzen ohne Warten testen lassen.

import crypto from 'node:crypto';

export const PROTOCOL_VERSIONS = [1];

/** Schließcodes (WebSocket, Bereich 4000–4999 für Anwendungen). */
export const CLOSE = Object.freeze({
  NORMAL: 1000,
  POLICY: 1008,
  TOO_BIG: 1009,
  JOIN_DENIED: 4001, // Raum unbekannt oder Geheimnis falsch (bewusst nicht unterscheidbar)
  ROOM_FULL: 4002,
  VERSION: 4003,
  ROOM_ENDED: 4004,
  RATE: 4005,
  JOIN_TIMEOUT: 4006,
  REPLACED: 4007, // derselbe Fahrer hat sich über eine neue Verbindung wieder angemeldet
  TOO_MANY_FAILURES: 4008,
  LEFT: 4009, // nach `leave`
  IDLE: 4010, // keine Nachricht mehr vom Gerät
  BAD_MESSAGE: 4011,
  SERVER_FULL: 4012,
});

/** Vorgaben; Betreiber überschreibt einen Teil per Umgebungsvariable (server.mjs). */
export const DEFAULT_LIMITS = Object.freeze({
  maxRooms: 20,
  maxRidersPerRoom: 10,
  maxRidersHardCap: 50,
  roomTtlMs: 6 * 60 * 60 * 1000,
  roomIdleMs: 15 * 60 * 1000,
  rejoinGraceMs: 30 * 1000,
  joinTimeoutMs: 5 * 1000,
  connectionIdleMs: 20 * 1000,
  maxMessageBytes: 1024,
  maxAvatarBytes: 512,
  maxNameChars: 32,
  // Ratenbegrenzung je Verbindung (Eimer mit Marken): 8 Nachrichten/s
  // dauerhaft, kurz bis 16. Zustand kommt mit 2–4 Hz, dazu Ping alle 5 s.
  rateBurst: 16,
  ratePerSecond: 8,
  // So viele verworfene Nachrichten in Folge, dann wird getrennt.
  rateStrikeLimit: 32,
  broadcastIntervalMs: 250,
  // Fehlgeschlagene Beitritte je Adresse, bevor gesperrt wird.
  joinFailureLimit: 20,
  joinFailureWindowMs: 15 * 60 * 1000,
  maxConnections: 300,
  // Plausibilität: Sprung je Zustand höchstens so weit plus maxSpeedMps · Δt.
  maxJumpM: 100,
  maxSpeedMps: 30,
  maxPowerW: 3000,
  maxCadenceRpm: 250,
  maxLane: 3,
  minCountdownS: 3,
  maxCountdownS: 600,
});

const ROOM_ID_RE = /^[A-Za-z0-9_-]{16}$/;
const RIDER_ID_RE = /^[A-Za-z0-9_-]{8}$/;

/** Zufällige Kennung in base64url. */
function randomToken(bytes, random) {
  return random(bytes).toString('base64url');
}

function sha256(text) {
  return crypto.createHash('sha256').update(String(text), 'utf8').digest();
}

/**
 * Konstantzeitiger Vergleich eines geschickten Geheimnisses mit einem
 * gespeicherten SHA-256. Hash vorher, damit Länge und Inhalt des Klartexts
 * die Laufzeit nicht beeinflussen.
 */
export function secretMatches(provided, storedHash) {
  if (typeof provided !== 'string' || !Buffer.isBuffer(storedHash)) return false;
  return crypto.timingSafeEqual(sha256(provided), storedHash);
}

/** Entfernt Steuerzeichen, kürzt und trimmt einen Anzeigenamen. */
export function cleanName(value, maxChars) {
  if (typeof value !== 'string') return null;
  // Steuerzeichen, Zeilenumbrüche und Richtungs-Steuerzeichen (Bidi) raus.
  const stripped = value.replace(/[\u0000-\u001f\u007f-\u009f​-‏‪-‮⁦-⁩]/g, '').trim();
  const chars = Array.from(stripped);
  if (chars.length === 0) return null;
  return chars.slice(0, maxChars).join('');
}

function isFiniteNumber(v) {
  return typeof v === 'number' && Number.isFinite(v);
}

/** Prüft und normalisiert die Strecke beim Anlegen. */
function parseRoute(route) {
  if (!route || typeof route !== 'object' || Array.isArray(route)) return null;
  if (typeof route.id !== 'string' || route.id.length < 1 || route.id.length > 128) return null;
  if (!isFiniteNumber(route.lengthM) || route.lengthM <= 0 || route.lengthM > 1_000_000) return null;
  const out = { id: route.id, lengthM: route.lengthM, loop: route.loop === true };
  if (route.version !== undefined) {
    if (typeof route.version !== 'string' || route.version.length > 64) return null;
    out.version = route.version;
  }
  if (route.name !== undefined) {
    const n = cleanName(route.name, 80);
    if (n === null) return null;
    out.name = n;
  }
  return out;
}

/**
 * Legt den Raum-Dienst an.
 *
 * @param {object} opts
 * @param {() => number} [opts.now] Uhr in ms seit Epoch
 * @param {(n: number) => Buffer} [opts.random] Zufallsbytes
 * @param {object} [opts.limits] Teilmenge von DEFAULT_LIMITS
 * @param {(msg: string) => void} [opts.log] Protokoll — bekommt NIE Geheimnisse, Namen oder Positionen
 */
export function createRoomService({ now = Date.now, random = crypto.randomBytes, limits = {}, log = () => {} } = {}) {
  const L = { ...DEFAULT_LIMITS, ...limits };
  /** roomId -> Raum */
  const rooms = new Map();
  /** Adresse -> { count, windowStart } */
  const joinFailures = new Map();
  /** alle offenen Sitzungen (auch noch nicht beigetretene) */
  const sessions = new Set();

  // -------------------------------------------------------------------------
  // REST-Seite (nur mit SYNC_TOKEN erreichbar, siehe server.mjs)
  // -------------------------------------------------------------------------

  function publicRoom(room) {
    const out = {
      id: room.id,
      route: room.route,
      maxRiders: room.maxRiders,
      createdAt: room.createdAt,
      expiresAt: room.expiresAt,
      startAt: room.startAt,
    };
    return out;
  }

  /**
   * Legt einen Raum an. Liefert `{status, body}`; die Geheimnisse stehen
   * genau einmal in dieser Antwort und werden danach nur noch als Hash gehalten.
   */
  function createRoom(body) {
    if (!body || typeof body !== 'object' || Array.isArray(body)) return invalid('Objekt erwartet');
    const route = parseRoute(body.route);
    if (!route) return invalid('route {id, lengthM, loop?, version?, name?} ungueltig');
    let maxRiders = L.maxRidersPerRoom;
    if (body.maxRiders !== undefined) {
      if (!Number.isInteger(body.maxRiders) || body.maxRiders < 2 || body.maxRiders > L.maxRidersPerRoom) {
        return invalid(`maxRiders muss 2..${L.maxRidersPerRoom} sein`);
      }
      maxRiders = body.maxRiders;
    }
    let ttlMs = L.roomTtlMs;
    if (body.ttlMin !== undefined) {
      if (!Number.isInteger(body.ttlMin) || body.ttlMin < 5 || body.ttlMin * 60_000 > L.roomTtlMs) {
        return invalid(`ttlMin muss 5..${Math.floor(L.roomTtlMs / 60_000)} sein`);
      }
      ttlMs = body.ttlMin * 60_000;
    }
    const t = now();
    let startAt = null;
    if (body.startAt !== undefined && body.startAt !== null) {
      if (!isFiniteNumber(body.startAt) || body.startAt < t || body.startAt > t + ttlMs) {
        return invalid('startAt muss zwischen jetzt und dem Ablauf des Raums liegen (Server-Uhr, ms)');
      }
      startAt = Math.round(body.startAt);
    }
    if (rooms.size >= L.maxRooms) {
      return { status: 429, body: { error: 'Zu viele Raeume auf diesem Server', code: 'ROOMS_FULL' } };
    }
    let id;
    do id = randomToken(12, random); while (rooms.has(id));
    const secret = randomToken(32, random);
    const hostSecret = randomToken(32, random);
    const room = {
      id,
      route,
      maxRiders,
      createdAt: t,
      expiresAt: t + ttlMs,
      startAt,
      secretHash: sha256(secret),
      hostSecretHash: sha256(hostSecret),
      riders: new Map(), // riderId -> Fahrer
      dirty: new Set(), // Fahrer mit neuem Zustand seit dem letzten Rundruf
      lastActiveAt: t,
    };
    rooms.set(id, room);
    log(`Raum angelegt (${rooms.size} aktiv)`);
    return {
      status: 201,
      body: { v: 1, room: publicRoom(room), secret, hostSecret, serverTime: t },
    };
  }

  function invalid(message) {
    return { status: 400, body: { error: message, code: 'INVALID' } };
  }

  /** Übersicht für den Betreiber: keine Geheimnisse, keine Namen, keine Positionen. */
  function listRooms() {
    return {
      versions: PROTOCOL_VERSIONS,
      limits: publicLimits(),
      serverTime: now(),
      rooms: [...rooms.values()].map((r) => ({
        ...publicRoom(r),
        riders: r.riders.size,
        connected: [...r.riders.values()].filter((x) => x.session).length,
      })),
    };
  }

  function publicLimits() {
    return {
      maxRooms: L.maxRooms,
      maxRidersPerRoom: L.maxRidersPerRoom,
      roomTtlMin: Math.floor(L.roomTtlMs / 60_000),
      roomIdleMin: Math.floor(L.roomIdleMs / 60_000),
      rejoinGraceS: Math.floor(L.rejoinGraceMs / 1000),
      maxMessageBytes: L.maxMessageBytes,
      maxAvatarBytes: L.maxAvatarBytes,
      maxNameChars: L.maxNameChars,
      maxMessagesPerSecond: L.ratePerSecond,
      broadcastIntervalMs: L.broadcastIntervalMs,
    };
  }

  /** Beendet einen Raum (Betreiber oder Gastgeber). */
  function deleteRoom(id, reason = 'host') {
    const room = rooms.get(id);
    if (!room) return false;
    endRoom(room, reason);
    return true;
  }

  function endRoom(room, reason) {
    if (!rooms.has(room.id)) return;
    rooms.delete(room.id);
    const msg = JSON.stringify({ type: 'ended', reason, serverTime: now() });
    for (const rider of room.riders.values()) {
      if (rider.session) {
        const session = rider.session;
        session.rider = null;
        session.room = null;
        session.conn.send(msg);
        session.conn.close(CLOSE.ROOM_ENDED, 'Raum beendet');
        onClose(session);
      }
    }
    room.riders.clear();
    room.dirty.clear();
    log(`Raum beendet: ${reason} (${rooms.size} aktiv)`);
  }

  // -------------------------------------------------------------------------
  // WebSocket-Seite
  // -------------------------------------------------------------------------

  function joinFailureBlocked(ip) {
    const e = joinFailures.get(ip);
    if (!e) return false;
    if (now() - e.windowStart > L.joinFailureWindowMs) {
      joinFailures.delete(ip);
      return false;
    }
    return e.count >= L.joinFailureLimit;
  }

  function recordJoinFailure(ip) {
    const t = now();
    const e = joinFailures.get(ip);
    if (!e || t - e.windowStart > L.joinFailureWindowMs) joinFailures.set(ip, { count: 1, windowStart: t });
    else e.count += 1;
    // Wie beim Sync-Server: Zeit und Adresse, NIE Raumkennung oder Geheimnis.
    log(`Fehlgeschlagener Raum-Beitritt von ${ip}`);
  }

  /** Darf eine neue Verbindung überhaupt angenommen werden? */
  function canAccept(ip) {
    if (sessions.size >= L.maxConnections) return { ok: false, status: 503, message: 'Server ausgelastet' };
    if (joinFailureBlocked(ip)) return { ok: false, status: 429, message: 'Zu viele fehlgeschlagene Beitritte' };
    return { ok: true };
  }

  /**
   * Nimmt eine Verbindung an. `conn` hat `send(text)` und `close(code, reason)`.
   * Liefert die Sitzung mit `onMessage(text)` und `onClose()`.
   */
  function attach(conn, ip = 'unbekannt') {
    const t = now();
    const session = {
      conn,
      ip,
      room: null,
      rider: null,
      openedAt: t,
      lastMessageAt: t,
      tokens: L.rateBurst,
      tokensAt: t,
      strikes: 0,
      lastRateErrorAt: 0,
      closed: false,
      onMessage: (text) => onMessage(session, text),
      onClose: () => onClose(session),
    };
    sessions.add(session);
    return session;
  }

  function sendTo(session, obj) {
    if (session.closed) return;
    session.conn.send(JSON.stringify(obj));
  }

  function closeSession(session, code, reason) {
    if (session.closed) return;
    session.conn.close(code, reason);
    // onClose folgt über die Verbindung; zur Sicherheit hier schon abräumen.
    onClose(session);
  }

  function sendError(session, code, message) {
    sendTo(session, { type: 'error', code, message });
  }

  /** Eimer mit Marken: true, wenn die Nachricht verarbeitet werden darf. */
  function takeToken(session) {
    const t = now();
    const refill = ((t - session.tokensAt) / 1000) * L.ratePerSecond;
    session.tokens = Math.min(L.rateBurst, session.tokens + refill);
    session.tokensAt = t;
    if (session.tokens >= 1) {
      session.tokens -= 1;
      session.strikes = 0;
      return true;
    }
    session.strikes += 1;
    return false;
  }

  function onMessage(session, text) {
    if (session.closed) return;
    session.lastMessageAt = now();
    if (!takeToken(session)) {
      if (session.strikes >= L.rateStrikeLimit) {
        closeSession(session, CLOSE.RATE, 'Zu viele Nachrichten');
        return;
      }
      if (session.lastMessageAt - session.lastRateErrorAt >= 1000) {
        session.lastRateErrorAt = session.lastMessageAt;
        sendError(session, 'RATE', 'Zu viele Nachrichten, verworfen');
      }
      return;
    }
    if (Buffer.byteLength(text, 'utf8') > L.maxMessageBytes) {
      closeSession(session, CLOSE.TOO_BIG, 'Nachricht zu gross');
      return;
    }
    let msg;
    try {
      msg = JSON.parse(text);
    } catch {
      msg = null;
    }
    if (!msg || typeof msg !== 'object' || Array.isArray(msg) || typeof msg.type !== 'string') {
      // Vor dem Beitritt sofort trennen, danach nur melden.
      if (!session.rider) return closeSession(session, CLOSE.BAD_MESSAGE, 'Ungueltige Nachricht');
      return sendError(session, 'BAD_MESSAGE', 'JSON-Objekt mit type erwartet');
    }
    if (!session.rider) {
      if (msg.type !== 'join') return closeSession(session, CLOSE.BAD_MESSAGE, 'Zuerst join');
      return handleJoin(session, msg);
    }
    switch (msg.type) {
      case 'state':
        return handleState(session, msg);
      case 'ping':
        return sendTo(session, { type: 'pong', c: isFiniteNumber(msg.c) ? msg.c : null, s: now() });
      case 'settings':
        return handleSettings(session, msg);
      case 'leave':
        return handleLeave(session);
      case 'start':
        return handleStart(session, msg);
      case 'end':
        if (!session.rider.host) return sendError(session, 'NOT_HOST', 'Nur der Gastgeber darf beenden');
        return endRoom(session.room, 'host');
      case 'join':
        return sendError(session, 'ALREADY_JOINED', 'Schon beigetreten');
      default:
        // Unbekannte Typen sind kein Fehler: Ein neuerer Client darf mehr
        // schicken, als dieser Server kennt (docs/raum-protokoll.md, Versionierung).
        return;
    }
  }

  function handleJoin(session, msg) {
    const ip = session.ip;
    if (joinFailureBlocked(ip)) return closeSession(session, CLOSE.TOO_MANY_FAILURES, 'Zu viele Fehlversuche');
    if (!Number.isInteger(msg.v) || !PROTOCOL_VERSIONS.includes(msg.v)) {
      sendTo(session, { type: 'error', code: 'VERSION', message: 'Protokollversion nicht unterstuetzt', supported: PROTOCOL_VERSIONS });
      return closeSession(session, CLOSE.VERSION, 'Version');
    }
    const roomId = typeof msg.room === 'string' && ROOM_ID_RE.test(msg.room) ? msg.room : null;
    const room = roomId ? rooms.get(roomId) : undefined;
    // Beide Geheimnisse immer prüfen (auch ohne Raum gegen einen Platzhalter),
    // damit die Antwortzeit nicht verrät, ob es den Raum gibt.
    const secretHash = room ? room.secretHash : PLACEHOLDER_HASH;
    const hostHash = room ? room.hostSecretHash : PLACEHOLDER_HASH;
    const asGuest = secretMatches(msg.secret, secretHash);
    const asHost = secretMatches(msg.secret, hostHash);
    if (!room || !(asGuest || asHost)) {
      recordJoinFailure(ip);
      sendTo(session, { type: 'error', code: 'JOIN_DENIED', message: 'Raum unbekannt oder Geheimnis falsch' });
      return closeSession(session, CLOSE.JOIN_DENIED, 'Beitritt abgelehnt');
    }
    const name = cleanName(msg.name, L.maxNameChars);
    if (name === null) {
      sendTo(session, { type: 'error', code: 'BAD_NAME', message: 'Anzeigename fehlt' });
      return closeSession(session, CLOSE.BAD_MESSAGE, 'Name');
    }
    let avatar = null;
    if (msg.avatar !== undefined && msg.avatar !== null) {
      const raw = JSON.stringify(msg.avatar);
      if (Buffer.byteLength(raw, 'utf8') > L.maxAvatarBytes) {
        sendTo(session, { type: 'error', code: 'AVATAR_TOO_BIG', message: `Avatar hoechstens ${L.maxAvatarBytes} Bytes` });
        return closeSession(session, CLOSE.BAD_MESSAGE, 'Avatar');
      }
      avatar = msg.avatar; // undurchsichtig, wird nur weitergereicht
    }
    const sharePower = msg.sharePower !== false;

    // Wiederbeitritt: dieselbe Fahrer-Kennung, wenn Schlüssel passt.
    let rider = null;
    if (msg.rejoin && typeof msg.rejoin === 'object' && typeof msg.rejoin.id === 'string' && RIDER_ID_RE.test(msg.rejoin.id)) {
      const candidate = room.riders.get(msg.rejoin.id);
      if (candidate && secretMatches(msg.rejoin.key, candidate.rejoinKeyHash)) rider = candidate;
    }
    let rejoinKey = null;
    const t = now();
    if (rider) {
      if (rider.session && rider.session !== session) {
        const old = rider.session;
        old.rider = null;
        old.room = null;
        old.conn.close(CLOSE.REPLACED, 'Neu verbunden');
        onClose(old);
      }
      rider.name = name;
      rider.avatar = avatar;
      rider.sharePower = sharePower;
      rider.host = rider.host || asHost;
      rider.disconnectedAt = null;
      rider.lastState = null; // erster Zustand nach Wiederbeitritt ohne Sprungprüfung
      rejoinKey = randomToken(18, random);
      rider.rejoinKeyHash = sha256(rejoinKey);
      broadcast(room, { type: 'rider', rider: riderInfo(rider) }, rider.id);
    } else {
      if (room.riders.size >= room.maxRiders) {
        sendTo(session, { type: 'error', code: 'ROOM_FULL', message: 'Raum ist voll' });
        return closeSession(session, CLOSE.ROOM_FULL, 'Voll');
      }
      let id;
      do id = randomToken(6, random); while (room.riders.has(id));
      rejoinKey = randomToken(18, random);
      rider = {
        id,
        name,
        avatar,
        sharePower,
        host: asHost,
        joinedAt: t,
        rejoinKeyHash: sha256(rejoinKey),
        session: null,
        disconnectedAt: null,
        lastState: null,
      };
      room.riders.set(id, rider);
      broadcast(room, { type: 'rider', rider: riderInfo(rider) }, rider.id);
    }
    rider.session = session;
    session.rider = rider;
    session.room = room;
    room.lastActiveAt = t;
    joinFailures.delete(ip);
    sendTo(session, {
      type: 'welcome',
      v: 1,
      serverTime: t,
      you: { id: rider.id, rejoinKey, host: rider.host },
      room: publicRoom(room),
      riders: [...room.riders.values()].map(riderInfo),
      states: [...room.riders.values()].filter((r) => r.lastState).map(stateEntry),
      limits: publicLimits(),
    });
  }

  function riderInfo(r) {
    const out = { id: r.id, name: r.name, host: r.host, sharePower: r.sharePower };
    if (r.avatar !== null) out.avatar = r.avatar;
    return out;
  }

  function stateEntry(r) {
    const s = r.lastState;
    const out = { id: r.id, at: s.at, distM: s.distM, speedMps: s.speedMps };
    if (s.powerW !== undefined && r.sharePower) out.powerW = s.powerW;
    if (s.cadenceRpm !== undefined) out.cadenceRpm = s.cadenceRpm;
    if (s.lane !== undefined) out.lane = s.lane;
    return out;
  }

  function handleState(session, msg) {
    const rider = session.rider;
    const t = now();
    if (!isFiniteNumber(msg.distM) || msg.distM < 0 || msg.distM > 10_000_000) {
      return sendError(session, 'INVALID_STATE', 'distM fehlt oder ungueltig');
    }
    if (!isFiniteNumber(msg.speedMps) || msg.speedMps < 0 || msg.speedMps > L.maxSpeedMps) {
      return sendError(session, 'INVALID_STATE', `speedMps muss 0..${L.maxSpeedMps} sein`);
    }
    const prev = rider.lastState;
    if (prev) {
      const dtS = Math.max(0, (t - prev.at) / 1000);
      const allowed = L.maxJumpM + L.maxSpeedMps * dtS;
      // Rückwärts nur in Rundungsbreite; vorwärts begrenzt (Fehler abfangen, keine Betrugsabwehr).
      if (msg.distM < prev.distM - 5 || msg.distM - prev.distM > allowed) {
        return sendError(session, 'IMPLAUSIBLE', 'Sprung in distM');
      }
    }
    const s = { at: t, distM: msg.distM, speedMps: msg.speedMps };
    if (msg.powerW !== undefined && msg.powerW !== null) {
      if (!isFiniteNumber(msg.powerW) || msg.powerW < 0 || msg.powerW > L.maxPowerW) {
        return sendError(session, 'INVALID_STATE', `powerW muss 0..${L.maxPowerW} sein`);
      }
      // Wer Leistung nicht teilen will, dessen Wert verlässt den Server nie.
      if (rider.sharePower) s.powerW = Math.round(msg.powerW);
    }
    if (msg.cadenceRpm !== undefined && msg.cadenceRpm !== null) {
      if (!isFiniteNumber(msg.cadenceRpm) || msg.cadenceRpm < 0 || msg.cadenceRpm > L.maxCadenceRpm) {
        return sendError(session, 'INVALID_STATE', `cadenceRpm muss 0..${L.maxCadenceRpm} sein`);
      }
      s.cadenceRpm = Math.round(msg.cadenceRpm);
    }
    if (msg.lane !== undefined && msg.lane !== null) {
      if (!Number.isInteger(msg.lane) || Math.abs(msg.lane) > L.maxLane) {
        return sendError(session, 'INVALID_STATE', `lane muss ganzzahlig -${L.maxLane}..${L.maxLane} sein`);
      }
      s.lane = msg.lane;
    }
    rider.lastState = s;
    session.room.dirty.add(rider.id);
    session.room.lastActiveAt = t;
  }

  function handleSettings(session, msg) {
    const rider = session.rider;
    if (typeof msg.sharePower === 'boolean') {
      rider.sharePower = msg.sharePower;
      if (!msg.sharePower && rider.lastState) delete rider.lastState.powerW;
    }
    if (msg.name !== undefined) {
      const name = cleanName(msg.name, L.maxNameChars);
      if (name) rider.name = name;
    }
    broadcast(session.room, { type: 'rider', rider: riderInfo(rider) });
  }

  function handleLeave(session) {
    const room = session.room;
    const rider = session.rider;
    removeRider(room, rider, 'leave');
    session.rider = null;
    session.room = null;
    closeSession(session, CLOSE.LEFT, 'Verlassen');
  }

  function handleStart(session, msg) {
    if (!session.rider.host) return sendError(session, 'NOT_HOST', 'Nur der Gastgeber darf starten');
    const room = session.room;
    const t = now();
    if (room.startAt !== null && room.startAt <= t) return sendError(session, 'ALREADY_STARTED', 'Schon gestartet');
    if (!Number.isInteger(msg.inS) || msg.inS < L.minCountdownS || msg.inS > L.maxCountdownS) {
      return sendError(session, 'INVALID', `inS muss ${L.minCountdownS}..${L.maxCountdownS} sein`);
    }
    room.startAt = t + msg.inS * 1000;
    broadcast(room, { type: 'countdown', startAt: room.startAt, serverTime: t });
  }

  function removeRider(room, rider, reason) {
    if (!room || !rider || !room.riders.has(rider.id)) return;
    room.riders.delete(rider.id);
    room.dirty.delete(rider.id);
    broadcast(room, { type: 'left', id: rider.id, reason });
  }

  function onClose(session) {
    if (session.closed) return;
    session.closed = true;
    sessions.delete(session);
    const rider = session.rider;
    if (rider && rider.session === session) {
      // Abbruch: Platz bleibt rejoinGraceMs reserviert (Wiederbeitritt mit rejoin).
      rider.session = null;
      rider.disconnectedAt = now();
    }
    session.rider = null;
    session.room = null;
  }

  function broadcast(room, obj, exceptRiderId = null) {
    const text = JSON.stringify(obj);
    for (const r of room.riders.values()) {
      if (r.id === exceptRiderId || !r.session) continue;
      r.session.conn.send(text);
    }
  }

  /**
   * Taktgeber (alle broadcastIntervalMs): Zustände rundsenden,
   * Zeitüberschreitungen und Ablauf prüfen.
   */
  function tick() {
    const t = now();
    for (const session of [...sessions]) {
      if (!session.rider && t - session.openedAt > L.joinTimeoutMs) {
        closeSession(session, CLOSE.JOIN_TIMEOUT, 'Kein join');
      } else if (session.rider && t - session.lastMessageAt > L.connectionIdleMs) {
        closeSession(session, CLOSE.IDLE, 'Keine Nachrichten');
      }
    }
    for (const room of [...rooms.values()]) {
      if (t >= room.expiresAt) {
        endRoom(room, 'expired');
        continue;
      }
      for (const rider of [...room.riders.values()]) {
        if (!rider.session && rider.disconnectedAt !== null && t - rider.disconnectedAt > L.rejoinGraceMs) {
          removeRider(room, rider, 'timeout');
        }
      }
      const anyConnected = [...room.riders.values()].some((r) => r.session);
      if (anyConnected) room.lastActiveAt = Math.max(room.lastActiveAt, t);
      else if (t - room.lastActiveAt > L.roomIdleMs) {
        endRoom(room, 'idle');
        continue;
      }
      if (room.dirty.size > 0) {
        const states = [];
        for (const id of room.dirty) {
          const r = room.riders.get(id);
          if (r && r.lastState) states.push(stateEntry(r));
        }
        room.dirty.clear();
        if (states.length > 0) broadcast(room, { type: 'snapshot', serverTime: t, states });
      }
    }
  }

  /** Alles beenden (Herunterfahren des Servers). */
  function shutdown() {
    for (const room of [...rooms.values()]) endRoom(room, 'server');
    for (const s of [...sessions]) closeSession(s, 1001, 'Server faehrt herunter');
  }

  return {
    limits: L,
    createRoom,
    listRooms,
    deleteRoom,
    canAccept,
    attach,
    tick,
    shutdown,
    // nur für Tests und Betriebszahlen
    stats: () => ({ rooms: rooms.size, sessions: sessions.size }),
  };
}

// Vergleichswert, wenn es den Raum nicht gibt (siehe handleJoin).
const PLACEHOLDER_HASH = sha256(crypto.randomBytes(32).toString('base64url'));
