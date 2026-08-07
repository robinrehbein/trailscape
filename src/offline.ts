const TILE_CACHE_NAME = "trailscape-tiles";
const TILE_URL_TEMPLATE = "https://tile.openstreetmap.org";
const MAX_PARALLEL_FETCHES = 4;
const MAX_LATITUDE = 85.0511;

/** Obergrenze für einen Offline-Download, damit die Kachel-Server nicht überlastet werden. */
export const MAX_TILES = 250;

export interface TileRegion {
  north: number;
  south: number;
  east: number;
  west: number;
}

export interface DownloadProgress {
  done: number;
  total: number;
}

interface TileRect {
  xMin: number;
  xMax: number;
  yMin: number;
  yMax: number;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

function tileCountAtZoom(zoom: number): number {
  return Math.pow(2, zoom);
}

function lonToTileX(lon: number, zoom: number): number {
  const n = tileCountAtZoom(zoom);
  const x = Math.floor(((lon + 180) / 360) * n);
  return clamp(x, 0, n - 1);
}

function latToTileY(lat: number, zoom: number): number {
  const n = tileCountAtZoom(zoom);
  const rad = (clamp(lat, -MAX_LATITUDE, MAX_LATITUDE) * Math.PI) / 180;
  const y = Math.floor(
    ((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2) * n
  );
  return clamp(y, 0, n - 1);
}

/**
 * Kachelrechteck einer Region auf einer Zoomstufe. Liefert null für leere
 * Rechtecke, etwa wenn Ost westlich von West liegt (Antimeridian wird nicht
 * unterstützt).
 */
function tileRect(region: TileRegion, zoom: number): TileRect | null {
  if (region.east < region.west || region.north < region.south) {
    return null;
  }

  return {
    xMin: lonToTileX(region.west, zoom),
    xMax: lonToTileX(region.east, zoom),
    yMin: latToTileY(region.north, zoom),
    yMax: latToTileY(region.south, zoom),
  };
}

function normalizeZoomRange(minZoom: number, maxZoom: number): number[] {
  const from = Math.max(0, Math.floor(minZoom));
  const to = Math.floor(maxZoom);
  const zooms: number[] = [];

  for (let zoom = from; zoom <= to; zoom += 1) {
    zooms.push(zoom);
  }

  return zooms;
}

function tileUrl(zoom: number, x: number, y: number): string {
  return `${TILE_URL_TEMPLATE}/${zoom}/${x}/${y}.png`;
}

/** Zählt die Kacheln einer Region über alle Zoomstufen — reine Rechnung ohne IO. */
export function estimateTileCount(
  region: TileRegion,
  minZoom: number,
  maxZoom: number
): number {
  let total = 0;

  for (const zoom of normalizeZoomRange(minZoom, maxZoom)) {
    const rect = tileRect(region, zoom);
    if (rect) {
      total += (rect.xMax - rect.xMin + 1) * (rect.yMax - rect.yMin + 1);
    }
  }

  return total;
}

function collectTileUrls(
  region: TileRegion,
  minZoom: number,
  maxZoom: number
): string[] {
  const urls: string[] = [];

  for (const zoom of normalizeZoomRange(minZoom, maxZoom)) {
    const rect = tileRect(region, zoom);
    if (!rect) {
      continue;
    }

    for (let x = rect.xMin; x <= rect.xMax; x += 1) {
      for (let y = rect.yMin; y <= rect.yMax; y += 1) {
        urls.push(tileUrl(zoom, x, y));
      }
    }
  }

  return urls;
}

function hasCacheStorage(): boolean {
  return typeof caches !== "undefined";
}

/**
 * Lädt alle Kacheln der Region in den Cache "trailscape-tiles". Die Auslieferung
 * an die Karte übernimmt später der Service Worker.
 */
export async function downloadRegion(
  region: TileRegion,
  minZoom: number,
  maxZoom: number,
  onProgress: (p: DownloadProgress) => void
): Promise<{ downloaded: number; skipped: number; failed: number }> {
  const estimate = estimateTileCount(region, minZoom, maxZoom);

  if (estimate > MAX_TILES) {
    throw new Error(
      `Zu großer Bereich: ${estimate} Kacheln (Limit ${MAX_TILES}). Zoome näher heran.`
    );
  }

  if (!hasCacheStorage()) {
    throw new Error("Cache-Storage ist in diesem Browser nicht verfügbar.");
  }

  const cache = await caches.open(TILE_CACHE_NAME);
  const urls = collectTileUrls(region, minZoom, maxZoom);
  const total = urls.length;

  let nextIndex = 0;
  let done = 0;
  let downloaded = 0;
  let skipped = 0;
  let failed = 0;

  async function worker(): Promise<void> {
    for (;;) {
      const index = nextIndex;
      nextIndex += 1;

      if (index >= total) {
        return;
      }

      const url = urls[index];

      try {
        const existing = await cache.match(url);
        if (existing) {
          skipped += 1;
        } else {
          // Opaque Responses sind hier gewollt: der Service Worker reicht sie
          // unverändert an die <img>-Kacheln weiter.
          const response = await fetch(url, { mode: "no-cors" });
          await cache.put(url, response);
          downloaded += 1;
        }
      } catch {
        failed += 1;
      }

      done += 1;
      onProgress({ done, total });
    }
  }

  const workerCount = Math.min(MAX_PARALLEL_FETCHES, total);
  const workers: Promise<void>[] = [];

  for (let i = 0; i < workerCount; i += 1) {
    workers.push(worker());
  }

  await Promise.all(workers);

  return { downloaded, skipped, failed };
}

/** Anzahl der aktuell offline vorgehaltenen Kacheln. */
export async function cachedTileCount(): Promise<number> {
  if (!hasCacheStorage()) {
    return 0;
  }

  if (!(await caches.has(TILE_CACHE_NAME))) {
    return 0;
  }

  const cache = await caches.open(TILE_CACHE_NAME);
  const keys = await cache.keys();
  return keys.length;
}

/** Verwirft alle offline vorgehaltenen Kacheln. */
export async function clearTileCache(): Promise<void> {
  if (!hasCacheStorage()) {
    return;
  }

  await caches.delete(TILE_CACHE_NAME);
}
