import type { Ride } from "./types";
import { listRides, saveRide } from "./storage";

const STORAGE_KEY = "trailscape.sync";

export interface SyncConfig {
  url: string;
  token: string;
}

export interface SyncResult {
  pushed: number;
  pulled: number;
  total: number;
}

interface RemoteRideSummary {
  id: string;
  name: string;
  createdAt: number;
}

function normalizeUrl(url: string): string {
  return url.trim().replace(/\/+$/, "");
}

export function getSyncConfig(): SyncConfig | null {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (
      typeof parsed === "object" &&
      parsed !== null &&
      typeof (parsed as SyncConfig).url === "string" &&
      typeof (parsed as SyncConfig).token === "string"
    ) {
      return parsed as SyncConfig;
    }
    return null;
  } catch {
    return null;
  }
}

export function setSyncConfig(config: SyncConfig | null): void {
  if (config === null) {
    localStorage.removeItem(STORAGE_KEY);
    return;
  }
  const normalized: SyncConfig = {
    url: normalizeUrl(config.url),
    token: config.token.trim(),
  };
  localStorage.setItem(STORAGE_KEY, JSON.stringify(normalized));
}

function authHeaders(config: SyncConfig): HeadersInit {
  return {
    Authorization: `Bearer ${config.token}`,
  };
}

async function fetchRemoteRides(config: SyncConfig): Promise<RemoteRideSummary[]> {
  let response: Response;
  try {
    response = await fetch(`${config.url}/api/rides`, {
      headers: authHeaders(config),
    });
  } catch {
    throw new Error("Sync-Server nicht erreichbar.");
  }

  if (!response.ok) {
    if (response.status === 401) {
      throw new Error("Token wird vom Server abgelehnt.");
    }
    throw new Error(`Sync fehlgeschlagen (HTTP ${response.status}).`);
  }

  return (await response.json()) as RemoteRideSummary[];
}

async function pushRide(config: SyncConfig, ride: Ride): Promise<void> {
  let response: Response;
  try {
    response = await fetch(`${config.url}/api/rides/${ride.id}`, {
      method: "PUT",
      headers: {
        ...authHeaders(config),
        "Content-Type": "application/json",
      },
      body: JSON.stringify(ride),
    });
  } catch {
    throw new Error(
      `Hochladen der Tour "${ride.name}" fehlgeschlagen: Sync-Server nicht erreichbar.`
    );
  }

  if (!response.ok) {
    throw new Error(
      `Hochladen der Tour "${ride.name}" fehlgeschlagen (HTTP ${response.status}).`
    );
  }
}

function isValidRide(data: unknown): data is Ride {
  return (
    typeof data === "object" &&
    data !== null &&
    typeof (data as Ride).id === "string" &&
    typeof (data as Ride).name === "string" &&
    Array.isArray((data as Ride).points)
  );
}

async function pullRide(config: SyncConfig, entry: RemoteRideSummary): Promise<Ride> {
  let response: Response;
  try {
    response = await fetch(`${config.url}/api/rides/${entry.id}`, {
      headers: authHeaders(config),
    });
  } catch {
    throw new Error(
      `Herunterladen der Tour "${entry.name}" fehlgeschlagen: Sync-Server nicht erreichbar.`
    );
  }

  if (!response.ok) {
    throw new Error(
      `Herunterladen der Tour "${entry.name}" fehlgeschlagen (HTTP ${response.status}).`
    );
  }

  const data = (await response.json()) as unknown;
  if (!isValidRide(data)) {
    throw new Error(
      `Herunterladen der Tour "${entry.name}" fehlgeschlagen: ungültige Daten vom Server.`
    );
  }

  return data;
}

export async function syncRides(): Promise<SyncResult> {
  const config = getSyncConfig();
  if (!config) {
    throw new Error("Sync ist nicht konfiguriert.");
  }

  const remoteRides = await fetchRemoteRides(config);
  const remoteIds = new Set(remoteRides.map((r) => r.id));

  const localRides = await listRides();
  const localIds = new Set(localRides.map((r) => r.id));

  let pushed = 0;
  for (const ride of localRides) {
    if (!remoteIds.has(ride.id)) {
      await pushRide(config, ride);
      pushed++;
    }
  }

  let pulled = 0;
  for (const entry of remoteRides) {
    if (!localIds.has(entry.id)) {
      const ride = await pullRide(config, entry);
      await saveRide(ride);
      pulled++;
    }
  }

  return {
    pushed,
    pulled,
    total: localRides.length + pulled,
  };
}
