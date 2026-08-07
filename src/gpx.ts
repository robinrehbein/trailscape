import type { TrackPoint } from "./types";

/**
 * Liest den ersten direkten Kind-Text eines Elements mit einem bestimmten
 * (unqualifizierten) Tag-Namen aus. GPX-Dateien nutzen üblicherweise einen
 * Default-Namespace ohne Präfix, sodass `tagName` dem lokalen Namen entspricht.
 */
function findChildText(parent: Element, tagName: string): string | null {
  for (const child of Array.from(parent.children)) {
    if (child.tagName === tagName) {
      return child.textContent;
    }
  }
  return null;
}

function parseTimeToMs(raw: string | null): number | undefined {
  if (!raw) {
    return undefined;
  }
  const ms = Date.parse(raw.trim());
  return Number.isNaN(ms) ? undefined : ms;
}

function parseEleM(raw: string | null): number | undefined {
  if (!raw) {
    return undefined;
  }
  const value = Number.parseFloat(raw.trim());
  return Number.isFinite(value) ? value : undefined;
}

function parsePoint(el: Element): TrackPoint {
  const latRaw = el.getAttribute("lat");
  const lonRaw = el.getAttribute("lon");
  const lat = latRaw !== null ? Number.parseFloat(latRaw) : NaN;
  const lon = lonRaw !== null ? Number.parseFloat(lonRaw) : NaN;

  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    throw new Error("Ungültige Koordinaten in der GPX-Datei.");
  }

  const point: TrackPoint = { lat, lon };

  const ele = parseEleM(findChildText(el, "ele"));
  if (ele !== undefined) {
    point.ele = ele;
  }

  const time = parseTimeToMs(findChildText(el, "time"));
  if (time !== undefined) {
    point.time = time;
  }

  return point;
}

function findName(doc: Document): string | null {
  const nameEls = Array.from(doc.getElementsByTagName("name"));

  const trkName = nameEls.find((el) => el.parentElement?.tagName === "trk");
  if (trkName?.textContent?.trim()) {
    return trkName.textContent.trim();
  }

  const metaName = nameEls.find((el) => el.parentElement?.tagName === "metadata");
  if (metaName?.textContent?.trim()) {
    return metaName.textContent.trim();
  }

  return null;
}

/**
 * Parst eine GPX-1.0/1.1-Datei (String) und liefert Name sowie alle
 * Trackpunkte in Reihenfolge. Fällt auf Routenpunkte (`rtept`) zurück,
 * falls keine Trackpunkte vorhanden sind.
 */
export function parseGpx(xml: string): { name: string | null; points: TrackPoint[] } {
  const parser = new DOMParser();
  const doc = parser.parseFromString(xml, "text/xml");

  if (doc.getElementsByTagName("parsererror").length > 0) {
    throw new Error("Die GPX-Datei enthält ungültiges XML.");
  }

  const rootTag = doc.documentElement?.tagName;
  if (!rootTag || rootTag !== "gpx") {
    throw new Error("Die Datei ist keine gültige GPX-Datei.");
  }

  let pointEls = Array.from(doc.getElementsByTagName("trkpt"));
  if (pointEls.length === 0) {
    pointEls = Array.from(doc.getElementsByTagName("rtept"));
  }

  if (pointEls.length === 0) {
    throw new Error("Die GPX-Datei enthält keine Trackpunkte.");
  }

  const points = pointEls.map(parsePoint);
  const name = findName(doc);

  return { name, points };
}

function escapeXml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function buildTrkpt(point: TrackPoint): string {
  const attrs = `lat="${point.lat}" lon="${point.lon}"`;
  const children: string[] = [];

  if (point.ele !== undefined) {
    children.push(`      <ele>${point.ele}</ele>`);
  }
  if (point.time !== undefined) {
    children.push(`      <time>${new Date(point.time).toISOString()}</time>`);
  }

  if (children.length === 0) {
    return `    <trkpt ${attrs}/>`;
  }

  return `    <trkpt ${attrs}>\n${children.join("\n")}\n    </trkpt>`;
}

/**
 * Erzeugt eine valide GPX-1.1-Datei mit einem einzelnen Track/Segment.
 */
export function buildGpx(name: string, points: TrackPoint[]): string {
  const trkpts = points.map(buildTrkpt).join("\n");

  return `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Trailscape" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>${escapeXml(name)}</name>
    <trkseg>
${trkpts}
    </trkseg>
  </trk>
</gpx>
`;
}
