import type { Ride } from "./types";

const DB_NAME = "trailscape";
const DB_VERSION = 1;
const STORE_NAME = "rides";

let dbPromise: Promise<IDBDatabase> | null = null;

function wrapRequest<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function openDb(): Promise<IDBDatabase> {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);

      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(STORE_NAME)) {
          db.createObjectStore(STORE_NAME, { keyPath: "id" });
        }
      };

      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }
  return dbPromise;
}

async function getStore(mode: IDBTransactionMode): Promise<IDBObjectStore> {
  const db = await openDb();
  const tx = db.transaction(STORE_NAME, mode);
  return tx.objectStore(STORE_NAME);
}

export async function saveRide(ride: Ride): Promise<void> {
  const store = await getStore("readwrite");
  await wrapRequest(store.put(ride));
}

export async function listRides(): Promise<Ride[]> {
  const store = await getStore("readonly");
  const rides = await wrapRequest(store.getAll() as IDBRequest<Ride[]>);
  return rides.slice().sort((a, b) => b.createdAt - a.createdAt);
}

export async function getRide(id: string): Promise<Ride | undefined> {
  const store = await getStore("readonly");
  const ride = await wrapRequest(store.get(id) as IDBRequest<Ride | undefined>);
  return ride;
}

export async function deleteRide(id: string): Promise<void> {
  const store = await getStore("readwrite");
  await wrapRequest(store.delete(id));
}
