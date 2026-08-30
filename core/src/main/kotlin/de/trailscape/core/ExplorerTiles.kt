package de.trailscape.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Reine Rechnung hinter den „Entdeckt-Kacheln" (Explorer-Tiles, wie bei
 * Statshunters/Squadrats): Die Welt wird in Slippy-Map-Kacheln der Stufe
 * [EXPLORER_TILE_ZOOM] geteilt; jede Kachel, durch die eine aufgezeichnete
 * Tour fuehrte, gilt als entdeckt.
 *
 * Bewusst ohne jeden Android- oder MapLibre-Import — nur so laesst sich das
 * hier als reiner JVM-Test pruefen (`core/src/test/.../ExplorerTilesTest.kt`).
 * Die GeoJSON-Darstellung fuer die Karte (Nebel, Umriss, groesstes Quadrat)
 * steht separat in `:app`
 * (`app/src/main/kotlin/de/trailscape/app/ui/map/ExplorerTileLayer.kt`), aus
 * demselben Grund.
 *
 * ## Warum genau Stufe 14
 * Stufe 14 ist die uebliche Wahl bei Statshunters/Squadrats: eine Kachel misst
 * an mitteleuropaeischen Breiten rund 1,5 km × 1,5 km — grob genug, dass ein
 * einziges Wochenende nennenswert Flaeche entdeckt, fein genug, dass „das
 * ganze Land" kein Zufallsprodukt ist.
 *
 * ## Slippy-Map-Mathe
 * Dieselben Formeln wie in `OfflineTileMath.kt` (dort privat und an die
 * Kamerazoomstufe gekoppelt) — hier fest auf [EXPLORER_TILE_ZOOM] verdrahtet,
 * inklusive Breiten-Klemmung auf ±85,05112878° (Grenze der Mercator-Projektion,
 * ueber der es keine Kacheln mehr gibt) und Index-Klemmung auf `0 .. n-1`.
 */
const val EXPLORER_TILE_ZOOM: Int = 14

/** `2^`[EXPLORER_TILE_ZOOM] — Kachelzahl je Achse auf dieser Stufe. */
private const val EXPLORER_TILE_COUNT: Int = 1 shl EXPLORER_TILE_ZOOM

/** Grenzwert der Mercator-Projektion (wie in `OfflineTileMath.kt`). */
private const val MAX_LATITUDE = 85.05112878

/**
 * Aufeinanderfolgende Stuetzstellen der Track-Interpolation duerfen hoechstens
 * so weit auseinanderliegen — in Kachelkoordinaten, also eine halbe
 * Kachelkante. Damit faellt bei GPS-Luecken keine durchfahrene Kachel durchs
 * Raster (siehe [explorerTilesForTrack]).
 */
private const val MAX_INTERPOLATION_STEP_TILES = 0.5

/**
 * Ab dieser Segmentlaenge wird NICHT mehr interpoliert — typischerweise ein
 * Import-/GPS-Ausreisser (Tunnel, GPS-Sprung, zwei zusammengefuegte Touren),
 * nicht wirklich durchfahrene Strecke. Nur die beiden Endpunkt-Kacheln zaehlen
 * dann als entdeckt.
 */
private const val MAX_INTERPOLATION_SEGMENT_KM = 20.0

/** Erdradius in Kilometern (WGS84-Mittel) — fuer die grobe Distanznaeherung. */
private const val EARTH_RADIUS_KM = 6371.0

/** Eine Kachel der Slippy-Map auf Stufe [EXPLORER_TILE_ZOOM]. */
data class ExplorerTile(val x: Int, val y: Int)

/** Kachelgrenzen in Grad. */
data class ExplorerTileBounds(val north: Double, val south: Double, val east: Double, val west: Double)

// -------------------------------------------------------------------- Mathe

private fun continuousTileX(lon: Double): Double =
    (lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * EXPLORER_TILE_COUNT

private fun continuousTileY(lat: Double): Double {
    val rad = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * (PI / 180)
    return (1 - ln(tan(rad) + 1 / cos(rad)) / PI) / 2 * EXPLORER_TILE_COUNT
}

private fun lonAtTileX(x: Int): Double = x.toDouble() / EXPLORER_TILE_COUNT * 360.0 - 180.0

private fun latAtTileY(y: Int): Double {
    val yFraction = y.toDouble() / EXPLORER_TILE_COUNT
    val rad = atan(sinh(PI * (1 - 2 * yFraction)))
    return rad * 180.0 / PI
}

/** Die Kachel, in der `(lat, lon)` liegt. */
fun explorerTileAt(lat: Double, lon: Double): ExplorerTile {
    val x = floor(continuousTileX(lon)).toInt().coerceIn(0, EXPLORER_TILE_COUNT - 1)
    val y = floor(continuousTileY(lat)).toInt().coerceIn(0, EXPLORER_TILE_COUNT - 1)
    return ExplorerTile(x, y)
}

/** Die Grenzen einer Kachel in Grad — invers zu [explorerTileAt]. */
fun explorerTileBounds(tile: ExplorerTile): ExplorerTileBounds = ExplorerTileBounds(
    north = latAtTileY(tile.y),
    south = latAtTileY(tile.y + 1),
    east = lonAtTileX(tile.x + 1),
    west = lonAtTileX(tile.x),
)

/**
 * Grobe (aequirektangulare) Distanznaeherung in Kilometern — fuer die
 * Segmentlaenge in [explorerTilesForTrack] reicht das allemal, ein
 * Haversine waere hier reiner Aufwand ohne Mehrwert.
 */
private fun approxDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val avgLatRad = (lat1 + lat2) / 2 * (PI / 180)
    val dLat = (lat2 - lat1) * (PI / 180)
    val dLon = (lon2 - lon1) * (PI / 180) * cos(avgLatRad)
    return hypot(dLat, dLon) * EARTH_RADIUS_KM
}

/**
 * Alle Kacheln, durch die der Track fuehrte.
 *
 * Nicht nur die Kacheln der aufgezeichneten Punkte selbst: Zwischen
 * aufeinanderfolgenden Punkten wird linear in Breite/Laenge interpoliert,
 * bis benachbarte Stuetzstellen hoechstens [MAX_INTERPOLATION_STEP_TILES]
 * Kachelkanten auseinanderliegen. Ohne das wuerden bei groben GPS-Intervallen
 * (bergab, im Tunnel, schlechter Empfang) Kacheln uebersprungen, durch die
 * tatsaechlich gefahren wurde.
 *
 * Ausnahme: Segmente ueber [MAX_INTERPOLATION_SEGMENT_KM] werden NICHT
 * interpoliert (Import-/GPS-Ausreisser) — dort zaehlen nur die beiden
 * Endpunkt-Kacheln.
 */
fun explorerTilesForTrack(points: List<TrackPoint>): Set<ExplorerTile> {
    if (points.isEmpty()) return emptySet()

    val tiles = mutableSetOf<ExplorerTile>()
    tiles += explorerTileAt(points[0].lat, points[0].lon)

    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]

        if (approxDistanceKm(a.lat, a.lon, b.lat, b.lon) > MAX_INTERPOLATION_SEGMENT_KM) {
            tiles += explorerTileAt(b.lat, b.lon)
            continue
        }

        val dx = continuousTileX(b.lon) - continuousTileX(a.lon)
        val dy = continuousTileY(b.lat) - continuousTileY(a.lat)
        val tileDistance = hypot(dx, dy)
        val steps = ceil(tileDistance / MAX_INTERPOLATION_STEP_TILES).toInt().coerceAtLeast(1)

        for (step in 1..steps) {
            val t = step.toDouble() / steps
            val lat = a.lat + (b.lat - a.lat) * t
            val lon = a.lon + (b.lon - a.lon) * t
            tiles += explorerTileAt(lat, lon)
        }
    }
    return tiles
}

/** Achsenparalleles Quadrat aus Kacheln. `x,y` = Kachel der linken oberen Ecke (kleinstes x und y), `size` = Kantenlaenge in Kacheln. */
data class ExplorerSquare(val x: Int, val y: Int, val size: Int)

/**
 * Das groesste zusammenhaengende `s×s`-Kachelquadrat, dessen Kacheln
 * **alle** entdeckt sind — die „groesstes Quadrat"-Kennzahl von
 * Statshunters/Squadrats.
 *
 * Sparse-DP ueber eine HashMap statt eines dichten Feldes: Entdeckte Kacheln
 * liegen typischerweise weit auseinander im 16384×16384-Gitter, ein dichtes
 * Feld waere hoffnungslos gross. Klassische Quadrat-DP
 * (`side(x,y) = 1 + min(side(x-1,y), side(x,y-1), side(x-1,y-1))` fuer jede
 * entdeckte Kachel, `0` fuer jede nicht in der Karte vorhandene) — nur je
 * entdeckter Kachel ein Eintrag, also linear in der Kachelzahl.
 *
 * Verarbeitung zeilenweise (erst `y`, dann `x`), damit beim Erreichen einer
 * Kachel ihre drei Nachbarn (oben, links, oben-links) bereits berechnet sind.
 * Bei Gleichstand gewinnt das zuletzt gefundene Quadrat — welches das genau
 * ist, ist fachlich egal.
 */
fun largestExplorerSquare(tiles: Set<ExplorerTile>): ExplorerSquare? {
    if (tiles.isEmpty()) return null

    val side = HashMap<ExplorerTile, Int>(tiles.size * 2)
    var best: ExplorerSquare? = null
    var bestSize = 0

    for (tile in tiles.sortedWith(compareBy({ it.y }, { it.x }))) {
        val left = side[ExplorerTile(tile.x - 1, tile.y)] ?: 0
        val up = side[ExplorerTile(tile.x, tile.y - 1)] ?: 0
        val upLeft = side[ExplorerTile(tile.x - 1, tile.y - 1)] ?: 0
        val size = 1 + minOf(left, up, upLeft)
        side[tile] = size

        if (size > bestSize) {
            bestSize = size
            best = ExplorerSquare(x = tile.x - size + 1, y = tile.y - size + 1, size = size)
        }
    }
    return best
}

/**
 * Zwischenstand einer Tour im Kachel-Cache.
 *
 * Fingerabdruck aus [updatedAt] + [pointCount] — dasselbe Muster wie
 * [StoredRideLoadFacts]: Ein Eintrag gilt, solange sich die Tour seit der
 * letzten Berechnung weder inhaltlich geaendert (`updatedAt`) noch in der
 * Punktzahl veraendert hat. Reicht hier ohne Profil-Signatur, weil die
 * Kachelmenge ausschliesslich von den GPS-Punkten abhaengt.
 */
data class StoredExplorerTiles(val updatedAt: Long, val pointCount: Int, val tiles: List<ExplorerTile>)

/**
 * Speicher fuer die Kachelmengen, je Tour-ID ein Eintrag — Pendant zu
 * [RideLoadFactsStore]. `:app` haengt hier eine Datei an, Tests nehmen eine
 * einfache Map-Implementierung.
 */
interface ExplorerTilesStore {
    fun get(id: String): StoredExplorerTiles?
    fun put(id: String, entry: StoredExplorerTiles)

    /** Wirft alle Eintraege weg, deren ID nicht in [ids] liegt (geloeschte oder geplante Touren). */
    fun retainAll(ids: Set<String>)

    /** Persistiert den Stand, falls die Implementierung persistiert. */
    fun flush()
}

/**
 * Sammelt die entdeckten Kacheln ueber alle **gefahrenen** Touren — geplante
 * Touren ([RideInfo.planned]) zaehlen nicht als entdeckt, denn wer eine Route
 * plant, ist sie noch nicht abgefahren.
 *
 * Arbeitet analog zu [computeInsights]/`RideLoadFacts.kt`: Je Tour wird
 * zunaechst der Cache-Eintrag befragt; stimmen [StoredExplorerTiles.updatedAt]
 * und [StoredExplorerTiles.pointCount] mit der Zusammenfassung ueberein, wird
 * er unveraendert uebernommen — ohne die Punkte erneut zu laden. Sonst wird
 * die volle Tour ueber [loadRide] nachgeladen (liefert das `null`, weil die
 * Datei geloescht oder in Quarantaene ist, wird die Tour uebersprungen), die
 * Kacheln frisch gerechnet und in den Cache geschrieben.
 *
 * Am Ende raeumt [ExplorerTilesStore.retainAll] alle Eintraege ab, die zu
 * keiner der uebergebenen, nicht geplanten Touren mehr gehoeren (geloeschte
 * Touren, oder Touren, die zwischenzeitlich als Planung markiert wurden), und
 * [ExplorerTilesStore.flush] schreibt den Stand fest.
 */
fun collectExplorerTiles(
    summaries: List<RideSummary>,
    store: ExplorerTilesStore,
    loadRide: (String) -> Ride?,
): Set<ExplorerTile> {
    val ridden = riddenRides(summaries)
    val allTiles = mutableSetOf<ExplorerTile>()

    for (summary in ridden) {
        val cached = store.get(summary.id)
        val tiles = if (cached != null &&
            cached.updatedAt == summary.updatedAt &&
            cached.pointCount == summary.pointCount
        ) {
            cached.tiles
        } else {
            val ride = loadRide(summary.id) ?: continue
            val computed = explorerTilesForTrack(ride.points).toList()
            store.put(
                summary.id,
                StoredExplorerTiles(
                    updatedAt = summary.updatedAt,
                    pointCount = summary.pointCount,
                    tiles = computed,
                ),
            )
            computed
        }
        allTiles += tiles
    }

    store.retainAll(ridden.map { it.id }.toSet())
    store.flush()
    return allTiles
}
