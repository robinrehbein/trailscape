package de.trailscape.app.ui.map

import android.content.Context
import de.trailscape.app.data.AppServices
import de.trailscape.app.ui.MapStyle
import de.trailscape.app.ui.formatOneDecimalDe
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Offline-Kartenausschnitte ueber den MapLibre-[OfflineManager].
 *
 * ## Zustaendigkeit
 * Diese Datei stellt **nur den Download** bereit — der Karten-Screen bietet
 * ihn als „Kartenausschnitt herunterladen" an. Die *Verwaltung* (Liste,
 * Loeschen, Groesse) baut der Mehr-Screen; er darf diese Datei benutzen
 * (siehe [readOfflineRegionInfo]), muss sie aber nicht: `OfflineManager` ist
 * ein prozessweites Singleton, jede eigene Verwaltung findet dieselben
 * Regionen. Die reine Rechnung (Kachelzahl, Grenzen, Style-Adresse,
 * Metadaten) steht nebenan in `OfflineTileMath.kt` (siehe
 * [planOfflineDownload]) und ist dort als JVM-Test geprueft.
 *
 * ## Wie der Rasterstil zur Region kommt — und warum das mal haengen blieb
 * [OfflineTilePyramidRegionDefinition] verlangt eine Style-**URL**; unsere
 * Stile entstehen aber zur Laufzeit als JSON ([MapStyle.toRasterStyleJson]).
 * Der erste Anlauf legte die JSON als Datei ab und uebergab eine
 * `file://`-Adresse. Das kann nicht funktionieren, und zwar still:
 *
 *  * Der Download laeuft im Kern ueber die `DatabaseFileSource`, und die kennt
 *    als Nachschub ausschliesslich die **Netz**-Quelle
 *    (`FileSourceManager::getFileSource(FileSourceType::Network, …)`,
 *    `platform/default/src/mbgl/storage/database_file_source.cpp`). Die
 *    `file://`/`asset://`-Aufloesung des `MainResourceLoader`, die beim
 *    *Anzeigen* der Karte greift, ist an dieser Stelle gar nicht beteiligt.
 *  * Die Android-Netzquelle reicht alles ausser `local://` an OkHttp weiter.
 *    Dort scheitert `HttpUrl.parse("file://…")` — und
 *    `HttpRequestImpl.executeRequest` **kehrt ohne jeden Rueckruf zurueck**
 *    (nur eine Logzeile „Unable to parse resourceUrl"). Kein Ergebnis, kein
 *    Fehler, kein Wiederholungsversuch.
 *
 * Der Kern wartete also ewig auf die eine Style-Ressource: `requiredResource
 * Count = 1`, `completedResourceCount = 0` — die Anzeige „Lade Kacheln … 0/1",
 * die nie weiterlief.
 *
 * Jetzt wird die JSON **vorher** unter ihrer Wunschadresse
 * ([offlineStyleUrl]) in MapLibres eigenen Ressourcen-Cache gelegt
 * ([OfflineManager.putResourceWithUrl] — genau dafuer gedacht). Der Download
 * schaut fuer jede Ressource zuerst in dieser Datenbank nach
 * (`OfflineDownload::ensureResource` → `OfflineDatabase::getRegionResource`,
 * Schluessel ist schlicht die URL) und findet den Stil dort, ohne je ins Netz
 * zu gehen. Beide Aufrufe laufen ueber denselben Aktor-Thread der
 * `DatabaseFileSource`, die Reihenfolge „erst ablegen, dann Region anlegen"
 * ist damit eingehalten.
 *
 * ## Unterschied zum Flutter-Original
 * `lib/tile_cache.dart` lud die Kacheln mit einem eigenen HTTP-Client in ein
 * Dateiverzeichnis und stellte sie ueber einen eigenen `TileProvider` wieder
 * zu. Nativ uebernimmt das der MapLibre-Kern: `OfflineManager` schreibt in
 * dieselbe SQLite-Datenbank, aus der auch die laufende Karte liest — die
 * heruntergeladenen Kacheln erscheinen also ohne weiteres Zutun offline.
 * Ein eigener Kachel-Download *in diese Datenbank* waere kein Ersatz: Kacheln
 * liegen dort in einer eigenen Tabelle mit dem Schluessel
 * (Vorlage, x, y, z) — [OfflineManager.putResourceWithUrl] schreibt aber in
 * die URL-Tabelle (`Resource::Kind::Unknown`), wo die Kartenanzeige
 * (`Resource::Kind::Tile`) nie nachsieht. Fuer den *Stil* passt der Weg, fuer
 * *Kacheln* nicht.
 */

/** Fortschritt eines laufenden Downloads. */
data class OfflineDownloadProgress(
    val completedTiles: Long,
    /**
     * Vom Kern erwartete Kachelzahl — **nur** gesetzt, wenn MapLibre sie
     * bereits genau kennt (`isRequiredResourceCountPrecise`). Vorher meldet
     * der Kern `1` (der Stil selbst), und genau diese `1` machte aus dem
     * Fortschrittsbalken die beruehmte Anzeige „0/1".
     */
    val requiredTiles: Long,
    val completedBytes: Long,
)

/** Verzeichnis der frueheren `file://`-Stildateien; wird nur noch aufgeraeumt. */
private const val LEGACY_STYLE_DIR_NAME = "map-styles"

/**
 * Wie lange der abgelegte Stil im Ressourcen-Cache als frisch gilt. Grosszuegig,
 * weil ihn nur der Download liest — und der prueft das Ablaufdatum ohnehin
 * nicht.
 */
private const val STYLE_CACHE_TTL_S = 365L * 24 * 60 * 60

/**
 * Bruecke von MapLibres [LatLngBounds] in die reine Rechnung: entscheidet, ob
 * und wie der sichtbare Ausschnitt geladen wird (siehe [planOfflineDownload]).
 */
fun planOfflineDownload(
    bounds: LatLngBounds,
    cameraZoom: Double,
    style: MapStyle,
): OfflineDownloadPlan = planOfflineDownload(
    north = bounds.latitudeNorth,
    south = bounds.latitudeSouth,
    east = bounds.longitudeEast,
    west = bounds.longitudeWest,
    cameraZoom = cameraZoom,
    style = style,
)

// ----------------------------------------------------------------- Download

/**
 * Laedt den sichtbaren Ausschnitt fuer [style] herunter.
 *
 * Muss aus dem Main-Thread heraus aufgerufen werden: Der [OfflineManager]
 * liefert seine Rueckmeldungen ueber den Main-Looper, und die Aufsicht gegen
 * haengende Downloads laeuft im selben (Einzel-)Thread — deshalb braucht der
 * gemeinsame Zustand hier keine Synchronisierung. Die Funktion suspendiert,
 * bis der Download fertig ist, und meldet zwischendurch ueber [onProgress].
 * Bricht die aufrufende Coroutine ab (Screen verlassen), wird der Download
 * gestoppt und die halbfertige Region wieder geloescht.
 *
 * [minZoom]/[maxZoom] sind **Kamerazoomstufen** der Regionsdefinition; welche
 * Kachelstufen daraus werden, steht in [offlineTileZoomRange].
 *
 * @throws IllegalStateException mit einer fuer die UI geeigneten Meldung.
 */
suspend fun downloadOfflineRegion(
    context: Context,
    style: MapStyle,
    bounds: LatLngBounds,
    minZoom: Int,
    maxZoom: Int,
    name: String,
    onProgress: (OfflineDownloadProgress) -> Unit,
): OfflineDownloadProgress = coroutineScope {
    val appContext = context.applicationContext
    val manager = OfflineManager.getInstance(appContext)
    val styleUrl = offlineStyleUrl(style)

    // Aus der Zeit der `file://`-Adressen koennen noch Stildateien im
    // app-privaten Speicher liegen; die braucht niemand mehr.
    withContext(Dispatchers.IO) {
        runCatching { File(appContext.filesDir, LEGACY_STYLE_DIR_NAME).deleteRecursively() }
    }

    val nowS = System.currentTimeMillis() / 1000
    runCatching {
        manager.putResourceWithUrl(
            styleUrl,
            style.toRasterStyleJson().toByteArray(Charsets.UTF_8),
            nowS,
            nowS + STYLE_CACHE_TTL_S,
            "",
            false,
        )
    }.getOrElse {
        throw IllegalStateException("Der Kartenstil konnte nicht abgelegt werden.")
    }

    val definition = OfflineTilePyramidRegionDefinition(
        styleUrl,
        bounds,
        minZoom.toDouble(),
        maxZoom.toDouble(),
        appContext.resources.displayMetrics.density,
    )
    val metadata = offlineRegionMetadata(name, style.id, System.currentTimeMillis())
    val watchdogScope = this

    suspendCancellableCoroutine { continuation ->
        var region: OfflineRegion? = null
        var settled = false
        var watchdog: Job? = null
        var lastProgressAt = System.currentTimeMillis()
        var lastCompletedResources = -1L
        var lastError: String? = null

        fun finish(action: () -> Unit) {
            if (settled) return
            settled = true
            watchdog?.cancel()
            region?.setObserver(null)
            region?.setDownloadState(OfflineRegion.STATE_INACTIVE)
            action()
        }

        /** Abbruch mit Meldung; die halbfertige Region ist wertlos und fliegt raus. */
        fun fail(message: String) {
            val halfDone = region
            finish {
                halfDone?.delete(NoopDeleteCallback)
                continuation.resumeWithException(IllegalStateException(message))
            }
        }

        // Zuerst die Aufsicht, dann der Auftrag: So faellt auch ein
        // `createOfflineRegion` auf, das ueberhaupt nie zurueckruft.
        watchdog = watchdogScope.launch {
            while (true) {
                delay(STALL_CHECK_INTERVAL_MS)
                if (System.currentTimeMillis() - lastProgressAt < STALL_TIMEOUT_MS) continue
                fail(stalledMessage(lastError))
                return@launch
            }
        }

        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    if (!continuation.isActive) {
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        offlineRegion.delete(NoopDeleteCallback)
                        return
                    }
                    region = offlineRegion
                    offlineRegion.setObserver(
                        object : OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status: OfflineRegionStatus) {
                                if (status.completedResourceCount > lastCompletedResources) {
                                    lastCompletedResources = status.completedResourceCount
                                    lastProgressAt = System.currentTimeMillis()
                                }
                                val progress = OfflineDownloadProgress(
                                    completedTiles = status.completedTileCount,
                                    // Der Stil selbst zaehlt als Ressource mit,
                                    // ist aber keine Kachel.
                                    requiredTiles = if (status.isRequiredResourceCountPrecise) {
                                        max(0L, status.requiredResourceCount - 1L)
                                    } else {
                                        0L
                                    },
                                    completedBytes = status.completedResourceSize,
                                )
                                onProgress(progress)
                                if (status.isComplete) {
                                    finish { continuation.resume(progress) }
                                }
                            }

                            /**
                             * Einzelne Fehler beenden den Download **nicht**: Der
                             * Kern ueberspringt fehlende Kacheln (404) von sich aus
                             * und wiederholt Verbindungsfehler mit wachsendem
                             * Abstand. Erst wenn danach gar nichts mehr vorangeht,
                             * greift die Aufsicht — und nimmt die zuletzt gemeldete
                             * Ursache in ihre Meldung auf.
                             */
                            override fun onError(error: OfflineRegionError) {
                                lastError = describeOfflineError(error)
                            }

                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                fail(
                                    "Zu viele Kacheln: MapLibre lädt höchstens $limit Stück. " +
                                        "Zoome näher heran.",
                                )
                            }
                        },
                    )
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    finish {
                        continuation.resumeWithException(
                            IllegalStateException("Region konnte nicht angelegt werden: $error"),
                        )
                    }
                }
            },
        )

        continuation.invokeOnCancellation {
            settled = true
            watchdog.cancel()
            region?.setObserver(null)
            region?.setDownloadState(OfflineRegion.STATE_INACTIVE)
            // Halbfertige Regionen sind wertlos und wuerden nur Platz belegen.
            region?.delete(NoopDeleteCallback)
        }
    }
}

/** Uebersetzt einen [OfflineRegionError] in einen deutschen Halbsatz. */
internal fun describeOfflineError(error: OfflineRegionError): String {
    val reason = when (error.reason) {
        OfflineRegionError.REASON_NOT_FOUND -> "Kachel nicht gefunden"
        OfflineRegionError.REASON_SERVER -> "Serverfehler"
        OfflineRegionError.REASON_CONNECTION -> "keine Verbindung"
        else -> "Fehler"
    }
    val detail = error.message.takeIf { it.isNotBlank() }
    return if (detail == null) reason else "$reason: $detail"
}

private object NoopDeleteCallback : OfflineRegion.OfflineRegionDeleteCallback {
    override fun onDelete() = Unit
    override fun onError(error: String) = Unit
}

// ------------------------------------------------- Download ausserhalb des Screens

/** Fortschritt des laufenden Ausschnitt-Downloads fuer die Oberflaeche. */
data class OfflineDownloadState(
    val running: Boolean = false,
    val completedTiles: Long = 0L,
    val totalTiles: Long = 0L,
)

/**
 * Haelt den laufenden Ausschnitt-Download **ausserhalb** der Komposition.
 *
 * Der Grund: Ein `rememberCoroutineScope()` des Karten-Screens stirbt, sobald
 * der `NavHost` den Screen beim Tab-Wechsel entsorgt. Die Coroutine wird dann
 * abgebrochen, und [downloadOfflineRegion] loescht die halbfertige Region in
 * seinem `invokeOnCancellation` — der Download war umsonst, ohne dass die
 * Nutzerin je etwas davon erfaehrt. Hier laeuft er stattdessen in
 * [AppServices.appScope] (bewusst auf [Dispatchers.Main], weil der
 * `OfflineManager` seine Rueckmeldungen ueber den Main-Looper liefert) und der
 * Fortschritt kommt als [StateFlow] zurueck. Ein Tab-Wechsel unterbricht damit
 * nichts mehr; die Abschlussmeldung geht ueber [onMessage] in den geteilten
 * Meldungskanal des [de.trailscape.app.ui.AppViewModel], den immer der gerade
 * sichtbare Screen als Snackbar zeigt.
 */
object OfflineDownloadController {

    private val _state = MutableStateFlow(OfflineDownloadState())

    /** Fortschritt des laufenden Downloads; `running == false`, wenn keiner laeuft. */
    val state: StateFlow<OfflineDownloadState> = _state.asStateFlow()

    /**
     * Startet einen Download nach dem geprueften [plan], sofern nicht schon
     * einer laeuft (dann passiert nichts). Alle Meldungen — Beginn, Erfolg,
     * Fehler — gehen an [onMessage].
     */
    fun start(
        context: Context,
        style: MapStyle,
        bounds: LatLngBounds,
        plan: OfflineDownloadPlan.Ready,
        name: String,
        onMessage: (String) -> Unit,
    ) {
        if (_state.value.running) return
        _state.value = OfflineDownloadState(
            running = true,
            completedTiles = 0L,
            totalTiles = plan.tileCount.toLong().coerceAtLeast(0L),
        )
        val appContext = context.applicationContext
        // Der Fortschrittsbalken zeigt nur Zahlen; welcher Ausschnitt in
        // welcher Aufloesung entsteht, sagt diese eine Meldung.
        onMessage("Lade Kartenausschnitt: ca. ${plan.tileCount} Kacheln, ${plan.zoomLabel}.")

        AppServices.appScope.launch(Dispatchers.Main) {
            try {
                val result = downloadOfflineRegion(
                    context = appContext,
                    style = style,
                    bounds = bounds,
                    minZoom = plan.minZoom,
                    maxZoom = plan.maxZoom,
                    name = name,
                ) { progress ->
                    _state.value = OfflineDownloadState(
                        running = true,
                        completedTiles = progress.completedTiles,
                        // Die Schaetzung bleibt stehen, bis MapLibre die
                        // Kachelzahl wirklich kennt — sonst spraenge der
                        // Balken auf die „1" des Stils zurueck.
                        totalTiles = if (progress.requiredTiles > 0) {
                            progress.requiredTiles
                        } else {
                            _state.value.totalTiles
                        },
                    )
                }
                onMessage(
                    "Ausschnitt gespeichert: ${result.completedTiles} Kacheln " +
                        "(${formatMegabytes(result.completedBytes)} MB).",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(e.message?.takeIf(String::isNotBlank) ?: "Download fehlgeschlagen.")
            } finally {
                _state.value = OfflineDownloadState()
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String = formatOneDecimalDe(bytes / 1024.0 / 1024.0)
