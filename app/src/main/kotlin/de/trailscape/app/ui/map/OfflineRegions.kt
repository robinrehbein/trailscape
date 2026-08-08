package de.trailscape.app.ui.map

import android.content.Context
import de.trailscape.app.ui.MapStyle
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
 * Regionen.
 *
 * ## Unterschied zum Flutter-Original
 * `lib/tile_cache.dart` lud die Kacheln mit einem eigenen HTTP-Client in ein
 * Dateiverzeichnis und stellte sie ueber einen eigenen `TileProvider` wieder
 * zu. Nativ uebernimmt das der MapLibre-Kern: `OfflineManager` schreibt in
 * dieselbe SQLite-Datenbank, aus der auch die laufende Karte liest — die
 * heruntergeladenen Kacheln erscheinen also ohne weiteres Zutun offline.
 * Uebernommen sind die *Grenzen* des Originals: dieselbe Zoom-Spanne
 * (`z … z+2`, gedeckelt) und dieselbe Obergrenze von
 * [MAX_TILES_PER_DOWNLOAD] Kacheln pro Download.
 */

/** Wie in `lib/tile_cache.dart`: mehr als so viele Kacheln laedt die App nicht am Stueck. */
const val MAX_TILES_PER_DOWNLOAD: Int = 250

/** Obergrenze der Zoomstufe eines Downloads (Original: `math.min(minZoom + 2, 17)`). */
const val MAX_OFFLINE_ZOOM: Int = 17

/** Wie viele Zoomstufen ueber der aktuellen mitgeladen werden. */
const val OFFLINE_ZOOM_SPAN: Int = 2

/** Grenzwert der Mercator-Projektion (wie `_maxLatitude` in Dart). */
private const val MAX_LATITUDE = 85.05112878

/** Fortschritt eines laufenden Downloads. */
data class OfflineDownloadProgress(
    val completedTiles: Long,
    val requiredResources: Long,
    val completedBytes: Long,
)

/** Beschreibung einer gespeicherten Region (aus den Metadaten). */
data class OfflineRegionInfo(
    val name: String,
    val styleId: String,
    val createdAtMs: Long,
)

// -------------------------------------------------------------------- Mathe

private fun tileCountAtZoom(zoom: Int): Int = 1 shl zoom.coerceIn(0, 30)

private fun lonToTileX(lon: Double, zoom: Int): Int {
    val n = tileCountAtZoom(zoom)
    val x = floor((lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * n).toInt()
    return x.coerceIn(0, n - 1)
}

private fun latToTileY(lat: Double, zoom: Int): Int {
    val n = tileCountAtZoom(zoom)
    val rad = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * (PI / 180)
    val y = floor((1 - ln(tan(rad) + 1 / cos(rad)) / PI) / 2 * n).toInt()
    return y.coerceIn(0, n - 1)
}

/**
 * Zaehlt die Kacheln einer Region ueber alle Zoomstufen — reine Rechnung ohne
 * IO, 1:1 wie `TileCache.estimateTileCount` in Dart. Grundlage der
 * Groessenwarnung, bevor ueberhaupt ein Download beginnt.
 */
fun estimateTileCount(bounds: LatLngBounds, minZoom: Int, maxZoom: Int): Int {
    if (bounds.longitudeEast < bounds.longitudeWest ||
        bounds.latitudeNorth < bounds.latitudeSouth
    ) {
        return 0
    }

    var total = 0
    for (zoom in max(0, minZoom)..maxZoom) {
        val xMin = lonToTileX(bounds.longitudeWest, zoom)
        val xMax = lonToTileX(bounds.longitudeEast, zoom)
        val yMin = latToTileY(bounds.latitudeNorth, zoom)
        val yMax = latToTileY(bounds.latitudeSouth, zoom)
        total += (xMax - xMin + 1) * (yMax - yMin + 1)
    }
    return total
}

/**
 * Die Zoom-Spanne, die zur aktuellen Kamerazoomstufe heruntergeladen wird:
 * `z … z+2`, begrenzt durch [MAX_OFFLINE_ZOOM] und die hoechste vom Anbieter
 * unterstuetzte Stufe.
 */
fun offlineZoomRange(cameraZoom: Double, style: MapStyle): IntRange {
    val minZoom = max(0, Math.round(cameraZoom).toInt())
    val maxZoom = min(minZoom + OFFLINE_ZOOM_SPAN, min(MAX_OFFLINE_ZOOM, style.maxZoom))
    return minZoom..max(minZoom, maxZoom)
}

// ------------------------------------------------------------------- Style

/**
 * Legt die Style-JSON des Rasterstils als Datei ab und liefert ihre
 * `file://`-Adresse.
 *
 * Der Grund: [OfflineTilePyramidRegionDefinition] verlangt eine Style-**URL**
 * (der Kern laedt den Stil selbst, um die Kachelquellen zu kennen) — eine
 * JSON-Zeichenkette wie beim Anzeigen der Karte
 * (`Style.Builder().fromJson(...)`) nimmt es nicht an. Die Datei liegt im
 * app-privaten Speicher und wird bei jedem Aufruf neu geschrieben, damit
 * Aenderungen am Stil-Katalog sofort greifen.
 */
fun mapStyleFileUri(context: Context, style: MapStyle): String {
    val dir = File(context.filesDir, STYLE_DIR_NAME)
    dir.mkdirs()
    val file = File(dir, "${style.id}.json")
    file.writeText(style.toRasterStyleJson(), Charsets.UTF_8)
    return "file://${file.absolutePath}"
}

private const val STYLE_DIR_NAME = "map-styles"

// ---------------------------------------------------------------- Metadaten

/** Baut die Metadaten einer Region (Name, Stil, Zeitpunkt) als UTF-8-JSON. */
fun offlineRegionMetadata(name: String, styleId: String, createdAtMs: Long): ByteArray =
    buildJsonObject {
        put("name", name)
        put("styleId", styleId)
        put("createdAt", createdAtMs)
    }.toString().toByteArray(Charsets.UTF_8)

/**
 * Liest die von [offlineRegionMetadata] geschriebenen Angaben zurueck.
 * Liefert `null`, wenn die Region von einer anderen Stelle angelegt wurde.
 */
fun readOfflineRegionInfo(metadata: ByteArray?): OfflineRegionInfo? {
    val raw = metadata ?: return null
    return runCatching {
        val json = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject
            ?: return null
        OfflineRegionInfo(
            name = json["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            styleId = json["styleId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            createdAtMs = json["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
        )
    }.getOrNull()
}

// ----------------------------------------------------------------- Download

/**
 * Laedt den sichtbaren Ausschnitt fuer [style] herunter.
 *
 * Muss aus dem Main-Thread heraus aufgerufen werden: Der [OfflineManager]
 * liefert seine Rueckmeldungen ueber den Main-Looper. Die Funktion
 * suspendiert, bis der Download fertig ist, und meldet zwischendurch ueber
 * [onProgress]. Bricht die aufrufende Coroutine ab (Screen verlassen), wird
 * der Download gestoppt und die halbfertige Region wieder geloescht.
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
): OfflineDownloadProgress {
    val appContext = context.applicationContext
    val styleUri = runCatching { mapStyleFileUri(appContext, style) }.getOrElse {
        throw IllegalStateException("Der Kartenstil konnte nicht abgelegt werden.")
    }

    val definition = OfflineTilePyramidRegionDefinition(
        styleUri,
        bounds,
        minZoom.toDouble(),
        maxZoom.toDouble(),
        appContext.resources.displayMetrics.density,
    )
    val metadata = offlineRegionMetadata(name, style.id, System.currentTimeMillis())

    return suspendCancellableCoroutine { continuation ->
        val manager = OfflineManager.getInstance(appContext)
        var region: OfflineRegion? = null
        var settled = false

        fun finish(action: () -> Unit) {
            if (settled) return
            settled = true
            region?.setObserver(null)
            region?.setDownloadState(OfflineRegion.STATE_INACTIVE)
            action()
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
                                val progress = OfflineDownloadProgress(
                                    completedTiles = status.completedTileCount,
                                    requiredResources = status.requiredResourceCount,
                                    completedBytes = status.completedResourceSize,
                                )
                                onProgress(progress)
                                if (status.isComplete) {
                                    finish { continuation.resume(progress) }
                                }
                            }

                            override fun onError(error: OfflineRegionError) {
                                finish {
                                    continuation.resumeWithException(
                                        IllegalStateException(
                                            "Download fehlgeschlagen: ${error.message}",
                                        ),
                                    )
                                }
                            }

                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                finish {
                                    continuation.resumeWithException(
                                        IllegalStateException(
                                            "Zu viele Kacheln (Grenze: $limit).",
                                        ),
                                    )
                                }
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
            region?.setObserver(null)
            region?.setDownloadState(OfflineRegion.STATE_INACTIVE)
            // Halbfertige Regionen sind wertlos und wuerden nur Platz belegen.
            region?.delete(NoopDeleteCallback)
        }
    }
}

private object NoopDeleteCallback : OfflineRegion.OfflineRegionDeleteCallback {
    override fun onDelete() = Unit
    override fun onError(error: String) = Unit
}
