package de.trailscape.app.ui.map

import android.content.Context
import de.trailscape.app.data.AppServices
import de.trailscape.app.ui.MapStyle
import de.trailscape.app.ui.formatOneDecimalDe
import de.trailscape.core.HttpMethod
import de.trailscape.core.HttpRequest
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
import org.maplibre.android.MapLibre
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
 * ## Welcher Stil ueberhaupt geladen wird
 * Nur einer: der Vektor-Stil von OpenFreeMap ([MapStyle.offlineAllowed],
 * Begruendung samt Quellen an `mapStyles` in `ui/MapStyles.kt`). Die
 * Rasterserver verbieten Vorab-Downloads; [downloadOfflineRegion] prueft das
 * deshalb selbst noch einmal, statt sich auf die Planung zu verlassen.
 *
 * ## Warum der Vektor-Stil festgeschrieben wird
 * Die Region fuehrt nicht die echte Style-URL von OpenFreeMap, sondern eine
 * Kopie des Stils, deren Vektorquelle auf die Kachelpfade des Datenstands
 * beim ersten Download festgelegt ist ([pinStyleSources], dort die
 * ausfuehrliche Begruendung: Die TileJSON dahinter wechselt woechentlich die
 * Version, und eine offline gespeicherte Region wuerde danach still leer).
 * Diese Kopie liegt an zwei Stellen:
 *  * in MapLibres Datenbank unter [offlineStyleUrl] — nur so findet der
 *    Download den Stil (derselbe Weg wie bei den Rasterstilen unten);
 *  * als Datei im app-eigenen Speicher ([PINNED_STYLE_DIR_NAME]) — daraus
 *    zeichnet die Karte den Stil, solange es eine solche Region gibt
 *    ([pinnedOfflineStyleJson]). Aus der Datenbank laesst sich eine Ressource
 *    nicht zuruecklesen.
 *
 * Alle Regionen teilen sich **eine** Kopie: Solange noch eine Region des
 * Stils existiert, nimmt ein neuer Download die vorhandene Kopie wieder,
 * statt einen neueren Datenstand festzuschreiben. Sonst laegen zwei Regionen
 * mit verschiedenen Kachelpfaden auf dem Geraet, und offline koennte die Karte
 * immer nur eine davon zeigen. Die Daten eines spaeteren Downloads sind
 * trotzdem aktuell, sobald OpenFreeMap die alte Version abgeraeumt hat (dann
 * liefert der Server unter dem alten Pfad den neuesten Stand, siehe
 * [pinStyleSources]). Erst wenn die letzte Region geloescht ist, schreibt der
 * naechste Download einen frischen Stil fest.
 *
 * ## Wie ein Rasterstil zur Region kam — und warum das mal haengen blieb
 * (Stand der alten Raster-Downloads; die Regionen liegen noch auf manchem
 * Geraet und werden weiter angezeigt.)
 * [OfflineTilePyramidRegionDefinition] verlangt eine Style-**URL**; unsere
 * Rasterstile entstehen aber zur Laufzeit als JSON ([MapStyle.toRasterStyleJson]).
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
    /**
     * Alle geladenen Ressourcen, nicht nur Kacheln. Beim Vektor-Stil kommen
     * zu den Kacheln Style, TileJSON, Sprites und einige Hundert kleine
     * Schrift-Pakete — der Fortschrittsbalken rechnet deshalb in Ressourcen,
     * sonst stuende er bei „fertig" erst bei einem Bruchteil.
     */
    val completedResources: Long = completedTiles,
    /** Vom Kern erwartete Ressourcen; `0`, solange die Zahl nicht genau ist. */
    val requiredResources: Long = requiredTiles,
)

/** Verzeichnis der frueheren `file://`-Stildateien; wird nur noch aufgeraeumt. */
private const val LEGACY_STYLE_DIR_NAME = "map-styles"

/**
 * Verzeichnis der festgeschriebenen Vektor-Stile (siehe Datei-KDoc). Bewusst
 * ein anderer Name als [LEGACY_STYLE_DIR_NAME], das jeder Download aufraeumt.
 */
private const val PINNED_STYLE_DIR_NAME = "offline-styles"

private fun pinnedStyleFile(context: Context, style: MapStyle): File =
    File(File(context.applicationContext.filesDir, PINNED_STYLE_DIR_NAME), "${style.id}.json")

/**
 * Alle Offline-Regionen, die MapLibre kennt. `MapLibre.getInstance` ist
 * idempotent und steht hier, weil der Aufruf auch ohne sichtbare Karte kommen
 * kann (Mehr-Tab). Muss auf dem Main-Thread laufen (Rueckruf ueber den
 * Main-Looper).
 */
internal suspend fun listOfflineRegions(context: Context): List<OfflineRegion> {
    val appContext = context.applicationContext
    MapLibre.getInstance(appContext)
    val manager = OfflineManager.getInstance(appContext)
    return suspendCancellableCoroutine { cont ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                if (cont.isActive) cont.resume(offlineRegions?.toList() ?: emptyList())
            }

            override fun onError(error: String) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(error))
            }
        })
    }
}

/** Ob es mindestens eine Region gibt, die [style] ueber [offlineStyleUrl] fuehrt. */
private suspend fun hasRegionFor(context: Context, style: MapStyle): Boolean {
    val url = offlineStyleUrl(style)
    return listOfflineRegions(context).any { region ->
        (region.definition as? OfflineTilePyramidRegionDefinition)?.styleURL == url
    }
}

/**
 * Die festgeschriebene Style-JSON, aus der die Karte einen Vektor-Stil
 * zeichnen soll — oder `null`, dann gilt die echte Style-URL.
 *
 * Gesetzt nur, solange es eine Region des Stils gibt: Ohne gespeicherte
 * Region braucht niemand den festen Datenstand, und die Live-URL bringt
 * Stil-Aenderungen von OpenFreeMap mit. Fehler (Datenbank, Datei) ergeben
 * `null` — die Karte laedt dann eben live, statt gar nicht.
 */
internal suspend fun pinnedOfflineStyleJson(context: Context, style: MapStyle): String? {
    if (!style.isVector) return null
    return runCatching {
        if (!hasRegionFor(context, style)) return null
        withContext(Dispatchers.IO) {
            pinnedStyleFile(context, style).takeIf { it.isFile }?.readText()
        }
    }.getOrNull()
}

/**
 * Liefert die festgeschriebene Style-JSON fuer einen neuen Download (siehe
 * Datei-KDoc): die vorhandene, solange eine Region sie nutzt, sonst eine
 * frisch von OpenFreeMap geholte und mit [pinStyleSources] festgelegte.
 */
private suspend fun preparePinnedVectorStyle(context: Context, style: MapStyle): String {
    val file = pinnedStyleFile(context, style)
    if (runCatching { hasRegionFor(context, style) }.getOrDefault(false)) {
        withContext(Dispatchers.IO) { runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() }
            ?.let { return it }
    }
    val styleUrl = checkNotNull(style.vectorStyleUrl)
    return withContext(Dispatchers.IO) {
        val pinned = runCatching {
            val styleJson = fetchText(styleUrl)
            val tileJsons = tileJsonSources(styleJson).mapValues { (_, url) -> fetchText(url) }
            pinStyleSources(styleJson, tileJsons)
        }.getOrElse {
            throw IllegalStateException(
                "Der Kartenstil ließ sich nicht laden. Bitte Internetverbindung prüfen.",
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(pinned)
        pinned
    }
}

/** Ein schlichter GET ueber den App-weiten HTTP-Client; wirft bei Fehlstatus. */
private fun fetchText(url: String): String {
    val response = AppServices.httpClient.execute(HttpRequest(HttpMethod.GET, url))
    check(response.statusCode in 200..299) { "HTTP ${response.statusCode} fuer $url" }
    return response.body
}

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
    // Zweite Sperre neben `planOfflineDownload`: Wer diese Funktion je an der
    // Planung vorbei aufruft, soll trotzdem keinen Rasterserver abgrasen.
    check(style.offlineAllowed) { offlineNotAllowedMessage(style) }
    val appContext = context.applicationContext
    val manager = OfflineManager.getInstance(appContext)
    val styleUrl = offlineStyleUrl(style)

    // Aus der Zeit der `file://`-Adressen koennen noch Stildateien im
    // app-privaten Speicher liegen; die braucht niemand mehr.
    withContext(Dispatchers.IO) {
        runCatching { File(appContext.filesDir, LEGACY_STYLE_DIR_NAME).deleteRecursively() }
    }

    // Der Stil muss vorab unter seiner Wunschadresse liegen (siehe
    // Datei-KDoc) — beim Rasterstil die zur Laufzeit gebaute JSON, beim
    // Vektor-Stil die festgeschriebene Kopie.
    val styleJson = if (style.isVector) {
        preparePinnedVectorStyle(appContext, style)
    } else {
        style.toRasterStyleJson()
    }
    val nowS = System.currentTimeMillis() / 1000
    runCatching {
        manager.putResourceWithUrl(
            styleUrl,
            styleJson.toByteArray(Charsets.UTF_8),
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
    val metadata = offlineRegionMetadata(
        name = name,
        styleId = style.id,
        createdAtMs = System.currentTimeMillis(),
        tileTemplate = if (style.isVector) pinnedTileTemplate(styleJson) else null,
    )
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
                                    completedResources = status.completedResourceCount,
                                    requiredResources = if (status.isRequiredResourceCountPrecise) {
                                        status.requiredResourceCount
                                    } else {
                                        0L
                                    },
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

    private val _savedRegions = MutableStateFlow(0)

    /**
     * Zaehlt die in diesem Prozess fertig gespeicherten Regionen. Die Karte
     * haengt ihren Stil daran: Nach dem ersten Download eines Vektor-Stils
     * soll sie sofort aus der festgeschriebenen Kopie zeichnen (siehe
     * [pinnedOfflineStyleJson]), nicht erst nach dem naechsten Start.
     */
    val savedRegions: StateFlow<Int> = _savedRegions.asStateFlow()

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
                    // Gezaehlt wird in Ressourcen (siehe
                    // [OfflineDownloadProgress.completedResources]). Die
                    // Schaetzung bleibt stehen, bis MapLibre die Zahl wirklich
                    // kennt — sonst spraenge der Balken auf die „1" des Stils
                    // zurueck.
                    val total = if (progress.requiredResources > 1) {
                        progress.requiredResources
                    } else {
                        _state.value.totalTiles
                    }
                    _state.value = OfflineDownloadState(
                        running = true,
                        // Solange der Nenner noch die Kachelschaetzung ist,
                        // zaehlen oben schon Style, Sprites und Schriften mit —
                        // ohne Deckel stuende kurz „300/180" da.
                        completedTiles = progress.completedResources.coerceAtMost(total),
                        totalTiles = total,
                    )
                }
                _savedRegions.value += 1
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
