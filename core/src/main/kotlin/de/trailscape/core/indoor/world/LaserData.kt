package de.trailscape.core.indoor.world

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * # Die Laserdaten einer Welt
 *
 * Laedt DGM1 und DOM1 im **Korridor** um die Strecke und haelt sie in
 * Bloecken von [LASER_BLOCK_M] Metern, die genau auf den Kacheln der Stufe 0
 * liegen (am ENU-Ursprung ausgerichtet, je Block 257² Proben auf ganzen
 * Metern — der Ostrand eines Blocks ist der Westrand des Nachbarn). Die
 * UTM-Raster der Dienste werden dabei einmal bilinear ins ENU-Gitter
 * umgetastet (je Block ein linearer Rahmen [EnuUtmFrame], Fehler wenige
 * Millimeter).
 *
 * ## Gelaende: Korridor fein, aussen wie bisher
 * Ein Block wird geladen, wenn er naeher als [LaserOptions.radiusM] an der
 * Strecke liegt. In ihm gilt das DGM1, zum Rand des Korridors hin
 * ([LaserOptions.fadeM]) weich ueberblendet in die Terrarium-Hoehen; diese
 * werden ueberall um den mittleren Versatz DGM1 − Terrarium ([offsetM],
 * Median im Korridor) verschoben, damit innen und aussen dieselbe Hoehe
 * haben. Die Ueberblendung ist in die Bloecke eingerechnet: [heightAboveSea]
 * kostet eine Blocksuche und eine bilineare Abfrage.
 *
 * ## Bewuchs
 * `nDOM = DOM1 − DGM1` je Probe (0…51 m, 0,2-m-Stufen in einem Byte). Daraus
 * [detectTrees]: Einzelbaeume als lokale Maxima des leicht geglaetteten
 * Kronenmodells mit hoehenabhaengigem Fenster (Popescu & Wynne 2004: Fenster
 * waechst mit der Baumhoehe), ohne Gebaeude (Grundrisse der Karte und von
 * LoD2, dazu ebene Flaechen) — und [canopyAt]/[canopyHeightAt] fuer Wald als
 * Dichte und Hoehe.
 *
 * Speicher: je Block 257² Float (DGM, 264 kB) + 257² Byte (nDOM, 66 kB). Ein
 * Kilometer Strecke mit 200 m Radius braucht rund 10 Bloecke, also 3,3 MB.
 */
internal class LaserData(
    val source: LaserSource,
    val origin: GeoOrigin,
    private val blocks: Map<Long, LaserBlock>,
    /** Mittlerer Versatz DGM1 − Terrarium in Metern (Median im Korridor). */
    val offsetM: Double,
    /** Gebaeude aus LoD2 (leer, wenn das Land keine anbietet oder sie nicht geladen wurden). */
    val buildings: List<LaserBuilding>,
    /** Kennzahlen des Ladens. */
    val stats: LaserStats,
    /** Ob an (`e`, `n`) eine LoD2-Kachel geladen ist (dort ist jedes echte Gebaeude in [buildings]). */
    val lod2Covered: (Double, Double) -> Boolean = { _, _ -> false },
    private val terrarium: (e: Double, n: Double) -> Double,
) {
    val blockCount: Int get() = blocks.size

    /**
     * Hoehe ueber dem Meer: DGM1 im Korridor, aussen Terrarium + [offsetM].
     * [outside] ersetzt die Terrarium-Hoehen ausserhalb der Bloecke (die
     * endgueltigen Hoehenkacheln decken mehr als die vorlaeufigen).
     */
    fun heightAboveSea(e: Double, n: Double, outside: (Double, Double) -> Double = terrarium): Double {
        val b = blockAt(e, n) ?: return outside(e, n) + offsetM
        return b.height(e, n)
    }

    /** Das reine DGM1 ohne Ueberblendung (`NaN` ausserhalb des Korridors oder ohne Daten). */
    fun dgmAt(e: Double, n: Double): Double = blockAt(e, n)?.dgm(e, n) ?: Double.NaN

    /**
     * Messwerte an (`e`, `n`) fuer Profile im Werkzeug: DGM1, DOM1 (= DGM1 + nDOM, auf 0,2 m), Terrarium + [offsetM],
     * ueberblendete Hoehe und Gewicht des DGM1 (alles Meter ueber dem Meer bzw. `0..1`; `NaN` ohne Daten).
     */
    fun probe(e: Double, n: Double): DoubleArray {
        val dgm = dgmAt(e, n)
        val nd = ndomAt(Math.round(e).toInt(), Math.round(n).toInt())
        val b = blockAt(e, n)
        return doubleArrayOf(dgm, dgm + nd, terrarium(e, n) + offsetM, heightAboveSea(e, n), b?.weight(e, n) ?: 0.0)
    }

    /** Ob (`e`, `n`) im voll aufgeloesten Teil des Korridors liegt (Ueberblendung = 1). */
    fun fullAt(e: Double, n: Double): Boolean = (blockAt(e, n)?.weight(e, n) ?: 0.0) >= 0.999

    /** Hoehe ueber Grund (nDOM) an der Probe (`ie`, `in_`) in ganzen ENU-Metern; `NaN` ohne Daten. */
    fun ndomAt(ie: Int, in_: Int): Double {
        val b = blocks[key(Math.floorDiv(ie, LASER_BLOCK), Math.floorDiv(in_, LASER_BLOCK))] ?: return Double.NaN
        return b.ndom(ie - b.bx * LASER_BLOCK, in_ - b.by * LASER_BLOCK)
    }

    /** Ob fuer den Block der Stufe-0-Kachel `(ix, iy)` Laserdaten vorliegen. */
    fun hasBlock(ix: Int, iy: Int): Boolean = blocks.containsKey(key(ix, iy))

    /**
     * Kronenbedeckung `0..1` um (`e`, `n`) — Anteil der Proben mit nDOM ≥ 3 m in
     * einem 9 × 9 m-Fenster; `NaN` ohne Daten. Gebaeude zaehlen nicht (vorher
     * mit [maskBuildings] ausgeblendet).
     */
    fun canopyAt(e: Double, n: Double): Double {
        val ce = Math.round(e).toInt()
        val cn = Math.round(n).toInt()
        var hit = 0
        var all = 0
        for (dj in -4..4 step 2) for (di in -4..4 step 2) {
            val v = ndomAt(ce + di, cn + dj)
            if (v.isNaN()) continue
            all++
            if (v >= 3.0) hit++
        }
        return if (all < 10) Double.NaN else hit.toDouble() / all
    }

    /** Mittlere Kronenhoehe (nDOM der bewachsenen Proben) in einem 13 × 13 m-Fenster; `NaN`, wenn dort kein Bewuchs ist. */
    fun canopyHeightAt(e: Double, n: Double): Double {
        val ce = Math.round(e).toInt()
        val cn = Math.round(n).toInt()
        var s = 0.0
        var k = 0
        for (dj in -6..6 step 2) for (di in -6..6 step 2) {
            val v = ndomAt(ce + di, cn + dj)
            if (v.isNaN() || v < 3.0) continue
            s += v
            k++
        }
        return if (k < 4) Double.NaN else s / k
    }

    /** Setzt das nDOM unter den Grundrissen [rings] (ENU, je Ring `e0, n0, e1, n1, …`) plus [bufferM] auf 0. */
    fun maskBuildings(rings: List<DoubleArray>, bufferM: Double = 1.0) {
        for (r in rings) {
            if (r.size < 6) continue
            var minE = Double.MAX_VALUE
            var minN = Double.MAX_VALUE
            var maxE = -Double.MAX_VALUE
            var maxN = -Double.MAX_VALUE
            for (i in r.indices step 2) {
                minE = min(minE, r[i]); maxE = max(maxE, r[i])
                minN = min(minN, r[i + 1]); maxN = max(maxN, r[i + 1])
            }
            val closed = r + doubleArrayOf(r[0], r[1])
            for (y in floor(minN - bufferM).toInt()..ceil(maxN + bufferM).toInt()) {
                for (x in floor(minE - bufferM).toInt()..ceil(maxE + bufferM).toInt()) {
                    val inside = pointInRings(listOf(r), x.toDouble(), y.toDouble()) ||
                        distanceToLine(closed, x.toDouble(), y.toDouble()) <= bufferM
                    if (!inside) continue
                    val b = blocks[key(Math.floorDiv(x, LASER_BLOCK), Math.floorDiv(y, LASER_BLOCK))] ?: continue
                    b.clearNdom(x - b.bx * LASER_BLOCK, y - b.by * LASER_BLOCK)
                }
            }
        }
    }

    /** Umgebungsrechteck aller Bloecke (minE, minN, maxE, maxN) oder `null`. */
    fun bounds(): DoubleArray? {
        if (blocks.isEmpty()) return null
        val v = blocks.values
        return doubleArrayOf(
            v.minOf { it.bx } * LASER_BLOCK_M, v.minOf { it.by } * LASER_BLOCK_M,
            (v.maxOf { it.bx } + 1) * LASER_BLOCK_M, (v.maxOf { it.by } + 1) * LASER_BLOCK_M,
        )
    }

    private fun blockAt(e: Double, n: Double): LaserBlock? {
        val bx = floor(e / LASER_BLOCK_M).toInt()
        val by = floor(n / LASER_BLOCK_M).toInt()
        return blocks[key(bx, by)]
    }

    /**
     * Einzelbaeume im Rechteck (Fusspunkt darin), aus lokalen Maxima des
     * Kronenmodells. Ergebnis je Rechteck fest (keine Zufallszahl), Reihenfolge
     * nach Ost, Nord.
     */
    fun detectTrees(minE: Double, minN: Double, maxE: Double, maxN: Double): List<LaserTree> {
        val x0 = floor(minE).toInt()
        val y0 = floor(minN).toInt()
        val x1 = ceil(maxE).toInt() - 1
        val y1 = ceil(maxN).toInt() - 1
        val m = 6 // Rand fuer Fenster, Glaettung und Plateauprobe
        val w = x1 - x0 + 1 + 2 * m
        val h = y1 - y0 + 1 + 2 * m
        if (w <= 2 * m || h <= 2 * m) return emptyList()
        val raw = FloatArray(w * h) { i ->
            val v = ndomAt(x0 - m + i % w, y0 - m + i / w)
            if (v.isNaN()) 0f else v.toFloat()
        }
        // 3×3-Gauss (1-2-1): nimmt Laserrauschen und Astluecken, die sonst Doppelspitzen machen.
        val chm = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var s = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val k = (if (dx == 0) 2 else 1) * (if (dy == 0) 2 else 1)
                s += raw[(y + dy) * w + x + dx] * k
            }
            chm[y * w + x] = s / 16f
        }
        val out = ArrayList<LaserTree>()
        for (y in m until h - m) for (x in m until w - m) {
            val v = chm[y * w + x]
            if (v < TREE_MIN_HEIGHT_M) continue
            // Fenster waechst mit der Hoehe: 2 m bei 3 m, 4 m ab 25 m.
            val r = (1.5 + 0.1 * v).coerceIn(2.0, 4.0)
            val ri = ceil(r).toInt()
            var isMax = true
            loop@ for (dy in -ri..ri) for (dx in -ri..ri) {
                if (dx == 0 && dy == 0) continue
                if (dx * dx + dy * dy > r * r) continue
                val q = chm[(y + dy) * w + x + dx]
                // Gleichstand: der Nachbar mit kleinerem Index gewinnt (genau einer pro Plateau).
                if (q > v || (q == v && (dy < 0 || (dy == 0 && dx < 0)))) {
                    isMax = false
                    break@loop
                }
            }
            if (!isMax) continue
            if (isFlat(raw, w, x, y) || isPlateau(raw, w, x, y)) continue
            // Kronenradius: wo das Kronenmodell in 8 Richtungen unter 60 % der Spitze faellt.
            var sum = 0.0
            for (d in 0 until 8) {
                val dx = DIR_X[d]
                val dy = DIR_Y[d]
                var s = 1
                while (s <= 8) {
                    val xx = x + dx * s
                    val yy = y + dy * s
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) break
                    if (chm[yy * w + xx] < 0.6f * v) break
                    s++
                }
                sum += s * (if (dx != 0 && dy != 0) 1.414 else 1.0)
            }
            val crown = (sum / 8 - 0.5).coerceIn(0.8, 8.0)
            // Spitze aus dem ungeglaetteten Modell (die Glaettung kappt sie um 5–10 %).
            val top = max(v, raw[y * w + x]).toDouble()
            out += LaserTree((x0 - m + x).toDouble(), (y0 - m + y).toDouble(), top, crown)
        }
        return out
    }

    /**
     * Plateau statt Kronenspitze (Flachdach, Hallendach, geschnittene Hecke):
     * In 7 × 7 m liegen mindestens 40 % der Proben weniger als 25 cm unter der
     * Spitze. Eine Krone faellt von der Spitze aus nach allen Seiten ab.
     */
    private fun isPlateau(raw: FloatArray, w: Int, x: Int, y: Int): Boolean {
        var top = 0f
        for (dy in -1..1) for (dx in -1..1) top = max(top, raw[(y + dy) * w + x + dx])
        var near = 0
        for (dy in -3..3) for (dx in -3..3) if (raw[(y + dy) * w + x + dx] >= top - 0.25f) near++
        return near >= 20
    }

    /** Ebene Flaeche in 5×5 (Dach, Mauer, Lkw): Restfehler einer Ausgleichsebene unter 12 cm. */
    private fun isFlat(raw: FloatArray, w: Int, x: Int, y: Int): Boolean {
        var n = 0
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        var sxx = 0.0; var syy = 0.0; var sxz = 0.0; var syz = 0.0
        for (dy in -2..2) for (dx in -2..2) {
            val z = raw[(y + dy) * w + x + dx].toDouble()
            if (z < 1.0) return false
            n++
            sx += dx; sy += dy; sz += z
            sxx += dx * dx; syy += dy * dy; sxz += dx * z; syz += dy * z
        }
        // Symmetrisches Gitter: Summen ueber dx, dy sind 0, Kreuzterme entfallen.
        val a = sxz / sxx
        val b = syz / syy
        val c = sz / n
        var res = 0.0
        for (dy in -2..2) for (dx in -2..2) {
            val z = raw[(y + dy) * w + x + dx].toDouble()
            val d = z - (a * dx + b * dy + c)
            res += d * d
        }
        return sqrt(res / n) < 0.12
    }

    companion object {
        const val LASER_BLOCK = 256
        const val LASER_BLOCK_M = 256.0
        const val TREE_MIN_HEIGHT_M = 3.0
        private val DIR_X = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        private val DIR_Y = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

        fun key(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    }
}

/** Ein Einzelbaum aus dem nDOM: Fusspunkt (ENU), Hoehe ueber Grund, Kronenradius. */
internal class LaserTree(val e: Double, val n: Double, val heightM: Double, val crownRadiusM: Double)

/** Kennzahlen des Ladens fuer Bericht und Messung. */
internal class LaserStats(
    val blocks: Int,
    val requests: Int,
    val fromCache: Int,
    val bytes: Long,
    val lod2Tiles: Int,
    val lod2Bytes: Long,
    val millis: Long,
    /** Abweichung DGM1 − Terrarium im Korridor: Median, 5. und 95. Perzentil. */
    val offsetP05: Double,
    val offsetP95: Double,
)

/** Ein Block der Laserdaten (Kachel der Stufe 0). */
internal class LaserBlock(val bx: Int, val by: Int) {
    /** Gelaende (DGM1, `NaN` = keine Daten), 257² Proben, Zeile = Nord-Index. */
    val dgm = FloatArray(N * N) { Float.NaN }

    /** Ueberblendetes Gelaende (Meter ueber dem Meer). */
    val blended = FloatArray(N * N)

    /** Gewicht des DGM1 in [blended], 0..255. */
    val weight = ByteArray(N * N)

    /** nDOM in 0,2-m-Stufen (0 = kein Bewuchs, 255 = 51 m oder mehr). */
    val ndomQ = ByteArray(N * N)

    private val e0 = bx * LaserData.LASER_BLOCK_M
    private val n0 = by * LaserData.LASER_BLOCK_M

    private inline fun bilinear(e: Double, n: Double, get: (Int) -> Double): Double {
        val fx = (e - e0).coerceIn(0.0, N - 1.0)
        val fy = (n - n0).coerceIn(0.0, N - 1.0)
        val ix = min(floor(fx).toInt(), N - 2)
        val iy = min(floor(fy).toInt(), N - 2)
        val tx = fx - ix
        val ty = fy - iy
        val i = iy * N + ix
        val a = get(i)
        val b = get(i + 1)
        val c = get(i + N)
        val d = get(i + N + 1)
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * ty
    }

    fun height(e: Double, n: Double): Double = bilinear(e, n) { blended[it].toDouble() }
    fun dgm(e: Double, n: Double): Double = bilinear(e, n) { dgm[it].toDouble() }
    fun weight(e: Double, n: Double): Double = bilinear(e, n) { (weight[it].toInt() and 0xFF) / 255.0 }

    fun ndom(x: Int, y: Int): Double {
        if (x !in 0 until N || y !in 0 until N) return Double.NaN
        val i = y * N + x
        if (dgm[i].isNaN()) return Double.NaN
        return (ndomQ[i].toInt() and 0xFF) * 0.2
    }

    fun clearNdom(x: Int, y: Int) {
        if (x in 0 until N && y in 0 until N) ndomQ[y * N + x] = 0
    }

    companion object {
        const val N = LaserData.LASER_BLOCK + 1
    }
}

/** Einstellungen der Laser-Stufe. */
class LaserOptions(
    val fetcher: LaserFetcher,
    val sources: List<LaserSource> = laserSources,
    /** Bloecke bis zu diesem Abstand (Blockrand–Strecke) werden geladen. */
    val radiusM: Double = 200.0,
    /** Breite der Ueberblendung DGM1 → Terrarium am Rand des Korridors. */
    val fadeM: Double = 60.0,
    /** LoD2-Gebaeude laden (sonst nur Gelaende und Bewuchs). */
    val buildings: Boolean = true,
    /**
     * LoD2 nur fuer 1-km-Kacheln, die dieser Streifen um die Strecke beruehrt
     * (eine Stadtkachel hat 20–30 MB). Weiter draussen: Hoehe aus dem nDOM
     * ueber dem OSM-Grundriss.
     */
    val lod2RadiusM: Double = 120.0,
    /**
     * LoD2 schon auf den Korridor zugeschnitten ([cutLod2Corridor], Format in
     * `LaserLod2Corridor.kt`): dann werden keine LoD2-Kacheln geladen.
     */
    val lod2Corridor: ByteArray? = null,
    val threads: Int = 4,
)

/**
 * Laedt die Laserdaten fuer die Strecke [lineE]/[lineN] (ENU-Punkte der Route),
 * oder `null`, wenn keine Quelle die Strecke abdeckt.
 *
 * @param terrariumAboveSea Hoehe aus den Terrarium-Kacheln (Meter ueber dem Meer).
 * @param isCancelled wird zwischen den Bloecken gefragt.
 */
internal fun loadLaserData(
    options: LaserOptions,
    origin: GeoOrigin,
    lineE: DoubleArray,
    lineN: DoubleArray,
    terrariumAboveSea: (Double, Double) -> Double,
    isCancelled: () -> Boolean = { false },
): LaserData? {
    val start = System.currentTimeMillis()
    // Die Quelle, in deren Rechteck die meisten Punkte liegen (mindestens die Haelfte).
    val probe = lineE.indices step max(1, lineE.size / 50)
    val source = options.sources
        .map { s -> s to probe.count { i -> s.covers(origin.toLat(lineN[i]), origin.toLon(lineE[i])) } }
        .filter { it.second * 2 >= probe.count() }
        .maxByOrNull { it.second }?.first ?: return null
    val line = PolylineDistance(lineE, lineN)
    val r = options.radiusM
    val wanted = laserBlocksFor(lineE, lineN, r, line)
    val requests = java.util.concurrent.atomic.AtomicInteger()
    val cached = java.util.concurrent.atomic.AtomicInteger()
    val bytes = java.util.concurrent.atomic.AtomicLong()
    val blocks = HashMap<Long, LaserBlock>()
    if (source.tiles != null) {
        // Kachel-Download (ZIP je Archiv): erst alle Bloecke anlegen, dann je Archiv fuellen.
        val list = wanted.map { LaserBlock(it[0], it[1]) }
        fillBlocksFromTiles(list, source, origin, options.fetcher, options.threads, requests, cached, bytes, isCancelled)
        for (b in list) blocks[LaserData.key(b.bx, b.by)] = b
    } else {
    val pool = Executors.newFixedThreadPool(max(1, options.threads)) { t -> Thread(t, "laser").apply { isDaemon = true } }
    try {
        val futures = ArrayList<Future<LaserBlock>>()
        for (w in wanted) {
            futures += pool.submit(Callable {
                if (isCancelled()) throw WorldBuildCancelled()
                val b = LaserBlock(w[0], w[1])
                fillBlock(b, source, origin, options.fetcher, requests, cached, bytes)
                b
            })
        }
        for (f in futures) {
            val b = try {
                f.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
            blocks[LaserData.key(b.bx, b.by)] = b
        }
    } finally {
        pool.shutdownNow()
    }
    }
    // Versatz DGM1 − Terrarium im Korridor (Median), dann Ueberblendung.
    val diffs = ArrayList<Double>()
    for (b in blocks.values) {
        var i = 0
        while (i < LaserBlock.N * LaserBlock.N) {
            val v = b.dgm[i]
            if (!v.isNaN()) {
                val e = b.bx * LaserData.LASER_BLOCK_M + i % LaserBlock.N
                val n = b.by * LaserData.LASER_BLOCK_M + i / LaserBlock.N
                if (line.distance(e, n, r) < r * 0.5) {
                    val t = terrariumAboveSea(e, n)
                    if (t.isFinite()) diffs += v - t
                }
            }
            i += 1031 // grob verteilt, rund 64 Proben je Block
        }
    }
    diffs.sort()
    fun pct(p: Double) = if (diffs.isEmpty()) 0.0 else diffs[((diffs.size - 1) * p).toInt()]
    val offset = pct(0.5)
    val full = r - options.fadeM
    for (b in blocks.values) {
        for (j in 0 until LaserBlock.N) for (i in 0 until LaserBlock.N) {
            val k = j * LaserBlock.N + i
            val e = b.bx * LaserData.LASER_BLOCK_M + i
            val n = b.by * LaserData.LASER_BLOCK_M + j
            val outer = terrariumAboveSea(e, n) + offset
            val v = b.dgm[k]
            val d = line.distance(e, n, r + 1)
            val wgt = if (v.isNaN()) 0.0 else 1.0 - smoothstep(full, r, d)
            b.weight[k] = (wgt * 255).toInt().toByte()
            b.blended[k] = if (wgt <= 0) outer.toFloat() else (outer + (v - outer) * wgt).toFloat()
        }
    }
    val corridor = options.lod2Corridor?.takeIf { options.buildings }
    val lod2Keys = if (corridor == null && options.buildings && source.lod2UrlTemplate != null) lod2Tiles(source, origin, lineE, lineN, options.lod2RadiusM) else emptySet()
    val lod2 = when {
        corridor != null -> {
            val (zone, list) = decodeLod2Corridor(corridor)
            val keys = blocks.keys
            val enu = list.mapNotNull { it.toEnu(origin, zone) }.filter { b ->
                LaserData.key(floor(b.ce / LaserData.LASER_BLOCK_M).toInt(), floor(b.cn / LaserData.LASER_BLOCK_M).toInt()) in keys
            }
            Lod2Result(enu.sortedWith(compareBy({ it.ce }, { it.cn }, { it.id })), 0, corridor.size.toLong())
        }
        lod2Keys.isNotEmpty() -> loadLod2(source, origin, options.fetcher, blocks.values, lod2Keys, options.threads, isCancelled)
        else -> Lod2Result(emptyList(), 0, 0L)
    }
    // Unter LoD2-Grundrissen liegt das Gelaende knapp unter dem Gebaeudeboden: Das adaptive Netz bekommt
    // dort eine gerade Kante an der Wand (sonst wellte sich das gemittelte DGM unter dem Haus an der Wand hoch).
    for (bld in lod2.buildings) flattenFootprint(blocks, bld)
    val stats = LaserStats(
        blocks = blocks.size,
        requests = requests.get(),
        fromCache = cached.get(),
        bytes = bytes.get(),
        lod2Tiles = lod2.tiles,
        lod2Bytes = lod2.bytes,
        millis = System.currentTimeMillis() - start,
        offsetP05 = pct(0.05) - offset,
        offsetP95 = pct(0.95) - offset,
    )
    val frame = EnuUtmFrame(origin, source.utmZone, (lineE.min() + lineE.max()) / 2, (lineN.min() + lineN.max()) / 2)
    val covered = if (corridor != null) {
        { e: Double, n: Double -> line.distance(e, n, options.lod2RadiusM) <= options.lod2RadiusM }
    } else {
        { e: Double, n: Double -> LaserData.key(floor(frame.utmX(e, n) / 1000).toInt(), floor(frame.utmY(e, n) / 1000).toInt()) in lod2Keys }
    }
    return LaserData(source, origin, blocks, offset, lod2.buildings, stats, covered) { e, n -> terrariumAboveSea(e, n) }
}

/** Setzt die ueberblendeten Hoehen im Grundriss von [b] auf hoechstens dessen Bodenhoehe − 0,3 m (nur bei vollem DGM-Gewicht). */
private fun flattenFootprint(blocks: Map<Long, LaserBlock>, b: LaserBuilding) {
    val floor = (b.groundU - 0.3).toFloat()
    for (r in b.footprint) {
        if (r.size < 6) continue
        var minE = Double.MAX_VALUE; var minN = Double.MAX_VALUE; var maxE = -Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
        for (i in r.indices step 2) { minE = min(minE, r[i]); maxE = max(maxE, r[i]); minN = min(minN, r[i + 1]); maxN = max(maxN, r[i + 1]) }
        for (y in ceil(minN).toInt()..floor(maxN).toInt()) for (x in ceil(minE).toInt()..floor(maxE).toInt()) {
            if (!pointInRings(listOf(r), x.toDouble(), y.toDouble())) continue
            // Eine Probe auf dem Blockrand steht in zwei (an der Ecke vier) Bloecken: alle gleich setzen.
            val bx0 = Math.floorDiv(x, LaserData.LASER_BLOCK)
            val by0 = Math.floorDiv(y, LaserData.LASER_BLOCK)
            for (bx in listOf(bx0, bx0 - 1)) for (by in listOf(by0, by0 - 1)) {
                val blk = blocks[LaserData.key(bx, by)] ?: continue
                val lx = x - bx * LaserData.LASER_BLOCK
                val ly = y - by * LaserData.LASER_BLOCK
                if (lx !in 0 until LaserBlock.N || ly !in 0 until LaserBlock.N) continue
                val k = ly * LaserBlock.N + lx
                if ((blk.weight[k].toInt() and 0xFF) < 255) continue
                if (blk.blended[k] > floor) blk.blended[k] = floor
            }
        }
    }
}

/** Die LoD2-Kacheln (Kilometer-Index Ost, Nord), die der Streifen [r] um den Polygonzug beruehrt. */
internal fun lod2Tiles(source: LaserSource, origin: GeoOrigin, lineE: DoubleArray, lineN: DoubleArray, r: Double): Set<Long> {
    val out = LinkedHashSet<Long>()
    val frame = EnuUtmFrame(origin, source.utmZone, (lineE.min() + lineE.max()) / 2, (lineN.min() + lineN.max()) / 2)
    for (i in lineE.indices) {
        for ((de, dn) in listOf(0.0 to 0.0, r to 0.0, -r to 0.0, 0.0 to r, 0.0 to -r, r * 0.7 to r * 0.7, -r * 0.7 to r * 0.7, r * 0.7 to -r * 0.7, -r * 0.7 to -r * 0.7)) {
            val x = frame.utmX(lineE[i] + de, lineN[i] + dn)
            val y = frame.utmY(lineE[i] + de, lineN[i] + dn)
            out += LaserData.key(floor(x / 1000).toInt(), floor(y / 1000).toInt())
        }
    }
    return out
}

/** Die Bloecke (bx, by), deren Rechteck naeher als [r] am Polygonzug liegt. */
internal fun laserBlocksFor(lineE: DoubleArray, lineN: DoubleArray, r: Double, line: PolylineDistance = PolylineDistance(lineE, lineN)): List<IntArray> {
    val minE = lineE.min() - r
    val maxE = lineE.max() + r
    val minN = lineN.min() - r
    val maxN = lineN.max() + r
    val wanted = ArrayList<IntArray>()
    val b = LaserData.LASER_BLOCK_M
    for (by in floor(minN / b).toInt()..floor(maxN / b).toInt()) {
        for (bx in floor(minE / b).toInt()..floor(maxE / b).toInt()) {
            // Mitte plus halbe Diagonale: konservativ (nie ein Block zu wenig, am Rand gelegentlich einer mehr).
            val d = line.distance((bx + 0.5) * b, (by + 0.5) * b, r + 182.0)
            if (d <= r + 181.02) wanted += intArrayOf(bx, by)
        }
    }
    return wanted
}

private fun fillBlock(
    b: LaserBlock,
    source: LaserSource,
    origin: GeoOrigin,
    fetcher: LaserFetcher,
    requests: java.util.concurrent.atomic.AtomicInteger,
    cached: java.util.concurrent.atomic.AtomicInteger,
    bytes: java.util.concurrent.atomic.AtomicLong,
) {
    val e0 = b.bx * LaserData.LASER_BLOCK_M
    val n0 = b.by * LaserData.LASER_BLOCK_M
    val frame = EnuUtmFrame(origin, source.utmZone, e0 + 128, n0 + 128)
    var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
    for ((ce, cn) in listOf(e0 to n0, e0 + 256 to n0, e0 to n0 + 256, e0 + 256 to n0 + 256)) {
        val x = frame.utmX(ce, cn)
        val y = frame.utmY(ce, cn)
        x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
    }
    val ix0 = floor(x0).toInt() - 2
    val iy0 = floor(y0).toInt() - 2
    val ix1 = ceil(x1).toInt() + 2
    val iy1 = ceil(y1).toInt() + 2
    fun get(dom: Boolean): FloatRaster? {
        val url = source.coverageUrl(dom, ix0, iy0, ix1, iy1)
        val resp = fetcher.fetch(url)
        requests.incrementAndGet()
        if (resp.fromCache) cached.incrementAndGet()
        bytes.addAndGet(resp.bytes.size.toLong())
        if (resp.status != 200) return null
        return runCatching { decodeFloatTiff(resp.bytes) }.getOrNull()
    }
    val dgm = get(false) ?: return
    val dom = get(true)
    val n = LaserBlock.N
    for (j in 0 until n) for (i in 0 until n) {
        val e = e0 + i
        val nn = n0 + j
        val x = frame.utmX(e, nn)
        val y = frame.utmY(e, nn)
        val g = dgm.sample(x, y)
        val k = j * n + i
        if (g.isNaN() || g < -500f) continue
        b.dgm[k] = g
        if (dom != null) {
            val s = dom.sample(x, y)
            if (!s.isNaN()) {
                val h = ((s - g) / 0.2f).toInt().coerceIn(0, 255)
                b.ndomQ[k] = h.toByte()
            }
        }
    }
}

/** Abstand zu einem Polygonzug ueber ein Raster der Segmente (wie [TrackLine.nearest], aber ohne WorldTrack). */
internal class PolylineDistance(private val e: DoubleArray, private val n: DoubleArray) {
    private val cell = 64.0
    private val grid = HashMap<Long, IntArray>()

    init {
        val tmp = HashMap<Long, MutableList<Int>>()
        for (i in 0 until e.size - 1) {
            val xa = floor(min(e[i], e[i + 1]) / cell).toInt()
            val xb = floor(max(e[i], e[i + 1]) / cell).toInt()
            val ya = floor(min(n[i], n[i + 1]) / cell).toInt()
            val yb = floor(max(n[i], n[i + 1]) / cell).toInt()
            for (x in xa..xb) for (y in ya..yb) tmp.getOrPut(LaserData.key(x, y)) { ArrayList() }.add(i)
        }
        for ((k, v) in tmp) grid[k] = v.toIntArray()
    }

    /** Abstand von (`x`, `y`) zum Polygonzug; `Double.MAX_VALUE`, wenn er weiter als [maxDist] ist. */
    fun distance(x: Double, y: Double, maxDist: Double): Double {
        var best = Double.MAX_VALUE
        val xa = floor((x - maxDist) / cell).toInt()
        val xb = floor((x + maxDist) / cell).toInt()
        val ya = floor((y - maxDist) / cell).toInt()
        val yb = floor((y + maxDist) / cell).toInt()
        for (gx in xa..xb) for (gy in ya..yb) {
            val list = grid[LaserData.key(gx, gy)] ?: continue
            for (i in list) {
                val ax = e[i]; val ay = n[i]
                val dx = e[i + 1] - ax; val dy = n[i + 1] - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 > 0) (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                val d = hypot(ax + dx * t - x, ay + dy * t - y)
                if (d < best) best = d
            }
        }
        return if (best <= maxDist) best else Double.MAX_VALUE
    }
}
