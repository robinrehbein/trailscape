let wakeLockSentinel: WakeLockSentinel | null = null;
let isWantedFlag = false;
let listenerRegistered = false;

export async function acquireWakeLock(): Promise<void> {
  if (!("wakeLock" in navigator)) {
    return;
  }

  if (wakeLockSentinel !== null) {
    isWantedFlag = true;
    return;
  }

  isWantedFlag = true;

  if (!listenerRegistered) {
    listenerRegistered = true;
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden && isWantedFlag && wakeLockSentinel === null) {
        acquireWakeLock().catch(() => {
          // silent
        });
      }
    });
  }

  try {
    wakeLockSentinel = await navigator.wakeLock.request("screen");
  } catch {
    // silent
  }
}

export async function releaseWakeLock(): Promise<void> {
  isWantedFlag = false;

  if (wakeLockSentinel !== null) {
    try {
      await wakeLockSentinel.release();
    } catch {
      // silent
    }
    wakeLockSentinel = null;
  }
}
