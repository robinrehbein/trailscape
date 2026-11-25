// Minimaler WebSocket-Server nach RFC 6455, nur Node-Core-Module.
//
// Bewusst klein: nur Textnachrichten, keine Erweiterungen (kein
// permessage-deflate), keine Unterprotokolle. Was der Raum-Dienst
// (rooms.mjs) braucht, ist genau das: kleine JSON-Nachrichten in beide
// Richtungen. Begründung der Wahl (statt Server-Sent Events + POST) steht in
// docs/raum-protokoll.md, Abschnitt „Transport“.
//
// Grenzen, die hier und nicht erst im Raum-Dienst durchgesetzt werden:
//  * Nachrichtengröße (`maxMessageBytes`): Ein Rahmen mit größerer
//    angekündigter Länge wird VOR dem Puffern abgewiesen (Schließcode 1009).
//  * Gepufferte Eingangsbytes sind dadurch auf maxMessageBytes + Kopf begrenzt.
//  * Ausgangspuffer (`maxBufferedBytes`): Liest ein Empfänger nicht mehr mit,
//    wird die Verbindung getrennt statt Speicher anzuhäufen.
//  * Protokollfehler (unmaskierte Client-Rahmen, gesetzte RSV-Bits, unbekannte
//    Opcodes, zu große Steuerrahmen, ungültiges UTF-8) schließen mit 1002/1007.

import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

export const OP_CONT = 0x0;
export const OP_TEXT = 0x1;
export const OP_BINARY = 0x2;
export const OP_CLOSE = 0x8;
export const OP_PING = 0x9;
export const OP_PONG = 0xa;

/** Sec-WebSocket-Accept zu einem Sec-WebSocket-Key (RFC 6455, 4.2.2). */
export function acceptKey(key) {
  return crypto.createHash('sha1').update(key + GUID).digest('base64');
}

/**
 * Prüft die Kopfzeilen eines Upgrade-Requests. Liefert `null`, wenn alles
 * stimmt, sonst `{status, headers, message}` für die Ablehnung.
 */
export function checkHandshake(req) {
  if (req.method !== 'GET') return { status: 405, message: 'Nur GET' };
  const upgrade = String(req.headers['upgrade'] || '').toLowerCase();
  const connection = String(req.headers['connection'] || '').toLowerCase();
  if (upgrade !== 'websocket' || !connection.split(',').some((s) => s.trim() === 'upgrade')) {
    return { status: 426, headers: { Upgrade: 'websocket' }, message: 'WebSocket erwartet' };
  }
  if (String(req.headers['sec-websocket-version'] || '') !== '13') {
    return { status: 426, headers: { 'Sec-WebSocket-Version': '13' }, message: 'Nur WebSocket-Version 13' };
  }
  const key = String(req.headers['sec-websocket-key'] || '');
  // 16 zufällige Bytes, base64 → 24 Zeichen mit '=='.
  if (!/^[A-Za-z0-9+/]{22}==$/.test(key)) return { status: 400, message: 'Ungültiger Sec-WebSocket-Key' };
  return null;
}

/** Schreibt eine HTTP-Ablehnung auf einen Upgrade-Socket und schließt ihn. */
export function rejectUpgrade(socket, status, message = '', headers = {}) {
  const body = JSON.stringify({ error: message || 'Abgelehnt' });
  const reason = { 400: 'Bad Request', 403: 'Forbidden', 404: 'Not Found', 405: 'Method Not Allowed', 426: 'Upgrade Required', 429: 'Too Many Requests', 503: 'Service Unavailable' }[status] || 'Error';
  let head = `HTTP/1.1 ${status} ${reason}\r\nConnection: close\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${Buffer.byteLength(body)}\r\n`;
  for (const [k, v] of Object.entries(headers)) head += `${k}: ${v}\r\n`;
  try {
    socket.end(head + '\r\n' + body);
  } catch {
    socket.destroy();
  }
  // Falls der Gegner nicht schließt, nicht ewig halten.
  setTimeout(() => socket.destroy(), 1000).unref();
}

/**
 * Baut einen Rahmen. Server-Rahmen sind unmaskiert; `mask` (4 Bytes) nur für
 * Tests, die einen Client nachbilden.
 */
export function encodeFrame(opcode, payload, { fin = true, mask = null, rsv = 0 } = {}) {
  const data = Buffer.isBuffer(payload) ? payload : Buffer.from(payload ?? '', 'utf8');
  const len = data.length;
  let headLen = 2;
  if (len >= 126 && len <= 0xffff) headLen += 2;
  else if (len > 0xffff) headLen += 8;
  if (mask) headLen += 4;
  const out = Buffer.alloc(headLen + len);
  out[0] = (fin ? 0x80 : 0) | ((rsv & 0x7) << 4) | (opcode & 0x0f);
  let off = 2;
  if (len < 126) {
    out[1] = len;
  } else if (len <= 0xffff) {
    out[1] = 126;
    out.writeUInt16BE(len, 2);
    off = 4;
  } else {
    out[1] = 127;
    out.writeBigUInt64BE(BigInt(len), 2);
    off = 10;
  }
  if (mask) {
    out[1] |= 0x80;
    mask.copy(out, off);
    off += 4;
    for (let i = 0; i < len; i++) out[off + i] = data[i] ^ mask[i & 3];
  } else {
    data.copy(out, off);
  }
  return out;
}

/** Inhalt eines Close-Rahmens: Code (2 Bytes) + UTF-8-Grund (gekürzt auf 123 Bytes). */
function closePayload(code, reason) {
  if (!code) return Buffer.alloc(0);
  let r = Buffer.from(String(reason || ''), 'utf8');
  if (r.length > 123) r = r.subarray(0, 123);
  const b = Buffer.alloc(2 + r.length);
  b.writeUInt16BE(code, 0);
  r.copy(b, 2);
  return b;
}

/** Vom Gegner gesendete Schließcodes, die RFC 6455 (7.4) zulässt. */
function isValidPeerCloseCode(code) {
  if (code >= 3000 && code <= 4999) return true;
  return [1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011].includes(code);
}

const utf8 = new TextDecoder('utf-8', { fatal: true });

/**
 * Eine WebSocket-Verbindung (Serverseite).
 *
 * Ereignisse: `message` (Text), `pong`, `close` ({code, reason}) — `close`
 * kommt genau einmal, egal ob sauber geschlossen oder abgerissen.
 */
export class WsConnection extends EventEmitter {
  constructor(socket, { maxMessageBytes = 1024, maxBufferedBytes = 256 * 1024 } = {}) {
    super();
    this.socket = socket;
    this.maxMessageBytes = maxMessageBytes;
    this.maxBufferedBytes = maxBufferedBytes;
    this.buffer = Buffer.alloc(0);
    this.fragments = null; // Array von Buffern einer fragmentierten Nachricht
    this.fragmentBytes = 0;
    this.closing = false; // Close-Rahmen gesendet
    this.closed = false; // `close` ausgelöst
    this.lastFrameAt = Date.now();
    socket.setNoDelay(true);
    socket.on('data', (chunk) => this._onData(chunk));
    socket.on('error', () => this._finish(1006, ''));
    socket.on('close', () => this._finish(1006, ''));
    socket.on('end', () => this._finish(1006, ''));
  }

  /** Sendet eine Textnachricht. Liefert false, wenn die Verbindung zu ist oder überläuft. */
  send(text) {
    if (this.closing || this.closed) return false;
    if (this.socket.writableLength > this.maxBufferedBytes) {
      // Empfänger kommt nicht mit — lieber trennen als Speicher anhäufen.
      this.terminate(1008, 'Empfaenger zu langsam');
      return false;
    }
    this.socket.write(encodeFrame(OP_TEXT, text));
    return true;
  }

  ping(data = '') {
    if (this.closing || this.closed) return;
    this.socket.write(encodeFrame(OP_PING, data));
  }

  /** Sauberes Schließen: Close-Rahmen senden, auf Antwort höchstens 2 s warten. */
  close(code = 1000, reason = '') {
    if (this.closing || this.closed) return;
    this.closing = true;
    this.closeCode = code;
    this.closeReason = reason;
    try {
      this.socket.write(encodeFrame(OP_CLOSE, closePayload(code, reason)));
    } catch {
      /* Socket schon weg */
    }
    setTimeout(() => this.terminate(code, reason), 2000).unref();
  }

  /** Sofort trennen (nach einem Close-Rahmen, wenn noch möglich). */
  terminate(code = 1006, reason = '') {
    if (!this.closing && !this.closed) {
      this.closing = true;
      try {
        this.socket.write(encodeFrame(OP_CLOSE, closePayload(code === 1006 ? 1011 : code, reason)));
      } catch {
        /* egal */
      }
    }
    this.socket.destroy();
    this._finish(this.closeCode ?? code, this.closeReason ?? reason);
  }

  _finish(code, reason) {
    if (this.closed) return;
    this.closed = true;
    this.socket.destroy();
    this.emit('close', { code: this.closeCode ?? code, reason: this.closeReason ?? reason });
  }

  _fail(code, reason) {
    this.buffer = Buffer.alloc(0);
    if (!this.closing) {
      this.closing = true;
      this.closeCode = code;
      this.closeReason = reason;
      try {
        this.socket.end(encodeFrame(OP_CLOSE, closePayload(code, reason)));
      } catch {
        /* egal */
      }
    }
    setTimeout(() => this.socket.destroy(), 500).unref();
    this._finish(code, reason);
  }

  _onData(chunk) {
    if (this.closed) return;
    this.lastFrameAt = Date.now();
    this.buffer = this.buffer.length === 0 ? chunk : Buffer.concat([this.buffer, chunk]);
    // Mehrere Rahmen können in einem Datenblock stecken, ein Rahmen über mehrere.
    while (!this.closed) {
      const frame = this._parseFrame();
      if (frame === null) break; // unvollständig
      if (frame === false) return; // Fehler, Verbindung wird geschlossen
      this._onFrame(frame);
    }
  }

  /** Liefert einen Rahmen, `null` bei zu wenig Daten oder `false` nach Protokollfehler. */
  _parseFrame() {
    const b = this.buffer;
    if (b.length < 2) return null;
    const fin = (b[0] & 0x80) !== 0;
    const rsv = (b[0] >> 4) & 0x7;
    const opcode = b[0] & 0x0f;
    const masked = (b[1] & 0x80) !== 0;
    let len = b[1] & 0x7f;
    let off = 2;
    if (rsv !== 0) return this._fail(1002, 'RSV gesetzt'), false;
    if (![OP_CONT, OP_TEXT, OP_BINARY, OP_CLOSE, OP_PING, OP_PONG].includes(opcode)) {
      return this._fail(1002, 'Unbekannter Opcode'), false;
    }
    if (!masked) return this._fail(1002, 'Client-Rahmen muessen maskiert sein'), false;
    const control = opcode >= 0x8;
    if (control && (!fin || len > 125)) return this._fail(1002, 'Ungueltiger Steuerrahmen'), false;
    if (len === 126) {
      if (b.length < 4) return null;
      len = b.readUInt16BE(2);
      off = 4;
    } else if (len === 127) {
      if (b.length < 10) return null;
      const big = b.readBigUInt64BE(2);
      if (big > BigInt(Number.MAX_SAFE_INTEGER)) return this._fail(1009, 'Nachricht zu gross'), false;
      len = Number(big);
      off = 10;
    }
    // Größe VOR dem Puffern prüfen: Ein Gegner kann sonst 2^63 Bytes ankündigen.
    if (!control && (this.fragmentBytes + len > this.maxMessageBytes)) {
      return this._fail(1009, 'Nachricht zu gross'), false;
    }
    if (b.length < off + 4 + len) return null;
    const mask = b.subarray(off, off + 4);
    off += 4;
    const payload = Buffer.allocUnsafe(len);
    for (let i = 0; i < len; i++) payload[i] = b[off + i] ^ mask[i & 3];
    this.buffer = b.subarray(off + len);
    return { fin, opcode, payload };
  }

  _onFrame({ fin, opcode, payload }) {
    switch (opcode) {
      case OP_PING:
        if (!this.closing) this.socket.write(encodeFrame(OP_PONG, payload));
        return;
      case OP_PONG:
        this.emit('pong', payload);
        return;
      case OP_CLOSE: {
        let code = 1005;
        let reason = '';
        if (payload.length === 1) return this._fail(1002, 'Ungueltiger Close-Rahmen');
        if (payload.length >= 2) {
          code = payload.readUInt16BE(0);
          if (!isValidPeerCloseCode(code)) return this._fail(1002, 'Ungueltiger Schliesscode');
          try {
            reason = utf8.decode(payload.subarray(2));
          } catch {
            return this._fail(1007, 'Ungueltiges UTF-8');
          }
        }
        if (!this.closing) {
          // Antwort auf das Schließen des Gegners: Code zurückspiegeln.
          this.closing = true;
          this.closeCode = code;
          this.closeReason = reason;
          try {
            this.socket.end(encodeFrame(OP_CLOSE, closePayload(code === 1005 ? 1000 : code, '')));
          } catch {
            /* egal */
          }
        }
        setTimeout(() => this.socket.destroy(), 500).unref();
        this._finish(this.closeCode ?? code, this.closeReason ?? reason);
        return;
      }
      case OP_TEXT:
      case OP_BINARY:
        if (this.fragments) return this._fail(1002, 'Neue Nachricht vor Ende der alten');
        if (fin) return this._deliver(opcode, [payload]);
        this.fragments = [payload];
        this.fragmentOpcode = opcode;
        this.fragmentBytes = payload.length;
        return;
      case OP_CONT:
        if (!this.fragments) return this._fail(1002, 'Fortsetzung ohne Anfang');
        this.fragments.push(payload);
        this.fragmentBytes += payload.length;
        if (fin) {
          const parts = this.fragments;
          this.fragments = null;
          this.fragmentBytes = 0;
          this._deliver(this.fragmentOpcode, parts);
        }
        return;
      default:
        return this._fail(1002, 'Unbekannter Opcode');
    }
  }

  _deliver(opcode, parts) {
    if (opcode === OP_BINARY) return this._fail(1003, 'Nur Textnachrichten');
    let text;
    try {
      text = utf8.decode(parts.length === 1 ? parts[0] : Buffer.concat(parts));
    } catch {
      return this._fail(1007, 'Ungueltiges UTF-8');
    }
    if (this.closing) return; // nach eigenem Close keine Nachrichten mehr ausliefern
    this.emit('message', text);
  }
}

/**
 * Vollzieht den Handshake auf einem Upgrade-Socket und liefert die
 * Verbindung. Vorher [checkHandshake] aufrufen.
 */
export function acceptUpgrade(req, socket, head, options) {
  const key = String(req.headers['sec-websocket-key']);
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n`
  );
  const conn = new WsConnection(socket, options);
  if (head && head.length > 0) conn._onData(head);
  return conn;
}
