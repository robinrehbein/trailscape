package de.trailscape.app.ui.map

import de.trailscape.app.ui.MapStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Die **reine Rechnung** hinter dem Offline-Download: Kachelzahl, Zoombereich,
 * Groessengrenzen, Style-Adresse und Regions-Metadaten.
 *
 * Bewusst ohne jeden Android- oder MapLibre-Import — nur so laesst sich das
 * hier als JVM-Test pruefen (`app/src/test/.../OfflineTileMathTest.kt`). Alles,
 * was einen `Context`, eine `LatLngBounds` oder den `OfflineManager` braucht,
 * steht nebenan in `OfflineRegions.kt` (siehe [downloadOfflineRegion]).
 *
 * ## Warum die Kachelstufe NICHT die Kamerazoomstufe ist
 * MapLibre rechnet intern mit 512-Punkt-Kacheln; unsere Stile sind
 * 256-Punkt-Raster (`"tileSize": 256`, siehe [MapStyle.toRasterStyleJson]).
 * Der Kern rechnet deshalb bei jeder Rasterquelle um:
 *
 * ```cpp
 * // maplibre-native, src/mbgl/util/tile_cover.cpp
 * int32_t coveringZoomLevel(double zoom, style::SourceType type, uint16_t size) {
 *     zoom += util::log2(util::tileSize_D / size);   // 512/256 -> +1
 *     ...
 * }
 * ```
 *
 * Eine Offline-Region mit `minZoom = 13` laedt bei einem 256er-Rasterstil also
 * die Kacheln der Stufe **14** — und pro Stufe viermal so viele, wie eine
 * naive Rechnung auf der Kamerazoomstufe erwartet. Genau daran ging die
 * bisherige Schaetzung vorbei: Sie zaehlte `z … z+2`, heruntergeladen wurde
 * aber `z+1 … z+3`, also rund das Vierfache. Die Obergrenze von
 * [MAX_TILES_PER_DOWNLOAD] Kacheln war damit wirkungslos.
 * [offlineTileZoomRange] macht diese Umrechnung explizit.
 */

/** Wie in `lib/tile_cache.dart`: mehr als so viele Kacheln laedt die App nicht am Stueck. */
const val MAX_TILES_PER_DOWNLOAD: Int = 250

/** Obergrenze der Kachelstufe eines Downloads (Original: `math.min(minZoom + 2, 17)`). */
const val MAX_OFFLINE_ZOOM: Int = 17

/** Wie viele Zoomstufen ueber der aktuellen mitgeladen werden. */
const val OFFLINE_ZOOM_SPAN: Int = 2

/**
 * Um so viele Stufen liegt das Kachelraster ueber der Kamerazoomstufe —
 * `log2(512 / 256) = 1` fuer die 256-Punkt-Rasterstile dieser App (siehe
 * Datei-KDoc). Waeren die Stile `"tileSize": 512`, waere der Versatz 0.
 */
const val RASTER_TILE_ZOOM_OFFSET: Int = 1

/**
 * Groesste Kantenlaenge des sichtbaren Ausschnitts, die noch heruntergeladen
 * werden darf.
 *
 * Der Sinn ist nicht die Datenmenge (dafuer gibt es [MAX_TILES_PER_DOWNLOAD]),
 * sondern der *Nutzen*: Wer halb Europa im Bild hat, laedt Kacheln der Stufe 5
 * bis 7 — huebsche Uebersichtsbilder ohne einen einzigen Feldweg. Offline
 * navigieren laesst sich damit nicht. 150 km Kantenlaenge entspricht auf einem
 * ueblichen Telefon etwa Kamerazoom 9.
 */
const val MAX_OFFLINE_EDGE_KM: Double = 150.0

/** Grenzwert der Mercator-Projektion (wie `_maxLatitude` in Dart). */
private const val MAX_LATITUDE = 85.05112878

/** Erdumfang am Aequator in Kilometern (WGS84). */
private const val EARTH_CIRCUMFERENCE_KM = 40_075.017

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
 * Zaehlt die Kacheln eines Bereichs ueber alle **Kachelstufen** von
 * [minTileZoom] bis [maxTileZoom] — reine Rechnung ohne IO, wie
 * `TileCache.estimateTileCount` in Dart.
 *
 * Achtung: Die Grenzen sind Kachelstufen, nicht Kamerazoomstufen — siehe
 * Datei-KDoc und [offlineTileZoomRange].
 */
fun estimateTileCount(
    north: Double,
    south: Double,
    east: Double,
    west: Double,
    minTileZoom: Int,
    maxTileZoom: Int,
): Int {
    if (east < west || north < south) return 0

    var total = 0
    for (zoom in max(0, minTileZoom)..maxTileZoom) {
        val xMin = lonToTileX(west, zoom)
        val xMax = lonToTileX(east, zoom)
        val yMin = latToTileY(north, zoom)
        val yMax = latToTileY(south, zoom)
        total += (xMax - xMin + 1) * (yMax - yMin + 1)
    }
    return total
}

/**
 * Die Zoom-Spanne der **Regionsdefinition** zur aktuellen Kamerazoomstufe:
 * `z … z+2`, begrenzt durch [MAX_OFFLINE_ZOOM] und die hoechste vom Anbieter
 * unterstuetzte Stufe.
 *
 * Die Obergrenze beruecksichtigt den [RASTER_TILE_ZOOM_OFFSET]: Eine
 * Definition bis `style.maxZoom` wuerde Kacheln *ueber* der hoechsten
 * vorhandenen Stufe verlangen (die MapLibre dann still abschneidet). Deshalb
 * endet die Definition eine Stufe darunter — die tatsaechlich geladene
 * Kachelstufe ist dann genau `style.maxZoom`.
 */
fun offlineZoomRange(cameraZoom: Double, style: MapStyle): IntRange {
    val highest = min(MAX_OFFLINE_ZOOM, style.maxZoom - RASTER_TILE_ZOOM_OFFSET)
    val minZoom = max(0, cameraZoom.roundToInt()).coerceAtMost(max(0, highest))
    val maxZoom = min(minZoom + OFFLINE_ZOOM_SPAN, highest)
    return minZoom..max(minZoom, maxZoom)
}

/**
 * Die Kachelstufen, die MapLibre fuer eine Definition mit [definitionZooms]
 * tatsaechlich herunterlaedt: um [RASTER_TILE_ZOOM_OFFSET] versetzt und oben
 * durch die hoechste Stufe des Anbieters begrenzt (`"maxzoom"` der Quelle in
 * [MapStyle.toRasterStyleJson], das MapLibre in `coveringZoomRange` anwendet).
 */
fun offlineTileZoomRange(definitionZooms: IntRange, style: MapStyle): IntRange {
    val first = min(definitionZooms.first + RASTER_TILE_ZOOM_OFFSET, style.maxZoom)
    val last = min(definitionZooms.last + RASTER_TILE_ZOOM_OFFSET, style.maxZoom)
    return first..max(first, last)
}

/** Breite des Bereichs in Kilometern (auf mittlerer Breite gemessen). */
fun boundsWidthKm(north: Double, south: Double, east: Double, west: Double): Double {
    val midLat = ((north + south) / 2).coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
    return (east - west).coerceAtLeast(0.0) / 360.0 * EARTH_CIRCUMFERENCE_KM * cos(midLat * PI / 180)
}

/** Hoehe des Bereichs in Kilometern. */
fun boundsHeightKm(north: Double, south: Double): Double =
    (north - south).coerceAtLeast(0.0) / 360.0 * EARTH_CIRCUMFERENCE_KM

// ------------------------------------------------------------------ Planung

/** Ergebnis von [planOfflineDownload]. */
sealed interface OfflineDownloadPlan {

    /** Der Ausschnitt darf geladen werden. */
    data class Ready(
        /** `minZoom` der Regionsdefinition (Kamerazoomstufe). */
        val minZoom: Int,
        /** `maxZoom` der Regionsdefinition (Kamerazoomstufe). */
        val maxZoom: Int,
        /** Geschaetzte Kachelzahl — bereits in **Kachelstufen** gerechnet. */
        val tileCount: Int,
        /** Die tatsaechlich geladenen Kachelstufen, fuer die Anzeige. */
        val tileZooms: IntRange,
    ) : OfflineDownloadPlan {

        /** „Zoomstufen 14–16" bzw. „Zoomstufe 14" — fuer Meldungen. */
        val zoomLabel: String
            get() = if (tileZooms.first == tileZooms.last) {
                "Zoomstufe ${tileZooms.first}"
            } else {
                "Zoomstufen ${tileZooms.first}–${tileZooms.last}"
            }
    }

    /** Der Ausschnitt wird abgelehnt; [message] ist fertig fuer die Snackbar. */
    data class Rejected(val message: String) : OfflineDownloadPlan
}

/**
 * Entscheidet, ob und wie der sichtbare Ausschnitt heruntergeladen wird.
 *
 * Zwei Grenzen, die verschiedene Dinge schuetzen:
 *  1. [MAX_OFFLINE_EDGE_KM] — gegen *sinnlose* Downloads (weit herausgezoomt).
 *     Diese Grenze fehlte bisher: Ein Bild von halb Europa bei Kamerazoom 4
 *     ergibt nur rund 200 Kacheln und lief deshalb glatt durch die
 *     Kachelgrenze hindurch.
 *  2. [MAX_TILES_PER_DOWNLOAD] — gegen *zu grosse* Downloads.
 */
fun planOfflineDownload(
    north: Double,
    south: Double,
    east: Double,
    west: Double,
    cameraZoom: Double,
    style: MapStyle,
): OfflineDownloadPlan {
    if (east < west || north < south) {
        return OfflineDownloadPlan.Rejected("Dieser Ausschnitt lässt sich nicht speichern.")
    }

    val edgeKm = max(boundsWidthKm(north, south, east, west), boundsHeightKm(north, south))
    if (edgeKm > MAX_OFFLINE_EDGE_KM) {
        return OfflineDownloadPlan.Rejected(
            "Der sichtbare Bereich ist ${edgeKm.roundToInt()} km groß und enthält offline " +
                "keine brauchbaren Details. Zoome näher heran, um einen Bereich " +
                "herunterzuladen (höchstens ${MAX_OFFLINE_EDGE_KM.roundToInt()} km).",
        )
    }

    val definitionZooms = offlineZoomRange(cameraZoom, style)
    val tileZooms = offlineTileZoomRange(definitionZooms, style)
    val tiles = estimateTileCount(north, south, east, west, tileZooms.first, tileZooms.last)
    if (tiles <= 0) {
        return OfflineDownloadPlan.Rejected("Dieser Ausschnitt lässt sich nicht speichern.")
    }
    if (tiles > MAX_TILES_PER_DOWNLOAD) {
        return OfflineDownloadPlan.Rejected(
            "Bereich zu groß: ca. $tiles Kacheln (höchstens $MAX_TILES_PER_DOWNLOAD). " +
                "Zoome näher heran.",
        )
    }

    return OfflineDownloadPlan.Ready(
        minZoom = definitionZooms.first,
        maxZoom = definitionZooms.last,
        tileCount = tiles,
        tileZooms = tileZooms,
    )
}

// ------------------------------------------------------------------ Aufsicht

/**
 * Ohne Fortschritt fuer diese Zeitspanne gilt ein Download als haengend.
 *
 * Der MapLibre-Kern kennt keinen Zeitablauf: Verbindungsfehler wiederholt er
 * endlos mit wachsendem Abstand, und wenn die Style-Adresse unter dem
 * HTTP-Baustein durchfaellt (der alte `file://`-Fall), meldet er ueberhaupt
 * nichts. Ohne diese Aufsicht bliebe der Balken fuer immer stehen — genau das
 * war im Bugreport zu sehen.
 */
const val STALL_TIMEOUT_MS: Long = 20_000L

/** Wie oft die Aufsicht nachsieht. */
const val STALL_CHECK_INTERVAL_MS: Long = 1_000L

/** Meldung der Aufsicht — mit der zuletzt gemeldeten Ursache, wenn es eine gab. */
fun stalledMessage(lastError: String?): String {
    val seconds = STALL_TIMEOUT_MS / 1000
    val base = "Download abgebrochen: seit $seconds Sekunden kein Fortschritt"
    return if (lastError.isNullOrBlank()) {
        "$base. Bitte Internetverbindung prüfen und erneut versuchen."
    } else {
        "$base ($lastError)."
    }
}

// ------------------------------------------------------------------- Style

/**
 * Die Adresse, unter der eine Offline-Region den Rasterstil fuehrt.
 *
 * [org.maplibre.android.offline.OfflineTilePyramidRegionDefinition] verlangt
 * eine Style-**URL**, keine JSON-Zeichenkette. Abgerufen wird diese Adresse
 * nie: [downloadOfflineRegion] legt die JSON vorher unter genau diesem
 * Schluessel in MapLibres Ressourcen-Cache ab (`OfflineManager
 * .putResourceWithUrl`), und der Download findet sie dort. Die Kennung des
 * Stils steckt im Pfad, damit
 * [de.trailscape.app.ui.more.OfflineMapsCardContent] auch bei fehlenden
 * Metadaten noch erkennt, um welchen Stil es geht.
 *
 * Die Wunsch-Domain endet bewusst auf `.invalid` (RFC 2606): Sollte der
 * Cache-Eintrag wider Erwarten fehlen, laeuft der Download nicht in einen
 * echten Server, sondern in einen sofortigen Namensaufloesungsfehler — und
 * damit in eine sichtbare Fehlermeldung statt in einen haengenden Balken.
 */
fun offlineStyleUrl(style: MapStyle): String =
    "https://offline-style.trailscape.invalid/${style.id}.json"

// ---------------------------------------------------------------- Metadaten

/** Beschreibung einer gespeicherten Region (aus den Metadaten). */
data class OfflineRegionInfo(
    val name: String,
    val styleId: String,
    val createdAtMs: Long,
)

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
 *
 * Das Format ist seit der ersten Fassung unveraendert — Regionen aus
 * aelteren App-Staenden (die den Stil noch ueber eine `file://`-Adresse
 * fuehrten) bleiben also lesbar.
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
