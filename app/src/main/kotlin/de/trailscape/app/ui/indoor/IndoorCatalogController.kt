package de.trailscape.app.ui.indoor

import de.trailscape.app.indoor.catalog.CatalogDownloader
import de.trailscape.app.indoor.catalog.CatalogFetchResult
import de.trailscape.app.indoor.catalog.CatalogStore
import de.trailscape.core.indoor.catalog.CatalogAutoRefresh
import de.trailscape.core.indoor.catalog.CatalogEntry
import de.trailscape.core.indoor.catalog.CatalogPreview
import de.trailscape.core.indoor.catalog.CatalogProfile
import de.trailscape.core.indoor.catalog.CatalogPackageException
import de.trailscape.core.indoor.catalog.IndoorCatalog
import java.io.IOException
import java.util.concurrent.CancellationException as JavaCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * # Bereich „Bekannte Strecken" (docs/indoor/catalog.md)
 *
 * Haelt den Zustand der Katalogkarten und fuehrt die Nutzeraktionen aus:
 * „Laden" (holt zuerst den kleinen Katalog des Release fuer Version,
 * Groesse und Pruefsumme, dann das Paket mit Fortschritt, Abbruch und
 * Wiederaufnahme, prueft, entpackt in die Weltverwaltung), „Abbrechen",
 * „Katalog aktualisieren".
 *
 * **Netz ohne Nutzeraktion** (seit 24.11.2026, vom Gruender freigegeben,
 * PRIVACY.md): [load] liest zuerst nur die Platte und zeigt die Liste; ist
 * das Geraet online ([isOnline]) und [CatalogAutoRefresh] faellig
 * (hoechstens einmal am Tag), holt es danach still `catalog.json` aus dem
 * Release. Ein Fehler zeigt keine Meldung, die vorhandene Liste bleibt.
 * Ebenso still laedt es die kleinen Vorschaubilder der Eintraege (nur von
 * der Adresse des Katalogs, einmal je Bild und Version, danach aus dem Cache).
 * Pakete laedt weiterhin nur „Laden".
 *
 * Es laeuft hoechstens ein Download gleichzeitig.
 *
 * @param store `null` = kein Katalog (Tests ohne Dateien) — die Liste bleibt leer.
 * @param isOnline ob ein Netz da ist (kein Flugmodus); `false` = nichts von selbst abrufen.
 * @param now Wanduhr fuer den taeglichen Abruf.
 */
class IndoorCatalogController(
    private val store: CatalogStore?,
    private val downloader: CatalogDownloader?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val isOnline: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _ui = MutableStateFlow(IndoorCatalogUi(items = emptyList(), loading = store != null))
    val ui: StateFlow<IndoorCatalogUi> = _ui.asStateFlow()

    @Volatile private var catalog: IndoorCatalog? = null
    @Volatile private var installed: Map<String, CatalogStore.Installed> = emptyMap()
    @Volatile private var partials: Map<String, Long> = emptyMap()

    /** Zustaende, die sich nicht aus der Platte ergeben (laufend, gescheitert). */
    private val live = java.util.concurrent.ConcurrentHashMap<String, IndoorCatalogState>()

    private var job: Job? = null
    @Volatile private var runningId: String? = null

    @Volatile private var cancelRequested = false

    /** Vorschaubilder je Dateiname, die auf der Platte liegen. */
    private val previews = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Vorschaubilder, deren Abruf in dieser Sitzung scheiterte (kein zweiter Versuch). */
    private val previewFailed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Kennungen, die mit dem letzten Abruf neu in die Liste kamen. */
    @Volatile private var newIds: Set<String> = emptySet()

    /** Der automatische Abruf wurde in dieser Sitzung schon erwogen. */
    @Volatile private var autoChecked = false

    /**
     * Liest Katalog, installierte Welten und angefangene Downloads (ohne
     * Netz) und zeigt die Liste. Danach, falls online und faellig, der stille
     * Abruf des Release-Katalogs und die Vorschaubilder.
     */
    fun load() {
        val s = store ?: return
        scope.launch {
            withContext(io) { readDisk(s) }
            publish(loading = false)
            autoRefresh(s)
            loadPreviews(s, fetch = isOnline())
        }
    }

    /** Der stille, hoechstens taegliche Abruf (docs/indoor/catalog.md, „Automatisch aktualisieren"). */
    private suspend fun autoRefresh(s: CatalogStore) {
        val d = downloader ?: return
        if (autoChecked) return
        autoChecked = true
        if (_ui.value.refreshing || runningId != null) return
        val online = runCatching { isOnline() }.getOrDefault(false)
        if (!online) return
        val nowMs = now()
        if (!CatalogAutoRefresh.isDue(s.lastRefreshAttemptMs(), nowMs)) return
        _ui.update { it.copy(refreshing = true) }
        val updated = withContext(io) {
            val before = catalog?.entries.orEmpty().map { it.id }.toSet()
            val base = catalog ?: s.catalog()
            val r = d.fetchText(base.catalogUrl())
            // Ein Netzfehler (offline) zaehlt nicht als Versuch; alles andere schon.
            if (!(r is CatalogFetchResult.Failed && r.offline)) runCatching { s.markRefreshAttempt(nowMs) }
            val ok = r is CatalogFetchResult.Done && runCatching { s.saveRemote(r.text.orEmpty()) }.isSuccess
            if (ok) {
                readDisk(s)
                newIds = newIds + (catalog?.entries.orEmpty().map { it.id }.toSet() - before).takeIf { before.isNotEmpty() }.orEmpty()
            }
            ok
        }
        if (updated) live.entries.removeAll { it.value is IndoorCatalogState.Failed && (it.value as IndoorCatalogState.Failed).reason == IndoorCatalogFailure.NOT_AVAILABLE }
        publish(refreshing = false)
    }

    /**
     * Vorschaubilder: aus dem Cache, mit [fetch] fehlende aus dem Release
     * (nacheinander, je Bild hoechstens ein Versuch je Sitzung). Raeumt
     * Bilder alter Versionen weg.
     */
    private suspend fun loadPreviews(s: CatalogStore, fetch: Boolean) {
        val c = catalog ?: return
        val d = downloader
        withContext(io) { runCatching { s.prunePreviews(c) } }
        previews.keys.retainAll(c.entries.mapNotNull { it.preview?.file }.toSet())
        for (e in c.entries) {
            val p = e.preview ?: continue
            if (previews.containsKey(p.file)) continue
            val cached = withContext(io) { runCatching { s.cachedPreview(e) }.getOrNull() }
            if (cached != null) {
                previews[p.file] = cached.path
                publish()
                continue
            }
            if (!fetch || d == null || p.file in previewFailed) continue
            val url = c.previewUrl(e) ?: continue
            val saved = withContext(io) {
                runCatching { d.fetchBytes(url, CatalogPreview.MAX_BYTES)?.let { s.savePreview(e, it) } }.getOrNull()
            }
            if (saved != null) {
                previews[p.file] = saved.path
                publish()
            } else {
                previewFailed += p.file
            }
        }
    }

    private fun readDisk(s: CatalogStore) {
        val c = runCatching { s.catalog() }.getOrNull()
        catalog = c
        installed = runCatching { s.installed() }.getOrDefault(emptyMap())
        partials = c?.entries.orEmpty().associate { it.id to runCatching { s.partialBytes(it) }.getOrDefault(0L) }
    }

    /** Die installierte Welt der Strecke [id] (blockierend, fuer `Dispatchers.IO`). */
    fun installedWorld(id: String): CatalogStore.Installed? = runCatching { store?.installed()?.get(id) }.getOrNull()

    /** Katalogeintrag [id] (wie zuletzt gelesen). */
    fun entry(id: String): CatalogEntry? = catalog?.entry(id)

    /** Nutzeraktion „Laden" bzw. „Fortsetzen"/„Aktualisieren". */
    fun download(id: String) {
        val s = store ?: return
        val d = downloader ?: return
        if (runningId != null) return
        val base = catalog?.entry(id) ?: return
        runningId = id
        cancelRequested = false
        live[id] = IndoorCatalogState.Downloading(partials[id] ?: 0L, base.packageBytes)
        publish()
        job = scope.launch {
            val result = try {
                withContext(io) { downloadAndInstall(s, d, id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                IndoorCatalogState.Failed(IndoorCatalogFailure.OTHER, e.toString())
            } finally {
                runningId = null
            }
            withContext(io) { readDisk(s) }
            if (result == null) live.remove(id) else live[id] = result
            publish()
        }
    }

    /** `null` = fertig oder abgebrochen (Zustand ergibt sich dann aus der Platte). */
    private fun downloadAndInstall(s: CatalogStore, d: CatalogDownloader, id: String): IndoorCatalogState? {
        val bundledOrMerged = catalog ?: return IndoorCatalogState.Failed(IndoorCatalogFailure.OTHER, "kein Katalog")
        val hasWorld = installed.containsKey(id)
        // 1. Den kleinen Katalog des Release: Version, Groesse, Pruefsumme.
        val fresh = when (val r = d.fetchText(bundledOrMerged.catalogUrl())) {
            is CatalogFetchResult.Done -> runCatching { s.saveRemote(r.text.orEmpty()) }
                .getOrElse { return IndoorCatalogState.Failed(IndoorCatalogFailure.OTHER, it.toString(), hasWorld) }
            CatalogFetchResult.NotAvailable -> return IndoorCatalogState.Failed(IndoorCatalogFailure.NOT_AVAILABLE, null, hasWorld)
            is CatalogFetchResult.Failed -> return IndoorCatalogState.Failed(
                if (r.offline) IndoorCatalogFailure.OFFLINE else IndoorCatalogFailure.OTHER, r.detail, hasWorld, partials[id] ?: 0L,
            )
            else -> return null
        }
        val merged = bundledOrMerged.mergedWith(fresh)
        catalog = merged
        val entry = fresh.entry(id) ?: return IndoorCatalogState.Failed(IndoorCatalogFailure.NOT_AVAILABLE, null, hasWorld)
        val sha = entry.sha256 ?: return IndoorCatalogState.Failed(IndoorCatalogFailure.NOT_AVAILABLE, null, hasWorld)
        if (cancelRequested) return null
        // 2. Das Paket.
        val part = s.partFile(entry)
        val start = if (part.isFile) part.length() else 0L
        live[id] = IndoorCatalogState.Downloading(start, entry.packageBytes)
        scope.launch { publish() }
        var lastShown = -1
        val result = d.download(
            url = merged.packageUrl(entry),
            part = part,
            expectedBytes = entry.packageBytes,
            expectedSha256 = sha,
            onProgress = { done, total ->
                val permille = if (total > 0) (done * 1000 / total).toInt() else 0
                if (permille != lastShown) {
                    lastShown = permille
                    live[id] = IndoorCatalogState.Downloading(done, total, cancelling = cancelRequested)
                    scope.launch { publish() }
                }
            },
            isCancelled = { cancelRequested },
        )
        return when (result) {
            is CatalogFetchResult.Done -> {
                live[id] = IndoorCatalogState.Installing
                scope.launch { publish() }
                try {
                    s.install(entry, result.file ?: part) { cancelRequested }
                    null
                } catch (e: JavaCancellation) {
                    null
                } catch (e: CatalogPackageException) {
                    s.discardPartial(entry)
                    val reason = when (e.reason) {
                        CatalogPackageException.Reason.CHECKSUM -> IndoorCatalogFailure.CHECKSUM
                        CatalogPackageException.Reason.IO -> IndoorCatalogFailure.NO_SPACE
                        CatalogPackageException.Reason.INVALID -> IndoorCatalogFailure.OTHER
                    }
                    IndoorCatalogState.Failed(reason, e.toString(), hasWorld)
                } catch (e: IOException) {
                    IndoorCatalogState.Failed(IndoorCatalogFailure.NO_SPACE, e.toString(), hasWorld)
                } finally {
                    s.cleanupStaging()
                }
            }
            CatalogFetchResult.Cancelled -> null
            CatalogFetchResult.NotAvailable -> IndoorCatalogState.Failed(IndoorCatalogFailure.NOT_AVAILABLE, null, hasWorld)
            CatalogFetchResult.ChecksumMismatch -> IndoorCatalogState.Failed(IndoorCatalogFailure.CHECKSUM, null, hasWorld)
            is CatalogFetchResult.Failed -> IndoorCatalogState.Failed(
                if (result.offline) IndoorCatalogFailure.OFFLINE else IndoorCatalogFailure.OTHER,
                result.detail,
                hasWorld,
                if (part.isFile) part.length() else 0L,
            )
        }
    }

    /** Nutzeraktion „Abbrechen": der angefangene Teil bleibt fuer „Fortsetzen". */
    fun cancel(id: String) {
        if (runningId != id) return
        cancelRequested = true
        live[id]?.let { if (it is IndoorCatalogState.Downloading) live[id] = it.copy(cancelling = true) }
        publish()
    }

    /** Ablauf verlassen: ein laufender Download endet am naechsten Block (Teil bleibt liegen). */
    fun release() {
        cancelRequested = true
        job?.cancel()
    }

    /** Verwirft einen angefangenen Download bzw. eine Fehlermeldung. */
    fun discard(id: String) {
        val s = store ?: return
        if (runningId == id) return
        val e = catalog?.entry(id) ?: return
        live.remove(id)
        scope.launch {
            withContext(io) {
                s.discardPartial(e)
                readDisk(s)
            }
            publish()
        }
    }

    /** Nutzeraktion „Katalog aktualisieren": holt `catalog.json` aus dem Release. */
    fun refreshCatalog() {
        val s = store ?: return
        val d = downloader ?: return
        if (_ui.value.refreshing) return
        _ui.update { it.copy(refreshing = true, refreshResult = null) }
        scope.launch {
            val result = withContext(io) {
                val base = catalog ?: s.catalog()
                val before = catalog?.entries.orEmpty().map { it.id }.toSet()
                val r = d.fetchText(base.catalogUrl())
                if (!(r is CatalogFetchResult.Failed && r.offline)) runCatching { s.markRefreshAttempt(now()) }
                when (r) {
                    is CatalogFetchResult.Done -> if (runCatching { s.saveRemote(r.text.orEmpty()) }.isSuccess) IndoorCatalogRefresh.UPDATED else IndoorCatalogRefresh.FAILED
                    CatalogFetchResult.NotAvailable -> IndoorCatalogRefresh.NOT_AVAILABLE
                    else -> IndoorCatalogRefresh.FAILED
                }.also {
                    readDisk(s)
                    if (it == IndoorCatalogRefresh.UPDATED && before.isNotEmpty()) {
                        newIds = newIds + (catalog?.entries.orEmpty().map { e -> e.id }.toSet() - before)
                    }
                }
            }
            // Ein „noch nicht verfuegbar" von frueher ist nach einer neuen Liste ueberholt.
            if (result == IndoorCatalogRefresh.UPDATED) live.entries.removeAll { it.value is IndoorCatalogState.Failed }
            publish(refreshing = false, refreshResult = result)
            // Von Hand ausgeloest: fehlende Bilder auch ohne Netzanzeige versuchen.
            previewFailed.clear()
            loadPreviews(s, fetch = true)
        }
    }

    /** Blendet die Meldung von „Katalog aktualisieren" aus. */
    fun dismissRefreshResult() {
        _ui.update { it.copy(refreshResult = null) }
    }

    private fun publish(
        loading: Boolean = _ui.value.loading,
        refreshing: Boolean = _ui.value.refreshing,
        refreshResult: IndoorCatalogRefresh? = _ui.value.refreshResult,
    ) {
        val c = catalog
        val items = c?.entries.orEmpty().map { e -> itemOf(e, stateOf(e)) }
        _ui.value = IndoorCatalogUi(items, loading, refreshing, refreshResult)
    }

    private fun stateOf(e: CatalogEntry): IndoorCatalogState {
        live[e.id]?.let { return it }
        val inst = installed[e.id]
        return when {
            inst != null && inst.version < e.version -> IndoorCatalogState.UpdateAvailable(inst.version, e.version)
            inst != null -> IndoorCatalogState.Ready
            (partials[e.id] ?: 0L) > 0L -> IndoorCatalogState.Paused(partials[e.id] ?: 0L, e.packageBytes)
            else -> IndoorCatalogState.NotLoaded
        }
    }

    /** Profil einer geladenen Welt (je `worldId` einmal gerechnet), wenn der Katalog keins hat. */
    private val worldProfiles = java.util.concurrent.ConcurrentHashMap<String, CatalogProfile>()

    private fun profileOfWorld(m: de.trailscape.core.indoor.world.WorldManifest): CatalogProfile? =
        worldProfiles[m.worldId] ?: runCatching {
            CatalogProfile.thinned(m.track.lengthM, m.track.sampleCount, m.track::distAt, { m.track.u[it].toDouble() })
        }.getOrNull()?.also { worldProfiles[m.worldId] = it }

    private fun itemOf(e: CatalogEntry, state: IndoorCatalogState) = IndoorCatalogItemUi(
        id = e.id,
        name = e.name,
        region = e.region,
        description = e.description,
        country = e.country,
        kind = e.kind,
        // Bereit: Zahlen der Welt auf dem Geraet, sonst die des Katalogs.
        distanceKm = (installed[e.id]?.manifest?.track?.lengthM ?: e.lengthM) / 1000.0,
        ascentM = e.ascentM,
        surfaces = e.surfaces.entries.sortedByDescending { it.value }.map { it.key to it.value },
        packageBytes = e.packageBytes,
        aerial = e.aerial,
        state = state,
        laser = e.laser,
        profile = e.profile ?: installed[e.id]?.manifest?.let(::profileOfWorld),
        previewPath = e.preview?.file?.let { previews[it] },
        isNew = e.id in newIds,
        diskBytes = e.diskBytes,
        aerialSource = e.aerialSource,
    )
}
