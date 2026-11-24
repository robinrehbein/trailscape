package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoute
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * # Planung der Kacheln — ohne Netz
 *
 * Welche Kacheln eine Welt hat und **wann** jede gebraucht wird
 * ([WorldTile.show]), rechnet [planTiles] allein aus der Lage der Strecke
 * (docs/indoor.md 7.2/7.4):
 *
 *  * **Stufe 0** im Korridor ±[PlanShape.corridorM] um das Stueck der Strecke
 *    von [PlanShape.backM] hinter bis [PlanShape.aheadM] vor dem Fahrer
 *    (Laufmeter),
 *  * **Stufe 1** im Umkreis [PlanShape.r1M] um den Fahrer, ausserdem immer,
 *    solange eine ihrer 16 Kinder-Kacheln der Stufe 0 gezeigt wird
 *    (sonst bliebe die Teilflaeche der Stufe 2 ueber ihr sichtbar),
 *  * **Stufe 2** im Umkreis [PlanShape.r2M] (der Sichtkreis).
 *
 * Jede Kachel wird [PlanShape.leadM] frueher geladen und spaeter entladen
 * (Vorlauf und Hysterese der Darstellung, in `show` eingerechnet). Ein Punkt
 * ist gedeckt, sobald irgendeine Stufe ihn zeigt: Die Darstellung blendet eine
 * Teilflaeche nur aus, wenn die feinere Kachel darunter gezeigt wird.
 *
 * Die Masse werden so gewaehlt, dass [IndoorQuality.maxTextureBytes] und
 * [IndoorQuality.maxTriangles] an **jeder** Stelle eingehalten sind, gerechnet
 * mit Obergrenzen je Kachel ([TileCosts]). Passt es nicht, wird in dieser
 * Reihenfolge verkleinert: Radius der Stufe 1 (nicht unter 1,5 km), Radius der
 * Stufe 2 (nicht unter 4 km), dann der Korridor (Breite, Voraus, Zurueck).
 * Der Nachweis ist [worstCase] — dieselbe Funktion rechnet der Bau spaeter mit
 * den echten Kachelwerten nach.
 */

/** Version des Weltbaus — Teil der Welt-ID; erhoehen, sobald sich gebaute Welten aendern. */
internal const val WORLD_BUILDER_ID: String = "indoor-world/4"

/** Eine geplante Kachel: wann sie geladen sein soll (Laufmeter innerhalb der Runde, sortiert, zusammengefasst). */
internal class TilePlan(val spec: TileSpec, val show: List<ShowInterval>) {
    fun neededAt(d: Double): Boolean = show.any { d in it }
}

/** Die Lage der Strecke fuer die Planung: Ost/Nord je Laufmeter. */
internal class PlanPath(val lengthM: Double, val loop: Boolean, private val dist: DoubleArray, private val e: DoubleArray, private val n: DoubleArray) {
    init {
        require(dist.size >= 2 && dist.size == e.size && e.size == n.size) { "Planung: Strecke mit ${dist.size} Punkten" }
    }

    private var hint = 0

    /** Ost, Nord an Laufmeter [d] (geklemmt; linear zwischen den Punkten). */
    fun at(d: Double, out: DoubleArray) {
        val x = d.coerceIn(0.0, lengthM)
        var i = hint.coerceIn(0, dist.size - 2)
        while (i > 0 && dist[i] > x) i--
        while (i < dist.size - 2 && dist[i + 1] < x) i++
        hint = i
        val span = dist[i + 1] - dist[i]
        val t = if (span > 0) ((x - dist[i]) / span).coerceIn(0.0, 1.0) else 0.0
        out[0] = e[i] + (e[i + 1] - e[i]) * t
        out[1] = n[i] + (n[i + 1] - n[i]) * t
    }

    companion object {
        fun of(track: WorldTrack): PlanPath = PlanPath(
            track.lengthM, track.loop,
            DoubleArray(track.sampleCount) { track.distAt(it) },
            DoubleArray(track.sampleCount) { track.e[it].toDouble() },
            DoubleArray(track.sampleCount) { track.n[it].toDouble() },
        )

        fun of(route: IndoorRoute, origin: GeoOrigin): PlanPath = PlanPath(
            route.lengthM, route.isLoop,
            DoubleArray(route.points.size) { route.points[it].distM },
            DoubleArray(route.points.size) { origin.toEast(route.points[it].lon) },
            DoubleArray(route.points.size) { origin.toNorth(route.points[it].lat) },
        )
    }
}

/** Die Masse einer Planung in Metern. */
internal data class PlanShape(
    val corridorM: Double,
    val aheadM: Double,
    val backM: Double,
    val r1M: Double,
    val r2M: Double,
    val leadM: Double = PLAN_LEAD_M,
)

/** Vorlauf/Hysterese der Darstellung, in `show` eingerechnet. */
internal const val PLAN_LEAD_M: Double = 80.0

/**
 * Kosten je Kachel und Stufe fuer die Planung (Obergrenzen).
 *
 * @property triangles Dreiecke je Kachel der Stufe 0, 1, 2.
 * @property textureBytes GPU-Texturspeicher je Kachel der Stufe 0, 1, 2.
 */
internal class TileCosts(val triangles: IntArray, val textureBytes: LongArray) {
    companion object {
        /**
         * Obergrenzen aus [TileBudget.total] und der Bodentextur
         * `texturePx(level)²` plus einem Zuschlag fuer die Schicht-Texturen
         * (Fahrbahn, Gebaeude, Laub …).
         */
        fun upperBounds(
            quality: IndoorQuality,
            layerTextureBytes: LongArray = longArrayOf(6L * 1024 * 1024, 1L * 1024 * 1024, 0L),
        ): TileCosts = TileCosts(
            IntArray(WORLD_LEVEL_COUNT) { TileBudget.of(quality, it).total },
            LongArray(WORLD_LEVEL_COUNT) { textureGpuBytes(quality.texturePx(it)) + layerTextureBytes[it] },
        )
    }
}

/** Ergebnis von [planTiles]: die gewaehlten Masse, die Kacheln und der Nachweis. */
internal class TileLayout(
    val shape: PlanShape,
    val plans: List<TilePlan>,
    /** Schlimmster Fall mit den Obergrenzen ([TileCosts]). */
    val worst: WorldBudget,
    /** `false`: auch die kleinsten Masse sprengen das Budget — [shape] ist dann das kleinste versuchte. */
    val fits: Boolean,
) {
    fun count(level: Int): Int = plans.count { it.spec.level == level }

    /** Groesste Zahl gleichzeitig gebrauchter Kacheln der Stufe [level]. */
    fun maxConcurrent(level: Int, lengthM: Double, loop: Boolean): Int =
        worstCase(plans.filter { it.spec.level == level }, lengthM, loop, { 0L }, { 1 }).maxTriangles
}

/** Ausgangsradius der Stufe 1 je Qualitaet. */
private fun startR1(quality: IndoorQuality): Double = when (quality) {
    IndoorQuality.LOW -> 3_000.0
    IndoorQuality.MEDIUM -> 4_000.0
    IndoorQuality.HIGH -> 5_000.0
}

private const val MIN_R1_M = 1_500.0
private const val MIN_R2_M = 4_000.0

/** Die Folge der versuchten Masse, vom Wunsch (docs/indoor.md 7.4) zum Kleinsten. */
internal fun planShapes(quality: IndoorQuality): List<PlanShape> {
    val out = ArrayList<PlanShape>()
    var s = PlanShape(250.0, 700.0, 250.0, startR1(quality), quality.viewDistanceM)
    out.add(s)
    while (s.r1M > MIN_R1_M) {
        s = s.copy(r1M = max(MIN_R1_M, s.r1M - 500.0))
        out.add(s)
    }
    while (s.r2M > MIN_R2_M) {
        s = s.copy(r2M = max(MIN_R2_M, s.r2M - 1_000.0))
        out.add(s)
    }
    val corridor = listOf(
        200.0 to null, 150.0 to null, null to 600.0, 120.0 to null, null to 500.0, 100.0 to null,
        null to 400.0, 80.0 to null, 60.0 to null, null to 300.0, 40.0 to null, 20.0 to null, 0.0 to null,
    )
    for ((c, a) in corridor) {
        s = if (c != null) s.copy(corridorM = c) else s.copy(aheadM = a!!)
        if (s.backM > 150.0 && s.corridorM <= 150.0) s = s.copy(backM = 150.0)
        if (s.backM > 100.0 && s.corridorM <= 80.0) s = s.copy(backM = 100.0)
        if (s.corridorM <= 20.0) s = s.copy(backM = 50.0)
        out.add(s)
    }
    return out
}

/**
 * Plant die Kacheln der Strecke [path] fuer [quality]: die ersten Masse aus
 * [planShapes], deren schlimmster Fall mit [costs] das Budget der Qualitaet
 * einhaelt.
 */
internal fun planTiles(path: PlanPath, quality: IndoorQuality, costs: TileCosts = TileCosts.upperBounds(quality)): TileLayout {
    var last: TileLayout? = null
    for (shape in planShapes(quality)) {
        val plans = planTilesWith(path, shape)
        val worst = worstCase(plans, path.lengthM, path.loop, { costs.textureBytes[it.spec.level] }, { costs.triangles[it.spec.level] })
        val fits = worst.maxTextureBytes <= quality.maxTextureBytes && worst.maxTriangles <= quality.maxTriangles
        val layout = TileLayout(shape, plans, worst, fits)
        if (fits) return layout
        last = layout
    }
    return last!!
}

/** Plant mit festen Massen [shape]. Ergebnis sortiert nach Stufe, dann Kachel. */
internal fun planTilesWith(path: PlanPath, shape: PlanShape): List<TilePlan> {
    val raw = HashMap<TileSpec, Spans>()
    val pos = DoubleArray(2)
    val len = path.lengthM

    // Stufe 0: Kacheln nahe der Strecke an Stelle s werden gebraucht fuer d ∈ [s − voraus, s + zurueck].
    val c0 = shape.corridorM + 3.0 // 3 m Zuschlag fuer den Probenabstand von 5 m
    val cell0 = worldCellM(0)
    var s = 0.0
    while (true) {
        path.at(s, pos)
        val ix0 = floor((pos[0] - c0) / cell0).toInt()
        val ix1 = floor((pos[0] + c0) / cell0).toInt()
        val iy0 = floor((pos[1] - c0) / cell0).toInt()
        val iy1 = floor((pos[1] + c0) / cell0).toInt()
        for (ix in ix0..ix1) for (iy in iy0..iy1) {
            val t = TileSpec(0, ix, iy)
            if (t.distanceTo(pos[0], pos[1]) > c0) continue
            raw.getOrPut(t) { Spans() }.add(s - shape.aheadM - shape.leadM, s + shape.backM + shape.leadM)
        }
        if (s >= len) break
        s = min(len, s + 5.0)
    }

    // Stufe 1 und 2: Umkreis um den Fahrer, abgetastet alle 10 m.
    for (level in 1..2) {
        val r = (if (level == 1) shape.r1M else shape.r2M) + 5.0
        val cell = worldCellM(level)
        var d = 0.0
        while (true) {
            path.at(d, pos)
            val ix0 = floor((pos[0] - r) / cell).toInt()
            val ix1 = floor((pos[0] + r) / cell).toInt()
            val iy0 = floor((pos[1] - r) / cell).toInt()
            val iy1 = floor((pos[1] + r) / cell).toInt()
            for (ix in ix0..ix1) for (iy in iy0..iy1) {
                val t = TileSpec(level, ix, iy)
                if (t.distanceTo(pos[0], pos[1]) > r) continue
                raw.getOrPut(t) { Spans() }.add(d - 5.0 - shape.leadM, d + 5.0 + shape.leadM)
            }
            if (d >= len) break
            d = min(len, d + 10.0)
        }
    }

    val plans = HashMap<TileSpec, MutableList<ShowInterval>>()
    for ((t, sp) in raw) plans[t] = normalizeIntervals(sp.list, len, path.loop).toMutableList()
    // Eltern-Regel: Eine Stufe-1-Kachel wird gezeigt, solange eines ihrer Kinder gezeigt wird.
    val f = WORLD_LEVEL_FACTOR
    for ((t, show) in plans.entries.filter { it.key.level == 0 }.map { it.key to it.value.toList() }) {
        val parent = TileSpec(1, Math.floorDiv(t.ix, f), Math.floorDiv(t.iy, f))
        val list = plans.getOrPut(parent) { ArrayList() }
        list.addAll(show)
    }
    return plans.entries
        .map { (t, list) -> TilePlan(t, mergeIntervals(list)) }
        .filter { it.show.isNotEmpty() }
        .sortedWith(compareBy({ it.spec.level }, { it.spec.ix }, { it.spec.iy }))
}

/** Sammelt Bereiche in aufsteigender Reihenfolge und fasst Ueberlappende sofort zusammen. */
private class Spans {
    val list = ArrayList<DoubleArray>()

    fun add(a: Double, b: Double) {
        val last = list.lastOrNull()
        if (last != null && a <= last[1] + 1e-6) {
            last[1] = max(last[1], b)
            last[0] = min(last[0], a)
        } else {
            list.add(doubleArrayOf(a, b))
        }
    }
}

/**
 * Bringt Bereiche in `[0, lengthM]`: auf einer Runde ueber Start/Ziel hinweg
 * in zwei Bereiche geteilt (die Vorschau kurz vor Rundenende braucht Kacheln
 * vom Rundenanfang), sonst abgeschnitten. Ergebnis sortiert und zusammengefasst.
 */
internal fun normalizeIntervals(raw: List<DoubleArray>, lengthM: Double, loop: Boolean): List<ShowInterval> {
    val out = ArrayList<ShowInterval>()
    for (r in raw) {
        val a = r[0]
        val b = r[1]
        if (b < a) continue
        if (loop && lengthM > 0) {
            if (b - a >= lengthM) {
                out.add(ShowInterval(0.0, lengthM))
                continue
            }
            val a2 = ((a % lengthM) + lengthM) % lengthM
            val b2 = a2 + (b - a)
            if (b2 <= lengthM) {
                out.add(ShowInterval(a2, b2))
            } else {
                out.add(ShowInterval(a2, lengthM))
                out.add(ShowInterval(0.0, b2 - lengthM))
            }
        } else {
            val a2 = max(0.0, a)
            val b2 = min(lengthM, b)
            if (b2 >= a2) out.add(ShowInterval(a2, b2))
        }
    }
    return mergeIntervals(out)
}

/** Sortiert und fasst ueberlappende oder sich beruehrende Bereiche zusammen. */
internal fun mergeIntervals(list: List<ShowInterval>): List<ShowInterval> {
    if (list.isEmpty()) return emptyList()
    val sorted = list.sortedBy { it.fromM }
    val out = ArrayList<ShowInterval>()
    var cur = sorted[0]
    for (i in 1 until sorted.size) {
        val x = sorted[i]
        cur = if (x.fromM <= cur.toM) ShowInterval(cur.fromM, max(cur.toM, x.toM)) else {
            out.add(cur)
            x
        }
    }
    out.add(cur)
    return out
}

/**
 * Der schlimmste Fall: groesste Summe der Texturbytes und der Dreiecke aller
 * an einer Stelle gleichzeitig gebrauchten Kacheln, exakt ueber alle Stellen
 * `0..lengthM` (Sweep ueber die Bereichsgrenzen; Bereiche sind geschlossen).
 * [WorldBudget.diskBytes] bleibt 0 — den Plattenbedarf rechnet der Bau.
 */
internal fun worstCase(
    plans: List<TilePlan>,
    lengthM: Double,
    loop: Boolean,
    texBytesOf: (TilePlan) -> Long,
    trianglesOf: (TilePlan) -> Int,
): WorldBudget {
    // Ereignisse: Position, Art (0 = Beginn vor 1 = Ende bei gleicher Position), Kachel.
    val pos = ArrayList<Double>()
    val kind = ArrayList<Int>()
    val who = ArrayList<Int>()
    val tex = LongArray(plans.size) { texBytesOf(plans[it]) }
    val tri = IntArray(plans.size) { trianglesOf(plans[it]) }
    for ((k, p) in plans.withIndex()) {
        for (iv in p.show) {
            pos.add(iv.fromM); kind.add(0); who.add(k)
            pos.add(iv.toM); kind.add(1); who.add(k)
        }
    }
    val order = pos.indices.sortedWith(compareBy({ pos[it] }, { kind[it] }))
    var curTex = 0L
    var curTri = 0L
    var maxTex = 0L
    var maxTri = 0L
    for (i in order) {
        val k = who[i]
        if (kind[i] == 0) {
            curTex += tex[k]
            curTri += tri[k]
            maxTex = max(maxTex, curTex)
            maxTri = max(maxTri, curTri)
        } else {
            curTex -= tex[k]
            curTri -= tri[k]
        }
    }
    return WorldBudget(maxTex, min(maxTri, Int.MAX_VALUE.toLong()).toInt(), 0L)
}

// ---------------------------------------------------------------------------
// Welt-ID
// ---------------------------------------------------------------------------

/**
 * Stabile Welt-ID: SHA-256 ueber Streckengeometrie (Laufmeter auf cm, Lage
 * auf 1e-6°), Rundenmerkmal, Belagsabschnitte, Qualitaet und
 * [WORLD_BUILDER_ID]. Name und `id` der Route gehen nicht ein, ebenso wenig
 * ihre Hoehen (die Fahrbahn liegt auf dem Gelaende, siehe [fitTrack]).
 */
internal fun worldIdOf(route: IndoorRoute, quality: IndoorQuality): String {
    val md = MessageDigest.getInstance("SHA-256")
    val sb = StringBuilder()
    sb.append(WORLD_BUILDER_ID).append('|').append(quality.name).append('|').append(route.isLoop).append('|')
    for (p in route.points) {
        sb.append(Math.round(p.distM * 100)).append(',')
            .append(Math.round(p.lat * 1e6)).append(',')
            .append(Math.round(p.lon * 1e6)).append(';')
        if (sb.length > 8192) {
            md.update(sb.toString().toByteArray())
            sb.setLength(0)
        }
    }
    sb.append('|')
    for (s in route.surfaces) {
        sb.append(Math.round(s.fromM * 100)).append(',').append(Math.round(s.toM * 100)).append(',')
            .append(s.kind.name).append(',').append(s.highway ?: "").append(',')
            .append(s.widthM?.let { Math.round(it * 100) } ?: "").append(';')
    }
    md.update(sb.toString().toByteArray())
    val hex = md.digest().joinToString("") { "%02x".format(it) }
    return "w" + hex.substring(0, 24)
}

// ---------------------------------------------------------------------------
// Voranschlag
// ---------------------------------------------------------------------------

/**
 * Durchschnittsgroessen fuer den Voranschlag ([WorldPlan]). **Schaetzwerte**
 * — der Hauptagent ersetzt sie durch Messwerte echter Welten.
 */
internal object PlanSizes {
    /** Terrarium-PNG (256²) einer Hoehenkachel. */
    const val ELEVATION_TILE_BYTES = 95_000L

    /** OpenFreeMap-Vektorkachel z14 (laendlich bis Kleinstadt). */
    const val VECTOR_Z14_BYTES = 45_000L

    /** OpenFreeMap-Vektorkachel z12. */
    const val VECTOR_Z12_BYTES = 220_000L

    /** Luftbild-JPEG je Kachel, Bytes je Pixel (≈ 0,25 B/px bei Baseline-JPEG). */
    const val AERIAL_BYTES_PER_PX = 0.25

    /** Fertige Kachel-GLB je Stufe 0, 1, 2. */
    val GLB_BYTES: LongArray = longArrayOf(900_000L, 380_000L, 140_000L)
}

/** Voranschlag einer Planung. */
internal class PlanEstimate(val requests: Map<GeoSourceKind, Int>, val downloadBytes: Long, val diskBytes: Long)

/**
 * Zaehlt die Netzanfragen ohne Cache: Hoehen z13 ueber dem Gebiet der Stufen
 * 0 und 1, z10 ueber dem der Stufe 2; Vektor z14 im Gebiet der Stufe 0, z12
 * im Gebiet der Stufe 1; Luftbild je Kachel, deren Mitte [aerialAt] abdeckt.
 */
internal fun estimatePlan(
    plans: List<TilePlan>,
    origin: GeoOrigin,
    quality: IndoorQuality,
    aerialAt: (lat: Double, lon: Double) -> Boolean,
): PlanEstimate {
    val elev13 = HashSet<Long>()
    val elev10 = HashSet<Long>()
    val vec14 = HashSet<Long>()
    val vec12 = HashSet<Long>()
    var aerial = 0
    var aerialBytes = 0L
    var disk = 0L
    for (p in plans) {
        val t = p.spec
        when (t.level) {
            0 -> {
                mercatorTiles(t, origin, 13, elev13)
                mercatorTiles(t, origin, 14, vec14)
            }
            1 -> {
                mercatorTiles(t, origin, 13, elev13)
                mercatorTiles(t, origin, 12, vec12)
            }
            else -> mercatorTiles(t, origin, 10, elev10)
        }
        if (aerialAt(origin.toLat(t.anchorN), origin.toLon(t.anchorE))) {
            aerial++
            val px = quality.texturePx(t.level).toDouble()
            aerialBytes += (px * px * PlanSizes.AERIAL_BYTES_PER_PX).toLong()
        }
        disk += PlanSizes.GLB_BYTES[t.level.coerceIn(0, 2)]
    }
    val requests = mapOf(
        GeoSourceKind.ELEVATION to elev13.size + elev10.size,
        GeoSourceKind.VECTOR to vec14.size + vec12.size,
        GeoSourceKind.AERIAL to aerial,
    )
    val download = (elev13.size + elev10.size) * PlanSizes.ELEVATION_TILE_BYTES +
        vec14.size * PlanSizes.VECTOR_Z14_BYTES + vec12.size * PlanSizes.VECTOR_Z12_BYTES + aerialBytes
    return PlanEstimate(requests, download, disk)
}

/** Traegt die Web-Mercator-Kacheln (XYZ, Zoom [z]) ein, die das Rechteck von [t] schneiden. */
internal fun mercatorTiles(t: TileSpec, origin: GeoOrigin, z: Int, into: MutableSet<Long>) {
    val w = origin.toLon(t.minE)
    val east = origin.toLon(t.maxE)
    val s = origin.toLat(t.minN)
    val nn = origin.toLat(t.maxN)
    val x0 = lonToTileX(w, z)
    val x1 = lonToTileX(east - 1e-9, z)
    val y0 = latToTileY(nn, z)
    val y1 = latToTileY(s + 1e-9, z)
    for (x in x0..x1) for (y in y0..y1) into.add((z.toLong() shl 56) or (x.toLong() shl 28) or y.toLong())
}

internal fun lonToTileX(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

internal fun latToTileY(lat: Double, z: Int): Int {
    val r = Math.toRadians(lat.coerceIn(-85.05, 85.05))
    return floor((1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
}
