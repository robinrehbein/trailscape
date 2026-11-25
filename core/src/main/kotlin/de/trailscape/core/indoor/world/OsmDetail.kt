package de.trailscape.core.indoor.world

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * # Volle OpenStreetMap-Attribute (Detailtreue, nur Katalog- und Server-Bau)
 *
 * Die Vektorkacheln (OpenMapTiles-Schema) lassen fuer die Wiedererkennung Wichtiges weg: Hecken,
 * Zaeune, Mauern, Leitplanken, Laermschutzwaende, Strommasten und -leitungen, Windraeder, Funkmasten,
 * Schornsteine, Wassertuerme, Laternen. OpenStreetMap selbst hat sie. Diese Datei liest einen
 * **Overpass-Auszug** (JSON mit `out geom`) entlang der Strecke, den das Entwicklerwerkzeug im Katalog-
 * bzw. Server-Bau holt (`indoor-dev … --osm-detail`, `docs/indoor/detailtreue.md`, Kapitel „Weitere
 * Datenquellen“). **Das Handy fragt Overpass nicht ab** — die Anfrage verriete den Streckenverlauf.
 *
 * Lizenz: ODbL 1.0, Quellenvermerk „© OpenStreetMap-Mitwirkende“ ([ATTRIBUTION]); er steht im Manifest.
 *
 * [OsmDetail] haelt die Objekte in Laenge/Breite; [project] rechnet sie in ENU der Welt um.
 */
class OsmDetail private constructor(internal val items: List<Item>) {
    internal class Item(val kind: OsmKind, val lat: DoubleArray, val lon: DoubleArray, val tags: Map<String, String>)

    val size: Int get() = items.size

    /** Anzahl je Art, fuer die Ausgabe des Werkzeugs. */
    fun counts(): Map<String, Int> = items.groupingBy { it.kind.name.lowercase() }.eachCount().toSortedMap()

    internal fun project(origin: GeoOrigin): List<OsmFeature> = items.map {
        val pts = DoubleArray(it.lat.size * 2)
        for (i in it.lat.indices) {
            pts[2 * i] = origin.toEast(it.lon[i])
            pts[2 * i + 1] = origin.toNorth(it.lat[i])
        }
        OsmFeature(it.kind, pts, it.tags)
    }

    companion object {
        const val ATTRIBUTION = "Zusatzdaten: © OpenStreetMap-Mitwirkende (über Overpass API)"
        const val LICENSE = "ODbL-1.0"
        const val LICENSE_URL = "https://www.openstreetmap.org/copyright"

        /**
         * Liest die Antwort einer Overpass-Abfrage (`[out:json]`, `out geom;`). Unbekannte Objekte werden
         * uebersprungen; kaputtes JSON wirft [IllegalArgumentException].
         */
        fun parseOverpass(text: String): OsmDetail {
            val root = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("Overpass-Antwort unlesbar: ${e.message}", e)
            } ?: throw IllegalArgumentException("Overpass-Antwort ist kein JSON-Objekt")
            val elements = root["elements"] as? JsonArray ?: throw IllegalArgumentException("Overpass-Antwort ohne elements")
            val out = ArrayList<Item>()
            for (el in elements) {
                val o = el as? JsonObject ?: continue
                val tags = (o["tags"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap() ?: continue
                val kind = OsmKind.of(tags) ?: continue
                when (o["type"]?.jsonPrimitive?.contentOrNull) {
                    "node" -> {
                        val la = o["lat"]?.jsonPrimitive?.doubleOrNull ?: continue
                        val lo = o["lon"]?.jsonPrimitive?.doubleOrNull ?: continue
                        out += Item(kind, doubleArrayOf(la), doubleArrayOf(lo), tags)
                    }
                    "way" -> {
                        val g = o["geometry"] as? JsonArray ?: continue
                        val la = ArrayList<Double>()
                        val lo = ArrayList<Double>()
                        for (p in g) {
                            val po = p as? JsonObject ?: continue
                            val pla = po["lat"]?.jsonPrimitive?.doubleOrNull ?: continue
                            val plo = po["lon"]?.jsonPrimitive?.doubleOrNull ?: continue
                            la += pla
                            lo += plo
                        }
                        if (la.isEmpty()) continue
                        // Flaechen (Turm-/Siloumriss) als Mittelpunkt, Linien als Linie.
                        if (kind.point && la.size > 1) {
                            out += Item(kind, doubleArrayOf(la.average()), doubleArrayOf(lo.average()), tags)
                        } else if (!kind.point && la.size >= 2) {
                            out += Item(kind, la.toDoubleArray(), lo.toDoubleArray(), tags)
                        }
                    }
                }
            }
            return OsmDetail(out)
        }

        /** Leerer Auszug (Tests, Bau ohne Zusatzdaten). */
        val EMPTY = OsmDetail(emptyList())

        /** Aus fertigen Objekten (Tests). */
        internal fun of(items: List<Item>) = OsmDetail(items)

        /**
         * Overpass-Abfrage (QL) fuer den Korridor um die Punkte [latLon] (lat, lon abwechselnd, z. B. alle 50–100 m
         * der Strecke). Radien je Art: Zaeune & Co. 120 m, Stromleitungen 600 m, Masten/Tuerme 2 km, Windraeder 5 km.
         */
        fun overpassQuery(latLon: DoubleArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i + 1 < latLon.size) {
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(TileMath.coord(latLon[i])).append(',').append(TileMath.coord(latLon[i + 1]))
                i += 2
            }
            val c = sb.toString()
            return """
                [out:json][timeout:180];
                (
                  way["barrier"~"^(hedge|fence|wall|guard_rail|retaining_wall|city_wall)$"](around:120,$c);
                  way["wall"="noise_barrier"](around:120,$c);
                  node["power"~"^(tower|pole)$"](around:600,$c);
                  way["power"~"^(line|minor_line)$"](around:600,$c);
                  node["power"="generator"]["generator:source"="wind"](around:5000,$c);
                  node["man_made"~"^(mast|tower|chimney|water_tower|silo)$"](around:2000,$c);
                  way["man_made"~"^(tower|chimney|water_tower|silo)$"](around:2000,$c);
                  node["highway"="street_lamp"](around:40,$c);
                );
                out geom;
            """.trimIndent()
        }
    }
}

/** Art eines Zusatzobjekts; [point] = als Punkt gezeichnet. */
internal enum class OsmKind(val point: Boolean) {
    HEDGE(false), FENCE(false), WALL(false), GUARD_RAIL(false), NOISE_BARRIER(false),
    POWER_LINE(false), POWER_TOWER(true), POWER_POLE(true),
    WIND_TURBINE(true), MAST(true), TOWER(true), CHIMNEY(true), WATER_TOWER(true), SILO(true),
    STREET_LAMP(true);

    companion object {
        fun of(t: Map<String, String>): OsmKind? = when {
            t["wall"] == "noise_barrier" -> NOISE_BARRIER
            t["barrier"] == "hedge" -> HEDGE
            t["barrier"] == "fence" -> FENCE
            t["barrier"] == "wall" || t["barrier"] == "retaining_wall" || t["barrier"] == "city_wall" -> WALL
            t["barrier"] == "guard_rail" -> GUARD_RAIL
            t["power"] == "line" || t["power"] == "minor_line" -> POWER_LINE
            t["power"] == "tower" -> POWER_TOWER
            t["power"] == "pole" -> POWER_POLE
            t["power"] == "generator" && t["generator:source"] == "wind" -> WIND_TURBINE
            t["man_made"] == "mast" -> MAST
            t["man_made"] == "tower" -> TOWER
            t["man_made"] == "chimney" -> CHIMNEY
            t["man_made"] == "water_tower" -> WATER_TOWER
            t["man_made"] == "silo" -> SILO
            t["highway"] == "street_lamp" -> STREET_LAMP
            else -> null
        }
    }
}

/** Ein Zusatzobjekt in ENU: Punkt (`e, n`) oder Linie. */
internal class OsmFeature(val kind: OsmKind, val pts: DoubleArray, val tags: Map<String, String>) {
    val e: Double get() = pts[0]
    val n: Double get() = pts[1]
    val minE: Double = (0 until pts.size / 2).minOf { pts[2 * it] }
    val maxE: Double = (0 until pts.size / 2).maxOf { pts[2 * it] }
    val minN: Double = (0 until pts.size / 2).minOf { pts[2 * it + 1] }
    val maxN: Double = (0 until pts.size / 2).maxOf { pts[2 * it + 1] }

    /** Hoehe in Metern aus `height` (z. B. „12“, „12 m“, „12.5“), sonst `null`. */
    fun heightM(key: String = "height"): Double? = tags[key]?.trim()?.removeSuffix("m")?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 && it < 400 }
}

/** [MapData] mit Zusatzobjekten aus [OsmDetail]. */
internal class OsmDetailMap(private val base: MapData, private val features: List<OsmFeature>) : MapData by base {
    private val index = RectIndex(features, { doubleArrayOf(it.minE, it.minN, it.maxE, it.maxN) }, 256.0)

    override fun osmFeatures(minE: Double, minN: Double, maxE: Double, maxN: Double): List<OsmFeature> = index.query(minE, minN, maxE, maxN)
}
