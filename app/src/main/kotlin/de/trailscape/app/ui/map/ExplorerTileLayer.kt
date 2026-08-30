package de.trailscape.app.ui.map

import de.trailscape.core.EXPLORER_TILE_ZOOM
import de.trailscape.core.ExplorerSquare
import de.trailscape.core.ExplorerTile
import de.trailscape.core.explorerTileBounds
import java.util.Locale

/**
 * Die **reine Geometrie** hinter den „Entdeckt-Kacheln" auf der Karte: aus
 * einer Menge entdeckter Kacheln ([de.trailscape.core.explorerTilesForTrack])
 * werden die drei GeoJSON-Ebenen gebaut, die die Karte zeichnet — Nebel
 * (unentdeckte Flaeche), Umriss der entdeckten Kacheln und das groesste
 * zusammenhaengende Quadrat.
 *
 * Bewusst ohne jeden Android- oder MapLibre-Import — nur so laesst sich das
 * hier als JVM-Test pruefen (`app/src/test/.../ExplorerTileLayerTest.kt`),
 * genau wie `OfflineTileMath.kt` nebenan. Alles, was einen `Context` oder eine
 * MapLibre-`GeoJsonSource` braucht, gehoert in eine eigene Datei daneben.
 *
 * ## Warum Rechtecke statt eines Polygons mit Loechern
 * Der Nebel ist geometrisch „das ganze Gitter minus die entdeckten Kacheln" —
 * ein einziges Polygon mit einem Loch pro entdecktem Fleck waere die
 * naheliegende Darstellung. GeoJSON-Loecher, die sich an einer Kante
 * beruehren (zwei entdeckte Kacheln nebeneinander erzeugen genau so eine
 * Beruehrung), sind nach der Spezifikation nicht eindeutig und bringen manche
 * Renderer durcheinander. [fogRectangles] zerlegt die Nebelflaeche deshalb in
 * disjunkte, lochfreie Rechtecke.
 */

/** Groesse des Kachelgitters je Achse auf Stufe [EXPLORER_TILE_ZOOM] (`2^14`). */
private const val GRID_SIZE: Int = 1 shl EXPLORER_TILE_ZOOM

/** Groesster gueltiger Kachelindex je Achse (0-basiert). */
private const val GRID_MAX: Int = GRID_SIZE - 1

/** Leere GeoJSON-FeatureCollection — wie `EMPTY_FEATURES` in `MapViewHost.kt`. */
private const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

/** Achsenparalleles Kachelrechteck, Grenzen einschliesslich. */
data class ExplorerTileRect(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

/**
 * Zerlegt „gesamtes Kachelgitter minus [explored]" in disjunkte Rechtecke.
 *
 * Leere Eingabemenge: ein einziges Rechteck ueber das ganze Gitter.
 *
 * Sonst in zwei Schritten:
 *  1. **Ausserhalb** des Begrenzungsrechtecks der entdeckten Kacheln bis zu
 *     vier grosse Randstreifen bis an die Gitterraender (oben, unten, links,
 *     rechts vom Begrenzungsrechteck — in dieser Reihenfolge disjunkt, weil
 *     links/rechts nur noch die Zeilen des Begrenzungsrechtecks abdecken).
 *  2. **Innerhalb** des Begrenzungsrechtecks zeilenweise die Luecken zwischen
 *     entdeckten Laeufen als Rechtecke bestimmen und ueber direkt
 *     untereinanderliegende Zeilen mit identischer x-Spanne zu einem
 *     durchgehenden, hohen Rechteck verschmelzen — ein klassisches
 *     „Histogramm-Zeilen"-Verfahren, hier auf Luecken statt auf Balken
 *     angewendet.
 */
fun fogRectangles(explored: Set<ExplorerTile>): List<ExplorerTileRect> {
    if (explored.isEmpty()) {
        return listOf(ExplorerTileRect(0, 0, GRID_MAX, GRID_MAX))
    }

    val minX = explored.minOf { it.x }
    val maxX = explored.maxOf { it.x }
    val minY = explored.minOf { it.y }
    val maxY = explored.maxOf { it.y }

    val rects = mutableListOf<ExplorerTileRect>()

    // Ausserhalb des Begrenzungsrechtecks: bis zu vier grosse Randstreifen.
    if (minY > 0) rects += ExplorerTileRect(0, 0, GRID_MAX, minY - 1)
    if (maxY < GRID_MAX) rects += ExplorerTileRect(0, maxY + 1, GRID_MAX, GRID_MAX)
    if (minX > 0) rects += ExplorerTileRect(0, minY, minX - 1, maxY)
    if (maxX < GRID_MAX) rects += ExplorerTileRect(maxX + 1, minY, GRID_MAX, maxY)

    // Innerhalb des Begrenzungsrechtecks: je Zeile die entdeckten x-Werte,
    // sortiert — daraus lassen sich die Luecken einer Zeile direkt ablesen.
    val exploredXByRow: Map<Int, List<Int>> = explored
        .groupBy({ it.y }, { it.x })
        .mapValues { (_, xs) -> xs.sorted() }

    // Noch offene, ueber mehrere Zeilen fortgesetzte Luecken: x-Spanne -> Start-Zeile.
    val open = LinkedHashMap<Pair<Int, Int>, Int>()

    for (y in minY..maxY) {
        val gaps = rowGaps(exploredXByRow[y].orEmpty(), minX, maxX)
        val gapSet = gaps.toSet()

        // Luecken, die es in dieser Zeile nicht mehr gibt, sind eine Zeile
        // frueher zu Ende gegangen.
        val ended = open.keys.filter { it !in gapSet }
        for (span in ended) {
            rects += ExplorerTileRect(span.first, open.getValue(span), span.second, y - 1)
            open.remove(span)
        }
        // Neue Luecken (noch nicht offen) starten in dieser Zeile.
        for (span in gaps) {
            open.putIfAbsent(span, y)
        }
    }
    // Was am Ende des Begrenzungsrechtecks noch offen ist, endet in dessen letzter Zeile.
    for ((span, startY) in open) {
        rects += ExplorerTileRect(span.first, startY, span.second, maxY)
    }

    return rects
}

/** Die Luecken zwischen den sortierten, entdeckten x-Werten einer Zeile innerhalb `[minX, maxX]`. */
private fun rowGaps(sortedExploredX: List<Int>, minX: Int, maxX: Int): List<Pair<Int, Int>> {
    val gaps = mutableListOf<Pair<Int, Int>>()
    var cursor = minX
    for (x in sortedExploredX) {
        if (x > cursor) gaps += cursor to (x - 1)
        cursor = x + 1
    }
    if (cursor <= maxX) gaps += cursor to maxX
    return gaps
}

// ------------------------------------------------------------------ GeoJSON

/** Zahl fuer GeoJSON, [Locale.ROOT]-formatiert — wie `coordinate()` in `MapViewHost.kt`. */
private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

/**
 * Polygon-Ring der vier Rechteckecken, geschlossen (erster = letzter Punkt)
 * und gegen den Uhrzeigersinn: Suedwest -> Suedost -> Nordost -> Nordwest ->
 * Suedwest.
 */
private fun ringCoordinates(north: Double, south: Double, east: Double, west: Double): String {
    val sw = "[${coordinate(west)},${coordinate(south)}]"
    val se = "[${coordinate(east)},${coordinate(south)}]"
    val ne = "[${coordinate(east)},${coordinate(north)}]"
    val nw = "[${coordinate(west)},${coordinate(north)}]"
    return "[$sw,$se,$ne,$nw,$sw]"
}

/** Ein Polygon-Feature ueber die Aussengrenzen eines Kachelrechtecks. */
private fun rectFeature(rect: ExplorerTileRect): String {
    // Nord/West kommen von der Kachel der linken oberen Ecke, Sued/Ost von
    // der Kachel der rechten unteren Ecke — [explorerTileBounds] liefert je
    // Kachel alle vier Grenzen, gebraucht wird hier je Ecke nur eine Haelfte.
    val topLeft = explorerTileBounds(ExplorerTile(rect.minX, rect.minY))
    val bottomRight = explorerTileBounds(ExplorerTile(rect.maxX, rect.maxY))
    val ring = ringCoordinates(
        north = topLeft.north,
        south = bottomRight.south,
        east = bottomRight.east,
        west = topLeft.west,
    )
    return """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[$ring]}}"""
}

/** Nebel-Ebene: die unentdeckte Flaeche als GeoJSON-FeatureCollection aus Polygonen. */
fun fogFeatureCollection(explored: Set<ExplorerTile>): String {
    val rects = fogRectangles(explored)
    if (rects.isEmpty()) return EMPTY_FEATURE_COLLECTION
    val builder = StringBuilder()
    builder.append("""{"type":"FeatureCollection","features":[""")
    rects.forEachIndexed { index, rect ->
        if (index > 0) builder.append(',')
        builder.append(rectFeature(rect))
    }
    builder.append("]}")
    return builder.toString()
}

/**
 * Umriss-Ebene: je entdeckter Kachel ein eigenes Polygon ueber deren Grenzen —
 * gedacht als duenne Linien-Ebene (Kachelraster ueber der entdeckten
 * Flaeche), die Feature-Zahl entspricht deshalb bewusst der Kachelzahl.
 */
fun exploredOutlineFeatureCollection(explored: Set<ExplorerTile>): String {
    if (explored.isEmpty()) return EMPTY_FEATURE_COLLECTION
    val builder = StringBuilder()
    builder.append("""{"type":"FeatureCollection","features":[""")
    explored.forEachIndexed { index, tile ->
        if (index > 0) builder.append(',')
        builder.append(rectFeature(ExplorerTileRect(tile.x, tile.y, tile.x, tile.y)))
    }
    builder.append("]}")
    return builder.toString()
}

/** Groesstes-Quadrat-Ebene: ein einzelnes Polygon ueber die Aussengrenzen von [square], leer bei `null`. */
fun maxSquareFeatureCollection(square: ExplorerSquare?): String {
    if (square == null) return EMPTY_FEATURE_COLLECTION
    val rect = ExplorerTileRect(
        minX = square.x,
        minY = square.y,
        maxX = square.x + square.size - 1,
        maxY = square.y + square.size - 1,
    )
    return """{"type":"FeatureCollection","features":[${rectFeature(rect)}]}"""
}
