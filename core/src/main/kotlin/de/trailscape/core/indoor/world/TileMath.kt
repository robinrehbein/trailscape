package de.trailscape.core.indoor.world

import de.trailscape.core.optionalString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * # Web-Mercator-Kachelrechnung und URL-Bau der Geodatenquellen
 *
 * Kachelzaehlung XYZ (x nach Osten, y nach **Sueden**, Ursprung oben links bei
 * 180° W / 85,05° N), wie Terrarium und OpenFreeMap sie verwenden.
 * „Kachelkoordinate" heisst hier eine gebrochene Kachelzahl: `x = 8516,63`
 * liegt in Kachel 8516 bei 63 % der Breite.
 */

/** Eine Kachel `z/x/y`. */
internal data class TileId(val z: Int, val x: Int, val y: Int) {
    override fun toString(): String = "$z/$x/$y"
}

/** Rechteck in geografischen Koordinaten (Grad, WGS84). */
internal data class LatLonBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    val centerLat: Double get() = (minLat + maxLat) / 2
    val centerLon: Double get() = (minLon + maxLon) / 2
}

internal object TileMath {
    /** Groesste Breite, die Web-Mercator darstellt. */
    const val MAX_LAT: Double = 85.0511287798066

    fun tilesPerAxis(z: Int): Int = 1 shl z

    /** Gebrochene Kachelzahl in x fuer die Laenge [lon]. */
    fun lonToTileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * tilesPerAxis(z)

    /** Gebrochene Kachelzahl in y (nach Sueden) fuer die Breite [lat]. */
    fun latToTileY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * tilesPerAxis(z)
    }

    fun tileXToLon(x: Double, z: Int): Double = x / tilesPerAxis(z) * 360.0 - 180.0

    fun tileYToLat(y: Double, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / tilesPerAxis(z)))))

    /** Die Kachel, in der der Punkt liegt (am Rand geklemmt). */
    fun tileOf(lat: Double, lon: Double, z: Int): TileId {
        val n = tilesPerAxis(z)
        val x = floor(lonToTileX(lon, z)).toInt().coerceIn(0, n - 1)
        val y = floor(latToTileY(lat, z)).toInt().coerceIn(0, n - 1)
        return TileId(z, x, y)
    }

    /** Bruchteil des Punkts innerhalb seiner Kachel: `(fx, fy)` in `[0, 1)`, `fy` von oben (Norden). */
    fun fractionInTile(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
        val x = lonToTileX(lon, z)
        val y = latToTileY(lat, z)
        return (x - floor(x)) to (y - floor(y))
    }

    /** Grenzen einer Kachel in lat/lon. */
    fun tileBounds(t: TileId): LatLonBox = LatLonBox(
        minLat = tileYToLat((t.y + 1).toDouble(), t.z),
        minLon = tileXToLon(t.x.toDouble(), t.z),
        maxLat = tileYToLat(t.y.toDouble(), t.z),
        maxLon = tileXToLon((t.x + 1).toDouble(), t.z),
    )

    /** Alle Kacheln der Stufe [z], die [box] beruehren, zeilenweise von Nordwesten. */
    fun tilesCovering(box: LatLonBox, z: Int): List<TileId> {
        val n = tilesPerAxis(z)
        val x0 = floor(lonToTileX(box.minLon, z)).toInt().coerceIn(0, n - 1)
        val x1 = floor(lonToTileX(box.maxLon, z)).toInt().coerceIn(0, n - 1)
        val y0 = floor(latToTileY(box.maxLat, z)).toInt().coerceIn(0, n - 1)
        val y1 = floor(latToTileY(box.minLat, z)).toInt().coerceIn(0, n - 1)
        val out = ArrayList<TileId>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (y in y0..y1) for (x in x0..x1) out += TileId(z, x, y)
        return out
    }

    /** ENU-Rechteck → lat/lon-Rechteck ueber die lineare Abbildung von [origin]. */
    fun enuToLatLon(origin: GeoOrigin, minE: Double, minN: Double, maxE: Double, maxN: Double): LatLonBox =
        LatLonBox(origin.toLat(minN), origin.toLon(minE), origin.toLat(maxN), origin.toLon(maxE))

    /** URL einer Terrarium-Hoehenkachel nach [GeoEndpoints.ELEVATION_URL_TEMPLATE]. */
    fun elevationUrl(t: TileId): String = fillTemplate(GeoEndpoints.ELEVATION_URL_TEMPLATE, t)

    /** Setzt `{z}`, `{x}`, `{y}` in eine Kachel-URL-Vorlage (z. B. `tiles[0]` aus dem TileJSON). */
    fun fillTemplate(template: String, t: TileId): String =
        template.replace("{z}", t.z.toString()).replace("{x}", t.x.toString()).replace("{y}", t.y.toString())

    /**
     * WMS-`GetMap` 1.3.0 genau nach docs/indoor.md 8.1:
     * `CRS=EPSG:4326` mit der Achsenreihenfolge der EPSG-Definition, also
     * `BBOX=minLat,minLon,maxLat,maxLon`. Zahlen ohne Exponentialschreibweise,
     * unabhaengig von der Locale.
     */
    fun wmsGetMapUrl(source: AerialSource, box: LatLonBox, widthPx: Int, heightPx: Int, format: String = "image/jpeg"): String =
        source.wmsUrl +
            "?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
            "&LAYERS=" + source.layer +
            "&STYLES=&CRS=EPSG:4326" +
            "&BBOX=" + coord(box.minLat) + "," + coord(box.minLon) + "," + coord(box.maxLat) + "," + coord(box.maxLon) +
            "&WIDTH=" + widthPx + "&HEIGHT=" + heightPx +
            "&FORMAT=" + format

    /** Grad mit hoechstens 9 Nachkommastellen (≈ 0,1 mm), ohne Exponent und ohne Nachnullen. */
    fun coord(deg: Double): String {
        require(deg.isFinite()) { "Koordinate ist keine Zahl: $deg" }
        val s = BigDecimal(deg).setScale(9, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }
}

/**
 * Das Wesentliche eines TileJSON: die versionierte Kachel-URL-Vorlage
 * (`tiles[0]`) und der Zoombereich.
 */
internal data class TileJson(val tileTemplate: String, val minZoom: Int, val maxZoom: Int, val attribution: String?)

/**
 * Liest ein TileJSON. `null`, wenn der Text kein JSON-Objekt ist oder
 * `tiles[0]` fehlt bzw. kein `https`-Muster mit `{z}`, `{x}`, `{y}` ist.
 */
internal fun parseTileJson(text: String): TileJson? {
    val o = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    val tiles = o["tiles"] as? JsonArray ?: return null
    val first = (tiles.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!first.startsWith("https://") || "{z}" !in first || "{x}" !in first || "{y}" !in first) return null
    val minZoom = (o["minzoom"] as? JsonPrimitive)?.intOrNull ?: 0
    val maxZoom = (o["maxzoom"] as? JsonPrimitive)?.intOrNull ?: 14
    return TileJson(first, minZoom, maxZoom, o.optionalString("attribution"))
}
