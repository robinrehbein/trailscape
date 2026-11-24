package de.trailscape.app.ui.indoor

import de.trailscape.app.indoor.catalog.CatalogDownloader
import de.trailscape.app.indoor.catalog.CatalogFetchResult
import de.trailscape.app.indoor.catalog.CatalogStore
import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.core.KeyValueStore
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.catalog.CatalogPackageException
import de.trailscape.core.indoor.server.BuilderMismatch
import de.trailscape.core.indoor.server.LaserExpectation
import de.trailscape.core.indoor.server.LaserPreview
import de.trailscape.core.indoor.server.ServerCall
import de.trailscape.core.indoor.server.ServerJobRecord
import de.trailscape.core.indoor.server.ServerJobState
import de.trailscape.core.indoor.server.ServerJobStatus
import de.trailscape.core.indoor.server.ServerWorldRequest
import de.trailscape.core.indoor.server.WorldServerClient
import de.trailscape.core.indoor.server.compareBuilder
import de.trailscape.core.indoor.server.laserPreviewOfPoints
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.worldBuilderVersion
import java.io.File
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Zustand fuer die Oberflaeche
// ---------------------------------------------------------------------------

/** Was der Server-Bau an Laserdaten erwarten laesst (fuer den Text vorher). */
data class IndoorServerLaserUi(
    val expectation: LaserExpectation,
    /** `nw`, `bw` oder `null`. */
    val regionId: String?,
    /** Anteil der Strecke im Rechteck der Quelle, 0..100. */
    val sharePct: Int,
)

fun IndoorServerLaserUi(p: LaserPreview): IndoorServerLaserUi = IndoorServerLaserUi(
    expectation = p.kind,
    regionId = (p.source ?: p.best)?.id,
    sharePct = Math.round((if (p.source != null) p.share else p.bestShare) * 100).toInt(),
)

enum class IndoorServerStep { SENDING, QUEUED, BUILDING, DOWNLOADING, INSTALLING }

enum class IndoorServerFailure { OFFLINE, UNAUTHORIZED, TOO_LONG, QUEUE_FULL, BUILD_FAILED, GONE, CHECKSUM, NO_SPACE, OTHER }

/** Die zweite Wahl im Schritt „Welt laden": Bau auf dem eigenen Server (docs/indoor/serverbau.md). */
sealed interface IndoorServerUi {
    /** Kein Server eingerichtet, kein Bau-Dienst, oder noch nicht gefragt — nichts zeigen. */
    data object Hidden : IndoorServerUi

    /** Der Server bietet den Bau an. */
    data class Offer(val laser: IndoorServerLaserUi, val queueLength: Int, val replacesDeviceWorld: Boolean = false) : IndoorServerUi

    /** Der Server spricht eine andere Welt-Version. */
    data class Incompatible(val mismatch: BuilderMismatch, val serverBuilderId: String) : IndoorServerUi

    /** Auftrag laeuft: senden, Warteschlange, Bau, Download, Installieren. */
    data class Working(
        val step: IndoorServerStep,
        val laser: IndoorServerLaserUi?,
        val queuePosition: Int = 0,
        val fraction: Float = 0f,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val cancelling: Boolean = false,
    ) : IndoorServerUi

    /**
     * Gescheitert. [canResume]: der Auftrag laeuft auf dem Server weiter
     * (z. B. kein Netz) — „Erneut versuchen" fragt nur den Stand wieder ab.
     */
    data class Failed(val reason: IndoorServerFailure, val detail: String? = null, val canResume: Boolean = false) : IndoorServerUi
}

// ---------------------------------------------------------------------------
// Abhaengigkeiten
// ---------------------------------------------------------------------------

/**
 * Was der Server-Bau braucht. [client] liefert den Client fuer den
 * eingerichteten Sync-Server, `null` = keiner eingerichtet.
 */
class IndoorServerDeps(
    val client: () -> WorldServerClient?,
    /** Download-Huelle fuer genau diese Server-Adresse (Weiterleitungen nur dorthin). */
    val downloader: (serverUrl: String) -> CatalogDownloader,
    val store: CatalogStore,
    val keyValue: KeyValueStore,
    val downloadsDir: File,
    val serverUrl: () -> String?,
    val pollMs: Long = 3_000,
    val wallClock: () -> Long = System::currentTimeMillis,
)

/** Gleiche Adresse (Schema, Host, Port) wie [base] — nur dorthin geht der Token. */
fun isSameOrigin(url: String, base: String): Boolean = runCatching {
    val a = URI(url)
    val b = URI(base.trim())
    fun port(u: URI) = if (u.port >= 0) u.port else if (u.scheme == "https") 443 else 80
    a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b) &&
        (a.scheme == "https" || a.scheme == "http")
}.getOrDefault(false)

// ---------------------------------------------------------------------------
// Ablauf
// ---------------------------------------------------------------------------

/**
 * # Bau auf dem eigenen Server (docs/indoor/serverbau.md)
 *
 * * [open]: fragt `GET /api/worlds/info` (ohne Streckendaten), sobald fuer
 *   eine Tour der Schritt „Welt laden" offen ist und ein Server eingerichtet
 *   ist. Liegt ein gemerkter Auftrag vor (App neu gestartet), geht es dort
 *   weiter.
 * * [start] (Nutzeraktion): schickt die aufbereitete Strecke, merkt den
 *   Auftrag im [KeyValueStore], fragt alle [IndoorServerDeps.pollMs] den
 *   Stand ab, laedt das fertige Paket (Range, SHA-256) und installiert es
 *   ueber den Installationsweg der Katalogwelten ([CatalogStore.installPackage]).
 *   Danach wird der Auftrag auf dem Server geloescht (die Strecke bleibt dort
 *   nicht laenger liegen als noetig).
 * * [cancel]: loescht den Auftrag auf dem Server und den angefangenen Download.
 * * [close]: Schritt verlassen — der Server baut weiter, die App fragt beim
 *   naechsten [open] wieder nach.
 *
 * @param onInstalled auf dem Hauptthread: Tour, Verzeichnis und Manifest der neuen Welt.
 */
class IndoorServerBuildController(
    private val deps: IndoorServerDeps?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val onInstalled: (rideId: String, dir: File, manifest: WorldManifest, laserSource: String?, replaced: Boolean) -> Unit,
) {
    private val _ui = MutableStateFlow<IndoorServerUi>(IndoorServerUi.Hidden)
    val ui: StateFlow<IndoorServerUi> = _ui.asStateFlow()

    private var job: Job? = null
    private var rideId: String? = null
    private var laser: IndoorServerLaserUi? = null
    private var replacesDevice = false

    @Volatile private var cancelRequested = false

    /** Laeuft gerade ein Auftrag (dann kein Bau auf dem Geraet daneben). */
    val busy: Boolean get() = _ui.value is IndoorServerUi.Working

    private fun key(rideId: String) = KEY_PREFIX + rideId

    /**
     * Schritt „Welt laden" fuer die Tour [rideId] ist offen. [track] sind ihre
     * Punkte (Breite, Laenge) fuer die Laser-Vorschau; [hasDeviceWorld] = es
     * liegt schon eine auf dem Geraet gebaute Welt vor.
     */
    fun open(rideId: String, track: List<Pair<Double, Double>>, hasDeviceWorld: Boolean) {
        val d = deps ?: return
        close()
        cancelRequested = false
        this.rideId = rideId
        this.replacesDevice = hasDeviceWorld
        _ui.value = IndoorServerUi.Hidden
        job = scope.launch {
            val laserUi = withContext(io) { IndoorServerLaserUi(laserPreviewOfPoints(track)) }
            laser = laserUi
            val record = withContext(io) { ServerJobRecord.parseOrNull(d.keyValue.getString(key(rideId))) }
            val client = withContext(io) { d.client() } ?: return@launch
            if (record != null) {
                if (record.serverUrl.trimEnd('/') == d.serverUrl()?.trimEnd('/')) {
                    follow(d, client, record)
                    return@launch
                }
                // Anderer Server eingerichtet: der alte Auftrag ist fuer diese App verloren.
                withContext(io) { d.keyValue.remove(key(rideId)) }
            }
            _ui.value = offerOrHidden(withContext(io) { client.info() })
        }
    }

    private fun offerOrHidden(info: ServerCall<de.trailscape.core.indoor.server.ServerWorldInfo>): IndoorServerUi = when (info) {
        is ServerCall.Ok -> IndoorServerUi.Offer(laser ?: IndoorServerLaserUi(LaserExpectation.NONE, null, 0), info.value.queueLength, replacesDevice)
        is ServerCall.Incompatible -> IndoorServerUi.Incompatible(compareBuilder(info.serverBuilderId), info.serverBuilderId)
        // Kein Bau-Dienst, kein Netz, Token falsch: die zweite Wahl erscheint nicht.
        else -> IndoorServerUi.Hidden
    }

    /** Nutzeraktion „Auf dem Server in Laserqualitaet bauen" mit der aufbereiteten Strecke. */
    fun start(rideId: String, routeName: String, route: IndoorRoute, quality: IndoorQuality, worldId: String) {
        val d = deps ?: return
        job?.cancel()
        cancelRequested = false
        this.rideId = rideId
        _ui.value = IndoorServerUi.Working(IndoorServerStep.SENDING, laser)
        job = scope.launch {
            val client = withContext(io) { d.client() }
            if (client == null) {
                _ui.value = IndoorServerUi.Hidden
                return@launch
            }
            val request = ServerWorldRequest(worldBuilderVersion, quality, laser = true, route = route, expectedWorldId = worldId)
            when (val r = withContext(io) { client.submit(request) }) {
                is ServerCall.Ok -> {
                    val record = ServerJobRecord(r.value.id, rideId, worldId, routeName, d.serverUrl().orEmpty(), d.wallClock())
                    withContext(io) { d.keyValue.setString(key(rideId), record.toJson()) }
                    follow(d, client, record, first = r.value)
                }
                is ServerCall.Incompatible -> _ui.value = IndoorServerUi.Incompatible(compareBuilder(r.serverBuilderId), r.serverBuilderId)
                else -> _ui.value = failureOf(r, canResume = false)
            }
        }
    }

    /** „Erneut versuchen" nach [IndoorServerUi.Failed] mit `canResume`. */
    fun resume() {
        val d = deps ?: return
        val id = rideId ?: return
        job?.cancel()
        cancelRequested = false
        job = scope.launch {
            val record = withContext(io) { ServerJobRecord.parseOrNull(d.keyValue.getString(key(id))) } ?: run {
                _ui.value = offerOrHidden(withContext(io) { d.client()?.info() } ?: ServerCall.NoService)
                return@launch
            }
            val client = withContext(io) { d.client() } ?: return@launch
            follow(d, client, record)
        }
    }

    /** Folgt einem Auftrag bis zur installierten Welt (Stand abfragen, laden, installieren). */
    private suspend fun follow(d: IndoorServerDeps, client: WorldServerClient, record: ServerJobRecord, first: ServerJobStatus? = null) {
        var status: ServerJobStatus? = first
        while (true) {
            if (cancelRequested) return
            val s = status ?: when (val r = withContext(io) { client.status(record.jobId) }) {
                is ServerCall.Ok -> r.value
                ServerCall.NotFound -> {
                    withContext(io) { forget(d, record) }
                    _ui.value = IndoorServerUi.Failed(IndoorServerFailure.GONE)
                    return
                }
                is ServerCall.Unreachable -> {
                    _ui.value = IndoorServerUi.Failed(IndoorServerFailure.OFFLINE, r.detail, canResume = true)
                    return
                }
                else -> {
                    _ui.value = failureOf(r, canResume = true)
                    return
                }
            }
            status = null
            when (s.state) {
                ServerJobState.QUEUED -> _ui.value = IndoorServerUi.Working(IndoorServerStep.QUEUED, laser, queuePosition = s.queuePosition)
                ServerJobState.RUNNING -> _ui.value = IndoorServerUi.Working(IndoorServerStep.BUILDING, laser, fraction = s.fraction.toFloat())
                ServerJobState.DONE -> {
                    download(d, client, record, s)
                    return
                }
                ServerJobState.FAILED, ServerJobState.CANCELLED -> {
                    withContext(io) { forget(d, record) }
                    _ui.value = IndoorServerUi.Failed(IndoorServerFailure.BUILD_FAILED, listOfNotNull(s.errorCode, s.errorDetail).joinToString(": "))
                    return
                }
            }
            delay(d.pollMs)
        }
    }

    private suspend fun download(d: IndoorServerDeps, client: WorldServerClient, record: ServerJobRecord, s: ServerJobStatus) {
        val sha = s.sha256
        val worldId = s.worldId
        if (sha == null || worldId == null || worldId != record.worldId) {
            withContext(io) { forget(d, record) }
            _ui.value = IndoorServerUi.Failed(IndoorServerFailure.OTHER, "Welt ${s.worldId} statt ${record.worldId}")
            return
        }
        val part = File(d.downloadsDir, "${record.jobId}.part")
        _ui.value = IndoorServerUi.Working(IndoorServerStep.DOWNLOADING, laser, totalBytes = s.packageBytes, downloadedBytes = if (part.isFile) part.length() else 0)
        val server = record.serverUrl
        var lastPermille = -1
        val result = withContext(io) {
            d.downloader(server).download(
                url = client.packageUrl(record.jobId),
                part = part,
                expectedBytes = s.packageBytes,
                expectedSha256 = sha,
                onProgress = { done, total ->
                    val permille = if (total > 0) (done * 1000 / total).toInt() else 0
                    if (permille != lastPermille) {
                        lastPermille = permille
                        _ui.value = IndoorServerUi.Working(IndoorServerStep.DOWNLOADING, laser, downloadedBytes = done, totalBytes = total, cancelling = cancelRequested)
                    }
                },
                isCancelled = { cancelRequested },
                headers = client.authHeaders(),
            )
        }
        when (result) {
            is CatalogFetchResult.Done -> install(d, client, record, s, result.file ?: part)
            CatalogFetchResult.Cancelled -> Unit
            CatalogFetchResult.ChecksumMismatch -> _ui.value = IndoorServerUi.Failed(IndoorServerFailure.CHECKSUM, canResume = true)
            CatalogFetchResult.NotAvailable -> {
                withContext(io) { forget(d, record) }
                _ui.value = IndoorServerUi.Failed(IndoorServerFailure.GONE)
            }
            is CatalogFetchResult.Failed -> _ui.value = IndoorServerUi.Failed(
                if (result.offline) IndoorServerFailure.OFFLINE else IndoorServerFailure.OTHER,
                result.detail,
                canResume = true,
            )
        }
    }

    private suspend fun install(d: IndoorServerDeps, client: WorldServerClient, record: ServerJobRecord, s: ServerJobStatus, zip: File) {
        _ui.value = IndoorServerUi.Working(IndoorServerStep.INSTALLING, laser, downloadedBytes = s.packageBytes, totalBytes = s.packageBytes)
        val outcome = withContext(io) {
            val replaced = runCatching { d.store.installedWorldIsDeviceBuilt(record.worldId) }.getOrDefault(false)
            try {
                val (dir, manifest) = d.store.installPackage(record.worldId, 1, record.worldId, zip) { staging ->
                    WorldLibrary.writeSource(staging, record.rideId, record.routeName)
                    WorldLibrary.writeServerMarker(staging, s.laserSource, d.wallClock())
                }
                forget(d, record)
                // Paket und Strecke auf dem Server nicht laenger aufheben als noetig.
                runCatching { client.delete(record.jobId) }
                Triple(dir, manifest, replaced)
            } catch (e: CatalogPackageException) {
                zip.delete()
                IndoorServerUi.Failed(
                    if (e.reason == CatalogPackageException.Reason.IO) IndoorServerFailure.NO_SPACE else IndoorServerFailure.CHECKSUM,
                    e.toString(),
                    canResume = true,
                )
            } catch (e: IOException) {
                IndoorServerUi.Failed(IndoorServerFailure.NO_SPACE, e.toString(), canResume = true)
            } finally {
                d.store.cleanupStaging()
            }
        }
        if (outcome is IndoorServerUi.Failed) {
            _ui.value = outcome
            return
        }
        val done = outcome as? Triple<*, *, *> ?: return
        val dir = done.first as File
        val manifest = done.second as WorldManifest
        val replaced = done.third as Boolean
        _ui.value = IndoorServerUi.Hidden
        onInstalled(record.rideId, dir, manifest, s.laserSource, replaced)
    }

    private fun forget(d: IndoorServerDeps, record: ServerJobRecord) {
        d.keyValue.remove(key(record.rideId))
        File(d.downloadsDir, "${record.jobId}.part").delete()
    }

    /** Die Strecke wird fuer den Auftrag aufbereitet (vor [start]). */
    fun showSending() {
        if (deps == null) return
        job?.cancel()
        cancelRequested = false
        _ui.value = IndoorServerUi.Working(IndoorServerStep.SENDING, laser)
    }

    /** Aufbereiten scheiterte vor dem Senden. */
    fun showFailure(detail: String?) {
        _ui.value = IndoorServerUi.Failed(IndoorServerFailure.OTHER, detail)
    }

    /** Nutzeraktion „Abbrechen": Auftrag auf dem Server loeschen, Download verwerfen. */
    fun cancel() {
        val d = deps ?: return
        val id = rideId ?: return
        cancelRequested = true
        (_ui.value as? IndoorServerUi.Working)?.let { _ui.value = it.copy(cancelling = true) }
        val running = job
        job = scope.launch {
            running?.cancel()
            running?.join()
            withContext(io) {
                val record = ServerJobRecord.parseOrNull(d.keyValue.getString(key(id)))
                if (record != null) {
                    runCatching { d.client()?.delete(record.jobId) }
                    forget(d, record)
                }
            }
            cancelRequested = false
            _ui.value = offerOrHidden(withContext(io) { d.client()?.info() } ?: ServerCall.NoService)
        }
    }

    /** Schritt verlassen: nur das Abfragen endet; der Auftrag bleibt gemerkt. */
    fun close() {
        cancelRequested = true
        job?.cancel()
        job = null
        _ui.value = IndoorServerUi.Hidden
    }

    private fun failureOf(r: ServerCall<*>, canResume: Boolean): IndoorServerUi.Failed = when (r) {
        ServerCall.Unauthorized -> IndoorServerUi.Failed(IndoorServerFailure.UNAUTHORIZED)
        is ServerCall.Unreachable -> IndoorServerUi.Failed(IndoorServerFailure.OFFLINE, r.detail, canResume)
        is ServerCall.Rejected -> IndoorServerUi.Failed(
            when (r.code) {
                "TOO_LONG", "TOO_MANY_POINTS", "TOO_LARGE" -> IndoorServerFailure.TOO_LONG
                "QUEUE_FULL" -> IndoorServerFailure.QUEUE_FULL
                else -> IndoorServerFailure.OTHER
            },
            listOfNotNull(r.code, r.detail).joinToString(": "),
        )
        is ServerCall.Failed -> IndoorServerUi.Failed(IndoorServerFailure.OTHER, "HTTP ${r.status}${r.detail?.let { ": $it" } ?: ""}", canResume)
        else -> IndoorServerUi.Failed(IndoorServerFailure.OTHER, r.toString(), canResume)
    }

    companion object {
        /** Vorsilbe des gemerkten Auftrags je Tour im [KeyValueStore]. */
        const val KEY_PREFIX = "trailscape.indoor.server_job."
    }
}

/** Liegt fuer [worldId] eine fertige Welt vor, die nicht vom Server (und nicht aus dem Katalog) stammt? */
private fun CatalogStore.installedWorldIsDeviceBuilt(worldId: String): Boolean = worldLibrary().get(worldId)?.let {
    it.complete && it.server == null && it.catalog == null
} ?: false
