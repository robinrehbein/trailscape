# Fix Plan: All Review Findings — Parallel Workstreams

## Context

Full review of Trailscape (Kotlin/Compose cycling app; `:core` pure-JVM domain, `:app` Android, `server/` Node sync server) found defects in sync semantics, data-import integrity, training math, recording stats, and privacy docs. This plan fixes **all** findings via parallel subagent workstreams with strict file ownership, followed by an orchestrator-run integration pass.

**Environment constraint (critical):** this sandbox has **no Java, no Android SDK, no Gradle cache**. No compile/test validation is possible locally. Therefore:
- Every change below is specified precisely (file, line region, exact behavior).
- Each workstream (WS) must update/add unit tests even though they cannot be run here; tests are the CI safety net (`./gradlew clean :core:test :app:testDebugUnitTest :app:assembleRelease` runs on push).
- Do not delete/weaken existing locked tests (662 in `:core`) without a stated justification and a replacement assertion.
- Follow the repo's existing conventions: German KDoc style, write-only-when-set JSON field pattern, `runCatching` where the codebase uses it.
- Orchestrator may best-effort attempt `apt-get install -y openjdk-21-jdk-headless` + `./gradlew :core:test` (abandon gracefully if downloads exceed the 2-min command timeout).

## User-approved design decisions

1. **Sync:** tombstones for deletes + optional `updatedAt` field with last-write-wins update propagation.
2. **Privacy (update check):** fix PRIVACY.md + in-app text; keep the feature.
3. **Sync token:** move to a separate SharedPreferences file excluded from cloud backup; migrate existing token; disclose in PRIVACY.md.
4. **Pause stats:** pause-interval-aware `computeStats` (default param preserves old behavior).
5. **Scope:** all findings, including the lower-severity batch.

## Cross-workstream contracts (fixed; do not deviate)

- **`Ride.updatedAt: Long? = null`** (WS2 adds): JSON key `"updatedAt"`, written only when non-null, read via `optionalLong`. LWW rule: higher `updatedAt` wins; `null` treated as `0`; tie → local wins and pushes.
- **`core/RideValidation.kt`** (WS2 creates): `val RIDE_ID_RE = Regex("^[A-Za-z0-9-]{1,64}$")` (matches `server.mjs:27`), `fun isValidRideId(id: String): Boolean`, `fun sanitizedRideId(raw: String, nowMs: Long, random: () -> String): String` (valid → unchanged; invalid → `"<nowMs>-<random6base36>"`).
- **`SyncTombstones`** (WS1 creates in core): backed by `KeyValueStore`, key `"trailscape.sync.tombstones"` (JSON string array). API: `add(id)`, `remove(id)`, `ids(): Set<String>`. Lives in main prefs (not the secret sync prefs file from WS6). AppViewModel/AppServices wiring happens only in the integration phase.
- **M7 contract:** `importWithReport` keeps its signature but no longer persists the import timestamp. WS5a's caller does `setLastImportAt(report.to)` **after** successful `saveRides` (rollback to `report.from` on failure, as today).
- **Pause intervals:** `data class PauseInterval(fromMs: Long, toMs: Long)` in `:core`; `computeStats(points, pauseIntervals = emptyList())` — a segment p[i]→p[i+1] is excluded from distance and moving-time when its time range overlaps any interval (inclusive bounds; points without time never excluded).

## File-ownership matrix (one owner per file in Phase 1)

| Workstream | Owned files |
|---|---|
| WS1 Sync | `core/SyncClient.kt`, `core/HttpClient.kt`, `server/server.mjs`, `server/README.md`, `app/ui/more/SyncCard.kt`, `app/data/OkHttpClientAdapter.kt` (auth-redirect guard only) |
| WS2 Data integrity | `core/RideValidation.kt` (new), `core/Models.kt`, `core/Export.kt`, `core/Gpx.kt`, `core/BulkImport.kt`, `app/data/RideStorage.kt` (+ respective test files) |
| WS3 Recording | `app/record/RecordingService.kt`, `app/record/RecordingJournal.kt`, `app/record/RecordingLogic.kt`, `app/record/RecordingRepository.kt`, `core/Stats.kt`, `core/PointFilter.kt` |
| WS4 Training math | `core/PerformanceManagement.kt`, `core/Readiness.kt`, `core/VitalsHistory.kt`, `core/HealthSyncLogic.kt`, `core/HealthTypes.kt`, `core/Fit.kt`, `core/TrainingLoad.kt`, `app/ui/TrainingInsights.kt` |
| WS5a ViewModel/crash/reminders | `app/ui/AppViewModel.kt`, `app/feedback/CrashReporter.kt`, `app/reminder/ReminderWorker.kt` |
| WS5b MapScreen | `app/ui/map/MapScreen.kt` |
| WS6 Privacy/backup config | `PRIVACY.md`, `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `app/data/PrefsStores.kt`, `app/data/AppServices.kt`, in-app privacy text (locate under `app/ui/more/`) |
| WS7 Offline segments | `app/routing/SegmentDownloader.kt`, `app/routing/SegmentDownloadWorker.kt`, `app/routing/SegmentInventory.kt`, `core/RoutingSegments.kt`, `core/Routing.kt` |
| WS8 Misc UI/wear | `app/ui/rides/RidesScreen.kt`, `app/ui/ShareFiles.kt`, `wear/src/main/kotlin/de/trailscape/wear/record/SpikeService.kt` |

No file appears twice. `AppViewModel.kt` is touched only by WS5a in Phase 1; cross-cutting wiring (tombstones) is integration-phase work.

---

## WS1 — Sync semantics (C1, H2, L1, L2, L3, server atomic write, auth-redirect)

1. `HttpClient.kt`: add `DELETE` to `HttpMethod`.
2. `SyncClient.kt` `syncRides` rework:
   - Load tombstones. Push: ride missing remotely **or** `(ride.updatedAt ?: 0) > (remote.updatedAt ?: 0)`. Pull: remote missing locally **or** `(remote.updatedAt ?: 0) > (local.updatedAt ?: 0)` → overwrite. Skip pulled entries with invalid IDs (per `RIDEValidation` contract) and count them in the result. Never pull an ID that is tombstoned. Issue `DELETE /api/rides/{id}` for tombstoned IDs present remotely; on 404/405 (old selfhost server without the endpoint or already gone) treat as confirmed. Prune confirmed tombstones.
   - `fetchRemoteRides`: parse `updatedAt` per entry; replace unchecked casts (L1) with safe null-checked access; non-primitive/garbage entries are skipped with a count.
   - Extend `SyncResult` with `deleted`, `skippedRemote` counts; `SyncCard.kt` renders them.
3. `server/server.mjs`: pass through `updatedAt` from ride JSON (verify it stores/returns the full object — likely free); L2: wrap `decodeURIComponent` in try/catch → 400; L3: reject the `readBody` promise on `'aborted'` unconditionally; atomic write: `fs.writeFile` → `fs.open`+`write`+`fh.sync()`+rename, plus best-effort directory fsync, all in try/catch.
4. `OkHttpClientAdapter.kt`: add a network interceptor that strips the `Authorization` header when a redirect crosses hosts (do **not** disable redirects globally — shared client also does tiles/geocoding).
5. `server/README.md`: document DELETE usage and `updatedAt` semantics.
6. Tests: extend `SyncClientTest` — delete propagation (tombstone → DELETE called, no re-pull), update push/pull by `updatedAt` (incl. null-vs-set), invalid remote ID skipped not thrown, old-server 405 prune. Compatibility note: changes are additive; old clients ignore `updatedAt`.
7. **Depends on WS2** (`Ride.updatedAt`, `isValidRideId`) for compilation — author in parallel, merge after WS2.

## WS2 — Data integrity (C4, M3, M5, M6, M8, BulkImport OOM, XML control chars, tmp cleanup)

1. `RideValidation.kt` per contract. `RideStorage.saveRide/getRide/deleteRide/renameRide` validate the ID first (throw `IllegalArgumentException` on invalid — callers guarded by WS5a's `runCatching`).
2. Sanitize at import boundaries: `Export.kt` backup restore + `rideFromGpx`/FIT import + `BulkImport` entries → `sanitizedRideId(...)` when invalid; set `updatedAt = nowMs` on newly created/imported rides. Pull-path skipping is WS1's job.
3. `Models.kt`: `updatedAt` field per contract. Finite-value hardening (M8): `TrackPoint.fromJson` → non-finite required `lat`/`lon` throws (ride file rejected, not re-poisoned); `ele` non-finite → null. `RideStats.fromJson` → non-finite doubles → null/default. `toJson` writes `ele` only when finite.
4. `Gpx.kt` M3: in `parseTimeToMs`, after `OffsetDateTime`/`Instant` fail, try `LocalDateTime.parse(trimmed).atZone(ZoneId.systemDefault()).toInstant()` (1.x Dart parity; local-zone by design). L4: `escapeXmlText`/`escapeXmlAttr` strip XML-1.0-invalid control chars (`< 0x20` except `\t\n\r`, and 0x7F–0x9F policy: strip).
5. `RideStorage.kt` M5: unique temp name (`"${file.name}.${UUID.randomUUID()}.tmp"` — still ends with `.tmp` so `listRides` filter holds); `synchronized(this)` around save/rename/delete bodies; after successful `renameTo`, best-effort parent-dir fsync via `runCatching { android.system.Os.open(...); Os.fsync(...) }` (swallowed on JVM unit-test stubs); fallback path writes via a *second* unique tmp + copy, never truncate-in-place; delete stale `*.tmp` leftovers older than 1 h during `listRides`.
6. `Export.kt` M6: backup restore becomes per-entry tolerant — returns `RestoreResult(imported: List<Ride>, skipped: List<SkippedRide(reason)>)`; `BackupCard` message updated (backupVersion refusal stays strict).
7. `BulkImport.kt`: cap decompressed entry size at 50 MB (`readAllBytesCompat` → bounded read; over-cap → per-entry `FormatException`), keeping per-entry error isolation.
8. Tests: `GpxTest` naive-timestamp cases (inject fixed zone), `ExportTest` mixed-valid backup restore + updatedAt round-trip, `RideStorageTest` traversal-ID rejection + concurrent save (two threads, unique tmp), `BulkImportTest` oversized entry skipped not crashed, NaN/Infinity JSON ride rejected/null-ed.

## WS3 — Recording chain (M1, M2, B1, B5, B6, B8, B9, B11, B12)

1. `RecordingJournal.kt` M2: `reopenForAppend` verifies the file's last byte is `\n` (read via `RandomAccessFile`); if missing and file non-empty, write `"\n"` before appending. B1: `parse()` additionally returns `pauseIntervals: List<PauseInterval>` (closed pairs) and `pausedSinceMs` (already exists) — expose both in the snapshot.
2. `Stats.kt`: `computeStats(points, pauseIntervals = emptyList())` per contract; M8/B8: `durationS` clamped `>= 0`.
3. `RecordingService.kt`:
   - M1: finalize passes closed intervals **plus** the open pause closed at stop time (`pausedSinceMs..stopWallClock`); live `distanceM`: `resumePending` flag → skip the `haversineM(previous, point)` addition for the first accepted point after resume.
   - B6: on `enterForeground()` failure, `stopSelfSafely()` **first**, then run `failAndStop`'s recovery save on the handler thread (journal survives either way).
   - B5: `stopSelf(startId)` (thread `startId` through from `onStartCommand`) in `stopSelfSafely`.
   - B9: `onProviderEnabled` only clears the error notification when the current error is GPS-loss-typed — add a type to `RecordingRepository.reportError` (journal-write errors are not cleared).
   - B11: run `pruefeStandortStrom` resubscribe check also while paused (warning display still suppressed via existing `gpsStilleMs` null-return).
   - B12: `onDestroy` publishes stopped state to `RecordingRepository`.
   - Set `updatedAt = nowMs` on the `Ride` built in `buildRide`.
4. `RecordingLogic.kt`: `ohnePausenzeit` unchanged (intervals now do the heavy lifting); reconcile KDocs.
5. Time-base mixing (GPS point times vs wall-clock markers, old B7): **accepted limitation** — document in one KDoc sentence at the interval handling.
6. Tests: journal reopen-after-truncated-line (partial line + append → parse yields the new point); pause-interval stats (jump across pause excluded from distance/moving-time; no-pause default byte-identical); open-pause-at-stop subtraction; negative-time clamp.

## WS4 — Training/readiness/health math (C2, H1, M4, M7-core, DST windows, FIT table, rolling-power window)

1. `PerformanceManagement.kt` C2: `last = min(days.last(), atMidnight(until))` when `until != null` (replace the extend-only branch; keep extension when `until` beyond).
2. `TrainingInsights.kt` C2: `riddenRides(rides).filter { it.createdAt <= nowMs }` before sorting; `VitalsHistory.toSummary` gains an upper day-bound at `now` (same filter style as its lower bound).
3. `Readiness.kt` H1: line ~139 → `val diff = dayDifference(ref, it.day); diff in 0..2`.
4. `HealthSyncLogic.kt` M4: `overlapRatio` symmetric — `overlap / min(durationA, durationB) > threshold`; overlap-range construction excludes `planned` rides; boundary sample filter start-exclusive (`> start`); M7: remove the internal `setLastImportAt(to)` (per contract). DST: replace absolute `-6*24h` / `-13*24h` (lines ~567, ~929) with calendar `addDays(today, -6/-13)`.
5. `Fit.kt`: correct `baseTypeSize`/`invalidRaw` to the FIT spec (`0x0A sint64` 8B/`0x7FFF...FF` invalid, `0x0B uint64` 8B/all-ones, `0x0C uint64z` 8B/0, `0x0D byte` 1B; keep alignment-by-declared-size).
6. `TrainingLoad.kt`: `bestRollingMeanPowerW` — form windows from sample *timestamps* (true 20-min windows) instead of wall-clock span since first sample.
7. Tests: future-dated ride no longer extends series/`weeklyLoad`/`historyDays`; readiness future sample excluded; symmetric overlap (3h-watch vs 1h-ride now skipped); DST-boundary week windows (fixed zone, spring-forward date); FIT 64-bit field decode; rolling-power window with gapped samples.

## WS5a — ViewModel, crash reporter, reminders (C3, undo races, M7-caller, segment-offer re-ask, reminder-during-recording)

1. `AppViewModel.kt` C3: `addRide`/`addRides`/`renameRide` wrap the IO block in `runCatching`; on failure surface via the existing error/message flow (locate the pattern used by sync errors) and do not reload. `renameRide` also sets `ride.updatedAt = System.currentTimeMillis()` before save.
2. M7-caller per contract: `applyReport` → on successful `saveRides`, `setLastImportAt(report.to)`; failure rollback unchanged.
3. Undo races: `PendingDeletion` gains `val cancelled = AtomicBoolean(false)`; `undoDeleteRide` sets it and re-inserts **only if** the id is absent from `_rides.value` (dedupe against reload-during-grace); the delete coroutine checks the flag immediately before `rideStorage.deleteRide` and aborts if set.
4. Segment offer (old L6-app): move `askedSegmentOffers.add(key)` to after `describeSegmentOffer` returned non-null.
5. `ReminderWorker.kt`: if `RecordingRepository.isRecording` → skip showing (still mark delivered) — live recording outranks the daily reminder.
6. `CrashReporter.kt` M9: bounded stack-trace builder — iterate frames + causes (cause depth ≤ 8), append each frame only while builder length < 200k, never materialize the full string.
7. Tests: app-test additions where the existing app test setup allows (crash-reporter truncation for synthetic huge traces; undo dedupe logic if extractable).

## WS5b — MapScreen (H2-cancellation, nav reset, progress text, Nominatim rate, share-name call site)

1. H2: in the planning `LaunchedEffect`, `val result = runCatching { ... }.also` → restructure: catch block rethrows `CancellationException` before mapping to error state (`if (it is CancellationException) throw it`), so a stale cancelled run can never clear `plannedRoute`/set `planError`/"Job was cancelled".
2. Nav reset: hoist the `RouteNavigator` into `remember(navTarget) { ... }`; position-collection effect keyed on `navTarget` only (not `isRecording`) so off-route hysteresis/`lastSegmentIndex` survive recording start.
3. Progress text: unify to 1-based ("Teilstrecke 1 von N" at start) for both sources.
4. Nominatim rate: minimum 1100 ms between actual network searches inside the search effect (track last-request timestamp; `delay` the remainder; only the latest effect issues the request).
5. Share call site (~line 1671): use WS8's collision-safe share-name helper (contract: `ShareFiles.uniqueShareName(dir, baseName, ext)`), guarded by a local fallback if WS8 hasn't landed.
6. Manual verification notes only (Compose UI, no unit tests).

## WS6 — Privacy & backup config (H3, H4)

1. `PRIVACY.md`: add `api.github.com` row to the recipient table (update check ≤ 1×/24 h, User-Agent, no other data); delete the "keine Update-Prüfung" sentence in § "keine weiteren Netzwerkverbindungen"; locate and sync the in-app Datenschutz screen text under `app/ui/more/`.
2. `PrefsStores.kt` + `AppServices.kt`: sync config moves to dedicated `SharedPreferences("trailscape_sync")` wrapped by a second `KeyValueStore` impl; one-time startup migration: if the old `trailscape.sync` key exists in `trailscape_prefs`, copy it to the new store and **remove** the old key (token must not linger in the backed-up main file).
3. `backup_rules.xml` + `data_extraction_rules.xml`: `<exclude domain="sharedpref" path="trailscape_sync.xml"/>` in both.
4. `PRIVACY.md` §7: disclose that sync credentials are excluded from device backup (and that everything else in prefs is included).
5. Read-only dependency: WS1's `SyncTombstones` stays in main prefs — confirm no key collision (`trailscape.sync.tombstones` ≠ migrated `trailscape.sync`).

## WS7 — Offline segments & routing client (M10, worker retry classification, profile cache, profile upload polish)

1. `SegmentDownloader.kt` M10: full downloads get content verification — reuse the existing server-MD5 probe / `verifyAssembled` CRC machinery for the final placed file; on mismatch, delete and throw (retryable). Resumed `.part` files: after final assembly, same verification (removes trust-in-earlier-bytes).
2. `SegmentDownloadWorker.kt`: new `SegmentPermanentException : IOException` thrown for 404-HEAD ("gibt es auf dem Server nicht") and ENOSPC-style write failures (match on `FileUtil`/IOException errno or message); worker maps it to `Result.failure` without retry; plain `IOException` keeps `retryOrFail`.
3. `Routing.kt`: `customGravelProfileId` → `@Volatile` (or synchronized accessor); profile upload `Content-Type: text/plain`; `requestRouteOnce` chains the original exception as `cause`.
4. Tests: worker classification (permanent → failure, no backoff flag), downloader verification-failure path (corrupted file deleted, retryable error), `RoutingTest` unchanged behavior.

## WS8 — Misc UI & wear (share-name collisions, wear double-start)

1. `ShareFiles.kt`: `uniqueShareName(dir, baseName, ext)` — appends `-2`, `-3`, … while the target exists; `RidesScreen.kt` share path uses it (fixes same-name rides overwriting each other's share files within the 1 h prune window).
2. `SpikeService.kt`: `ACTION_START` sets the `laeuft` guard **synchronously** in `onStartCommand` before launching the coroutine (coroutine resets it on preparation failure); PAUSE/RESUME/STOP unchanged.
3. Tests: `uniqueShareName` unit test if ShareFiles is test-covered; wear has no test infra — code change only.

---

## Integration phase (orchestrator, after Phase-1 merges)

Merge order: **WS2 → WS1 → WS4 → WS3 → WS5a → WS5b → WS6 → WS7 → WS8** (WS1 compiles only after WS2; WS5a's M7-caller assumes WS4's contract). Then:

1. Wire `SyncTombstones`: construct in `AppServices` (main prefs store), pass into `AppViewModel`; record on `deleteRide` + `deleteRideWithUndo` expiry; `remove` on `undoDeleteRide`.
2. Audit `updatedAt` set-points: import (WS2), recording finalize (WS3), rename + health merge (WS5a) — all present and consistent.
3. Cross-review the full diff for contract violations; verify no file was edited by two workstreams.
4. Best-effort: install JDK 21, run `./gradlew :core:test` (abandon if downloads exceed timeouts; report as unrun).
5. Consistency pass: README (sync section: deletes/updates now propagate), server/README, PRIVACY.md.

## Validation plan

- Per-WS: author lists every touched test with expected outcome; orchestrator reviews diffs against this plan line-by-line.
- CI (on push, existing workflow): `clean :core:test :app:testDebugUnitTest :app:assembleRelease` — the authoritative gate.
- Required new regression tests minimum: WS1 (4 cases), WS2 (6), WS3 (4), WS4 (6), WS7 (2), WS8 (1).

## Risks & accepted limitations

- **No local compile** → signature drift is the top risk; mitigated by fixed contracts + integration review. 
- Sync protocol changes are additive; old selfhost servers without `DELETE` → tombstone prune on 404/405.
- `updatedAt` LWW can lose a concurrent edit on two devices — accepted (single-user data, matches decision).
- GPX naive timestamps interpreted in device local zone — intentional 1.x parity.
- Pause intervals mix wall-clock markers with GPS point times — accepted, documented (old B7).
- Out of scope, documented as known issues: reminder timezone drift (self-correcting), `geodesicPoint` antipodal NaN (unreachable via UI), seam-dedup >2 m (by design), wear NTP boot-time skew (spike).

## Open questions

None — all decisions resolved with the user.
